package app.snapcast.cast.di

import app.snapcast.cast.CastController
import app.snapcast.cast.CastTarget
import app.snapcast.cast.chromecast.ChromecastTarget
import app.snapcast.cast.controller.CastControllerImpl
import app.snapcast.data.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class CastModule {
    @Binds
    abstract fun bindCastTarget(impl: ChromecastTarget): CastTarget

    @Binds
    abstract fun bindCastController(impl: CastControllerImpl): CastController

    @Binds
    @IntoSet
    abstract fun bindCastInitializer(impl: CastInitializer): AppInitializer
}
