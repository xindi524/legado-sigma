package io.legado.app.ui.book.changecover

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogChangeCoverBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.searchcover.WebCoverActivity
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

    constructor(name: String, author: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
        }
    }

    private val binding by viewBinding(DialogChangeCoverBinding::bind)
    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeCoverViewModel by viewModels()
    private val adapter by lazy { CoverAdapter(requireContext(), this) }

    // F4：网页搜封面，选中图片回传后走 changeTo 设为封面
    private val searchCoverWeb =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringExtra("cover")?.let { cover ->
                changeTo(cover)
            }
        }

    private val startStopMenuItem: MenuItem?
        get() = binding.toolBar.menu.findItem(R.id.menu_start_stop)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.change_cover_source)
        viewModel.initData(arguments)
        initMenu()
        initView()
        initData()
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
            R.id.menu_search_engine -> {
                val intent = Intent(requireContext(), WebCoverActivity::class.java)
                intent.putExtra("keyword", "${viewModel.name} 封面")
                searchCoverWeb.launch(intent)
            }
        }
        return false
    }

    override fun changeTo(coverUrl: String) {
        callBack?.coverChangeTo(coverUrl)
        dismissAllowingStateLoss()
    }

    interface CallBack {
        fun coverChangeTo(coverUrl: String)
    }
}