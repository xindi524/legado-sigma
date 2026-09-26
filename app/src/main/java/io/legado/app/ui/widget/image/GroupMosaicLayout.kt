package io.legado.app.ui.widget.image

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * F2 分组封面拼图容器
 * 强制 3:4 宽高比（与 CoverImageView 的 onMeasure 规则一致，保证拼图块与单封面块等高）
 */
class GroupMosaicLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val measuredHeightSpec = if (width > 0 && heightMode != MeasureSpec.EXACTLY) {
            MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, measuredHeightSpec)
    }
}
