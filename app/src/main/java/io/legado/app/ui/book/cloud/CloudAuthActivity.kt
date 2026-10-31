package io.legado.app.ui.book.cloud

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCloudAuthBinding
import io.legado.app.help.cloud.BaiduPanHelp
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            try {
                // 拉取百度昵称与 uk 入库为独立账号并激活
                val (name, uk) = BaiduPanHelp.uinfoWithToken(token.first)
                BaiduPanHelp.addAccount(
                    name, token.first,
                    System.currentTimeMillis() + (token.second - 86400) * 1000, uk
                )
            } catch (e: Exception) {
                BaiduPanHelp.addAccount(
                    "账号${System.currentTimeMillis() % 1000}", token.first,
                    System.currentTimeMillis() + (token.second - 86400) * 1000,
                    "u${System.currentTimeMillis()}"
                )
            }
            setResult(RESULT_OK)
            finish()
        }
    }

}
