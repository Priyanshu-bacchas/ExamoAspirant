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
        setPadding(dp(horizontalPad), dp(if (veryShort) 10 else if (compact) 14 else 20), dp(horizontalPad), dp(if (veryShort) 6 else 10))
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

        val art = StudentOrbitView(context)
        val artParams = LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(if (compact) 0 else 4)
            bottomMargin = dp(if (compact) 0 else 3)
            weight = if (veryShort) 0.42f else if (compact) 0.52f else 0.60f
        }
        addView(art, artParams)

        addView(label("Your exams, beautifully organised.", if (veryShort) 16f else if (compact) 17f else 19f, Color.rgb(29,48,91), true),
            LayoutParams(-1, dp(if (veryShort) 28 else if (compact) 31 else 36)))
        addView(label("Exam forms, schedules, preparation and subjects — everything\na student needs, in one place.", if (veryShort) 10f else if (compact) 11f else 12f, Color.rgb(76,94,127)),
            LayoutParams(-1, dp(if (veryShort) 38 else if (compact) 42 else 46)))

        val chips = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = HORIZONTAL }
        listOf("Exam Forms", "Schedules", "Preparation").forEach { text ->
            val chip = label(text, 10f, Color.rgb(36,62,112), true).apply {
                background = rounded(Color.rgb(247,250,255), 24)
                setPadding(dp(13), dp(8), dp(13), dp(8))
            }
            val lp = LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
            chips.addView(chip, lp)
        }
        addView(chips, LayoutParams(-1, dp(if (veryShort) 32 else if (compact) 36 else 40)))

        // A visible grab-handle makes the swipe gesture obvious.
        val swipeArea = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(if (veryShort) 1 else 3), 0, 0)
        }
        val handle = View(context).apply {
            background = rounded(Color.rgb(70, 96, 151), 8)
            alpha = 0.95f
        }
        swipeArea.addView(handle, LayoutParams(dp(54), dp(4)).apply {
            bottomMargin = dp(7)
        })
        val swipe = label("Swipe up to get started  ↑", if (compact) 12f else 13f, Color.rgb(31,64,132), true).apply {
            background = rounded(Color.argb(55,255,255,255), 22)
            setPadding(dp(18), dp(if (compact) 7 else 8), dp(18), dp(if (compact) 7 else 8))
            alpha = 0.95f
        }
        swipeArea.addView(swipe, LayoutParams(-2, dp(if (veryShort) 30 else if (compact) 34 else 38)))
        addView(swipeArea, LayoutParams(-1, dp(if (veryShort) 38 else if (compact) 45 else 50)))
        post {
            handle.animate().alpha(0.35f).setDuration(850).withEndAction {
                handle.animate().alpha(0.85f).setDuration(850).start()
            }.start()
        }
    }
}

private class StudentOrbitView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var orbitAngle = 0f
    private var orbitAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (orbitAnimator == null) {
            orbitAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 12000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    orbitAngle = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        orbitAnimator?.cancel()
        orbitAnimator = null
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
        val cy=height*.49f
        val scale=(width.coerceAtMost(height).toFloat()/430f).coerceIn(.55f,1.12f)
        // Soft orbit rings and ambient dots
        p.style=Paint.Style.STROKE; p.strokeWidth=1.3f*scale; p.color=Color.rgb(188,211,247)
        canvas.drawCircle(cx,cy,166f*scale,p)
        p.pathEffect=android.graphics.DashPathEffect(floatArrayOf(2.5f*scale,4.5f*scale),0f)
        canvas.drawCircle(cx,cy,187f*scale,p); p.pathEffect=null
        p.style=Paint.Style.FILL
        val dots=arrayOf(floatArrayOf(-112f,-143f),floatArrayOf(146f,-94f),floatArrayOf(-169f,40f),floatArrayOf(170f,50f),floatArrayOf(-8f,184f))
        dots.forEachIndexed { i,d ->
            p.color=if(i%2==0) Color.rgb(143,190,249) else Color.rgb(117,174,245)
            canvas.drawCircle(cx+d[0]*scale,cy+d[1]*scale,(if(i==4)4f else 2.6f)*scale,p)
        }
        // Central soft blue halo
        p.color=Color.rgb(222,235,255); p.setShadowLayer(18f*scale,0f,5f*scale,0x22365C9A)
        canvas.drawCircle(cx,cy,132f*scale,p); p.clearShadowLayer()
        // Body with a gentle navy-to-blue fade
        val body=Path().apply {
            moveTo(cx-88*scale,cy+119*scale)
            cubicTo(cx-84*scale,cy+65*scale,cx-50*scale,cy+47*scale,cx-22*scale,cy+49*scale)
            lineTo(cx+22*scale,cy+49*scale)
            cubicTo(cx+57*scale,cy+48*scale,cx+84*scale,cy+68*scale,cx+88*scale,cy+119*scale)
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
        canvas.drawOval(cx-34*scale,cy-47*scale,cx+34*scale,cy+31*scale,p)
        p.color=Color.rgb(255,207,190)
        canvas.drawCircle(cx-22*scale,cy+8*scale,6f*scale,p); canvas.drawCircle(cx+22*scale,cy+8*scale,6f*scale,p)
        p.color=Color.rgb(35,54,87)
        canvas.drawCircle(cx-13*scale,cy-12*scale,2.8f*scale,p); canvas.drawCircle(cx+13*scale,cy-12*scale,2.8f*scale,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=2.3f*scale; p.strokeCap=Paint.Cap.ROUND
        canvas.drawArc(cx-13*scale,cy-1*scale,cx+13*scale,cy+16*scale,15f,150f,false,p); p.style=Paint.Style.FILL
        // Graduation cap with gold tassel
        p.color=Color.rgb(17,43,88)
        val cap=Path().apply { moveTo(cx-82*scale,cy-49*scale); lineTo(cx,cy-78*scale); lineTo(cx+82*scale,cy-49*scale); lineTo(cx,cy-18*scale); close() }
        canvas.drawPath(cap,p)
        val capBand=Path().apply { moveTo(cx-55*scale,cy-38*scale); quadTo(cx,cy-14*scale,cx+55*scale,cy-38*scale); lineTo(cx+48*scale,cy-18*scale); quadTo(cx,cy+1*scale,cx-48*scale,cy-18*scale); close() }
        canvas.drawPath(capBand,p)
        line(canvas,cx+36*scale,cy-48*scale,cx+53*scale,cy-3*scale,Color.rgb(245,177,47),2.6f,scale)
        p.color=Color.rgb(245,177,47); canvas.drawCircle(cx+53*scale,cy-1*scale,5f*scale,p)
        // Seven feature tiles with real line icons, positioned around the student.
        val pts=arrayOf(floatArrayOf(-151f,-96f),floatArrayOf(0f,-160f),floatArrayOf(151f,-96f),floatArrayOf(-171f,15f),floatArrayOf(171f,15f),floatArrayOf(-132f,125f),floatArrayOf(132f,125f))
        val tints=intArrayOf(0xff8b45ff.toInt(),0xff3777ff.toInt(),0xffffa000.toInt(),0xff00a5e8.toInt(),0xff00b985.toInt(),0xffff5353.toInt(),0xffec4a9a.toInt())
        // Rotate the complete seven-tile constellation around the student.
        // Keep each tile upright while its CENTER travels a full 360-degree orbit.
        val radians = Math.toRadians(orbitAngle.toDouble())
        val cosA = kotlin.math.cos(radians).toFloat()
        val sinA = kotlin.math.sin(radians).toFloat()
        for (i in pts.indices) {
            val ox = pts[i][0] * scale
            val oy = pts[i][1] * scale
            val orbitX = ox * cosA - oy * sinA
            val orbitY = ox * sinA + oy * cosA
            tile(canvas, cx + orbitX, cy + orbitY, i, tints[i], scale)
        }
    }
}
