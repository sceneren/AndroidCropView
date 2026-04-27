package com.github.sceneren.cropview

import android.graphics.Bitmap

/**
 * 中文：调用方图片加载器返回的轻量取消句柄。
 * English: Lightweight cancellation handle returned by caller-provided image loaders.
 */
fun interface Cancelable {
    fun cancel()

    companion object {
        val NONE = Cancelable {}
    }
}

/**
 * 中文：传给外部远程图片加载器的请求信息。
 * English: Request passed to the external remote-image loader.
 */
data class ImageLoadRequest(
    val url: String,
    val requestedWidth: Int,
    val requestedHeight: Int,
)

/**
 * 中文：远程图片加载器通过该回调返回已解码 Bitmap 或错误。
 * English: Callback used by remote-image loaders to return decoded bitmaps or errors.
 */
interface ImageLoadCallback {
    fun onSuccess(bitmap: Bitmap)

    fun onError(error: Throwable)
}

/**
 * 中文：可用 Coil、Glide、Picasso 或自定义网络管线实现该接口。
 * English: Implement this with Coil, Glide, Picasso, or any custom network pipeline.
 */
fun interface CropImageLoader {
    fun load(request: ImageLoadRequest, callback: ImageLoadCallback): Cancelable
}
