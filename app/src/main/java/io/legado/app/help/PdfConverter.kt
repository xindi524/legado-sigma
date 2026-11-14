package io.legado.app.help

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.localBook.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * F7：PDF 转 TXT（pdf.js / WebView 方案）
 * 无 native 依赖、无许可风险。PDF 复制到缓存后由 WebView 内的 pdf.js 逐页提取文字，
 * 页与页之间以空行分隔，结果保存到书籍保存位置并返回 TXT 的 Uri。
 * 扫描版（无文字层）PDF 提取结果为空，会抛出明确错误提示。
 */
object PdfConverter {

    private const val TIMEOUT_MS = 180_000L

    private class Bridge(
        private val onInfoCb: (Int) -> Unit,
        private val onPageCb: (Int, Int, String) -> Unit,
        private val onDoneCb: (String?) -> Unit
    ) {
        @JavascriptInterface
        fun onInfo(total: Int) = onInfoCb(total)

        @JavascriptInterface
        fun onPage(page: Int, total: Int, text: String) = onPageCb(page, total, text)

        @JavascriptInterface
        fun onDone(error: String?) = onDoneCb(error)
    }

    /**
     * 将 PDF 转为 TXT 并保存到书籍保存位置，返回 TXT 的 Uri。
     * fileName 为原始 PDF 文件名（含 .pdf 后缀），输出同名 .txt。
     */
    suspend fun convertToTxt(pdfUri: Uri, fileName: String): Uri {
        val text = extractText(pdfUri)
        if (text.trim().length < 20) {
            throw NoStackTraceException("该 PDF 没有可提取的文字内容（可能为扫描版/图片版），暂不支持转换")
        }
        val txtName = fileName.removeSuffix(".pdf").removeSuffix(".PDF") + ".txt"
        return LocalBook.saveBookFile(text.byteInputStream(), txtName)
    }

    /**
     * 提取 PDF 全部文字，页与页之间以空行分隔
     */
    suspend fun extractText(pdfUri: Uri): String = withTimeout(TIMEOUT_MS) {
        // 1) PDF 复制到缓存，供 WebView 内 pdf.js 读取
        val tmpPdf = withContext(Dispatchers.IO) {
            val f = File(appCtx.cacheDir, "pdf2txt_tmp.pdf")
            appCtx.contentResolver.openInputStream(pdfUri)!!.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        }
        try {
            // 2) WebView（主线程创建）加载提取页
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val sb = StringBuilder()
                    var webViewRef: WebView? = null
                    val bridge = Bridge(
                        onInfoCb = { },
                        onPageCb = { _, _, text -> sb.append(text).append("\n\n") },
                        onDoneCb = { error ->
                            mainHandler.post {
                                webViewRef?.destroy()
                                if (cont.isActive) {
                                    if (error.isNullOrBlank()) cont.resume(sb.toString())
                                    else cont.resumeWithException(
                                        NoStackTraceException("PDF 转换失败：$error")
                                    )
                                }
                            }
                        }
                    )
                    val webView = createWebView(bridge)
                    webViewRef = webView
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // base64 分块注入，绕开 WebView 对 file:// 的读取限制
                            val bytes = tmpPdf.readBytes()
                            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            view.evaluateJavascript("window.PDF_B64_CHUNKS = []", null)
                            var idx = 0
                            while (idx < b64.length) {
                                val end = minOf(idx + 400_000, b64.length)
                                val chunk = b64.substring(idx, end)
                                view.evaluateJavascript(
                                    "window.PDF_B64_CHUNKS.push('$chunk')", null
                                )
                                idx = end
                            }
                            view.evaluateJavascript("startExtractFromChunks()", null)
                        }
                    }
                    webView.loadUrl("file:///android_asset/pdf2txt/index.html")
                    cont.invokeOnCancellation {
                        mainHandler.post { webViewRef?.destroy() }
                    }
                }
            }
        } finally {
            tmpPdf.delete()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(bridge: Bridge): WebView {
        val webView = WebView(appCtx)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        return webView
    }
}
