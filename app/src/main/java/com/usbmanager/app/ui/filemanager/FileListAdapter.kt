package com.usbmanager.app.ui.filemanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.usbmanager.app.R
import com.usbmanager.app.databinding.ItemFileBinding
import java.util.Locale

/**
 * Artik dogrudan `UsbFile` (libaums) DEGIL, kaynak-bagimsiz [BrowseEntry]
 * ile calisir -- boylece HEM FAT32 (libaums) HEM NTFS/exFAT (ExFatReader/
 * NtfsReader) girisleri AYNI listede, AYNI sekilde gosterilebilir (bkz.
 * FileManagerViewModel.kt basindaki BrowseEntry aciklamasi).
 */
class FileListAdapter(
    private val onClick: (BrowseEntry) -> Unit,
    private val onLongClick: (BrowseEntry) -> Boolean,
    private val isSelected: (BrowseEntry) -> Boolean
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private val items = mutableListOf<BrowseEntry>()

    fun submitList(newItems: List<BrowseEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(private val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: BrowseEntry) {
            binding.textFileName.text = entry.name

            // NOT: Klasor icin item sayisini burada HESAPLAMIYORUZ; bu, her
            // satir icin ayri bir USB okumasi (gercek donanim erisimi)
            // tetikler ve listede COK sayida klasor varken ana thread'i uzun
            // sure bloke ederek uygulamanin donmasina yol acabilirdi.
            val meta = if (entry.isDirectory) "Klasör" else formatSize(entry.sizeBytes)
            binding.textFileMeta.text = meta

            binding.imageIcon.setImageResource(
                if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_iso
            )

            binding.root.alpha = if (isSelected(entry)) 0.5f else 1f
            binding.root.setOnClickListener { onClick(entry) }
            binding.root.setOnLongClickListener { onLongClick(entry) }
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
