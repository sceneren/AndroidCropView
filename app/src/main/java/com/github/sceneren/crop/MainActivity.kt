package com.github.sceneren.crop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.load

class MainActivity : AppCompatActivity() {

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Callback is invoked after the user selects a media item or closes the
        // photo picker.
        if (uri != null) {
            // 授予应用对媒体文件的访问权限
            val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flag)
            Log.d("PhotoPicker", "Selected URI: $uri")
            originalUri = uri
            ivOriginal.load(uri)
        } else {
            Log.d("PhotoPicker", "No media selected")
        }
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val croppedUri = data?.data
            Log.d("PhotoPicker", "Cropped URI: $croppedUri")
            ivCropped.load(croppedUri)
        }
    }

    private lateinit var btnPickPhoto: Button
    private lateinit var ivOriginal: ImageView
    private lateinit var btnCrop: Button
    private lateinit var btnCropNetImage: Button
    private lateinit var btnCropCircle: Button
    private lateinit var ivCropped: ImageView

    private var originalUri: Uri? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnPickPhoto = findViewById(R.id.btnPickPhoto)
        ivOriginal = findViewById(R.id.ivOriginal)
        btnCrop = findViewById(R.id.btnCrop)
        btnCropNetImage = findViewById(R.id.btnCropNetImage)
        btnCropCircle = findViewById(R.id.btnCropCircle)
        ivCropped = findViewById(R.id.ivCropped)

        btnPickPhoto.setOnClickListener {
            pickPhoto()
        }
        btnCrop.setOnClickListener {
            cropPhoto()
        }
        btnCropNetImage.setOnClickListener {
            val cropIntent = CropActivity.createIntent(this, "https://picsum.photos/800/800?random=1")
            cropLauncher.launch(cropIntent)
        }
        btnCropCircle.setOnClickListener {
            cropPhotoCircle()
        }

    }

    /**
     * Pick photo from gallery
     */
    private fun pickPhoto() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun cropPhoto() {
        if (originalUri == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val cropIntent = CropActivity.createIntent(this, originalUri!!)
        cropLauncher.launch(cropIntent)
    }

    private fun cropPhotoCircle() {
        if (originalUri == null) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }
        val cropIntent = CropActivity.createIntent(this, originalUri!!, CropActivity.CROP_SHAPE_OVAL)
        cropLauncher.launch(cropIntent)
    }
}