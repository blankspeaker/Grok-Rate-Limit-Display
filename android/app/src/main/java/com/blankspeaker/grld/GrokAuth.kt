package com.blankspeaker.grld

import android.content.Context
import android.webkit.CookieManager

/**
 * App auth façade — OAuth tokens only ([OidcSession]).
 * Legacy SSO cookies / WebView sessions are not used.
 */
object GrokAuth {

    fun isSignedIn(context: Context): Boolean = OidcSession.hasTokens(context)

    fun clear(context: Context) {
        OidcSession.clear(context)
        // Wipe any leftover cookie jar from older builds
        wipeLegacyCookieStore()
        context.getSharedPreferences("grld_auth", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** One-shot cleanup of pre-OAuth cookie prefs (safe to call every launch). */
    fun purgeLegacyAuthArtifacts(context: Context) {
        context.getSharedPreferences("grld_auth", Context.MODE_PRIVATE).edit().clear().apply()
        wipeLegacyCookieStore()
    }

    private fun wipeLegacyCookieStore() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) { }
    }
}
