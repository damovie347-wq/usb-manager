package com.usbmanager.app.ui.speedtest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Basit, bagimlilik gerektirmeyen Canvas tabanli hiz gostergesi (speedometer).
 * 0..maxMBps araligini 135°..405° (yani -135° ile +135°, toplam 270°) arasinda
 * yesil -> sari -> kirmizi renk gecisli bir yay olarak cizer, uzerine bir ibre
 * yerlestirir.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var maxMBps = 500.0
    private var currentMBps = 0.0
    private var animatedMBps = 0.0

    private val arcRect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 28f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = 0xFF37474F.toInt()
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF37474F.toInt()
    }

    private var animator: ValueAnimator? = null

    fun setMaxSpeed(mbps: Double) {
        maxMBps = mbps.coerceAtLeast(1.0)
        invalidate()
    }

    fun setSpeed(mbps: Double) {
        currentMBps = mbps.coerceIn(0.0, maxMBps)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animatedMBps.toFloat(), currentMBps.toFloat()).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedMBps = (it.animatedValue as Float).toDouble()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = trackPaint.strokeWidth
        arcRect.set(padding, padding, width - padding, height - padding)

        val startAngle = 135f
        val sweepTotal = 270f

        // Yesil / Sari / Kirmizi bolgeler (yaklasik 1/3'er)
        val third = sweepTotal / 3f
        trackPaint.color = 0xFF43A047.toInt()
        canvas.drawArc(arcRect, startAngle, third, false, trackPaint)
        trackPaint.color = 0xFFFDD835.toInt()
        canvas.drawArc(arcRect, startAngle + third, third, false, trackPaint)
        trackPaint.color = 0xFFE53935.toInt()
        canvas.drawArc(arcRect, startAngle + 2 * third, third, false, trackPaint)

        // Ibre
        val fraction = (animatedMBps / maxMBps).coerceIn(0.0, 1.0)
        val needleAngleDeg = startAngle + (sweepTotal * fraction)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - padding * 2.2f
        val angleRad = Math.toRadians(needleAngleDeg.toDouble())
        val nx = cx + radius * cos(angleRad).toFloat()
        val ny = cy + radius * sin(angleRad).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        canvas.drawCircle(cx, cy, 14f, centerPaint)
    }
}
