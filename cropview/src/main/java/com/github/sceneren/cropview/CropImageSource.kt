package com.github.sceneren.cropview

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import androidx.core.net.toUri

/**
 * 中文：描述裁剪控件读取图片源的位置。
 * English: Describes where the crop view should read its source image from.
 */
sealed class CropImageSource {
    /**
     * 中文：来自 content 或 file Uri 的图片。
     * English: Image from a content or file uri.
     */
    data class UriSource(val uri: Uri) : CropImageSource()

    /**
     * 中文：来自本地文件路径的图片。
     * English: Image from a local file path.
     */
    data class FileSource(val file: File) : CropImageSource()

    /**
     * 中文：远程图片；使用该类型时调用方必须提供 [CropImageLoader]。
     * English: Remote image. Callers must provide a [CropImageLoader] when using this source.
     */
    data class UrlSource(val url: String) : CropImageSource()

    /**
     * 中文：调用方已经解码好的 Bitmap。
     * English: Already decoded bitmap owned by the caller.
     */
    data class BitmapSource(val bitmap: Bitmap) : CropImageSource()

    companion object {
        fun fromUri(uri: Uri): CropImageSource = UriSource(uri)

        fun fromFile(file: File): CropImageSource = FileSource(file)

        fun fromUrl(url: String): CropImageSource = UrlSource(url)

        /**
         * 中文：把常见字符串输入转换为对应的图片源类型。
         * English: Converts common string inputs into the correct source type.
         */
        fun fromPath(path: String): CropImageSource {
            val value = path.trim()
            return when {
                value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) -> UrlSource(value)
                value.startsWith("content://", ignoreCase = true) ||
                    value.startsWith("file://", ignoreCase = true) -> UriSource(value.toUri())
                else -> FileSource(File(value))
            }
        }
    }
}
