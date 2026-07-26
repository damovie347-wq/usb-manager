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
        binding.textTestSize.text = "512 MB"

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
        viewModel.connectToFirstAvailableDevice()
    }

    private fun observeViewModel() {
        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            binding.buttonStartTest.text = getString(
                if (running) R.string.speed_stop else R.string.speed_start
            )
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
