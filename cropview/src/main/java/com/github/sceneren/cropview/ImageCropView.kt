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
import android.view.View
import android.view.ViewConfiguration
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap

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
    private val handleSize = 28f.dp()
    private val edgeSlop = 28f.dp()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())

    private var bitmap: Bitmap? = null
    private var localLoadFuture: Future<*>? = null
    private var remoteLoad: Cancelable? = null
    private var dragMode = DragMode.NONE
    private var cropShape: CropShape = CropShape.Rectangle()
    private var lastX = 0f
    private var lastY = 0f
    private var movedPastSlop = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        isClickable = true
    }

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

    fun setImageBitmap(bitmap: Bitmap) {
        cancelCurrentLoad()
        setImageBitmapInternal(bitmap)
    }

    fun setCropShape(shape: CropShape) {
        cropShape = shape
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
        return cropShape !is CropShape.Rectangle
    }

    fun rotateBy(degrees: Float) {
        if (bitmap == null || cropRect.isEmpty) return
        imageMatrix.postRotate(degrees, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    fun zoomBy(factor: Float) {
        if (bitmap == null || cropRect.isEmpty || factor <= 0f) return
        imageMatrix.postScale(factor, factor, cropRect.centerX(), cropRect.centerY())
        ensureImageCoversCrop()
        invalidate()
    }

    fun resetImage() {
        if (bitmap == null || width == 0 || height == 0) return
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
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.scale(outputWidth / cropRect.width(), outputHeight / cropRect.height())
        canvas.translate(-cropRect.left, -cropRect.top)
        canvas.drawBitmap(source, imageMatrix, bitmapPaint)
        return applyCropShapeMask(result)
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragMode = findDragMode(event.x, event.y)
                lastX = event.x
                lastY = event.y
                movedPastSlop = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1) {
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
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = DragMode.NONE
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        cancelCurrentLoad()
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
        bitmap = nextBitmap
        bitmapBounds.set(0f, 0f, nextBitmap.width.toFloat(), nextBitmap.height.toFloat())
        if (width > 0 && height > 0) {
            configureInitialState()
        }
        invalidate()
    }

    private fun configureInitialState() {
        updateContentRect()
        initCropRect()
        fitImageToCrop()
    }

    private fun updateContentRect() {
        contentRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat(),
        )
    }

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

    private fun fitImageToCrop() {
        val source = bitmap ?: return
        if (cropRect.isEmpty) return
        bitmapBounds.set(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val scale = max(cropRect.width() / bitmapBounds.width(), cropRect.height() / bitmapBounds.height())
        val dx = cropRect.centerX() - bitmapBounds.centerX() * scale
        val dy = cropRect.centerY() - bitmapBounds.centerY() * scale
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        ensureImageCoversCrop()
    }

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
            else -> DragMode.NONE
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        if (cropRect.isEmpty) return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        drawClearCropShape(canvas)
        canvas.restoreToCount(layer)

        if (cropShape is CropShape.Rectangle) {
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
        drawHandles(canvas)
    }

    private fun drawClearCropShape(canvas: Canvas) {
        when (val shape = cropShape) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, clearPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, clearPaint)
            is CropShape.BitmapMask -> canvas.drawBitmap(shape.bitmap, null, cropRect, maskClearPaint)
        }
    }

    private fun drawShapeGuide(canvas: Canvas) {
        when (val shape = cropShape) {
            is CropShape.Rectangle -> canvas.drawRect(cropRect, borderPaint)
            CropShape.Circle -> canvas.drawOval(cropRect, borderPaint)
            is CropShape.BitmapMask -> {
                canvas.drawRect(cropRect, borderPaint)
            }
        }
    }

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

    private fun drawHandles(canvas: Canvas) {
        val l = cropRect.left
        val t = cropRect.top
        val r = cropRect.right
        val b = cropRect.bottom
        val size = handleSize
        canvas.drawLine(l, t, l + size, t, handlePaint)
        canvas.drawLine(l, t, l, t + size, handlePaint)
        canvas.drawLine(r, t, r - size, t, handlePaint)
        canvas.drawLine(r, t, r, t + size, handlePaint)
        canvas.drawLine(l, b, l + size, b, handlePaint)
        canvas.drawLine(l, b, l, b - size, handlePaint)
        canvas.drawLine(r, b, r - size, b, handlePaint)
        canvas.drawLine(r, b, r, b - size, handlePaint)
    }

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
        val targetWidth = max(requestedWidth, 2048)
        val targetHeight = max(requestedHeight, 2048)
        if (sourceHeight > targetHeight || sourceWidth > targetWidth) {
            var halfHeight = sourceHeight / 2
            var halfWidth = sourceWidth / 2
            while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
                halfHeight /= 2
                halfWidth /= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun requestedDecodeWidth(): Int = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 2

    private fun requestedDecodeHeight(): Int = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * 2

    private fun cancelCurrentLoad() {
        localLoadFuture?.cancel(true)
        localLoadFuture = null
        remoteLoad?.cancel()
        remoteLoad = null
    }

    private fun activeAspectRatio(): Float? = cropShape.aspectRatio

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor.coerceIn(0.85f, 1.18f)
            imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            ensureImageCoversCrop()
            invalidate()
            return true
        }
    }
}
