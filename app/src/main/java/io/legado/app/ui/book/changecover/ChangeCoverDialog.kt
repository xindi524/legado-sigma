package io.legado.app.ui.book.changecover

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogChangeCoverBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 换封面
 */
class ChangeCoverDialog() : BaseDialogFragment(R.layout.dialog_change_cover),
    Toolbar.OnMenuItemClickListener,
    CoverAdapter.CallBack {

    constructor(name: String, author: String, useEngine: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
            putBoolean("useEngine", useEngine)
        }
    }

    private val binding by viewBinding(DialogChangeCoverBinding::bind)
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeCoverViewModel by viewModels()
    private val adapter by lazy { CoverAdapter(requireContext(), this) }

    private val startStopMenuItem: MenuItem?
        get() = binding.toolBar.menu.findItem(R.id.menu_start_stop)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        viewModel.initData(arguments)
        upTitle()
        initMenu()
        initView()
        initData()
    }

    private fun upTitle() {
        binding.toolBar.title = if (viewModel.engine.isEmpty()) {
            getString(R.string.change_cover_source)
        } else {
            "${viewModel.engineNames[viewModel.engine] ?: viewModel.engine} · 点右上角切换"
        }
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.change_cover)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    private fun initView() {
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter
    }

    private fun initData() {
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.dataFlow.conflate().collect {
                adapter.setItems(it)
                delay(1000)
            }
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        viewModel.searchStateData.observe(viewLifecycleOwner) {
            binding.refreshProgressBar.isAutoLoading = it == 1
            when (it) {
                1 -> {
                    startStopMenuItem?.let { item ->
                        item.setIcon(R.drawable.ic_stop_black_24dp)
                        item.setTitle(R.string.stop)
                    }
                }
                0 -> {
                    startStopMenuItem?.let { item ->
                        item.setIcon(R.drawable.ic_refresh_black_24dp)
                        item.setTitle(R.string.refresh)
                    }
                }
                2 -> {
                    startStopMenuItem?.let { item ->
                        item.setIcon(R.drawable.ic_play_outline_24dp)
                        item.setTitle(R.string.resume)
                    }
                }
            }
            binding.toolBar.menu.applyTint(requireContext())
        }

    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_start_stop -> viewModel.startOrStopSearch()
            R.id.menu_search_keyword -> showKeywordDialog()
            R.id.menu_engine_baidu -> switchEngine("baidu")
            R.id.menu_engine_bing -> switchEngine("bing")
            R.id.menu_engine_sogou -> switchEngine("sogou")
            R.id.menu_engine_google -> switchEngine("google")
            R.id.menu_engine_source -> switchEngine("")
        }
        return false
    }

    private fun switchEngine(engineKey: String) {
        viewModel.searchFromEngine(engineKey)
        upTitle()
    }

    private fun showKeywordDialog() {
        val editText = EditText(requireContext()).apply {
            setText(viewModel.keyword.ifBlank { "${viewModel.name} 封面" })
            setSingleLine()
            setPadding(48, 32, 48, 0)
        }
        requireContext().alert("搜索关键词") {
            customView { editText }
            positiveButton("搜索") {
                val kw = editText.text.toString().trim()
                if (kw.isNotEmpty()) {
                    viewModel.keyword = kw
                    viewModel.searchFromEngine(viewModel.engine.ifEmpty { "baidu" })
                    upTitle()
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    override fun changeTo(coverUrl: String) {
        callBack?.coverChangeTo(coverUrl)
        dismissAllowingStateLoss()
    }

    interface CallBack {
        fun coverChangeTo(coverUrl: String)
    }
}