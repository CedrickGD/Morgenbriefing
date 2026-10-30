package com.cedrickgd.morgenbriefing.ui.news

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.cedrickgd.morgenbriefing.data.remote.RssClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

private const val DISK_CACHE_BYTES = 120L * 1024 * 1024
private const val MEMORY_CACHE_PERCENT = 0.20
private const val DISK_CACHE_DIR = "article_images"

/**
 * The app-wide Coil [ImageLoader] for article pictures. It shares the app's OkHttp connection
 * pool, sends the app's descriptive User-Agent and refuses every request that is not https —
 * also redirect hops — so no picture is ever fetched in the clear. Memory cache ≈ 20 % of the
 * app's memory budget, disk cache 120 MB, crossfade on.
 */
internal fun newsImageLoader(context: Context, http: OkHttpClient): ImageLoader {
    val app = context.applicationContext
    val client = http.newBuilder()
        .addInterceptor(UserAgentInterceptor)
        .addNetworkInterceptor(HttpsOnlyInterceptor)
        .build()
    return ImageLoader.Builder(app)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(app, MEMORY_CACHE_PERCENT).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(app.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .crossfade(true)
        .build()
}

private object UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", RssClient.FEED_USER_AGENT).build())
}

/** Network interceptor: sees every hop, so a redirect to plain http fails instead of loading. */
private object HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!isAllowedImageScheme(url.scheme)) {
            throw IOException("Bilder werden nur über https geladen, nicht von $url")
        }
        return chain.proceed(chain.request())
    }
}
