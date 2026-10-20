package io.github.veilfilter

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Imports the optional 640m model from user-picked files (no network): either
 * the .tflite itself, or the split package veil-model-640m.partNofM.zip (each
 * part fits a 25 MB attachment). Parts are joined in file-name order and the
 * result must match the known SHA-256, so a wrong / damaged / foreign file can
 * never be loaded as a model.
 */
object ModelImporter {
    const val M640_SHA256 = "e5be516c5416522b9869b795e027947b9083d6e333758394a90b170ef6abb073"
    const val M640_SIZE = 52031664L

    class Source(val name: String, val open: () -> InputStream)

    /** @return null on success, else an error message. [dst] is replaced only on success. */
    fun import(sources: List<Source>, dst: File, expectedSha: String = M640_SHA256, onProgress: (Long) -> Unit = {}): String? {
        if (sources.isEmpty()) return "no file selected"
        val ordered = sources.sortedBy { it.name.lowercase() }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            tmp.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                for (s in ordered) {
                    s.open().use { raw ->
                        val zipped = s.name.lowercase().endsWith(".zip")
                        val zin = if (zipped) ZipInputStream(raw.buffered()) else null
                        if (zin != null && zin.nextEntry == null) return "empty zip: ${s.name}"
                        val inp: InputStream = zin ?: raw
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); total += n
                            onProgress(total)
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != expectedSha) {
                tmp.delete()
                return "not the Veil 640m model (checksum mismatch, ${total} bytes from ${ordered.size} file(s)). " +
                    "Select ALL parts, or the .tflite."
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) return "could not save the model"
            return null
        } catch (t: Throwable) {
            tmp.delete()
            return "import failed: ${t.message}"
        }
    }
}
