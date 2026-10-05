package com.cedrickgd.morgenbriefing.ui.news

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/** Wikimedia asks every client for a descriptive User-Agent with contact information. */
internal const val IMAGE_USER_AGENT = "Morgenbriefing/1.0 (+https://github.com/CedrickGD/Morgenbriefing)"

private const val DISK_CACHE_BYTES = 100L * 1024 * 1024
private const val MEMORY_CACHE_PERCENT = 0.20
private const val DISK_CACHE_DIR = "article_images"

/**
 * The app-wide Coil [ImageLoader] for article pictures. It shares the app's OkHttp connection
 * pool, sends the descriptive User-Agent Wikimedia requires and refuses every request that is
 * not https to `upload.wikimedia.org` — also redirect hops — so no other host is ever contacted
 * for images. Memory cache ≈ 20 % of the app's memory budget, disk cache 100 MB, crossfade on.
 */
internal fun newsImageLoader(context: Context, http: OkHttpClient): ImageLoader {
    val app = context.applicationContext
    val client = http.newBuilder()
        .addInterceptor(UserAgentInterceptor)
        .addNetworkInterceptor(CommonsOnlyInterceptor)
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
        chain.proceed(chain.request().newBuilder().header("User-Agent", IMAGE_USER_AGENT).build())
}

/** Network interceptor: sees every hop, so a redirect to another host fails instead of loading. */
private object CommonsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!isAllowedImageHost(url.scheme, url.host)) {
            throw IOException("Bilder werden nur von $COMMONS_HOST geladen, nicht von ${url.host}")
        }
        return chain.proceed(chain.request())
    }
}
