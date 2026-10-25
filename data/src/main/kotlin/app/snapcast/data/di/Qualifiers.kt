package app.snapcast.data.di

import javax.inject.Qualifier

/** Process-wide [kotlinx.coroutines.CoroutineScope] (SupervisorJob + Dispatchers.Default) provided by the data module. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
