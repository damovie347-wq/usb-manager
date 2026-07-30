package com.usbmanager.app.ui.format

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.core.FileSystemType
import com.usbmanager.app.core.FormatEngine
import com.usbmanager.app.core.FormatMode
import com.usbmanager.app.core.FormatProgress
import com.usbmanager.app.core.FormatResult
import com.usbmanager.app.usb.ScsiRawBlockDevice
import com.usbmanager.app.usb.UsbFileSystemSession
import com.usbmanager.app.usb.UsbMassStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bağlı USB'nin ekranda gösterilecek özeti (ürün adı + kapasite). */
data class ConnectedUsbInfo(
    val usbDevice: UsbDevice,
    val capacityBytes: Long
)

/**
 * Bagli USB aygitini ve biçimlendirme islemini yoneten ViewModel.
 *
 * Ham blok erisimi (kapasite okuma, format yazma) icin libaums DEGIL,
 * dogrudan Android USB Host API uzerinde calisan `ScsiRawBlockDevice`
 * kullanilir -- cunku libaums'un genel API'si bu tur erisim sunmuyor
 * (bkz. ScsiRawBlockDevice.kt basindaki mimari not).
 */
class FormatViewModel(app: Application) : AndroidViewModel(app) {

    private val _connectedDevice = MutableLiveData<ConnectedUsbInfo?>()
    val connectedDevice: LiveData<ConnectedUsbInfo?> = _connectedDevice

    private val _progress = MutableLiveData<FormatProgress?>()
    val progress: LiveData<FormatProgress?> = _progress

    private val _result = MutableLiveData<FormatResult?>()
    val result: LiveData<FormatResult?> = _result

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    /**
     * Bagli USB'yi bulur, izin gerekiyorsa ister, ardindan kapasitesini
     * okumak icin KISA SURELI bir ScsiRawBlockDevice acip hemen kapatir.
     * Asil (uzun suren) baglanti sadece startFormat() sirasinda acilir.
     */
    fun refreshConnectedDevice() {
        val ctx = getApplication<Application>()
        val usbDevice = UsbMassStorageManager.listDevices(ctx).firstOrNull()?.usbDevice
        if (usbDevice == null) {
            _connectedDevice.value = null
            return
        }

        UsbMassStorageManager.requestPermissionIfNeeded(ctx, usbDevice) { granted ->
            if (!granted) {
                _connectedDevice.postValue(null)
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching {
                        // Dosya Yoneticisi/Hiz Testi'nin PAYLASILAN libaums
                        // baglantisi aciksa, HAM erisimden once serbest birak
                        // (ayni arabirime iki baglanti turu AYNI ANDA olamaz).
                        UsbFileSystemSession.releaseForExclusiveAccess()
                        val raw = ScsiRawBlockDevice.open(ctx, usbDevice)
                        val capacity = raw.totalBytes
                        raw.close()
                        ConnectedUsbInfo(usbDevice, capacity)
                    }.getOrNull()
                }
                _connectedDevice.postValue(info)
            }
        }
    }

    /**
     * Format islemini baslatir. TUM agir islem (SCSI acma + FormatEngine)
     * Dispatchers.IO uzerinde calisir; UI thread hicbir asamada bloke
     * OLMAZ, ilerleme SADECE LiveData uzerinden Fragment'a ulasir.
     */
    fun startFormat(fs: FileSystemType, mode: FormatMode) {
        if (_isRunning.value == true) return
        val info = _connectedDevice.value ?: return
        val ctx = getApplication<Application>()

        _isRunning.value = true
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                var opened: ScsiRawBlockDevice? = null
                try {
                    UsbFileSystemSession.releaseForExclusiveAccess()
                    val raw = ScsiRawBlockDevice.open(ctx, info.usbDevice)
                    opened = raw
                    FormatEngine.run(raw, fs, mode) { progress ->
                        _progress.postValue(progress)
                    }
                } catch (t: Throwable) {
                    FormatResult.Failed(t)
                } finally {
                    opened?.close()
                }
            }
            _result.postValue(res)
            _isRunning.postValue(false)
        }
    }
}
