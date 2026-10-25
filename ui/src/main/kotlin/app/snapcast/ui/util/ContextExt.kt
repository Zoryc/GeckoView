package app.snapcast.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** The [Activity] behind this context, unwrapping [ContextWrapper]s (Compose hands out wrapped contexts). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The hosting [FragmentActivity] (needed by the Cast device chooser), or null. */
fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity
