package com.usbmanager.app.ui.format

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.core.FileSystemType
import com.usbmanager.app.core.FormatEngine
import com.usbmanager.app.core.FormatMode
import com.usbmanager.app.core.FormatProgress
import com.usbmanager.app.core.FormatResult
import com.usbmanager.app.usb.UsbMassStorageManager
import com.usbmanager.app.usb.rawDeviceOf
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.UsbMassStorageDevice

/**
 * Bagli USB aygitini ve biçimlendirme islemini yoneten ViewModel.
 *
 * Diger modullerle (FileManagerViewModel, IsoWriterViewModel, SpeedTestViewModel)
 * AYNI deseni izler: AndroidViewModel -> Application context'e ihtiyac
 * duymadan Fragment'tan parametresiz cagrilabilir; USB izin isteme ve
 * RawBlockDevice acma islemleri burada, UI'dan gizlenmis sekilde yapilir.
 */
class FormatViewModel(app: Application) : AndroidViewModel(app) {

    private val _connectedDevice = MutableLiveData<UsbMassStorageDevice?>()
    val connectedDevice: LiveData<UsbMassStorageDevice?> = _connectedDevice

    private val _progress = MutableLiveData<FormatProgress?>()
    val progress: LiveData<FormatProgress?> = _progress

    private val _result = MutableLiveData<FormatResult?>()
    val result: LiveData<FormatResult?> = _result

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    fun refreshConnectedDevice() {
        val ctx = getApplication<Application>()
        _connectedDevice.value = UsbMassStorageManager.listDevices(ctx).firstOrNull()
    }

    /**
     * Format islemini baslatir. USB izni gerekiyorsa once ister, ardindan
     * aygiti baslatip (init) ham blok arayuzunu (RawBlockDevice) acar ve
     * TUM agir islemi FormatEngine icinde Dispatchers.IO uzerinde calistirir.
     * UI thread hicbir asamada bloke OLMAZ; ilerleme SADECE LiveData
     * uzerinden Fragment'a ulasir.
     */
    fun startFormat(fs: FileSystemType, mode: FormatMode) {
        if (_isRunning.value == true) return
        val device = _connectedDevice.value ?: return
        val ctx = getApplication<Application>()

        _isRunning.value = true
        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _isRunning.value = false
                _result.value = FormatResult.Failed(SecurityException("USB erişim izni verilmedi"))
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch {
                val res = runCatching {
                    device.init()
                    val raw = rawDeviceOf(device)
                    FormatEngine.run(raw, fs, mode) { progress ->
                        _progress.postValue(progress)
                    }
                }.getOrElse { FormatResult.Failed(it) }
                _result.postValue(res)
                _isRunning.postValue(false)
            }
        }
    }
}
