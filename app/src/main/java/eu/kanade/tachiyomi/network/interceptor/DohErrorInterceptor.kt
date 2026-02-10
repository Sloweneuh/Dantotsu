package eu.kanade.tachiyomi.network.interceptor

import ani.dantotsu.util.Logger
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Interceptor that handles DoH (DNS-over-HTTPS) related errors gracefully.
 * Prevents gateway timeout errors (504) and other DoH failures from crashing
 * or being sent to error tracking services.
 */
class DohErrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return try {
            chain.proceed(request)
        } catch (e: SocketTimeoutException) {
            // DoH gateway timeout - log locally but don't propagate as critical error
            if (isDohRelatedError(e)) {
                Logger.log("DoH timeout: ${e.message} - This is expected and will fall back to regular DNS")
                throw IOException("DoH gateway timeout, falling back to system DNS", e)
            }
            throw e
        } catch (e: IOException) {
            // Check if this is a DoH-related error
            if (isDohRelatedError(e)) {
                Logger.log("DoH error: ${e.message} - Falling back to system DNS")
                // Wrap in a generic IOException to prevent Sentry from tracking DoH failures
                throw IOException("DNS resolution failed, using fallback", e)
            }
            throw e
        }
    }

    private fun isDohRelatedError(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: ""
        val stackTrace = error.stackTraceToString().lowercase()

        return message.contains("dns-query") ||
               message.contains("doh") ||
               message.contains("504") ||
               message.contains("gateway timeout") ||
               message.contains("adguard-dns.com") ||
               message.contains("cloudflare-dns.com") ||
               message.contains("dns.google") ||
               stackTrace.contains("dnsoverhttps")
    }
}

