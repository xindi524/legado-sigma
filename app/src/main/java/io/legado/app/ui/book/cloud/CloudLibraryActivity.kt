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
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
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
import java.io.File

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
                CloudIndexManager.setAccount(BaiduPanHelp.activeAccount()?.id ?: "default")
                upAuthState()
                updateAccountMenu()
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
        CloudDownloadManager.onUpdate = { showDownloadState() }
        CloudIndexManager.onUpdate = { showIndexHint() }
        CloudIndexManager.setAccount(BaiduPanHelp.activeAccount()?.id ?: "default")
        CloudIndexManager.ensureFresh()
        updateAccountMenu()
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
                    // 索引稍旧（可能没扫到最新文件）：合并语义搜索结果补充覆盖
                    if (!CloudIndexManager.isFresh()) {
                        try {
                            val semantic = BaiduPanHelp.unisearch(kw)
                            val have = results.map { it.fsId }.toHashSet()
                            val add = semantic.filter { s ->
                                s.fsId !in have && if (fuzzy) true
                                else BaiduPanHelp.subsequenceMatch(s.name.lowercase(), lowerKw)
                            }
                            if (add.isNotEmpty()) {
                                results = (results ?: emptyList()) + add
                                scopeText = "索引+语义"
                            }
                        } catch (e: Exception) {
                            // 语义补充失败不影响索引结果
                        }
                    }
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
            if (file.name == "← 返回上级") {
                upToParent()
                return
            }
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
                // 非根目录时首项显示返回上级
                val display = if (currentDir != "/") {
                    listOf(CloudFile(0, "", "← 返回上级", 0, true)) + list
                } else list
                adapter.setItems(display)
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

    /**
     * 索引状态提示：重建中显示进度，不影响浏览与搜索
     */
    private fun showIndexHint() {
        if (CloudIndexManager.isBuilding() &&
            binding.tvBreadcrumb.text.toString() == currentDir
        ) {
            binding.tvBreadcrumb.text = CloudIndexManager.progressText()
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
     * 显示下载任务状态：状态条已改为列表项内圆环，此处只刷新列表
     */
    private fun showDownloadState() {
        adapter.notifyDataSetChanged()
    }

    override fun currentTask(): CloudDownloadManager.Task? = CloudDownloadManager.task

    override fun toggleTask() {
        val t = CloudDownloadManager.task ?: return
        when (t.state) {
            CloudDownloadManager.State.RUNNING -> CloudDownloadManager.pause()
            CloudDownloadManager.State.PAUSED -> CloudDownloadManager.resume()
            else -> CloudDownloadManager.clear()
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
            R.id.menu_baidu_backup -> baiduBackup()
            R.id.menu_baidu_restore -> baiduRestore()
            R.id.menu_account -> showAccountSelector()
            R.id.menu_logout -> {
                BaiduPanHelp.clearToken()
                upAuthState()
                adapter.setItems(emptyList())
            }
        }
        return false
    }

    /**
     * 生成完整备份 ZIP 并上传到网盘 /apps/开源阅读D/backup/
     */
    private fun updateAccountMenu() {
        binding.toolBar.menu.findItem(R.id.menu_account)?.title =
            "当前账号：${BaiduPanHelp.activeAccount()?.name ?: "未授权"}"
    }

    /**
     * 账号管理：切换 / 添加 / 移除
     */
    private fun showAccountSelector() {
        val accs = BaiduPanHelp.accounts()
        val active = BaiduPanHelp.activeAccount()
        val items = ArrayList<CharSequence>()
        accs.forEach { items.add(if (it.id == active?.id) "● ${it.name}" else it.name) }
        val addIdx = accs.size
        items.add("＋ 添加新账号")
        val removeIdx = if (accs.size > 1 && active != null) {
            items.add("－ 移除当前账号"); accs.size + 1
        } else -1
        selector("百度网盘账号", items) { _, i ->
            when {
                i < accs.size -> {
                    if (accs[i].id == active?.id) return@selector
                    if (CloudDownloadManager.task?.state == CloudDownloadManager.State.RUNNING) {
                        toastOnUi("有文件正在下载，完成或暂停后再切换账号")
                        return@selector
                    }
                    BaiduPanHelp.setActive(accs[i].id)
                    CloudIndexManager.setAccount(accs[i].id)
                    updateAccountMenu()
                    refresh()
                    toastOnUi("已切换到：${accs[i].name}")
                }
                i == addIdx -> {
                    toastOnUi("正在打开授权页…")
                    try {
                        authLauncher.launch(Intent(this, CloudAuthActivity::class.java))
                    } catch (e: Exception) {
                        toastOnUi("打开授权页失败：${e.message}")
                    }
                }
                i == removeIdx -> {
                    if (CloudDownloadManager.task?.state == CloudDownloadManager.State.RUNNING) {
                        toastOnUi("有文件正在下载，完成或暂停后再移除账号")
                        return@selector
                    }
                    BaiduPanHelp.removeActive()
                    val na = BaiduPanHelp.activeAccount()
                    if (na != null) {
                        CloudIndexManager.setAccount(na.id)
                        updateAccountMenu()
                        refresh()
                        toastOnUi("已移除，切换到：${na.name}")
                    } else {
                        CloudIndexManager.setAccount("default")
                        upAuthState()
                        adapter.setItems(emptyList())
                        updateAccountMenu()
                    }
                }
            }
        }
    }

    private fun baiduBackup() {
        lifecycleScope.launch {
            try {
                binding.refreshProgressBar.isAutoLoading = true
                withContext(Dispatchers.IO) {
                    Backup.backupLocked(this@CloudLibraryActivity, null)
                }
                val dir = appCtx.getExternalFilesDir(null)!!
                val zip = dir.listFiles { f -> f.name.startsWith("backup") && f.name.endsWith(".zip") }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw Exception("未找到备份文件")
                val remotePath = "/apps/开源阅读D/backup/${zip.name}"
                withContext(Dispatchers.IO) {
                    BaiduPanHelp.uploadFile(zip, remotePath)
                }
                toastOnUi("已备份到网盘：${zip.name}")
            } catch (e: Exception) {
                toastOnUi("备份失败：${e.message}")
            } finally {
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

    /**
     * 从网盘 /apps/开源阅读D/backup/ 选择备份 ZIP 下载并恢复
     */
    private fun baiduRestore() {
        lifecycleScope.launch {
            try {
                binding.refreshProgressBar.isAutoLoading = true
                val files = withContext(Dispatchers.IO) {
                    BaiduPanHelp.list("/apps/开源阅读D/backup")
                }.filter { !it.isDir && it.name.endsWith(".zip") }
                    .sortedByDescending { it.name }
                binding.refreshProgressBar.isAutoLoading = false
                if (files.isEmpty()) {
                    toastOnUi("网盘中没有备份文件")
                    return@launch
                }
                selector("选择要恢复的备份", files.map { it.name as CharSequence }) { _, i ->
                    val sel = files[i]
                    lifecycleScope.launch {
                        try {
                            binding.refreshProgressBar.isAutoLoading = true
                            val tmp = File(appCtx.cacheDir, "restore_${sel.name}")
                            withContext(Dispatchers.IO) {
                                tmp.outputStream().use { out ->
                                    BaiduPanHelp.downloadTo(sel.fsId, out)
                                }
                            }
                            withContext(Dispatchers.IO) {
                                Restore.restore(this@CloudLibraryActivity, Uri.fromFile(tmp))
                            }
                            tmp.delete()
                            toastOnUi("已从网盘恢复：${sel.name}\n建议重启应用使配置完全生效")
                        } catch (e: Exception) {
                            toastOnUi("恢复失败：${e.message}")
                        } finally {
                            binding.refreshProgressBar.isAutoLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                toastOnUi(e.message ?: "获取备份列表失败")
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

}
