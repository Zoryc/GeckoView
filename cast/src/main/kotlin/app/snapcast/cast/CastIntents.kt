package app.snapcast.cast

/** Intent contract between the casting notification and the app's launcher activity. */
object CastIntents {
    /**
     * Boolean extra set on the launcher intent when the user taps the casting notification; the activity
     * should show the expanded cast controller.
     */
    const val EXTRA_OPEN_CAST_CONTROLLER = "app.snapcast.extra.OPEN_CAST_CONTROLLER"
}
