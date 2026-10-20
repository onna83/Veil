package io.github.veilfilter

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * User-initiated model download: HTTPS only, resumable (HTTP Range), retried
 * with backoff, and verified against a hard-coded size + SHA-256 before the
 * file is renamed into place. Nothing else in Veil touches the network.
 */
class ModelDownloader(
    private val dir: File,
    private val maxAttempts: Int = 5,
    private val backoffMs: Long = 1000L,
    /** tests use a localhost http server */
    private val allowLocalHttp: Boolean = false,
) {
    sealed class Result {
        object Ok : Result()
        object Cancelled : Result()
        class Failed(val message: String) : Result()
    }

    fun download(
        urls: List<Pair<String, Map<String, String>>>,
        fileName: String, size: Long, sha256: String,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Result {
        if (urls.isEmpty()) return Result.Failed("no download source configured")
        for ((u, _) in urls) if (!allowed(u)) return Result.Failed("refusing non-HTTPS URL: $u")
        dir.mkdirs()
        val dst = File(dir, fileName)
        val part = File(dir, "$fileName.dl")
        var lastErr = "unknown error"
        var attempt = 0
        var shaFailures = 0
        while (attempt < maxAttempts) {
            for ((url, headers) in urls) {
                if (cancelled()) return Result.Cancelled
                try {
                    fetch(url, headers, part, size, onProgress, cancelled)
                    if (cancelled()) return Result.Cancelled
                    if (part.length() != size) { lastErr = "incomplete (${part.length()} of $size bytes)"; continue }
                    val got = sha(part)
                    if (got != sha256) {
                        part.delete(); shaFailures++
                        lastErr = "checksum mismatch (got ${got.take(12)}…)"
                        if (shaFailures >= 2) return Result.Failed("$lastErr – file at the source is not the expected model")
                        continue
                    }
                    if (dst.exists()) dst.delete()
                    if (!part.renameTo(dst)) return Result.Failed("could not save $fileName")
                    return Result.Ok
                } catch (e: IOException) {
                    lastErr = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            attempt++
            if (attempt < maxAttempts) {
                val wait = backoffMs shl (attempt - 1)
                val until = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < until) { if (cancelled()) return Result.Cancelled; Thread.sleep(50) }
            }
        }
        return Result.Failed(lastErr)
    }

    private fun allowed(u: String) = u.startsWith("https://") ||
        (allowLocalHttp && (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")))

    /** One request (following redirects by hand so Range survives cross-host hops). */
    private fun fetch(
        url0: String, headers: Map<String, String>, part: File, size: Long,
        onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean,
    ) {
        if (part.length() > size) part.delete()
        if (part.length() == size) return
        var url = url0
        var first = true
        var hops = 0
        while (true) {
            val have = part.length()
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Veil-model-download")
            if (first) for ((k, v) in headers) c.setRequestProperty(k, v)
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    url = URL(URL(url), loc).toString()
                    if (!allowed(url)) throw IOException("redirect to non-HTTPS URL")
                    first = false
                    if (++hops > 6) throw IOException("too many redirects")
                    continue
                }
                if (code == 416) { if (have == size) return; part.delete(); throw IOException("range not satisfiable") }
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                val ct = c.contentType ?: ""
                if (ct.startsWith("text/html")) throw IOException("got a web page instead of the file (login / rate limit?)")
                val append = code == 206 && have > 0
                var done = if (append) have else 0L
                c.inputStream.use { inp ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            if (cancelled()) return
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done > size) throw IOException("more data than expected")
                            if (done - lastReport >= 256 * 1024 || done == size) { lastReport = done; onProgress(done, size) }
                        }
                    }
                }
                return
            } finally { c.disconnect() }
        }
    }

    companion object {
        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
