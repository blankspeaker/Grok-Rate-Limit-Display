package com.blankspeaker.grld

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches weekly usage via OAuth + REST billing only. */
class GrokClient(private val context: Context) {

    sealed class ClientError(message: String) : Exception(message) {
        class NotSignedIn : ClientError("Not signed in")
        class FreePlan : ClientError("This app requires a SuperGrok or SuperGrok Heavy plan to access usage limits.")
        class Http(code: Int, body: String) : ClientError("HTTP $code: ${body.take(120)}")
        class ParseFailed : ClientError("Could not parse usage (session may have expired)")
        class Network(e: Exception) : ClientError(e.message ?: "Network error")
    }

    suspend fun fetch(): UsageResponse = withContext(Dispatchers.IO) {
        if (!OidcSession.hasTokens(context)) throw ClientError.NotSignedIn()
        try {
            BillingClient.fetch(context)
        } catch (e: ClientError) {
            throw e
        } catch (e: Exception) {
            throw ClientError.Network(e)
        }
    }
}
