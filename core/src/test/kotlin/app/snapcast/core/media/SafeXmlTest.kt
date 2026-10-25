package app.snapcast.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.InputSource
import java.io.StringReader

class SafeXmlTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun xxe(): String {
        val secret = temp.newFile("secret.txt").apply { writeText("TOP-SECRET-CONTENT") }
        return """
            <?xml version="1.0"?>
            <!DOCTYPE root [ <!ENTITY xxe SYSTEM "${secret.toURI()}"> ]>
            <root>&xxe;</root>
        """.trimIndent()
    }

    @Test
    fun `documents declaring a DTD are rejected before parsing`() {
        assertThat(SafeXml.parse(xxe())).isNull()
        assertThat(SafeXml.parse("<!doctype root><root/>")).isNull()
    }

    @Test
    fun `the builder itself never expands external entities`() {
        // Defence in depth: even without the DTD pre-check, the hardened builder must not read the file.
        val content = try {
            SafeXml.newDocumentBuilder().parse(InputSource(StringReader(xxe()))).documentElement.textContent
        } catch (e: Exception) {
            null
        }
        if (content != null) assertThat(content).doesNotContain("TOP-SECRET-CONTENT")
    }

    @Test
    fun `parses ordinary documents with namespaces`() {
        val doc = SafeXml.parse("﻿<?xml version=\"1.0\"?><a:root xmlns:a=\"urn:x\" a:attr=\" v \"><a:child>t</a:child><child/></a:root>")!!
        val root = doc.documentElement
        assertThat(root.localTag).isEqualTo("root")
        assertThat(root.attr("attr")).isEqualTo("v")
        assertThat(root.attr("missing")).isNull()
        assertThat(root.childElements("child")).hasSize(2)
        assertThat(root.childElements()).hasSize(2)
        assertThat(root.descendants("child").map { it.textContent }).containsExactly("t", "").inOrder()
    }

    @Test
    fun `malformed and blank input yield null`() {
        assertThat(SafeXml.parse("<root>")).isNull()
        assertThat(SafeXml.parse("   ")).isNull()
        assertThat(SafeXml.parse("not xml")).isNull()
    }
}
