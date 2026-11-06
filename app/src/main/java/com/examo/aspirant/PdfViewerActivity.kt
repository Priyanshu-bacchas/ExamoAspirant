package com.examo.aspirant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class PdfViewerActivity : Activity() {
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private val executor = Executors.newFixedThreadPool(2)

    companion object {
        private const val EXTRA_PATH = "pdf_path"
        private const val EXTRA_NAME = "pdf_name"
        fun createIntent(context: Context, path: String, name: String) =
            Intent(context, PdfViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path).putExtra(EXTRA_NAME, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank() || !File(path).isFile) {
            android.widget.Toast.makeText(this, "PDF file is not available.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        try {
            descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Unable to read PDF: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(241, 245, 249))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_NAME) ?: "PDF Document"
            textSize = 16f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfViewerActivity)
            adapter = PageAdapter()
            itemAnimator = null
            setBackgroundColor(Color.rgb(226, 232, 240))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)
        override fun getItemCount() = renderer?.pageCount ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            return Holder(image).also { it.itemView.layoutParams = RecyclerView.LayoutParams(-1, -2) }
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.image.setImageDrawable(null)
            holder.image.tag = position
            val width = (resources.displayMetrics.widthPixels - dp(20)).coerceAtLeast(dp(240))
            executor.execute {
                val bitmap = try {
                    synchronized(lock) {
                        val pdf = renderer ?: return@execute
                        val page = pdf.openPage(position)
                        try {
                            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        } finally { page.close() }
                    }
                } catch (_: Exception) { null }
                runOnUiThread {
                    if (!isFinishing && holder.bindingAdapterPosition == position && holder.image.tag == position) {
                        holder.image.setImageBitmap(bitmap)
                    } else bitmap?.recycle()
                }
            }
        }
        override fun onViewRecycled(holder: Holder) {
            (holder.image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            holder.image.setImageDrawable(null)
            super.onViewRecycled(holder)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { descriptor?.close() } catch (_: Exception) {}
            renderer = null
            descriptor = null
        }
        super.onDestroy()
    }
}
