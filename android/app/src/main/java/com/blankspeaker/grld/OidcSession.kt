package com.blankspeaker.grld

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.max

/**
 * OAuth2 / OIDC session for Grok Build–style auth (auth.x.ai + CLI client id).
 * Tokens live in SharedPreferences; no permanent WebView required after login.
 */
object OidcSession {

    private const val TAG = "GRLD-Oidc"
    private const val PREFS = "grld_oidc"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_ISSUER = "issuer"
    private const val KEY_CLIENT_ID = "client_id"

    /** Same public client as Grok Build CLI (prototype / test). */
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val ISSUER = "https://auth.x.ai"
    /** Loopback redirect — intercepted in WebView (never actually loads). */
    const val REDIRECT_URI = "http://127.0.0.1:8742/callback"
    const val TOKEN_HEADER = "xai-grok-cli"
    const val BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"

    private val SCOPES = listOf(
        "openid", "profile", "email", "offline_access",
        "grok-cli:access", "api:access",
        "conversations:read", "conversations:write"
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val userId: String,
        val email: String?
    ) {
        val isPresent: Boolean get() = accessToken.isNotEmpty()
        fun isExpired(skewMs: Long = 60_000L): Boolean =
            System.currentTimeMillis() >= expiresAtMs - skewMs
    }

    data class Discovery(
        val authorizationEndpoint: String,
        val tokenEndpoint: String
    )

    data class Pkce(
        val codeVerifier: String,
        val codeChallenge: String,
        val state: String,
        val nonce: String
    )

    fun hasTokens(context: Context): Boolean = load(context)?.isPresent == true

    fun load(context: Context): Tokens? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = p.getString(KEY_ACCESS, null)?.trim().orEmpty()
        if (access.isEmpty()) return null
        return Tokens(
            accessToken = access,
            refreshToken = p.getString(KEY_REFRESH, null),
            expiresAtMs = p.getLong(KEY_EXPIRES_AT, 0L),
            userId = p.getString(KEY_USER_ID, null).orEmpty().ifEmpty {
                peekUserId(access).orEmpty()
            },
            email = p.getString(KEY_EMAIL, null)
        )
    }

    fun save(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSec: Long?,
        userId: String?,
        email: String?
    ) {
        val expiresAt = System.currentTimeMillis() +
            max(60L, expiresInSec ?: (6 * 3600L)) * 1000L
        val uid = userId?.takeIf { it.isNotBlank() }
            ?: peekUserId(accessToken)
            ?: ""
        // commit() so tokens are on disk before we jump back from Chrome
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, uid)
            .putString(KEY_EMAIL, email)
            .putString(KEY_ISSUER, ISSUER)
            .putString(KEY_CLIENT_ID, CLIENT_ID)
            .commit()
        Log.i(TAG, "saved tokens userId=${uid.take(8)}… expiresIn=${expiresInSec}s")
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun generatePkce(): Pkce {
        val verifier = randomUrlSafe(32)
        val challenge = sha256UrlSafe(verifier)
        return Pkce(
            codeVerifier = verifier,
            codeChallenge = challenge,
            state = randomUrlSafe(16),
            nonce = randomUrlSafe(16)
        )
    }

    fun discover(): Discovery {
        val url = "$ISSUER/.well-known/openid-configuration"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            if (code !in 200..299) error("OIDC discovery HTTP $code: ${body.take(200)}")
            val json = JSONObject(body)
            return Discovery(
                authorizationEndpoint = json.getString("authorization_endpoint"),
                tokenEndpoint = json.getString("token_endpoint")
            )
        } finally {
            conn.disconnect()
        }
    }

    fun buildAuthorizeUrl(discovery: Discovery, pkce: Pkce): String {
        val scope = SCOPES.joinToString(" ")
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return buildString {
            append(discovery.authorizationEndpoint)
            append("?response_type=code")
            append("&client_id=").append(enc(CLIENT_ID))
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(scope))
            append("&code_challenge=").append(enc(pkce.codeChallenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(pkce.state))
            append("&nonce=").append(enc(pkce.nonce))
            append("&referrer=").append(enc("grok-build"))
        }
    }

    fun exchangeCode(tokenEndpoint: String, code: String, codeVerifier: String): Tokens {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to codeVerifier
        )
        val json = postForm(tokenEndpoint, body)
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token", null).takeIf { !it.isNullOrBlank() }
        val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else 6 * 3600L
        val uid = peekUserId(access).orEmpty()
        val email = peekEmail(access)
        return Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
            userId = uid,
            email = email
        )
    }

    /** Refresh if needed; returns valid access token or null. */
    fun ensureAccessToken(context: Context): String? {
        val t = load(context) ?: return null
        if (!t.isExpired()) return t.accessToken
        val refresh = t.refreshToken ?: return null
        return try {
            val discovery = discover()
            val body = form(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to CLIENT_ID
            )
            val json = postForm(discovery.tokenEndpoint, body)
            val access = json.getString("access_token")
            val newRefresh = json.optString("refresh_token", null)
                .takeIf { !it.isNullOrBlank() } ?: refresh
            val expiresIn = if (json.has("expires_in")) json.getLong("expires_in") else 6 * 3600L
            save(
                context,
                accessToken = access,
                refreshToken = newRefresh,
                expiresInSec = expiresIn,
                userId = t.userId.ifEmpty { peekUserId(access) },
                email = t.email ?: peekEmail(access)
            )
            access
        } catch (e: Exception) {
            Log.w(TAG, "token refresh failed", e)
            null
        }
    }

    private fun postForm(url: String, formBody: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-grok-client-version", "grld-android-oauth-test")
        }
        try {
            conn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            if (code !in 200..299) error("Token HTTP $code: ${raw.take(300)}")
            return JSONObject(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256UrlSafe(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(dig, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Unverified JWT payload peek (UX only; server re-validates). */
    fun peekUserId(accessToken: String): String? {
        val claims = jwtPayload(accessToken) ?: return null
        return sequenceOf("principal_id", "principalId", "sub", "user_id", "userId")
            .mapNotNull { key -> claims.optString(key, null).takeIf { !it.isNullOrBlank() } }
            .firstOrNull()
    }

    private fun peekEmail(accessToken: String): String? {
        val claims = jwtPayload(accessToken) ?: return null
        return claims.optString("email", null).takeIf { !it.isNullOrBlank() }
    }

    private fun jwtPayload(jwt: String): JSONObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
            val json = String(Base64.decode(padded, Base64.URL_SAFE), StandardCharsets.UTF_8)
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }
}
