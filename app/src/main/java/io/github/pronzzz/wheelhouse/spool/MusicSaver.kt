package io.github.pronzzz.wheelhouse.spool

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * Saves a downloaded song into Music/Spool, where Android's media library (and so
 * Wheelhouse) picks it up with its tags and cover.
 */
object MusicSaver {
    const val FOLDER = "Spool"

    /** Android 6–9 need the storage permission to write into Music. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT in 23..28 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun save(context: Context, fileName: String, input: InputStream) {
        val name = safeName(fileName)
        if (Build.VERSION.SDK_INT >= 29) saveMediaStore(context, name, input) else saveFile(context, name, input)
    }

    private fun saveMediaStore(context: Context, name: String, input: InputStream) {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(collection, values) ?: throw java.io.IOException("Android refused to create the file.")
        try {
            cr.openOutputStream(uri)?.use { out -> input.copyTo(out, 64 * 1024) } ?: throw java.io.IOException("Can't write the file.")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveFile(context: Context, name: String, input: InputStream) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Can't create Music/$FOLDER.")
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}").also { n++ }
        val tmp = File(dir, ".${file.name}.part")
        try {
            tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            if (!tmp.renameTo(file)) throw java.io.IOException("Can't save ${file.name}.")
        } finally {
            tmp.delete()
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
    }

    fun safeName(name: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), " ").replace(Regex("\\s+"), " ").trim().trimStart('.')
        return clean.ifEmpty { "Untitled.m4a" }.take(150)
    }

    fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "audio/mp4"
    }
}
