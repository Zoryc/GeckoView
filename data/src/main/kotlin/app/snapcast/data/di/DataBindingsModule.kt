package app.snapcast.data.di

import app.snapcast.data.AppInitializer
import app.snapcast.data.bookmarks.BookmarkRepository
import app.snapcast.data.bookmarks.DefaultBookmarksInitializer
import app.snapcast.data.bookmarks.RoomBookmarkRepository
import app.snapcast.data.diagnostics.DiagnosticsLog
import app.snapcast.data.diagnostics.InMemoryDiagnosticsLog
import app.snapcast.data.history.HistoryRepository
import app.snapcast.data.history.RoomHistoryRepository
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.data.playback.RoomPlaybackPositionRepository
import app.snapcast.data.settings.DataStoreSettingsRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.data.tabs.FileTabSessionStore
import app.snapcast.data.tabs.TabSessionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the data module's public contracts to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataBindingsModule {
    @Binds
    abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds
    abstract fun bindBookmarkRepository(impl: RoomBookmarkRepository): BookmarkRepository

    @Binds
    abstract fun bindPlaybackPositionRepository(impl: RoomPlaybackPositionRepository): PlaybackPositionRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindTabSessionStore(impl: FileTabSessionStore): TabSessionStore

    @Binds
    abstract fun bindDiagnosticsLog(impl: InMemoryDiagnosticsLog): DiagnosticsLog

    @Binds
    @IntoSet
    abstract fun bindDefaultBookmarksInitializer(impl: DefaultBookmarksInitializer): AppInitializer
}
