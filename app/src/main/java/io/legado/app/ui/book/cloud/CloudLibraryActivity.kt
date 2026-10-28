package io.legado.app.ui.book.cloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCloudLibraryBinding
import io.legado.app.help.cloud.BaiduPanHelp
import io.legado.app.help.cloud.BaiduPanHelp.CloudFile
import io.legado.app.help.cloud.CloudDownloadManager
import io.legado.app.help.cloud.CloudIndexManager
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.applyTint
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * F5：云端书库——百度网盘目录浏览、下载并导入书架
 */
class CloudLibraryActivity : BaseActivity<ActivityCloudLibraryBinding>(),
    Toolbar.OnMenuItemClickListener,
    CloudFileAdapter.CallBack {

    override val binding by viewBinding(ActivityCloudLibraryBinding::inflate)

    private val uiPrefs by lazy { getSharedPreferences("cloud_pan_ui", 0) }
    private var currentDir: String = "/"
    private val adapter by lazy { CloudFileAdapter(this, this) }
    private var pendingFile: CloudFile? = null

    /**
     * 选择书籍保存位置（仿导入本地书籍的授权设计）
     */
    private val bookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
            pendingFile?.let { f ->
                pendingFile = null
                onFileClick(f)
            }
        } ?: run {
            pendingFile = null
        }
    }
    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (BaiduPanHelp.hasToken()) {
                upAuthState()
                refresh()
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.title = "云端书库 · 百度网盘"
        binding.toolBar.inflateMenu(R.menu.cloud_library)
        binding.toolBar.menu.applyTint(this)
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.toolBar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.tvBreadcrumb.setOnClickListener { upToParent() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        binding.btnSearch.setOnClickListener { doSearch() }
        binding.btnSearch.setOnLongClickListener {
            doSearch(allDrives = true)
            true
        }
        binding.btnDownloadToggle.setOnClickListener {
            val t = CloudDownloadManager.task ?: return@setOnClickListener
            when (t.state) {
                CloudDownloadManager.State.RUNNING -> CloudDownloadManager.pause()
                CloudDownloadManager.State.PAUSED -> CloudDownloadManager.resume()
                else -> CloudDownloadManager.clear()
            }
        }
        CloudDownloadManager.onUpdate = { showDownloadState() }
        CloudIndexManager.ensureFresh()
        binding.btnAuth.setOnClickListener {
            authLauncher.launch(Intent(this, CloudAuthActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this) {
            if (currentDir != "/") {
                upToParent()
            } else {
                finish()
            }
        }
        currentDir = uiPrefs.getString("last_dir", "/") ?: "/"
        upAuthState()
        showDownloadState()
        if (BaiduPanHelp.hasToken()) {
            refresh()
        }
    }

    override fun onDestroy() {
        CloudDownloadManager.onUpdate = null
        super.onDestroy()
    }

    /**
     * 搜索链路（速度优先）：①索引内存过滤（<10ms，最常见路径）
     * ②服务端语义搜索（索引缺失/过期时，Readingo 同款）
     * ③本地递归兜底。全部结果按匹配度由高到低排序
     * 精确=子序列（可缺字漏字有间隔，不能错字乱序）；模糊=≥50%字符命中
     */
    private fun doSearch(allDrives: Boolean = false) {
        if (!BaiduPanHelp.hasToken()) return
        val kw = binding.etSearch.text?.toString()?.trim() ?: ""
        binding.refreshProgressBar.isAutoLoading = true
        lifecycleScope.launch {
            try {
                if (kw.isEmpty()) {
                    val list = BaiduPanHelp.list(currentDir)
                    adapter.setItems(list)
                    binding.tvBreadcrumb.text = currentDir
                    return@launch
                }
                val fuzzy = binding.switchFuzzy.isChecked
                val lowerKw = kw.lowercase()
                var results: List<CloudFile>? = null
                var scopeText = ""

                // ①索引内存过滤（最瞬时；长按强制跳过走语义）
                if (!allDrives && CloudIndexManager.index != null) {
                    results = CloudIndexManager.search(lowerKw, fuzzy).map { it.toCloudFile() }
                    scopeText = if (CloudIndexManager.isFresh()) "全盘（索引）" else "全盘（索引·稍旧）"
                    CloudIndexManager.ensureFresh()
                }

                // ①.5 索引无结果时语义搜索补位（索引可能缺新文件或超上限）
                if (results != null && results.isEmpty() && !allDrives) {
                    try {
                        val semantic = BaiduPanHelp.unisearch(kw)
                        val filtered = if (fuzzy) semantic else semantic.filter {
                            BaiduPanHelp.subsequenceMatch(it.name.lowercase(), lowerKw)
                        }
                        if (filtered.isNotEmpty()) {
                            results = filtered
                            scopeText = "语义搜索补位"
                        }
                    } catch (e: Exception) {
                        // 语义搜索不可用
                    }
                }

                // ②服务端语义搜索（索引完全缺失时；精确模式按子序列规则精筛）
                if (results == null && !allDrives) {
                    try {
                        val semantic = BaiduPanHelp.unisearch(kw)
                        val filtered = if (fuzzy) semantic else semantic.filter {
                            BaiduPanHelp.subsequenceMatch(it.name.lowercase(), lowerKw)
                        }
                        if (filtered.isNotEmpty()) {
                            results = filtered
                            scopeText = if (fuzzy) "语义搜索" else "语义搜索·精筛"
                        }
                    } catch (e: Exception) {
                        // 语义搜索不可用，走本地递归
                    }
                }

                // ③本地递归兜底
                if (results == null) {
                    val start = if (allDrives) "/" else currentDir
                    results = BaiduPanHelp.searchAll(start, lowerKw, fuzzy) { scanned, _ ->
                        binding.tvBreadcrumb.text = "搜索中…已扫描 $scanned 个文件夹"
                    }
                    scopeText = if (allDrives) "全盘" else "当前目录（含子文件夹）"
                    if (allDrives) {
                        val all = BaiduPanHelp.collectAll("/")
                        CloudIndexManager.saveFrom(all)
                    }
                    CloudIndexManager.ensureFresh()
                }

                // 统一按匹配度由高到低排序
                results = results.sortedByDescending {
                    BaiduPanHelp.matchScore(it.name.lowercase(), lowerKw, fuzzy)
                }
                adapter.setItems(results)
                val mode = if (fuzzy) "模糊" else ""
                binding.tvBreadcrumb.text =
                    "${scopeText}${mode}搜索「$kw」：${results.size} 个结果"
            } catch (e: Exception) {
                toastOnUi(e.message ?: "搜索失败")
            } finally {
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

    override fun onFileClick(file: CloudFile) {
        if (file.isDir) {
            binding.etSearch.setText("")
            currentDir = file.path
            refresh()
            return
        }
        if (!file.name.endsWith(".txt", true) && !file.name.endsWith(".epub", true)) {
            toastOnUi("仅支持导入 TXT / EPUB 文件")
            return
        }
        // 未设置书籍保存位置：仿导入本地书籍的设计弹窗引导授权
        if (AppConfig.defaultBookTreeUri.isNullOrBlank()) {
            pendingFile = file
            alertSelectBookFolder()
            return
        }
        // 书籍保存位置已有同名同大小文件：跳过下载直接导入
        val existing = findInBookStore(file.name, file.size)
        if (existing != null) {
            alert("导入已有文件") {
                setMessage("「${file.name}」已在书籍保存位置，直接加入书架？")
                positiveButton("导入") {
                    importExisting(existing)
                }
                negativeButton("取消")
            }
            return
        }
        if (CloudDownloadManager.task?.state == CloudDownloadManager.State.RUNNING) {
            toastOnUi("已有文件在下载中，请先暂停或等待完成")
            return
        }
        alert("下载并导入") {
            setMessage(
                "「${file.name}」（${formatSize(file.size)}）\n\n" +
                    "将保存到「书籍保存位置」设定的文件夹（设置→其他可修改），网盘原件不受影响。"
            )
            positiveButton("下载") {
                CloudDownloadManager.start(file)
            }
            negativeButton("取消")
        }
    }

    private fun upAuthState() {
        val hasToken = BaiduPanHelp.hasToken()
        binding.authLayout.visibility = if (hasToken) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasToken) View.VISIBLE else View.GONE
        binding.tvBreadcrumb.visibility = if (hasToken) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        if (!BaiduPanHelp.hasToken()) return
        binding.refreshProgressBar.isAutoLoading = true
        lifecycleScope.launch {
            try {
                val list = BaiduPanHelp.list(currentDir)
                adapter.setItems(list)
                binding.tvBreadcrumb.text = currentDir
                uiPrefs.edit().putString("last_dir", currentDir).apply()
            } catch (e: Exception) {
                toastOnUi(e.message ?: "加载失败")
            } finally {
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

    private fun upToParent() {
        if (currentDir == "/") return
        currentDir = currentDir.substringBeforeLast('/').ifEmpty { "/" }
        refresh()
    }

    /**
     * 引导设置书籍保存位置（文案与流程仿导入本地书籍）
     */
    private fun alertSelectBookFolder() {
        try {
            val storageHelp = String(assets.open("storageHelp.md").readBytes())
            val hint = getString(R.string.select_book_folder)
            alert(hint, storageHelp) {
                okButton {
                    bookTreeSelect.launch { title = hint }
                }
                negativeButton("取消")
            }
        } catch (e: Exception) {
            toastOnUi("请先在 设置→其他→书籍保存位置 中设置文件夹")
        }
    }

    private fun importExisting(uri: Uri) {
        lifecycleScope.launch {
            try {
                val book = withContext(Dispatchers.IO) {
                    LocalBook.importFile(uri)
                }
                toastOnUi("《${book.name}》已加入书架")
            } catch (e: Exception) {
                toastOnUi("导入失败：${e.message}")
            }
        }
    }

    /**
     * 显示下载任务状态（任务由 CloudDownloadManager 后台执行，不随页面销毁）
     */
    private fun showDownloadState() {
        val t = CloudDownloadManager.task
        if (t == null) {
            binding.downloadBar.visibility = View.GONE
            return
        }
        binding.downloadBar.visibility = View.VISIBLE
        val pct = if (t.file.size > 0) {
            (t.downloaded * 100 / t.file.size).toInt().coerceIn(0, 100)
        } else 0
        binding.tvDownloadText.text = when (t.state) {
            CloudDownloadManager.State.RUNNING -> "下载中「${t.file.name}」$pct%"
            CloudDownloadManager.State.PAUSED -> "已暂停「${t.file.name}」$pct%"
            CloudDownloadManager.State.DONE -> "已完成「${t.file.name}」"
            CloudDownloadManager.State.FAILED -> "下载失败「${t.file.name}」"
        }
        binding.btnDownloadToggle.text = when (t.state) {
            CloudDownloadManager.State.RUNNING -> "暂停"
            CloudDownloadManager.State.PAUSED -> "继续"
            else -> "清除"
        }
        if (t.state == CloudDownloadManager.State.FAILED && t.needReselect) {
            t.needReselect = false
            pendingFile = t.file
            alertSelectBookFolder()
        }
    }

    /**
     * 在用户授权的书籍保存位置中查找同名且同大小的文件
     */
    private fun findInBookStore(name: String, expectedSize: Long): Uri? = try {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (treeUriStr.isNullOrBlank()) {
            null
        } else {
            DocumentFile.fromTreeUri(this, treeUriStr.toUri())?.findFile(name)
                ?.takeIf { it.length() == expectedSize }?.uri
        }
    } catch (e: Exception) {
        null
    }

    private fun formatSize(size: Long): String = when {
        size >= 1024 * 1024 -> String.format("%.1f MB", size / 1048576f)
        size >= 1024 -> String.format("%.0f KB", size / 1024f)
        else -> "$size B"
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_refresh -> refresh()
            R.id.menu_logout -> {
                BaiduPanHelp.clearToken()
                upAuthState()
                adapter.setItems(emptyList())
            }
        }
        return false
    }

}
