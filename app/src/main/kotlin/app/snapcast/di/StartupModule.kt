package app.snapcast.di

import app.snapcast.data.AppInitializer
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class StartupModule {
    /** Declared so the set exists even if no module contributes an initializer. */
    @Multibinds
    abstract fun appInitializers(): Set<AppInitializer>
}
