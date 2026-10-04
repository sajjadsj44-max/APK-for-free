package com.sajjad.transcripts

/** Thin wrapper over the native whisper.cpp library (app/src/main/cpp). */
object WhisperLib {
    init {
        System.loadLibrary("whisperjni")
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeTranscribe(
        ctx: Long, samples: FloatArray, language: String, translate: Boolean, threads: Int
    ): ByteArray?
    @JvmStatic external fun nativeAbort(flag: Boolean)
    @JvmStatic external fun nativeProgress(): Int
    @JvmStatic external fun nativeSystemInfo(): String
}
