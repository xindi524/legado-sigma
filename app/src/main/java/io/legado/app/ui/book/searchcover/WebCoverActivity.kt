package io.legado.app.ui.book.searchcover

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
 * F4：网络搜索封面——打开搜索引擎图片页浏览，长按图片设为封面
 */
class WebCoverActivity : BaseActivity<ActivityWebCoverBinding>() {

    override val binding by viewBinding(ActivityWebCoverBinding::inflate)

    private var engine = "baidu"
    private var keyword = ""
    // 百度结果页需要会话Cookie，新WebView直接访问会被踢回首页；先访问一次首页预热
    private var baiduWarmed = false
    // 仅在预热导航后自动跳搜索结果页，避免干扰用户后续浏览
    private var pendingBaiduAutoSearch = false

    private val engines = linkedMapOf(
        "baidu" to "百度",
        "bing" to "Bing",
        "sogou" to "搜狗",
        "360" to "360",
        "google" to "Google"
    )
    private val engineViews = hashMapOf<String, TextView>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        keyword = intent.getStringExtra("keyword") ?: ""
        initView()
        buildEngineBar()
        loadEngine()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.title = "网络搜索"
        toolBar.inflateMenu(R.menu.web_cover)
        toolBar.menu.applyTint(this@WebCoverActivity)
        toolBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_search_keyword -> showKeywordDialog()
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

            override fun onPageFinished(view: WebView, url: String) {
                if (engine != "baidu") return
                if (pendingBaiduAutoSearch && !url.contains("vsearch")) {
                    // 预热首页加载完：填入关键词并自动跳搜索结果页
                    fillSearchBox(view)
                    view.postDelayed({
                        pendingBaiduAutoSearch = false
                        view.loadUrl(baiduSearchUrl())
                    }, 600)
                } else {
                    // 结果页或用户浏览的其他页：若搜索框未回显词则填入
                    fillSearchBox(view)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                refreshProgressBar.isAutoLoading = newProgress < 100
            }
        }
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val url = result.extra
            if ((result.type == WebView.HitTestResult.IMAGE_TYPE
                        || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
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
    }

    /**
     * 顶部一长条引擎切换栏（仿阅读App：百度/Bing/搜狗/360/Google 点击即切）
     */
    private fun buildEngineBar() {
        binding.engineContainer.removeAllViews()
        engineViews.clear()
        for ((key, label) in engines) {
            val tv = TextView(this).apply {
                text = label
                textSize = 15f
                setPadding(28, 0, 28, 0)
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (engine != key) {
                        engine = key
                        loadEngine()
                    }
                }
            }
            engineViews[key] = tv
            binding.engineContainer.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        upEngineBarState()
    }

    private fun upEngineBarState() {
        for ((key, tv) in engineViews) {
            if (key == engine) {
                tv.setTextColor(Color.WHITE)
                tv.paint.isFakeBoldText = true
            } else {
                tv.setTextColor(Color.parseColor("#999999"))
                tv.paint.isFakeBoldText = false
            }
        }
    }

    private fun loadEngine() {
        upEngineBarState()
        when (engine) {
            "baidu" -> {
                if (!baiduWarmed) {
                    // 先访问百度移动版首页建立会话Cookie，否则直接进结果页会被302踢回首页
                    baiduWarmed = true
                    pendingBaiduAutoSearch = true
                    binding.webView.loadUrl("https://m.baidu.com/")
                } else {
                    binding.webView.loadUrl(baiduSearchUrl())
                }
            }
            "bing" -> binding.webView.loadUrl("https://cn.bing.com/images/search?q=${enc(keyword)}")
            "sogou" -> binding.webView.loadUrl("https://pic.sogou.com/pics?query=${enc(keyword)}")
            "360" -> binding.webView.loadUrl("https://image.so.com/i?q=${enc(keyword)}")
            "google" -> binding.webView.loadUrl("https://www.google.com/search?q=${enc(keyword)}&udm=2")
        }
    }

    private fun baiduSearchUrl(): String =
        "https://m.baidu.com/sf/vsearch?pd=image&word=${enc(keyword)}"

    /**
     * 百度移动版搜索框不回显URL词，页面加载完JS注入填入（含延迟重试，防SPA未渲染完）
     */
    private fun fillSearchBox(view: WebView) {
        if (engine != "baidu" || keyword.isBlank()) return
        val jsKw = keyword.replace("\\", "\\\\").replace("'", "\\'")
        val js = "(function(){var ks=document.querySelectorAll(" +
                "'#index-kw,#kw,input[name=word],input[name=word1]');" +
                "for(var i=0;i<ks.length;i++){if(!ks[i].value){ks[i].value='$jsKw';}}})()"
        view.evaluateJavascript(js, null)
        view.postDelayed({ view.evaluateJavascript(js, null) }, 800)
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
