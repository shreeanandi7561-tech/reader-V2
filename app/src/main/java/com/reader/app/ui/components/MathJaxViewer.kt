package com.reader.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathJaxViewer(
    markdown: String,
    modifier: Modifier = Modifier,
    textColorHex: String = "#1C1B1F",
    textSizePx: Int = 17
) {
    var webViewHeight by remember { mutableStateOf(40) } // safe initial minimum height in dp
    val density = LocalDensity.current

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeight.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            "javascript:if(window.Android){window.Android.resize(document.documentElement.scrollHeight || document.body.scrollHeight);}",
                            null
                        )
                    }
                }
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun resize(height: Int) {
                        post {
                            if (height > 0) {
                                webViewHeight = height
                            }
                        }
                    }
                }, "Android")
            }
        },
        update = { webView ->
            val cacheKey = "$markdown|$textColorHex|$textSizePx"
            if (webView.tag != cacheKey) {
                webView.tag = cacheKey
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
                                        setTimeout(reportHeight, 200);
                                    }
                                }
                            };
                        </script>
                        <script id="MathJax-script" async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js" onerror="this.onerror=null; var script=document.createElement('script'); script.src='https://cdnjs.cloudflare.com/ajax/libs/mathjax/3.2.2/es5/tex-mml-chtml.min.js'; document.head.appendChild(script);"></script>
                        <style>
                            body { 
                                font-family: system-ui, -apple-system, sans-serif, 'Noto Sans Devanagari'; 
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
                            function reportHeight() {
                                if (window.Android && window.Android.resize) {
                                    const h = document.documentElement.scrollHeight || document.body.scrollHeight;
                                    window.Android.resize(h);
                                }
                            }

                            function b64DecodeUnicode(str) {
                                return decodeURIComponent(atob(str).split('').map(function(c) {
                                    return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
                                }).join(''));
                            }
                            try {
                                const md = b64DecodeUnicode("$base64Md");
                                
                                // Protect math equations from marked parser eating backslashes
                                const mathBlocks = [];
                                let placeholderCounter = 0;
                                let processedMd = md;

                                // 1. Double dollar display math: $$...$$
                                processedMd = processedMd.replace(/\$\$([\s\S]*?)\$\$/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERDP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 2. Single dollar inline math: $...$
                                processedMd = processedMd.replace(/\$([^\$\n]+?)\$/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERIP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 3. LaTeX display math: \[...\] or \\[...\\]
                                processedMd = processedMd.replace(/\\\\?\[([\s\S]*?)\\\\?\]/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERLDP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // 4. LaTeX inline math: \(...\) or \\(\\...)
                                processedMd = processedMd.replace(/\\\\?\(([\s\S]*?)\\\\?\)/g, function(match) {
                                    const placeholder = 'MATHPLACEHOLDERLIP' + (placeholderCounter++);
                                    mathBlocks.push({ placeholder: placeholder, content: match });
                                    return placeholder;
                                });

                                // Parse with marked.js
                                let parsedHtml = marked.parse(processedMd);

                                // Restore all math equations untouched
                                for (let i = 0; i < mathBlocks.length; i++) {
                                    parsedHtml = parsedHtml.replace(new RegExp(mathBlocks[i].placeholder, 'gi'), mathBlocks[i].content);
                                }

                                // Clean up any lingering unreplaced placeholder strings defensively
                                parsedHtml = parsedHtml.replace(/mathplaceholder[a-z0-9_]+/gi, '');

                                document.getElementById('content').innerHTML = parsedHtml;

                                // Explicitly trigger MathJax typesetting if available
                                if (window.MathJax && MathJax.typesetPromise) {
                                    MathJax.typesetPromise().then(function() {
                                        setTimeout(reportHeight, 50);
                                    });
                                }
                                reportHeight();

                                // Setup ResizeObserver to catch any size changes dynamically (such as images, MathJax render settle)
                                if (window.ResizeObserver) {
                                    const observer = new ResizeObserver(function() {
                                        reportHeight();
                                    });
                                    observer.observe(document.documentElement);
                                    observer.observe(document.body);
                                }
                            } catch(e) {
                                document.getElementById('content').innerHTML = "Error parsing content: " + e.message;
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
            }
        }
    )
}
