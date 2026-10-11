package edu.cse499a.rocobangla.data

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import edu.cse499a.rocobangla.core.AppConfig
import edu.cse499a.rocobangla.core.AppError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.FileNotFoundException

/** Reads image metadata through the ContentResolver. Only the header is
 *  decoded (inJustDecodeBounds); the image itself is never changed. */
class ContentResolverImageInspector(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ImageInspector {

    override suspend fun inspect(uri: String): Outcome<PickedImage> = withContext(io) {
        try {
            val parsed = Uri.parse(uri)
            var name: String? = null
            var size = -1L
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
                }
            if (size < 0) size = countBytes(parsed)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds); true }
            if (opened != true || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Outcome.Failure(AppError.ImageUnreadable)
            }
            Outcome.Success(
                PickedImage(
                    uri = uri,
                    displayName = name ?: parsed.lastPathSegment ?: "image",
                    sizeBytes = size,
                    mimeType = resolver.getType(parsed) ?: bounds.outMimeType,
                    widthPx = bounds.outWidth,
                    heightPx = bounds.outHeight,
                ),
            )
        } catch (e: SecurityException) {
            Outcome.Failure(AppError.ImageUnreadable)
        } catch (e: FileNotFoundException) {
            Outcome.Failure(AppError.ImageUnreadable)
        } catch (e: java.io.IOException) {
            Outcome.Failure(AppError.ImageUnreadable)
        }
    }

    /** Counts bytes when the provider does not report a size, stopping just past the limit. */
    private fun countBytes(uri: Uri): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            while (total <= AppConfig.MAX_IMAGE_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
        } ?: return -1
        return total
    }
}

/** Streams the original bytes from the ContentResolver as multipart. No Base64,
 *  no re-encoding, no resizing: the server applies the training-time 512 px shrink. */
class ContentUriUploadBodyFactory(private val resolver: ContentResolver) : UploadBodyFactory {
    override fun create(image: PickedImage): RequestBody = ContentUriRequestBody(resolver, image)
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val image: PickedImage,
) : RequestBody() {
    override fun contentType(): MediaType? =
        (image.mimeType ?: "application/octet-stream").toMediaTypeOrNull()

    override fun contentLength(): Long = if (image.sizeBytes > 0) image.sizeBytes else -1

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(Uri.parse(image.uri))
            ?: throw FileNotFoundException("image is no longer readable")
        input.source().use { sink.writeAll(it) }
    }
}
