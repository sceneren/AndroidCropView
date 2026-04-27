# AndroidCropView

[English](README.md) | [简体中文](README_zh.md)

AndroidCropView is a custom Android image cropping view. It does not depend on a third-party crop widget. The project contains:

- `:cropview`: a reusable Android library module.
- `:app`: a sample app for testing local Uri, local File, and remote URL image cropping.

## Features

- Custom `View` implementation for crop, rotate, zoom, drag, and fling.
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

In `settings.gradle.kts`:

```kotlin
include(":cropview")
```

In your app module:

```kotlin
dependencies {
    implementation(project(":cropview"))
}
```

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
```

Non-rectangle shapes contain transparency, so save them as PNG or another alpha-preserving format.

## Sample App

The `:app` module includes a crop test screen that demonstrates:

- selecting a local image Uri
- copying a selected image to a local File
- loading a remote URL through Coil
- switching crop shapes
- rotating, zooming, dragging, and fling

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

