package com.usbmanager.app.ui.speedtest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.usbmanager.app.R
import com.usbmanager.app.core.AppPrefs
import com.usbmanager.app.core.SpeedTestEngine
import com.usbmanager.app.core.SpeedTestUpdate
import com.usbmanager.app.core.TestPhase
import com.usbmanager.app.databinding.FragmentSpeedTestBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class SpeedTestFragment : Fragment() {

    private var _binding: FragmentSpeedTestBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SpeedTestViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpeedTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.speedometer.setMaxSpeed(600.0) // USB 3.x icin makul ust sinir
        binding.speedometer.animateChanges = AppPrefs.animationsEnabled(requireContext())

        // KULLANICI ISTEGI: test dosyasi boyutu ARTIK SABIT 512 MB DEGIL,
        // secilebilir (yavas bir disk icin daha kucuk/hizli bir test de
        // yapilabilsin). "Otomatik" secildiginde, bagli USB'nin kapasitesine
        // gore MAKUL bir boyut hesaplanir (bkz. applyAutoSize()).
        applySizeSelection(viewModel.testSizeBytes)
        binding.chipGroupTestSize.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip_size_auto -> applyAutoSize()
                R.id.chip_size_256 -> applySizeSelection(256L * 1024 * 1024)
                R.id.chip_size_512 -> applySizeSelection(512L * 1024 * 1024)
                R.id.chip_size_1024 -> applySizeSelection(1024L * 1024 * 1024)
                R.id.chip_size_2048 -> applySizeSelection(2048L * 1024 * 1024)
            }
        }

        // KULLANICI ISTEGI: CrystalDiskMark tarzi TEK TUS -> yazma + okuma
        // OTOMATIK VE SIRAYLA calisir; ayri "yazma testi" / "okuma testi"
        // secimi YOK.
        binding.buttonStartTest.setOnClickListener {
            if (viewModel.isRunning.value == true) {
                viewModel.stop()
            } else {
                resetResultsOnly()
                viewModel.start()
            }
        }

        observeViewModel()
        resetDisplay()
        // NOT: ilk baglanti denemesi burada DEGIL, asagidaki onResume()
        // icinde yapiliyor (bkz. FileManagerFragment'taki ayni not) -- iki
        // eszamanli USB baglanti denemesinin birbirini kesintiye ugratmasini
        // onlemek icin.
    }

    override fun onResume() {
        super.onResume()
        // ONCEKI DAVRANIS: baglanti SADECE ekran ilk acildiginda bir kez
        // denenirdi. Kullanici USB'yi bu ekrana GELMEDEN ONCE ya da baglanti
        // ilk seferde basarisiz olduktan SONRA takarsa, ekrana tekrar donene
        // kadar (ki bu da yeni bir Fragment orneği YARATMADIGI icin) hicbir
        // yeniden deneme olmuyordu. Sadece HENUZ BAGLI DEGILKEN yeniden
        // deniyoruz ki calisan bir testin ortasinda baglantiyi SIFIRLAMAYALIM.
        if (!viewModel.isConnected()) {
            viewModel.connectToFirstAvailableDevice()
        }
    }

    private fun observeViewModel() {
        viewModel.statusMessage.observe(viewLifecycleOwner) { msg ->
            msg ?: return@observe
            com.google.android.material.snackbar.Snackbar.make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
        }
        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            binding.buttonStartTest.text = getString(
                if (running) R.string.speed_stop else R.string.speed_start
            )
            binding.chipGroupTestSize.isEnabled = !running
            for (i in 0 until binding.chipGroupTestSize.childCount) {
                binding.chipGroupTestSize.getChildAt(i).isEnabled = !running
            }
            if (!running) {
                binding.buttonStartTest.isEnabled = viewModel.deviceReady.value == true
            }
        }

        viewModel.deviceReady.observe(viewLifecycleOwner) { ready ->
            if (viewModel.isRunning.value != true) {
                binding.buttonStartTest.isEnabled = ready
                binding.textPhase.text = getString(
                    if (ready) R.string.speed_phase_idle else R.string.speed_phase_no_device
                )
            }
            // Aygit yeni baglandiginda/kapasitesi artik bilindiginde, "Otomatik"
            // secili ise onerilen boyutu GERCEK kapasiteye gore yeniden hesapla.
            if (ready && binding.chipGroupTestSize.checkedChipId == R.id.chip_size_auto) {
                applyAutoSize()
            }
        }

        viewModel.update.observe(viewLifecycleOwner) { update ->
            when (update) {
                is SpeedTestUpdate.Progress -> {
                    binding.textPhase.text = getString(
                        if (update.phase == TestPhase.WRITE) R.string.speed_phase_write
                        else R.string.speed_phase_read
                    )
                    binding.speedometer.setSpeed(update.instantMBps)
                    binding.textSpeedValue.text = String.format(Locale.getDefault(), "%.1f MB/s", update.instantMBps)
                    binding.textSpeedMbit.text = String.format(
                        Locale.getDefault(), "%.2f Gbit/s",
                        SpeedTestEngine.mbpsToMbits(update.instantMBps) / 1000.0
                    )
                }
                is SpeedTestUpdate.PhaseFinished -> {
                    val text = String.format(Locale.getDefault(), "%.1f MB/s", update.averageMBps)
                    if (update.phase == TestPhase.WRITE) {
                        binding.textWriteResult.text = text
                    } else {
                        binding.textReadResult.text = text
                    }
                }
                is SpeedTestUpdate.AllFinished -> {
                    binding.textPhase.text = getString(R.string.speed_phase_done)
                    binding.textWriteResult.text = String.format(Locale.getDefault(), "%.1f MB/s", update.writeAverageMBps)
                    binding.textReadResult.text = String.format(Locale.getDefault(), "%.1f MB/s", update.readAverageMBps)
                    binding.textTestDuration.text = formatDuration(update.totalElapsedMs)
                    binding.speedometer.setSpeed(update.readAverageMBps)
                    binding.textSpeedValue.text = String.format(Locale.getDefault(), "%.1f MB/s", update.readAverageMBps)
                }
                is SpeedTestUpdate.CancelledOrFailed -> {
                    binding.textPhase.text = getString(
                        if (viewModel.deviceReady.value == true) R.string.speed_phase_idle
                        else R.string.speed_phase_no_device
                    )
                    binding.speedometer.setSpeed(0.0)
                    binding.textSpeedValue.text = "0.0 MB/s"
                    binding.textSpeedMbit.text = "0.00 Gbit/s"
                }
                null -> Unit
            }
        }
    }

    private fun applySizeSelection(bytes: Long) {
        viewModel.testSizeBytes = bytes
        binding.textTestSize.text = formatSize(bytes)
    }

    /**
     * "Otomatik": bagli USB'nin TOPLAM KAPASITESine gore MAKUL bir test
     * boyutu onerir -- kucuk/yavas bir bellekte 512 MB'lik sabit bir test
     * hem cok uzun surebilir hem de kapasitenin cok buyuk bir kismini
     * kaplayabilir. Kaba kural: kapasitenin ~%2'si, 64 MB ile 2 GB arasinda
     * sinirlanir ve 64 MB'in katlarina yuvarlanir. Kapasite HENUZ
     * bilinmiyorsa (aygit hazir degilse) varsayilan 512 MB'de kalir.
     */
    private fun applyAutoSize() {
        val capacity = viewModel.currentCapacityBytes()
        val bytes = if (capacity != null && capacity > 0) {
            val step = 64L * 1024 * 1024
            val raw = (capacity / 50).coerceIn(64L * 1024 * 1024, 2048L * 1024 * 1024)
            (raw / step) * step
        } else {
            512L * 1024 * 1024
        }
        viewModel.testSizeBytes = bytes
        binding.textTestSize.text = "${formatSize(bytes)} (${getString(R.string.speed_test_size_auto)})"
    }

    private fun formatSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
        String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    } else {
        String.format(Locale.getDefault(), "%d MB", bytes / (1024 * 1024))
    }

    private fun resetResultsOnly() {
        binding.textWriteResult.text = "—"
        binding.textReadResult.text = "—"
        binding.textTestDuration.text = "00:00:00"
    }

    private fun resetDisplay() {
        binding.speedometer.setSpeed(0.0)
        binding.textSpeedValue.text = "0.0 MB/s"
        binding.textSpeedMbit.text = "0.00 Gbit/s"
        resetResultsOnly()
        binding.textPhase.text = getString(
            if (viewModel.deviceReady.value == true) R.string.speed_phase_idle
            else R.string.speed_phase_no_device
        )
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
