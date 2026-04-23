package com.github.sceneren.crop

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class App : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val imageLoaderBuilder = ImageLoader.Builder(context)
            .crossfade(false)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .callTimeout(60.seconds.toJavaDuration())
                                .writeTimeout(60.seconds.toJavaDuration())
                                .readTimeout(60.seconds.toJavaDuration())
                                .connectTimeout(60.seconds.toJavaDuration())
                                .build()
                        }
                    )
                )
                if (SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
        if (BuildConfig.DEBUG) {
            imageLoaderBuilder.logger(DebugLogger())
        }
        return imageLoaderBuilder.build()
    }
}