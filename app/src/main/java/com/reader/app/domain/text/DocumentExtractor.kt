package com.reader.app.domain.text

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

// All comments in this file are `//` line comments. (Kotlin 2.1+ has a
// stricter lexer that can mis-tokenise slash-star sequences appearing
// even inside line comments, and an earlier banner here that quoted
// them literally — to warn future contributors not to use them — was
// itself triggering an "Unclosed comment" build failure. Lesson: just
// avoid mentioning the literal pair entirely; if you need to refer to
// a block comment in prose, write "block comment" in words.)

// Single entry point for "give me the plain text inside this document".
//
// Supported types (per user spec — "saare text-based document support
// karna chahie"):
//
//   - text-class MIME types (txt, md, csv, log, json, xml, rtf, ...)
//     are read directly as UTF-8.
//   - application/pdf is handled by PDFBox-Android.
//   - .pptx (presentationml) and .docx (wordprocessingml) are unzipped
//     and the visible text elements are scraped by regex.
//
// Anything else — including image-based PDFs and the legacy binary
// .ppt / .doc formats — is rejected with a friendly Hindi message.
object DocumentExtractor {

    sealed interface Result {
        data class Ok(val text: String, val suggestedTitle: String?) : Result
        data class Reject(val reason: String) : Result
    }

    // Read [uri] and return its extracted text. Always runs on
    // Dispatchers.IO — caller can invoke from any context.
    suspend fun extract(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime  = resolver.getType(uri).orEmpty().lowercase()
        val name  = queryDisplayName(resolver, uri)
        val ext   = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val title = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

        try {
            val text = when {
                isLikelyText(mime, ext) -> readPlainText(resolver, uri)
                mime == "application/pdf" || ext == "pdf" -> extractPdf(resolver, uri)
                isPptx(mime, ext)         -> extractOoxml(resolver, uri, OoxmlKind.Pptx)
                isDocx(mime, ext)         -> extractOoxml(resolver, uri, OoxmlKind.Docx)
                isLegacyOffice(mime, ext) -> return@withContext Result.Reject(
                    "Legacy .ppt / .doc files supported nahi hain. Kripya .pptx / .docx mein " +
                        "save karke dobara try karein, ya text content paste kar dein."
                )
                isImageOnly(mime, ext) -> return@withContext Result.Reject(
                    "Image-based document detect hua. Sirf text-based documents accept hote " +
                        "hain (.txt, .md, .pdf with selectable text, .pptx, .docx)."
                )
                else -> return@withContext Result.Reject(
                    "Is file type ka support nahi hai" +
                        (if (mime.isNotEmpty()) " ($mime)" else "") +
                        ". Sirf .txt / .md / .pdf / .pptx / .docx supported hain."
                )
            }
            if (text.isBlank()) {
                Result.Reject(
                    "Document mein readable text nahi mila. Yeh image-based ya scanned " +
                        "document ho sakta hai — text-based version use karein."
                )
            } else {
                Result.Ok(text, title)
            }
        } catch (t: Throwable) {
            Result.Reject("File read nahi ho saki: ${t.message ?: "unknown error"}")
        }
    }

    // ---------- type sniffing ----------

    private fun isLikelyText(mime: String, ext: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in TEXT_MIMES) return true
        return ext in TEXT_EXTENSIONS
    }

    private fun isPptx(mime: String, ext: String): Boolean =
        ext == "pptx" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private fun isDocx(mime: String, ext: String): Boolean =
        ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun isLegacyOffice(mime: String, ext: String): Boolean =
        ext == "ppt" || ext == "doc" || ext == "xls" ||
            mime == "application/vnd.ms-powerpoint" ||
            mime == "application/msword" ||
            mime == "application/vnd.ms-excel"

    private fun isImageOnly(mime: String, ext: String): Boolean =
        mime.startsWith("image/") || ext in IMAGE_EXTENSIONS

    // ---------- readers ----------

    private fun readPlainText(resolver: ContentResolver, uri: Uri): String {
        return resolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not open file")
    }

    private fun extractPdf(resolver: ContentResolver, uri: Uri): String {
        return resolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    paragraphStart = " "
                    lineSeparator  = " "
                }
                stripper.getText(doc)
            }
        } ?: error("Could not open PDF")
    }

    private enum class OoxmlKind {
        Pptx,           // PowerPoint XML — slides under ppt/slides/slideN.xml
        Docx            // Word XML — single word/document.xml file
    }

    // Pull text out of an OOXML zip without bringing in Apache POI (which
    // would balloon the APK by ~12 MB). Both .pptx and .docx are zips of
    // XML; visible text in PPTX is wrapped in `a:t` elements, and in
    // DOCX in `w:t` elements. We scrape both via extractTextElements.
    private fun extractOoxml(resolver: ContentResolver, uri: Uri, kind: OoxmlKind): String {
        val sb = StringBuilder()
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val match = when (kind) {
                        OoxmlKind.Pptx -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") &&
                            !name.contains("_rels")
                        OoxmlKind.Docx -> name == "word/document.xml"
                    }
                    if (!match) {
                        zip.closeEntry()
                        continue
                    }
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    extractTextElements(xml, sb)
                    sb.append(' ')
                    zip.closeEntry()
                }
            }
        } ?: error("Could not open OOXML")
        return sb.toString()
    }

    // Naive but reliable: pull the contents of every namespace-prefixed
    // `t` element from the XML. OOXML uses `a:t` for PPTX and `w:t` for
    // DOCX; both share the suffix `:t` so a single regex over any
    // ASCII-letter prefix covers both. XML entities are decoded so that
    // `&amp;` etc. becomes the user's real character.
    private fun extractTextElements(xml: String, out: StringBuilder) {
        val tagRegex = Regex("<[a-zA-Z]+:t(?:\\s[^>]*)?>([\\s\\S]*?)</[a-zA-Z]+:t>")
        for (m in tagRegex.findAll(xml)) {
            val raw = m.groupValues[1]
            if (raw.isNotEmpty()) {
                out.append(decodeXmlEntities(raw)).append(' ')
            }
        }
    }

    private fun decodeXmlEntities(raw: String): String =
        raw.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // MIME types the OS file picker can present for a plain text picker.
    val ACCEPTED_MIMES: Array<String> = arrayOf(
        "text/*",
        "application/pdf",
        "application/json",
        "application/xml",
        "application/rtf",
        "application/x-yaml",
        "application/x-tex",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/octet-stream"
    )

    private val TEXT_MIMES = setOf(
        "application/json",
        "application/xml",
        "application/rtf",
        "application/x-yaml",
        "application/x-tex",
        "application/octet-stream"
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "text", "md", "markdown", "csv", "tsv", "log", "json", "xml",
        "rtf", "yml", "yaml", "tex", "rst", "ini", "conf", "properties"
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tiff", "tif"
    )
}
