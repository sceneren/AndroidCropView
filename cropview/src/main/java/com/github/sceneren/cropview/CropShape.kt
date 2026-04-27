package com.github.sceneren.cropview

import android.graphics.Bitmap

/**
 * 中文：裁剪窗口支持的形状；非矩形形状会以带透明通道的结果导出。
 * English: Supported crop-window shapes. Non-rectangle shapes are exported with alpha.
 */
sealed class CropShape {
    internal abstract val aspectRatio: Float?

    /**
     * 中文：矩形裁剪；比例为 0 表示自由缩放，正数比例表示固定宽高比。
     * English: Rectangle crop. Use zero ratios for free resizing, or positive ratios for fixed aspect.
     */
    data class Rectangle(
        val widthRatio: Int = 0,
        val heightRatio: Int = 0,
    ) : CropShape() {
        override val aspectRatio: Float? =
            if (widthRatio > 0 && heightRatio > 0) widthRatio.toFloat() / heightRatio.toFloat() else null
    }

    /**
     * 中文：圆形裁剪，使用正方形裁剪框。
     * English: Circle crop using a square crop frame.
     */
    object Circle : CropShape() {
        override val aspectRatio: Float = 1f
    }

    /**
     * 中文：自定义形状裁剪；Bitmap 的 alpha 通道定义透明裁剪窗口。
     * English: Custom shape crop. The bitmap alpha channel defines the transparent crop window.
     */
    data class BitmapMask(
        val bitmap: Bitmap,
        val widthRatio: Int = bitmap.width,
        val heightRatio: Int = bitmap.height,
    ) : CropShape() {
        override val aspectRatio: Float =
            if (widthRatio > 0 && heightRatio > 0) widthRatio.toFloat() / heightRatio.toFloat() else 1f
    }
}
