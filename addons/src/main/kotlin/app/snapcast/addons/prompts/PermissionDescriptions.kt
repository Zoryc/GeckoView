package app.snapcast.addons.prompts

import javax.inject.Inject

/**
 * API permissions Firefox describes in its install prompt. Permissions missing here (storage, alarms,
 * menus, cookies, unlimitedStorage, activeTab, scripting, ...) are granted silently, as in Firefox.
 * Several manifest names may share one description.
 */
internal enum class ApiPermission(vararg names: String) {
    BOOKMARKS("bookmarks"),
    BROWSER_SETTINGS("browserSettings"),
    BROWSING_DATA("browsingData"),
    CLIPBOARD_READ("clipboardRead"),
    CLIPBOARD_WRITE("clipboardWrite"),
    DECLARATIVE_NET_REQUEST("declarativeNetRequest"),
    DECLARATIVE_NET_REQUEST_FEEDBACK("declarativeNetRequestFeedback"),
    DEVTOOLS("devtools"),
    DNS("dns"),
    DOWNLOADS("downloads"),
    DOWNLOADS_OPEN("downloads.open"),
    FIND("find"),
    GEOLOCATION("geolocation"),
    HISTORY("history", "topSites"),
    MANAGEMENT("management"),
    NATIVE_MESSAGING("nativeMessaging"),
    NETWORK_REQUESTS("webRequest", "webRequestBlocking"),
    NOTIFICATIONS("notifications"),
    PKCS11("pkcs11"),
    PRIVACY("privacy"),
    PROXY("proxy"),
    SESSIONS("sessions"),
    TABS("tabs"),
    TAB_HIDE("tabHide"),
    TRIAL_ML("trialML"),
    USER_SCRIPTS("userScripts"),
    WEB_NAVIGATION("webNavigation"),
    ;

    val manifestNames: Set<String> = names.toSet()
}

/** Data collection categories an add-on declares (`data_collection_permissions`). "none" is not listed. */
internal enum class DataCollectionPermission(val manifestName: String) {
    AUTHENTICATION_INFO("authenticationInfo"),
    BOOKMARKS_INFO("bookmarksInfo"),
    BROWSING_ACTIVITY("browsingActivity"),
    FINANCIAL_AND_PAYMENT_INFO("financialAndPaymentInfo"),
    HEALTH_INFO("healthInfo"),
    LOCATION_INFO("locationInfo"),
    PERSONAL_COMMUNICATIONS("personalCommunications"),
    PERSONALLY_IDENTIFYING_INFO("personallyIdentifyingInfo"),
    SEARCH_TERMS("searchTerms"),
    TECHNICAL_AND_INTERACTION("technicalAndInteraction"),
    WEBSITE_ACTIVITY("websiteActivity"),
    WEBSITE_CONTENT("websiteContent"),
}

/** One line about host access. */
internal sealed interface SiteAccess {
    data object AllSites : SiteAccess

    /** `*.example.com` patterns. */
    data class Domain(val domain: String) : SiteAccess

    data class OtherDomains(val count: Int) : SiteAccess

    data class Site(val host: String) : SiteAccess

    data class OtherSites(val count: Int) : SiteAccess
}

/** Turns manifest permissions and match patterns into prompt lines, following Firefox's rules. */
internal object PermissionMessages {
    const val ALL_URLS = "<all_urls>"

    /** Firefox lists up to this many hosts (or domains); beyond that it shows [SHOWN_WHEN_COLLAPSED] and a count. */
    private const val MAX_LISTED = 4
    private const val SHOWN_WHEN_COLLAPSED = 3

    /** Same rule as Firefox: `scheme://host/...` where scheme may be `*`. */
    private val MATCH_PATTERN = Regex("^[a-z*]+://([^/]*)/", RegexOption.IGNORE_CASE)

    private val API_BY_NAME: Map<String, ApiPermission> =
        ApiPermission.entries.flatMap { permission -> permission.manifestNames.map { it to permission } }.toMap()

    private val DATA_BY_NAME: Map<String, DataCollectionPermission> =
        DataCollectionPermission.entries.associateBy { it.manifestName }

    /** API permissions worth showing, deduplicated, in a stable order. */
    fun api(permissions: Collection<String>): List<ApiPermission> {
        val present = permissions.mapNotNullTo(HashSet()) { API_BY_NAME[it.trim()] }
        return ApiPermission.entries.filter { it in present }
    }

    fun dataCollection(permissions: Collection<String>): List<DataCollectionPermission> {
        val present = permissions.mapNotNullTo(HashSet()) { DATA_BY_NAME[it.trim()] }
        return DataCollectionPermission.entries.filter { it in present }
    }

    /**
     * Host access lines: "all websites" alone when any pattern covers every host; otherwise wildcard
     * domains, then single hosts, each group collapsed to three entries plus a count when longer than four.
     */
    fun siteAccess(origins: Collection<String>): List<SiteAccess> {
        val domains = LinkedHashSet<String>()
        val sites = LinkedHashSet<String>()
        for (origin in origins) {
            val pattern = origin.trim()
            if (pattern == ALL_URLS) return listOf(SiteAccess.AllSites)
            val host = MATCH_PATTERN.find(pattern)?.groupValues?.get(1)?.lowercase()
            if (host.isNullOrEmpty()) continue
            when {
                host == "*" -> return listOf(SiteAccess.AllSites)
                host.startsWith("*.") -> host.removePrefix("*.").takeIf { it.isNotEmpty() }?.let(domains::add)
                else -> sites.add(host)
            }
        }
        return collapse(domains, { SiteAccess.Domain(it) }, { SiteAccess.OtherDomains(it) }) +
            collapse(sites, { SiteAccess.Site(it) }, { SiteAccess.OtherSites(it) })
    }

    private fun collapse(
        items: Set<String>,
        single: (String) -> SiteAccess,
        others: (Int) -> SiteAccess,
    ): List<SiteAccess> =
        if (items.size <= MAX_LISTED) {
            items.map(single)
        } else {
            items.take(SHOWN_WHEN_COLLAPSED).map(single) + others(items.size - SHOWN_WHEN_COLLAPSED)
        }
}

/** Localised text for each prompt line; implemented with Android resources, faked in tests. */
internal interface PermissionStrings {
    fun siteAccess(access: SiteAccess): String

    fun api(permission: ApiPermission): String

    fun dataCollection(permission: DataCollectionPermission): String
}

/** Human-readable permission lists for [app.snapcast.addons.AddonPermissionPrompt]. */
internal class PermissionDescriber @Inject constructor(
    private val strings: PermissionStrings,
) {
    /** API permission lines followed by data collection lines, without duplicates. */
    fun describePermissions(permissions: Collection<String>, dataCollection: Collection<String> = emptyList()): List<String> =
        (PermissionMessages.api(permissions).map(strings::api) +
            PermissionMessages.dataCollection(dataCollection).map(strings::dataCollection)).distinct()

    /** Host access lines for match patterns such as `<all_urls>` or `*://*.example.com/*`. */
    fun describeOrigins(origins: Collection<String>): List<String> =
        PermissionMessages.siteAccess(origins).map(strings::siteAccess).distinct()
}
