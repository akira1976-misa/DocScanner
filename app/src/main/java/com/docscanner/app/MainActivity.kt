package com.docscanner.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.launch
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
            .setScannerMode(SCANNER_MODE_FULL)   // 전체 기능 (자동 감지 + 수동 조절)
            .setResultFormats(RESULT_FORMAT_PDF, RESULT_FORMAT_JPEG)
            .setPageLimit(20)                     // 최대 20페이지
            .setGalleryImportAllowed(true)        // 갤러리에서 가져오기 허용
            .build()

        scanner = GmsDocumentScanning.getClient(options)

        // 스캐너 결과 수신
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
                val fileName = "문서_$timestamp"

                // PDF 저장
                val pdfUri = result.pdf?.uri
                if (pdfUri != null) {
                    val pdfFile = savePdfToInternalStorage(pdfUri, "$fileName.pdf")
                    if (pdfFile != null) {
                        val pageCount = result.pdf?.pageCount ?: 1
                        val scannedFile = ScannedFile(
                            name = fileName,
                            file = pdfFile,
                            pageCount = pageCount,
                            createdAt = System.currentTimeMillis(),
                            type = FileType.PDF
                        )
                        scannedFiles.add(0, scannedFile)
                        adapter.notifyItemInserted(0)
                        binding.recyclerView.scrollToPosition(0)
                        showMessage("PDF 저장 완료: $pageCount 페이지")
                    }
                }

                // JPEG 이미지도 저장 (미리보기용)
                result.pages?.forEachIndexed { index, page ->
                    saveImageToInternalStorage(page.imageUri, "${fileName}_page${index + 1}.jpg")
                }

                updateEmptyState()
            } catch (e: Exception) {
                showMessage("저장 실패: ${e.message}")
            } finally {
                showLoading(false)
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

    // ── 파일 저장 ──────────────────────────────────────────────────
    private fun savePdfToInternalStorage(uri: Uri, fileName: String): File? {
        return try {
            val outputDir = File(filesDir, "scanned_pdfs").also { it.mkdirs() }
            val outputFile = File(outputDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outputFile
        } catch (e: Exception) {
            null
        }
    }

    private fun saveImageToInternalStorage(uri: Uri, fileName: String): File? {
        return try {
            val outputDir = File(filesDir, "scanned_images").also { it.mkdirs() }
            val outputFile = File(outputDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outputFile
        } catch (e: Exception) {
            null
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
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_FILE_PATH, file.file.absolutePath)
            putExtra(PdfViewerActivity.EXTRA_FILE_NAME, file.name)
        }
        startActivity(intent)
    }

    // ── 파일 공유 ──────────────────────────────────────────────────
    private fun shareFile(file: ScannedFile) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file.file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "PDF 공유"))
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
        val dir = File(filesDir, "scanned_pdfs")
        if (dir.exists()) {
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
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
            adapter.notifyDataSetChanged()
        }
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
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
