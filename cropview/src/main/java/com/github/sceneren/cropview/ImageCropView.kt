package com.github.sceneren.cropview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.createBitmap
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 中文：自包含裁剪控件，负责图片加载、手势、裁剪框绘制、形状蒙版和 Bitmap 导出。
 * English: A self-contained crop view that handles image loading, gestures, crop-frame drawing,
 * shape masking, and bitmap export without depending on a third-party crop widget.
 */
class ImageCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface ImageLoadListener {
        fun onLoadStart() = Unit

        fun onLoadSuccess(bitmap: Bitmap) = Unit

        fun onLoadError(error: Throwable) = Unit
    }

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

    var imageLoadListener: ImageLoadListener? = null

    /**
     * 中文：控制矩形裁剪区域中是否绘制三分线。
     * English: Controls whether the rule-of-thirds guide lines are drawn inside rectangle crops.
     */
    var showGridLines: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 中文：控制是否绘制 L 形角标。
     * English: Controls whether the L-shaped corner handles are drawn.
     */
    var showCornerHandles: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 中文：为 false 时，拖动裁剪框边缘和角点会移动图片，而不是缩放裁剪框。
     * English: When false, dragging crop-frame edges and corners moves the image instead of resizing the frame.
     */
    var cropFrameResizeEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && dragMode != DragMode.MOVE_IMAGE) {
                dragMode = DragMode.NONE
            }
            invalidate()
        }

    /**
     * 中文：可见角标使用的颜色。
     * English: Color used for the visible corner handles.
     */
    var cornerColor: Int = Color.WHITE
        set(value) {
            field = value
            handlePaint.color = value
            invalidate()
        }

    /**
     * 中文：可见角标使用的线宽，单位为像素。
     * English: Stroke width, in pixels, used for the visible corner handles.
     */
    var cornerStrokeWidth: Float = 4f.dp()
        set(value) {
            field = value.coerceAtLeast(0f)
            handlePaint.strokeWidth = field
            invalidate()
        }

    /**
     * 中文：每段可见角标的长度，单位为像素。
     * English: Length, in pixels, of each visible corner-handle segment.
     */
    var cornerLength: Float = 28f.dp()
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /**
     * 中文：相对初始铺满裁剪框状态的最大放大倍数，默认 4.0，最小值为 1。
     * English: Maximum zoom multiplier relative to the initial crop-covering scale. Default is 4.0. Minimum is 1.
     */
    var maxZoomScale: Float = DEFAULT_MAX_ZOOM_SCALE
        set(value) {
            val normalized = if (value.isNaN() || value.isInfinite()) DEFAULT_MAX_ZOOM_SCALE else value
            field = normalized.coerceAtLeast(1f)
            clampImageScaleToMax()
            invalidate()
        }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeWidth = 4f.dp()
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val maskClearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val bitmapBounds = RectF()
    private val imageBounds = RectF()
    private val cropRect = RectF()
    private val contentRect = RectF()

    private val minCropSize = 96f.dp()
    private val edgeSlop = 28f.dp()
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maxFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val flingScroller = OverScroller(context)

    private var bitmap: Bitmap? = null
    private var localLoadFuture: Future<*>? = null
    private var remoteLoad: Cancelable? = null
    private var dragMode = DragMode.NONE
    private var cropShape: CropShape = CropShape.Rectangle()
    private var lastX = 0f
    private var lastY = 0f
    private var movedPastSlop = false
    private var velocityTracker: VelocityTracker? = null
    private var canFlingImage = false
    private var flingLastX = 0
    private var flingLastY = 0
    private var baseImageScale = 1f
    private val matrixValues = FloatArray(9)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        isClickable = true
        readStyledAttributes(context, attrs, defStyleAttr)
    }

    /**
     * 中文：加载本地 Uri、本地 File、原始 Bitmap 或远程 URL；远程 URL 需要调用方提供加载器。
     * English: Loads a local uri/file, raw bitmap, or remote url. Remote urls require a caller-supplied loader.
     */
    fun setImageSource(source: CropImageSource, imageLoader: CropImageLoader? = null) {
        cancelCurrentLoad()
        imageLoadListener?.onLoadStart()
        when (source) {
            is CropImageSource.BitmapSource -> {
                setImageBitmapInternal(source.bitmap)
                imageLoadListener?.onLoadSuccess(source.bitmap)
            }
            is CropImageSource.UrlSource -> loadRemoteImage(source.url, imageLoader)
            is CropImageSource.UriSource -> loadLocalImage {
                decodeUri(source.uri, requestedDecodeWidth(), requestedDecodeHeight())
            }
            is CropImageSource.FileSource -> loadLocalImage {
                decodeFile(source.file, requestedDecodeWidth(), requestedDecodeHeight())
            }
        }
    }

    /**
     * 中文：把已经解码好的 Bitmap 设置为裁剪源。
     * English: Sets an already decoded bitmap as the crop source.
     */
    fun setImageBitmap(bitmap: Bitmap) {
        cancelCurrentLoad()
        setImageBitmapInternal(bitmap)
    }

    /**
     * 中文：设置当前裁剪形状，并根据该形状的宽高比重新计算裁剪框。
     * English: Sets the active crop shape and recalculates the crop frame for that shape's aspect ratio.
     */
    fun setCropShape(shape: CropShape) {
        stopFling()
        cropShape = shape
        initCropRect()
        ensureImageCoversCrop()
        invalidate()
    }

    /**
     * 中文：设置固定宽高比矩形裁剪的便捷 API。
     * English: Convenience API for a rectangle crop with a fixed aspect ratio.
     */
    fun setAspectRatio(width: Int, height: Int) {
        setCropShape(CropShape.Rectangle(width, height))
    }

    /**
     * 中文：使用可自由缩放的矩形裁剪框。
     * English: Uses a free-size rectangle crop frame.
     */
    fun clearAspectRatio() {
        setCropShape(CropShape.Rectangle())
    }

    /**
     * 中文：更新可见角标样式。
     * English: Updates the visible corner-handle style.
     */
    fun setCornerStyle(color: Int, strokeWidth: Float, length: Float) {
        cornerColor = color
        cornerStrokeWidth = strokeWidth
        cornerLength = length
    }

    /**
     * 中文：非矩形裁剪需要使用 PNG 等保留透明通道的输出格式。
     * English: Non-rectangle crops need alpha-preserving output formats such as PNG.
     */
    fun requiresAlphaOutput(): Boolean {
        return cropShape !is CropShape.Rectangle
    }

    /**
     * 中文：围绕裁剪框中心旋转图片。
     * English: Rotates the image around the crop frame center.
     */
    fun rotateBy(degrees: Float) {
        if (bitmap == null || cropRect.isEmpty) return
        stopFling()
        imageMatrix.postRotate(degrees, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    /**
     * 中文：围绕裁剪框中心缩放图片，并保持图片覆盖完整裁剪框。
     * English: Scales the image around the crop frame center and keeps the crop frame covered.
     */
    fun zoomBy(factor: Float) {
        if (bitmap == null || cropRect.isEmpty || factor <= 0f) return
        stopFling()
        scaleImageBy(factor, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    /**
     * 中文：恢复初始裁剪框和图片变换。
     * English: Restores the initial crop frame and image transform.
     */
    fun resetImage() {
        if (bitmap == null || width == 0 || height == 0) return
        stopFling()
        configureInitialState()
        invalidate()
    }

    /**
     * 中文：按当前屏幕上的裁剪框尺寸导出 Bitmap。
     * English: Exports a bitmap using the current on-screen crop-frame size.
     */
    fun getCroppedBitmap(): Bitmap? {
        val outputWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val outputHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        return getCroppedBitmap(outputWidth, outputHeight)
    }

    /**
     * 中文：按指定输出尺寸导出 Bitmap，并应用当前裁剪形状作为蒙版。
     * English: Exports a bitmap with the requested output size and applies the active crop shape as a mask.
     */
    fun getCroppedBitmap(outputWidth: Int, outputHeight: Int): Bitmap? {
        val source = bitmap ?: return null
        if (cropRect.isEmpty || outputWidth <= 0 || outputHeight <= 0) return null
        val result = createBitmap(outputWidth, outputHeight)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.scale(outputWidth / cropRect.width(), outputHeight / cropRect.height())
        canvas.translate(-cropRect.left, -cropRect.top)
        canvas.drawBitmap(source, imageMatrix, bitmapPaint)
        return applyCropShapeMask(result)
    }

    /**
     * 中文：按当前裁剪框尺寸裁剪并保存到应用私有缓存目录，成功回调返回文件绝对路径。
     * English: Crops at the current frame size, saves into app-private cache, and returns an absolute file path.
     */
    @JvmOverloads
    fun cropAndSaveToCache(
        callback: CropSaveCallback,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ) {
        val outputWidth = cropRect.width().roundToInt().coerceAtLeast(1)
        val outputHeight = cropRect.height().roundToInt().coerceAtLeast(1)
        cropAndSaveToCache(outputWidth, outputHeight, callback, filePrefix, jpegQuality)
    }

    /**
     * 中文：按指定输出尺寸裁剪并保存；PNG/JPEG 选择由当前裁剪形状决定。
     * English: Crops to the requested output size and chooses PNG/JPEG from the active crop shape.
     */
    @JvmOverloads
    fun cropAndSaveToCache(
        outputWidth: Int,
        outputHeight: Int,
        callback: CropSaveCallback,
        filePrefix: String = DEFAULT_SAVE_FILE_PREFIX,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ) {
        val cropped = getCroppedBitmap(outputWidth, outputHeight)
        if (cropped == null) {
            postCropSaveError(callback, IllegalStateException("Image is not ready to crop."))
            return
        }

        val appContext = context.applicationContext
        val keepAlpha = requiresAlphaOutput()
        try {
            // Disk I/O runs on the existing single-thread executor; callbacks are marshalled to the main thread.
            loadExecutor.submit {
                try {
                    val result = CropImageCacheSaver.saveToPrivateCache(
                        context = appContext,
                        bitmap = cropped,
                        keepAlpha = keepAlpha,
                        filePrefix = filePrefix,
                        jpegQuality = jpegQuality,
                    )
                    mainHandler.post {
                        callback.onCropSaveSuccess(result)
                    }
                } catch (error: Throwable) {
                    postCropSaveError(callback, error)
                }
            }
        } catch (error: RejectedExecutionException) {
            postCropSaveError(callback, error)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && bitmap != null) {
            configureInitialState()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(16, 17, 20))
        bitmap?.let { canvas.drawBitmap(it, imageMatrix, bitmapPaint) }
        drawOverlay(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null) return true

        scaleDetector.onTouchEvent(event)
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
                dragMode = findDragMode(event.x, event.y)
                canFlingImage = dragMode == DragMode.MOVE_IMAGE
                lastX = event.x
                lastY = event.y
                movedPastSlop = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                canFlingImage = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
                    canFlingImage = false
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!movedPastSlop && abs(dx) + abs(dy) > touchSlop) {
                    movedPastSlop = true
                }
                if (movedPastSlop) {
                    handleDrag(event.x, event.y, dx, dy)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && canFlingImage && movedPastSlop) {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        startImageFling(tracker.xVelocity, tracker.yVelocity)
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                canFlingImage = false
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = DragMode.NONE
                return true
            }
        }
        return true
    }

    override fun computeScroll() {
        if (flingScroller.computeScrollOffset()) {
            val dx = (flingScroller.currX - flingLastX).toFloat()
            val dy = (flingScroller.currY - flingLastY).toFloat()
            flingLastX = flingScroller.currX
            flingLastY = flingScroller.currY
            imageMatrix.postTranslate(dx, dy)
            ensureImageCoversCrop()
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        cancelCurrentLoad()
        velocityTracker?.recycle()
        velocityTracker = null
        loadExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun loadRemoteImage(url: String, imageLoader: CropImageLoader?) {
        if (imageLoader == null) {
            val error = IllegalStateException("Remote image requires a CropImageLoader.")
            imageLoadListener?.onLoadError(error)
            return
        }
        remoteLoad = imageLoader.load(
            ImageLoadRequest(url, requestedDecodeWidth(), requestedDecodeHeight()),
            object : ImageLoadCallback {
                override fun onSuccess(bitmap: Bitmap) {
                    mainHandler.post {
                        setImageBitmapInternal(bitmap)
                        imageLoadListener?.onLoadSuccess(bitmap)
                    }
                }

                override fun onError(error: Throwable) {
                    mainHandler.post {
                        imageLoadListener?.onLoadError(error)
                    }
                }
            },
        )
    }

    /**
     * 中文：在 UI 线程外解码本地图片源，并忽略设置新图片源之前的过期结果。
     * English: Decodes local sources away from the UI thread and ignores stale results after a new source is set.
     */
    private fun loadLocalImage(decode: () -> Bitmap) {
        var future: Future<*>? = null
        future = loadExecutor.submit {
            try {
                val decoded = decode()
                mainHandler.post {
                    if (localLoadFuture == future) {
                        setImageBitmapInternal(decoded)
                        imageLoadListener?.onLoadSuccess(decoded)
                    }
                }
            } catch (error: Throwable) {
                mainHandler.post {
                    if (localLoadFuture == future) {
                        imageLoadListener?.onLoadError(error)
                    }
                }
            }
        }
        localLoadFuture = future
    }

    internal fun setImageBitmapInternal(nextBitmap: Bitmap) {
        stopFling()
        bitmap = nextBitmap
        bitmapBounds.set(0f, 0f, nextBitmap.width.toFloat(), nextBitmap.height.toFloat())
        if (width > 0 && height > 0) {
            configureInitialState()
        }
        invalidate()
    }

    /**
     * 中文：当新 Bitmap 或 View 尺寸可用时，初始化裁剪框和图片矩阵。
     * English: Initializes both the crop frame and image matrix when a new bitmap or view size is available.
     */
    private fun configureInitialState() {
        updateContentRect()
        initCropRect()
        fitImageToCrop()
    }

    /**
     * 中文：内容区域会排除调用方设置的 padding。
     * English: Content bounds exclude any padding applied by callers.
     */
    private fun updateContentRect() {
        contentRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat(),
        )
    }

    /**
     * 中文：在 View 中心放置一个合理的初始裁剪框。
     * English: Places a sensible initial crop frame in the center of the view.
     */
    private fun initCropRect() {
        if (width == 0 || height == 0) return
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

    /**
     * 中文：调整图片位置和缩放，使当前裁剪框被完整覆盖。
     * English: Fits the image so the active crop frame is fully covered.
     */
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

    /**
     * 中文：保持图片足够大且位置合适，避免裁剪框内出现空白像素。
     * English: Keeps the image large enough and positioned so no empty pixels appear inside the crop frame.
     */
    internal fun ensureImageCoversCrop() {
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

    /**
     * 中文：根据当前命中区域，把拖动分发给图片移动或裁剪框缩放。
     * English: Dispatches a drag either to image movement or crop-frame resizing.
     */
    private fun handleDrag(x: Float, y: Float, dx: Float, dy: Float) {
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

    /**
     * 中文：通过当前激活的边或角缩放自由比例矩形。
     * English: Resizes a free-aspect rectangle by the active edge or corner.
     */
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

    /**
     * 中文：在保持中心点不变的情况下缩放固定比例裁剪框。
     * English: Resizes a fixed-aspect crop frame while preserving its center.
     */
    private fun resizeFixedCrop(dx: Float, dy: Float) {
        val ratio = activeAspectRatio() ?: return
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

    /**
     * 中文：防止裁剪框编辑后超出 View 的内容区域。
     * English: Prevents crop-frame edits from moving outside this view's content bounds.
     */
    private fun ensureCropInsideContent() {
        var dx = 0f
        var dy = 0f
        if (cropRect.left < contentRect.left) dx = contentRect.left - cropRect.left
        if (cropRect.right > contentRect.right) dx = contentRect.right - cropRect.right
        if (cropRect.top < contentRect.top) dy = contentRect.top - cropRect.top
        if (cropRect.bottom > contentRect.bottom) dy = contentRect.bottom - cropRect.bottom
        cropRect.offset(dx, dy)
    }

    /**
     * 中文：启用裁剪框缩放时，边和角用于缩放裁剪框；其他位置用于拖动图片。
     * English: Edges and corners resize the frame when enabled; every other point drags the image.
     */
    private fun findDragMode(x: Float, y: Float): DragMode {
        if (!cropFrameResizeEnabled) {
            return DragMode.MOVE_IMAGE
        }
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

    /**
     * 中文：绘制裁剪区域外的遮罩、可选参考线、边框和角标。
     * English: Draws the dimmed outside area, optional guide lines, border, and corner handles.
     */
    private fun drawOverlay(canvas: Canvas) {
        if (cropRect.isEmpty) return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        drawClearCropShape(canvas)
        canvas.restoreToCount(layer)

        if (showGridLines && cropShape is CropShape.Rectangle) {
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
            drawHandles(canvas)
        }
    }

    /**
     * 中文：从遮罩层中扣出裁剪窗口；Bitmap 蒙版使用 alpha 扣出自定义形状。
     * English: Clears the crop window from the overlay. Bitmap masks use alpha to punch through custom shapes.
     */
    private fun drawClearCropShape(canvas: Canvas) {
        when (val shape = cropShape) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, clearPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, clearPaint)
            is CropShape.BitmapMask -> canvas.drawBitmap(shape.bitmap, null, cropRect, maskClearPaint)
        }
    }

    /**
     * 中文：绘制当前形状的可见裁剪框轮廓。
     * English: Draws the visible crop-frame outline for the current shape.
     */
    private fun drawShapeGuide(canvas: Canvas) {
        when (val shape = cropShape) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, borderPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, borderPaint)
            is CropShape.BitmapMask -> {
                canvas.drawRect(cropRect, borderPaint)
            }
        }
    }

    /**
     * 中文：为导出的 Bitmap 应用非矩形输出蒙版。
     * English: Applies non-rectangle output masks to the exported bitmap.
     */
    private fun applyCropShapeMask(source: Bitmap): Bitmap {
        val shape = cropShape
        if (shape is CropShape.Rectangle) return source

        val output = createBitmap(source.width, source.height)
        val canvas = Canvas(output)
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

    /**
     * 中文：绘制 L 形角标。
     * English: Draws L-shaped corner handles.
     */
    private fun drawHandles(canvas: Canvas) {
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val size = cornerLength
        canvas.drawLine(l, t, l + size, t, handlePaint)
        canvas.drawLine(l, t, l, t + size, handlePaint)
        canvas.drawLine(r, t, r - size, t, handlePaint)
        canvas.drawLine(r, t, r, t + size, handlePaint)
        canvas.drawLine(l, b, l + size, b, handlePaint)
        canvas.drawLine(l, b, l, b - size, handlePaint)
        canvas.drawLine(r, b, r - size, b, handlePaint)
        canvas.drawLine(r, b, r, b - size, handlePaint)
    }

    /**
     * 中文：读取 content Uri 两次：第一次读取尺寸，第二次解码采样后的 Bitmap。
     * English: Reads a content uri twice: first for dimensions, then for the sampled bitmap.
     */
    private fun decodeUri(uri: android.net.Uri, requestedWidth: Int, requestedHeight: Int): Bitmap {
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

    /**
     * 中文：使用与 Uri 图片源相同的采样限制解码文件路径。
     * English: Decodes a file path with the same sampling limits as uri sources.
     */
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

    /**
     * 中文：限制解码后的长边和总像素数，保证超长图仍然可绘制。
     * English: Caps decoded long-edge and total pixel count so very tall images remain drawable.
     */
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

    private fun requestedDecodeWidth(): Int = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 2

    private fun requestedDecodeHeight(): Int = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 2

    /**
     * 中文：在切换图片源或 View 分离前，取消当前本地或远程加载任务。
     * English: Cancels any active local or remote load before changing sources or detaching.
     */
    private fun cancelCurrentLoad() {
        stopFling()
        localLoadFuture?.cancel(true)
        localLoadFuture = null
        remoteLoad?.cancel()
        remoteLoad = null
    }

    private fun activeAspectRatio(): Float? = cropShape.aspectRatio

    /**
     * 中文：按最大放大倍数限制缩放因子，并围绕指定中心点缩放图片。
     * English: Limits the requested scale factor by max zoom, then scales around the given pivot.
     */
    private fun scaleImageBy(factor: Float, pivotX: Float, pivotY: Float) {
        val constrainedFactor = constrainScaleFactor(factor)
        if (constrainedFactor == 1f) return
        imageMatrix.postScale(constrainedFactor, constrainedFactor, pivotX, pivotY)
    }

    /**
     * 中文：计算在最大放大倍数内允许执行的实际缩放因子。
     * English: Computes the actual scale factor allowed by the configured max zoom.
     */
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

    /**
     * 中文：当最大放大倍数变小时，把当前图片缩放回允许范围内。
     * English: Scales the current image back into range when the max zoom setting is reduced.
     */
    private fun clampImageScaleToMax() {
        if (bitmap == null || cropRect.isEmpty) return
        val currentScale = currentImageScale()
        val maxScale = baseImageScale * maxZoomScale
        if (currentScale <= maxScale || currentScale <= 0f || maxScale <= 0f) return
        imageMatrix.postScale(maxScale / currentScale, maxScale / currentScale, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
    }

    /**
     * 中文：读取矩阵中的统一缩放值；旋转后通过 scale/skew 组合计算。
     * English: Reads the uniform matrix scale, using scale/skew values so rotated images are handled.
     */
    private fun currentImageScale(): Float {
        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val skewY = matrixValues[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }

    /**
     * 中文：在当前裁剪覆盖边界内启动图片惯性滑动。
     * English: Starts inertial image movement within the current crop coverage limits.
     */
    private fun startImageFling(xVelocity: Float, yVelocity: Float) {
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

        val velocityX = if (minDeltaX == 0 && maxDeltaX == 0) 0 else xVelocity.roundToInt()
        val velocityY = if (minDeltaY == 0 && maxDeltaY == 0) 0 else yVelocity.roundToInt()
        if (velocityX == 0 && velocityY == 0) return

        flingLastX = 0
        flingLastY = 0
        flingScroller.fling(
            0,
            0,
            velocityX,
            velocityY,
            minDeltaX,
            maxDeltaX,
            minDeltaY,
            maxDeltaY,
        )
        postInvalidateOnAnimation()
    }

    /**
     * 中文：在用户直接操作或 API 触发变换前，停止当前惯性滑动。
     * English: Stops active inertial movement before direct user or API-driven transforms.
     */
    private fun stopFling() {
        if (!flingScroller.isFinished) {
            flingScroller.abortAnimation()
        }
        flingLastX = 0
        flingLastY = 0
    }

    private fun postCropSaveError(callback: CropSaveCallback, error: Throwable) {
        mainHandler.post {
            callback.onCropSaveError(error)
        }
    }

    private fun readStyledAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ImageCropView, defStyleAttr, 0)
        try {
            showGridLines = typedArray.getBoolean(R.styleable.ImageCropView_icv_showGridLines, showGridLines)
            showCornerHandles = typedArray.getBoolean(
                R.styleable.ImageCropView_icv_showCornerHandles,
                showCornerHandles,
            )
            cropFrameResizeEnabled = typedArray.getBoolean(
                R.styleable.ImageCropView_icv_cropFrameResizeEnabled,
                cropFrameResizeEnabled,
            )
            cornerColor = typedArray.getColor(R.styleable.ImageCropView_icv_cornerColor, cornerColor)
            cornerStrokeWidth = typedArray.getDimension(
                R.styleable.ImageCropView_icv_cornerStrokeWidth,
                cornerStrokeWidth,
            )
            cornerLength = typedArray.getDimension(R.styleable.ImageCropView_icv_cornerLength, cornerLength)
            maxZoomScale = typedArray.getFloat(R.styleable.ImageCropView_icv_maxZoomScale, maxZoomScale)
        } finally {
            typedArray.recycle()
        }
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private companion object {
        const val MAX_DECODED_LONG_EDGE = 12_000
        const val MAX_DECODED_PIXELS = 24_000_000
        const val DEFAULT_MAX_ZOOM_SCALE = 4f
        const val DEFAULT_SAVE_FILE_PREFIX = "crop"
        const val DEFAULT_JPEG_QUALITY = 94
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.coerceIn(0.85f, 1.18f)
            scaleImageBy(factor, detector.focusX, detector.focusY)
            ensureImageCoversCrop()
            invalidate()
            return true
        }
    }
}
