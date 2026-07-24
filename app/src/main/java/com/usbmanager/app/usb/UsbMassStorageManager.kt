package com.usbmanager.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.partition.Partition

/**
 * USB Mass Storage aygitlarina ROOT GEREKTIRMEDEN, dogrudan Android USB Host
 * API + SCSI komut seti uzerinden ham (raw) erisim saglayan katman.
 *
 * Bu katman, uygulamanin "Direct I/O", "Low-Level Format" ve "RAW/DD ISO
 * yazma" gereksinimlerinin teknik temelidir: libaums bir Linux blok aygiti
 * (/dev/sdX) ACMADAN, USB bulk-transfer uzerinden dogrudan SCSI
 * READ(10)/WRITE(10) komutlariyla konusur. Boylece Android'in sayfa
 * onbellegi (page cache) tamamen atlanir ve olculen hiz gercek donanim
 * hizidir.
 *
 * ONEMLI (durum notu): libaums acik kaynakli, aktif gelisen bir kutuphanedir
 * (https://github.com/magnusja/libaums). Asagidaki cagrilar `core:0.10.0`
 * surumune gore yazildi; ilk CI derlemesinde kucuk API isim farkliliklari
 * cikarsa, kutuphanenin kendi `app/` ornek projesindeki kullanimla
 * karsilastirarak birebir duzeltin.
 */
object UsbMassStorageManager {

    private const val ACTION_USB_PERMISSION = "com.usbmanager.app.USB_PERMISSION"

    fun listDevices(context: Context): List<UsbMassStorageDevice> =
        UsbMassStorageDevice.getMassStorageDevices(context).toList()

    fun findMassStorageDevice(context: Context, usbDevice: UsbDevice): UsbMassStorageDevice? =
        listDevices(context).firstOrNull { it.usbDevice.deviceId == usbDevice.deviceId }

    /**
     * Kullanicidan USB erisim izni ister (Android USB Host izin dialoglari).
     * Izin zaten verilmisse callback dogrudan true ile cagrilir.
     */
    fun requestPermissionIfNeeded(
        context: Context,
        usbDevice: UsbDevice,
        onResult: (granted: Boolean) -> Unit
    ) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (usbManager.hasPermission(usbDevice)) {
            onResult(true)
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { ctx.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        }

        usbManager.requestPermission(usbDevice, permissionIntent)
    }

    /** Aygiti baslatir (partition tablosunu okur) ve ilk bolumu dondurur. */
    fun openFirstPartition(device: UsbMassStorageDevice): Partition? {
        device.init()
        return device.partitions.firstOrNull()
    }

    fun fileSystemOf(partition: Partition): FileSystem? = partition.fileSystem

    /** Blok boyutu (genelde 512 byte) — hiz testi / zero-fill hizalamasi icin. */
    fun blockSize(device: UsbMassStorageDevice): Int = device.blockSize

    /** Toplam kapasite (byte). */
    fun capacityBytes(device: UsbMassStorageDevice): Long =
        device.blockSize.toLong() * device.blockCount

    /**
     * Aygiti baslatir (SCSI Inquiry/ReadCapacity) ve Format/SpeedTest/
     * IsoWriter motorlarinin kullandigi ham blok arayuzunu (RawBlockDevice)
     * dondurur. Bu, partition/dosya-sistemi katmanini ATLAYIP diskin
     * TAMAMINA (LBA 0 dahil) erisim saglar.
     */
    fun initializedRawDevice(device: UsbMassStorageDevice): RawBlockDevice {
        device.init()
        return rawDeviceOf(device)
    }
}
