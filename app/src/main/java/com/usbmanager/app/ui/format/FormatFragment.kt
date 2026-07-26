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
            val isRoadmap = fs.uygulamaDurumu == FileSystemType.SupportLevel.ROADMAP
            val chip = Chip(requireContext()).apply {
                text = if (isRoadmap) "${fs.displayName} (henüz yok)" else fs.displayName
                isCheckable = true
                isChecked = fs == selectedFs
            }
            chip.setOnClickListener {
                if (isRoadmap) {
                    // ONCEKI DAVRANIS: kullanici bu chip'i secebiliyor, sonra
                    // ancak "İŞLEME BAŞLA" tusuna basip iki onay dialogunu
                    // gectikten SONRA bir Snackbar ile "desteklenmiyor"
                    // mesaji goruyordu -- bu "kirik/yaricalismis" bir his
                    // veriyordu. Simdi tıklanır tıklanmaz NEDEN henuz
                    // olmadigini ACIKCA anlatiyoruz ve secimi degistirmiyoruz.
                    chip.isChecked = (selectedFs == fs)
                    showRoadmapInfo(fs)
                } else {
                    selectedFs = fs
                }
            }
            binding.chipGroupFs.addView(chip)
        }
    }

    private fun showRoadmapInfo(fs: FileSystemType) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${fs.displayName} henüz yok")
            .setMessage(
                "${fs.displayName} için gerçek ve güvenilir bir biçimlendirici yazmak, " +
                    "diskin ham veri yapılarını (dizin/meta veri tabloları vb.) sıfırdan " +
                    "doğru şekilde oluşturmayı gerektiren büyük ve riskli bir mühendislik " +
                    "işidir — yanlış yazılmış bir dosya sistemi, USB belleğinizin " +
                    "bilgisayarınızda okunamaz hale gelmesine yol açabilir.\n\n" +
                    "Bu yüzden şu an yalnızca FAT32 ve exFAT güvenle destekleniyor; " +
                    "${fs.displayName} yol haritasında yer alıyor."
            )
            .setPositiveButton("Anladım", null)
            .show()
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
                    showMessage("${result.fs.displayName} bu sürümde henüz desteklenmiyor (yol haritasında). Şimdilik FAT32 veya exFAT kullanabilirsiniz.")
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
