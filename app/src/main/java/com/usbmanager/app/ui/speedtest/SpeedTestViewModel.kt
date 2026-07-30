package com.usbmanager.app.ui.speedtest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.core.SpeedTestEngine
import com.usbmanager.app.core.SpeedTestUpdate
import com.usbmanager.app.usb.UsbFileSystemSession
import com.usbmanager.app.usb.UsbMassStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.fs.FileSystem

class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {

    // ARTIK disaridan (kullanicinin secimine gore) degistirilebiliyor --
    // bkz. SpeedTestFragment'teki boyut secici (256 MB / 512 MB / 1 GB / 2 GB).
    var testSizeBytes: Long = 512L * 1024 * 1024

    // ARTIK bu ViewModel kendi USB baglantisini AYRICA ACIP KAPAMIYOR;
    // paylasilan `UsbFileSystemSession`i kullaniyor (bkz. o dosyadaki KOK
    // NEDEN aciklamasi -- "her ekran degisiminde USB takildi/cikartildi"
    // bildirimlerinin gercek nedeni buydu).
    private var fileSystem: FileSystem? = null
    private var job: Job? = null

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _update = MutableLiveData<SpeedTestUpdate?>()
    val update: LiveData<SpeedTestUpdate?> = _update

    private val _deviceReady = MutableLiveData(false)
    val deviceReady: LiveData<Boolean> = _deviceReady

    private val _statusMessage = MutableLiveData<String?>()
    val statusMessage: LiveData<String?> = _statusMessage

    /** Fragment, mevcut baglanti YOKKEN (ekrana donuldugunde) yeniden denemek icin kullanir. */
    fun isConnected(): Boolean = fileSystem != null

    /** Fragment, "Otomatik" boyut secenegi icin bagli aygitin kapasitesini okur. */
    fun currentCapacityBytes(): Long? = runCatching { fileSystem?.capacity }.getOrNull()

    fun connectToFirstAvailableDevice() {
        val ctx = getApplication<android.app.Application>()
        val device = UsbMassStorageManager.listDevices(ctx).firstOrNull()
        if (device == null) {
            fileSystem = null
            _deviceReady.value = false
            // ONCEKI DAVRANIS: burada hicbir aciklama verilmiyordu, "Testi
            // Baslat" tusu SESSIZCE devre disi kaliyordu. Artik neden
            // baslatilamadigini soyluyoruz.
            _statusMessage.postValue("Bağlı USB depolama bulunamadı.")
            return
        }

        // PAYLASILAN oturumda AYNI aygita zaten acik bir baglanti varsa,
        // arabirimi tekrar claim/release ETMEDEN dogrudan onu kullan (bkz.
        // UsbFileSystemSession.kt basindaki KOK NEDEN aciklamasi).
        UsbFileSystemSession.existingFor(device.usbDevice)?.let { fs ->
            fileSystem = fs
            _deviceReady.value = true
            return
        }

        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _deviceReady.value = false
                _statusMessage.postValue("USB erişim izni verilmedi")
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch(Dispatchers.IO) {
                val fs = runCatching {
                    UsbMassStorageManager.openFirstPartition(device)
                        ?.let { p -> UsbMassStorageManager.fileSystemOf(p) }
                }.getOrNull()

                if (fs == null) {
                    // KOK NEDEN: libaums FAT32 disinda (exFAT/NTFS) bir dosya
                    // sistemini ANLAMIYOR (bkz. FileManagerViewModel'deki ayni
                    // notlar). Hiz testinin "algilamiyor" gibi gorunmesinin
                    // gercek nedeni cogunlukla budur; bunu acikca soyluyoruz.
                    runCatching { device.close() }
                    val detected = UsbMassStorageManager.sniffUnrecognizedFileSystem(ctx, device.usbDevice)
                    _statusMessage.postValue(
                        if (detected != null)
                            "USB bellek algılandı ama dosya sistemi $detected. Hız Testi şu an yalnızca FAT32'yi destekliyor; Biçimlendir ekranından FAT32'ye çevirebilirsiniz."
                        else
                            "USB bellek algılandı ama üzerinde okunabilir bir bölüm/dosya sistemi bulunamadı."
                    )
                } else {
                    UsbFileSystemSession.adopt(device.usbDevice, device, fs)
                }

                fileSystem = fs
                _deviceReady.postValue(fs != null)
            }
        }
    }

    /** CrystalDiskMark tarzi: TEK TUS ile yazma + okuma testini sirayla baslatir. */
    fun start() {
        val fs = fileSystem ?: return
        _isRunning.value = true
        job = viewModelScope.launch {
            SpeedTestEngine.runFullTest(fs, testSizeBytes) { _update.postValue(it) }
            _isRunning.postValue(false)
        }
    }

    fun stop() {
        job?.cancel()
        _isRunning.value = false
    }

    // NOT: burada artik `onCleared()` icinde baglantiyi KAPATMIYORUZ -- bkz.
    // FileManagerViewModel'deki ayni not.
}
