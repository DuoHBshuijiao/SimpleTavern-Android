package com.simpletavern.core.api

import android.media.MediaDataSource
import java.io.IOException

/** Bytes arrive from the existing bounded speech transport, not a second HTTP connection. */
internal class ProgressiveSpeechSource : MediaDataSource() {
    private val lock = java.lang.Object()
    private var bytes = ByteArray(65_536)
    private var length = 0
    private var finished = false
    private var closed = false
    private var failure: Throwable? = null

    fun append(chunk: ByteArray) = synchronized(lock) {
        if (closed) throw IOException("speech player closed")
        val needed = length + chunk.size
        if (needed > bytes.size) bytes = bytes.copyOf(maxOf(needed, bytes.size * 2))
        chunk.copyInto(bytes, length); length = needed
        lock.notifyAll()
    }

    fun finish(error: Throwable? = null) = synchronized(lock) {
        failure = error; finished = true; lock.notifyAll()
    }

    override fun getSize(): Long = synchronized(lock) { if (finished) length.toLong() else -1L }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(lock) {
        if (size == 0) return@synchronized 0
        while (position >= length && !finished && !closed) lock.wait()
        failure?.let { throw IOException("speech transport failed", it) }
        if (closed) throw IOException("speech player closed")
        if (position >= length) return@synchronized -1
        val count = minOf(size, length - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        count
    }

    override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
}
