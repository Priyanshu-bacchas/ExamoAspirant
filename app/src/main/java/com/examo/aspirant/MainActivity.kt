package com.examo.aspirant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val websiteUrl = getString(R.string.examo_website_url)
        if (websiteUrl.contains("YOUR-EXAMO-WEBSITE-URL")) {
            finish()
            return
        }

        val uri = Uri.parse(websiteUrl)
        try {
            // Use the real Chrome browser engine and its persistent origin storage.
            // This avoids WebView's isolated sessionStorage problems with Firebase OAuth
            // and keeps the login/OTP page alive while the user switches to their email app.
            val customTabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setToolbarColor(android.graphics.Color.rgb(30, 55, 112))
                .setUrlBarHidingEnabled(false)
                .build()
            customTabs.launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } finally {
            finish()
        }
    }
}
