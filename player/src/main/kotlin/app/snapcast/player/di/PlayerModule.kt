package app.snapcast.player.di

import app.snapcast.player.PlayerLauncher
import app.snapcast.player.PlayerLauncherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlayerModule {
    @Binds
    abstract fun bindPlayerLauncher(impl: PlayerLauncherImpl): PlayerLauncher
}
