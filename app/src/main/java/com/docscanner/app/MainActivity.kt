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

    override fun onDestroy() {
        super.onDestroy()
        adapter.destroy()  // 어댑터 코루틴 + 캐시 정리
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
                val pageCount = result.pdf?.pageCount ?: result.pages?.size ?: 1

                val jpegFiles = mutableListOf<File>()
                result.pages?.forEachIndexed { index, page ->
                    val jpegName = "${baseName}_p${String.format("%02d", index + 1)}.jpg"
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
                    withContext(Dispatchers.IO) {
                        savePdfToDownloads(pdfUri, "$baseName.pdf")
                    }
                }

                val representFile = jpegFiles.firstOrNull()
                if (representFile != null) {
                    val scannedFile = ScannedFile(
                        name         = baseName,
                        file         = representFile,
                        allPageFiles = jpegFiles,
                        pageCount    = pageCount,
                        createdAt    = System.currentTimeMillis(),
                        type         = FileType.IMAGE
                    )
                    scannedFiles.add(0, scannedFile)
                    adapter.notifyItemInserted(0)
                    binding.recyclerView.scrollToPosition(0)
                    showMessage("✅ 저장 완료 — $pageCount 페이지")
                }
                updateEmptyState()
            } catch (e: Exception) {
                showMessage("오류: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    // ── 파일 열기 ──────────────────────────────────────────────────
    private fun openFile(file: ScannedFile) {
        if (file.allPageFiles.size > 1) {
            // 여러 페이지: PDF로 열기
            val pdfFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$SAVE_FOLDER/${file.name}.pdf"
            )
            if (pdfFile.exists()) { openPdfFile(pdfFile); return }
        }
        // 단일 페이지: 이미지로 열기
        try {
            val uri = getMediaStoreUri(file.file) ?: run {
                // MediaStore에 없으면 FileProvider로 시도
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file.file
                )
            }
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }, "이미지 열기"
            ))
        } catch (e: Exception) {
            showMessage("열기 실패: 갤러리 앱을 확인해 주세요.")
        }
    }

    private fun openPdfFile(pdfFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", pdfFile
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }, "PDF 열기"
            ))
        } catch (e: Exception) {
            showMessage("PDF 뷰어 앱을 설치해 주세요.")
        }
    }

    // ── OCR: 모든 페이지 인식 후 노트 앱 전송 ────────────────────
    private fun runOcr(file: ScannedFile) {
        showLoading(true)
        lifecycleScope.launch {
            try {
                val totalPages = file.allPageFiles.size
                val allText = StringBuilder()

                file.allPageFiles.forEachIndexed { index, pageFile ->
                    // 진행 상황 표시
                    showMessage("텍스트 인식 중... (${ index + 1}/$totalPages 페이지)")

                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(pageFile.absolutePath)
                    } ?: return@forEachIndexed

                    val pageText = withContext(Dispatchers.Default) {
                        OcrHelper.extractText(bitmap)
                    }

                    if (pageText.isNotBlank()) {
                        if (totalPages > 1) allText.append("── ${index + 1}페이지 ──\n")
                        allText.append(pageText).append("\n\n")
                    }
                }

                val finalText = allText.toString().trim()
                if (finalText.isBlank()) {
                    showMessage("인식된 텍스트가 없습니다.")
                    return@launch
                }
                sendTextToNoteApp(finalText, file.name)

            } catch (e: Exception) {
                showMessage("텍스트 인식 실패: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    // ── 노트 앱으로 텍스트 전송 ───────────────────────────────────
    private fun sendTextToNoteApp(text: String, title: String) {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }

        val samsungPkg = "com.samsung.android.app.notes"
        val keepPkg    = "com.google.android.keep"

        when {
            isAppInstalled(samsungPkg) -> {
                try {
                    startActivity(Intent(baseIntent).apply { setPackage(samsungPkg) })
                    showMessage("삼성노트로 전송 완료 (${text.length}자)")
                } catch (e: Exception) {
                    // 삼성노트 실패 시 선택창으로 대체
                    startActivity(Intent.createChooser(baseIntent, "텍스트 저장"))
                }
            }
            isAppInstalled(keepPkg) -> {
                try {
                    startActivity(Intent(baseIntent).apply { setPackage(keepPkg) })
                    showMessage("Google Keep으로 전송 완료 (${text.length}자)")
                } catch (e: Exception) {
                    startActivity(Intent.createChooser(baseIntent, "텍스트 저장"))
                }
            }
            else -> {
                startActivity(Intent.createChooser(baseIntent, "텍스트 저장 — 앱 선택"))
            }
        }
    }

    private fun isAppInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) { false }

    // ── 공유 버튼 ──────────────────────────────────────────────────
    private fun shareFile(file: ScannedFile) {
        try {
            if (file.allPageFiles.size > 1) {
                // 여러 페이지: PDF 공유
                val pdfFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$SAVE_FOLDER/${file.name}.pdf"
                )
                if (pdfFile.exists()) {
                    val uri = try {
                        androidx.core.content.FileProvider.getUriForFile(
                            this, "${packageName}.fileprovider", pdfFile)
                    } catch (e: Exception) { Uri.fromFile(pdfFile) }

                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "PDF 공유"
                    ))
                    return
                }
            }
            // 단일 페이지: 이미지 공유
            val uri = getMediaStoreUri(file.file) ?: run {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file.file)
            }
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "공유"
            ))
        } catch (e: Exception) {
            showMessage("공유 실패: ${e.message}")
        }
    }

    // ── 삭제 버튼 ──────────────────────────────────────────────────
    private fun deleteFile(file: ScannedFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("'${file.name}'\n${file.pageCount}페이지를 모두 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                val index = scannedFiles.indexOf(file)
                file.allPageFiles.forEach { pageFile ->
                    try {
                        val uri = getMediaStoreUri(pageFile)
                        if (uri != null) contentResolver.delete(uri, null, null)
                        else pageFile.delete()
                    } catch (e: Exception) { pageFile.delete() }
                }
                // PDF도 함께 삭제
                val pdfFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$SAVE_FOLDER/${file.name}.pdf"
                )
                if (pdfFile.exists()) pdfFile.delete()

                scannedFiles.removeAt(index)
                adapter.notifyItemRemoved(index)
                updateEmptyState()
                showMessage("삭제되었습니다.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 기존 파일 불러오기 ─────────────────────────────────────────
    private fun loadExistingFiles() {
        val picturesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SAVE_FOLDER
        )
        if (!picturesDir.exists()) { updateEmptyState(); return }

        val allFiles = picturesDir.listFiles { f ->
            f.extension.lowercase() in listOf("jpg", "jpeg")
        } ?: run { updateEmptyState(); return }

        // 파일명의 _p01, _p02 등을 제거해서 그룹화
        val grouped = allFiles.groupBy { file ->
            file.nameWithoutExtension.replace(Regex("_p\\d+$"), "")
        }

        grouped.forEach { (baseName, pageFiles) ->
            if (scannedFiles.none { it.name == baseName }) {
                val sortedPages = pageFiles.sortedBy { it.name }
                scannedFiles.add(ScannedFile(
                    name         = baseName,
                    file         = sortedPages.first(),
                    allPageFiles = sortedPages,
                    pageCount    = sortedPages.size,
                    createdAt    = sortedPages.first().lastModified(),
                    type         = FileType.IMAGE
                ))
            }
        }

        scannedFiles.sortByDescending { it.createdAt }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    // ── MediaStore URI 조회 ────────────────────────────────────────
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

    private fun setupRecyclerView() {
        adapter = ScannedFileAdapter(
            files         = scannedFiles,
            onItemClick   = { file -> openFile(file) },
            onOcrClick    = { file -> runOcr(file) },
            onShareClick  = { file -> shareFile(file) },
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
