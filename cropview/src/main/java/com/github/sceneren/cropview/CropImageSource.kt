package com.github.sceneren.cropview

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import androidx.core.net.toUri

sealed class CropImageSource {
    data class UriSource(val uri: Uri) : CropImageSource()
    data class FileSource(val file: File) : CropImageSource()
    data class UrlSource(val url: String) : CropImageSource()
    data class BitmapSource(val bitmap: Bitmap) : CropImageSource()

    companion object {
        fun fromUri(uri: Uri): CropImageSource = UriSource(uri)

        fun fromFile(file: File): CropImageSource = FileSource(file)

        fun fromUrl(url: String): CropImageSource = UrlSource(url)

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
