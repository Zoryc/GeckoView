package app.snapcast.proxy.rewrite

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DOM parsing hardened against XXE and entity expansion: manifests never need a DTD, so documents declaring
 * one are refused, and the factory is told to refuse DTDs and external entities where the platform supports
 * it (Android's parser ignores several of these settings, hence the textual pre-check).
 */
internal object SafeXml {
    private val DTD_MARKER = Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE)
    private val XML_DECLARED_ENCODING = Regex("^<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._\\-]+)[\"']")

    /** Namespace-aware DOM of [xml], or null when it is malformed or declares a DTD. */
    fun parse(xml: String): Document? {
        val text = xml.removePrefix("\uFEFF")
        if (text.isBlank() || DTD_MARKER.containsMatchIn(text)) return null
        return try {
            val builder = factory().newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.setErrorHandler(ThrowingErrorHandler)
            builder.parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes an XML document: a byte order mark wins, then the encoding of the XML declaration, then the
     * charset of the Content-Type header, then UTF-8.
     */
    fun decode(bytes: ByteArray, contentTypeCharset: Charset?): String {
        val b0 = bytes.getOrNull(0)?.toInt()?.and(0xff)
        val b1 = bytes.getOrNull(1)?.toInt()?.and(0xff)
        val b2 = bytes.getOrNull(2)?.toInt()?.and(0xff)
        when {
            b0 == 0xEF && b1 == 0xBB && b2 == 0xBF -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b0 == 0xFE && b1 == 0xFF -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            b0 == 0xFF && b1 == 0xFE -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val prolog = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = XML_DECLARED_ENCODING.find(prolog)?.groupValues?.get(1)?.let(::charsetOrNull)
        return String(bytes, declared ?: contentTypeCharset ?: Charsets.UTF_8)
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun factory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        attempt { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        attempt { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        attempt { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        attempt { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        attempt { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        attempt { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        attempt { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        attempt { factory.isXIncludeAware = false }
        attempt { factory.isValidating = false }
        return factory
    }

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Unsupported by this platform's parser; the DTD pre-check still applies.
        } catch (e: AbstractMethodError) {
            // Older parser implementations do not implement every JAXP setter.
        }
    }

    private object ThrowingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException) = throw exception
        override fun fatalError(exception: SAXParseException) = throw exception
    }
}

/**
 * Serializes a DOM back to text with an XML declaration (UTF-8). Namespace declarations are re-created
 * where the DOM lacks them: Android's namespace-aware parser drops `xmlns` attributes from the tree.
 */
internal object XmlWriter {
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    fun write(document: Document): String {
        val out = StringBuilder(4096)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val nodes = document.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeType) {
                Node.ELEMENT_NODE -> writeElement(node as Element, emptyMap(), out)
                Node.COMMENT_NODE -> out.append("<!--").append(node.nodeValue).append("-->\n")
                Node.PROCESSING_INSTRUCTION_NODE -> out.append("<?").append(node.nodeName).append(' ')
                    .append(node.nodeValue).append("?>\n")
                else -> Unit
            }
        }
        return out.toString()
    }

    private fun writeElement(element: Element, inScope: Map<String, String>, out: StringBuilder) {
        val declarations = LinkedHashMap<String, String>()
        val attributes = ArrayList<Attr>()
        val nodeMap = element.attributes
        for (i in 0 until nodeMap.length) {
            val attr = nodeMap.item(i) as Attr
            val name = attr.name
            when {
                name == "xmlns" -> declarations[""] = attr.value
                name.startsWith("xmlns:") -> declarations[name.substring(6)] = attr.value
                else -> attributes += attr
            }
        }
        val scope = HashMap(inScope).apply { putAll(declarations) }
        fun ensureBound(prefix: String, uri: String) {
            if (scope[prefix] != uri) {
                declarations[prefix] = uri
                scope[prefix] = uri
            }
        }
        val namespace = element.namespaceURI
        if (namespace != null) {
            ensureBound(element.prefix.orEmpty(), namespace)
        } else if (element.prefix == null && !scope[""].isNullOrEmpty()) {
            ensureBound("", "")
        }
        for (attr in attributes) {
            val attrNamespace = attr.namespaceURI ?: continue
            val prefix = attr.prefix ?: continue
            if (attrNamespace != XML_NS && attrNamespace != XMLNS_NS) ensureBound(prefix, attrNamespace)
        }

        out.append('<').append(element.nodeName)
        for ((prefix, uri) in declarations) {
            out.append(if (prefix.isEmpty()) " xmlns" else " xmlns:$prefix").append("=\"")
            escapeAttribute(uri, out)
            out.append('"')
        }
        for (attr in attributes) {
            out.append(' ').append(attr.name).append("=\"")
            escapeAttribute(attr.value, out)
            out.append('"')
        }
        val children = element.childNodes
        if (children.length == 0) {
            out.append("/>")
            return
        }
        out.append('>')
        writeChildren(element, scope, out)
        out.append("</").append(element.nodeName).append('>')
    }

    private fun writeChildren(parent: Node, scope: Map<String, String>, out: StringBuilder) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.ELEMENT_NODE -> writeElement(child as Element, scope, out)
                Node.TEXT_NODE -> escapeText(child.nodeValue, out)
                Node.CDATA_SECTION_NODE ->
                    out.append("<![CDATA[").append(child.nodeValue.replace("]]>", "]]]]><![CDATA[>")).append("]]>")
                Node.COMMENT_NODE -> out.append("<!--").append(child.nodeValue).append("-->")
                Node.PROCESSING_INSTRUCTION_NODE ->
                    out.append("<?").append(child.nodeName).append(' ').append(child.nodeValue).append("?>")
                Node.ENTITY_REFERENCE_NODE -> writeChildren(child, scope, out)
                else -> Unit
            }
        }
    }

    private fun escapeText(text: String, out: StringBuilder) {
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '\r' -> out.append("&#13;")
                else -> out.append(c)
            }
        }
    }

    private fun escapeAttribute(text: String, out: StringBuilder) {
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\n' -> out.append("&#10;")
                '\r' -> out.append("&#13;")
                '\t' -> out.append("&#9;")
                else -> out.append(c)
            }
        }
    }
}

/** Element name without namespace prefix. */
internal val Element.localTag: String
    get() = localName ?: nodeName.substringAfter(':')

/** Direct child elements with the given local name. */
internal fun Element.childElements(localName: String): List<Element> {
    val result = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localTag == localName) result += node
    }
    return result
}
