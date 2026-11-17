package it.matteo.veneziahotel

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Solo collaudo (build debug): una WebView come quelle delle ricerche, tenuta aperta 20 minuti
 * per guidarla dal Mac con DevTools e capire come leggere un sito nuovo.
 */
private var probe: WebView? = null

@SuppressLint("SetJavaScriptEnabled")
fun startProbe(ctx: Context) {
    if (probe != null) return
    CookieManager.getInstance().setAcceptCookie(true)
    probe = WebView(ctx.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = DESKTOP_UA
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webViewClient = WebViewClient()
        measure(1366, 3000); layout(0, 0, 1366, 3000)
        loadUrl("about:blank")
    }
    Handler(Looper.getMainLooper()).postDelayed({ probe?.destroy(); probe = null }, 20 * 60_000L)
}
