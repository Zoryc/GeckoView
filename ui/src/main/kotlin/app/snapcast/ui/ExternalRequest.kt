package app.snapcast.ui

/** Requests coming from outside the UI (launcher intents, links shared to the app, the cast notification). */
sealed interface ExternalRequest {
    /** Open [url] in a new tab (ACTION_VIEW / ACTION_SEND). */
    data class OpenUrl(val url: String, val private: Boolean = false) : ExternalRequest

    /** Show the expanded cast controller (tap on the casting notification). */
    data object OpenCastController : ExternalRequest
}
