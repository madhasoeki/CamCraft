package org.artcraft.camcraft.network

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import java.io.OutputStream

/**
 * Android Open Accessory (AOA v1) protocol constants and helpers matching
 * the desktop Linux `usb_aoa` receiver.
 */
object UsbAccessoryTransport {
    const val MANUFACTURER = "ArtCraft"
    const val MODEL = "CamCraft"
    const val VERSION = "1.0"
    const val MAX_AOA_WRITE_CHUNK = 16_384

    /**
     * Returns `true` when [manufacturer] and [model] match the CamCraft AOA accessory filter.
     */
    fun isCamCraftAccessory(manufacturer: String?, model: String?): Boolean =
        manufacturer == MANUFACTURER && model == MODEL

    /**
     * Returns `true` when [accessory] identifies itself as the CamCraft AOA host.
     */
    fun isCamCraftAccessory(accessory: UsbAccessory?): Boolean =
        accessory != null && isCamCraftAccessory(accessory.manufacturer, accessory.model)

    /**
     * Extracts the [UsbAccessory] parcelable from an incoming USB attachment [intent].
     */
    fun extractAccessoryFromIntent(intent: Intent?): UsbAccessory? {
        if (intent == null) return null
        return IntentCompat.getParcelableExtra(
            intent,
            UsbManager.EXTRA_ACCESSORY,
            UsbAccessory::class.java,
        )
    }

    /**
     * Returns the first attached CamCraft [UsbAccessory] that already has USB permission,
     * or `null` if none is attached or permitted.
     */
    fun findAttachedCamCraftAccessory(context: Context): UsbAccessory? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val accessories = try {
            usbManager.accessoryList
        } catch (_: Exception) {
            null
        } ?: return null

        return accessories.firstOrNull { accessory ->
            isCamCraftAccessory(accessory) && try {
                usbManager.hasPermission(accessory)
            } catch (_: Exception) {
                false
            }
        }
    }
}

/**
 * [OutputStream] wrapper splitting large writes into chunks of at most [maxChunkSize] bytes.
 *
 * Android's kernel `f_accessory` USB gadget driver enforces a 16 KiB bulk buffer ceiling per
 * `write()` on `/dev/usb_accessory`; splitting large H.264 IDR frames keeps every transfer within
 * that limit while preserving exact byte order.
 */
class AoaChunkedOutputStream(
    private val delegate: OutputStream,
    private val maxChunkSize: Int = UsbAccessoryTransport.MAX_AOA_WRITE_CHUNK,
) : OutputStream() {

    init {
        require(maxChunkSize > 0) {
            "maxChunkSize must be > 0, got $maxChunkSize"
        }
    }

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("Invalid slice: off=$off, len=$len, size=${b.size}")
        }
        if (len == 0) return

        var offset = off
        var remaining = len
        while (remaining > 0) {
            val chunk = minOf(remaining, maxChunkSize)
            delegate.write(b, offset, chunk)
            offset += chunk
            remaining -= chunk
        }
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
