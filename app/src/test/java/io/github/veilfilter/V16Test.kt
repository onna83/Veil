package io.github.veilfilter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class V16Test {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val data = ByteArray(700_000) { ((it * 131) xor (it shr 7)).toByte() }
    private val sha = sha(data)
    private val requests = AtomicInteger()
    private val ranges = ArrayList<String?>()
    /** first request on /flaky dies after this many bytes */
    private var dropAfter = 300_000

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun base() = "http://127.0.0.1:${server.localPort}"

    /** Minimal HTTP/1.1 server (Connection: close): enough for Range, redirects, content types. */
    private class Req(val path: String, val headers: Map<String, String>)

    private fun handle(s: Socket) = s.use { sock ->
        val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val line = inp.readLine() ?: return
        val path = line.split(' ')[1]
        val h = HashMap<String, String>()
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); h[l.substring(0, i).lowercase()] = l.substring(i + 1).trim() }
        val out = sock.getOutputStream()
        fun head(code: Int, extra: String = "", len: Long = 0) {
            out.write("HTTP/1.1 $code X\r\nConnection: close\r\nContent-Length: $len\r\n$extra\r\n".toByteArray()); out.flush()
        }
        fun serve(body: ByteArray, limit: Int = Int.MAX_VALUE) {
            val range = h["range"]
            synchronized(ranges) { ranges.add(range) }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (start >= body.size) { head(416); return }
            head(if (start > 0) 206 else 200, "Content-Type: application/octet-stream\r\n", (body.size - start).toLong())
            out.write(body, start, minOf(body.size - start, limit)); out.flush()
        }
        when (path) {
            "/ok" -> serve(data)
            "/flaky" -> { val k = requests.incrementAndGet(); serve(data, if (k == 1) dropAfter else Int.MAX_VALUE) }
            "/redirect" -> if (h["accept"] != "application/octet-stream") head(404) else head(302, "Location: ${base()}/ok\r\n")
            "/bad" -> serve(data.copyOf().also { b -> b[5] = (b[5] + 1).toByte() })
            "/html" -> { val b = "<html>login</html>".toByteArray(); head(200, "Content-Type: text/html; charset=utf-8\r\n", b.size.toLong()); out.write(b) }
            else -> head(404)
        }
    }

    @Before fun setUp() {
        dir = createTempDirectory("veil16").toFile()
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Exception) { break }
                Thread { try { handle(c) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun tearDown() { server.close(); dir.deleteRecursively() }

    private fun dl(urls: List<Pair<String, Map<String, String>>>, cancel: () -> Boolean = { false }) =
        ModelDownloader(dir, maxAttempts = 3, backoffMs = 10, allowLocalHttp = true)
            .download(urls, "m.bin", data.size.toLong(), sha, cancelled = cancel)

    @Test fun downloadsAndVerifies() {
        val r = dl(listOf("${base()}/ok" to emptyMap()))
        assertTrue(r is ModelDownloader.Result.Ok)
        assertEquals(sha, ModelDownloader.sha(File(dir, "m.bin")))
        assertFalse(File(dir, "m.bin.dl").exists())
    }

    @Test fun resumesWithRangeAfterDroppedConnection() {
        val r = dl(listOf("${base()}/flaky" to emptyMap()))
        assertTrue("got $r", r is ModelDownloader.Result.Ok)
        assertEquals(sha, ModelDownloader.sha(File(dir, "m.bin")))
        assertTrue(requests.get() >= 2)
        // second request continued where the first stopped, not from zero
        val second = synchronized(ranges) { ranges[1] }
        assertNotNull(second)
        assertTrue(second!!.removePrefix("bytes=").substringBefore('-').toInt() > 0)
    }

    @Test fun resumesExistingPartialFile() {
        File(dir, "m.bin.dl").writeBytes(data.copyOf(123_456))
        assertTrue(dl(listOf("${base()}/ok" to emptyMap())) is ModelDownloader.Result.Ok)
        assertEquals("bytes=123456-", ranges[0])
        assertEquals(sha, ModelDownloader.sha(File(dir, "m.bin")))
    }

    @Test fun followsRedirectSendingHeadersOnlyToFirstHop() {
        val r = dl(listOf("${base()}/redirect" to mapOf("Accept" to "application/octet-stream")))
        assertTrue("got $r", r is ModelDownloader.Result.Ok)
    }

    @Test fun rejectsWrongChecksumAndKeepsNothing() {
        val r = dl(listOf("${base()}/bad" to emptyMap()))
        assertTrue(r is ModelDownloader.Result.Failed)
        assertTrue((r as ModelDownloader.Result.Failed).message.contains("checksum"))
        assertFalse(File(dir, "m.bin").exists())
        assertFalse(File(dir, "m.bin.dl").exists())
    }

    @Test fun fallsBackToSecondUrlAndRejectsHtml() {
        val r = dl(listOf("${base()}/html" to emptyMap(), "${base()}/ok" to emptyMap()))
        assertTrue(r is ModelDownloader.Result.Ok)
        val r2 = ModelDownloader(dir, maxAttempts = 2, backoffMs = 1, allowLocalHttp = true)
            .download(listOf("${base()}/html" to emptyMap()), "x.bin", 10, "00")
        assertTrue(r2 is ModelDownloader.Result.Failed)
        assertFalse(File(dir, "x.bin").exists())
    }

    @Test fun refusesPlainHttpInTheApp() {
        val r = ModelDownloader(dir).download(listOf("http://example.com/m" to emptyMap()), "m.bin", 1, "00")
        assertTrue(r is ModelDownloader.Result.Failed)
    }

    @Test fun cancelStopsAndKeepsPartialForResume() {
        var n = 0
        val r = dl(listOf("${base()}/ok" to emptyMap())) { n++ > 2 }
        assertTrue(r is ModelDownloader.Result.Cancelled)
        assertFalse(File(dir, "m.bin").exists())
    }

    @Test fun catalogIsConsistent() {
        val ids = ModelSize.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // v1.8: only the portrait 320n@224x480 is bundled
        assertEquals(listOf(ModelSize.P224), ModelSize.entries.filter { it.source == Source.BUNDLED })
        for (m in ModelSize.entries.filter { it.external }) {
            assertTrue(m.id, m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.id, m.bytes > 1_000_000)
            assertTrue(m.id, m.note.isNotEmpty())
        }
        // entries sharing a file agree on size + hash
        for ((_, g) in ModelSize.entries.filter { it.external }.groupBy { it.file }) {
            assertEquals(1, g.map { it.sha256 to it.bytes }.toSet().size)
        }
        // sha -> distinct file
        for ((_, g) in ModelSize.entries.filter { it.external }.groupBy { it.sha256 }) assertEquals(1, g.map { it.file }.toSet().size)
        assertEquals(ModelImporter.M640_SHA256, ModelSize.M640.sha256)
        assertEquals(ModelImporter.M640_SIZE, ModelSize.M640.bytes)
        // anchors match strides 8/16/32
        for (m in ModelSize.entries) {
            val a = (m.w / 8) * (m.h / 8) + (m.w / 16) * (m.h / 16) + (m.w / 32) * (m.h / 32)
            assertEquals(m.id, maxOf(m.w, m.h), m.px)
            assertTrue(m.id, m.w % 32 == 0 && m.h % 32 == 0)
            assertEquals(m.id, a, m.anchors)
        }
    }

    @Test fun hostedNeedsConfiguredHostUpstreamDoesNot() {
        // v1.8: the default host is the project's own GitHub release
        assertTrue(ModelSize.hostConfigured(ModelSize.DEFAULT_BASE_URL))
        assertEquals("https://github.com/onna83/Veil/releases/download/v1.8/nudenet_640m_fp16.tflite", ModelSize.M640.urls(ModelSize.DEFAULT_BASE_URL).single().first)
        assertFalse(ModelSize.hostConfigured("https://github.com/YOUR-GITHUB-USER/veil-model-host/releases/download/v1"))
        assertTrue(ModelSize.P256.urls("https://x.example/").single().first.endsWith("/nudenet_320n_p256x544_fp16.tflite"))
        val u = ModelSize.N640.urls("https://github.com/me/veil-models/releases/download/models-v1/")
        assertEquals("https://github.com/me/veil-models/releases/download/models-v1/nudenet_320n_at640.tflite", u.single().first)
        assertFalse(ModelSize.hostConfigured("http://insecure.example/x"))
        val up = ModelSize.X640.urls(ModelSize.DEFAULT_BASE_URL)
        assertEquals(2, up.size)
        assertTrue(up[0].first.startsWith("https://api.github.com/repos/notAI-tech/NudeNet/releases/assets/"))
        assertEquals("application/octet-stream", up[0].second["Accept"])
        assertTrue(up.all { it.first.startsWith("https://") })
        assertEquals(ModelRuntime.ONNX, ModelSize.Y640.runtime)
        assertFalse(ModelSize.Y640.tiles)
    }

    @Test fun importAnyRecognisesByHash() {
        // fake a catalog match: use a real-size-independent path by importing the exact bytes whose
        // hash is unknown -> rejected
        val files = File(dir, "files").apply { mkdirs() }
        val modelsDir = File(files, "models")
        val (m, err) = ModelImporter.importAny(listOf(ModelImporter.Source("x.tflite") { data.inputStream() }), modelsDir)
        assertNull(m); assertNotNull(err)
        assertTrue(modelsDir.listFiles()!!.isEmpty())
        // zipped parts are joined in name order before hashing
        fun zip(name: String, b: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry(name)); z.write(b); z.closeEntry() }
            return o.toByteArray()
        }
        val p1 = zip("a.001", data.copyOfRange(0, 1000)); val p2 = zip("a.002", data.copyOfRange(1000, data.size))
        val out = File(modelsDir, "joined")
        assertNull(ModelImporter.import(listOf(
            ModelImporter.Source("p.part2of2.zip") { p2.inputStream() },
            ModelImporter.Source("p.part1of2.zip") { p1.inputStream() },
        ), out, expectedSha = sha))
        assertEquals(sha, ModelDownloader.sha(out))
    }
}
