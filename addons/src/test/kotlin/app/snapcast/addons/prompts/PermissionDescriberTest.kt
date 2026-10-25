package app.snapcast.addons.prompts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionDescriberTest {

    /** Readable fake texts so assertions show exactly which line was produced. */
    private val strings = object : PermissionStrings {
        override fun siteAccess(access: SiteAccess): String = when (access) {
            SiteAccess.AllSites -> "all sites"
            is SiteAccess.Domain -> "domain ${access.domain}"
            is SiteAccess.OtherDomains -> "${access.count} other domains"
            is SiteAccess.Site -> "site ${access.host}"
            is SiteAccess.OtherSites -> "${access.count} other sites"
        }

        override fun api(permission: ApiPermission): String = "api ${permission.name}"

        override fun dataCollection(permission: DataCollectionPermission): String = "data ${permission.name}"
    }

    private val describer = PermissionDescriber(strings)

    @Test
    fun `ublock origin permissions read like firefox`() {
        val permissions = listOf(
            "alarms", "dns", "menus", "privacy", "storage", "tabs", "unlimitedStorage",
            "webNavigation", "webRequest", "webRequestBlocking",
        )
        assertThat(describer.describePermissions(permissions)).containsExactly(
            "api DNS",
            "api NETWORK_REQUESTS",
            "api PRIVACY",
            "api TABS",
            "api WEB_NAVIGATION",
        ).inOrder()
        assertThat(describer.describeOrigins(listOf("<all_urls>"))).containsExactly("all sites")
    }

    @Test
    fun `silent and unknown permissions are not listed`() {
        val silent = listOf("storage", "alarms", "contextMenus", "menus", "cookies", "activeTab", "scripting", "unknownThing")
        assertThat(describer.describePermissions(silent)).isEmpty()
    }

    @Test
    fun `permissions sharing a description appear once`() {
        assertThat(describer.describePermissions(listOf("history", "topSites"))).containsExactly("api HISTORY")
        assertThat(describer.describePermissions(listOf("webRequestBlocking", "webRequest"))).containsExactly("api NETWORK_REQUESTS")
    }

    @Test
    fun `every described permission maps from its manifest name`() {
        val names = mapOf(
            "bookmarks" to ApiPermission.BOOKMARKS,
            "browserSettings" to ApiPermission.BROWSER_SETTINGS,
            "browsingData" to ApiPermission.BROWSING_DATA,
            "clipboardRead" to ApiPermission.CLIPBOARD_READ,
            "clipboardWrite" to ApiPermission.CLIPBOARD_WRITE,
            "declarativeNetRequest" to ApiPermission.DECLARATIVE_NET_REQUEST,
            "declarativeNetRequestFeedback" to ApiPermission.DECLARATIVE_NET_REQUEST_FEEDBACK,
            "devtools" to ApiPermission.DEVTOOLS,
            "downloads" to ApiPermission.DOWNLOADS,
            "downloads.open" to ApiPermission.DOWNLOADS_OPEN,
            "find" to ApiPermission.FIND,
            "geolocation" to ApiPermission.GEOLOCATION,
            "management" to ApiPermission.MANAGEMENT,
            "nativeMessaging" to ApiPermission.NATIVE_MESSAGING,
            "notifications" to ApiPermission.NOTIFICATIONS,
            "pkcs11" to ApiPermission.PKCS11,
            "proxy" to ApiPermission.PROXY,
            "sessions" to ApiPermission.SESSIONS,
            "tabHide" to ApiPermission.TAB_HIDE,
            "trialML" to ApiPermission.TRIAL_ML,
            "userScripts" to ApiPermission.USER_SCRIPTS,
        )
        names.forEach { (name, permission) ->
            assertThat(PermissionMessages.api(listOf(name))).containsExactly(permission)
        }
    }

    @Test
    fun `any all-hosts pattern collapses to all sites`() {
        listOf("*://*/*", "https://*/*", "http://*/path/*", "<all_urls>").forEach { pattern ->
            assertThat(describer.describeOrigins(listOf("https://example.com/*", pattern))).containsExactly("all sites")
        }
    }

    @Test
    fun `hosts and wildcard domains are listed`() {
        val origins = listOf("*://*.example.com/*", "https://WWW.Mozilla.org/*", "https://api.github.com/repos/*")
        assertThat(describer.describeOrigins(origins)).containsExactly(
            "domain example.com",
            "site www.mozilla.org",
            "site api.github.com",
        ).inOrder()
    }

    @Test
    fun `more than four sites collapse to three plus a count`() {
        val five = (1..5).map { "https://site$it.com/*" }
        assertThat(describer.describeOrigins(five)).containsExactly(
            "site site1.com",
            "site site2.com",
            "site site3.com",
            "2 other sites",
        ).inOrder()

        val four = (1..4).map { "https://site$it.com/*" }
        assertThat(describer.describeOrigins(four)).hasSize(4)
    }

    @Test
    fun `more than four wildcard domains collapse separately from sites`() {
        val origins = (1..6).map { "*://*.d$it.org/*" } + "https://one.example/*"
        assertThat(describer.describeOrigins(origins)).containsExactly(
            "domain d1.org",
            "domain d2.org",
            "domain d3.org",
            "3 other domains",
            "site one.example",
        ).inOrder()
    }

    @Test
    fun `duplicate and malformed origins are ignored`() {
        val origins = listOf("https://a.com/*", "https://a.com/x/*", "not a pattern", "file:///*", "https://b.com")
        assertThat(describer.describeOrigins(origins)).containsExactly("site a.com")
    }

    @Test
    fun `data collection is listed after api permissions`() {
        val lines = describer.describePermissions(
            permissions = listOf("tabs"),
            dataCollection = listOf("none", "websiteContent", "browsingActivity"),
        )
        assertThat(lines).containsExactly(
            "api TABS",
            "data BROWSING_ACTIVITY",
            "data WEBSITE_CONTENT",
        ).inOrder()
    }
}
