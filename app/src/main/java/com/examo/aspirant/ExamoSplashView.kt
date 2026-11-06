package com.examo.aspirant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        val compact = resources.displayMetrics.heightPixels / resources.displayMetrics.density < 760f
        val horizontalPad = if (compact) 18 else 22
        setPadding(dp(horizontalPad), dp(if (compact) 16 else 22), dp(horizontalPad), dp(if (compact) 10 else 14))
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
        addView(header, LayoutParams(-1, dp(if (compact) 48 else 54)))

        val art = StudentOrbitView(context)
        val artParams = LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(if (compact) 0 else 4)
            bottomMargin = dp(if (compact) 0 else 3)
            weight = if (compact) 0.82f else 1f
        }
        addView(art, artParams)

        addView(label("Your exams, beautifully organised.", if (compact) 18f else 19f, Color.rgb(29,48,91), true),
            LayoutParams(-1, dp(if (compact) 32 else 36)))
        addView(label("Exam forms, schedules, preparation and subjects — everything\na student needs, in one place.", if (compact) 11f else 12f, Color.rgb(76,94,127)),
            LayoutParams(-1, dp(if (compact) 42 else 46)))

        val chips = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = HORIZONTAL }
        listOf("Exam Forms", "Schedules", "Preparation").forEach { text ->
            val chip = label(text, 10f, Color.rgb(36,62,112), true).apply {
                background = rounded(Color.rgb(247,250,255), 24)
                setPadding(dp(13), dp(8), dp(13), dp(8))
            }
            val lp = LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) }
            chips.addView(chip, lp)
        }
        addView(chips, LayoutParams(-1, dp(if (compact) 36 else 40)))

        // A visible grab-handle makes the swipe gesture obvious.
        val swipeArea = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(if (compact) 3 else 5), 0, 0)
        }
        val handle = View(context).apply {
            background = rounded(Color.rgb(104, 132, 181), 8)
            alpha = 0.72f
        }
        swipeArea.addView(handle, LayoutParams(dp(54), dp(4)).apply {
            bottomMargin = dp(7)
        })
        val swipe = label("Swipe up to get started  ↑", if (compact) 12f else 13f, Color.rgb(31,64,132), true).apply {
            background = rounded(Color.argb(55,255,255,255), 22)
            setPadding(dp(18), dp(if (compact) 7 else 8), dp(18), dp(if (compact) 7 else 8))
            alpha = 0.95f
        }
        swipeArea.addView(swipe, LayoutParams(-2, dp(if (compact) 34 else 38)))
        addView(swipeArea, LayoutParams(-1, dp(if (compact) 45 else 50)))
        post {
            handle.animate().alpha(0.35f).setDuration(850).withEndAction {
                handle.animate().alpha(0.85f).setDuration(850).start()
            }.start()
        }
    }
}

private class StudentOrbitView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.49f
        val scale = (width.coerceAtMost(height).toFloat() / 430f).coerceAtLeast(0.65f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * scale; p.color = Color.rgb(177,207,250)
        canvas.drawCircle(cx, cy, 165f*scale, p); p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f,5f),0f)
        canvas.drawCircle(cx, cy, 185f*scale, p); p.pathEffect = null

        p.style = Paint.Style.FILL
        p.color = Color.rgb(255,255,255); p.setShadowLayer(15f*scale,0f,6f*scale,0x22365C9A)
        canvas.drawCircle(cx,cy,132f*scale,p); p.clearShadowLayer()

        // Student shoulders and shirt
        p.color = Color.rgb(28,57,112)
        val shoulders = Path().apply {
            moveTo(cx-88*scale,cy+119*scale); cubicTo(cx-80*scale,cy+60*scale,cx-42*scale,cy+48*scale,cx,cy+50*scale)
            cubicTo(cx+46*scale,cy+48*scale,cx+80*scale,cy+65*scale,cx+88*scale,cy+119*scale); close()
        }
        canvas.drawPath(shoulders,p)
        p.color = Color.rgb(245,248,255)
        val shirt=Path().apply{moveTo(cx-25*scale,cy+55*scale);lineTo(cx,cy+105*scale);lineTo(cx+25*scale,cy+55*scale);close()}
        canvas.drawPath(shirt,p)

        // Neck and face
        p.color=Color.rgb(245,190,151); canvas.drawRoundRect(cx-13*scale,cy+18*scale,cx+13*scale,cy+62*scale,8f*scale,8f*scale,p)
        canvas.drawCircle(cx,cy-7*scale,37f*scale,p)
        p.color=Color.rgb(39,58,91); canvas.drawCircle(cx-13*scale,cy-12*scale,3f*scale,p); canvas.drawCircle(cx+13*scale,cy-12*scale,3f*scale,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=2.5f*scale;p.strokeCap=Paint.Cap.ROUND
        p.color=Color.rgb(39,58,91);canvas.drawArc(cx-13*scale,cy-2*scale,cx+13*scale,cy+15*scale,15f,150f,false,p)
        p.style=Paint.Style.FILL

        // Graduation cap
        p.color=Color.rgb(17,45,91)
        val cap=Path().apply{moveTo(cx-70*scale,cy-48*scale);lineTo(cx,cy-75*scale);lineTo(cx+70*scale,cy-48*scale);lineTo(cx,cy-22*scale);close()}
        canvas.drawPath(cap,p)
        p.color=Color.rgb(238,170,47);p.strokeWidth=3f*scale
        canvas.drawLine(cx+45*scale,cy-48*scale,cx+58*scale,cy-5*scale,p)
        canvas.drawCircle(cx+58*scale,cy-2*scale,5f*scale,p)

        // Floating feature tiles
        val points=arrayOf(floatArrayOf(-150f,-95f),floatArrayOf(0f,-160f),floatArrayOf(150f,-95f),floatArrayOf(-170f,15f),floatArrayOf(170f,15f),floatArrayOf(-130f,125f),floatArrayOf(130f,125f))
        val glyphs=arrayOf("▣","◷","▦","▱","▯","✎","☆")
        val colors=intArrayOf(0xff8b45ff.toInt(),0xff3777ff.toInt(),0xffffa000.toInt(),0xff00a5e8.toInt(),0xff00b985.toInt(),0xffff5353.toInt(),0xffec4a9a.toInt())
        for(i in points.indices){
            val x=cx+points[i][0]*scale; val y=cy+points[i][1]*scale
            p.color=Color.WHITE;p.setShadowLayer(9f*scale,0f,4f*scale,0x22365C9A)
            canvas.drawRoundRect(x-25*scale,y-25*scale,x+25*scale,y+25*scale,14f*scale,14f*scale,p);p.clearShadowLayer()
            p.color=colors[i];p.textSize=25f*scale;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER
            canvas.drawText(glyphs[i],x,y+8*scale,p)
        }
    }
}
