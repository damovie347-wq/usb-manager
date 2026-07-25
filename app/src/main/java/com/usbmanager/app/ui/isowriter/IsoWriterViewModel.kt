package com.usbmanager.app.ui.isowriter

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.core.IsoWriteUpdate
import com.usbmanager.app.core.IsoWriterEngine
import com.usbmanager.app.usb.ScsiRawBlockDevice
import com.usbmanager.app.usb.UsbMassStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectedIso(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val kind: String,
    val sha256: String?
)

/** Hedef USB'nin ekranda gösterilecek özeti (ürün adı + kapasite). */
data class TargetUsbInfo(
    val usbDevice: UsbDevice,
    val capacityBytes: Long
)

/**
 * ISO/IMG secimini ve RAW/DD yazma islemini yoneten ViewModel.
 *
 * Ham blok yazimi icin libaums DEGIL, dogrudan Android USB Host API
 * uzerinde calisan `ScsiRawBlockDevice` kullanilir -- cunku libaums'un
 * genel API'si bu tur erisim sunmuyor (bkz. ScsiRawBlockDevice.kt
 * basindaki mimari not).
 */
class IsoWriterViewModel(app: Application) : AndroidViewModel(app) {

    private val _selectedIso = MutableLiveData<SelectedIso?>()
    val selectedIso: LiveData<SelectedIso?> = _selectedIso

    private val _targetDevice = MutableLiveData<TargetUsbInfo?>()
    val targetDevice: LiveData<TargetUsbInfo?> = _targetDevice

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _update = MutableLiveData<IsoWriteUpdate?>()
    val update: LiveData<IsoWriteUpdate?> = _update

    fun refreshTargetDevice() {
        val ctx = getApplication<Application>()
        val usbDevice = UsbMassStorageManager.listDevices(ctx).firstOrNull()?.usbDevice
        if (usbDevice == null) {
            _targetDevice.value = null
            return
        }

        UsbMassStorageManager.requestPermissionIfNeeded(ctx, usbDevice) { granted ->
            if (!granted) {
                _targetDevice.postValue(null)
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = ScsiRawBlockDevice.open(ctx, usbDevice)
                        val capacity = raw.totalBytes
                        raw.close()
                        TargetUsbInfo(usbDevice, capacity)
                    }.getOrNull()
                }
                _targetDevice.postValue(info)
            }
        }
    }

    fun onIsoPicked(uri: Uri, name: String, sizeBytes: Long) {
        val kind = IsoWriterEngine.detectImageKind(name)
        _selectedIso.value = SelectedIso(uri, name, sizeBytes, kind, sha256 = null)

        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val hash = runCatching { IsoWriterEngine.computeSha256(ctx, uri) }.getOrNull()
            _selectedIso.postValue(_selectedIso.value?.copy(sha256 = hash))
        }
    }

    fun startWriting() {
        val iso = _selectedIso.value ?: return
        val target = _targetDevice.value ?: return
        val ctx = getApplication<Application>()

        _isRunning.value = true
        viewModelScope.launch {
            var opened: ScsiRawBlockDevice? = null
            try {
                withContext(Dispatchers.IO) {
                    val raw = ScsiRawBlockDevice.open(ctx, target.usbDevice)
                    opened = raw
                    IsoWriterEngine.writeRawImage(ctx, iso.uri, iso.sizeBytes, raw) { update ->
                        _update.postValue(update)
                    }
                }
            } catch (t: Throwable) {
                _update.postValue(IsoWriteUpdate.Failed(t))
            } finally {
                opened?.close()
            }
            _isRunning.postValue(false)
        }
    }
}
