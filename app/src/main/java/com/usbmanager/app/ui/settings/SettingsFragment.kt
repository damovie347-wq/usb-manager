package com.usbmanager.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.usbmanager.app.BuildConfig
import com.usbmanager.app.databinding.FragmentSettingsBinding
import com.usbmanager.app.theme.ThemeManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences("usb_manager_prefs", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setInitialThemeSelection()

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.radioThemeLight.id -> ThemeManager.Mode.LIGHT
                binding.radioThemeDark.id -> ThemeManager.Mode.DARK
                binding.radioThemeAmoled.id -> ThemeManager.Mode.AMOLED
                else -> ThemeManager.Mode.SYSTEM
            }
            ThemeManager.setMode(requireContext(), mode)
            // Tema anlik degisebilsin diye Activity'yi yeniden olustur.
            requireActivity().recreate()
        }

        binding.switchAnimations.isChecked = prefs.getBoolean("animations_enabled", true)
        binding.switchAnimations.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("animations_enabled", checked) }
        }

        binding.switchHaptics.isChecked = prefs.getBoolean("haptics_enabled", true)
        binding.switchHaptics.setOnCheckedChangeListener { _, checked ->
            prefs.edit { putBoolean("haptics_enabled", checked) }
        }

        binding.textAbout.text = buildString {
            append("USB Manager v${BuildConfig.VERSION_NAME}\n")
            append("Evrensel USB & Depolama Yöneticisi\n\n")
            append("%100 çevrimdışı çalışır. Hiçbir veri internete gönderilmez.\n")
            append("Root gerektirmez — USB erişimi Android USB Host API + libaums ile sağlanır.")
        }
    }

    private fun setInitialThemeSelection() {
        val id = when (ThemeManager.currentMode(requireContext())) {
            ThemeManager.Mode.LIGHT -> binding.radioThemeLight.id
            ThemeManager.Mode.DARK -> binding.radioThemeDark.id
            ThemeManager.Mode.AMOLED -> binding.radioThemeAmoled.id
            ThemeManager.Mode.SYSTEM -> binding.radioThemeSystem.id
        }
        binding.radioGroupTheme.check(id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
