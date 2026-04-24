package com.github.sceneren.cropview

import android.graphics.Bitmap

sealed class CropShape {
    internal abstract val aspectRatio: Float?

    data class Rectangle(
        val widthRatio: Int = 0,
        val heightRatio: Int = 0,
    ) : CropShape() {
        override val aspectRatio: Float? =
            if (widthRatio > 0 && heightRatio > 0) widthRatio.toFloat() / heightRatio.toFloat() else null
    }

    object Circle : CropShape() {
        override val aspectRatio: Float = 1f
    }

    data class BitmapMask(
        val bitmap: Bitmap,
        val widthRatio: Int = bitmap.width,
        val heightRatio: Int = bitmap.height,
    ) : CropShape() {
        override val aspectRatio: Float =
            if (widthRatio > 0 && heightRatio > 0) widthRatio.toFloat() / heightRatio.toFloat() else 1f
    }
}
