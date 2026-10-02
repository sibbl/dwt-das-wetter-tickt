package net.sibbl.dwt.data.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.RadarFrameReference
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Lossless, already colorized frames survive RAM eviction and process restarts.
 * Identity includes the source asset, layer, timestamp and rendering format.
 * Only this cache's files are pruned; raw ZIPs and user settings are untouched.
 */
internal class PreparedFrameDiskCache(
    private val directory: File,
    private val maxBytes: Long = 128L * 1024 * 1024,
    private val maxEntries: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val entries = LinkedHashMap<String, File>(16, 0.75f, true)
    // UI/prefetch readiness checks must never wait for disk IO or PNG encoding.
    private val available = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.extension == "frame" }
            ?.sortedBy { it.lastModified() }?.forEach {
                if (clock() - it.lastModified() > MAX_AGE_MILLIS) it.delete()
                else { entries[it.name] = it; available.add(it.name) }
            }
        trim()
    }

    fun contains(reference: RadarFrameReference): Boolean = available.contains(key(reference))
    @Synchronized fun byteCount(): Long = entries.values.sumOf { it.length() }

    @Synchronized fun read(reference: RadarFrameReference): RadarBitmapFrame? {
        val name = key(reference)
        val file = entries[name] ?: return null
        return try {
            check(file.length() in 37..(MAX_PNG_BYTES + 36))
            val frame = DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readInt() == MAGIC)
                val south = input.readDouble(); val west = input.readDouble()
                val north = input.readDouble(); val east = input.readDouble()
                val bytes = input.readBytes()
                check(bytes.size <= MAX_PNG_BYTES)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                check(options.outWidth in 1..4096 && options.outHeight in 1..4096)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: error("Invalid prepared frame")
                RadarBitmapFrame(reference, bitmap, GeoBounds(GeoPoint(south, west), GeoPoint(north, east)))
            }
            file.setLastModified(clock())
            frame
        } catch (_: Exception) {
            entries.remove(name); available.remove(name); file.delete(); null
        }
    }

    @Synchronized fun write(frame: RadarBitmapFrame) {
        val name = key(frame.reference)
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        try {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeDouble(frame.bounds.southWest.latitude)
                output.writeDouble(frame.bounds.southWest.longitude)
                output.writeDouble(frame.bounds.northEast.latitude)
                output.writeDouble(frame.bounds.northEast.longitude)
                check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            if (temp.length() > MAX_PNG_BYTES || temp.length() > maxBytes) return
            check(temp.renameTo(target))
            target.setLastModified(clock()); entries[name] = target; available.add(name)
            trim()
        } catch (_: Exception) {
            // A full/unavailable cache must not prevent displaying a loaded frame.
        } finally { temp.delete() }
    }

    private fun trim() {
        var bytes = entries.values.sumOf { it.length() }
        val iterator = entries.entries.iterator()
        while ((bytes > maxBytes || entries.size > maxEntries) && iterator.hasNext()) {
            val file = iterator.next().value
            bytes -= file.length(); file.delete(); available.remove(file.name); iterator.remove()
        }
    }

    private fun key(reference: RadarFrameReference): String = MessageDigest.getInstance("SHA-256")
        .digest(("render-v1|" + reference.toString()).toByteArray())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') } + ".frame"

    private companion object {
        const val MAGIC = 0x44575431
        const val MAX_PNG_BYTES = 8L * 1024 * 1024
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }
}
