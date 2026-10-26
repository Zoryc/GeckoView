package app.snapcast.ui.di

import app.snapcast.data.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UiModule {
    @Binds
    @IntoSet
    abstract fun bindImageLoaderInitializer(impl: ImageLoaderInitializer): AppInitializer
}
