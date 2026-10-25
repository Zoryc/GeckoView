package app.snapcast.browser.notifications

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets the notification click activity and dismiss receiver reach the singleton controller. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WebNotificationEntryPoint {
    fun webNotifications(): WebNotificationController
}
