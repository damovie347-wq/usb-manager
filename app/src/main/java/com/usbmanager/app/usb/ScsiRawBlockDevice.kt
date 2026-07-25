package com.usbmanager.app.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * libaums'un GENEL (public) API'si -- FileSystem / UsbFile -- ham (raw) blok
 * okuma/yazma SUNMAZ. Bu, gerçek CI derlemesinde ortaya çıkan bir isim
 * uyuşmazlığı değil, kütüphanenin bilinçli bir tasarım sınırıdır (README:
 * "the library currently supports the SCSI command set and the FAT32 file
 * system" -- erişim hep Partition/FileSystem soyutlaması üzerinden).
 *
 * Bu sınıf, libaums'un kendi İÇİNDE kullandığı ama DIŞARI açmadığı şeyi --
 * USB Mass Storage Bulk-Only Transport (BBB) + SCSI READ(10)/WRITE(10) komut
 * setini -- doğrudan Android'in USB Host API'si (UsbDeviceConnection)
 * üzerinde, HİÇBİR üçüncü taraf kütüphaneye bağlı olmadan uygular.
 *
 * Sadece Format ve ISO Yazıcı modülleri bunu kullanır (ham disk erişimi --
 * MBR/FAT tabloları, sektör sektör dd-tarzı yazma -- gerektirdikleri için).
 * Dosya Yöneticisi ve Hız Testi, libaums'un sağlam ve genel FileSystem/
 * UsbFile API'sini kullanmaya devam eder; onlar için değişiklik gerekmedi.
 *
 * Protokol referansı: USB Mass Storage Class Bulk-Only Transport v1.0
 * + SCSI Primary/Block Commands (READ(10)=0x28, WRITE(10)=0x2A,
 * READ CAPACITY(10)=0x25, TEST UNIT READY=0x00).
 *
 * BİLİNEN SINIRLAMALAR (v1 -- gerçek donanımda test edilirken akılda tutun):
 * - Sadece LUN 0 desteklenir (çoklu-LUN kart okuyucular hariç, USB flash
 *   belleklerin neredeyse tamamı tek LUN'dur).
 * - READ(10)/WRITE(10) 32-bit LBA kullanır -> ~2 TB üzeri disklerde çalışmaz
 *   (bu boyuttaki USB flash bellekler pratikte yok denecek kadar azdır).
 * - Tam BBB hata kurtarma (Mass Storage Reset + endpoint clear-halt) bu
 *   sürümde YOK; bir SCSI komutu başarısız olursa IOException fırlatılır.
 *   Sağlıklı/standart bir flash bellekte "happy path" güvenilir çalışır.
 */
class ScsiRawBlockDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    override val blockSizeBytes: Int,
    private val totalBlocks: Long
) : RawBlockDevice {

    override val totalBytes: Long = blockSizeBytes.toLong() * totalBlocks

    private val tagCounter = AtomicInteger(100)
    private val maxBlocksPerCommand: Int =
        (MAX_BYTES_PER_SCSI_COMMAND / blockSizeBytes).coerceAtLeast(1)

    override fun readAt(byteOffset: Long, buffer: ByteBuffer) {
        require(byteOffset % blockSizeBytes == 0L) { "byteOffset sektöre hizalı değil" }
        val totalToRead = buffer.remaining()
        require(totalToRead % blockSizeBytes == 0) { "buffer boyutu sektöre hizalı değil" }

        var lba = byteOffset / blockSizeBytes
        var remainingBlocks = totalToRead / blockSizeBytes

        while (remainingBlocks > 0) {
            val blocksNow = minOf(remainingBlocks, maxBlocksPerCommand.toLong()).toInt()
            val chunkBytes = blocksNow * blockSizeBytes
            val chunkData = ByteArray(chunkBytes)

            val cdb = buildRw10Cdb(opcode = 0x28, lba = lba, blockCount = blocksNow)
            val result = executeCommand(
                connection, inEndpoint, outEndpoint, cdb,
                tag = tagCounter.incrementAndGet(),
                dataLength = chunkBytes, direction = DIR_IN, inData = chunkData
            )
            if (result.status != 0) {
                throw IOException("SCSI READ(10) başarısız (LBA=$lba, status=${result.status})")
            }

            buffer.put(chunkData)
            lba += blocksNow
            remainingBlocks -= blocksNow
        }
    }

    override fun writeAt(byteOffset: Long, buffer: ByteBuffer) {
        require(byteOffset % blockSizeBytes == 0L) { "byteOffset sektöre hizalı değil" }
        val totalToWrite = buffer.remaining()
        require(totalToWrite % blockSizeBytes == 0) { "buffer boyutu sektöre hizalı değil" }

        var lba = byteOffset / blockSizeBytes
        var remainingBlocks = totalToWrite / blockSizeBytes

        while (remainingBlocks > 0) {
            val blocksNow = minOf(remainingBlocks, maxBlocksPerCommand.toLong()).toInt()
            val chunkBytes = blocksNow * blockSizeBytes
            val chunkData = ByteArray(chunkBytes)
            buffer.get(chunkData)

            val cdb = buildRw10Cdb(opcode = 0x2A, lba = lba, blockCount = blocksNow)
            val result = executeCommand(
                connection, inEndpoint, outEndpoint, cdb,
                tag = tagCounter.incrementAndGet(),
                dataLength = chunkBytes, direction = DIR_OUT, outData = chunkData
            )
            if (result.status != 0) {
                throw IOException("SCSI WRITE(10) başarısız (LBA=$lba, status=${result.status})")
            }

            lba += blocksNow
            remainingBlocks -= blocksNow
        }
    }

    /** Bağlantıyı ve USB arabirimini serbest bırakır. İşlem bitince MUTLAKA çağrılmalı. */
    fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    private data class CswResult(val tag: Int, val dataResidue: Int, val status: Int)

    companion object {
        private const val TRANSFER_TIMEOUT_MS = 10_000
        private const val MAX_BYTES_PER_SCSI_COMMAND = 32 * 1024 // 32 KB/komut - genis uyumluluk
        private const val BULK_CHUNK = 16 * 1024 // tek bulkTransfer cagrisi basina guvenli boyut

        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"

        private const val DIR_NONE = 0
        private const val DIR_IN = 1  // aygittan host'a (READ)
        private const val DIR_OUT = 2 // host'tan aygita (WRITE)

        /**
         * Verilen UsbDevice'i açar, Mass Storage arabirimini/bulk uç
         * noktalarını bulur, arabirimi claim eder, TEST UNIT READY ile
         * hazır olduğunu doğrular ve READ CAPACITY(10) ile kapasiteyi
         * okuyarak kullanıma hazır bir ScsiRawBlockDevice döndürür.
         *
         * Kullanıcıdan izin ÖNCEDEN alınmış olmalıdır (bkz.
         * UsbMassStorageManager.requestPermissionIfNeeded).
         */
        fun open(context: Context, usbDevice: UsbDevice): ScsiRawBlockDevice {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!usbManager.hasPermission(usbDevice)) {
                throw SecurityException("USB erişim izni yok")
            }

            var msInterface: UsbInterface? = null
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue

                var candidateIn: UsbEndpoint? = null
                var candidateOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) candidateIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) candidateOut = ep
                }
                if (candidateIn != null && candidateOut != null) {
                    msInterface = iface
                    inEp = candidateIn
                    outEp = candidateOut
                    break
                }
            }

            val iface = msInterface ?: throw IOException("USB Mass Storage arabirimi bulunamadı")
            val inEndpoint = inEp ?: throw IOException("Bulk IN endpoint bulunamadı")
            val outEndpoint = outEp ?: throw IOException("Bulk OUT endpoint bulunamadı")

            val connection = usbManager.openDevice(usbDevice)
                ?: throw IOException("USB aygıtı açılamadı")

            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw IOException("USB arabirimi claim edilemedi")
            }

            try {
                var ready = false
                repeat(3) {
                    if (!ready) ready = testUnitReadyOnce(connection, inEndpoint, outEndpoint)
                }
                if (!ready) throw IOException("USB bellek hazır değil (Test Unit Ready başarısız)")

                val (blockSize, blockCount) = readCapacityOnce(connection, inEndpoint, outEndpoint)
                return ScsiRawBlockDevice(connection, iface, inEndpoint, outEndpoint, blockSize, blockCount)
            } catch (t: Throwable) {
                runCatching { connection.releaseInterface(iface) }
                runCatching { connection.close() }
                throw t
            }
        }

        private fun buildCbw(tag: Int, dataLength: Int, direction: Int, cdb: ByteArray): ByteArray {
            require(cdb.size <= 16)
            val bb = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(CBW_SIGNATURE)
            bb.putInt(tag)
            bb.putInt(dataLength)
            bb.put(if (direction == DIR_IN) 0x80.toByte() else 0x00.toByte())
            bb.put(0) // bCBWLUN = 0 (tek LUN varsayımı)
            bb.put(cdb.size.toByte())
            bb.put(cdb)
            repeat(16 - cdb.size) { bb.put(0) }
            return bb.array()
        }

        private fun buildRw10Cdb(opcode: Int, lba: Long, blockCount: Int): ByteArray {
            require(lba <= 0xFFFFFFFFL) { "LBA 32-bit sınırını aşıyor (>~2TB disk desteklenmiyor)" }
            val cdb = ByteArray(10)
            cdb[0] = opcode.toByte()
            cdb[2] = (lba shr 24).toByte()
            cdb[3] = (lba shr 16).toByte()
            cdb[4] = (lba shr 8).toByte()
            cdb[5] = lba.toByte()
            cdb[7] = (blockCount shr 8).toByte()
            cdb[8] = blockCount.toByte()
            return cdb
        }

        private fun testUnitReadyOnce(conn: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint): Boolean {
            return try {
                executeCommand(conn, inEp, outEp, ByteArray(6), tag = 1, dataLength = 0, direction = DIR_NONE).status == 0
            } catch (e: Exception) {
                false
            }
        }

        private fun readCapacityOnce(conn: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint): Pair<Int, Long> {
            val cdb = ByteArray(10)
            cdb[0] = 0x25 // READ CAPACITY (10)
            val inData = ByteArray(8)
            val result = executeCommand(conn, inEp, outEp, cdb, tag = 2, dataLength = 8, direction = DIR_IN, inData = inData)
            if (result.status != 0) throw IOException("READ CAPACITY(10) başarısız (status=${result.status})")

            val bb = ByteBuffer.wrap(inData).order(ByteOrder.BIG_ENDIAN)
            val lastLba = bb.int.toLong() and 0xFFFFFFFFL
            val blockSize = bb.int
            return blockSize to (lastLba + 1)
        }

        /**
         * Bulk-Only Transport çekirdeği: CBW gönder -> veri fazı -> CSW oku.
         * Hem open() sırasında (nesne henüz yokken) hem de readAt/writeAt
         * içinde (instance üzerinden, connection/inEndpoint/outEndpoint
         * parametre olarak geçirilerek) kullanılan TEK ortak implementasyon.
         */
        private fun executeCommand(
            conn: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint,
            cdb: ByteArray, tag: Int, dataLength: Int, direction: Int,
            outData: ByteArray? = null, inData: ByteArray? = null
        ): CswResult {
            val cbw = buildCbw(tag, dataLength, direction, cdb)
            val sent = conn.bulkTransfer(outEp, cbw, cbw.size, TRANSFER_TIMEOUT_MS)
            if (sent != cbw.size) throw IOException("CBW gönderilemedi (sent=$sent)")

            if (direction == DIR_OUT && dataLength > 0) {
                var offset = 0
                while (offset < dataLength) {
                    val chunk = minOf(BULK_CHUNK, dataLength - offset)
                    val s = conn.bulkTransfer(outEp, outData!!, offset, chunk, TRANSFER_TIMEOUT_MS)
                    if (s != chunk) throw IOException("Bulk OUT eksik/başarısız (sent=$s, expected=$chunk)")
                    offset += chunk
                }
            } else if (direction == DIR_IN && dataLength > 0) {
                var offset = 0
                while (offset < dataLength) {
                    val chunk = minOf(BULK_CHUNK, dataLength - offset)
                    val r = conn.bulkTransfer(inEp, inData!!, offset, chunk, TRANSFER_TIMEOUT_MS)
                    if (r != chunk) throw IOException("Bulk IN eksik/başarısız (read=$r, expected=$chunk)")
                    offset += chunk
                }
            }

            val cswBuf = ByteArray(13)
            val read = conn.bulkTransfer(inEp, cswBuf, 13, TRANSFER_TIMEOUT_MS)
            if (read != 13) throw IOException("CSW okunamadı (read=$read)")

            val bb = ByteBuffer.wrap(cswBuf).order(ByteOrder.LITTLE_ENDIAN)
            val sig = bb.int
            if (sig != CSW_SIGNATURE) throw IOException("Geçersiz CSW imzası")
            val cswTag = bb.int
            val residue = bb.int
            val status = bb.get().toInt() and 0xFF
            return CswResult(cswTag, residue, status)
        }
    }
}
