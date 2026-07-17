package com.blankspeaker.grld

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * RFC 8628 device-code login (same as `grok login --device-auth`).
 * Opens Chrome for consent; app polls until approved — no in-app WebView.
 */
object DeviceAuth {

    private const val TAG = "GRLD-DeviceAuth"
    private const val GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private const val DEVICE_CODE_URL = "${OidcSession.ISSUER}/oauth2/device/code"
    private const val TOKEN_URL = "${OidcSession.ISSUER}/oauth2/token"

    private val SCOPES = listOf(
        "openid", "profile", "email", "offline_access",
        "grok-cli:access", "api:access",
        "conversations:read", "conversations:write"
    )

    data class Pending(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val intervalSec: Int,
        val expiresInSec: Long
    ) {
        /** Prefer complete URI (pre-fills user code in the browser). */
        val browserUrl: String
            get() = verificationUriComplete?.takeIf { it.isNotBlank() } ?: verificationUri
    }

    sealed class PollResult {
        data class Success(val tokens: OidcSession.Tokens) : PollResult()
        data object PendingAuth : PollResult()
        data object SlowDown : PollResult()
        data class Denied(val message: String) : PollResult()
        data class Expired(val message: String) : PollResult()
        data class Error(val message: String) : PollResult()
    }

    fun requestCode(): Pending {
        val body = form(
            "client_id" to OidcSession.CLIENT_ID,
            "scope" to SCOPES.joinToString(" "),
            "referrer" to "grok-build"
        )
        val json = post(DEVICE_CODE_URL, body, surface = "ui")
        val deviceCode = json.getString("device_code")
        val userCode = json.getString("user_code")
        val verificationUri = json.getString("verification_uri")
        val complete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() }
        val interval = if (json.has("interval")) json.getInt("interval") else 5
        val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else 1800L
        Log.d(TAG, "device code ok user=$userCode expires=${expiresIn}s")
        return Pending(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = complete,
            intervalSec = interval.coerceAtLeast(1),
            expiresInSec = expiresIn.coerceAtLeast(60L)
        )
    }

    fun pollOnce(pending: Pending): PollResult {
        val body = form(
            "grant_type" to GRANT,
            "device_code" to pending.deviceCode,
            "client_id" to OidcSession.CLIENT_ID
        )
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-grok-client-version", "grld-android-oauth-test")
            setRequestProperty("x-grok-client-surface", "ui")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            if (code in 200..299) {
                val json = JSONObject(raw)
                val access = json.getString("access_token")
                val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else 6 * 3600L
                return PollResult.Success(
                    OidcSession.Tokens(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                        userId = OidcSession.peekUserId(access).orEmpty(),
                        email = null
                    )
                )
            }
            val err = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return PollResult.Error("HTTP $code: ${raw.take(200)}")
            }
            val errCode = err.optString("error", "error")
            val desc = err.optString("error_description", errCode)
            return when (errCode) {
                "authorization_pending" -> PollResult.PendingAuth
                "slow_down" -> PollResult.SlowDown
                "access_denied" -> PollResult.Denied(desc)
                "expired_token" -> PollResult.Expired(desc)
                else -> PollResult.Error(desc)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, formBody: String, surface: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-grok-client-version", "grld-android-oauth-test")
            setRequestProperty("x-grok-client-surface", surface)
        }
        try {
            conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            if (code !in 200..299) error("Device code HTTP $code: ${raw.take(300)}")
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
}
