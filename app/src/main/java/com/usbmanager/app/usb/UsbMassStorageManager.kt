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
 * USB Mass Storage aygitlarini ROOT GEREKTIRMEDEN listeler, izin akisini
 * yonetir ve libaums'un GENEL API'si olan FileSystem/Partition'a erisim
 * saglar. Bu katman Dosya Yoneticisi ve Hiz Testi modulleri tarafindan
 * kullanilir.
 *
 * NOT: Format ve ISO Yazici modulleri -- MBR/FAT tablosu yazma, dd-tarzi
 * ham blok yazma gibi libaums'un DISARI ACMADIGI islemler icin -- bu
 * dosyayi degil, dogrudan `ScsiRawBlockDevice.open()`'i kullanir (bkz.
 * ScsiRawBlockDevice.kt basindaki mimari not).
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

    /**
     * `fileSystemOf()` `null` dondugunde (libaums bu dosya sistemini
     * ANLAMADIGI icin) GERCEK nedeni ogrenmek uzere cagrilir: aygiti KISA
     * SURELIGINE ham (raw) modda acip onyukleme sektorunu okur, sonra
     * HEMEN kapatir. Izin ONCEDEN verilmis olmalidir. Herhangi bir
     * hata/uyumsuzluk durumunda sessizce `null` doner (bu sadece bir
     * TESHIS yardimcisidir, ana akisi asla bozmamali).
     */
    fun sniffUnrecognizedFileSystem(context: Context, usbDevice: UsbDevice): String? = runCatching {
        val raw = ScsiRawBlockDevice.open(context, usbDevice)
        try {
            RawFileSystemSniffer.sniffLabel(raw)
        } finally {
            raw.close()
        }
    }.getOrNull()
}
