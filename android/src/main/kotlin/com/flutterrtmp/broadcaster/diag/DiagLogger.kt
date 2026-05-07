package com.flutterrtmp.broadcaster.diag

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagLogger {
    private const val TAG = "DiagLogger"
    private const val MAX_SIZE = 256 * 1024L
    private val lock = Any()
    private var logFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.filesDir, "rtmp_diag.log")
        }
        log("SESSION", "plugin attached model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
    }

    fun log(tag: String, msg: String, t: Throwable? = null) {
        Log.d(TAG, "[$tag] $msg")
        writeToFile(buildLine(tag, msg, t))
    }

    fun logError(code: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$code] $msg", t)
        writeToFile(buildLine("ERROR/$code", msg, t))
    }

    fun read(): String {
        val file = logFile ?: return "(DiagLogger not initialized)"
        val rotated = File(file.parent, "${file.name}.1")
        return buildString {
            if (rotated.exists()) append(rotated.readText())
            if (file.exists()) append(file.readText())
        }.ifEmpty { "(log is empty)" }
    }

    fun clear() {
        synchronized(lock) {
            logFile?.delete()
            logFile?.let { File(it.parent, "${it.name}.1").delete() }
        }
    }

    fun installUncaughtHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { logError("UNCAUGHT", "thread=${t.name}: ${e.message}", e) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun buildLine(tag: String, msg: String, t: Throwable?): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val thread = Thread.currentThread().name
        return buildString {
            append("$ts | $thread | $tag | $msg\n")
            t?.let { append(Log.getStackTraceString(it)).append("\n") }
        }
    }

    private fun writeToFile(line: String) {
        val file = logFile ?: return
        synchronized(lock) {
            try {
                if (file.exists() && file.length() > MAX_SIZE) {
                    val rotated = File(file.parent, "${file.name}.1")
                    rotated.delete()
                    file.renameTo(rotated)
                }
                BufferedWriter(FileWriter(file, true)).use { it.write(line) }
            } catch (e: Exception) {
                Log.e(TAG, "writeToFile failed: $e")
            }
        }
    }
}
