package app.snapcast.addons.di

import app.snapcast.addons.AddonsInitializer
import app.snapcast.addons.AddonsManager
import app.snapcast.addons.AddonsRepository
import app.snapcast.addons.AddonsSessionObserver
import app.snapcast.addons.install.XpiLoadInterceptor
import app.snapcast.addons.install.XpiResponseInterceptor
import app.snapcast.addons.prompts.PermissionStrings
import app.snapcast.addons.prompts.ResourcePermissionStrings
import app.snapcast.browser.ExternalResponseInterceptor
import app.snapcast.browser.LoadInterceptor
import app.snapcast.browser.SessionObserver
import app.snapcast.data.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Add-on bindings plus this module's contributions to the browser hooks and startup. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AddonsModule {
    @Binds
    abstract fun bindAddonsRepository(impl: AddonsManager): AddonsRepository

    @Binds
    abstract fun bindPermissionStrings(impl: ResourcePermissionStrings): PermissionStrings

    @Binds
    @IntoSet
    abstract fun bindSessionObserver(impl: AddonsSessionObserver): SessionObserver

    @Binds
    @IntoSet
    abstract fun bindLoadInterceptor(impl: XpiLoadInterceptor): LoadInterceptor

    @Binds
    @IntoSet
    abstract fun bindResponseInterceptor(impl: XpiResponseInterceptor): ExternalResponseInterceptor

    @Binds
    @IntoSet
    abstract fun bindInitializer(impl: AddonsInitializer): AppInitializer
}
