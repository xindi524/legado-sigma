package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemManageCoverBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover

/**
 * 书架管理：封面网格模式适配器
 * 点击封面切换选中状态，配合底部批量操作栏使用
 */
class CoverBookAdapter(context: Context, val callBack: BookAdapter.CallBack) :
    RecyclerAdapter<Book, ItemManageCoverBinding>(context) {

    private val selectedBooks = hashSetOf<Book>()

    val selection: List<Book>
        get() = getItems().filter { selectedBooks.contains(it) }

    override fun getViewBinding(parent: ViewGroup): ItemManageCoverBinding {
        return ItemManageCoverBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upSelectCount()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemManageCoverBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        val cover = item.getDisplayCover()
        if (cover.isNullOrEmpty()) {
            binding.ivCover.setImageResource(R.drawable.image_cover_default)
        } else {
            BookCover.load(context, cover).into(binding.ivCover)
        }
        binding.tvName.text = item.name
        val progress = item.durChapterTitle
        if (progress.isNullOrEmpty()) {
            binding.tvProgress.visibility = View.GONE
        } else {
            binding.tvProgress.visibility = View.VISIBLE
            binding.tvProgress.text = progress
        }
        val checked = selectedBooks.contains(item)
        binding.ivCheck.visibility = if (checked) View.VISIBLE else View.GONE
        binding.ivCheck.setColorFilter(context.primaryColor)
        binding.ivCover.alpha = if (checked) 0.7f else 1f
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemManageCoverBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { book ->
                toggleSelect(book)
                notifyItemChanged(holder.layoutPosition)
                callBack.upSelectCount()
            }
        }
    }

    private fun toggleSelect(book: Book) {
        if (selectedBooks.contains(book)) {
            selectedBooks.remove(book)
        } else {
            selectedBooks.add(book)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            getItems().forEach {
                selectedBooks.add(it)
            }
        } else {
            selectedBooks.clear()
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun revertSelection() {
        getItems().forEach {
            if (selectedBooks.contains(it)) {
                selectedBooks.remove(it)
            } else {
                selectedBooks.add(it)
            }
        }
        notifyDataSetChanged()
        callBack.upSelectCount()
    }
}
