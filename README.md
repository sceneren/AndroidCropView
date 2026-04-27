# AndroidCropView

[![](https://jitpack.io/v/sceneren/AndroidCropView.svg)](https://jitpack.io/#sceneren/AndroidCropView)

[English](README.md) | [简体中文](README_zh.md)

## Demo

- [Watch the crop interaction demo](docs/media/androidcropview-demo.mp4)
- [Download demo APK](docs/releases/AndroidCropView-0.0.1.apk)

AndroidCropView is a custom Android image cropping view. It does not depend on a third-party crop widget. The project contains:

- `:cropview`: a reusable Android library module.
- `:cropview-compose`: a reusable Jetpack Compose library module.
- `:app`: a sample app for testing local Uri, local File, and remote URL image cropping.

## Features

- Custom `View` implementation for crop, rotate, zoom, drag, and fling.
- Jetpack Compose implementation with the same crop state, gestures, shape masks, and bitmap export APIs.
- Local image sources: `Uri`, `File`, `Bitmap`, and string paths.
- Remote image source support through a caller-provided `CropImageLoader`.
- Built-in crop shapes:
  - rectangle
  - rectangle with custom aspect ratio
  - circle
  - custom bitmap mask, such as a star PNG mask
- Customizable crop UI:
  - guide lines
  - corner handles
  - corner color
  - corner stroke width
  - corner length
  - crop-frame resize switch
  - max zoom scale
- Very tall image decoding is sampled to keep rendering stable.

## Module Setup

### Local project dependency

In `settings.gradle.kts`:

```kotlin
include(":cropview")
include(":cropview-compose")
```

In your app module:

```kotlin
dependencies {
    implementation(project(":cropview"))
    implementation(project(":cropview-compose")) // For Compose UI.
}
```

### JitPack dependency

After pushing a tag or GitHub release, add JitPack to the consuming project:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then depend on the library module:

```kotlin
dependencies {
    implementation("com.github.sceneren.AndroidCropView:cropview:<tag>")
    implementation("com.github.sceneren.AndroidCropView:cropview-compose:<tag>") // For Compose UI.
}
```

Replace `<tag>` with your Git tag or GitHub release version, for example `1.0.0`.

## XML Usage

```xml
<com.github.sceneren.cropview.ImageCropView
    android:id="@+id/cropImageView"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:icv_showGridLines="true"
    app:icv_showCornerHandles="true"
    app:icv_cropFrameResizeEnabled="true"
    app:icv_cornerColor="@android:color/white"
    app:icv_cornerStrokeWidth="4dp"
    app:icv_cornerLength="28dp"
    app:icv_maxZoomScale="4.0" />
```

## XML Attributes

| Attribute | Type | Default | Description |
| --- | --- | --- | --- |
| `icv_showGridLines` | boolean | `true` | Shows guide lines inside rectangle crop areas. |
| `icv_showCornerHandles` | boolean | `true` | Shows L-shaped corner handles. |
| `icv_cropFrameResizeEnabled` | boolean | `true` | Allows edge and corner drags to resize the crop frame. |
| `icv_cornerColor` | color | `white` | Corner-handle color. |
| `icv_cornerStrokeWidth` | dimension | `4dp` | Corner-handle stroke width. |
| `icv_cornerLength` | dimension | `28dp` | Corner-handle segment length. |
| `icv_maxZoomScale` | float | `4.0` | Maximum zoom multiplier relative to the initial crop-covering scale. |

## Kotlin Usage

```kotlin
cropImageView.setImageSource(CropImageSource.fromUri(uri))
cropImageView.setCropShape(CropShape.Rectangle(16, 9))

cropImageView.showGridLines = true
cropImageView.showCornerHandles = true
cropImageView.cropFrameResizeEnabled = true
cropImageView.maxZoomScale = 4f
cropImageView.setCornerStyle(
    color = Color.WHITE,
    strokeWidth = 4f * resources.displayMetrics.density,
    length = 28f * resources.displayMetrics.density,
)

val croppedBitmap = cropImageView.getCroppedBitmap()

cropImageView.cropAndSaveToCache(object : CropSaveCallback {
    override fun onCropSaveSuccess(result: CropSaveResult) {
        val filePath = result.filePath
    }

    override fun onCropSaveError(error: Throwable) = Unit
})
```

## Compose Usage

```kotlin
val cropState = rememberCropViewState()

LaunchedEffect(uri) {
    cropState.setImageSource(context, CropImageSource.fromUri(uri))
}

ImageCropper(
    state = cropState,
    modifier = Modifier.fillMaxSize(),
)

cropState.setCropShape(CropShape.Rectangle(16, 9))
cropState.rotateBy(90f)
val croppedBitmap = cropState.getCroppedBitmap()
val saved = cropState.cropAndSaveToCache(context)
val filePath = saved.filePath
```

## Image Sources

```kotlin
cropImageView.setImageSource(CropImageSource.fromUri(uri))
cropImageView.setImageSource(CropImageSource.fromFile(file))
cropImageView.setImageSource(CropImageSource.BitmapSource(bitmap))
cropImageView.setImageSource(CropImageSource.fromPath(pathOrUrl), imageLoader)
```

Remote URLs require an external loader:

```kotlin
class CoilCropImageLoader(private val context: Context) : CropImageLoader {
    override fun load(request: ImageLoadRequest, callback: ImageLoadCallback): Cancelable {
        val imageRequest = ImageRequest.Builder(context)
            .data(request.url)
            .allowHardware(false)
            .listener(
                onSuccess = { _, result -> callback.onSuccess(result.image.toBitmap()) },
                onError = { _, result -> callback.onError(result.throwable) },
            )
            .build()

        val disposable = context.imageLoader.enqueue(imageRequest)
        return Cancelable { disposable.dispose() }
    }
}
```

This keeps the library independent from Coil, Glide, Picasso, or any other image loading framework.

## Crop Shapes

```kotlin
// Free rectangle
cropImageView.setCropShape(CropShape.Rectangle())

// Fixed aspect ratio rectangle
cropImageView.setCropShape(CropShape.Rectangle(widthRatio = 16, heightRatio = 9))

// Circle
cropImageView.setCropShape(CropShape.Circle)

// Custom bitmap mask
cropImageView.setCropShape(CropShape.BitmapMask(maskBitmap))
```

For custom bitmap masks, the bitmap alpha channel defines the visible crop window. Use a transparent-background PNG when you need precise custom shapes.

## Export

```kotlin
val bitmap = cropImageView.getCroppedBitmap()
val sizedBitmap = cropImageView.getCroppedBitmap(outputWidth = 1080, outputHeight = 1080)
cropImageView.cropAndSaveToCache(callback)
```

`cropAndSaveToCache` writes to `context.cacheDir/cropview` and returns the absolute file path through `CropSaveResult.filePath`. Rectangle crops are saved as JPEG; non-rectangle crops are saved as PNG to preserve transparency.

## Sample App

The `:app` module includes a crop test screen that demonstrates:

- selecting a local image Uri
- copying a selected image to a local File
- loading a remote URL through Coil
- using the `:cropview-compose` Compose crop screen
- switching crop shapes
- rotating, zooming, dragging, and fling

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```
