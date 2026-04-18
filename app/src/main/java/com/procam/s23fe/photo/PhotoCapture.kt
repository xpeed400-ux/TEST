package com.procam.s23fe.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.Image
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles both JPEG + DNG (RAW) output and the "Clean 5MP" downscale path
 * described in section 11 of the spec.
 */
class PhotoCapture(private val context: Context) {

    private val tag = "PhotoCapture"

    data class SaveResult(val jpeg: File? = null, val dng: File? = null, val clean5MP: File? = null)

    fun saveJpeg(image: Image, orientationDegrees: Int, alsoClean5MP: Boolean): SaveResult {
        val buf: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }

        val jpegFile = writeBytes(bytes, "Photo", "jpg")
        publish(jpegFile, "image/jpeg", "DCIM/ProCam/Photo")

        var clean: File? = null
        if (alsoClean5MP) clean = writeClean5MP(bytes, orientationDegrees)
        return SaveResult(jpeg = jpegFile, clean5MP = clean)
    }

    fun saveDng(bytes: ByteArray): File {
        val f = writeBytes(bytes, "RAW", "dng")
        publish(f, "image/x-adobe-dng", "DCIM/ProCam/RAW")
        return f
    }

    /** Downscale to ~5MP while staying on the sensor aspect ratio. */
    private fun writeClean5MP(jpegBytes: ByteArray, rotateDeg: Int): File? {
        val opts = BitmapFactory.Options().apply { inSampleSize = computeSampleSize(jpegBytes, 5_000_000) }
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
        val rotated = if (rotateDeg != 0) {
            Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(rotateDeg.toFloat()) }, true
            )
        } else bmp
        val out = newFile("Photo", "jpg").let { File(it.parentFile, "CLEAN5_${it.name}") }
        FileOutputStream(out).use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        publish(out, "image/jpeg", "DCIM/ProCam/Photo")
        return out
    }

    private fun computeSampleSize(jpeg: ByteArray, targetPixels: Int): Int {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        var sample = 1
        var pixels = opts.outWidth.toLong() * opts.outHeight.toLong()
        while (pixels > targetPixels * 2L) { sample *= 2; pixels /= 4 }
        return sample
    }

    private fun writeBytes(bytes: ByteArray, subdir: String, ext: String): File {
        val f = newFile(subdir, ext)
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    private fun newFile(subdir: String, ext: String): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "ProCam/$subdir"
        )
        if (!base.exists()) base.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(base, "PROCAM_$ts.$ext")
    }

    private fun publish(file: File, mime: String, relPath: String) = try {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        Unit
    } catch (e: Exception) { Log.w(tag, "publish failed", e) }
}
