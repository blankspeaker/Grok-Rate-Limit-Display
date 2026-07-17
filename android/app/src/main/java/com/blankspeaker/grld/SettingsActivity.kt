package com.blankspeaker.grld

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Full-screen settings page (gear from main). Clean grouped rows —
 * does not push main UI around.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: UsageRepository
    private lateinit var history: DailyHistoryStore
    private lateinit var checkShowUsed: ImageView
    private lateinit var checkShowRemaining: ImageView
    private lateinit var checkStatusPill: ImageView
    private lateinit var checkTrayNotif: ImageView
    private lateinit var checkAlertsEnable: ImageView
    private lateinit var checkAlertDaily: ImageView
    private lateinit var alertsOptions: View
    private lateinit var txtAlertSound: TextView
    private lateinit var txtAlertEvery: TextView
    private lateinit var txtAccountAction: TextView
    private lateinit var chevronAccount: ImageView
    private lateinit var rowCheckUpdate: View
    private lateinit var dividerCheckUpdate: View
    private lateinit var rowAutoUpdate: View
    private lateinit var dividerAutoUpdate: View
    private lateinit var checkAutoUpdate: ImageView
    private lateinit var txtUpdateStatus: TextView

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (GrokAuth.isSignedIn(this)) {
            UsageMonitorService.start(this)
        }
        refreshAccountRow()
        updateChipChecks()
        updateDisplayChecks()
        updateAlertUi()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(DailyHistoryStore.EXPORT_MIME)
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val ver = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
            val json = history.exportDocument(ver)
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("openOutputStream failed")
            Toast.makeText(this, R.string.export_history_ok, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_history_fail, Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use { inp ->
                inp.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("openInputStream failed")
            val n = history.importDocument(text)
            val msg = if (n > 0) {
                getString(R.string.import_history_ok, n)
            } else {
                getString(R.string.import_history_none)
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (n > 0 && GrokAuth.isSignedIn(this)) {
                UsageMonitorService.start(this)
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.import_history_fail, Toast.LENGTH_LONG).show()
        }
    }

    private val ringtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        @Suppress("DEPRECATION")
        val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        // RESULT_OK + null URI = Silent; non-null = chosen ringtone (incl. default)
        // Store empty string for silent so we can distinguish from "use system default" (null).
        repo.setAlertSoundUri(uri?.toString() ?: "")
        UsageMonitorService.recreateAlertChannel(this)
        updateAlertUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repo = UsageRepository.get(this)
        history = DailyHistoryStore.get(this)

        checkShowUsed = findViewById(R.id.checkShowUsed)
        checkShowRemaining = findViewById(R.id.checkShowRemaining)
        checkStatusPill = findViewById(R.id.checkStatusPill)
        checkTrayNotif = findViewById(R.id.checkTrayNotif)
        checkAlertsEnable = findViewById(R.id.checkAlertsEnable)
        checkAlertDaily = findViewById(R.id.checkAlertDaily)
        alertsOptions = findViewById(R.id.alertsOptions)
        txtAlertSound = findViewById(R.id.txtAlertSound)
        txtAlertEvery = findViewById(R.id.txtAlertEvery)
        txtAccountAction = findViewById(R.id.txtAccountAction)
        chevronAccount = findViewById(R.id.chevronAccount)
        rowCheckUpdate = findViewById(R.id.rowCheckUpdate)
        dividerCheckUpdate = findViewById(R.id.dividerCheckUpdate)
        rowAutoUpdate = findViewById(R.id.rowAutoUpdate)
        dividerAutoUpdate = findViewById(R.id.dividerAutoUpdate)
        checkAutoUpdate = findViewById(R.id.checkAutoUpdate)
        txtUpdateStatus = findViewById(R.id.txtUpdateStatus)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.rowAccount).setOnClickListener {
            if (GrokAuth.isSignedIn(this)) {
                confirmSignOut()
            } else {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }

        findViewById<View>(R.id.rowShowUsed).setOnClickListener {
            repo.setShowUsedPercent(true)
            UsageMonitorService.refreshNow(this)
            updateChipChecks()
        }
        findViewById<View>(R.id.rowShowRemaining).setOnClickListener {
            repo.setShowUsedPercent(false)
            UsageMonitorService.refreshNow(this)
            updateChipChecks()
        }

        findViewById<View>(R.id.rowStatusPill).setOnClickListener {
            repo.setShowStatusPill(!repo.showStatusPill())
            UsageMonitorService.refreshNow(this)
            updateDisplayChecks()
        }
        findViewById<View>(R.id.rowTrayNotif).setOnClickListener {
            repo.setShowTrayNotification(!repo.showTrayNotification())
            UsageMonitorService.refreshNow(this)
            updateDisplayChecks()
        }
        findViewById<View>(R.id.rowAddWidget).setOnClickListener {
            if (UsageWidgetProvider.requestPin(this)) {
                Toast.makeText(this, R.string.widget_pin_started, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<View>(R.id.rowAlertsEnable).setOnClickListener {
            val on = !repo.alertsEnabled()
            repo.setAlertsEnabled(on)
            if (on) {
                // Ensure alert channel exists with current sound
                UsageMonitorService.recreateAlertChannel(this)
            }
            updateAlertUi()
        }
        findViewById<View>(R.id.rowAlertSound).setOnClickListener { pickAlertSound() }
        findViewById<View>(R.id.rowAlertEvery).setOnClickListener { pickAlertEvery() }
        findViewById<View>(R.id.rowAlertDaily).setOnClickListener {
            repo.setAlertOverDailyGoal(!repo.alertOverDailyGoal())
            updateAlertUi()
        }

        findViewById<View>(R.id.rowExportHistory).setOnClickListener {
            exportLauncher.launch(DailyHistoryStore.EXPORT_FILENAME)
        }
        findViewById<View>(R.id.rowImportHistory).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        findViewById<View>(R.id.rowLiveUpdates).setOnClickListener {
            UsageMonitorService.openLiveUpdateSettings(this)
        }
        rowAutoUpdate.setOnClickListener {
            val on = !AppUpdater.autoUpdateEnabled(this)
            AppUpdater.setAutoUpdateEnabled(this, on)
            applyUpdateRowVisibility()
            if (on) {
                // Kick a check soon if permission allows
                checkForAppUpdate()
            }
        }
        rowCheckUpdate.setOnClickListener { checkForAppUpdate() }
        findViewById<View>(R.id.rowGitHub).setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/blankspeaker/Grok-Rate-Limit-Display")
                )
            )
        }

        refreshAccountRow()
        updateChipChecks()
        updateDisplayChecks()
        updateAlertUi()
        applyUpdateRowVisibility()
    }

    override fun onResume() {
        super.onResume()
        refreshAccountRow()
        updateChipChecks()
        updateDisplayChecks()
        updateAlertUi()
        applyUpdateRowVisibility()
    }

    private fun applyUpdateRowVisibility() {
        val unlocked = AppUpdater.updatesUnlocked(this)
        val vis = if (unlocked) View.VISIBLE else View.GONE
        rowCheckUpdate.visibility = vis
        dividerCheckUpdate.visibility = vis
        rowAutoUpdate.visibility = vis
        dividerAutoUpdate.visibility = vis
        if (unlocked) {
            val auto = AppUpdater.autoUpdateEnabled(this)
            checkAutoUpdate.visibility = if (auto) View.VISIBLE else View.GONE
            txtUpdateStatus.text =
                getString(R.string.check_update_sub) + " · v${AppUpdater.currentVersionName(this)}"
        }
    }

    private fun checkForAppUpdate() {
        txtUpdateStatus.setText(R.string.check_update_checking)
        lifecycleScope.launch {
            try {
                AppUpdater.markChecked(this@SettingsActivity)
                val msg = AppUpdater.checkDownloadAndInstall(this@SettingsActivity, interactive = true)
                txtUpdateStatus.text = msg ?: getString(R.string.check_update_sub)
                if (msg != null) {
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                txtUpdateStatus.setText(R.string.check_update_failed)
                Toast.makeText(
                    this@SettingsActivity,
                    e.message ?: getString(R.string.check_update_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pickAlertSound() {
        val existing = repo.alertSoundUri()?.let { Uri.parse(it) }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alerts_sound_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                existing ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
        }
        ringtoneLauncher.launch(intent)
    }

    private fun pickAlertEvery() {
        val options = intArrayOf(0, 5, 10, 20, 25, 50)
        val labels = options.map { v ->
            if (v == 0) getString(R.string.alerts_every_off)
            else getString(R.string.alerts_every_value, v)
        }.toTypedArray()
        val current = repo.alertEveryPercent()
        val checked = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.alerts_every_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                repo.setAlertEveryPercent(options[which])
                // Reset milestone so next cross can fire cleanly after interval change
                repo.setLastAlertMilestone(0)
                updateAlertUi()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                GrokAuth.clear(this)
                UsageMonitorService.stop(this)
                refreshAccountRow()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshAccountRow() {
        val signedIn = GrokAuth.isSignedIn(this)
        if (signedIn) {
            txtAccountAction.setText(R.string.sign_out)
            txtAccountAction.setTextColor(0xFFEF5350.toInt())
            chevronAccount.visibility = View.GONE
        } else {
            txtAccountAction.setText(R.string.sign_in)
            txtAccountAction.setTextColor(0xFFF2F2F2.toInt())
            chevronAccount.visibility = View.VISIBLE
        }
    }

    private fun updateChipChecks() {
        val used = repo.showUsedPercent()
        checkShowUsed.visibility = if (used) View.VISIBLE else View.GONE
        checkShowRemaining.visibility = if (used) View.GONE else View.VISIBLE
    }

    private fun updateDisplayChecks() {
        checkStatusPill.visibility =
            if (repo.showStatusPill()) View.VISIBLE else View.GONE
        checkTrayNotif.visibility =
            if (repo.showTrayNotification()) View.VISIBLE else View.GONE
    }

    private fun updateAlertUi() {
        val on = repo.alertsEnabled()
        checkAlertsEnable.visibility = if (on) View.VISIBLE else View.GONE
        alertsOptions.visibility = if (on) View.VISIBLE else View.GONE
        checkAlertDaily.visibility =
            if (repo.alertOverDailyGoal()) View.VISIBLE else View.GONE

        val every = repo.alertEveryPercent()
        txtAlertEvery.text = if (every <= 0) {
            getString(R.string.alerts_every_off)
        } else {
            getString(R.string.alerts_every_value, every)
        }

        txtAlertSound.text = soundLabel(repo.alertSoundUri())
    }

    private fun soundLabel(uriStr: String?): String {
        // null = never chosen → system default; empty = user picked Silent
        if (uriStr == null) return getString(R.string.alerts_sound_default)
        if (uriStr.isEmpty()) return getString(R.string.alerts_sound_none)
        return try {
            val uri = Uri.parse(uriStr)
            val rt = RingtoneManager.getRingtone(this, uri)
            rt?.getTitle(this)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.alerts_sound_default)
        } catch (_: Exception) {
            getString(R.string.alerts_sound_default)
        }
    }
}
