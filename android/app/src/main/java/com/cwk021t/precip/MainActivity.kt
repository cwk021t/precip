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
        // WebView ignores its own padding, so wrap it in a container that we inset instead
        val root = android.widget.FrameLayout(this)
        root.addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Android 15+ draws edge-to-edge; keep content clear of the status bar, gesture bar and keyboard
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
            root.requestApplyInsets()
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

        /** Native HTTP GET for the weather APIs (avoids WebView CORS/network quirks). */
        @JavascriptInterface
        fun httpGet(url: String): String {
            val out = org.json.JSONObject()
            val host = try { java.net.URL(url).host } catch (e: Exception) { "" }
            if (!(host.endsWith("open-meteo.com") || host == "api.weather.gov")) {
                return out.put("status", 0).put("error", "Blocked host").toString()
            }
            return try {
                val con = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                con.setRequestProperty("User-Agent", "PrecipLog (github.com/cwk021t/precip)")
                con.setRequestProperty("Accept", "application/geo+json, application/json")
                con.connectTimeout = 20000
                con.readTimeout = 30000
                val code = con.responseCode
                val body = (if (code < 400) con.inputStream else con.errorStream)?.bufferedReader()?.readText() ?: ""
                con.disconnect()
                out.put("status", code).put("body", body).toString()
            } catch (e: Exception) {
                out.put("status", 0).put("error", e.javaClass.simpleName + ": " + (e.message ?: "")).toString()
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
