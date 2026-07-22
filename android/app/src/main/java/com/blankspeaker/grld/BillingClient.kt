package com.blankspeaker.grld

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Grok Build–style credits fetch:
 * GET https://cli-chat-proxy.grok.com/v1/billing?format=credits
 * Authorization: Bearer <oauth access token>
 */
object BillingClient {

    private const val TAG = "GRLD-Billing"

    fun fetch(context: Context): UsageResponse {
        val access = OidcSession.ensureAccessToken(context)
            ?: throw GrokClient.ClientError.NotSignedIn()
        val tokens = OidcSession.load(context)
            ?: throw GrokClient.ClientError.NotSignedIn()

        val conn = (URL(OidcSession.BILLING_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $access")
            setRequestProperty("X-XAI-Token-Auth", OidcSession.TOKEN_HEADER)
            if (tokens.userId.isNotBlank()) {
                setRequestProperty("x-userid", tokens.userId)
            }
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-grok-client-version", "grld-android-oauth-test")
            setRequestProperty("User-Agent", "GRLD-Android/oauth-test")
        }
        try {
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            if (code == 401 || code == 403) {
                throw GrokClient.ClientError.Http(code, "Session rejected — Sign In again")
            }
            if (code !in 200..299) {
                throw GrokClient.ClientError.Http(code, raw.take(200))
            }
            return parse(raw)
        } catch (e: GrokClient.ClientError) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "billing fetch failed", e)
            throw GrokClient.ClientError.Network(e)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(jsonText: String): UsageResponse {
        val root = JSONObject(jsonText)
        val cfg = root.optJSONObject("config")
            ?: throw GrokClient.ClientError.ParseFailed()

        val usedF = when {
            cfg.has("creditUsagePercent") && !cfg.isNull("creditUsagePercent") ->
                cfg.getDouble("creditUsagePercent")
            else -> null
        }
        val used = usedF?.roundToInt()?.coerceIn(0, 100)

        val periodObj = cfg.optJSONObject("currentPeriod")
        val period = if (periodObj != null) {
            val type = periodObj.optString("type", "USAGE_PERIOD_TYPE_WEEKLY")
            val kind = when {
                type.contains("MONTHLY", ignoreCase = true) -> "monthly"
                else -> "weekly"
            }
            UsagePeriod(
                type = kind,
                start = periodObj.optString("start", null).takeIf { !it.isNullOrBlank() }
                    ?: cfg.optString("billingPeriodStart", null).takeIf { !it.isNullOrBlank() },
                end = periodObj.optString("end", null).takeIf { !it.isNullOrBlank() }
                    ?: cfg.optString("billingPeriodEnd", null).takeIf { !it.isNullOrBlank() }
            )
        } else {
            UsagePeriod(
                type = "weekly",
                start = cfg.optString("billingPeriodStart", null).takeIf { !it.isNullOrBlank() },
                end = cfg.optString("billingPeriodEnd", null).takeIf { !it.isNullOrBlank() }
            )
        }

        val products = mutableListOf<ProductUsage>()
        val arr = cfg.optJSONArray("productUsage")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val nameRaw = p.optString("product", "").ifBlank { "Unknown" }
                // API often lists every product with no usagePercent (or 0).
                // Only keep categories that actually have used %.
                if (!p.has("usagePercent") || p.isNull("usagePercent")) continue
                val pct = p.getDouble("usagePercent").roundToInt().coerceIn(0, 100)
                if (pct <= 0) continue
                val id = productIdForName(nameRaw)
                products.add(
                    ProductUsage(
                        product = id,
                        name = ProductColors.displayName(id).let { dn ->
                            if (dn.startsWith("Other")) humanName(nameRaw) else dn
                        },
                        usagePercent = pct
                    )
                )
            }
        }

        // Free plan / no weekly pool: no percent and no products with usage
        val hasSignal = used != null || products.any { it.usagePercent > 0 } ||
            period.start != null || period.end != null
        if (!hasSignal) throw GrokClient.ClientError.FreePlan()

        val usedFinal = used ?: products.maxOfOrNull { it.usagePercent } ?: 0
        val tier = root.optString("subscriptionTier", null)
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("subscription_tier", null)?.takeIf { it.isNotBlank() }

        return UsageResponse(
            ok = true,
            remainingPercent = (100 - usedFinal).coerceIn(0, 100),
            usedPercent = usedFinal.coerceIn(0, 100),
            weeklyUsageAvailable = true,
            tierName = tier,
            currentPeriod = period,
            productUsage = products.sortedByDescending { it.usagePercent },
            fetchedAt = isoNow(),
            cached = false
        )
    }

    private fun productIdForName(raw: String): Int {
        val s = raw.lowercase(Locale.US)
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
        // Exact known API names first (avoids substring traps like "appbuilder" ⊃ "build").
        return when (s) {
            "grokbuild", "build" -> ProductColors.BUILD.id
            "grokappbuilder", "appbuilder" -> ProductColors.APP_BUILDER.id
            "grokchat", "chat" -> ProductColors.CHAT.id
            "grokimagine", "imagine", "image", "images" -> ProductColors.IMAGINE.id
            "grokplugins", "plugins", "plugin" -> ProductColors.PLUGINS.id
            "voice", "grokvoice" -> ProductColors.VOICE.id
            "api", "grokapi" -> ProductColors.API.id
            "thirdparty", "3rdparty", "third" -> ProductColors.THIRD_PARTY.id
            else -> when {
                s.contains("appbuilder") -> ProductColors.APP_BUILDER.id
                s.contains("third") || s.contains("3rd") -> ProductColors.THIRD_PARTY.id
                s.contains("plugin") -> ProductColors.PLUGINS.id
                s.contains("imagine") || s.contains("image") -> ProductColors.IMAGINE.id
                s.contains("voice") -> ProductColors.VOICE.id
                s.contains("chat") -> ProductColors.CHAT.id
                s.contains("api") -> ProductColors.API.id
                // "build" but not "builder" (GrokAppBuilder must not map to Grok Build).
                s.contains("build") && !s.contains("builder") -> ProductColors.BUILD.id
                else -> {
                    // Stable pseudo-id for unknowns so palette still works
                    100 + (s.hashCode() and 0xFF)
                }
            }
        }
    }

    private fun humanName(raw: String): String {
        return raw
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
            .ifBlank { raw }
    }

    private fun isoNow(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }
}
