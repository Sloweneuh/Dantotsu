package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Nullifies the transparent gzip of [okhttp3.internal.http.BridgeInterceptor], so that gzip and
 * Brotli can be handled explicitly by [okhttp3.brotli.BrotliInterceptor] running *after* it as a
 * network interceptor.
 *
 * **No longer used by the app.** `NetworkHelper` now adds Brotli as an application interceptor,
 * which sets its own `Accept-Encoding` before Bridge ever runs and so needs nothing undone
 * afterwards — and extensions built against lib 1.6 explicitly refuse a default client that carries
 * this alongside them.
 *
 * Kept because extensions link against this app's classes at runtime: one that references this
 * directly would die with `NoClassDefFoundError` if it disappeared. Don't add it back to the shared
 * client.
 */
class IgnoreGzipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.header("Accept-Encoding") == "gzip") {
            request = request.newBuilder().removeHeader("Accept-Encoding").build()
        }
        return chain.proceed(request)
    }
}
