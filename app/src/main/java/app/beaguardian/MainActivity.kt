package app.beaguardian

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = fileCallback
        fileCallback = null
        if (cb != null) {
            var result: Array<Uri>? = null
            if (r.resultCode == RESULT_OK) {
                val data = r.data?.data
                result = if (data != null) arrayOf(data) else cameraUri?.let { arrayOf(it) }
            }
            cb.onReceiveValue(result)
        }
    }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = hasFineLocation()
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (cb != null && origin != null) cb.invoke(origin, ok, false)
        if (!ok) {
            Toast.makeText(this, "Please allow Location for this app in Settings", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
            }
        }
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setGeolocationEnabled(true)

        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val own = Uri.parse(BuildConfig.BASE_URL)
                if (uri.host == own.host) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasFineLocation()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                val initial = ArrayList<Intent>()
                try {
                    val dir = File(cacheDir, "images").apply { mkdirs() }
                    val f = File.createTempFile("photo_", ".jpg", dir)
                    cameraUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                        clipData = ClipData.newRawUri("", cameraUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    initial.add(cam)
                } catch (e: Exception) {
                }
                val ch = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, pick)
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, initial.toTypedArray())
                }
                return try {
                    chooser.launch(ch)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    cb.onReceiveValue(null)
                    false
                }
            }
        }

        val wanted = ArrayList<String>()
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.launch(wanted.toTypedArray())

        if (savedInstanceState == null) web.loadUrl(BuildConfig.BASE_URL)
        applyDuty(Prefs.token(this), Prefs.onDuty(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        web.loadUrl(BuildConfig.BASE_URL)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        web.onResume()
    }

    override fun onPause() {
        visible = false
        web.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun applyDuty(token: String, onDuty: Boolean) {
        val i = Intent(this, DutyService::class.java)
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (token.isNotEmpty() && onDuty && hasLocation) {
            try {
                ContextCompat.startForegroundService(this, i)
                askBatteryAndFullScreen()
            } catch (e: Exception) {
            }
        } else {
            stopService(i)
        }
    }

    private fun askBatteryAndFullScreen() {
        val seen = getSharedPreferences("bag", MODE_PRIVATE)
        if (seen.getBoolean("askedSettings", false)) return
        seen.edit().putBoolean("askedSettings", true).apply()
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun setSession(token: String?, onDuty: Boolean) {
            val t = token ?: ""
            if (t.isEmpty()) Prefs.clear(this@MainActivity) else Prefs.set(this@MainActivity, t, onDuty)
            runOnUiThread { applyDuty(t, onDuty) }
        }
    }

    companion object {
        @Volatile
        var visible = false
    }
}
