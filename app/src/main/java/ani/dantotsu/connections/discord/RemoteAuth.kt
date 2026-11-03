package ani.dantotsu.connections.discord

import android.util.Base64
import ani.dantotsu.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Timer
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.concurrent.fixedRateTimer

/**
 * Discord's "scan to log in": the code discord.com's own login page shows, scanned with the Discord
 * app on a phone that's signed in, which then asks there whether to sign this device in. No password
 * typed on a TV.
 *
 * Over Discord's remote-auth gateway: this side sends a fresh public key, proves it holds the private
 * one, and gets a fingerprint to show as the code. Once scanned, the gateway names the account; once
 * confirmed on the phone, a ticket that the API trades for the account's token — encrypted to the key,
 * as everything personal on the way is.
 */
class RemoteAuth(private val listener: Listener) {

    interface Listener {
        /** The code to show: [url] is what the Discord app's scanner reads. */
        fun onCode(url: String)

        /** Scanned by [username]'s Discord app, waiting for them to confirm there. */
        fun onScanned(username: String, avatarUrl: String?)

        /** Confirmed: the account's token. */
        fun onToken(token: String)

        /** Turned down on the phone, timed out, or failed: a new code is needed. */
        fun onFailed(reason: Reason)
    }

    enum class Reason { CANCELLED, TIMED_OUT, CAPTCHA, ERROR }

    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var socket: WebSocket? = null
    private var heartbeat: Timer? = null
    @Volatile
    private var done = false

    fun start() {
        val request = Request.Builder()
            .url(GATEWAY)
            .header("Origin", "https://discord.com")
            .header("User-Agent", USER_AGENT)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("RemoteAuth: gateway failed: ${t.message}")
                fail(Reason.ERROR)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // The gateway closes a code nobody scanned after a couple of minutes.
                if (!done) fail(Reason.TIMED_OUT)
            }
        })
    }

    fun stop() {
        done = true
        heartbeat?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    private fun handle(ws: WebSocket, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("op")) {
            "hello" -> {
                val interval = message.optLong("heartbeat_interval", 41_250L)
                heartbeat?.cancel()
                heartbeat = fixedRateTimer("discord-ra", daemon = true, initialDelay = interval, period = interval) {
                    ws.send(JSONObject().put("op", "heartbeat").toString())
                }
                val publicKey = Base64.encodeToString(keys.public.encoded, Base64.NO_WRAP)
                ws.send(JSONObject().put("op", "init").put("encoded_public_key", publicKey).toString())
            }
            "nonce_proof" -> {
                val nonce = decrypt(message.optString("encrypted_nonce")) ?: return fail(Reason.ERROR)
                val proof = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(nonce),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                ws.send(JSONObject().put("op", "nonce_proof").put("proof", proof).toString())
            }
            "pending_remote_init" -> listener.onCode("https://discord.com/ra/" + message.optString("fingerprint"))
            "pending_ticket" -> {
                // "id:discriminator:avatar hash:username"
                val user = decrypt(message.optString("encrypted_user_payload"))?.toString(Charsets.UTF_8)?.split(':')
                if (user != null && user.size >= 4) {
                    val avatar = user[2].takeIf { it.isNotBlank() && it != "0" }
                        ?.let { "https://cdn.discordapp.com/avatars/${user[0]}/$it.png?size=128" }
                    listener.onScanned(user.drop(3).joinToString(":"), avatar)
                }
            }
            "pending_login" -> redeem(message.optString("ticket"))
            "cancel" -> fail(Reason.CANCELLED)
        }
    }

    /** Trades the gateway's ticket for the token, which comes back encrypted to this side's key. */
    private fun redeem(ticket: String) {
        runCatching {
            val body = JSONObject().put("ticket", ticket).toString().toRequestBody("application/json".toMediaType())
            val response = client.newCall(
                Request.Builder()
                    .url("https://discord.com/api/v9/users/@me/remote-auth/login")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
            ).execute()
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.has("captcha_key") || json.has("captcha_sitekey")) return fail(Reason.CAPTCHA)
            val token = decrypt(json.optString("encrypted_token"))?.toString(Charsets.UTF_8)
            if (token.isNullOrBlank()) return fail(Reason.ERROR)
            done = true
            stop()
            listener.onToken(token)
        }.onFailure {
            Logger.log("RemoteAuth: login failed: ${it.message}")
            fail(Reason.ERROR)
        }
    }

    private fun fail(reason: Reason) {
        if (done) return
        done = true
        stop()
        listener.onFailed(reason)
    }

    /** RSA-OAEP with SHA-256 throughout — the MGF1 digest too, which Android's default leaves at SHA-1. */
    private fun decrypt(base64: String): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, keys.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        cipher.doFinal(Base64.decode(base64, Base64.DEFAULT))
    }.onFailure { Logger.log("RemoteAuth: decrypt failed: ${it.message}") }.getOrNull()

    private companion object {
        const val GATEWAY = "wss://remote-auth-gateway.discord.gg/?v=2"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(0, TimeUnit.SECONDS).build()
        }
    }
}
