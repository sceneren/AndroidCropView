package com.github.sceneren.crop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.github.sceneren.crop.utils.getUriForFile
import com.github.sceneren.cropview.Cancelable
import com.github.sceneren.cropview.CropImageLoader
import com.github.sceneren.cropview.CropImageSource
import com.github.sceneren.cropview.CropShape
import com.github.sceneren.cropview.ImageCropView
import com.github.sceneren.cropview.ImageLoadCallback
import com.github.sceneren.cropview.ImageLoadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CropActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CROP_SHAPE = "crop_shape"
        private const val EXTRA_NEED_COMPRESS = "need_compress"

        const val CROP_SHAPE_OVAL = "oval"

        fun createIntent(context: AppCompatActivity, uri: Uri, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, CropActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }

        fun createIntent(context: AppCompatActivity, path: String, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, CropActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }
    }

    private enum class ShapeMode {
        RECT_FREE,
        RECT_SQUARE,
        RECT_WIDE,
        CIRCLE,
        STAR,
    }

    private var originalUri: Uri? = null
    private var originalPath: String? = null
    private var shapeMode = ShapeMode.RECT_FREE
    private var isSaving = false
    private var starMask: Bitmap? = null

    private lateinit var cropImageView: ImageCropView
    private lateinit var ivBack: ImageView
    private lateinit var progressBar: ContentLoadingProgressBar
    private lateinit var btnCrop: ImageButton
    private lateinit var btnRotateLeft: ImageButton
    private lateinit var btnRotateRight: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var btnShape: ImageButton
    private lateinit var tvStatus: TextView

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_crop)
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        if (!parseIntent()) {
            return
        }

        initView()
        initListeners()
        applyShapeMode()

        ViewCompat.setOnApplyWindowInsetsListener(ivBack) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = systemBars.top
            insets
        }

        loadImage()
    }

    private fun parseIntent(): Boolean {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (intent.data == null && path.isNullOrEmpty()) {
            Toast.makeText(this, "uri is null", Toast.LENGTH_SHORT).show()
            return false
        }
        if (path != null) {
            originalPath = path
        } else {
            originalUri = intent.data
        }
        if (intent.getStringExtra(EXTRA_CROP_SHAPE) == CROP_SHAPE_OVAL) {
            shapeMode = ShapeMode.CIRCLE
        }
        return true
    }

    private fun initView() {
        cropImageView = findViewById(R.id.cropImageView)
        ivBack = findViewById(R.id.ivBack)
        progressBar = findViewById(R.id.progressBar)
        btnCrop = findViewById(R.id.btnCrop)
        btnRotateLeft = findViewById(R.id.btnRotateLeft)
        btnRotateRight = findViewById(R.id.btnRotateRight)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnReset = findViewById(R.id.btnReset)
        btnShape = findViewById(R.id.btnShape)
        tvStatus = findViewById(R.id.tvStatus)
    }

    private fun initListeners() {
        btnCrop.setOnClickListener {
            saveCroppedImage()
        }
        ivBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        btnRotateLeft.setOnClickListener {
            cropImageView.rotateBy(-90f)
        }
        btnRotateRight.setOnClickListener {
            cropImageView.rotateBy(90f)
        }
        btnZoomIn.setOnClickListener {
            cropImageView.zoomBy(1.12f)
        }
        btnZoomOut.setOnClickListener {
            cropImageView.zoomBy(0.9f)
        }
        btnReset.setOnClickListener {
            cropImageView.resetImage()
        }
        btnShape.setOnClickListener {
            shapeMode = nextShapeMode(shapeMode)
            applyShapeMode()
        }
    }

    private fun loadImage() {
        cropImageView.imageLoadListener = object : ImageCropView.ImageLoadListener {
            override fun onLoadStart() {
                progressBar.show()
            }

            override fun onLoadSuccess(bitmap: Bitmap) {
                progressBar.hide()
                applyShapeMode()
            }

            override fun onLoadError(error: Throwable) {
                progressBar.hide()
                Log.e("CropActivity", "Image load failed", error)
                Toast.makeText(this@CropActivity, getString(R.string.crop_load_failed), Toast.LENGTH_SHORT).show()
            }
        }

        val source = originalUri?.let { CropImageSource.fromUri(it) }
            ?: originalPath?.let { CropImageSource.fromPath(it) }
            ?: return
        cropImageView.setImageSource(source, CoilCropImageLoader(this))
    }

    private fun applyShapeMode() {
        when (shapeMode) {
            ShapeMode.RECT_FREE -> {
                cropImageView.setCropShape(CropShape.Rectangle())
                tvStatus.text = getString(R.string.crop_status_rect_free)
            }
            ShapeMode.RECT_SQUARE -> {
                cropImageView.setCropShape(CropShape.Rectangle(1, 1))
                tvStatus.text = getString(R.string.crop_status_rect_square)
            }
            ShapeMode.RECT_WIDE -> {
                cropImageView.setCropShape(CropShape.Rectangle(16, 9))
                tvStatus.text = getString(R.string.crop_status_rect_wide)
            }
            ShapeMode.CIRCLE -> {
                cropImageView.setCropShape(CropShape.Circle)
                tvStatus.text = getString(R.string.crop_status_circle)
            }
            ShapeMode.STAR -> {
                val mask = starMask ?: createStarMask().also { starMask = it }
                cropImageView.setCropShape(CropShape.BitmapMask(mask))
                tvStatus.text = getString(R.string.crop_status_star)
            }
        }
    }

    private fun nextShapeMode(mode: ShapeMode): ShapeMode {
        return when (mode) {
            ShapeMode.RECT_FREE -> ShapeMode.RECT_SQUARE
            ShapeMode.RECT_SQUARE -> ShapeMode.RECT_WIDE
            ShapeMode.RECT_WIDE -> ShapeMode.CIRCLE
            ShapeMode.CIRCLE -> ShapeMode.STAR
            ShapeMode.STAR -> ShapeMode.RECT_FREE
        }
    }

    internal fun saveCroppedImage() {
        if (isSaving) return
        val cropped = cropImageView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, getString(R.string.crop_no_image), Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        progressBar.show()
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { writeBitmapToCache(cropped, cropImageView.requiresAlphaOutput()) }
            progressBar.hide()
            isSaving = false
            Log.d("CropActivity", "Crop successful: $uri")
            val intent = Intent().apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun writeBitmapToCache(bitmap: Bitmap, keepAlpha: Boolean): Uri {
        val extension = if (keepAlpha) "png" else "jpg"
        val file = File(cacheDir, "crop_${System.currentTimeMillis()}.$extension")
        FileOutputStream(file).use { output ->
            val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            bitmap.compress(format, if (keepAlpha) 100 else 94, output)
        }
        return getUriForFile(this, file)
    }

    private fun createStarMask(size: Int = 640): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val path = Path()
        val center = size / 2f
        val outerRadius = size * 0.45f
        val innerRadius = size * 0.2f
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = -PI / 2.0 + i * PI / 5.0
            val x = center + cos(angle).toFloat() * radius
            val y = center + sin(angle).toFloat() * radius
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
        return output
    }

    private class CoilCropImageLoader(private val context: Context) : CropImageLoader {
        override fun load(request: ImageLoadRequest, callback: ImageLoadCallback): Cancelable {
            val imageRequest = ImageRequest.Builder(context)
                .data(request.url)
                .allowHardware(false)
                .listener(
                    onSuccess = { _, result ->
                        callback.onSuccess(result.image.toBitmap())
                    },
                    onError = { _, result ->
                        callback.onError(result.throwable)
                    },
                )
                .build()
            val disposable = context.imageLoader.enqueue(imageRequest)
            return Cancelable {
                disposable.dispose()
            }
        }
    }
}
