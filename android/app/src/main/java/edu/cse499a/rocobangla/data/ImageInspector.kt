package edu.cse499a.rocobangla.data

import okhttp3.RequestBody

/** Reads name, size, type and pixel size of a picked image (content URI as a string). */
fun interface ImageInspector {
    suspend fun inspect(uri: String): Outcome<PickedImage>
}

/** Streams the picked image's original bytes as an upload body. */
fun interface UploadBodyFactory {
    fun create(image: PickedImage): RequestBody
}
