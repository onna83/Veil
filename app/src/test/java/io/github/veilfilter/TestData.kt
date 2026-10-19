package io.github.veilfilter

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TestData {
    fun bytes(name: String): ByteArray {
        val url = javaClass.classLoader!!.getResource(name) ?: error("missing test resource $name")
        return File(url.toURI()).readBytes()
    }

    fun floats(name: String): FloatArray {
        val b = ByteBuffer.wrap(bytes(name)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    fun lines(name: String): List<String> = String(bytes(name)).lines().filter { it.isNotBlank() }
}
