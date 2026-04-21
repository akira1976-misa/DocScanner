package com.docscanner.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
            .setScannerMode(SCANNER_MODE_BASE)
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
                val pageCount = result.pdf?.pageCount ?: 1

                val jpegFiles = mutableListOf<File>()
                result.pages?.forEachIndexed { index, page ->
                    val jpegName = "${baseName}_p${index + 1}.jpg"
                    val saved = withContext(Dispatchers.IO) {
                        val raw = contentResolver.openInputStream(page.imageUri)
                            ?.use { BitmapFactory.decodeStream(it) }
                        val cleaned = raw?.let { FingerRemover.removeFingers(it) } ?: raw
                        if (cleaned != null) saveCleanedJpeg(cleaned, jpegName) else null
                    }
                    if (saved != null) jpegFiles.add(saved)
                }

                val pdfUri = result.pdf?.uri
                if (pdfUri != null) {
                    withContext(Dispatchers.IO) { savePdfToDownloads(pdfUri, "$baseName.pdf") }
                }

                val representFile = jpegFiles.firstOrNull()
                if (representFile != null) {
                    val scannedFile = ScannedFile(
                        name = baseName,
                        file = representFile,
                        pageCount = pageCount,
                        createdAt = System.currentTimeMillis(),
                        type = FileType.IMAGE
                    )
                    scannedFiles.add(0, scannedFile)
                    adapter.notifyItemInserted(0)
                    binding.recyclerView.scrollToPosition(0)
                    showMessage("✅ 저장 완료 ($pageCount 페이지)")
                }
                updateEmptyState()
            } catch (e: Exception) {
                showMessage("오류: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    // ── OCR: 이미지에서 텍스트 추출 후 노트 앱으로 전송 ──────────
    private fun runOcr(file: ScannedFile) {
        showLoading(true)
        showMessage("텍스트 인식 중...")
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(file.file.absolutePath)
                }
                if (bitmap == null) { showMessage("이미지를 불러올 수 없습니다."); return@launch }

                val text = withContext(Dispatchers.Default) {
                    OcrHelper.extractText(bitmap)
                }

                if (text.isBlank()) {
                    showMessage("인식된 텍스트가 없습니다.")
                    return@launch
                }

                // 추출된 텍스트를 노트 앱으로 전송
                sendTextToNoteApp(text, file.name)

            } catch (e: Exception) {
                showMessage("텍스트 인식 실패: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun sendTextToNoteApp(text: String, title: String) {
        // 1순위: 삼성노트
        val samsungNoteIntent = packageManager.getLaunchIntentForPackage(
            "com.samsung.android.app.notes"
        )?.apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 2순위: Google Keep
        val keepIntent = packageManager.getLaunchIntentForPackage(
            "com.google.android.keep"
        )?.apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 3순위: 범용 공유 (모든 텍스트 앱)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }

        when {
            samsungNoteIntent != null -> {
                startActivity(samsungNoteIntent)
                showMessage("삼성노트로 전송했습니다 (${text.length}자)")
            }
            keepIntent != null -> {
                startActivity(keepIntent)
                showMessage("Google Keep으로 전송했습니다 (${text.length}자)")
            }
            else -> {
                // 앱 선택창 표시
                startActivity(Intent.createChooser(shareIntent, "텍스트 저장 — 앱 선택"))
            }
        }
    }

    private fun saveCleanedJpeg(bitmap: Bitmap, fileName: String): File? {
        return try {
            val jpegBytes = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }.toByteArray()
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
                contentResolver.openOutputStream(itemUri)?.use { it.write(jpegBytes) }
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
                out.writeBytes(jpegBytes)
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
        } catch (e: Exception) { null }
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
            val uri = getMediaStoreUri(file.file) ?: Uri.fromFile(file.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(Intent.createChooser(intent, "이미지 열기"))
        } catch (e: Exception) {
            showMessage("열기 실패: 갤러리 앱을 확인해 주세요.")
        }
    }

    private fun getMediaStoreUri(file: File): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(file.name), null
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
            val uri = getMediaStoreUri(file.file) ?: Uri.fromFile(file.file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
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
                try {
                    val uri = getMediaStoreUri(file.file)
                    if (uri != null) contentResolver.delete(uri, null, null)
                    else file.file.delete()
                } catch (e: Exception) { file.file.delete() }
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
            SAVE_FOLDER
        )
        if (picturesDir.exists()) {
            picturesDir.listFiles { f ->
                f.extension.lowercase() in listOf("jpg", "jpeg")
            }?.sortedByDescending { it.lastModified() }
             ?.forEach { file ->
                if (scannedFiles.none { it.file.name == file.name }) {
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
        scannedFiles.sortByDescending { it.createdAt }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun setupRecyclerView() {
        adapter = ScannedFileAdapter(
            files = scannedFiles,
            onItemClick  = { file -> openFile(file) },
            onOcrClick   = { file -> runOcr(file) },
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
