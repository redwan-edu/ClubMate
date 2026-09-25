package com.example.clubmate.e2ee

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.example.clubmate.crypto.E2eeCrypto
import com.example.clubmate.e2ee.E2eeManager.E2eeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * End-to-end encrypted images for chats, groups and private channels.
 *
 * Before upload the picture is re-encoded on the device (downscaled, EXIF rotation applied, all
 * metadata such as GPS location dropped) and encrypted with a fresh random AES-256-GCM key. Cloudinary
 * stores it as an opaque "raw" file. The message then carries an image reference
 *
 *     e2ee-image:v1:<Base64 key>:<https URL>
 *
 * inside its own encrypted content, so only the chat/group/channel members ever learn the key.
 */
object SecureImages {

    private const val TAG = "SecureImages"
    private const val PREFIX = "e2ee-image:v1:"
    private const val FOLDER = "encrypted_images"

    private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 15 * 1024 * 1024
    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    private lateinit var appContext: Context

    // decrypted images, sized in kilobytes (1/8 of the app's memory)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isEncrypted(ref: String): Boolean = ref.startsWith(PREFIX)

    /** The file URL inside an encrypted image reference (or the reference itself if it is a URL). */
    fun urlOf(ref: String): String = if (isEncrypted(ref)) parse(ref)?.second.orEmpty() else ref

    /**
     * Re-encodes, encrypts and uploads the picture at [uri]. Returns the image reference to put in
     * the message content. Throws [E2eeException] with a user-readable reason on failure.
     */
    suspend fun upload(uri: Uri): String {
        val jpeg = withContext(Dispatchers.IO) {
            try {
                prepare(uri)
            } catch (e: E2eeException) {
                throw e
            } catch (e: Exception) { // unreadable file, revoked permission, ...
                Log.e(TAG, "Couldn't prepare image", e)
                throw E2eeException("Couldn't read the image")
            }
        }
        val sealed = withContext(Dispatchers.Default) { E2eeCrypto.encryptAttachment(jpeg) }
        val url = uploadRaw(sealed.data)
        return PREFIX + Base64.encodeToString(sealed.key, Base64.NO_WRAP) + ":" + url
    }

    /** Downloads and decrypts an encrypted image reference. Returns null if it can't be shown. */
    suspend fun load(ref: String): Bitmap? {
        cache.get(ref)?.let { return it }
        val (key, url) = parse(ref) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val encrypted = download(url)
                val plain = E2eeCrypto.decryptAttachment(key, encrypted)
                decodeSampled(plain, MAX_DIMENSION)?.also { cache.put(ref, it) }
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Image rejected: it was altered or the key is wrong")
                null
            } catch (e: Exception) { // network error, too large, ...
                Log.w(TAG, "Image download failed: ${e.message}")
                null
            }
        }
    }

    private fun parse(ref: String): Pair<ByteArray, String>? {
        if (!isEncrypted(ref)) return null
        val rest = ref.removePrefix(PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        val key = try {
            Base64.decode(rest.substring(0, separator), Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val url = rest.substring(separator + 1)
        if (key.size != E2eeCrypto.KEY_SIZE || !url.startsWith("https://")) return null
        return key to url
    }

    // ---------------------------------------------------------------- upload

    private fun prepare(uri: Uri): ByteArray {
        val original = appContext.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_SOURCE_BYTES) }
            ?: throw E2eeException("Couldn't read the image")

        val orientation = try {
            ExifInterface(ByteArrayInputStream(original))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val decoded = decodeSampled(original, MAX_DIMENSION)
            ?: throw E2eeException("That file isn't a supported image")

        val matrix = Matrix()
        val largest = maxOf(decoded.width, decoded.height)
        if (largest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / largest
            matrix.postScale(scale, scale)
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val finalBitmap = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        return ByteArrayOutputStream().use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private suspend fun uploadRaw(data: ByteArray): String = suspendCancellableCoroutine { cont ->
        MediaManager.get().upload(data)
            .option("folder", FOLDER)
            .option("resource_type", "raw") // opaque bytes: no previews or transformations
            .callback(object : UploadCallback {
                override fun onStart(requestId: String?) {
                    Log.d(TAG, "Upload started")
                }

                override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {
                    Log.d(TAG, "Uploading: $bytes/$totalBytes")
                }

                override fun onSuccess(requestId: String?, resultData: MutableMap<Any?, Any?>?) {
                    val url = resultData?.get("secure_url") as? String
                    if (!cont.isActive) return
                    if (url != null) cont.resume(url)
                    else cont.resumeWithException(E2eeException("Image upload failed"))
                }

                override fun onError(requestId: String?, error: ErrorInfo?) {
                    Log.e(TAG, "Upload failed: ${error?.description}")
                    if (cont.isActive) cont.resumeWithException(E2eeException("Image upload failed"))
                }

                override fun onReschedule(requestId: String?, error: ErrorInfo?) {
                    Log.e(TAG, "Upload rescheduled: ${error?.description}")
                    if (cont.isActive) cont.resumeWithException(E2eeException("Image upload failed, try again"))
                }
            }).dispatch()
    }

    // ---------------------------------------------------------------- download / decode

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { readLimited(it, MAX_DOWNLOAD_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    // Decodes at a reduced size when the picture is much larger than needed, to save memory.
    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw E2eeException("The image is too large")
        }
        return out.toByteArray()
    }
}
