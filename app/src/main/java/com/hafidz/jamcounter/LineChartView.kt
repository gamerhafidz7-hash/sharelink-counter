package com.hafidz.jamcounter

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * Grafik garis + area (gaya Shopee). Nilai negatif = titik tidak ada (garis terputus, mis. hari belum terjadi).
 * Sentuh grafik untuk melihat nilai titik (tooltip di bagian atas).
 */
class LineChartView(ctx: Context) : View(ctx) {

    private var values = IntArray(0)
    private var xLabels: List<String> = emptyList()
    private var tips: List<String> = emptyList()
    private var selected = -1
    private var prog = 1f
    private var anim: ValueAnimator? = null

    private val accent = Color.parseColor("#EE4D2D")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(5).toFloat(), ctx.dp(4).toFloat()), 0f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
    }
    private val selLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(4).toFloat(), ctx.dp(3).toFloat()), 0f)
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.RIGHT
        textSize = sp(11f)
    }
    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#757575")
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    private val dotWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333")
        style = Paint.Style.FILL
    }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
    }

    // geometri plot dari gambar terakhir (dipakai untuk sentuhan)
    private var plotLeft = 0f
    private var plotRight = 0f

    private var touchDownX = 0f
    private var touchDownY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun d(v: Int): Float = context.dp(v).toFloat()

    fun setData(values: IntArray, xLabels: List<String>, tips: List<String>) {
        this.values = values.copyOf()
        this.xLabels = xLabels
        this.tips = tips
        selected = -1
        anim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 520
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            prog = it.animatedValue as Float
            invalidate()
        }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private fun niceStep(target: Int): Int {
        var base = 1
        while (true) {
            for (m in intArrayOf(1, 2, 5)) {
                val s = base * m
                if ((target + s - 1) / s <= 4) return s
            }
            base *= 10
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        var maxV = 0
        for (v in values) if (v > maxV) maxV = v
        val target = maxOf(maxV, 2)
        val step = niceStep(target)
        val topV = ((target + step - 1) / step) * step
        val tickCount = topV / step

        val left = yLabelPaint.measureText(topV.toString()) + d(12)
        val right = w - d(14)
        val top = d(32)
        val bottom = h - d(26)
        plotLeft = left
        plotRight = right

        fun xOf(i: Int): Float =
            if (n <= 1) (left + right) / 2f else left + (right - left) * i / (n - 1)

        fun yOf(v: Int): Float = bottom - (bottom - top) * v * prog / topV

        // garis bantu + label sumbu Y
        val gp = Path()
        for (t in 0..tickCount) {
            val v = t * step
            val y = yOf(v)
            if (t == 0) {
                canvas.drawLine(left, y, right, y, axisPaint)
            } else {
                gp.reset()
                gp.moveTo(left, y)
                gp.lineTo(right, y)
                canvas.drawPath(gp, gridPaint)
            }
            canvas.drawText(v.toString(), left - d(6), y + yLabelPaint.textSize / 3f, yLabelPaint)
        }

        // garis + area per rangkaian titik yang berurutan
        var i = 0
        while (i < n) {
            if (values[i] < 0) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && values[j + 1] >= 0) j++

            if (j > i) {
                val line = Path()
                val area = Path()
                line.moveTo(xOf(i), yOf(values[i]))
                area.moveTo(xOf(i), bottom)
                area.lineTo(xOf(i), yOf(values[i]))
                for (k in i + 1..j) {
                    line.lineTo(xOf(k), yOf(values[k]))
                    area.lineTo(xOf(k), yOf(values[k]))
                }
                area.lineTo(xOf(j), bottom)
                area.close()
                areaPaint.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    Color.argb(70, 238, 77, 45), Color.argb(4, 238, 77, 45),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(area, areaPaint)
                canvas.drawPath(line, linePaint)
            } else {
                canvas.drawCircle(xOf(i), yOf(values[i]), d(3), dotFill) // titik tunggal
            }
            i = j + 1
        }

        // titik kecil kalau datanya sedikit (mis. 7 hari)
        if (n <= 8) {
            for (k in 0 until n) if (values[k] >= 0) canvas.drawCircle(xOf(k), yOf(values[k]), d(3), dotFill)
        }

        // label sumbu X (dijarangkan otomatis agar tidak bertumpuk)
        if (xLabels.isNotEmpty()) {
            var maxW = 0f
            for (s in xLabels) maxW = maxOf(maxW, xLabelPaint.measureText(s))
            val slot = if (n > 1) (right - left) / (n - 1) else (right - left)
            val stride = maxOf(1, Math.ceil(((maxW + d(8)) / slot).toDouble()).toInt())
            var k = 0
            while (k < n) {
                val s = xLabels.getOrNull(k) ?: ""
                if (s.isNotEmpty()) {
                    val tw = xLabelPaint.measureText(s)
                    val cx = xOf(k).coerceIn(tw / 2f, maxOf(tw / 2f, w - tw / 2f))
                    canvas.drawText(s, cx, bottom + d(18), xLabelPaint)
                }
                k += stride
            }
        }

        // titik terpilih + tooltip
        val s = selected
        if (s in 0 until n && values[s] >= 0) {
            val x = xOf(s)
            val y = yOf(values[s])
            val vp = Path()
            vp.moveTo(x, top)
            vp.lineTo(x, bottom)
            canvas.drawPath(vp, selLinePaint)
            canvas.drawCircle(x, y, d(6), dotWhite)
            canvas.drawCircle(x, y, d(4), dotFill)

            val text = tips.getOrNull(s) ?: values[s].toString()
            val bw = tipText.measureText(text) + d(16)
            val bh = d(22)
            val maxLeft = maxOf(d(2), w - bw - d(2))
            val bl = (x - bw / 2f).coerceIn(d(2), maxLeft)
            val rect = RectF(bl, d(2), bl + bw, d(2) + bh)
            canvas.drawRoundRect(rect, d(6), d(6), tipBg)
            canvas.drawText(text, rect.centerX(), rect.centerY() + tipText.textSize / 3f, tipText)
        }
    }

    private fun selectNearest(x: Float) {
        val n = values.size
        if (n == 0) return
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until n) {
            if (values[i] < 0) continue
            val px = if (n <= 1) (plotLeft + plotRight) / 2f else plotLeft + (plotRight - plotLeft) * i / (n - 1)
            val dist = abs(px - x)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        if (best != selected) {
            selected = best
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = e.x
                touchDownY = e.y
                selectNearest(e.x)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(e.x - touchDownX)
                val dy = abs(e.y - touchDownY)
                if (dx > slop && dx > dy) {
                    parent?.requestDisallowInterceptTouchEvent(true) // geser mendatar = pilih titik, bukan scroll halaman
                    selectNearest(e.x)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
