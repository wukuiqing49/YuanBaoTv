package com.wkq.bao.core.media.scraper

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
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
