package com.github.sceneren.crop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil3.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            Log.d("PhotoPicker", "Selected URI: $uri")
            originalUri = uri
            ivOriginal.load(uri)
        } else {
            Log.d("PhotoPicker", "No media selected")
        }
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val croppedUri = result.data?.data
            Log.d("PhotoPicker", "Cropped URI: $croppedUri")
            ivCropped.load(croppedUri)
        }
    }

    private lateinit var btnPickPhoto: Button
    private lateinit var btnCropUri: Button
    private lateinit var btnCropFile: Button
    private lateinit var btnCropNetImage: Button
    private lateinit var btnCropCircle: Button
    private lateinit var ivOriginal: ImageView
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
        btnCropUri = findViewById(R.id.btnCropUri)
        btnCropFile = findViewById(R.id.btnCropFile)
        btnCropNetImage = findViewById(R.id.btnCropNetImage)
        btnCropCircle = findViewById(R.id.btnCropCircle)
        ivOriginal = findViewById(R.id.ivOriginal)
        ivCropped = findViewById(R.id.ivCropped)

        btnPickPhoto.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnCropUri.setOnClickListener {
            cropSelectedUri()
        }
        btnCropFile.setOnClickListener {
            cropSelectedFile()
        }
        btnCropNetImage.setOnClickListener {
            cropLauncher.launch(CropActivity.createIntent(this, "https://picsum.photos/1400/1000?random=42"))
        }
        btnCropCircle.setOnClickListener {
            cropSelectedCircle()
        }
    }

    private fun cropSelectedUri() {
        val uri = originalUri
        if (uri == null) {
            Toast.makeText(this, R.string.pick_photo_first, Toast.LENGTH_SHORT).show()
            return
        }
        cropLauncher.launch(CropActivity.createIntent(this, uri))
    }

    private fun cropSelectedFile() {
        val uri = originalUri
        if (uri == null) {
            Toast.makeText(this, R.string.pick_photo_first, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCacheFile(uri) }
            cropLauncher.launch(CropActivity.createIntent(this@MainActivity, file.absolutePath))
        }
    }

    private fun cropSelectedCircle() {
        val uri = originalUri
        if (uri == null) {
            Toast.makeText(this, R.string.pick_photo_first, Toast.LENGTH_SHORT).show()
            return
        }
        cropLauncher.launch(CropActivity.createIntent(this, uri, CropActivity.CROP_SHAPE_OVAL))
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Log.w("PhotoPicker", "Persistable permission is not available for $uri", error)
        }
    }

    private fun copyUriToCacheFile(uri: Uri): File {
        val extension = contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "jpg"
        val file = File(cacheDir, "source_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(file).use { output ->
                requireNotNull(input) { "Unable to open selected image." }.copyTo(output)
            }
        }
        return file
    }
}
