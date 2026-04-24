package com.github.sceneren.cropview

import android.graphics.Bitmap

fun interface Cancelable {
    fun cancel()

    companion object {
        val NONE = Cancelable {}
    }
}

data class ImageLoadRequest(
    val url: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
)

interface ImageLoadCallback {
    fun onSuccess(bitmap: Bitmap)

    fun onError(error: Throwable)
}

fun interface CropImageLoader {
    fun load(request: ImageLoadRequest, callback: ImageLoadCallback): Cancelable
}
