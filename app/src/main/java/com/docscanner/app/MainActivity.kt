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

    // 저장 폴더: Downloads/DocScanner
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

    // ── ML Kit 문서 스캐너 초기화 ──────────────────────────────────
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

    // ── 스캔 결과 처리 ─────────────────────────────────────────────
    private fun processScanResult(result: GmsDocumentScanningResult?) {
        if (result == null) {
            showMessage("스캔 결과를 가져올 수 없습니다.")
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val fileName = "문서_$timestamp.pdf"

                val pdfUri = result.pdf?.uri
                if (pdfUri != null) {
                    val pageCount = result.pdf?.pageCount ?: 1

                    // ── Downloads/DocScanner 폴더에 저장 ──
                    val savedFile = withContext(Dispatchers.IO) {
                        savePdfToDownloads(pdfUri, fileName)
                    }

                    if (savedFile != null) {
                        val scannedFile = ScannedFile(
                            name = fileName.removeSuffix(".pdf"),
                            file = savedFile,
                            pageCount = pageCount,
                            createdAt = System.currentTimeMillis(),
                            type = FileType.PDF
                        )
                        scannedFiles.add(0, scannedFile)
                        adapter.notifyItemInserted(0)
                        binding.recyclerView.scrollToPosition(0)
                        showMessage("✅ 저장 완료 → Downloads/DocScanner/$fileName")
                    } else {
                        showMessage("저장에 실패했습니다.")
                    }
                }

                updateEmptyState()
            } catch (e: Exception) {
                showMessage("오류: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    // ── Downloads/DocScanner 폴더에 PDF 저장 ──────────────────────
    private fun savePdfToDownloads(uri: Uri, fileName: String): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 이상: MediaStore 사용
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                val itemUri = contentResolver.insert(collection, values) ?: return null

                contentResolver.openOutputStream(itemUri)?.use { output ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        input.copyTo(output)
                    }
                }

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)

                // 실제 File 경로로 변환 (목록 표시용)
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SAVE_FOLDER
                ).also { it.mkdirs() }
                File(downloadDir, fileName)

            } else {
                // Android 9 이하: 직접 파일 쓰기
                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    SAVE_FOLDER
                ).also { it.mkdirs() }

                val outputFile = File(downloadDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                outputFile
            }
        } catch (e: Exception) {
            // Downloads 실패 시 앱 내부 저장소로 폴백
            try {
                val fallbackDir = File(filesDir, "scanned_pdfs").also { it.mkdirs() }
                val fallbackFile = File(fallbackDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    fallbackFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                showMessage("내부 저장소에 저장됨")
                fallbackFile
            } catch (e2: Exception) {
                null
            }
        }
    }

    // ── 스캔 시작 ──────────────────────────────────────────────────
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

    // ── RecyclerView 설정 ──────────────────────────────────────────
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

    // ── 파일 열기 ──────────────────────────────────────────────────
    private fun openFile(file: ScannedFile) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file.file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "PDF 열기"))
        } catch (e: Exception) {
            showMessage("PDF 뷰어 앱이 없습니다.")
        }
    }

    // ── 파일 공유 ──────────────────────────────────────────────────
    private fun shareFile(file: ScannedFile) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "PDF 공유"))
        } catch (e: Exception) {
            showMessage("공유 실패: ${e.message}")
        }
    }

    // ── 파일 삭제 ──────────────────────────────────────────────────
    private fun deleteFile(file: ScannedFile) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("'${file.name}'을(를) 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                val index = scannedFiles.indexOf(file)
                if (file.file.delete()) {
                    scannedFiles.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    updateEmptyState()
                    showMessage("삭제되었습니다.")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 기존 파일 불러오기 ─────────────────────────────────────────
    private fun loadExistingFiles() {
        // Downloads/DocScanner 폴더 먼저 확인
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SAVE_FOLDER
        )
        // 내부 저장소 폴더도 확인 (이전 버전 호환)
        val internalDir = File(filesDir, "scanned_pdfs")

        listOf(downloadDir, internalDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles { f -> f.extension == "pdf" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { file ->
                        if (scannedFiles.none { it.file.absolutePath == file.absolutePath }) {
                            scannedFiles.add(
                                ScannedFile(
                                    name = file.nameWithoutExtension,
                                    file = file,
                                    pageCount = 0,
                                    createdAt = file.lastModified(),
                                    type = FileType.PDF
                                )
                            )
                        }
                    }
            }
        }

        scannedFiles.sortByDescending { it.createdAt }
        adapter.notifyDataSetChanged()
        updateEmptyState()
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
