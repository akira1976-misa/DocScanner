package com.docscanner.app

import android.view.LayoutInflater
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

    inner class ViewHolder(private val binding: ItemScannedFileBinding)
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: ScannedFile) {
            binding.tvFileName.text = file.name
            binding.tvFileSize.text = file.fileSizeMb
            binding.tvPageCount.text = if (file.pageCount > 0) "${file.pageCount}페이지" else "이미지"
            binding.tvDate.text = dateFormat.format(Date(file.createdAt))

            binding.root.setOnClickListener { onItemClick(file) }
            binding.btnOcr.setOnClickListener { onOcrClick(file) }
            binding.btnShare.setOnClickListener { onShareClick(file) }
            binding.btnDelete.setOnClickListener { onDeleteClick(file) }
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
}
