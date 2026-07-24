package com.usbmanager.app.ui.speedtest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.tabs.TabLayout
import com.usbmanager.app.core.SpeedTestEngine
import com.usbmanager.app.core.SpeedTestUpdate
import com.usbmanager.app.databinding.FragmentSpeedTestBinding
import java.util.Locale
import java.util.concurrent.TimeUnit

class SpeedTestFragment : Fragment() {

    private var _binding: FragmentSpeedTestBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SpeedTestViewModel by viewModels()

    private var selectedKind = TestKind.WRITE

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSpeedTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.speedometer.setMaxSpeed(600.0) // USB 3.x icin makul ust sinir
        binding.textTestSize.text = "512 MB"

        binding.tabWriteRead.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedKind = if (tab.position == 0) TestKind.WRITE else TestKind.READ
                resetDisplay()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.buttonStartTest.setOnClickListener {
            if (viewModel.isRunning.value == true) {
                viewModel.stop()
            } else {
                viewModel.start(selectedKind)
            }
        }

        observeViewModel()
        viewModel.connectToFirstAvailableDevice()
    }

    private fun observeViewModel() {
        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            binding.buttonStartTest.text = getString(
                if (running) com.usbmanager.app.R.string.speed_stop
                else com.usbmanager.app.R.string.speed_start
            )
        }

        viewModel.deviceReady.observe(viewLifecycleOwner) { ready ->
            binding.buttonStartTest.isEnabled = ready
        }

        viewModel.update.observe(viewLifecycleOwner) { update ->
            when (update) {
                is SpeedTestUpdate.Progress -> {
                    binding.speedometer.setSpeed(update.instantMBps)
                    binding.textSpeedValue.text = String.format(Locale.getDefault(), "%.1f MB/s", update.instantMBps)
                    binding.textSpeedMbit.text = String.format(
                        Locale.getDefault(), "%.2f Gbit/s",
                        SpeedTestEngine.mbpsToMbits(update.instantMBps) / 1000.0
                    )
                }
                is SpeedTestUpdate.Finished -> {
                    binding.speedometer.setSpeed(update.averageMBps)
                    binding.textSpeedValue.text = String.format(Locale.getDefault(), "%.1f MB/s", update.averageMBps)
                    binding.textTestDuration.text = formatDuration(update.elapsedMs)
                }
                is SpeedTestUpdate.CancelledOrFailed -> {
                    resetDisplay()
                }
                null -> Unit
            }
        }
    }

    private fun resetDisplay() {
        binding.speedometer.setSpeed(0.0)
        binding.textSpeedValue.text = "0.0 MB/s"
        binding.textSpeedMbit.text = "0.00 Gbit/s"
        binding.textTestDuration.text = "00:00:00"
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
