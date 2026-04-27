package com.github.sceneren.cropview

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 中文：裁剪图保存到应用私有缓存目录后的结果；调用方主要使用 [filePath]。
 * English: Result returned after a cropped image is saved into the app-private cache directory.
 */
data class CropSaveResult(
    val file: File,
    val filePath: String,
    val mimeType: String,
    val format: Bitmap.CompressFormat,
    val keepAlpha: Boolean,
)

/**
 * 中文：裁剪并保存的回调；成功时返回文件绝对路径，不返回 Uri。
 * English: Callback for crop-and-save operations. Success returns an absolute file path, not a Uri.
 */
interface CropSaveCallback {
    fun onCropSaveSuccess(result: CropSaveResult)

    fun onCropSaveError(error: Throwable)
}

/**
 * 中文：共享的缓存写入器，View 和 Compose 版本都通过它保持一致的输出目录和格式。
 * English: Shared cache writer used by both the View and Compose implementations.
 */
object CropImageCacheSaver {
    private const val CACHE_DIR_NAME = "cropview"
    private const val DEFAULT_PREFIX = "crop"
    private const val DEFAULT_JPEG_QUALITY = 94

    /**
     * 中文：保存到 context.cacheDir/cropview；矩形默认 JPEG，非矩形保留 alpha 并保存 PNG。
     * English: Saves into context.cacheDir/cropview. Rectangle crops use JPEG; alpha crops use PNG.
     */
    @JvmStatic
    @JvmOverloads
    fun saveToPrivateCache(
        context: Context,
        bitmap: Bitmap,
        keepAlpha: Boolean,
        filePrefix: String = DEFAULT_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): CropSaveResult {
        val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (keepAlpha) "png" else "jpg"
        val mimeType = if (keepAlpha) "image/png" else "image/jpeg"
        val outputDir = File(context.cacheDir, CACHE_DIR_NAME)
        // The output is app-private cache storage, so callers receive only a filesystem path.
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Unable to create crop cache directory: ${outputDir.absolutePath}")
        }

        // Normalize caller-provided prefixes so they cannot create nested paths.
        val safePrefix = filePrefix.asSafeFilePrefix()
        val outputFile = File(outputDir, "${safePrefix}_${System.currentTimeMillis()}.$extension")
        FileOutputStream(outputFile).use { output ->
            // PNG ignores lossy quality, while JPEG clamps caller input into Android's valid range.
            val quality = if (keepAlpha) 100 else jpegQuality.coerceIn(0, 100)
            if (!bitmap.compress(format, quality, output)) {
                throw IOException("Unable to write cropped image: ${outputFile.absolutePath}")
            }
        }

        return CropSaveResult(
            file = outputFile,
            filePath = outputFile.absolutePath,
            mimeType = mimeType,
            format = format,
            keepAlpha = keepAlpha,
        )
    }

    // File names are derived from caller input, so keep only stable filename characters.
    private fun String.asSafeFilePrefix(): String {
        val safe = filter { char ->
            char.isLetterOrDigit() || char == '_' || char == '-'
        }
        return safe.ifBlank { DEFAULT_PREFIX }
    }
}
