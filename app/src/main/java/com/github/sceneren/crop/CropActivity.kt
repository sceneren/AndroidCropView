package com.github.sceneren.crop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
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
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.github.sceneren.crop.utils.getFilePathFromUri
import com.github.sceneren.crop.utils.getUriForFile
import com.iuuaa.jpegcompressor.JPEGCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 *
 * 裁剪图片
 * @Author:         renjunjia
 * @CreateDate:     2026/4/23 10:54
 * @Version:        1.0
 */
class CropActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CROP_SHAPE = "crop_shape"
        private const val EXTRA_NEED_COMPRESS = "need_compress"

        const val CROP_SHAPE_OVAL = "oval"

        /**
         * 创建一个Intent来启动CropActivity
         * @param context
         * @param uri 图片的Uri
         * @param cropShape 裁剪形状，默认为矩形
         * @param needCompress 是否需要压缩
         */
        fun createIntent(context: AppCompatActivity, uri: Uri, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, CropActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }

        /**
         * 创建一个Intent来启动CropActivity
         * @param context
         * @param path 图片的路径：可以是本地或者远程地址
         * @param cropShape 裁剪形状，默认为矩形
         * @param needCompress 是否需要压缩
         */
        fun createIntent(context: AppCompatActivity, path: String, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, CropActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }
    }

    // 原始图片的Uri
    private var originalUri: Uri? = null

    // 原始图片的路径：可以是本地或者远程地址
    private var originalPath: String? = null

    // 裁剪后的图片的Uri
    private var croppedUri: Uri? = null

    // 是否需要压缩
    private var needCompress = false

    private lateinit var cropImageView: CropImageView
    private lateinit var ivCrop: ImageView
    private lateinit var ivBack: ImageView
    private lateinit var progressBar: ContentLoadingProgressBar
    private lateinit var ivRotateLeft: ImageView
    private lateinit var ivRotateRight: ImageView


    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            saveCroppedImage()
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

        ViewCompat.setOnApplyWindowInsetsListener(ivBack) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = systemBars.top
            insets
        }

        initCropImageView()
    }

    /**
     * 解析Intent
     */
    private fun parseIntent(): Boolean {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (intent.data == null && path.isNullOrEmpty()) {
            Toast.makeText(this, "uri is null", Toast.LENGTH_SHORT).show()
            return false
        }
        if (path != null) {
            originalPath = path
        } else if (intent.data != null) {
            originalUri = intent.data
        }
        needCompress = intent.getBooleanExtra(EXTRA_NEED_COMPRESS, false)
        return true
    }

    /**
     * 初始化View
     */
    private fun initView() {
        cropImageView = findViewById(R.id.cropImageView)
        ivCrop = findViewById(R.id.ivCrop)
        ivBack = findViewById(R.id.ivBack)
        progressBar = findViewById(R.id.progressBar)
        ivRotateLeft = findViewById(R.id.ivRotateLeft)
        ivRotateRight = findViewById(R.id.ivRotateRight)
    }

    /**
     * 初始化监听器
     */
    private fun initListeners() {
        ivCrop.setOnClickListener {
            progressBar.show()
            cropImage()
        }

        ivBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        ivRotateLeft.setOnClickListener {
            cropImageView.rotateImage(-90)
        }
        ivRotateRight.setOnClickListener {
            cropImageView.rotateImage(90)
        }
    }

    /**
     * 初始化CropImageView
     */
    private fun initCropImageView() {
        if (originalPath.isNullOrEmpty() && originalUri == null) {
            return
        }

        val cropShape = intent.getStringExtra(EXTRA_CROP_SHAPE)
        val cropOptions = CropImageOptions().apply {
            if (cropShape == CROP_SHAPE_OVAL) {
                this.cropShape = CropImageView.CropShape.OVAL
                this.fixAspectRatio = true
                this.aspectRatioX = 1
                this.aspectRatioY = 1
            }
        }
        cropImageView.setImageCropOptions(cropOptions)

        // 不显示默认的进度条
        cropImageView.isShowProgressBar = false
        // 图片加载完成的回调
        cropImageView.setOnSetImageUriCompleteListener { _, _, error ->
            if (error != null) {
                Toast.makeText(this, "Image loaded Error", Toast.LENGTH_SHORT).show()
            } else {
                progressBar.hide()
            }
        }
        // 裁剪完成的回调
        cropImageView.setOnCropImageCompleteListener { _, result ->
            if (result.isSuccessful && result.uriContent != null) {
                Toast.makeText(this, "Crop successful", Toast.LENGTH_SHORT).show()
                Log.d("CropActivity", "Crop successful: ${result.uriContent}")
                progressBar.show()
                if (needCompress) {
                    lifecycleScope.launch {
                        val compressedPath = compressImage(result.uriContent!!)
                        croppedUri = if (compressedPath != null){
                            getUriForFile(this@CropActivity, File(compressedPath))
                        }else{
                            result.uriContent
                        }
                        setImageUri(croppedUri!!)
                    }
                } else {
                    croppedUri = result.uriContent
                    setImageUri(croppedUri!!)
                }
            } else {
                croppedUri = null
                progressBar.hide()
                Toast.makeText(this, "Crop failed", Toast.LENGTH_SHORT).show()
                Log.e("CropActivity", "Crop failed", result.error)
            }
        }
        // 显示进度条
        if (originalUri != null) {
            setImageUri(originalUri!!)
        } else if (originalPath != null) {
            if (File(originalPath!!).exists()) {
                val uri = getUriForFile(this@CropActivity, File(originalPath!!))
                setImageUri(uri)
            } else {
                loadImageByCoil(originalPath!!)
            }
        }
    }

    /**
     * 压缩图片
     * @param uri
     * @return 压缩后的图片路径
     */
    private suspend fun compressImage(uri: Uri) = withContext(Dispatchers.IO) {
        val inputPath = getFilePathFromUri(this@CropActivity, uri, true)
        val outputPath = createCompressedFilePath(inputPath)
        val compressor = JPEGCompressor.instance
        val result = compressor.compressSync(
            inputPath = inputPath,
            outputPath = outputPath,
        )

        if (result.success) {
            File(inputPath).delete()
            outputPath
        } else {
            null
        }
    }

    /**
     * 设置图片Uri
     * @param uri
     */
    private fun setImageUri(uri: Uri) {
        progressBar.show()
        cropImageView.setImageUriAsync(uri)
    }

    /**
     * 使用Coil加载图片
     * @param path
     */
    private fun loadImageByCoil(path: String) {
        val request = ImageRequest.Builder(this)
            .data(path)
            .allowHardware(false)
            .listener(
                onStart = {
                    progressBar.show()
                },
                onSuccess = { _, result ->
                    progressBar.hide()
                    val bitmap = result.image.toBitmap()
                    if (BuildConfig.DEBUG) {
                        Log.i("CropActivity", "Image loaded successfully by Coil [${bitmap.width}*${bitmap.height}]")
                    }
                    cropImageView.setImageBitmap(bitmap)

                },
                onError = { _, result ->
                    progressBar.hide()
                    Log.e("CropActivity", "Image loaded Error by Coil [$result]")
                    Toast.makeText(this, "Image loaded Error by Coil [$result]", Toast.LENGTH_SHORT).show()
                }
            )
            .build()
        imageLoader.enqueue(request)
    }

    /**
     * 裁剪图片
     */
    private fun cropImage() {
        cropImageView.croppedImageAsync()
    }

    /**
     * 保存裁剪后的图片
     */
    internal fun saveCroppedImage() {
        if (croppedUri != null) {
            val intent = Intent().apply {
                data = croppedUri
            }
            setResult(RESULT_OK, intent)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    /**
     * 创建压缩后的图片保存路径
     * @param filePath 输入图片的路径
     * @return 压缩后图片的保存路径
     */
    private fun createCompressedFilePath(filePath: String): String {
        val inputFileName = File(filePath).name
        return "$cacheDir${File.separator}compressed_${inputFileName}"
    }

}