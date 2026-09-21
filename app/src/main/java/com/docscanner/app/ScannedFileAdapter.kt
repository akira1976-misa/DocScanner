package com.docscanner.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.docscanner.app.databinding.ItemScannedFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScannedFileAdapter(
    private val files: MutableList<ScannedFile>,
    private val onItemClick: (ScannedFile) -> Unit,
    private val onOcrClick: (ScannedFile) -> Unit,
    private val onShareClick: (ScannedFile) -> Unit,
    private val onDeleteClick: (ScannedFile) -> Unit
) : RecyclerView.Adapter<ScannedFileAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())

    // 간단한 썸네일 캐시 (파일 경로 → 썸네일)
    private val thumbCache = mutableMapOf<String, android.graphics.Bitmap?>()

    inner class ViewHolder(private val binding: ItemScannedFileBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: ScannedFile) {
            // 파일명: 날짜 형식으로 보기 좋게 변환
            binding.tvFileName.text = formatDisplayName(file.name)
            binding.tvFileSize.text = file.fileSizeMb
            binding.tvDate.text = dateFormat.format(Date(file.createdAt))

            // 페이지 배지
            binding.tvPageBadge.text = if (file.pageCount > 1)
                "${file.pageCount}페이지" else "1페이지"
            binding.tvPageBadge.visibility =
                if (file.pageCount > 0) View.VISIBLE else View.GONE

            // ── 미리보기 썸네일 로드 ──
            loadThumbnail(file)

            // 클릭 이벤트
            binding.root.setOnClickListener { onItemClick(file) }
            binding.imgPreview.setOnClickListener { onItemClick(file) }
            binding.btnOcr.setOnClickListener { onOcrClick(file) }
            binding.btnShare.setOnClickListener { onShareClick(file) }
            binding.btnDelete.setOnClickListener { onDeleteClick(file) }
        }

        private fun loadThumbnail(file: ScannedFile) {
            val path = file.file.absolutePath

            // 캐시에 있으면 바로 사용
            if (thumbCache.containsKey(path)) {
                val cached = thumbCache[path]
                if (cached != null) {
                    binding.imgPreview.setImageBitmap(cached)
                    binding.imgPreview.visibility = View.VISIBLE
                    binding.layoutPlaceholder.visibility = View.GONE
                } else {
                    showPlaceholder()
                }
                return
            }

            // 파일 존재 여부 확인
            if (!file.file.exists()) {
                thumbCache[path] = null
                showPlaceholder()
                return
            }

            // 저해상도로 빠르게 디코드 (원본의 1/8 크기)
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 4   // 1/4 크기로 디코드
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeFile(path, opts)
                thumbCache[path] = bitmap

                if (bitmap != null) {
                    binding.imgPreview.setImageBitmap(bitmap)
                    binding.imgPreview.visibility = View.VISIBLE
                    binding.layoutPlaceholder.visibility = View.GONE
                } else {
                    showPlaceholder()
                }
            } catch (e: Exception) {
                thumbCache[path] = null
                showPlaceholder()
            }
        }

        private fun showPlaceholder() {
            binding.imgPreview.visibility = View.GONE
            binding.layoutPlaceholder.visibility = View.VISIBLE
        }

        // "문서_20260409_134500" → "2026.04.09 13:45:00"
        private fun formatDisplayName(name: String): String {
            return try {
                val regex = Regex("(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})")
                val match = regex.find(name)
                if (match != null) {
                    val (y, mo, d, h, mi, s) = match.destructured
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

    override fun getItemCount() = files.size

    // 어댑터 제거 시 캐시 정리
    fun clearCache() { thumbCache.clear() }
}
