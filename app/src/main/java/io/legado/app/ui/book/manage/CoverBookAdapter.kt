package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.invisible

/**
 * 书架管理：封面网格模式适配器
 * 直接复用书架网格 item 布局（item_bookshelf_grid），封面大小与书架完全一致
 * 点击封面切换选中状态，配合底部批量操作栏使用
 */
class CoverBookAdapter(context: Context, val callBack: BookAdapter.CallBack) :
    RecyclerAdapter<Book, ItemBookshelfGridBinding>(context) {

    private val selectedBooks = hashSetOf<Book>()

    val selection: List<Book>
        get() = getItems().filter { selectedBooks.contains(it) }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        callBack.upSelectCount()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGridBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.ivCover.load(item, false)
        binding.tvName.text = item.name
        // 书架管理模式不显示未读角标与刷新动画
        binding.bvUnread.invisible()
        binding.rlLoading.inVisible()
        val checked = selectedBooks.contains(item)
        if (checked) {
            // 主题色实心圆底 + 深色描边；勾色按主题色亮度自适应（浅主题色用黑勾，深主题色用白勾）
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.primaryColor)
                setStroke(1.5f.dpToPx().toInt(), 0x8A000000.toInt())
            }
            binding.ivCheck.background = bg
            val lum = androidx.core.graphics.ColorUtils.calculateLuminance(context.primaryColor)
            binding.ivCheck.setImageResource(
                if (lum > 0.5) R.drawable.ic_check_bold_dark else R.drawable.ic_check_bold_white
            )
            binding.ivCheck.visibility = View.VISIBLE
            binding.ivCover.alpha = 0.85f
        } else {
            binding.ivCheck.visibility = View.GONE
            binding.ivCover.alpha = 1f
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfGridBinding) {
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
