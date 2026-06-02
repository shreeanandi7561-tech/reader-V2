package com.reader.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathJaxViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    textColorHex: String = "#1C1B1F",
    textSizePx: Int = 17
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            val encodedBytes = markdown.toByteArray(Charsets.UTF_8)
            val base64Md = Base64.encodeToString(encodedBytes, Base64.NO_WRAP)
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <script>
                        window.MathJax = {
                            tex: { 
                                inlineMath: [['$', '$'], ['\\(', '\\)']],
                                displayMath: [['$$', '$$'], ['\\[', '\\]']]
                            },
                            svg: { fontCache: 'global' },
                            startup: {
                                ready: function() {
                                    MathJax.startup.defaultReady();
                                }
                            }
                        };
                    </script>
                    <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                    <style>
                        body { 
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; 
                            font-size: ${textSizePx}px; 
                            color: ${textColorHex}; 
                            padding: 0; 
                            margin: 0; 
                            background: transparent;
                            word-wrap: break-word;
                            line-height: 1.6;
                        }
                        p:first-child { margin-top: 0; }
                        p:last-child { margin-bottom: 0; }
                    </style>
                </head>
                <body>
                    <div id="content"></div>
                    <script>
                        function b64DecodeUnicode(str) {
                            return decodeURIComponent(atob(str).split('').map(function(c) {
                                return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
                            }).join(''));
                        }
                        try {
                            const md = b64DecodeUnicode("$base64Md");
                            document.getElementById('content').innerHTML = marked.parse(md);
                        } catch(e) {
                            document.getElementById('content').innerHTML = "Error parsing content.";
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
        }
    )
}
