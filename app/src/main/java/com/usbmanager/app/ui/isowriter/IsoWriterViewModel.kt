package com.usbmanager.app.ui.isowriter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.usbmanager.app.core.IsoWriteUpdate
import com.usbmanager.app.core.IsoWriterEngine
import com.usbmanager.app.usb.UsbMassStorageManager
import com.usbmanager.app.usb.rawDeviceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.UsbMassStorageDevice

data class SelectedIso(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val kind: String,
    val sha256: String?
)

class IsoWriterViewModel(app: Application) : AndroidViewModel(app) {

    private val _selectedIso = MutableLiveData<SelectedIso?>()
    val selectedIso: LiveData<SelectedIso?> = _selectedIso

    private val _targetDevice = MutableLiveData<UsbMassStorageDevice?>()
    val targetDevice: LiveData<UsbMassStorageDevice?> = _targetDevice

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _update = MutableLiveData<IsoWriteUpdate?>()
    val update: LiveData<IsoWriteUpdate?> = _update

    fun refreshTargetDevice() {
        val ctx = getApplication<android.app.Application>()
        _targetDevice.value = UsbMassStorageManager.listDevices(ctx).firstOrNull()
    }

    fun onIsoPicked(uri: Uri, name: String, sizeBytes: Long) {
        val kind = IsoWriterEngine.detectImageKind(name)
        _selectedIso.value = SelectedIso(uri, name, sizeBytes, kind, sha256 = null)

        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<android.app.Application>()
            val hash = runCatching { IsoWriterEngine.computeSha256(ctx, uri) }.getOrNull()
            _selectedIso.postValue(_selectedIso.value?.copy(sha256 = hash))
        }
    }

    fun startWriting() {
        val iso = _selectedIso.value ?: return
        val device = _targetDevice.value ?: return
        val ctx = getApplication<android.app.Application>()

        _isRunning.value = true
        UsbMassStorageManager.requestPermissionIfNeeded(ctx, device.usbDevice) { granted ->
            if (!granted) {
                _isRunning.value = false
                _update.value = IsoWriteUpdate.Failed(SecurityException("USB erişim izni verilmedi"))
                return@requestPermissionIfNeeded
            }
            viewModelScope.launch {
                runCatching {
                    device.init()
                    val raw = rawDeviceOf(device)
                    IsoWriterEngine.writeRawImage(ctx, iso.uri, iso.sizeBytes, raw) { update ->
                        _update.postValue(update)
                    }
                }.onFailure {
                    _update.postValue(IsoWriteUpdate.Failed(it))
                }
                _isRunning.postValue(false)
            }
        }
    }
}
