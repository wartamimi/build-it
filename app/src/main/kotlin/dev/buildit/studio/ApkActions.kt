package dev.buildit.studio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Hands a built APK to the system installer or the share sheet through a content URI. */
object ApkActions {
    private const val MIME = "application/vnd.android.package-archive"

    private fun uri(context: Context, apk: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)

    fun install(context: Context, apk: File, failed: (String) -> String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, apk), MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failed(e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    fun share(context: Context, apk: File) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri(context, apk))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, apk.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
