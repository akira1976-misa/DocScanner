package com.docscanner.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.docscanner.app.databinding.ActivityPdfViewerBinding
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }

    private lateinit var filePath: String
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "문서"

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = fileName
            setDisplayHomeAsUpEnabled(true)
        }

        loadPdf()
    }

    // PDF를 Google Docs Viewer를 통해 WebView에서 표시
    // (실제 기기에서는 직접 URI로 열거나 PdfRenderer 사용 가능)
    private fun loadPdf() {
        val file = File(filePath)
        if (!file.exists()) {
            finish()
            return
        }

        // WebView 설정으로 PDF 렌더링
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
            }
            webViewClient = WebViewClient()
        }

        // FileProvider URI를 통해 직접 열기 시도
        openPdfWithExternalApp(file)
    }

    private fun openPdfWithExternalApp(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                finish()
            } else {
                // PDF 뷰어 앱이 없으면 공유 다이얼로그
                sharePdf(file)
            }
        } catch (e: Exception) {
            sharePdf(file)
        }
    }

    private fun sharePdf(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "PDF 열기"))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_share -> {
                val file = File(filePath)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file
                )
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "PDF 공유"
                    )
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
