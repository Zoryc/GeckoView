package app.snapcast.data.di

import android.content.Context
import androidx.room.Room
import app.snapcast.data.db.BookmarkDao
import app.snapcast.data.db.HistoryDao
import app.snapcast.data.db.PlaybackPositionDao
import app.snapcast.data.db.SnapcastDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the Room database and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SnapcastDatabase =
        Room.databaseBuilder(context, SnapcastDatabase::class.java, SnapcastDatabase.FILE_NAME)
            .fallbackToDestructiveMigrationOnDowngrade(true)
            .build()

    @Provides
    fun provideHistoryDao(database: SnapcastDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideBookmarkDao(database: SnapcastDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun providePlaybackPositionDao(database: SnapcastDatabase): PlaybackPositionDao = database.playbackPositionDao()
}
