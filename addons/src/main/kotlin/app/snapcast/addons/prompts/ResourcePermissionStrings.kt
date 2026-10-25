package app.snapcast.addons.prompts

import android.content.Context
import androidx.annotation.StringRes
import app.snapcast.addons.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [PermissionStrings] backed by this module's string resources. */
internal class ResourcePermissionStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionStrings {

    override fun siteAccess(access: SiteAccess): String = when (access) {
        SiteAccess.AllSites -> context.getString(R.string.addons_perm_all_sites)
        is SiteAccess.Domain -> context.getString(R.string.addons_perm_domain, access.domain)
        is SiteAccess.OtherDomains ->
            context.resources.getQuantityString(R.plurals.addons_perm_other_domains, access.count, access.count)
        is SiteAccess.Site -> context.getString(R.string.addons_perm_site, access.host)
        is SiteAccess.OtherSites ->
            context.resources.getQuantityString(R.plurals.addons_perm_other_sites, access.count, access.count)
    }

    override fun api(permission: ApiPermission): String = context.getString(apiText(permission))

    override fun dataCollection(permission: DataCollectionPermission): String =
        context.getString(dataCollectionText(permission))

    @StringRes
    private fun apiText(permission: ApiPermission): Int = when (permission) {
        ApiPermission.BOOKMARKS -> R.string.addons_perm_bookmarks
        ApiPermission.BROWSER_SETTINGS -> R.string.addons_perm_browser_settings
        ApiPermission.BROWSING_DATA -> R.string.addons_perm_browsing_data
        ApiPermission.CLIPBOARD_READ -> R.string.addons_perm_clipboard_read
        ApiPermission.CLIPBOARD_WRITE -> R.string.addons_perm_clipboard_write
        ApiPermission.DECLARATIVE_NET_REQUEST -> R.string.addons_perm_declarative_net_request
        ApiPermission.DECLARATIVE_NET_REQUEST_FEEDBACK -> R.string.addons_perm_declarative_net_request_feedback
        ApiPermission.DEVTOOLS -> R.string.addons_perm_devtools
        ApiPermission.DNS -> R.string.addons_perm_dns
        ApiPermission.DOWNLOADS -> R.string.addons_perm_downloads
        ApiPermission.DOWNLOADS_OPEN -> R.string.addons_perm_downloads_open
        ApiPermission.FIND -> R.string.addons_perm_find
        ApiPermission.GEOLOCATION -> R.string.addons_perm_geolocation
        ApiPermission.HISTORY -> R.string.addons_perm_history
        ApiPermission.MANAGEMENT -> R.string.addons_perm_management
        ApiPermission.NATIVE_MESSAGING -> R.string.addons_perm_native_messaging
        ApiPermission.NETWORK_REQUESTS -> R.string.addons_perm_network_requests
        ApiPermission.NOTIFICATIONS -> R.string.addons_perm_notifications
        ApiPermission.PKCS11 -> R.string.addons_perm_pkcs11
        ApiPermission.PRIVACY -> R.string.addons_perm_privacy
        ApiPermission.PROXY -> R.string.addons_perm_proxy
        ApiPermission.SESSIONS -> R.string.addons_perm_sessions
        ApiPermission.TABS -> R.string.addons_perm_tabs
        ApiPermission.TAB_HIDE -> R.string.addons_perm_tab_hide
        ApiPermission.TRIAL_ML -> R.string.addons_perm_trial_ml
        ApiPermission.USER_SCRIPTS -> R.string.addons_perm_user_scripts
        ApiPermission.WEB_NAVIGATION -> R.string.addons_perm_web_navigation
    }

    @StringRes
    private fun dataCollectionText(permission: DataCollectionPermission): Int = when (permission) {
        DataCollectionPermission.AUTHENTICATION_INFO -> R.string.addons_data_authentication_info
        DataCollectionPermission.BOOKMARKS_INFO -> R.string.addons_data_bookmarks_info
        DataCollectionPermission.BROWSING_ACTIVITY -> R.string.addons_data_browsing_activity
        DataCollectionPermission.FINANCIAL_AND_PAYMENT_INFO -> R.string.addons_data_financial_and_payment_info
        DataCollectionPermission.HEALTH_INFO -> R.string.addons_data_health_info
        DataCollectionPermission.LOCATION_INFO -> R.string.addons_data_location_info
        DataCollectionPermission.PERSONAL_COMMUNICATIONS -> R.string.addons_data_personal_communications
        DataCollectionPermission.PERSONALLY_IDENTIFYING_INFO -> R.string.addons_data_personally_identifying_info
        DataCollectionPermission.SEARCH_TERMS -> R.string.addons_data_search_terms
        DataCollectionPermission.TECHNICAL_AND_INTERACTION -> R.string.addons_data_technical_and_interaction
        DataCollectionPermission.WEBSITE_ACTIVITY -> R.string.addons_data_website_activity
        DataCollectionPermission.WEBSITE_CONTENT -> R.string.addons_data_website_content
    }
}
