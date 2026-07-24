package com.usbmanager.app

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.usbmanager.app.databinding.ActivityMainBinding
import com.usbmanager.app.theme.ThemeManager
import com.usbmanager.app.ui.filemanager.FileManagerFragment
import com.usbmanager.app.ui.format.FormatFragment
import com.usbmanager.app.ui.isowriter.IsoWriterFragment
import com.usbmanager.app.ui.settings.SettingsFragment
import com.usbmanager.app.ui.speedtest.SpeedTestFragment
import com.usbmanager.app.usb.UsbMassStorageManager

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // AMOLED tema secildiyse setContentView'DAN ONCE uygulanmali.
        ThemeManager.applyActivityThemeOverride(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.app_name, R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            binding.navView.setCheckedItem(R.id.nav_format)
            showFragment(FormatFragment(), R.id.nav_format)
        }

        handleUsbAttachIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent()
    }

    /**
     * OTG uzerinden USB bellek takildiginda cagrilir.
     *
     * Not: device_filter.xml bilerek "her USB cihazini" yakalayacak sekilde
     * bos birakildi, cunku cogu USB bellek CIHAZ seviyesinde class=0
     * (composite) bildirir; gercek Mass Storage sinifi (8) genellikle
     * INTERFACE seviyesindedir. Bu yuzden gercek suzme burada, interface
     * sinifina bakarak yapilir.
     */
    private fun handleUsbAttachIntent() {
        val action = intent?.action ?: return
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return

        val looksLikeMassStorage = (0 until usbDevice.interfaceCount).any { i ->
            usbDevice.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE
        }
        if (!looksLikeMassStorage) return

        // Kullaniciya sistemin kendi "Bu USB ile USB Manager'i ac?" onayi zaten
        // manifest intent-filter'i sayesinde gosterilir. Burada sadece izin
        // istegini tetikleyip Dosya Yoneticisi ekranina yonlendiriyoruz.
        UsbMassStorageManager.requestPermissionIfNeeded(this, usbDevice) { granted ->
            if (granted) {
                binding.navView.setCheckedItem(R.id.nav_file_manager)
                showFragment(FileManagerFragment(), R.id.nav_file_manager)
            }
        }
    }

    override fun onNavigationItemSelected(item: android.view.MenuItem): Boolean {
        val fragment: Fragment = when (item.itemId) {
            R.id.nav_format -> FormatFragment()
            R.id.nav_file_manager -> FileManagerFragment()
            R.id.nav_speed_test -> SpeedTestFragment()
            R.id.nav_iso_writer -> IsoWriterFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> FormatFragment()
        }
        showFragment(fragment, item.itemId)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showFragment(fragment: Fragment, checkedId: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        title = when (checkedId) {
            R.id.nav_format -> getString(R.string.menu_format)
            R.id.nav_file_manager -> getString(R.string.menu_file_manager)
            R.id.nav_speed_test -> getString(R.string.menu_speed_test)
            R.id.nav_iso_writer -> getString(R.string.menu_iso_writer)
            R.id.nav_settings -> getString(R.string.menu_settings)
            else -> getString(R.string.app_name)
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
