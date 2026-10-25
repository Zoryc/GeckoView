package app.snapcast.proxy.rewrite

import app.snapcast.core.util.UrlUtils
import app.snapcast.proxy.http.PathCodec
import org.w3c.dom.Element

/**
 * Rewrites a DASH manifest so segments, initialization data, indexes and manifest refreshes go through the
 * proxy. DOM based; documents with a DTD are refused.
 *
 * The effective BaseURL is tracked down MPD > Period > AdaptationSet > Representation, starting with the
 * manifest URL (the final URL after redirects):
 * - every `BaseURL` becomes its proxied form: a base route when the resolved URL is a directory (ends with "/"),
 *   an exact route otherwise;
 * - `SegmentTemplate` `@media`, `@initialization`, `@index`, `@bitstreamSwitching`, `SegmentURL` `@media`/`@index`
 *   and `Initialization`/`RepresentationIndex`/`BitstreamSwitching` `@sourceURL` are resolved against the effective
 *   base keeping `$...$` placeholders, then split at the last "/" before the first placeholder into a directory and
 *   a rest: the directory becomes a base route and the rest (still containing the placeholders) is appended, so
 *   players expand templates as usual. Plain URLs become exact routes;
 * - `Location`, `PatchLocation` and `xlink:href` become exact routes.
 *
 * All produced URLs are root-relative, so they ignore the BaseURL chain the player computes; when a
 * Representation has a different base than the level that defines an inherited SegmentTemplate URL, that URL is
 * copied onto the Representation's own SegmentTemplate (created when missing) and rewritten against its base.
 */
internal object DashRewriter {
    private const val XLINK_NS = "http://www.w3.org/1999/xlink"
    private val TEMPLATE_ATTRIBUTES = listOf("media", "initialization", "index", "bitstreamSwitching")
    private val SEGMENT_BASE_CHILDREN = listOf("Initialization", "RepresentationIndex", "BitstreamSwitching")

    /** The rewritten manifest, or null when [xml] is not a well-formed MPD (or declares a DTD). */
    fun rewrite(xml: String, manifestUrl: String, mapper: ProxyUrlMapper): String? {
        val document = SafeXml.parse(xml) ?: return null
        val root = document.documentElement ?: return null
        if (root.localTag != "MPD") return null
        Walker(mapper).rewriteMpd(root, manifestUrl)
        return XmlWriter.write(document)
    }

    /** An inherited SegmentTemplate URL attribute: its original value and the base of the level defining it. */
    private data class InheritedValue(val value: String, val base: String)

    private class Walker(private val mapper: ProxyUrlMapper) {

        fun rewriteMpd(mpd: Element, manifestUrl: String) {
            (mpd.childElements("Location") + mpd.childElements("PatchLocation")).forEach { location ->
                val resolved = UrlUtils.resolve(manifestUrl, location.textContent.trim())
                if (UrlUtils.isHttp(resolved)) location.textContent = mapper.exact(resolved)
            }
            val base = applyBaseUrls(mpd, manifestUrl)
            mpd.childElements("Period").forEach { rewritePeriod(it, base) }
        }

        private fun rewritePeriod(period: Element, parentBase: String) {
            rewriteXlink(period, parentBase)
            val base = applyBaseUrls(period, parentBase)
            val inherited = inherit(emptyMap(), period, base)
            rewriteSegmentInfo(period, base)
            period.childElements("AdaptationSet").forEach { rewriteAdaptationSet(it, base, inherited) }
        }

        private fun rewriteAdaptationSet(set: Element, parentBase: String, parentInherited: Map<String, InheritedValue>) {
            rewriteXlink(set, parentBase)
            val base = applyBaseUrls(set, parentBase)
            val inherited = inherit(parentInherited, set, base)
            rewriteSegmentInfo(set, base)
            set.childElements("Representation").forEach { rewriteRepresentation(it, base, inherited) }
        }

        private fun rewriteRepresentation(rep: Element, parentBase: String, inherited: Map<String, InheritedValue>) {
            val base = applyBaseUrls(rep, parentBase)
            materializeInherited(rep, base, inherited)
            rewriteSegmentInfo(rep, base)
        }

        /** [parent] overridden by the (not yet rewritten) SegmentTemplate URL attributes of [element]. */
        private fun inherit(parent: Map<String, InheritedValue>, element: Element, base: String): Map<String, InheritedValue> {
            val template = element.childElements("SegmentTemplate").firstOrNull() ?: return parent
            val result = LinkedHashMap(parent)
            for (attribute in TEMPLATE_ATTRIBUTES) {
                templateValue(template, attribute)?.let { result[attribute] = InheritedValue(it, base) }
            }
            return result
        }

        /**
         * Copies inherited template URLs whose defining level has another base than [rep] onto the
         * Representation's own SegmentTemplate (still unrewritten; [rewriteSegmentInfo] rewrites them next).
         */
        private fun materializeInherited(rep: Element, base: String, inherited: Map<String, InheritedValue>) {
            if (inherited.isEmpty()) return
            if (rep.childElements("SegmentList").isNotEmpty() || rep.childElements("SegmentBase").isNotEmpty()) return
            val own = rep.childElements("SegmentTemplate").firstOrNull()
            val pending = inherited.filter { (attribute, origin) ->
                origin.base != base && (own == null || templateValue(own, attribute) == null)
            }
            if (pending.isEmpty()) return
            val template = own ?: createSegmentTemplate(rep)
            pending.forEach { (attribute, origin) -> template.setAttribute(attribute, origin.value) }
        }

        private fun createSegmentTemplate(rep: Element): Element {
            val name = rep.prefix?.let { "$it:SegmentTemplate" } ?: "SegmentTemplate"
            val template = rep.ownerDocument.createElementNS(rep.namespaceURI, name)
            val before = rep.childElements("SubRepresentation").firstOrNull()
            if (before != null) rep.insertBefore(template, before) else rep.appendChild(template)
            return template
        }

        /** Rewrites the BaseURL children of [element]; returns the effective base for its descendants. */
        private fun applyBaseUrls(element: Element, parentBase: String): String {
            var effective: String? = null
            for (baseUrl in element.childElements("BaseURL")) {
                val resolved = UrlUtils.resolve(parentBase, baseUrl.textContent.trim())
                if (effective == null) effective = resolved
                if (UrlUtils.isHttp(resolved)) {
                    baseUrl.textContent = if (isDirectory(resolved)) mapper.base(resolved) else mapper.exact(resolved)
                }
            }
            return effective ?: parentBase
        }

        private fun rewriteSegmentInfo(element: Element, base: String) {
            element.childElements("SegmentTemplate").forEach { template ->
                for (attribute in TEMPLATE_ATTRIBUTES) {
                    val value = templateValue(template, attribute) ?: continue
                    template.setAttribute(attribute, rewriteReference(value, base))
                }
                rewriteSegmentBaseChildren(template, base)
            }
            element.childElements("SegmentList").forEach { list ->
                rewriteXlink(list, base)
                list.childElements("SegmentURL").forEach { segment ->
                    rewriteAttribute(segment, "media", base)
                    rewriteAttribute(segment, "index", base)
                }
                rewriteSegmentBaseChildren(list, base)
            }
            element.childElements("SegmentBase").forEach { rewriteSegmentBaseChildren(it, base) }
        }

        private fun rewriteSegmentBaseChildren(element: Element, base: String) {
            SEGMENT_BASE_CHILDREN.forEach { name ->
                element.childElements(name).forEach { rewriteAttribute(it, "sourceURL", base) }
            }
        }

        private fun rewriteAttribute(element: Element, attribute: String, base: String) {
            val value = element.getAttribute(attribute).trim()
            if (value.isNotEmpty()) element.setAttribute(attribute, rewriteReference(value, base))
        }

        private fun rewriteXlink(element: Element, base: String) {
            val href = element.getAttributeNodeNS(XLINK_NS, "href") ?: return
            val value = href.value.trim()
            if (value.isEmpty() || value.startsWith("urn:", ignoreCase = true)) return
            val resolved = UrlUtils.resolve(base, value)
            if (UrlUtils.isHttp(resolved)) href.value = mapper.exact(resolved)
        }

        /** A SegmentTemplate URL attribute; `@bitstreamSwitching` may also be a boolean, which is not a URL. */
        private fun templateValue(template: Element, attribute: String): String? {
            val value = template.getAttribute(attribute).trim().takeIf { it.isNotEmpty() } ?: return null
            if (attribute == "bitstreamSwitching" && (value == "true" || value == "false")) return null
            return value
        }

        /** Proxied form of a (possibly templated) reference resolved against [base]; unchanged when not http(s). */
        fun rewriteReference(value: String, base: String): String {
            val resolved = UrlUtils.resolve(base, value)
            if (!UrlUtils.isHttp(resolved)) return value
            val dollar = resolved.indexOf('$')
            if (dollar < 0) return mapper.exact(resolved)
            val authorityEnd = resolved.indexOf('/', resolved.indexOf("//") + 2).let { if (it < 0) resolved.length else it }
            val pathEnd = resolved.indexOfAny(charArrayOf('?', '#'), authorityEnd).let { if (it < 0) resolved.length else it }
            val slash = resolved.lastIndexOf('/', minOf(dollar, pathEnd))
            if (slash < authorityEnd || dollar < authorityEnd) return resolved
            val baseDir = resolved.substring(0, slash + 1)
            val rest = resolved.substring(slash + 1)
            return mapper.base(baseDir) + PathCodec.protectPlusInPath(rest)
        }

        private fun isDirectory(url: String): Boolean {
            val pathEnd = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
            return pathEnd > 0 && url[pathEnd - 1] == '/'
        }
    }
}
