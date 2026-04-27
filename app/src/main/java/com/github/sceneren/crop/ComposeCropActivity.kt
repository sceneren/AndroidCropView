package com.github.sceneren.crop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.github.sceneren.cropview.Cancelable
import com.github.sceneren.cropview.CropImageLoader
import com.github.sceneren.cropview.CropImageSource
import com.github.sceneren.cropview.CropSaveCallback
import com.github.sceneren.cropview.CropSaveResult
import com.github.sceneren.cropview.CropShape
import com.github.sceneren.cropview.ImageLoadCallback
import com.github.sceneren.cropview.ImageLoadRequest
import com.github.sceneren.cropview.compose.CropLoadState
import com.github.sceneren.cropview.compose.CropViewState
import com.github.sceneren.cropview.compose.ImageCropper
import com.github.sceneren.cropview.compose.rememberCropViewState
import kotlinx.coroutines.launch

class ComposeCropActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_CROP_SHAPE = "crop_shape"
        private const val EXTRA_NEED_COMPRESS = "need_compress"

        fun createIntent(context: AppCompatActivity, uri: Uri, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, ComposeCropActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }

        fun createIntent(context: AppCompatActivity, path: String, cropShape: String? = null, needCompress: Boolean = true): Intent {
            return Intent(context, ComposeCropActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_NEED_COMPRESS, needCompress)
                cropShape?.let { putExtra(EXTRA_CROP_SHAPE, it) }
            }
        }
    }

    enum class ShapeMode {
        RECT_FREE,
        RECT_SQUARE,
        RECT_WIDE,
        CIRCLE,
        STAR,
    }

    private var originalUri: Uri? = null
    private var originalPath: String? = null
    private var initialShapeMode = ShapeMode.RECT_FREE

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(onBackPressedCallback)
        if (!parseIntent()) {
            return
        }

        val source = originalUri?.let { CropImageSource.fromUri(it) }
            ?: originalPath?.let { CropImageSource.fromPath(it) }
            ?: return

        setContent {
            MaterialTheme {
                val cropState = rememberCropViewState()
                val imageLoader = remember { CoilCropImageLoader(this@ComposeCropActivity) }
                var shapeMode by rememberSaveable { mutableStateOf(initialShapeMode) }
                var isSaving by remember { mutableStateOf(false) }
                var starMask by remember { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(source) {
                    cropState.setImageSource(this@ComposeCropActivity, source, imageLoader)
                }

                LaunchedEffect(shapeMode) {
                    val shape = when (shapeMode) {
                        ShapeMode.RECT_FREE -> CropShape.Rectangle()
                        ShapeMode.RECT_SQUARE -> CropShape.Rectangle(1, 1)
                        ShapeMode.RECT_WIDE -> CropShape.Rectangle(16, 9)
                        ShapeMode.CIRCLE -> CropShape.Circle
                        ShapeMode.STAR -> {
                            val mask = starMask ?: createStarBitmap().also { starMask = it }
                            CropShape.BitmapMask(mask)
                        }
                    }
                    cropState.setCropShape(shape)
                }

                LaunchedEffect(cropState.loadState) {
                    val state = cropState.loadState
                    if (state is CropLoadState.Error) {
                        Log.e("ComposeCropActivity", "Image load failed", state.error)
                        Toast.makeText(this@ComposeCropActivity, R.string.crop_load_failed, Toast.LENGTH_SHORT).show()
                    }
                }

                ComposeCropScreen(
                    cropState = cropState,
                    shapeMode = shapeMode,
                    isBusy = isSaving || cropState.loadState is CropLoadState.Loading,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onReset = { cropState.resetImage() },
                    onZoomOut = { cropState.zoomBy(0.9f) },
                    onZoomIn = { cropState.zoomBy(1.12f) },
                    onRotateLeft = { cropState.rotateBy(-90f) },
                    onRotateRight = { cropState.rotateBy(90f) },
                    onNextShape = { shapeMode = nextShapeMode(shapeMode) },
                    onDone = {
                        if (!isSaving) {
                            saveCroppedImage(cropState) { saving ->
                                isSaving = saving
                            }
                        }
                    },
                )
            }
        }
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
        if (intent.getStringExtra(EXTRA_CROP_SHAPE) == CropActivity.CROP_SHAPE_OVAL) {
            initialShapeMode = ShapeMode.CIRCLE
        }
        return true
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

    private fun saveCroppedImage(cropState: CropViewState, onSavingChange: (Boolean) -> Unit) {
        onSavingChange(true)
        lifecycleScope.launch {
            // Compose uses the same callback contract; the state handles crop + private-cache saving.
            cropState.cropAndSaveToCache(
                context = this@ComposeCropActivity,
                callback = object : CropSaveCallback {
                    override fun onCropSaveSuccess(result: CropSaveResult) {
                        onSavingChange(false)
                        Log.d("ComposeCropActivity", "Crop successful: ${result.filePath}")
                        val intent = Intent().apply {
                            putExtra(CropActivity.EXTRA_RESULT_PATH, result.filePath)
                        }
                        setResult(RESULT_OK, intent)
                        finish()
                    }

                    override fun onCropSaveError(error: Throwable) {
                        onSavingChange(false)
                        Log.e("ComposeCropActivity", "Crop save failed", error)
                        Toast.makeText(this@ComposeCropActivity, getString(R.string.crop_no_image), Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    private fun createStarBitmap(): Bitmap {
        return AppCompatResources.getDrawable(this, R.drawable.star)!!.toBitmap()
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

@Composable
private fun ComposeCropScreen(
    cropState: CropViewState,
    shapeMode: ComposeCropActivity.ShapeMode,
    isBusy: Boolean,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onNextShape: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114)),
    ) {
        ImageCropper(
            state = cropState,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CropIconButton(
                iconRes = R.drawable.ic_arrow_back_24,
                contentDescription = R.string.crop_back,
                onClick = onBack,
            )
            Text(
                text = shapeMode.statusText(),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE6111215))
                .navigationBarsPadding()
                .height(96.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CropIconButton(R.drawable.ic_reset_24, R.string.crop_reset, onReset)
            CropIconButton(R.drawable.ic_zoom_out_24, R.string.crop_zoom_out, onZoomOut)
            CropIconButton(R.drawable.ic_zoom_in_24, R.string.crop_zoom_in, onZoomIn)
            CropIconButton(R.drawable.ic_rotate_left_24, R.string.crop_rotate_left, onRotateLeft)
            CropIconButton(R.drawable.ic_rotate_right_24, R.string.crop_rotate_right, onRotateRight)
            CropIconButton(R.drawable.ic_shape_24, R.string.crop_shape, onNextShape)
            CropIconButton(
                iconRes = R.drawable.ic_check_24,
                contentDescription = R.string.crop_done,
                onClick = onDone,
                isPrimary = true,
            )
        }

        if (isBusy) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
    }
}

@Composable
private fun CropIconButton(
    iconRes: Int,
    contentDescription: Int,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
) {
    val modifier = if (isPrimary) {
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2E7DFF))
    } else {
        Modifier.size(48.dp)
    }
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(contentDescription),
            tint = Color.White,
        )
    }
}

@Composable
private fun ComposeCropActivity.ShapeMode.statusText(): String {
    return when (this) {
        ComposeCropActivity.ShapeMode.RECT_FREE -> stringResource(R.string.crop_status_rect_free)
        ComposeCropActivity.ShapeMode.RECT_SQUARE -> stringResource(R.string.crop_status_rect_square)
        ComposeCropActivity.ShapeMode.RECT_WIDE -> stringResource(R.string.crop_status_rect_wide)
        ComposeCropActivity.ShapeMode.CIRCLE -> stringResource(R.string.crop_status_circle)
        ComposeCropActivity.ShapeMode.STAR -> stringResource(R.string.crop_status_star)
    }
}
