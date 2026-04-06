package com.example.stalker2ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class CurvedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var text = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        // Увеличили межбуквенный интервал, чтобы текст не слипался (было 0.12f)
        letterSpacing = 0.25f
        // Немного сужаем сами буквы
        textScaleX = 0.9f
    }
    private val path = Path()

    @Suppress("unused")
    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    @Suppress("unused")
    fun setTextSize(size: Float) {
        paint.textSize = size
        invalidate()
    }

    @Suppress("unused")
    fun setTextColor(color: Int) {
        paint.color = color
        invalidate()
    }

    @Suppress("unused")
    fun setShadow(radius: Float, dx: Float, dy: Float, color: Int) {
        paint.setShadowLayer(radius, dx, dy, color)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        path.reset()
        // Сделали дугу чуть шире (0.1f вместо 0.15f), чтобы поместился текст с большими интервалами
        path.moveTo(w * 0.1f, h * 0.6f)
        path.quadTo(w * 0.5f, -h * 0.4f, w * 0.9f, h * 0.6f)

        canvas.drawTextOnPath(text, path, 0f, 0f, paint)
    }
}
