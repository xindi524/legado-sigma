package io.legado.app.ui.book.toc.rule

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.databinding.DialogBookTocRegexBinding

/**
 * F3a：本书多正则编辑对话框
 * 为单本书配置多个目录正则（存入 book.variableMap，免数据库迁移），保存后触发重新分章
 */
class BookTocRegexDialog(val book: Book) : BaseDialogFragment(R.layout.dialog_book_toc_regex) {

    interface Callback {
        fun upBookAndToc(book: Book)
    }

    private val binding by viewBinding(DialogBookTocRegexBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        initData()
        binding.tvAdd.setOnClickListener { addRegexRow("") }
        binding.tvCancel.setOnClickListener { dismiss() }
        binding.tvSave.setOnClickListener { save() }
    }

    private fun initData() {
        val regexes = book.getTocRegexes()
        if (regexes.isEmpty()) {
            addRegexRow("")
        } else {
            regexes.forEach { addRegexRow(it) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addRegexRow(text: String) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            tag = "keep"
        }
        val et = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            setText(text)
            hint = "正则表达式，如：第[0-9一二三四五六七八九十百千]+[章节回]"
            textSize = 14f
            singleLine = false
            setMaxLines(3)
        }
        val del = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 12.dpToPx() }
            text = "✕"
            textSize = 18f
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
            setOnClickListener {
                row.tag = "deleted"
                row.visibility = View.GONE
            }
        }
        row.addView(et)
        row.addView(del)
        binding.llContainer.addView(row)
    }

    private fun save() {
        val regexes = mutableListOf<String>()
        for (i in 0 until binding.llContainer.childCount) {
            val row = binding.llContainer.getChildAt(i) as? LinearLayout ?: continue
            if (row.tag == "deleted") continue
            val et = row.getChildAt(0) as? EditText ?: continue
            et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { regexes.add(it) }
        }
        book.setTocRegexes(regexes)
        if (regexes.isEmpty()) {
            // 清空本书正则时重置 tocUrl，让分章恢复全局规则竞争
            book.tocUrl = ""
        }
        appDb.bookDao.update(book)
        if (regexes.isEmpty()) {
            requireContext().toastOnUi("已清空本书正则，恢复全局目录规则")
        } else {
            requireContext().toastOnUi("已保存 ${regexes.size} 条本书正则，正在重新分章")
        }
        (activity as? Callback)?.upBookAndToc(book)
        dismiss()
    }
}
