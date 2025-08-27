package com.example.ocrml

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 4f
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x80000000.toInt()
    }

    private val boxRect = Rect()
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxWidth = 250f * resources.displayMetrics.density
        val boxHeight = 150f * resources.displayMetrics.density
        val left = ((w - boxWidth) / 2f).toInt()
        val top = ((h - boxHeight) / 2f).toInt()
        val right = (left + boxWidth).toInt()
        val bottom = (top + boxHeight).toInt()
        boxRect.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(RectF(boxRect), Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, maskPaint)
        canvas.drawRect(boxRect, borderPaint)
    }

    fun getBoxRect(): Rect = boxRect
}

