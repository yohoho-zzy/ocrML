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
    private val cornerRadius = 16f * resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxWidth = 300f * resources.displayMetrics.density
        val boxHeight = 180f * resources.displayMetrics.density
        val expansion = 30f * resources.displayMetrics.density
        val offsetY = 120f * resources.displayMetrics.density
        val left = ((w - boxWidth) / 2f - expansion / 2f).toInt()
        val top = (((h - boxHeight) / 2f) - offsetY - expansion / 2f)
            .toInt().coerceAtLeast(0)
        val right = (left + boxWidth + expansion).toInt()
        val bottom = (top + boxHeight + expansion).toInt()
        boxRect.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(RectF(boxRect), cornerRadius, cornerRadius, Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, maskPaint)
        canvas.drawRoundRect(RectF(boxRect), cornerRadius, cornerRadius, borderPaint)
    }

    fun getBoxRect(): Rect = boxRect
}

