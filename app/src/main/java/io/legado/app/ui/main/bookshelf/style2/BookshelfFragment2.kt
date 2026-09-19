package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout >= 2) {
            BooksAdapterGrid(requireContext(), this)
        } else {
            BooksAdapterList(requireContext(), this)
        }
    }
    private var bookGroups: List<BookGroup> = emptyList()
    // F1 嵌套分组：导航栈，保存从根部进入当前分组的层级链
    private val groupStack = ArrayDeque<Long>()
    // F1 嵌套分组：原生标题 TextView（Toolbar 内部控件，反射获取；分组内平移到屏幕居中）
    private var nativeTitleView: TextView? = null
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0
    private var totalRows = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        // F1 嵌套分组：标题栏返回按钮，点击逐级回退（小窗模式下无侧滑手势也能退出分组）
        binding.titleBar.setNavigationOnClickListener { back() }
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        if (bookshelfLayout >= 2) {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout)
        } else {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        }
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 采用 layoutManager?.onRestoreInstanceState(layoutState)
         * 恢复滚动位置
         * **/
        binding.rvBookshelf.itemAnimator =  null
        binding.rvBookshelf.addItemDecoration( object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (bookshelfLayout >= 2) {
                    val spanCount = bookshelfLayout
                    val rowIndex = position / spanCount
                    when (rowIndex) {
                        0 -> { //第一行加额外上边距
                            outRect.set(bookshelfMargin, bookshelfMargin + 24, bookshelfMargin, bookshelfMargin)
                        }
                        totalRows - 1 -> { //最后一行加额外下边距
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin + 24)
                        }
                        else -> {
                            outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                        }
                    }
                } else {
                    when (position) {
                        0 -> {
                            outRect.set(0, bookshelfMargin + 24, 0, bookshelfMargin)
                        }
                        itemCount - 1 -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin + 24)
                        }
                        else -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                    }
                }
            }
        })
    }

    override fun upGroup(data: List<BookGroup>) {
        // F1 嵌套分组：无条件应用最新分组数据（移除相等判断，排除一切跳过刷新的可能）
        bookGroups = data
        booksAdapter.updateItems(groupId)
        itemCount = getItemCount()
        val spanCount = bookshelfLayout
        if (spanCount >= 2) {
            totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
        }
        binding.tvEmptyMsg.isGone = itemCount > 0
        binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        // F1 嵌套分组：返回按钮显隐（根部隐藏，子分组内显示）
        upBackIcon()
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                // F1: 根部保留原版样式（左对齐"书架"）
                upCenterTitle(null)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                // F1 嵌套分组：分组内直接显示文件夹名（居中）
                upCenterTitle(it.groupName)
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            // F1 嵌套分组：主页(根部)=顶层分组块+未分组的书（分组书只出现在所属分组，主页不重复显示）；
            // 普通分组只显示直属的书；全部/本地等特殊分组沿用原版查询逻辑
            val bookFlow = if (groupId == BookGroup.IdRoot) {
                appDb.bookDao.flowRootAll()
            } else {
                appDb.bookDao.flowByGroup(groupId)
            }
            bookFlow.map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                try {
                    books = list
                    booksAdapter.updateItems(groupId)
                    itemCount = getItemCount()
                    val spanCount = bookshelfLayout
                    if (spanCount >= 2) {
                        totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                    }
                    binding.tvEmptyMsg.isGone = itemCount > 0
                    binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                    delay(100)
                } catch (e: Exception) {
                    AppLog.put("书架书籍列表处理失败\n${e.localizedMessage}", e)
                }
            }
        }
    }

    fun back(): Boolean {
        if (groupStack.isNotEmpty()) {
            groupId = groupStack.removeLast()
            initBooksData()
            return true
        }
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    // F1 嵌套分组：返回按钮显隐与染色（18dp 细线箭头，跟随主题文字色）
    private fun upBackIcon() {
        binding.titleBar.toolbar.navigationIcon = if (groupId == BookGroup.IdRoot) {
            null
        } else {
            AppCompatResources.getDrawable(requireContext(), R.drawable.ic_group_back)?.apply {
                setTint(primaryTextColor)
            }
        }
    }

    // F1 嵌套分组：反射获取 Toolbar 原生标题 TextView（布局 xml 的 title="书架" 已确保它被创建）
    private fun findNativeTitle(): TextView? {
        nativeTitleView?.let { return it }
        return try {
            val f = Toolbar::class.java.getDeclaredField("mTitleTextView")
            f.isAccessible = true
            (f.get(binding.titleBar.toolbar) as? TextView)?.also { nativeTitleView = it }
        } catch (e: Exception) {
            AppLog.put("F1: 反射读取原生标题失败 ${e.localizedMessage}")
            null
        }
    }

    // F1 嵌套分组：text=null 时为根部（原生"书架"左对齐原样），
    // 否则分组内显示文件夹名——用原生 title 控件平移到屏幕绝对居中（样式与"书架"天然一致）
    private fun upCenterTitle(text: CharSequence?) {
        val toolbar = binding.titleBar.toolbar
        if (text == null) {
            toolbar.title = getString(R.string.bookshelf)
            nativeTitleView?.translationX = 0f
        } else {
            toolbar.title = text
            findNativeTitle()?.let { tv ->
                tv.post {
                    // 重置后测量当前布局位置，平移到屏幕正中
                    tv.translationX = 0f
                    val offset = toolbar.width / 2f - (tv.x + tv.width / 2f)
                    tv.translationX = offset
                }
            }
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                // F1 嵌套分组：记录层级链，返回键逐级回退
                if (groupId != BookGroup.IdRoot) {
                    groupStack.addLast(groupId)
                } else {
                    groupStack.clear()
                }
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    // F1 嵌套分组：当前视图应显示的分组块
    // 主页=全部：首页显示用户自建顶层分组块＋"全部/本地/音频"等受开关控制的功能块（show 开关），
    // "网络未分组/本地未分组"两个拆分视图不上首页；未分组的书（本地+网络）直接散落首页
    private fun getCurrentGroups(): List<BookGroup> {
        val hideIds = listOf(BookGroup.IdNetNone, BookGroup.IdLocalNone)
        return when (groupId) {
            BookGroup.IdRoot -> bookGroups.filter { it.parentId == 0L && it.groupId !in hideIds }
            else -> bookGroups.filter { it.parentId == groupId }
        }
    }

    fun getItemCount(): Int {
        return getCurrentGroups().size + books.size
    }

    override fun getItems(): List<Any> {
        return getCurrentGroups() + books
    }

    // F1 嵌套分组：分组变更直调刷新入口（含当前视图组被删时的回根部保护）
    override fun refreshGroupData() {
        super.refreshGroupData()
        if (viewLifecycleOwnerLiveData.value != null) {
            if (groupId > 0 && appDb.bookGroupDao.getByID(groupId) == null) {
                groupId = BookGroup.IdRoot
                groupStack.clear()
            }
            initBooksData()
            // F1: 强制全量重绘兜底
            binding.rvBookshelf.post {
                booksAdapter.notifyDataSetChanged()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
        }
        // F1 嵌套分组：分组变更延迟补发（防时序竞态的兜底，主通道为 GroupChangeNotifier 直调）
        observeEvent<String>(EventBus.BOOK_GROUP_CHANGED_DELAYED) {
            refreshGroupData()
        }
    }
}