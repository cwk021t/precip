package com.cwk021t.precip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private var pendingCsv: String? = null

    companion object {
        const val REQ_NOTIF = 1
        const val REQ_GEO = 2
        const val REQ_CSV = 3
        const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannel(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)

        // Android 15+ draws edge-to-edge; keep content clear of the status and nav bars
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_GEO
                    )
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(APP_URL)

        Scheduler.schedule(this)
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_GEO -> {
                geoCallback?.invoke(geoOrigin, granted, false)
                geoCallback = null
            }
            REQ_NOTIF -> web.evaluateJavascript("window.onNotifPermission&&window.onNotifPermission($granted)", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CSV && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val csv = pendingCsv ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not save CSV", Toast.LENGTH_SHORT).show()
            }
            pendingCsv = null
        }
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.onNotifPermission&&window.onNotifPermission()", null)
    }

    /** Methods callable from the web UI as window.Android.* */
    inner class Bridge {
        @JavascriptInterface
        fun saveConfig(json: String) {
            Prefs.saveConfig(this@MainActivity, json)
            Scheduler.schedule(this@MainActivity)
        }

        @JavascriptInterface
        fun notificationsAllowed(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun requestNotifications() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                }
            }
        }

        @JavascriptInterface
        fun testNotification() {
            Notifier.show(this@MainActivity, 99, "Precip Log", "Notifications are working. You'll get rain and snow alerts here.")
        }

        @JavascriptInterface
        fun saveCsv(csv: String) {
            runOnUiThread {
                pendingCsv = csv
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, "precip-log.csv")
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_CSV)
            }
        }
    }
}
