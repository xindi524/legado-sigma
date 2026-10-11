package io.legado.app.ui.book.searchcover

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.addCallback
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityWebCoverBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.net.URLEncoder

/**
 * F4：网页搜封面——打开搜索引擎图片页，长按图片设为封面
 */
class WebCoverActivity : BaseActivity<ActivityWebCoverBinding>() {

    override val binding by viewBinding(ActivityWebCoverBinding::inflate)

    private var engine = "baidu"
    private var keyword = ""

    private val engineNames = mapOf(
        "baidu" to "百度图片",
        "bing" to "必应图片",
        "sogou" to "搜狗图片",
        "google" to "谷歌图片"
    )

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        keyword = intent.getStringExtra("keyword") ?: ""
        initView()
        loadEngine()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.inflateMenu(R.menu.web_cover)
        toolBar.menu.applyTint(this@WebCoverActivity)
        toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_search_keyword -> showKeywordDialog()
                R.id.menu_engine_baidu -> { engine = "baidu"; loadEngine() }
                R.id.menu_engine_bing -> { engine = "bing"; loadEngine() }
                R.id.menu_engine_sogou -> { engine = "sogou"; loadEngine() }
                R.id.menu_engine_google -> { engine = "google"; loadEngine() }
            }
            true
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                refreshProgressBar.isAutoLoading = newProgress < 100
            }
        }
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val url = result.extra
            if ((result.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE
                        || result.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                && !url.isNullOrBlank() && url.startsWith("http")
            ) {
                showSetCoverDialog(url)
                true
            } else {
                false
            }
        }
        onBackPressedDispatcher.addCallback(this@WebCoverActivity) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
        upTitle()
    }

    private fun upTitle() {
        binding.toolBar.title = "搜索封面 · ${engineNames[engine] ?: engine}"
    }

    private fun loadEngine() {
        upTitle()
        val url = when (engine) {
            "baidu" -> "https://image.baidu.com/s?wd=${enc(keyword)}"
            "bing" -> "https://cn.bing.com/images/search?q=${enc(keyword)}"
            "sogou" -> "https://pic.sogou.com/pics?query=${enc(keyword)}"
            "google" -> "https://www.google.com/search?q=${enc(keyword)}&udm=2"
            else -> return
        }
        binding.webView.loadUrl(url)
    }

    private fun showSetCoverDialog(imageUrl: String) {
        alert("设为封面") {
            setMessage("长按选中的图片：\n$imageUrl")
            positiveButton("设为封面") {
                setResult(RESULT_OK, Intent().putExtra("cover", imageUrl))
                finish()
            }
            negativeButton("取消")
        }
    }

    private fun showKeywordDialog() {
        val editText = EditText(this).apply {
            setText(keyword)
            setSingleLine()
            setPadding(48, 32, 48, 0)
        }
        alert("搜索关键词") {
            setCustomView(editText)
            positiveButton("搜索") {
                val kw = editText.text.toString().trim()
                if (kw.isNotEmpty()) {
                    keyword = kw
                    loadEngine()
                }
            }
            negativeButton("取消")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

}
