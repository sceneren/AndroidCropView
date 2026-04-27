package com.github.sceneren.cropview.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.github.sceneren.cropview.Cancelable
import com.github.sceneren.cropview.CropImageCacheSaver
import com.github.sceneren.cropview.CropImageLoader
import com.github.sceneren.cropview.CropImageSource
import com.github.sceneren.cropview.CropSaveCallback
import com.github.sceneren.cropview.CropSaveResult
import com.github.sceneren.cropview.CropShape
import com.github.sceneren.cropview.ImageLoadCallback
import com.github.sceneren.cropview.ImageLoadRequest
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 中文：Compose 裁剪图片加载状态，调用方可用它驱动进度和错误 UI。
 * English: Image loading state exposed for progress and error UI in Compose.
 */
sealed interface CropLoadState {
    data object Empty : CropLoadState

    data object Loading : CropLoadState

    data class Success(val bitmap: Bitmap) : CropLoadState

    data class Error(val error: Throwable) : CropLoadState
}

/**
 * 中文：图片加载回调；用于需要和传统 View 版保持同类事件模型的场景。
 * English: Image-load callback for callers that prefer View-style load events.
 */
interface ComposeImageLoadListener {
    fun onLoadStart() = Unit

    fun onLoadSuccess(bitmap: Bitmap) = Unit

    fun onLoadError(error: Throwable) = Unit
}

/**
 * 中文：创建并记住裁剪状态；公开操作方法都挂在 [CropViewState] 上。
 * English: Creates and remembers crop state. Public operations are exposed on [CropViewState].
 */
@Composable
fun rememberCropViewState(
    cropShape: CropShape = CropShape.Rectangle(),
    showGridLines: Boolean = true,
    showCornerHandles: Boolean = true,
    cropFrameResizeEnabled: Boolean = true,
    maxZoomScale: Float = 4f,
): CropViewState {
    return remember {
        CropViewState(
            cropShape = cropShape,
            showGridLines = showGridLines,
            showCornerHandles = showCornerHandles,
            cropFrameResizeEnabled = cropFrameResizeEnabled,
            maxZoomScale = maxZoomScale,
        )
    }
}

/**
 * 中文：Compose 裁剪组件，只负责渲染和手势；裁剪、保存、旋转等命令通过 [state] 调用。
 * English: Compose cropper UI. Rendering and gestures live here; commands are invoked through [state].
 */
@Composable
fun ImageCropper(
    state: CropViewState,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF101114),
    overlayColor: Color = Color(0x96000000),
    borderColor: Color = Color.White,
    borderStrokeWidth: Dp = 2.dp,
    gridColor: Color = Color(0x96FFFFFF),
    gridStrokeWidth: Dp = 1.dp,
    cornerColor: Color = Color.White,
    cornerStrokeWidth: Dp = 4.dp,
    cornerLength: Dp = 28.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val drawVersion = state.drawVersion
    val flingVersion = state.flingVersion

    LaunchedEffect(flingVersion) {
        while (state.stepFling()) {
            withFrameNanos { }
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { size ->
                state.onViewportChanged(size, density.density)
            }
            .pointerInput(state, context, viewConfiguration.touchSlop) {
                cropGestureInput(state, context, viewConfiguration.touchSlop)
            },
    ) {
        drawVersion
        state.draw(
            drawScope = this,
            backgroundColor = backgroundColor,
            overlayColor = overlayColor,
            borderColor = borderColor,
            borderStrokeWidthPx = borderStrokeWidth.toPx(),
            gridColor = gridColor,
            gridStrokeWidthPx = gridStrokeWidth.toPx(),
            cornerColor = cornerColor,
            cornerStrokeWidthPx = cornerStrokeWidth.toPx(),
            cornerLengthPx = cornerLength.toPx(),
        )
    }
}

/**
 * 中文：Compose 版裁剪状态，保存图片矩阵、裁剪框、形状和导出/保存方法。
 * English: Compose crop state storing image transform, crop frame, shape, export, and save APIs.
 */
@Stable
class CropViewState(
    cropShape: CropShape = CropShape.Rectangle(),
    showGridLines: Boolean = true,
    showCornerHandles: Boolean = true,
    cropFrameResizeEnabled: Boolean = true,
    maxZoomScale: Float = 4f,
) {
    var imageLoadListener: ComposeImageLoadListener? = null

    var loadState: CropLoadState by mutableStateOf(CropLoadState.Empty)
        private set

    private var showGridLinesState: Boolean by mutableStateOf(showGridLines)

    var showGridLines: Boolean
        get() = showGridLinesState
        set(value) {
            showGridLinesState = value
            invalidate()
        }

    private var showCornerHandlesState: Boolean by mutableStateOf(showCornerHandles)

    var showCornerHandles: Boolean
        get() = showCornerHandlesState
        set(value) {
            showCornerHandlesState = value
            invalidate()
        }

    private var cropFrameResizeEnabledState: Boolean by mutableStateOf(cropFrameResizeEnabled)

    var cropFrameResizeEnabled: Boolean
        get() = cropFrameResizeEnabledState
        set(value) {
            cropFrameResizeEnabledState = value
            if (!value && dragMode != DragMode.MOVE_IMAGE) {
                dragMode = DragMode.NONE
            }
            invalidate()
        }

    private var maxZoomScaleState: Float by mutableFloatStateOf(maxZoomScale.normalizedZoom())

    var maxZoomScale: Float
        get() = maxZoomScaleState
        set(value) {
            maxZoomScaleState = value.normalizedZoom()
            clampImageScaleToMax()
            invalidate()
        }

    val cropShape: CropShape
        get() = cropShapeState

    val hasImage: Boolean
        get() = bitmap != null

    internal var drawVersion: Int by mutableIntStateOf(0)
        private set

    internal var flingVersion: Int by mutableIntStateOf(0)
        private set

    private enum class DragMode {
        NONE,
        MOVE_IMAGE,
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val maskClearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val imageMatrix = Matrix()
    private val bitmapBounds = RectF()
    private val imageBounds = RectF()
    private val cropRect = RectF()
    private val contentRect = RectF()
    private val matrixValues = FloatArray(9)

    private var bitmap: Bitmap? by mutableStateOf(null)
    private var cropShapeState: CropShape by mutableStateOf(cropShape)
    private var viewportSize = IntSize.Zero
    private var density = 1f
    private var dragMode = DragMode.NONE
    private var baseImageScale = 1f
    private var flingScroller: OverScroller? = null
    private var flingLastX = 0
    private var flingLastY = 0

    suspend fun setImageSource(
        context: Context,
        source: CropImageSource,
        imageLoader: CropImageLoader? = null,
    ) {
        stopFling()
        loadState = CropLoadState.Loading
        imageLoadListener?.onLoadStart()
        try {
            val decoded = when (source) {
                is CropImageSource.BitmapSource -> source.bitmap
                is CropImageSource.UrlSource -> loadRemoteImage(context, source.url, imageLoader)
                is CropImageSource.UriSource -> withContext(Dispatchers.IO) {
                    decodeUri(context, source.uri, requestedDecodeWidth(context), requestedDecodeHeight(context))
                }
                is CropImageSource.FileSource -> withContext(Dispatchers.IO) {
                    decodeFile(source.file, requestedDecodeWidth(context), requestedDecodeHeight(context))
                }
            }
            setImageBitmap(decoded)
            loadState = CropLoadState.Success(decoded)
            imageLoadListener?.onLoadSuccess(decoded)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            loadState = CropLoadState.Error(error)
            imageLoadListener?.onLoadError(error)
        }
    }

    fun setImageBitmap(nextBitmap: Bitmap) {
        stopFling()
        bitmap = nextBitmap
        bitmapBounds.set(0f, 0f, nextBitmap.width.toFloat(), nextBitmap.height.toFloat())
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            configureInitialState()
        }
        invalidate()
    }

    fun setCropShape(shape: CropShape) {
        stopFling()
        cropShapeState = shape
        initCropRect()
        ensureImageCoversCrop()
        invalidate()
    }

    fun setAspectRatio(width: Int, height: Int) {
        setCropShape(CropShape.Rectangle(width, height))
    }

    fun clearAspectRatio() {
        setCropShape(CropShape.Rectangle())
    }

    fun requiresAlphaOutput(): Boolean {
        return cropShapeState !is CropShape.Rectangle
    }

    fun rotateBy(degrees: Float) {
        if (bitmap == null || cropRect.isEmpty) return
        stopFling()
        imageMatrix.postRotate(degrees, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    fun zoomBy(factor: Float) {
        if (bitmap == null || cropRect.isEmpty || factor <= 0f) return
        stopFling()
        scaleImageBy(factor, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    fun resetImage() {
        if (bitmap == null || viewportSize.width == 0 || viewportSize.height == 0) return
        stopFling()
        configureInitialState()
        invalidate()
    }

    fun getCroppedBitmap(): Bitmap? {
        val outputWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val outputHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        return getCroppedBitmap(outputWidth, outputHeight)
    }

    fun getCroppedBitmap(outputWidth: Int, outputHeight: Int): Bitmap? {
        val source = bitmap ?: return null
        if (cropRect.isEmpty || outputWidth <= 0 || outputHeight <= 0) return null
        val result = createBitmap(outputWidth, outputHeight)
        val canvas = AndroidCanvas(result)
        canvas.drawColor(AndroidColor.TRANSPARENT)
        canvas.scale(outputWidth / cropRect.width(), outputHeight / cropRect.height())
        canvas.translate(-cropRect.left, -cropRect.top)
        canvas.drawBitmap(source, imageMatrix, bitmapPaint)
        return applyCropShapeMask(result)
    }

    /**
     * 中文：按当前裁剪框尺寸裁剪并保存到应用私有缓存目录，返回文件绝对路径等信息。
     * English: Crops at the current frame size, saves into app-private cache, and returns file-path metadata.
     */
    suspend fun cropAndSaveToCache(
        context: Context,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): CropSaveResult {
        val outputWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val outputHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        return cropAndSaveToCache(context, outputWidth, outputHeight, filePrefix, jpegQuality)
    }

    /**
     * 中文：回调版保存 API，便于调用方和 View 版使用同一套回调类型。
     * English: Callback-style save API, sharing the same callback contract as the View implementation.
     */
    suspend fun cropAndSaveToCache(
        context: Context,
        callback: CropSaveCallback,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ) {
        try {
            callback.onCropSaveSuccess(cropAndSaveToCache(context, filePrefix, jpegQuality))
        } catch (error: Throwable) {
            callback.onCropSaveError(error)
        }
    }

    /**
     * 中文：按指定输出尺寸裁剪并保存；磁盘写入切到 IO 线程，调用方拿到私有缓存文件路径。
     * English: Crops to a requested size and writes on Dispatchers.IO, returning the private cache file path.
     */
    suspend fun cropAndSaveToCache(
        context: Context,
        outputWidth: Int,
        outputHeight: Int,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): CropSaveResult {
        val cropped = getCroppedBitmap(outputWidth, outputHeight)
            ?: throw IllegalStateException("Image is not ready to crop.")
        val appContext = context.applicationContext
        val keepAlpha = requiresAlphaOutput()
        // Keep expensive file writing off the Compose/main thread.
        return withContext(Dispatchers.IO) {
            CropImageCacheSaver.saveToPrivateCache(
                context = appContext,
                bitmap = cropped,
                keepAlpha = keepAlpha,
                filePrefix = filePrefix,
                jpegQuality = jpegQuality,
            )
        }
    }

    /**
     * 中文：指定尺寸的回调版保存 API；错误会通过 onCropSaveError 返回。
     * English: Requested-size callback API. Failures are delivered through onCropSaveError.
     */
    suspend fun cropAndSaveToCache(
        context: Context,
        outputWidth: Int,
        outputHeight: Int,
        callback: CropSaveCallback,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ) {
        try {
            callback.onCropSaveSuccess(cropAndSaveToCache(context, outputWidth, outputHeight, filePrefix, jpegQuality))
        } catch (error: Throwable) {
            callback.onCropSaveError(error)
        }
    }

    internal fun onViewportChanged(size: IntSize, density: Float) {
        this.density = density
        if (size.width <= 0 || size.height <= 0) return
        if (viewportSize == size) return
        viewportSize = size
        if (bitmap != null) {
            configureInitialState()
        } else {
            updateContentRect()
            initCropRect()
        }
        invalidate()
    }

    internal fun draw(
        drawScope: DrawScope,
        backgroundColor: Color,
        overlayColor: Color,
        borderColor: Color,
        borderStrokeWidthPx: Float,
        gridColor: Color,
        gridStrokeWidthPx: Float,
        cornerColor: Color,
        cornerStrokeWidthPx: Float,
        cornerLengthPx: Float,
    ) {
        overlayPaint.color = overlayColor.toArgb()
        borderPaint.color = borderColor.toArgb()
        borderPaint.strokeWidth = borderStrokeWidthPx
        gridPaint.color = gridColor.toArgb()
        gridPaint.strokeWidth = gridStrokeWidthPx
        handlePaint.color = cornerColor.toArgb()
        handlePaint.strokeWidth = cornerStrokeWidthPx

        drawScope.drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            nativeCanvas.drawColor(backgroundColor.toArgb())
            bitmap?.let { nativeCanvas.drawBitmap(it, imageMatrix, bitmapPaint) }
            drawOverlay(nativeCanvas, cornerLengthPx)
        }
    }

    internal fun beginGesture(x: Float, y: Float): Boolean {
        if (bitmap == null) return false
        stopFling()
        dragMode = findDragMode(x, y)
        return dragMode == DragMode.MOVE_IMAGE
    }

    internal fun dragBy(dx: Float, dy: Float) {
        if (bitmap == null) return
        when (dragMode) {
            DragMode.MOVE_IMAGE -> {
                imageMatrix.postTranslate(dx, dy)
                ensureImageCoversCrop()
            }
            DragMode.NONE -> Unit
            else -> {
                if (activeAspectRatio() == null) {
                    resizeFreeCrop(dx, dy)
                } else {
                    resizeFixedCrop(dx, dy)
                }
                ensureCropInsideContent()
                ensureImageCoversCrop()
            }
        }
        invalidate()
    }

    internal fun pinchZoomBy(factor: Float, pivotX: Float, pivotY: Float) {
        if (bitmap == null || cropRect.isEmpty || factor <= 0f) return
        scaleImageBy(factor.coerceIn(0.85f, 1.18f), pivotX, pivotY)
        ensureImageCoversCrop()
        invalidate()
    }

    internal fun endGesture() {
        dragMode = DragMode.NONE
    }

    internal fun startImageFling(context: Context, xVelocity: Float, yVelocity: Float) {
        if (bitmap == null || cropRect.isEmpty) return
        val configuration = ViewConfiguration.get(context)
        val minFlingVelocity = configuration.scaledMinimumFlingVelocity
        val maxFlingVelocity = configuration.scaledMaximumFlingVelocity.toFloat()
        if (abs(xVelocity) < minFlingVelocity && abs(yVelocity) < minFlingVelocity) return

        val mapped = mappedBitmapRect()
        val minDeltaX: Int
        val maxDeltaX: Int
        if (mapped.width() > cropRect.width()) {
            minDeltaX = (cropRect.right - mapped.right).roundToInt()
            maxDeltaX = (cropRect.left - mapped.left).roundToInt()
        } else {
            minDeltaX = 0
            maxDeltaX = 0
        }

        val minDeltaY: Int
        val maxDeltaY: Int
        if (mapped.height() > cropRect.height()) {
            minDeltaY = (cropRect.bottom - mapped.bottom).roundToInt()
            maxDeltaY = (cropRect.top - mapped.top).roundToInt()
        } else {
            minDeltaY = 0
            maxDeltaY = 0
        }

        if (minDeltaX == 0 && maxDeltaX == 0 && minDeltaY == 0 && maxDeltaY == 0) return

        val velocityX = if (minDeltaX == 0 && maxDeltaX == 0) {
            0
        } else {
            xVelocity.coerceIn(-maxFlingVelocity, maxFlingVelocity).roundToInt()
        }
        val velocityY = if (minDeltaY == 0 && maxDeltaY == 0) {
            0
        } else {
            yVelocity.coerceIn(-maxFlingVelocity, maxFlingVelocity).roundToInt()
        }
        if (velocityX == 0 && velocityY == 0) return

        val scroller = flingScroller ?: OverScroller(context.applicationContext).also {
            flingScroller = it
        }
        flingLastX = 0
        flingLastY = 0
        scroller.fling(
            0,
            0,
            velocityX,
            velocityY,
            minDeltaX,
            maxDeltaX,
            minDeltaY,
            maxDeltaY,
        )
        flingVersion++
    }

    internal fun stepFling(): Boolean {
        val scroller = flingScroller ?: return false
        if (!scroller.computeScrollOffset()) return false
        val dx = (scroller.currX - flingLastX).toFloat()
        val dy = (scroller.currY - flingLastY).toFloat()
        flingLastX = scroller.currX
        flingLastY = scroller.currY
        imageMatrix.postTranslate(dx, dy)
        ensureImageCoversCrop()
        invalidate()
        return true
    }

    private fun configureInitialState() {
        updateContentRect()
        initCropRect()
        fitImageToCrop()
    }

    private fun updateContentRect() {
        contentRect.set(0f, 0f, viewportSize.width.toFloat(), viewportSize.height.toFloat())
    }

    private fun initCropRect() {
        if (viewportSize.width == 0 || viewportSize.height == 0) return
        updateContentRect()
        val availableWidth = contentRect.width()
        val availableHeight = contentRect.height()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val ratio = activeAspectRatio()
        val cropWidth: Float
        val cropHeight: Float
        if (ratio != null) {
            var targetWidth = availableWidth * 0.82f
            var targetHeight = targetWidth / ratio
            if (targetHeight > availableHeight * 0.72f) {
                targetHeight = availableHeight * 0.72f
                targetWidth = targetHeight * ratio
            }
            cropWidth = targetWidth
            cropHeight = targetHeight
        } else {
            cropWidth = availableWidth * 0.84f
            cropHeight = min(availableHeight * 0.62f, cropWidth * 0.78f)
        }

        val left = contentRect.centerX() - cropWidth / 2f
        val top = contentRect.centerY() - cropHeight / 2f
        cropRect.set(left, top, left + cropWidth, top + cropHeight)
    }

    private fun fitImageToCrop() {
        val source = bitmap ?: return
        if (cropRect.isEmpty) return
        bitmapBounds.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val scale = max(cropRect.width() / bitmapBounds.width(), cropRect.height() / bitmapBounds.height())
        val dx = cropRect.centerX() - bitmapBounds.centerX() * scale
        val dy = cropRect.centerY() - bitmapBounds.centerY() * scale
        baseImageScale = scale
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        ensureImageCoversCrop()
    }

    private fun ensureImageCoversCrop() {
        if (bitmap == null || cropRect.isEmpty) return
        val mapped = mappedBitmapRect()
        if (mapped.width() <= 0f || mapped.height() <= 0f) return

        val scale = max(
            if (mapped.width() < cropRect.width()) cropRect.width() / mapped.width() else 1f,
            if (mapped.height() < cropRect.height()) cropRect.height() / mapped.height() else 1f,
        )
        if (scale > 1f) {
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
        }

        val adjusted = mappedBitmapRect()
        var dx = 0f
        var dy = 0f
        if (adjusted.left > cropRect.left) {
            dx = cropRect.left - adjusted.left
        } else if (adjusted.right < cropRect.right) {
            dx = cropRect.right - adjusted.right
        }
        if (adjusted.top > cropRect.top) {
            dy = cropRect.top - adjusted.top
        } else if (adjusted.bottom < cropRect.bottom) {
            dy = cropRect.bottom - adjusted.bottom
        }
        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }

    private fun mappedBitmapRect(): RectF {
        imageBounds.set(bitmapBounds)
        imageMatrix.mapRect(imageBounds)
        return imageBounds
    }

    private fun resizeFreeCrop(dx: Float, dy: Float) {
        val next = RectF(cropRect)
        when (dragMode) {
            DragMode.LEFT -> next.left += dx
            DragMode.TOP -> next.top += dy
            DragMode.RIGHT -> next.right += dx
            DragMode.BOTTOM -> next.bottom += dy
            DragMode.TOP_LEFT -> {
                next.left += dx
                next.top += dy
            }
            DragMode.TOP_RIGHT -> {
                next.right += dx
                next.top += dy
            }
            DragMode.BOTTOM_LEFT -> {
                next.left += dx
                next.bottom += dy
            }
            DragMode.BOTTOM_RIGHT -> {
                next.right += dx
                next.bottom += dy
            }
            else -> Unit
        }
        next.left = next.left.coerceAtLeast(contentRect.left)
        next.top = next.top.coerceAtLeast(contentRect.top)
        next.right = next.right.coerceAtMost(contentRect.right)
        next.bottom = next.bottom.coerceAtMost(contentRect.bottom)

        val minCropSize = 96f * density
        if (next.width() < minCropSize) {
            if (dragMode == DragMode.LEFT || dragMode == DragMode.TOP_LEFT || dragMode == DragMode.BOTTOM_LEFT) {
                next.left = next.right - minCropSize
            } else {
                next.right = next.left + minCropSize
            }
        }
        if (next.height() < minCropSize) {
            if (dragMode == DragMode.TOP || dragMode == DragMode.TOP_LEFT || dragMode == DragMode.TOP_RIGHT) {
                next.top = next.bottom - minCropSize
            } else {
                next.bottom = next.top + minCropSize
            }
        }

        next.left = next.left.coerceAtLeast(contentRect.left)
        next.top = next.top.coerceAtLeast(contentRect.top)
        next.right = next.right.coerceAtMost(contentRect.right)
        next.bottom = next.bottom.coerceAtMost(contentRect.bottom)
        cropRect.set(next)
    }

    private fun resizeFixedCrop(dx: Float, dy: Float) {
        val ratio = activeAspectRatio() ?: return
        val minCropSize = 96f * density
        val centerX = cropRect.centerX()
        val centerY = cropRect.centerY()
        val widthDelta = when (dragMode) {
            DragMode.LEFT,
            DragMode.TOP_LEFT,
            DragMode.BOTTOM_LEFT -> -dx * 2f
            DragMode.RIGHT,
            DragMode.TOP_RIGHT,
            DragMode.BOTTOM_RIGHT -> dx * 2f
            else -> 0f
        }
        val heightDelta = when (dragMode) {
            DragMode.TOP,
            DragMode.TOP_LEFT,
            DragMode.TOP_RIGHT -> -dy * 2f
            DragMode.BOTTOM,
            DragMode.BOTTOM_LEFT,
            DragMode.BOTTOM_RIGHT -> dy * 2f
            else -> 0f
        }

        var nextWidth = cropRect.width() + widthDelta
        if (heightDelta != 0f && abs(heightDelta) > abs(widthDelta)) {
            nextWidth = (cropRect.height() + heightDelta) * ratio
        }
        nextWidth = nextWidth.coerceAtLeast(minCropSize)
        var nextHeight = nextWidth / ratio
        if (nextHeight < minCropSize) {
            nextHeight = minCropSize
            nextWidth = nextHeight * ratio
        }

        val maxWidth = min(centerX - contentRect.left, contentRect.right - centerX) * 2f
        val maxHeight = min(centerY - contentRect.top, contentRect.bottom - centerY) * 2f
        if (nextWidth > maxWidth) {
            nextWidth = maxWidth
            nextHeight = nextWidth / ratio
        }
        if (nextHeight > maxHeight) {
            nextHeight = maxHeight
            nextWidth = nextHeight * ratio
        }
        cropRect.set(centerX - nextWidth / 2f, centerY - nextHeight / 2f, centerX + nextWidth / 2f, centerY + nextHeight / 2f)
    }

    private fun ensureCropInsideContent() {
        var dx = 0f
        var dy = 0f
        if (cropRect.left < contentRect.left) dx = contentRect.left - cropRect.left
        if (cropRect.right > contentRect.right) dx = contentRect.right - cropRect.right
        if (cropRect.top < contentRect.top) dy = contentRect.top - cropRect.top
        if (cropRect.bottom > contentRect.bottom) dy = contentRect.bottom - cropRect.bottom
        cropRect.offset(dx, dy)
    }

    private fun findDragMode(x: Float, y: Float): DragMode {
        if (!cropFrameResizeEnabled) {
            return DragMode.MOVE_IMAGE
        }
        val edgeSlop = 28f * density
        val nearLeft = abs(x - cropRect.left) <= edgeSlop
        val nearRight = abs(x - cropRect.right) <= edgeSlop
        val nearTop = abs(y - cropRect.top) <= edgeSlop
        val nearBottom = abs(y - cropRect.bottom) <= edgeSlop
        val insideVertical = y in cropRect.top..cropRect.bottom
        val insideHorizontal = x in cropRect.left..cropRect.right

        return when {
            nearLeft && nearTop -> DragMode.TOP_LEFT
            nearRight && nearTop -> DragMode.TOP_RIGHT
            nearLeft && nearBottom -> DragMode.BOTTOM_LEFT
            nearRight && nearBottom -> DragMode.BOTTOM_RIGHT
            nearLeft && insideVertical -> DragMode.LEFT
            nearRight && insideVertical -> DragMode.RIGHT
            nearTop && insideHorizontal -> DragMode.TOP
            nearBottom && insideHorizontal -> DragMode.BOTTOM
            cropRect.contains(x, y) -> DragMode.MOVE_IMAGE
            else -> DragMode.MOVE_IMAGE
        }
    }

    private fun drawOverlay(canvas: AndroidCanvas, cornerLengthPx: Float) {
        if (cropRect.isEmpty) return
        val width = viewportSize.width.toFloat()
        val height = viewportSize.height.toFloat()
        val layer = canvas.saveLayer(0f, 0f, width, height, null)
        canvas.drawRect(0f, 0f, width, height, overlayPaint)
        drawClearCropShape(canvas)
        canvas.restoreToCount(layer)

        if (showGridLines && cropShapeState is CropShape.Rectangle) {
            val thirdWidth = cropRect.width() / 3f
            val thirdHeight = cropRect.height() / 3f
            for (i in 1..2) {
                val x = cropRect.left + thirdWidth * i
                canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
                val y = cropRect.top + thirdHeight * i
                canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
            }
        }
        drawShapeGuide(canvas)
        if (showCornerHandles) {
            drawHandles(canvas, cornerLengthPx)
        }
    }

    private fun drawClearCropShape(canvas: AndroidCanvas) {
        when (val shape = cropShapeState) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, clearPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, clearPaint)
            is CropShape.BitmapMask -> canvas.drawBitmap(shape.bitmap, null, cropRect, maskClearPaint)
        }
    }

    private fun drawShapeGuide(canvas: AndroidCanvas) {
        when (cropShapeState) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, borderPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, borderPaint)
            is CropShape.BitmapMask -> canvas.drawRect(cropRect, borderPaint)
        }
    }

    private fun drawHandles(canvas: AndroidCanvas, cornerLengthPx: Float) {
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val size = cornerLengthPx
        canvas.drawLine(l, t, l + size, t, handlePaint)
        canvas.drawLine(l, t, l, t + size, handlePaint)
        canvas.drawLine(r, t, r - size, t, handlePaint)
        canvas.drawLine(r, t, r, t + size, handlePaint)
        canvas.drawLine(l, b, l + size, b, handlePaint)
        canvas.drawLine(l, b, l, b - size, handlePaint)
        canvas.drawLine(r, b, r - size, b, handlePaint)
        canvas.drawLine(r, b, r, b - size, handlePaint)
    }

    private fun applyCropShapeMask(source: Bitmap): Bitmap {
        val shape = cropShapeState
        if (shape is CropShape.Rectangle) return source

        val output = createBitmap(source.width, source.height)
        val canvas = AndroidCanvas(output)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outputRect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        when (shape) {
            CropShape.Circle -> canvas.drawOval(outputRect, maskPaint)
            is CropShape.BitmapMask -> canvas.drawBitmap(shape.bitmap, null, outputRect, maskPaint)
            else -> Unit
        }
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, maskPaint)
        maskPaint.xfermode = null
        return output
    }

    private suspend fun loadRemoteImage(
        context: Context,
        url: String,
        imageLoader: CropImageLoader?,
    ): Bitmap {
        if (imageLoader == null) {
            throw IllegalStateException("Remote image requires a CropImageLoader.")
        }
        return suspendCancellableCoroutine { continuation ->
            var cancelable: Cancelable = Cancelable.NONE
            cancelable = imageLoader.load(
                ImageLoadRequest(url, requestedDecodeWidth(context), requestedDecodeHeight(context)),
                object : ImageLoadCallback {
                    override fun onSuccess(bitmap: Bitmap) {
                        if (continuation.isActive) {
                            continuation.resume(bitmap)
                        }
                    }

                    override fun onError(error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                },
            )
            continuation.invokeOnCancellation {
                cancelable.cancel()
            }
        }
    }

    private fun decodeUri(context: Context, uri: android.net.Uri, requestedWidth: Int, requestedHeight: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
        }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalArgumentException("Unable to open image uri: $uri")
    }

    private fun decodeFile(file: File, requestedWidth: Int, requestedHeight: Int): Bitmap {
        if (!file.exists()) {
            throw IllegalArgumentException("Image file does not exist: ${file.absolutePath}")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(bounds, requestedWidth, requestedHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Unable to decode image file: ${file.absolutePath}")
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, requestedWidth: Int, requestedHeight: Int): Int {
        val sourceHeight = options.outHeight
        val sourceWidth = options.outWidth
        if (sourceHeight <= 0 || sourceWidth <= 0) return 1
        var inSampleSize = 1
        while (true) {
            val sampledWidth = sourceWidth / inSampleSize
            val sampledHeight = sourceHeight / inSampleSize
            val sampledPixels = sampledWidth.toLong() * sampledHeight.toLong()
            val shouldSample = sampledWidth > MAX_DECODED_LONG_EDGE ||
                sampledHeight > MAX_DECODED_LONG_EDGE ||
                sampledPixels > MAX_DECODED_PIXELS
            if (!shouldSample) break
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun requestedDecodeWidth(context: Context): Int {
        return (viewportSize.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) * 2
    }

    private fun requestedDecodeHeight(context: Context): Int {
        return (viewportSize.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels) * 2
    }

    private fun activeAspectRatio(): Float? {
        return cropShapeState.aspectRatioOrNull()
    }

    private fun scaleImageBy(factor: Float, pivotX: Float, pivotY: Float) {
        val constrainedFactor = constrainScaleFactor(factor)
        if (constrainedFactor == 1f) return
        imageMatrix.postScale(constrainedFactor, constrainedFactor, pivotX, pivotY)
    }

    private fun constrainScaleFactor(factor: Float): Float {
        if (factor <= 1f) return factor
        val currentScale = currentImageScale()
        val maxScale = baseImageScale * maxZoomScale
        if (currentScale <= 0f || maxScale <= 0f) return factor
        val remainingFactor = maxScale / currentScale
        return when {
            remainingFactor <= 1f -> 1f
            else -> min(factor, remainingFactor)
        }
    }

    private fun clampImageScaleToMax() {
        if (bitmap == null || cropRect.isEmpty) return
        val currentScale = currentImageScale()
        val maxScale = baseImageScale * maxZoomScale
        if (currentScale <= maxScale || currentScale <= 0f || maxScale <= 0f) return
        imageMatrix.postScale(maxScale / currentScale, maxScale / currentScale, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
    }

    private fun currentImageScale(): Float {
        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }

    private fun stopFling() {
        flingScroller?.let { scroller ->
            if (!scroller.isFinished) {
                scroller.abortAnimation()
            }
        }
        flingLastX = 0
        flingLastY = 0
    }

    private fun invalidate() {
        drawVersion++
    }

    private fun Float.normalizedZoom(): Float {
        return if (isNaN() || isInfinite()) 4f else coerceAtLeast(1f)
    }

    private fun CropShape.aspectRatioOrNull(): Float? {
        return when (this) {
            is CropShape.Rectangle -> if (widthRatio > 0 && heightRatio > 0) {
                widthRatio.toFloat() / heightRatio.toFloat()
            } else {
                null
            }
            CropShape.Circle -> 1f
            is CropShape.BitmapMask -> if (widthRatio > 0 && heightRatio > 0) {
                widthRatio.toFloat() / heightRatio.toFloat()
            } else {
                1f
            }
        }
    }

    private companion object {
        const val MAX_DECODED_LONG_EDGE = 12_000
        const val MAX_DECODED_PIXELS = 24_000_000
        const val DEFAULT_SAVE_FILE_PREFIX = "crop"
        const val DEFAULT_JPEG_QUALITY = 94
    }
}

private suspend fun PointerInputScope.cropGestureInput(
    state: CropViewState,
    context: Context,
    touchSlop: Float,
) {
    // One pointer moves/resizes the image or crop frame; two or more pointers perform pinch zoom.
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var activePointerId = down.id
        var velocityTracker = VelocityTracker()
        velocityTracker.addPosition(down.uptimeMillis, down.position)
        var canFling = state.beginGesture(down.position.x, down.position.y)
        var movedPastSlop = false
        val downPosition = down.position

        while (true) {
            val event = awaitPointerEvent()
            val pressedChanges = event.changes.filter { it.pressed }
            if (pressedChanges.isEmpty()) break

            if (pressedChanges.size > 1) {
                canFling = false
                movedPastSlop = true
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom.isFinite() && zoom > 0f && centroid.isSpecifiedValue()) {
                    state.pinchZoomBy(zoom, centroid.x, centroid.y)
                }
            } else {
                val change = pressedChanges.find { it.id == activePointerId } ?: pressedChanges.first()
                activePointerId = change.id
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - change.previousPosition.x
                val dy = change.position.y - change.previousPosition.y
                if (!movedPastSlop && abs(change.position.x - downPosition.x) + abs(change.position.y - downPosition.y) > touchSlop) {
                    movedPastSlop = true
                }
                if (movedPastSlop) {
                    state.dragBy(dx, dy)
                }
            }

            event.changes.forEach { change ->
                if (change.positionChanged()) {
                    change.consume()
                }
            }
        }

        if (canFling && movedPastSlop) {
            val velocity = velocityTracker.calculateVelocity()
            state.startImageFling(context, velocity.x, velocity.y)
        }
        state.endGesture()
    }
}

private fun Offset.isSpecifiedValue(): Boolean {
    return x.isFinite() && y.isFinite()
}
