package com.jarvis.assistant.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Minimal WAV writer (PCM 16-bit mono). */
object WavUtil {

    fun writeWav(file: File, pcmData: ByteArray, sampleRate: Int): Boolean {
        if (pcmData.isEmpty()) return false
        FileOutputStream(file).use { fos ->
            val header = buildHeader(pcmData.size, sampleRate)
            fos.write(header)
            fos.write(pcmData)
        }
        return file.length() > 44
    }

    private fun buildHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44)
        val bytesPerSample = 2
        val channels = 1
        val byteRate = sampleRate * channels * bytesPerSample

        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        writeString("RIFF")
        writeIntLE(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16)                 // PCM chunk size
        writeShortLE(1)                // PCM format
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(channels * bytesPerSample)
        writeShortLE(16)               // bits per sample
        writeString("data")
        writeIntLE(dataSize)
        return out.toByteArray()
    }
}
