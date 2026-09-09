package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookGroupEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.gone
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.collections.contains

class GroupEditDialog() : BaseDialogFragment(R.layout.dialog_book_group_edit) {

    constructor(bookGroup: BookGroup? = null) : this() {
        arguments = Bundle().apply {
            putParcelable("group", bookGroup?.copy())
        }
    }

    private val binding by viewBinding(DialogBookGroupEditBinding::bind)
    private val viewModel by viewModels<GroupViewModel>()
    private var bookGroup: BookGroup? = null
    // F1 嵌套分组：所选上级分组ID，0表示顶层
    private var selectedParentId: Long = 0L
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri ?: return@registerForActivityResult
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            binding.ivCover.load(uri.toString())
            return@registerForActivityResult
        }
        readUri(uri) { fileDoc, inputStream ->
            try {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = it.uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                    MD5Utils.md5Encode(tmp) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                binding.ivCover.load(file.absolutePath)
            } catch (e: Exception) {
                appCtx.toastOnUi(e.localizedMessage)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        @Suppress("DEPRECATION")
        bookGroup = arguments?.getParcelable("group")
        bookGroup?.let {
            binding.btnDelete.visible(it.groupId > 0 || it.groupId == Long.MIN_VALUE)
            binding.tieGroupName.setText(it.groupName)
            binding.ivCover.load(it.cover)
            if (it.bookSort + 1 !in 0..<binding.spSort.count) {
                it.bookSort = -1
            }
            binding.spSort.setSelection(it.bookSort + 1)
            binding.cbEnableRefresh.isChecked = it.enableRefresh
            binding.cbEnableOnlyRead.isChecked = it.onlyUpdateRead
            // F1: 回填上级分组
            selectedParentId = it.parentId
            upParentName()
        } ?: let {
            binding.toolBar.title = getString(R.string.add_group)
            binding.btnDelete.gone()
            binding.ivCover.load()
            selectedParentId = 0L
        }
        binding.run {
            ivCover.onClick {
                if (!bookGroup?.cover.isNullOrEmpty()) {
                    val actions = arrayListOf(
                        getString(R.string.select_image),
                        getString(R.string.delete)
                    )
                    context?.selector(items = actions) { _, i ->
                        when (i) {
                            0 -> selectImage.launch {
                                mode = HandleFileContract.IMAGE
                            }
                            1 -> binding.ivCover.load()
                        }
                    }
                } else {
                    selectImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
            // F1: 选择上级分组（排除自己及所有下级，防止循环嵌套）
            tvParentValue.onClick {
                viewLifecycleOwner.lifecycleScope.launch {
                    val candidates = selectableParents()
                    val names = mutableListOf("无（顶层分组）")
                    names.addAll(candidates.map { it.groupName })
                    context?.selector(items = names) { _, i ->
                        selectedParentId = if (i == 0) 0L else candidates[i - 1].groupId
                        upParentName()
                    }
                }
            }
            btnCancel.onClick {
                dismiss()
            }
            btnOk.onClick {
                val groupName = tieGroupName.text?.toString()
                if (groupName.isNullOrEmpty()) {
                    toastOnUi("分组名称不能为空")
                } else {
                    val bookSort = binding.spSort.selectedItemPosition - 1
                    val coverPath = binding.ivCover.bitmapPath
                    val enableRefresh = binding.cbEnableRefresh.isChecked
                    val onlyUpdateRead = binding.cbEnableOnlyRead.isChecked
                    bookGroup?.let {
                        it.groupName = groupName
                        it.cover = coverPath
                        it.bookSort = bookSort
                        it.enableRefresh = enableRefresh
                        it.onlyUpdateRead = onlyUpdateRead
                        it.parentId = selectedParentId
                        viewModel.upGroup(it) {
                            dismiss()
                        }
                    } ?: let {
                        viewModel.addGroup(
                            groupName,
                            bookSort,
                            enableRefresh,
                            onlyUpdateRead,
                            coverPath,
                            selectedParentId
                        ) {
                            dismiss()
                        }
                    }
                }

            }
            btnDelete.onClick {
                deleteGroup {
                    bookGroup?.let {
                        viewModel.delGroup(it) {
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    private fun deleteGroup(ok: () -> Unit) {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                ok.invoke()
            }
            noButton()
        }
    }

    // F1: 显示当前所选上级分组名称
    private fun upParentName() {
        viewLifecycleOwner.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                if (selectedParentId == 0L) {
                    "无（顶层分组）"
                } else {
                    appDb.bookGroupDao.getByID(selectedParentId)?.groupName ?: "无（顶层分组）"
                }
            }
            binding.tvParentValue.text = name
        }
    }

    // F1: 可选上级分组列表 = 所有普通分组 - 自己 - 自己的所有下级（防循环）
    private suspend fun selectableParents(): List<BookGroup> = withContext(Dispatchers.IO) {
        val all = appDb.bookGroupDao.all
        val selfId = bookGroup?.groupId ?: return@withContext all.filter { it.groupId >= 0 }
        val exclude = mutableSetOf(selfId)
        var changed = true
        while (changed) {
            changed = false
            for (g in all) {
                if (g.parentId in exclude && exclude.add(g.groupId)) {
                    changed = true
                }
            }
        }
        all.filter { it.groupId >= 0 && it.groupId !in exclude }
    }

}