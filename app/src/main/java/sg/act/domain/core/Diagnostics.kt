package sg.act.domain.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only diagnostics. Captures this app's *own* logcat — which an app may
 * always read without any permission — and hands it to the share sheet as a .txt.
 * Includes the native llama.cpp loader output that we route through
 * __android_log_print, so a model-load failure can be reported straight from the
 * device — no ADB, host machine or root needed. Only ever invoked from the debug
 * build (see SettingsScreen).
 */
object Diagnostics {

    fun captureAndShare(context: Context) {
        val logs = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "5000", "--pid=${Process.myPid()}"),
            )
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "Could not read logcat: ${it.message}" }

        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "domain-ai-log-${System.currentTimeMillis()}.txt")
        file.writeText(header(context) + logs)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Domain AI diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun header(context: Context): String = buildString {
        appendLine("Domain AI diagnostics")
        appendLine("package: ${context.packageName}")
        appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("captured: $now")
        appendLine("----")
    }
}
