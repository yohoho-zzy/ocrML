package com.example.ocrml

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 緑枠（スキャン対象のガイド）──
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
    }

    // ── 背景の半透明マスク ──
    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x80000000.toInt()
        isAntiAlias = true
    }

    // ── デバッグ用：実際に切り出される ROI を表示する枠（ビュー座標）──
    private val debugPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 3f
        // 点線で見やすく
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        isAntiAlias = true
    }

    private val boxRect = Rect()
    private val path = Path()
    private val cornerRadius = 16f * resources.displayMetrics.density

    // ── デバッグ描画の状態 ──
    @Volatile private var debugRect: Rect? = null
    @Volatile private var debugEnabled: Boolean = false

    // ── 画面回転やサイズ変更時にガイド枠を再計算 ──
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val expansion = 20f * density
        val isLandscape = w > h

        if (isLandscape) {
            // 横向き：名刺/カード比(5:3)の枠を画面中央に配置、少し余白(expansion)
            val desiredWidth = w * 0.6f
            val maxWidthByHeight = (h - expansion) * 5f / 3f
            val boxWidth = min(desiredWidth, maxWidthByHeight)
            val boxHeight = boxWidth * 3f / 5f
            val left = ((w - boxWidth) / 2f - expansion / 2f).toInt().coerceAtLeast(0)
            val top = ((h - boxHeight) / 2f - expansion / 2f).toInt().coerceAtLeast(0)
            val right = (left + boxWidth + expansion).toInt().coerceAtMost(w)
            val bottom = (top + boxHeight + expansion).toInt().coerceAtMost(h)
            boxRect.set(left, top, right, bottom)
        } else {
            // 縦向き：あなたの既存バランスを踏襲（完璧な見た目を維持）
            val boxWidth = 300f * density
            val boxHeight = 180f * density
            val offsetY = 120f * density
            val left = ((w - boxWidth) / 2f - expansion / 2f).toInt().coerceAtLeast(0)
            val top = (((h - boxHeight) / 2f) - offsetY - expansion / 2f)
                .toInt().coerceAtLeast(0)
            val right = (left + boxWidth + expansion).toInt().coerceAtMost(w)
            val bottom = (top + boxHeight + expansion).toInt().coerceAtMost(h)
            boxRect.set(left, top, right, bottom)
        }
    }

    // ── マスク＋緑枠＋（必要なら）デバッグ枠を描画 ──
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(RectF(boxRect), cornerRadius, cornerRadius, Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, maskPaint)
        canvas.drawRoundRect(RectF(boxRect), cornerRadius, cornerRadius, borderPaint)

        val r = debugRect
        if (debugEnabled && r != null) {
            canvas.drawRect(RectF(r), debugPaint)
        }
    }

    // ── 外部（Activity）から読み出すためのガイド枠 ──
    fun getBoxRect(): Rect = boxRect

    // ── デバッグ描画の ON/OFF を切り替える（長押しトグルから呼び出し）──
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        invalidate()
    }

    // ── デバッグ矩形（ビュー座標）を設定。null なら消去 ──
    fun setDebugRect(rect: Rect?) {
        debugRect = rect
        if (debugEnabled) invalidate()
    }
}