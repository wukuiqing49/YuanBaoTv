package com.wkq.bao.core.media.scraper

import org.w3c.dom.Element
import java.io.StringReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** 只读取电影 NFO 的展示字段；禁用外部实体，避免访问文件或网络。 */
object MovieNfoParser {
    data class Metadata(
        val title: String = "",
        val originalTitle: String = "",
        val year: String = "",
        val genre: String = "",
        val description: String = ""
    )

    fun parse(input: InputStream): Metadata? = runCatching {
        val bytes = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_NFO_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_NFO_BYTES + 1 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        if (bytes.size > MAX_NFO_BYTES) return@runCatching null
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        val xml = charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        // Android DOM 不支持所有桌面 JAXP 特性；先拒绝 DTD/实体声明，再按能力加强解析器。
        if ('\u0000' in xml || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return@runCatching null
        val factory = DocumentBuilderFactory.newInstance().apply {
            mapOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                XMLConstants.FEATURE_SECURE_PROCESSING to true
            ).forEach { (feature, enabled) -> runCatching { setFeature(feature, enabled) } }
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        if (!root.tagName.equals("movie", ignoreCase = true)) return@runCatching null
        fun field(name: String): String = root.getElementsByTagName(name)
            .item(0)?.textContent?.trim().orEmpty()
        val title = field("title")
        Metadata(
            title = title,
            originalTitle = field("originaltitle"),
            year = field("year").ifBlank { field("premiered").take(4) },
            genre = root.getElementsByTagName("genre").let { nodes ->
                (0 until nodes.length).mapNotNull { index ->
                    (nodes.item(index) as? Element)?.textContent?.trim()?.takeIf(String::isNotBlank)
                }.distinct().joinToString(" / ")
            },
            description = field("plot").ifBlank { field("outline") }
        )
    }.getOrNull()

    private const val MAX_NFO_BYTES = 256 * 1024
}
