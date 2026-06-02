package com.reader.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathJaxViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    textColorHex: String = "#1C1B1F",
    textSizePx: Int = 17
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient = WebViewClient()
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
                            svg: {
                                fontCache: 'global'
                            },
                            startup: {
                                ready: function() {
                                    MathJax.startup.defaultReady();
                                    window.isMathJaxReady = true;
                                    if (window.pendingUpdate) {
                                        updateContent(window.pendingUpdate);
                                        window.pendingUpdate = null;
                                    }
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
                        /* Hide marked.js paragraph margin for tighter spacing matching Compose */
                        p:first-child { margin-top: 0; }
                        p:last-child { margin-bottom: 0; }
                    </style>
                </head>
                <body>
                    <div id="content"></div>
                    <script>
                        function updateContent(md) {
                            if (!window.isMathJaxReady) {
                                window.pendingUpdate = md;
                                return;
                            }
                            document.getElementById('content').innerHTML = marked.parse(md);
                            MathJax.typesetPromise();
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()
            loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
        }
    }

    LaunchedEffect(markdown) {
        val safeMd = markdown
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        webView.evaluateJavascript("updateContent(\"$safeMd\");", null)
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxWidth()
    )
}
