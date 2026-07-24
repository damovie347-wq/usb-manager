package com.usbmanager.app.ui.filemanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.usbmanager.app.R
import com.usbmanager.app.databinding.ItemFileBinding
import me.jahnen.libaums.core.fs.UsbFile
import java.text.SimpleDateFormat
import java.util.Locale

class FileListAdapter(
    private val onClick: (UsbFile) -> Unit,
    private val onLongClick: (UsbFile) -> Boolean,
    private val isSelected: (UsbFile) -> Boolean
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private val items = mutableListOf<UsbFile>()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    fun submitList(newItems: List<UsbFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        holder.bind(file)
    }

    override fun getItemCount() = items.size

    inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: UsbFile) {
            binding.textFileName.text = file.name

            val meta = if (file.isDirectory) {
                runCatching { "${file.listFiles().size} öğe" }.getOrDefault("Klasör")
            } else {
                formatSize(runCatching { file.length }.getOrDefault(0L))
            }
            binding.textFileMeta.text = meta

            binding.imageIcon.setImageResource(
                if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_iso
            )

            binding.root.alpha = if (isSelected(file)) 0.5f else 1f
            binding.root.setOnClickListener { onClick(file) }
            binding.root.setOnLongClickListener { onLongClick(file) }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
    }
}
