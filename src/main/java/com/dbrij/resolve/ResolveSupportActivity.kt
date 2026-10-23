package com.dbrij.resolve

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The support screen: the desk's own support center inside your app.
 *
 *     ResolveSupport.open(context, desk = "rk_...", identity = ResolveIdentity(email, userHash = hashFromYourServer))
 */
object ResolveSupport {
    internal const val EXTRA_DESK = "dbrij.resolve.desk"
    internal const val EXTRA_EMAIL = "dbrij.resolve.email"
    internal const val EXTRA_NAME = "dbrij.resolve.name"
    internal const val EXTRA_EXTERNAL_ID = "dbrij.resolve.externalId"
    internal const val EXTRA_USER_HASH = "dbrij.resolve.userHash"
    internal const val EXTRA_COLOR = "dbrij.resolve.color"

    /** New team replies while the screen is open. Set it before [open]. */
    @JvmStatic
    var onUnread: ((Int) -> Unit)? = null

    @JvmStatic
    @JvmOverloads
    fun open(context: Context, desk: String, identity: ResolveIdentity? = null, color: String? = null) {
        val intent = Intent(context, ResolveSupportActivity::class.java).apply {
            putExtra(EXTRA_DESK, desk)
            identity?.let {
                putExtra(EXTRA_EMAIL, it.email)
                putExtra(EXTRA_NAME, it.name)
                putExtra(EXTRA_EXTERNAL_ID, it.externalId)
                putExtra(EXTRA_USER_HASH, it.userHash)
            }
            putExtra(EXTRA_COLOR, color)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

class ResolveSupportActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var client: ResolveClient
    private var pendingFiles: ValueCallback<Array<Uri>>? = null

    // Screenshots and documents for a ticket: Android's web view cannot pick a file for
    // a page on its own, so the activity does it.
    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pendingFiles?.onReceiveValue(uris.toTypedArray())
        pendingFiles = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val desk = intent.getStringExtra(ResolveSupport.EXTRA_DESK) ?: return finish()
        client = ResolveClient(this, desk)
        val identity = intent.getStringExtra(ResolveSupport.EXTRA_EMAIL)?.let { email ->
            ResolveIdentity(
                email = email,
                name = intent.getStringExtra(ResolveSupport.EXTRA_NAME),
                externalId = intent.getStringExtra(ResolveSupport.EXTRA_EXTERNAL_ID),
                userHash = intent.getStringExtra(ResolveSupport.EXTRA_USER_HASH),
            )
        }
        val url = client.url(identity, color = intent.getStringExtra(ResolveSupport.EXTRA_COLOR))
        val origin = "${url.scheme}://${url.authority}"

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(Bridge(), "DbrijResolveAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (target.startsWith(origin)) return false
                    // A file or a page the team linked opens in the customer's browser.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = callback
                    pickFiles.launch("*/*")
                    return true
                }
            }
        }
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        if (savedInstanceState == null) webView.loadUrl(url.toString()) else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        pendingFiles?.onReceiveValue(null)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** What the page calls: window.DbrijResolveAndroid.postMessage(json). Off the main thread. */
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            val msg = ResolveHostMessage.parse(raw) ?: return
            runOnUiThread {
                when (msg) {
                    is ResolveHostMessage.Close -> finish()
                    is ResolveHostMessage.Unread -> ResolveSupport.onUnread?.invoke(msg.count)
                    is ResolveHostMessage.Session -> client.rememberSession(msg.token)
                }
            }
        }
    }
}
