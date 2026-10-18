package io.legado.app.ui.book.cloud

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCloudAuthBinding
import io.legado.app.help.cloud.BaiduPanHelp
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * F5：百度网盘 OAuth 授权（implicit 流），拦截 login_success 地址提取 accesstoken
 */
class CloudAuthActivity : BaseActivity<ActivityCloudAuthBinding>() {

    override val binding by viewBinding(ActivityCloudAuthBinding::inflate)

    private var authorized = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = "百度网盘授权"
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 标准 Chrome 移动版 UA，避免百度给 WebView 渲染极简页
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                checkLoginSuccess(request.url.toString())
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                checkLoginSuccess(url)
                // 调试：当前地址自动复制到剪贴板，粘贴发回即可定位问题
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                binding.toolBar.title = "当前地址已复制，请粘贴反馈"
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.refreshProgressBar.isAutoLoading = newProgress < 100
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }
        binding.webView.loadUrl(BaiduPanHelp.AUTH_URL)
    }

    private fun checkLoginSuccess(url: String) {
        if (authorized) return
        val token = BaiduPanHelp.extractToken(url) ?: return
        authorized = true
        BaiduPanHelp.saveToken(token.first, token.second)
        setResult(RESULT_OK)
        finish()
    }

}
