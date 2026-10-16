package com.killhuishenghuo.core.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.killhuishenghuo.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Writes the in-memory log to a file and hands it to whatever the user wants to
 * send it with.
 *
 * The file goes in `cacheDir/logs` rather than `filesDir`, and that is the point
 * of the whole feature: a cache directory is readable by the app's own
 * `FileProvider` without any storage permission, and it is the one directory the
 * system is allowed to reclaim. A debugging artefact that outlives the debugging
 * session is litter.
 *
 * One file per export, named with the moment it was written, because the useful
 * comparison is often two exports — before and after — and a fixed name silently
 * destroys the first one.
 */
object LogExporter {

    private const val DIR = "logs"

    /** The last file written, so the UI can say where it went. */
    var lastExport: File? = null
        private set

    /**
     * Writes the log and returns the file.
     *
     * `suspend` and moved onto [Dispatchers.IO] because everything in here is
     * disk: `mkdirs`, a `listFiles` plus a delete per entry, formatting a date,
     * and then up to a few thousand lines of text. All of that used to run in the
     * click handler, on the main thread — a visible freeze on the one screen that
     * is reached *because* something has already gone wrong.
     *
     * The `File` handles out of here — the chooser is started from the caller.
     */
    suspend fun write(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): File =
        withContext(ioDispatcher) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            // Any older exports are gone: the buffer is in memory, so yesterday's
            // file is a copy of something that no longer exists to compare
            // against.
            dir.listFiles()?.forEach { it.delete() }

            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(java.util.Date())
            val file = File(dir, "huishui-log-$stamp.txt")
            file.writeText(AppLog.dump(appVersion = BuildConfig.VERSION_NAME))
            lastExport = file
            file
        }

    /**
     * Hands the log to the system share sheet.
     *
     * `setClipData` is not redundant with `EXTRA_STREAM`: it is what gives the
     * receiving app permission to read the URI under the modern sharing rules,
     * and without it a share to a chat app arrives as an unreadable attachment.
     */
    suspend fun share(context: Context, chooserTitle: String = "发送运行日志") {
        val file = write(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        context.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
