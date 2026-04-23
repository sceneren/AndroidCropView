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
import com.canhub.cropper.CropImageView
import androidx.core.net.toUri
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
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
        fun createIntent(context: AppCompatActivity, uri: Uri): Intent {
            return Intent(context, CropActivity::class.java).apply {
                data = uri
            }
        }

        fun createIntent(context: AppCompatActivity, path: String): Intent {
            return Intent(context, CropActivity::class.java).apply {
                putExtra("path", path)
            }
        }
    }

    private var originalUri: Uri? = null
    private var originalPath: String? = null

    private var croppedUri: Uri? = null

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
        val path = intent.getStringExtra("path")
        if (intent.data == null && path.isNullOrEmpty()) {
            Toast.makeText(this, "uri is null", Toast.LENGTH_SHORT).show()
            return
        }
        if (path != null) {
            originalPath = path
        } else if (intent.data != null) {
            originalUri = intent.data
        }

        cropImageView = findViewById(R.id.cropImageView)
        ivCrop = findViewById(R.id.ivCrop)
        ivBack = findViewById(R.id.ivBack)
        progressBar = findViewById(R.id.progressBar)
        ivRotateLeft = findViewById(R.id.ivRotateLeft)
        ivRotateRight = findViewById(R.id.ivRotateRight)

        ViewCompat.setOnApplyWindowInsetsListener(ivBack) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val layoutParams = v.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = systemBars.top
            insets
        }

        initCropImageView()

        ivCrop.setOnClickListener {
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

    private fun initCropImageView() {
        if (originalPath.isNullOrEmpty() && originalUri == null) {
            return
        }

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
                croppedUri = result.uriContent
                progressBar.show()
                setImageUri(croppedUri!!)
            } else {
                croppedUri = null
                Toast.makeText(this, "Crop failed", Toast.LENGTH_SHORT).show()
                Log.e("CropActivity", "Crop failed", result.error)
            }
        }
        // 显示进度条
        if (originalUri != null) {
            setImageUri(originalUri!!)
        } else if (originalPath != null) {
            if (File(originalPath!!).exists()) {
                setImageUri(originalPath!!.toUri())
            } else {
                loadImageByCoil(originalPath!!)
            }
        }
    }

    private fun setImageUri(uri: Uri) {
        progressBar.show()
        cropImageView.setImageUriAsync(uri)
    }

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
                    Log.i("CropActivity", "Image loaded successfully by Coil [${bitmap.width}*${bitmap.height}]")
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

}