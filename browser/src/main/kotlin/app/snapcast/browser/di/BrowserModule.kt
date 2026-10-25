package app.snapcast.browser.di

import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.ExternalResponseInterceptor
import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.browser.LoadInterceptor
import app.snapcast.browser.SessionObserver
import app.snapcast.browser.notifications.WebNotificationsInitializer
import app.snapcast.browser.runtime.GeckoRuntimeProviderImpl
import app.snapcast.browser.runtime.RuntimeInitializer
import app.snapcast.browser.tabs.TabManager
import app.snapcast.data.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Browser bindings plus the hook sets other modules contribute to (empty when nobody does). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BrowserModule {
    @Binds
    abstract fun bindRuntimeProvider(impl: GeckoRuntimeProviderImpl): GeckoRuntimeProvider

    @Binds
    abstract fun bindBrowserTabs(impl: TabManager): BrowserTabs

    @Binds
    @IntoSet
    abstract fun bindRuntimeInitializer(impl: RuntimeInitializer): AppInitializer

    @Binds
    @IntoSet
    abstract fun bindWebNotificationsInitializer(impl: WebNotificationsInitializer): AppInitializer

    @Multibinds
    abstract fun sessionObservers(): Set<SessionObserver>

    @Multibinds
    abstract fun loadInterceptors(): Set<LoadInterceptor>

    @Multibinds
    abstract fun externalResponseInterceptors(): Set<ExternalResponseInterceptor>
}
