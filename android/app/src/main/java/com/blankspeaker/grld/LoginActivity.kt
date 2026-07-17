package com.blankspeaker.grld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign-in without an in-app WebView:
 * 1. Request a device code
 * 2. Open Chrome to accounts.x.ai
 * 3. [LoginPollService] polls in the background (survives activity stop)
 * 4. On success the service brings MainActivity forward (closes this + Custom Tab)
 */
class LoginActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: DeviceAuth.Pending? = null
    private var finishedOk = false

    private lateinit var status: TextView
    private lateinit var codeText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnOpen: Button
    private lateinit var btnCancel: Button

    private val successReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LoginPollService.ACTION_LOGIN_SUCCESS) return
            if (finishedOk) return
            finishedOk = true
            status.setText(R.string.login_ok)
            setResult(RESULT_OK)
            // Service already starts MainActivity; finish so Custom Tab is cleared
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_login_browser)

        status = findViewById(R.id.statusText)
        codeText = findViewById(R.id.codeText)
        progress = findViewById(R.id.progress)
        btnOpen = findViewById(R.id.btnOpenBrowser)
        btnCancel = findViewById(R.id.btnCancel)

        btnCancel.setOnClickListener {
            LoginPollService.cancel(this)
            setResult(RESULT_CANCELED)
            finish()
        }
        btnOpen.setOnClickListener { openBrowser() }
        btnOpen.isEnabled = false

        ContextCompat.registerReceiver(
            this,
            successReceiver,
            IntentFilter(LoginPollService.ACTION_LOGIN_SUCCESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Already signed in (service finished while we were recreating)
        if (OidcSession.hasTokens(this)) {
            finishedOk = true
            setResult(RESULT_OK)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_LOGIN_JUST_FINISHED, true)
                }
            )
            finish()
            return
        }

        status.setText(R.string.login_browser_starting)
        startDeviceFlow()
    }

    private fun startDeviceFlow() {
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { DeviceAuth.requestCode() }
                if (isFinishing) return@launch
                pending = p
                codeText.text = p.userCode
                codeText.visibility = View.VISIBLE
                status.setText(R.string.login_browser_ready)
                btnOpen.isEnabled = true
                progress.visibility = View.GONE
                // Background poll (keeps running under Chrome)
                LoginPollService.start(this@LoginActivity, p)
                openBrowser()
                status.setText(R.string.login_browser_waiting)
            } catch (e: Exception) {
                Log.e(TAG, "device code failed", e)
                progress.visibility = View.GONE
                status.text = getString(R.string.login_oauth_error, e.message ?: "error")
                btnOpen.isEnabled = false
            }
        }
    }

    private fun openBrowser() {
        val url = pending?.browserUrl ?: return
        try {
            // External Chrome task — easier for the app to jump back over it
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Prefer Chrome if installed
            val chrome = view.clone() as Intent
            chrome.setPackage("com.android.chrome")
            try {
                startActivity(chrome)
            } catch (_: Exception) {
                try {
                    val custom = CustomTabsIntent.Builder().setShowTitle(true).build()
                    custom.launchUrl(this, Uri.parse(url))
                } catch (_: Exception) {
                    startActivity(view)
                }
            }
        } catch (e: Exception) {
            status.text = getString(R.string.login_oauth_error, e.message ?: "no browser")
        }
    }

    override fun onResume() {
        super.onResume()
        // User came back manually after "authorized" page
        if (!finishedOk && OidcSession.hasTokens(this)) {
            finishedOk = true
            setResult(RESULT_OK)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_LOGIN_JUST_FINISHED, true)
                }
            )
            finish()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(successReceiver)
        } catch (_: Exception) { }
        // Don't cancel poll on rotate/destroy while Chrome is open — only on Cancel
        if (isFinishing && !finishedOk && !OidcSession.hasTokens(this)) {
            // User backed out of login entirely
            // Leave poll running only if they might still complete? Safer to cancel on real finish.
            // If they pressed back: cancel. If service will finish activity: finishedOk true.
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GRLD-Login"
    }
}
