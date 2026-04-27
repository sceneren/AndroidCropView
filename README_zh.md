# AndroidCropView

[![](https://jitpack.io/v/sceneren/AndroidCropView.svg)](https://jitpack.io/#sceneren/AndroidCropView)

[English](README.md) | [简体中文](README_zh.md)

## 演示

- [查看裁剪交互演示](docs/media/androidcropview-demo.mp4)
- [下载 Demo APK](docs/releases/AndroidCropView-0.0.1.apk)

AndroidCropView 是一个 Android 自定义图片裁剪 View，不依赖第三方裁剪控件。项目包含：

- `:cropview`：可复用的 Android Library 模块。
- `:cropview-compose`：可复用的 Jetpack Compose Library 模块。
- `:app`：用于测试本地 Uri、本地 File、远程 URL 裁剪的示例 App。

## 功能特性

- 自定义 `View` 实现裁剪、旋转、缩放、拖动和 fling 惯性滑动。
- Jetpack Compose 实现同等裁剪状态、手势、形状蒙版和 Bitmap 导出能力。
- 支持本地图片源：`Uri`、`File`、`Bitmap`、字符串路径。
- 支持远程图片源，并通过调用方注入的 `CropImageLoader` 加载。
- 内置裁剪形状：
  - 矩形
  - 可自定义宽高比例的矩形
  - 圆形
  - Bitmap 自定义蒙版，例如五角星 PNG 蒙版
- 裁剪 UI 可配置：
  - 是否显示参考线
  - 是否显示角标
  - 角标颜色
  - 角标线宽
  - 角标长度
  - 是否允许缩放裁剪框
  - 最大放大倍数
- 对超长图进行采样解码，避免绘制异常。

## 模块接入

### 本地项目依赖

在 `settings.gradle.kts` 中添加：

```kotlin
include(":cropview")
include(":cropview-compose")
```

在 app 模块中依赖：

```kotlin
dependencies {
    implementation(project(":cropview"))
    implementation(project(":cropview-compose")) // Compose UI 使用。
}
```

### JitPack 依赖

推送 Git tag 或创建 GitHub Release 后，在使用方项目中添加 JitPack 仓库：

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

然后依赖库模块：

```kotlin
dependencies {
    implementation("com.github.sceneren.AndroidCropView:cropview:<tag>")
    implementation("com.github.sceneren.AndroidCropView:cropview-compose:<tag>") // Compose UI 使用。
}
```

将 `<tag>` 替换为你的 Git tag 或 GitHub Release 版本，例如 `1.0.0`。

## XML 用法

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

## XML 参数

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `icv_showGridLines` | boolean | `true` | 是否显示矩形裁剪区域中的参考线。 |
| `icv_showCornerHandles` | boolean | `true` | 是否显示 L 形角标。 |
| `icv_cropFrameResizeEnabled` | boolean | `true` | 是否允许拖动边和角缩放裁剪框。 |
| `icv_cornerColor` | color | `white` | 角标颜色。 |
| `icv_cornerStrokeWidth` | dimension | `4dp` | 角标线宽。 |
| `icv_cornerLength` | dimension | `28dp` | 角标每段线的长度。 |
| `icv_maxZoomScale` | float | `4.0` | 相对初始铺满裁剪框状态的最大放大倍数。 |

## Kotlin 用法

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

## Compose 用法

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

## 图片源

```kotlin
cropImageView.setImageSource(CropImageSource.fromUri(uri))
cropImageView.setImageSource(CropImageSource.fromFile(file))
cropImageView.setImageSource(CropImageSource.BitmapSource(bitmap))
cropImageView.setImageSource(CropImageSource.fromPath(pathOrUrl), imageLoader)
```

远程 URL 必须由外部图片加载器处理：

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

这样 `:cropview` 模块不会绑定 Coil、Glide、Picasso 或其他具体图片加载框架。

## 裁剪形状

```kotlin
// 自由比例矩形
cropImageView.setCropShape(CropShape.Rectangle())

// 固定宽高比矩形
cropImageView.setCropShape(CropShape.Rectangle(widthRatio = 16, heightRatio = 9))

// 圆形
cropImageView.setCropShape(CropShape.Circle)

// 自定义 Bitmap 蒙版
cropImageView.setCropShape(CropShape.BitmapMask(maskBitmap))
```

自定义 Bitmap 蒙版使用 Bitmap 的 alpha 通道定义可见裁剪窗口。需要精确形状时，建议使用透明背景 PNG。

## 导出裁剪结果

```kotlin
val bitmap = cropImageView.getCroppedBitmap()
val sizedBitmap = cropImageView.getCroppedBitmap(outputWidth = 1080, outputHeight = 1080)
cropImageView.cropAndSaveToCache(callback)
```

`cropAndSaveToCache` 会写入 `context.cacheDir/cropview`，并通过 `CropSaveResult.filePath` 返回文件绝对路径。矩形裁剪保存为 JPEG，非矩形裁剪保存为 PNG 以保留透明通道。

## 示例 App

`:app` 模块包含一个裁剪测试页面，演示：

- 选择本地图片 Uri
- 将选择的图片复制为本地 File
- 通过 Coil 加载远程 URL
- 使用 `:cropview-compose` 的 Compose 裁剪页面
- 切换裁剪形状
- 旋转、缩放、拖动和 fling 惯性滑动

## 构建

```powershell
.\gradlew.bat :app:assembleDebug
```
