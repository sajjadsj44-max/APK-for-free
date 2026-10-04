package com.sajjad.transcripts

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Reads 16-bit PCM WAV in chunks and returns 16 kHz mono floats for whisper. */
class WavReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    private val dataStart: Long
    private val dataLength: Long

    init {
        fun readBytes(n: Int): ByteArray = ByteArray(n).also { raf.readFully(it) }
        fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (String(readBytes(4), Charsets.US_ASCII) != "RIFF") throw IOException("Audio file is not WAV")
        readBytes(4)
        if (String(readBytes(4), Charsets.US_ASCII) != "WAVE") throw IOException("Audio file is not WAV")

        var rate = 0
        var ch = 0
        var bits = 0
        var start = -1L
        var length = 0L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = String(readBytes(4), Charsets.US_ASCII)
            val size = le(readBytes(4)).int.toLong() and 0xFFFFFFFFL
            val bodyPos = raf.filePointer
            if (id == "fmt ") {
                val b = le(readBytes(16))
                val format = b.short.toInt() and 0xFFFF
                ch = b.short.toInt()
                rate = b.int
                b.int; b.short
                bits = b.short.toInt()
                if (format != 1 && format != 0xFFFE) throw IOException("Unsupported WAV encoding ($format)")
            } else if (id == "data") {
                start = bodyPos
                length = min(size, raf.length() - bodyPos)
                break
            }
            raf.seek(bodyPos + size + (size and 1L))
        }
        if (start < 0 || rate <= 0 || ch <= 0) throw IOException("WAV file has no audio data")
        if (bits != 16) throw IOException("WAV must be 16-bit (got $bits-bit)")
        sampleRate = rate
        channels = ch
        dataStart = start
        dataLength = length
    }

    private val frameBytes get() = channels * 2
    val durationSeconds: Double get() = dataLength.toDouble() / frameBytes / sampleRate

    /** Reads [seconds] of audio starting at [fromSeconds]; resamples to 16 kHz mono. */
    fun read(fromSeconds: Double, seconds: Double): FloatArray {
        val firstFrame = (fromSeconds * sampleRate).toLong()
        val totalFrames = dataLength / frameBytes
        val frames = min((seconds * sampleRate).toLong(), totalFrames - firstFrame).toInt()
        if (frames <= 0) return FloatArray(0)
        val bytes = ByteArray(frames * frameBytes)
        raf.seek(dataStart + firstFrame * frameBytes)
        raf.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += buf.short / 32768f
            mono[i] = sum / channels
        }
        return if (sampleRate == 16000) mono else resample(mono, sampleRate, 16000)
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[min(idx, input.size - 1)]
            val b = input[min(idx + 1, input.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    override fun close() = raf.close()
}
