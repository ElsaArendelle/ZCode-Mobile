package com.autosquare.zcode.mobile.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 授权页的公开前端资源在部分网络的 HTTP/2 连接上会被截断。 */
internal object AuthAssets {
    private var assetClient: OkHttpClient? = null

    @Synchronized
    private fun client(context: Context): OkHttpClient = assetClient ?: OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "auth-assets"), 16L * 1024 * 1024))
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .build().also { assetClient = it }

    fun matches(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return uri.scheme == "https" && uri.host == "z-cdn.chatglm.cn" &&
            path.startsWith("/z-ai/frontend/") && (path.endsWith(".js") || path.endsWith(".css"))
    }

    fun load(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame || request.method != "GET" || !matches(request.url)) return null
        val builder = Request.Builder().url(request.url.toString())
        request.requestHeaders["Origin"]?.let { builder.header("Origin", it) }
        return try {
            client(context).newCall(builder.build()).execute().use { response ->
                val body = response.body ?: return null
                if (response.code != 200) return null
                val type = body.contentType() ?: return null
                // 完整接收后再交给 WebView，避免页面执行半截脚本。
                val bytes = body.bytes()
                WebResourceResponse(
                    "${type.type}/${type.subtype}", type.charset()?.name() ?: "UTF-8",
                    response.code, "OK", response.headers.toMap(), bytes.inputStream(),
                )
            }
        } catch (error: IOException) {
            android.util.Log.w("ZCodeWeb", "auth asset ${request.url.lastPathSegment}: ${error.javaClass.simpleName}")
            null // 由 WebView 自身重试并报告最终错误。
        }
    }
}
