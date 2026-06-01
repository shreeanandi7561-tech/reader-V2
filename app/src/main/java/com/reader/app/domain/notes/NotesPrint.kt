package com.reader.app.domain.notes

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView

/**
 * "Save as PDF" via Android's platform `PrintManager` + the WebView's
 * `createPrintDocumentAdapter`.
 *
 * Why this path:
 *   - It's the same pipeline Chrome / Files / etc. use to print to PDF
 *     on Android. The OS handles page breaks, headers, margins, A4
 *     sizing — none of which we'd nail in app code.
 *   - The user gets a system-level chooser ("Save as PDF" target,
 *     "Print to printer" target, OEM printers) — meets the spec's
 *     "Save PDF / Download PDF" requirement without us shipping a
 *     toolbar.
 *   - Works offline. No external library. Already in the SDK.
 *
 * The WebView passed in MUST already have the HTML loaded and laid out
 * (call this from the NotesScreen *after* `onPageFinished` has fired).
 * Calling on an empty / partially-rendered WebView produces a blank PDF.
 */
object NotesPrint {

    /**
     * Kick off the system print/save-as-PDF dialog for [webView].
     *
     * [jobName] becomes the default filename in the OS dialog (system
     * appends ".pdf" automatically). Keep it short and filesystem-safe
     * — the helper sanitises spaces but not exotic characters.
     */
    fun printToPdf(context: Context, webView: WebView, jobName: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val safeName = sanitise(jobName).ifBlank { "Notes" }

        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val adapter = webView.createPrintDocumentAdapter(safeName)
        pm.print(safeName, adapter, attrs)
    }

    private fun sanitise(s: String): String {
        // Drop newlines + control chars, collapse whitespace.
        return s.replace(Regex("[\\p{Cntrl}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
