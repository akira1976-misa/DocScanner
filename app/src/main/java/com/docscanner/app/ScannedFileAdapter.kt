package com.docscanner.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.docscanner.app.databinding.ItemScannedFileBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

class ScannedFileAdapter(
    private val files: MutableList<ScannedFile>,
    private val onItemClick: (ScannedFile) -> Unit,
    private val onOcrClick: (ScannedFile) -> Unit,
    private val onShareClick: (ScannedFile) -> Unit,
    private val onDeleteClick: (ScannedFile) -> Unit
) : RecyclerView.Adapter<ScannedFileAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())

    // 어댑터 전용 코루틴 스코프
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 썸네일 LRU 캐시 (최대 30개)
    private val thumbCache: LinkedHashMap<String, Bitmap?> =
        object : LinkedHashMap<String, Bitmap?>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) =
                size > 30
        }

    inner class ViewHolder(private val binding: ItemScannedFileBinding)
        : RecyclerView.ViewHolder(binding.root) {

        private var thumbnailJob: Job? = null

        fun bind(file: ScannedFile) {
            // 이전 로딩 취소
            thumbnailJob?.cancel()

            // 텍스트 정보 설정
            binding.tvFileName.text = formatDisplayName(file.name)
            binding.tvFileSize.text = file.fileSizeMb
            binding.tvDate.text = dateFormat.format(Date(file.createdAt))
            binding.tvPageBadge.text = "${file.pageCount}페이지"
            binding.tvPageBadge.visibility =
                if (file.pageCount > 0) View.VISIBLE else View.GONE

            // 플레이스홀더로 초기화
            binding.imgPreview.setImageBitmap(null)
            binding.imgPreview.visibility = View.GONE
            binding.layoutPlaceholder.visibility = View.VISIBLE

            // ── 비동기 썸네일 로드 ──
            val path = file.file.absolutePath
            thumbnailJob = adapterScope.launch {
                // 캐시 확인
                val cached = thumbCache[path]
                if (cached != null) {
                    showBitmap(cached)
                    return@launch
                }

                // IO 스레드에서 디코드
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        if (!file.file.exists()) return@withContext null
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 4  // 1/4 크기로 빠르게 로드
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(path, opts)
                    } catch (e: Exception) { null }
                }

                thumbCache[path] = bitmap
                if (bitmap != null) showBitmap(bitmap)
            }

            // 클릭 이벤트
            binding.root.setOnClickListener { onItemClick(file) }
            binding.imgPreview.setOnClickListener { onItemClick(file) }
            binding.btnOcr.setOnClickListener { onOcrClick(file) }
            binding.btnShare.setOnClickListener { onShareClick(file) }
            binding.btnDelete.setOnClickListener { onDeleteClick(file) }
        }

        private fun showBitmap(bitmap: Bitmap) {
            binding.imgPreview.setImageBitmap(bitmap)
            binding.imgPreview.visibility = View.VISIBLE
            binding.layoutPlaceholder.visibility = View.GONE
        }

        fun cancelLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
        }

        // "문서_20260409_134500" → "문서 2026.04.09 13:45"
        private fun formatDisplayName(name: String): String {
            return try {
                val regex = Regex("(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})")
                val match = regex.find(name)
                if (match != null) {
                    val (y, mo, d, h, mi) = match.destructured
                    "문서 $y.$mo.$d $h:$mi"
                } else name
            } catch (e: Exception) { name }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScannedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    // 뷰 재사용 시 로딩 취소
    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    override fun getItemCount() = files.size

    // 어댑터 종료 시 리소스 정리
    fun destroy() {
        adapterScope.coroutineContext[Job]?.cancel()
        thumbCache.clear()
    }
}
