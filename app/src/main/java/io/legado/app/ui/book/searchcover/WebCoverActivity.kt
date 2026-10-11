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
        val url = when (engine) {
            // 百度用移动版图片搜索（PC版在手机上不跳转、搜索框不回显、布局不适配）
            "baidu" -> "https://m.baidu.com/sf/vsearch?pd=image&word=${enc(keyword)}"
            "bing" -> "https://cn.bing.com/images/search?q=${enc(keyword)}"
            "sogou" -> "https://pic.sogou.com/pics?query=${enc(keyword)}"
            "360" -> "https://image.so.com/i?q=${enc(keyword)}"
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
