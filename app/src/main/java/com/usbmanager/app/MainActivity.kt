package com.usbmanager.app

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.usbmanager.app.core.AppPrefs
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

    override fun onNewIntent(intent: Intent) {
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
     *
     * KOK NEDEN ("tema degistirince USB sokulup cikarilmis gibi davraniyor"):
     * `Activity.recreate()` (orn. tema degisiminde) `onCreate()`'i, Activity'nin
     * O ANDA sahip oldugu Intent'i KORUYARAK tekrar calistirir. Bu fonksiyon
     * eskiden intent'i "tuketmiyordu" -- yani kullanici Dosya Yoneticisi'ne
     * bir USB-takildi bildirimiyle girdikten SONRA (mesela Ayarlar'a gecip)
     * temayi degistirdiginde, `onCreate()` YENIDEN calisiyor, `intent.action`
     * HALA ACTION_USB_DEVICE_ATTACHED oldugu icin bu fonksiyon USB izin/
     * baglanti akisini SIFIRDAN tekrar tetikliyor ve ekrani zorla Dosya
     * Yoneticisi'ne atiyordu -- goruntude USB'nin o an sokulup takilmis gibi
     * gorunmesine yol aciyordu. Duzeltme: bir intent BIR KEZ islendikten
     * sonra `setIntent()` ile "tuketilir" (ACTION_MAIN'e cevrilir), boylece
     * sonraki her `onCreate()` (recreate dahil) onu bir daha islemez.
     */
    private fun handleUsbAttachIntent() {
        val currentIntent = intent ?: return
        if (currentIntent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            currentIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        // Bu intent'i HEMEN "tuket" -- asagidaki izin kontrolu ASENKRON
        // olabilir (sistem dialoglu), ama biz bu FIZIKSEL takilma olayini
        // TEKRAR islemeyecegimizi simdiden garanti altina aliyoruz (bkz.
        // yukaridaki KOK NEDEN notu).
        setIntent(Intent(currentIntent).apply { action = Intent.ACTION_MAIN })

        if (usbDevice == null) return
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
        val transaction = supportFragmentManager.beginTransaction()
        // Ayarlar > Animasyonlar kapaliysa, ekran gecislerinde HICBIR animasyon
        // oynatilmaz (eskiden bu tercih hicbir yerde okunmuyordu).
        if (AppPrefs.animationsEnabled(this)) {
            transaction.setCustomAnimations(
                android.R.anim.fade_in, android.R.anim.fade_out
            )
        }
        transaction
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
