package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Keeps product photos in the app's private storage.
 *
 * Pickers return `content://` links with a *temporary* read grant that Android revokes
 * once the app restarts, so storing the link itself makes photos vanish. Instead we copy
 * the picked image here (downscaled, rotation applied) and store the file path.
 */
object ProductImageStore {

    private const val TAG = "ProductImageStore"
    private const val DIR = "product_images"
    private const val MAX_SIDE = 1024

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** Copies [source] into app storage and returns the saved file's path, or null on failure. Call off the main thread. */
    fun import(context: Context, source: Uri): String? = try {
        val bitmap = decodeScaled(context, source) ?: error("Could not decode image")
        val file = File(dir(context), "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        file.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "Failed to import product image", e)
        null
    }

    /** True for photos this class saved (as opposed to old picker links or web URLs). */
    fun isOwned(context: Context, path: String): Boolean =
        path.isNotBlank() && File(path).absoluteFile.parentFile == dir(context).absoluteFile

    /** Deletes a photo previously saved by [import]; ignores anything else. */
    fun delete(context: Context, path: String) {
        if (isOwned(context, path)) runCatching { File(path).delete() }
    }

    /** Whether an image reference can still be shown (old `content://` links usually can't). */
    fun isAvailable(context: Context, path: String): Boolean = when {
        path.isBlank() -> false
        path.startsWith("http://") || path.startsWith("https://") -> true
        path.startsWith("content://") -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { true } ?: false
        }.getOrDefault(false)
        else -> File(path.removePrefix("file://")).exists()
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF rotation itself.
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        if (rotation == 0f && scale == 1f) return decoded
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also {
            if (it != decoded) decoded.recycle()
        }
    }
}
