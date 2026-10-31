package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.secondaryTextColor

/**
 * 云端书库下载进度圆环（iOS AppStore 风格）：
 * 运行中：主题色进度弧 + 中心百分比数字
 * 暂停：中心显示继续三角
 * 完成：中心显示对勾
 * 点击切换暂停/继续/清除
 */
class CloudProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }
    var paused: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var done: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var onToggle: (() -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val playPath = Path()

    init {
        setOnClickListener { onToggle?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val stroke = width * 0.13f
        val radius = width / 2f - stroke
        val themeColor = context.primaryColor
        val secColor = context.secondaryTextColor

        // 背景圆环
        ringPaint.strokeWidth = stroke
        ringPaint.color = secColor
        ringPaint.alpha = 50
        canvas.drawCircle(cx, cy, radius, ringPaint)

        if (done) {
            fillPaint.color = themeColor
            val s = width * 0.42f
            playPath.reset()
            playPath.moveTo(cx - s / 2, cy)
            playPath.lineTo(cx - s * 0.1f, cy + s * 0.45f)
            playPath.lineTo(cx + s / 2, cy - s * 0.4f)
            playPath.close()
            canvas.drawPath(playPath, fillPaint)
            return
        }

        if (paused) {
            // 继续符号（实心三角）
            fillPaint.color = themeColor
            val s = width * 0.44f
            playPath.reset()
            playPath.moveTo(cx - s / 2.2f, cy - s / 2f)
            playPath.lineTo(cx - s / 2.2f, cy + s / 2f)
            playPath.lineTo(cx + s / 2.2f, cy)
            playPath.close()
            canvas.drawPath(playPath, fillPaint)
            return
        }

        // 进度弧
        ringPaint.color = themeColor
        ringPaint.alpha = 255
        canvas.drawArc(
            cx - radius, cy - radius, cx + radius, cy + radius,
            -90f, progress * 3.6f, false, ringPaint
        )
        // 中心进度数字
        textPaint.color = themeColor
        textPaint.textSize = width * 0.30f
        canvas.drawText("$progress%", cx, cy + textPaint.textSize / 3, textPaint)
    }

}
