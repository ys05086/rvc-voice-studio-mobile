package com.voicestudio.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A recorder-app waveform: bars on a dB scale (so whispers still show), the played part in the accent
 * colour, a playhead, and touch-to-seek. Two modes:
 *   audio  a whole clip; [position] is a sample index, dragging calls [onSeek]
 *   live   while recording; [livePeaks] (one value per 20 ms) scroll in from the right
 */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onSeek: ((Int) -> Unit)? = null
    var onSeekDone: ((Int) -> Unit)? = null
    private var audio: FloatArray? = null
    private var bars = FloatArray(0)
    private var barsWidth = -1
    var position = 0
        set(value) { field = value; invalidate() }
    var livePeaks: FloatArray? = null
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val step = 3 * density  // one bar every 3 dp
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent); strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
    private val rest = Paint(played).apply { color = context.getColor(R.color.fill); alpha = 150 }
    private val axis = Paint().apply { color = context.getColor(R.color.line); strokeWidth = density }
    private val head = Paint().apply { color = context.getColor(R.color.rec); strokeWidth = 2 * density }

    val samples get() = audio?.size ?: 0

    fun setAudio(x: FloatArray?) {
        audio = x
        livePeaks = null
        position = 0
        barsWidth = -1
        invalidate()
    }

    private fun height(peak: Float): Float {  // dB scale: -60 dB at the axis, 0 dB full height
        val db = 20 * log10(peak + 1e-6f)
        return ((db + 60) / 60).coerceIn(0.02f, 1f)
    }

    private fun computeBars(w: Int) {
        val x = audio ?: return
        val n = max(1, (w / step).toInt())
        bars = FloatArray(n) { i ->
            val a = (i.toLong() * x.size / n).toInt()
            val b = max(a + 1, ((i + 1).toLong() * x.size / n).toInt())
            var peak = 0f
            for (k in a until min(b, x.size)) peak = max(peak, abs(x[k]))
            height(peak)
        }
        barsWidth = w
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val mid = height / 2f
        val half = height / 2f - 2 * density
        canvas.drawLine(0f, mid, w, mid, axis)
        val live = livePeaks
        if (live != null) {
            val n = (w / step).toInt()
            val first = max(0, live.size - n)
            for (i in first until live.size) {
                val px = w - (live.size - i) * step + step / 2
                val h = height(live[i]) * half
                canvas.drawLine(px, mid - h, px, mid + h, played)
            }
            return
        }
        val x = audio ?: return
        if (barsWidth != width) computeBars(width)
        val headX = if (x.isEmpty()) 0f else position.toFloat() / x.size * w
        for (i in bars.indices) {
            val px = i * step + step / 2
            val h = bars[i] * half
            canvas.drawLine(px, mid - h, px, mid + h, if (px <= headX) played else rest)
        }
        canvas.drawLine(headX, 0f, headX, height.toFloat(), head)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = audio ?: return false
        if (livePeaks != null || x.isEmpty()) return false
        val target = ((event.x / width).coerceIn(0f, 1f) * (x.size - 1)).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)  // keep the scroll view from taking the drag
                position = target
                onSeek?.invoke(target)
            }
            MotionEvent.ACTION_UP -> {
                position = target
                onSeekDone?.invoke(target)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
