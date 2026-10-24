package io.legado.app.ui.book.cloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityCloudLibraryBinding
import io.legado.app.help.cloud.BaiduPanHelp
import io.legado.app.help.cloud.BaiduPanHelp.CloudFile
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.localBook.LocalBook
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
        if (BaiduPanHelp.hasToken()) {
            refresh()
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
     * 搜索：关键词为空恢复当前目录浏览；模糊=全盘递归搜，精确=当前目录内过滤
     */
    private fun doSearch() {
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
                if (binding.switchFuzzy.isChecked) {
                    val results = BaiduPanHelp.searchAll(kw) { scanned, found ->
                        binding.tvBreadcrumb.text = "全盘搜索中…已扫描 $scanned 个文件夹，命中 $found"
                    }
                    results.sortedWith(
                        compareByDescending<CloudFile> { it.isDir }.thenBy { it.name }
                    )
                    adapter.setItems(results)
                    binding.tvBreadcrumb.text = "全盘搜索「$kw」：${results.size} 个结果"
                } else {
                    val results = BaiduPanHelp.list(currentDir).filter {
                        it.name.contains(kw, ignoreCase = true)
                    }
                    adapter.setItems(results)
                    binding.tvBreadcrumb.text = "当前目录搜索「$kw」：${results.size} 个结果"
                }
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
        // 公共目录已有同名同大小文件：跳过下载直接导入
        val existing = BaiduPanHelp.findPublic(file.name, file.size)
        if (existing != null) {
            alert("导入已有文件") {
                setMessage("「${file.name}」已在 Download/开源阅读/ 目录，直接加入书架？")
                positiveButton("导入") {
                    downloadAndImport(file, existing, skipDownload = true)
                }
                negativeButton("取消")
            }
            return
        }
        alert("下载并导入") {
            setMessage(
                "「${file.name}」（${formatSize(file.size)}）\n\n" +
                    "将下载到手机「Download/开源阅读/」目录（文件管理器可见，可随时手动删除），" +
                    "网盘原件不受影响。"
            )
            positiveButton("下载") {
                downloadAndImport(file, null)
            }
            negativeButton("取消")
        }
    }

    private fun downloadAndImport(file: CloudFile, existingUri: Uri?, skipDownload: Boolean = false) {
        if (downloading) {
            toastOnUi("有文件正在下载，请稍候")
            return
        }
        downloading = true
        if (!skipDownload) toastOnUi("开始下载：${file.name}")
        lifecycleScope.launch {
            try {
                val uri: Uri = existingUri ?: run {
                    // 优先公共目录（文件管理器可见），失败回退应用私有目录
                    val pub = BaiduPanHelp.downloadPublic(file)
                    if (pub != null) {
                        toastOnUi("已保存到 Download/开源阅读/")
                        pub
                    } else {
                        val f = File(
                            appCtx.getExternalFilesDir(null) ?: appCtx.filesDir,
                            "cloudBooks/${file.name}"
                        )
                        withContext(Dispatchers.IO) {
                            BaiduPanHelp.download(file.fsId, f)
                        }
                        Uri.fromFile(f)
                    }
                }
                val book = withContext(Dispatchers.IO) {
                    LocalBook.importFile(uri)
                }
                toastOnUi("《${book.name}》已加入书架")
            } catch (e: Exception) {
                toastOnUi("导入失败：${e.message}")
            } finally {
                downloading = false
            }
        }
    }

    private var downloading = false

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
