package app.snapcast.ui.di

import app.snapcast.data.AppInitializer
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Installs the app's image loader before any screen draws: posters go through the shared OkHttpClient (one
 * connection pool for the whole app), and the network fetcher is registered explicitly, so no build tool change
 * to Coil's ServiceLoader lookup can silently stop every poster from loading.
 */
internal class ImageLoaderInitializer @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : AppInitializer {
    override fun initialize() {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient)) }
                .serviceLoaderEnabled(false)
                .build()
        }
    }
}
