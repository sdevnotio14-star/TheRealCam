package com.therealcam.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/**
 * Video files of VIDEO mode in DCIM/TheRealCam: through MediaStore (pending until finished) on
 * API 29+, the public DCIM folder plus a media scan before that.
 */
class VideoStore(private val context: Context) {
    /** An open output; [file] is set for the pre-MediaStore path. */
    class Target(val uri: Uri, val pfd: ParcelFileDescriptor, val file: File?, val label: String)

    fun create(displayName: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/TheRealCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            val pfd = try {
                resolver.openFileDescriptor(uri, "rw") ?: throw IOException("open failed")
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            return Target(uri, pfd, null, "DCIM/TheRealCam")
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (granted) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "TheRealCam")
        else context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "videos")
        dir.mkdirs()
        val f = File(dir, displayName)
        val pfd = ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return Target(Uri.fromFile(f), pfd, f, if (granted) "DCIM/TheRealCam" else dir.absolutePath)
    }

    /** Makes a finished recording visible in galleries. */
    fun publish(t: Target) {
        runCatching { t.pfd.close() }
        if (t.file == null) {
            context.contentResolver.update(t.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(t.file.absolutePath), arrayOf("video/mp4"), null)
        }
    }

    fun discard(t: Target) {
        runCatching { t.pfd.close() }
        runCatching { if (t.file == null) context.contentResolver.delete(t.uri, null, null) else t.file.delete() }
    }
}
