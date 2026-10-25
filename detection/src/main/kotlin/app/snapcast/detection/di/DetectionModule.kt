package app.snapcast.detection.di

import app.snapcast.browser.SessionObserver
import app.snapcast.data.AppInitializer
import app.snapcast.detection.DetectedVideoRepository
import app.snapcast.detection.DetectedVideoRepositoryImpl
import app.snapcast.detection.DetectionSessionObserver
import app.snapcast.detection.extension.DetectorExtensionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DetectionModule {
    @Binds
    abstract fun bindDetectedVideoRepository(impl: DetectedVideoRepositoryImpl): DetectedVideoRepository

    @Binds
    @IntoSet
    abstract fun bindSessionObserver(observer: DetectionSessionObserver): SessionObserver

    @Binds
    @IntoSet
    abstract fun bindExtensionInitializer(manager: DetectorExtensionManager): AppInitializer
}
