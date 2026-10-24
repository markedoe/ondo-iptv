package com.markedoe.ondo.core.parsing

import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * SAX parsing that works the same on the JVM (Xerces) and Android (Expat): no namespace processing,
 * no validation, and external entities / DTDs (`<!DOCTYPE tv SYSTEM "xmltv.dtd">`) are never fetched.
 */
internal object Sax {
    private val disabledFeatures = listOf(
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    )

    /**
     * Parses `input` with `handler` (element names arrive as `qName`), read through [XmlText]: the faults providers'
     * files commonly have do not stop it. Throws on what is still malformed.
     */
    fun parse(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        for (feature in disabledFeatures) {
            // Android's Expat parser does not know every feature; the entity resolver covers it.
            try {
                factory.setFeature(feature, false)
            } catch (_: Exception) {
            }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.parse(InputSource(XmlText.reader(input)))
    }
}
