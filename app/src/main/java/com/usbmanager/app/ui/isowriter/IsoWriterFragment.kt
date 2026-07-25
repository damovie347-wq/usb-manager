package com.usbmanager.app.ui.isowriter

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.usbmanager.app.R
import com.usbmanager.app.core.IsoWriteUpdate
import com.usbmanager.app.databinding.FragmentIsoWriterBinding
import java.util.Locale

class IsoWriterFragment : Fragment() {

    private var _binding: FragmentIsoWriterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: IsoWriterViewModel by viewModels()

    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handlePickedUri(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIsoWriterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonPickIso.setOnClickListener {
            // .iso / .img icin sabit bir MIME turu yok; bu yuzden "*/*" ile
            // aciyoruz ve secim sonrasi uzantiyi kendimiz dogruluyoruz.
            pickIso.launch(arrayOf("*/*"))
        }

        binding.buttonStartWrite.setOnClickListener { confirmAndWrite() }
        binding.buttonCopyHash.setOnClickListener {
            val hash = viewModel.selectedIso.value?.sha256 ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", hash))
            Snackbar.make(binding.root, "SHA-256 kopyalandı", Snackbar.LENGTH_SHORT).show()
        }

        observeViewModel()
        viewModel.refreshTargetDevice()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshTargetDevice()
    }

    private fun handlePickedUri(uri: Uri) {
        val (name, size) = queryNameAndSize(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "iso" && ext != "img") {
            Snackbar.make(binding.root, "Lütfen bir .iso veya .img dosyası seçin", Snackbar.LENGTH_LONG).show()
            return
        }
        requireContext().contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        viewModel.onIsoPicked(uri, name, size)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "seçilen_dosya"
        var size = 0L
        val cursor: Cursor? = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIdx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = it.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = it.getLong(sizeIdx)
            }
        }
        return name to size
    }

    private fun observeViewModel() {
        viewModel.selectedIso.observe(viewLifecycleOwner) { iso ->
            if (iso == null) {
                binding.cardIsoInfo.visibility = View.GONE
                binding.buttonStartWrite.isEnabled = false
                return@observe
            }
            binding.cardIsoInfo.visibility = View.VISIBLE
            binding.textIsoName.text = iso.name
            binding.textIsoSize.text = String.format(
                Locale.getDefault(), "%.2f GB · %s",
                iso.sizeBytes / (1024.0 * 1024 * 1024), iso.kind
            )
            binding.textIsoSha256.text = if (iso.sha256 != null)
                "SHA-256: ${iso.sha256.take(8)}...${iso.sha256.takeLast(6)}"
            else "SHA-256 hesaplanıyor..."
            updateStartButtonState()
        }

        viewModel.targetDevice.observe(viewLifecycleOwner) { device ->
            binding.textTargetDevice.text = if (device != null) {
                val capacityGb = device.capacityBytes / (1024.0 * 1024 * 1024)
                String.format(Locale.getDefault(), "%s — %.2f GB", device.usbDevice.productName ?: "USB", capacityGb)
            } else "Bağlı USB bulunamadı"
            updateStartButtonState()
        }

        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            binding.progressIso.visibility = if (running) View.VISIBLE else View.GONE
            binding.textIsoProgressLabel.visibility = if (running) View.VISIBLE else View.GONE
            binding.buttonStartWrite.isEnabled = !running && canStart()
            binding.buttonPickIso.isEnabled = !running
        }

        viewModel.update.observe(viewLifecycleOwner) { update ->
            when (update) {
                is IsoWriteUpdate.Progress -> {
                    val pct = if (update.totalBytes > 0) ((update.bytesWritten * 100) / update.totalBytes).toInt() else 0
                    binding.progressIso.progress = pct.coerceIn(0, 100)
                    binding.textIsoProgressLabel.text = String.format(
                        Locale.getDefault(), "%%%d — %.1f MB/s", pct, update.instantMBps
                    )
                }
                is IsoWriteUpdate.Finished ->
                    Snackbar.make(binding.root, "Bootable USB oluşturuldu ✅", Snackbar.LENGTH_LONG).show()
                is IsoWriteUpdate.Failed ->
                    Snackbar.make(binding.root, "Hata: ${update.error.message}", Snackbar.LENGTH_LONG).show()
                null -> Unit
            }
        }
    }

    private fun canStart() = viewModel.selectedIso.value != null && viewModel.targetDevice.value != null

    private fun updateStartButtonState() {
        binding.buttonStartWrite.isEnabled = canStart() && viewModel.isRunning.value != true
    }

    private fun confirmAndWrite() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.format_confirm_title)
            .setMessage("Hedef USB üzerindeki TÜM VERİLER silinip yerine seçilen imaj yazılacak. Bu işlem GERİ ALINAMAZ. Devam edilsin mi?")
            .setPositiveButton("Evet, Yaz") { _, _ -> viewModel.startWriting() }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
