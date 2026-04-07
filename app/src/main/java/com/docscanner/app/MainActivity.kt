package com.docscanner.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.docscanner.app.databinding.ActivityMainBinding
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: GmsDocumentScanner
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var adapter: ScannedFileAdapter
    private val scannedFiles = mutableListOf<ScannedFile>()
    private val SAVE_FOLDER = "DocScanner"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScanner()
        setupRecyclerView()
        setupClickListeners()
        loadExistingFiles()
    }

    private fun setupScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(SCANNER_MODE_FULL)
            .setResultFormats(RESULT_FORMAT_PDF, RESULT_FORMAT_JPEG)
            .setPageLimit(20)
            .setGalleryImportAllowed(true)
            .build()
        scanner = GmsDocumentScanning.getClient(options)
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                processScanResult(scanResult)
            } else {
                showMessage("스캔이 취소되었습니다.")
            }
        }
    }

    private fun processScanResult(result: GmsDocumentScanningResult?) {
        if (result == null) { showMessage("스캔 결과를 가져올 수 없습니다."); return }
        showLoading(true)
        lifecycleScope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val baseName = "문서_$timestamp"
                val pdfUri = result.pdf?.uri
                val pageCount = result.pdf?.pageCount ?: 1
                var savedPdf: File? = null

                if (pdfUri != null) {
                    savedPdf = withContext(Dispatchers.IO) {
                        savePdfToDownloads(pdfUri, "$baseName.pdf")
                    }
                }

                val jpegFiles = mutableListOf<File>()
                result.pages?.forEachIndexed { index, page ->
                    val jpegName = "${baseName}_p${index + 1}.jpg"
                    val saved = withContext(Dispatchers.IO) {
                        saveJpegToGallery(page.imageUri, jpegName)
                    }
                    if (saved != null) jpegFiles.add(saved)
                }

                val representFile = jpegFiles.firstOrNull() ?: savedPdf
                if (representFile != null) {
                    val scannedFile = ScannedFile(
                        name = baseName,
                        file = representFile,
                        pageCount = pageCount,
                        createdAt = System.currentTimeMillis(),
                        type = if (jpegFiles.isNotEmpty()) FileType.IMAGE else FileType.PDF
                    )
                    scannedFiles.add(0, scannedFile)
                    adapter.notifyItemInserted(0)
                    binding.recyclerView.scrollToPosition(0)
                    showMessage("✅ 저장 완료 ($pageCount 페이지) — 갤러리: DocScanner 앨범")
                }
                updateEmptyState()
            } catch (e: Exception) {
                showMessage("오류: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun saveJpegToGallery(uri: Uri, fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = contentResolver.insert(collection, values) ?: return null
                contentResolver.openOutputStream(itemUri)?.use { out ->
                    contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "$SAVE_FOLDER/$fileName"
                )
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    SAVE_FOLDER
                ).also { it.mkdirs() }
                val out = File(dir, fileName)
                contentResolver.openInputStream(uri)?.use { it.copyTo(out.outputStream()) }
                android.media.MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(out.absolutePath), arrayOf("image/jpeg"), null
                )
                out
            }
        } catch (e: Exception) { null }
    }

    private fun savePdfToDownloads(uri: Uri, fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = contentResolver.insert(collection, values) ?: return null
                contentResolver.openOutputStream(itemUri)?.use { out ->
                    contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$SAVE_FOLDER/$fileName"
                )
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SAVE_FOLDER
                ).also { it.mkdirs() }
                val out = File(dir, fileName)
                contentResolver.openInputStream(uri)?.use { it.copyTo(out.outputStream()) }
                out
            }
        } catch (e: Exception) {
            try {
                val dir = File(filesDir, "scanned_pdfs").also { it.mkdirs() }
                val out = File(dir, fileName)
                contentResolver.openInputStream(uri)?.use { it.copyTo(out.outputStream()) }
                out
            } catch (e2: Exception) { null }
        }
    }

    private fun startScanning() {
        showLoading(true)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                showLoading(false)
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                showLoading(false)
                showMessage("스캐너를 시작할 수 없습니다: ${e.message}")
            }
    }

    private fun openFile(file: ScannedFile) {
        try {
            val isImage = file.type == FileType.IMAGE ||
                file.file.extension.lowercase() in listOf("jpg", "jpeg", "png")
            val uri = if (isImage) {
                getMediaStoreUri(file.file) ?: Uri.fromFile(file.file)
            } else {
                try {
                    androidx.core.content.FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", file.file)
                } catch (e: Exception) { Uri.fromFile(file.file) }
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isImage) "image/jpeg" else "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(Intent.createChooser(intent,
                if (isImage) "이미지 열기 — 갤러리 / 삼성노트" else "PDF 열기"))
        } catch (e: Exception) {
            showMessage("열기 실패: 해당 파일을 열 수 있는 앱이 없습니다.")
        }
    }

    private fun getMediaStoreUri(file: File): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            val args = arrayOf(file.name)
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun shareFile(file: ScannedFile) {
        try {
            val isImage = file.type == FileType.IMAGE ||
                file.file.extension.lowercase() in listOf("jpg", "jpeg", "png")
            val uri = if (isImage) {
                getMediaStoreUri(file.file) ?: Uri.fromFile(file.file)
            } else {
                try {
                    androidx.core.content.FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", file.file)
                } catch (e: Exception) { Uri.fromFile(file.file) }
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isImage) "image/jpeg" else "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "공유"))
        } catch (e: Exception) {
            showMessage("공유 실패: ${e.message}")
        }
    }

    private fun deleteFile(file: ScannedFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("'${file.name}'을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                val index = scannedFiles.indexOf(file)
                file.file.delete()
                scannedFiles.removeAt(index)
                adapter.notifyItemRemoved(index)
                updateEmptyState()
                showMessage("삭제되었습니다.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadExistingFiles() {
        val picturesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SAVE_FOLDER)
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SAVE_FOLDER)
        val internalDir = File(filesDir, "scanned_pdfs")

        if (picturesDir.exists()) {
            picturesDir.listFiles { f ->
                f.extension.lowercase() in listOf("jpg", "jpeg")
            }?.forEach { file ->
                if (scannedFiles.none { it.file.absolutePath == file.absolutePath }) {
                    scannedFiles.add(ScannedFile(
                        name = file.nameWithoutExtension,
                        file = file,
                        pageCount = 1,
                        createdAt = file.lastModified(),
                        type = FileType.IMAGE
                    ))
                }
            }
        }

        listOf(downloadDir, internalDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles { f -> f.extension == "pdf" }?.forEach { file ->
                    if (scannedFiles.none { it.file.absolutePath == file.absolutePath }) {
                        scannedFiles.add(ScannedFile(
                            name = file.nameWithoutExtension,
                            file = file,
                            pageCount = 0,
                            createdAt = file.lastModified(),
                            type = FileType.PDF
                        ))
                    }
                }
            }
        }

        scannedFiles.sortByDescending { it.createdAt }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupRecyclerView() {
        adapter = ScannedFileAdapter(
            files = scannedFiles,
            onItemClick = { file -> openFile(file) },
            onShareClick = { file -> shareFile(file) },
            onDeleteClick = { file -> deleteFile(file) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupClickListeners() {
        binding.fabScan.setOnClickListener { startScanning() }
        binding.btnScanEmpty.setOnClickListener { startScanning() }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.fabScan.isEnabled = !show
    }

    private fun updateEmptyState() {
        val isEmpty = scannedFiles.isEmpty()
        binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showMessage(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
