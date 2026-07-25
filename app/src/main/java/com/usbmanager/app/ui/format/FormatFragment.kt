package com.usbmanager.app.ui.format

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.usbmanager.app.R
import com.usbmanager.app.core.FileSystemType
import com.usbmanager.app.core.FormatMode
import com.usbmanager.app.core.FormatResult
import com.usbmanager.app.core.FormatStage
import com.usbmanager.app.databinding.FragmentFormatBinding

class FormatFragment : Fragment() {

    private var _binding: FragmentFormatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FormatViewModel by viewModels()

    private var selectedFs: FileSystemType = FileSystemType.FAT32

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFormatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        populateFileSystemChips()
        observeViewModel()

        binding.buttonStartFormat.setOnClickListener { confirmAndStart() }

        viewModel.refreshConnectedDevice()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConnectedDevice()
    }

    private fun populateFileSystemChips() {
        binding.chipGroupFs.removeAllViews()
        FileSystemType.entries.forEach { fs ->
            val chip = Chip(requireContext()).apply {
                text = if (fs.uygulamaDurumu == FileSystemType.SupportLevel.ROADMAP)
                    "${fs.displayName} (yakında)" else fs.displayName
                isCheckable = true
                isChecked = fs == selectedFs
                tag = fs
            }
            chip.setOnClickListener {
                selectedFs = fs
            }
            binding.chipGroupFs.addView(chip)
        }
    }

    private fun observeViewModel() {
        viewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            if (device != null) {
                binding.cardDeviceInfo.visibility = View.VISIBLE
                binding.textDeviceName.text = device.usbDevice.productName ?: getString(R.string.menu_format)
                val capacityGb = device.capacityBytes / (1024.0 * 1024 * 1024)
                binding.textDeviceCapacity.text = String.format("%.2f GB", capacityGb)
            } else {
                binding.cardDeviceInfo.visibility = View.GONE
            }
        }

        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            binding.buttonStartFormat.isEnabled = !running
            binding.progressFormat.visibility = if (running) View.VISIBLE else View.GONE
            binding.textProgressLabel.visibility = if (running) View.VISIBLE else View.GONE
        }

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            progress ?: return@observe
            binding.progressFormat.progress = progress.percent
            binding.textProgressLabel.text = "${progress.stage.label} — %${progress.percent}"
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            when (result) {
                is FormatResult.Success ->
                    showMessage("Biçimlendirme tamamlandı ✅")
                is FormatResult.Unsupported ->
                    showMessage("${result.fs.displayName} bu sürümde henüz desteklenmiyor (yol haritasında). Şimdilik FAT32 kullanılabilir.")
                is FormatResult.Failed ->
                    showMessage("Hata: ${result.error.message ?: result.error::class.simpleName}")
                null -> Unit
            }
        }
    }

    private fun confirmAndStart() {
        val mode = if (binding.radioLowlevel.isChecked) FormatMode.LOW_LEVEL_ZERO_FILL else FormatMode.QUICK

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.format_confirm_title)
            .setMessage(R.string.format_confirm_message)
            .setPositiveButton("Devam Et") { _, _ -> confirmFinal(mode) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun confirmFinal(mode: FormatMode) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.format_confirm_title)
            .setMessage(R.string.format_confirm_final)
            .setPositiveButton("Evet, Biçimlendir") { _, _ ->
                viewModel.startFormat(selectedFs, mode)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showMessage(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
