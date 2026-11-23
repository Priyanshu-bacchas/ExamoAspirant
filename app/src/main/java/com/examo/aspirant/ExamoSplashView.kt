package com.examo.aspirant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class ExamoSplashView(context: Context) : LinearLayout(context) {
    val swipeHandle: LinearLayout = LinearLayout(context)
    fun setSwipeTouchListener(listener: OnTouchListener) {
        // Listen on the container and both visible children so dragging the
        // blue grip OR the hint text starts the same swipe gesture.
        swipeHandle.setOnTouchListener(listener)
        for (index in 0 until swipeHandle.childCount) {
            swipeHandle.getChildAt(index).setOnTouchListener(listener)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text; textSize = size; setTextColor(color)
            gravity = Gravity.CENTER
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val screenHeightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val compact = screenHeightDp < 820f
        val veryShort = screenHeightDp < 700f
        val horizontalPad = if (veryShort) 14 else if (compact) 18 else 22
        setPadding(dp(horizontalPad), dp(if (veryShort) 28 else if (compact) 32 else 38), dp(horizontalPad), 0)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(244,248,255), Color.rgb(211,229,252))).apply { cornerRadius = dp(28).toFloat() }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = HORIZONTAL }
        val mark = TextView(context).apply {
            text = "E"; textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.rgb(25,48,103), 13)
            elevation = dp(7).toFloat()
        }
        header.addView(mark, LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12),0,0,0) }
        brand.addView(label("Examo", 23f, Color.rgb(23,48,99), true))
        brand.addView(label("Student Management", 11f, Color.rgb(75,98,140)))
        header.addView(brand)
        addView(header, LayoutParams(-1, dp(if (veryShort) 44 else if (compact) 48 else 54)))

        // Size the illustration from the available screen width, not the full
        // leftover height. This brings the headline directly beneath the avatar
        // instead of leaving a large empty gap on tall phones.
        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        // Slightly enlarge the illustration while keeping it centered and
        // bounded by the available screen dimensions on compact phones.
        val artHeightDp = minOf(screenWidthDp * 0.99f, screenHeightDp * 0.51f)
            .toInt()
            .coerceAtLeast(if (veryShort) 260 else 295)
        val art = StudentOrbitView(context)
        val artParams = LayoutParams(-1, dp(artHeightDp)).apply {
            // Let the orbit artwork use the full device width instead of the
            // padded text column, while staying centered and inside the screen.
            marginStart = -dp(horizontalPad)
            marginEnd = -dp(horizontalPad)
            topMargin = dp(if (compact) 0 else 4)
            bottomMargin = dp(if (veryShort) 6 else 10)
        }
        addView(art, artParams)

        // Move the text block slightly lower, keeping its alignment and style.
        addView(label("Your exams, beautifully organised.", if (veryShort) 17f else if (compact) 19f else 21f, Color.rgb(29,48,91), true),
            LayoutParams(-1, dp(if (veryShort) 31 else if (compact) 36 else 40)).apply {
                // Enlarge and lower the complete copy block while preserving
                // the illustration motion and the bottom swipe interaction.
                topMargin = dp(if (veryShort) 14 else if (compact) 18 else 20)
            })
        addView(label("Exam forms, schedules, preparation and subjects — everything\na student needs, in one place.", if (veryShort) 11f else if (compact) 12f else 13f, Color.rgb(76,94,127)),
            LayoutParams(-1, dp(if (veryShort) 42 else if (compact) 48 else 52)))

        val chips = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = HORIZONTAL }
        listOf("Exam Forms", "Schedules", "Preparation").forEach { text ->
            val chip = label(text, if (veryShort) 10.5f else 11.5f, Color.rgb(36,62,112), true).apply {
                background = rounded(Color.rgb(247,250,255), 24)
                setPadding(dp(15), dp(9), dp(15), dp(9))
            }
            val lp = LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
            chips.addView(chip, lp)
        }
        addView(chips, LayoutParams(-1, dp(if (veryShort) 36 else if (compact) 42 else 46)))

        // Consume the remaining vertical space here so the swipe grip and hint
        // stay anchored close to the bottom edge on both compact and tall phones.
        addView(View(context), LayoutParams(-1, 0, 1f))

        // A visible grab-handle makes the swipe gesture obvious.
        val swipeArea = swipeHandle.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, dp(if (veryShort) 1 else 3), 0, 0)
        }
        val grip = View(context).apply {
            background = rounded(Color.rgb(34, 79, 164), 999)
            alpha = 1f
            elevation = dp(2).toFloat()
        }
        swipeArea.addView(grip, LayoutParams(-1, dp(8)).apply {
            bottomMargin = dp(7)
        })
        val swipe = label("Swipe up to get started  ↑", if (compact) 12f else 13f, Color.rgb(31,64,132), true).apply {
            background = rounded(Color.argb(55,255,255,255), 22)
            setPadding(dp(18), dp(if (compact) 7 else 8), dp(18), dp(if (compact) 7 else 8))
            alpha = 0.95f
        }
        swipeArea.addView(swipe, LayoutParams(-2, dp(if (veryShort) 30 else if (compact) 34 else 38)))
        addView(swipeArea, LayoutParams(-1, dp(if (veryShort) 50 else if (compact) 56 else 60)).apply {
            topMargin = dp(8)
            bottomMargin = dp(14)
        })
        post {
            grip.animate().alpha(0.55f).setDuration(850).withEndAction {
                grip.animate().alpha(1f).setDuration(850).start()
            }.start()
        }
    }
}

private class StudentOrbitView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var motionPhase = 0f
    private var motionAnimator: ValueAnimator? = null
    private val tilePhase = floatArrayOf(0f, .6f, 1.2f, .3f, .9f, 1.5f, .2f)
    private val tileDuration = floatArrayOf(5f, 6f, 5.5f, 6.5f, 5.2f, 6f, 5.8f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (motionAnimator == null) {
            motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 6000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    motionPhase = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        motionAnimator?.cancel()
        motionAnimator = null
        super.onDetachedFromWindow()
    }
    private fun color(value: Long) = value.toInt()
    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, c: Int, w: Float, scale: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = w * scale
        p.color = c
        canvas.drawLine(x1, y1, x2, y2, p)
        p.style = Paint.Style.FILL
    }
    private fun tile(canvas: Canvas, x: Float, y: Float, type: Int, tint: Int, scale: Float) {
        p.color = Color.WHITE
        p.setShadowLayer(10f * scale, 0f, 5f * scale, 0x26365C9A)
        canvas.drawRoundRect(x-27*scale,y-27*scale,x+27*scale,y+27*scale,15f*scale,15f*scale,p)
        p.clearShadowLayer()
        p.color=tint
        p.style=Paint.Style.STROKE
        p.strokeWidth=2.4f*scale
        p.strokeCap=Paint.Cap.ROUND
        p.strokeJoin=Paint.Join.ROUND
        when(type) {
            0 -> { // open book
                val path=Path().apply {
                    moveTo(x,y-10*scale); cubicTo(x-10*scale,y-14*scale,x-17*scale,y-12*scale,x-17*scale,y-8*scale)
                    lineTo(x-17*scale,y+10*scale); cubicTo(x-9*scale,y+7*scale,x-4*scale,y+8*scale,x,y+12*scale)
                    moveTo(x,y-10*scale); cubicTo(x+10*scale,y-14*scale,x+17*scale,y-12*scale,x+17*scale,y-8*scale)
                    lineTo(x+17*scale,y+10*scale); cubicTo(x+9*scale,y+7*scale,x+4*scale,y+8*scale,x,y+12*scale)
                    moveTo(x,y-10*scale); lineTo(x,y+12*scale)
                }; canvas.drawPath(path,p)
            }
            1 -> { // clock
                canvas.drawCircle(x,y,10*scale,p); line(canvas,x,y,x,y-6*scale,tint,2.3f,scale); line(canvas,x,y,x+5*scale,y+3*scale,tint,2.3f,scale)
            }
            2 -> { // calendar
                canvas.drawRoundRect(x-10*scale,y-10*scale,x+10*scale,y+11*scale,2*scale,2*scale,p)
                line(canvas,x-10*scale,y-4*scale,x+10*scale,y-4*scale,tint,2f,scale)
                line(canvas,x-5*scale,y-13*scale,x-5*scale,y-8*scale,tint,2.5f,scale)
                line(canvas,x+5*scale,y-13*scale,x+5*scale,y-8*scale,tint,2.5f,scale)
                for(row in 0..1) for(col in 0..2) canvas.drawCircle(x+(-5+col*5)*scale,y+(1+row*5)*scale,1.1f*scale,p)
            }
            3 -> { // laptop
                canvas.drawRoundRect(x-10*scale,y-10*scale,x+10*scale,y+7*scale,2*scale,2*scale,p)
                line(canvas,x-14*scale,y+11*scale,x+14*scale,y+11*scale,tint,2.5f,scale)
                line(canvas,x-10*scale,y+7*scale,x-13*scale,y+11*scale,tint,2f,scale)
                line(canvas,x+10*scale,y+7*scale,x+13*scale,y+11*scale,tint,2f,scale)
            }
            4 -> { // phone
                canvas.drawRoundRect(x-7*scale,y-13*scale,x+7*scale,y+13*scale,2.5f*scale,2.5f*scale,p)
                canvas.drawCircle(x,y+9*scale,1f*scale,p)
            }
            5 -> { // pencil
                val path=Path().apply { moveTo(x-10*scale,y+9*scale); lineTo(x+8*scale,y-9*scale); lineTo(x+12*scale,y-5*scale); lineTo(x-6*scale,y+13*scale); close() }
                canvas.drawPath(path,p)
                line(canvas,x-10*scale,y+9*scale,x-12*scale,y+14*scale,tint,2f,scale)
                line(canvas,x+6*scale,y-7*scale,x+10*scale,y-3*scale,tint,1.5f,scale)
            }
            6 -> { // medal
                val path=Path().apply { moveTo(x-7*scale,y-13*scale); lineTo(x-3*scale,y-3*scale); lineTo(x,y-6*scale); lineTo(x+3*scale,y-3*scale); lineTo(x+7*scale,y-13*scale) }
                canvas.drawPath(path,p)
                canvas.drawCircle(x,y+3*scale,7*scale,p)
                line(canvas,x-3*scale,y+9*scale,x-5*scale,y+16*scale,tint,2f,scale)
                line(canvas,x+3*scale,y+9*scale,x+5*scale,y+16*scale,tint,2f,scale)
            }
        }
        p.style=Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx=width/2f
        val cy=height*.50f
        // Scale against the actual full-width art viewport. The 400dp horizontal
        // bound includes the outer icon tiles, so the larger drawing stays fully
        // visible on narrow phones while remaining centered.
        val scale=minOf(width.toFloat()/400f,height.toFloat()/380f).coerceIn(.55f,1.22f)
        // Soft orbit rings and ambient dots
        p.style=Paint.Style.STROKE; p.strokeWidth=1.3f*scale; p.color=Color.rgb(188,211,247)
        canvas.drawCircle(cx,cy,166f*scale,p)
        p.pathEffect=android.graphics.DashPathEffect(floatArrayOf(2.5f*scale,4.5f*scale),0f)
        canvas.drawCircle(cx,cy,187f*scale,p); p.pathEffect=null
        p.style=Paint.Style.FILL
        val dots=arrayOf(floatArrayOf(-112f,-143f),floatArrayOf(146f,-94f),floatArrayOf(-169f,40f),floatArrayOf(170f,50f),floatArrayOf(-8f,184f))
        dots.forEachIndexed { i,d ->
            p.color=if(i%2==0) Color.rgb(143,190,249) else Color.rgb(117,174,245)
            val dotPulse = (kotlin.math.sin((motionPhase * 2.0 * Math.PI + i * 0.9)).toFloat() + 1f) / 2f
            p.alpha = (55 + 150 * dotPulse).toInt().coerceIn(0, 255)
            val dotSize = (if(i==4)4f else 2.6f) * scale * (0.7f + 0.5f * dotPulse)
            canvas.drawCircle(cx+d[0]*scale,cy+d[1]*scale,dotSize,p)
            p.alpha = 255
        }
        // Central soft blue halo
        p.color=Color.rgb(222,235,255); p.setShadowLayer(18f*scale,0f,5f*scale,0x22365C9A)
        canvas.drawCircle(cx,cy,132f*scale,p); p.clearShadowLayer()
        // Student gently bobs as on the web hero; rings and icon anchors stay fixed.
        val studentBob = (kotlin.math.sin(motionPhase * 2.0 * Math.PI).toFloat() - 1f) * 3f * scale
        canvas.save()
        canvas.translate(0f, studentBob)
        // Body with a gentle navy-to-blue fade
        val body=Path().apply {
            moveTo(cx-96*scale,cy+119*scale)
            cubicTo(cx-91*scale,cy+63*scale,cx-54*scale,cy+45*scale,cx-22*scale,cy+49*scale)
            lineTo(cx+22*scale,cy+49*scale)
            cubicTo(cx+60*scale,cy+45*scale,cx+91*scale,cy+65*scale,cx+96*scale,cy+119*scale)
            close()
        }
        p.shader=android.graphics.LinearGradient(cx,cy+48*scale,cx,cy+122*scale,
            intArrayOf(Color.rgb(25,53,111),Color.rgb(37,73,137),Color.rgb(190,211,244)),null,android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(body,p); p.shader=null
        // Shirt collar and central tie
        p.color=Color.rgb(239,246,255)
        val collar=Path().apply { moveTo(cx-25*scale,cy+52*scale); lineTo(cx,cy+103*scale); lineTo(cx+25*scale,cy+52*scale); lineTo(cx+10*scale,cy+48*scale); lineTo(cx-10*scale,cy+48*scale); close() }
        canvas.drawPath(collar,p)
        p.color=Color.rgb(24,52,108)
        val tie=Path().apply { moveTo(cx-5*scale,cy+62*scale); lineTo(cx+5*scale,cy+62*scale); lineTo(cx+8*scale,cy+88*scale); lineTo(cx,cy+101*scale); lineTo(cx-8*scale,cy+88*scale); close() }
        canvas.drawPath(tie,p)
        // Neck, ears and face
        p.color=Color.rgb(245,190,153)
        canvas.drawRoundRect(cx-13*scale,cy+15*scale,cx+13*scale,cy+65*scale,8f*scale,8f*scale,p)
        canvas.drawCircle(cx-38*scale,cy-5*scale,8f*scale,p); canvas.drawCircle(cx+38*scale,cy-5*scale,8f*scale,p)
        canvas.drawOval(cx-36*scale,cy-46*scale,cx+36*scale,cy+34*scale,p)
        p.color=Color.rgb(255,207,190)
        canvas.drawCircle(cx-22*scale,cy+8*scale,6f*scale,p); canvas.drawCircle(cx+22*scale,cy+8*scale,6f*scale,p)
        p.color=Color.rgb(35,54,87)
        val blinkPhase = (motionPhase * 6f / 5f) % 1f
        val eyeHeight = if (blinkPhase in 0.92f..0.96f) 0.55f else 3.1f
        canvas.drawOval(cx-17*scale,cy-9f*scale-eyeHeight*scale,cx-11*scale,cy-9f*scale+eyeHeight*scale,p)
        canvas.drawOval(cx+11*scale,cy-9f*scale-eyeHeight*scale,cx+17*scale,cy-9f*scale+eyeHeight*scale,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=2.3f*scale; p.strokeCap=Paint.Cap.ROUND
        canvas.drawArc(cx-14*scale,cy+1*scale,cx+14*scale,cy+19*scale,15f,150f,false,p); p.style=Paint.Style.FILL
        // Graduation cap with gold tassel
        p.color=Color.rgb(17,43,88)
        val cap=Path().apply { moveTo(cx-82*scale,cy-49*scale); lineTo(cx,cy-78*scale); lineTo(cx+82*scale,cy-49*scale); lineTo(cx,cy-18*scale); close() }
        canvas.drawPath(cap,p)
        val capBand=Path().apply { moveTo(cx-55*scale,cy-38*scale); quadTo(cx,cy-14*scale,cx+55*scale,cy-38*scale); lineTo(cx+48*scale,cy-18*scale); quadTo(cx,cy+1*scale,cx-48*scale,cy-18*scale); close() }
        canvas.drawPath(capBand,p)
        line(canvas,cx+36*scale,cy-48*scale,cx+53*scale,cy-3*scale,Color.rgb(245,177,47),2.6f,scale)
        p.color=Color.rgb(245,177,47); canvas.drawCircle(cx+53*scale,cy-1*scale,5f*scale,p)
        canvas.restore()
        // Seven feature tiles with real line icons, positioned around the student.
        val pts=arrayOf(floatArrayOf(-151f,-96f),floatArrayOf(0f,-160f),floatArrayOf(151f,-96f),floatArrayOf(-171f,15f),floatArrayOf(171f,15f),floatArrayOf(-132f,125f),floatArrayOf(132f,125f))
        val tints=intArrayOf(0xff8b45ff.toInt(),0xff3777ff.toInt(),0xffffa000.toInt(),0xff00a5e8.toInt(),0xff00b985.toInt(),0xffff5353.toInt(),0xffec4a9a.toInt())
        // Match the website: each icon gently floats up/down at its own
        // fixed anchor, with its own phase and duration (no circular orbit).
        for (i in pts.indices) {
            val period = tileDuration[i]
            val phase = (motionPhase * 6f / period + tilePhase[i] / period) % 1f
            val verticalFloat = (kotlin.math.sin(phase * (Math.PI * 2.0)).toFloat() - 1f) * 6.5f * scale
            val tilt = kotlin.math.sin(phase * (Math.PI * 2.0)).toFloat() * 2f
            val x = cx + pts[i][0] * scale
            val y = cy + pts[i][1] * scale + verticalFloat
            canvas.save()
            canvas.rotate(tilt, x, y)
            tile(canvas, x, y, i, tints[i], scale)
            canvas.restore()
        }
    }
}
