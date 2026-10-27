package app.snapcast.ui.di

import app.snapcast.data.AppInitializer
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Installs the app's image loader before any screen draws: posters go through a client derived from the shared
 * OkHttpClient (one connection pool for the whole app), and the network fetcher is registered explicitly, so no
 * build tool change to Coil's ServiceLoader lookup can silently stop every poster from loading.
 *
 * A poster request may carry the cookie of the poster's host; it is dropped on a redirect to another host, as the
 * proxy and the player do, so a site's cookie never follows a redirect to a third-party image host.
 */
internal class ImageLoaderInitializer @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : AppInitializer {
    override fun initialize() {
        val client = okHttpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val firstHost = chain.call().request().url.host
                if (request.header(COOKIE) != null && !request.url.host.equals(firstHost, ignoreCase = true)) {
                    chain.proceed(request.newBuilder().removeHeader(COOKIE).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = client)) }
                .serviceLoaderEnabled(false)
                .build()
        }
    }

    private companion object {
        const val COOKIE = "Cookie"
    }
}
