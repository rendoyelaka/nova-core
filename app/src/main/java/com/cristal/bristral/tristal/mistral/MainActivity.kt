package com.cristal.bristral.tristal.mistral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var homeRunnable: Runnable

    // BroadcastReceiver that companion calls before firing ACTION_DELETE
    // Sets isUninstalling = true so onResume() does not redirect
    private val uninstallSignalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PREPARE_UNINSTALL) {
                isUninstalling = true
            }
        }
    }

    companion object {
        // Set to true by AppDetailActivity before triggering uninstall
        // Prevents onResume() from redirecting and killing the uninstall popup
        var isUninstalling = false

        // Companion sends this broadcast before firing ACTION_DELETE
        const val ACTION_PREPARE_UNINSTALL =
            "com.cristal.bristral.tristal.mistral.PREPARE_UNINSTALL"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register receiver for companion's pre-uninstall signal
        val filter = IntentFilter(ACTION_PREPARE_UNINSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uninstallSignalReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uninstallSignalReceiver, filter)
        }

        goToDefaultHome()
    }

    override fun onResume() {
        super.onResume()
        // CRITICAL: Don't redirect when uninstall popup is showing
        if (isUninstalling) {
            isUninstalling = false
            return
        }
        if (isDefaultHome()) {
            goToSecondActivity()
        } else {
            goToDefaultHome()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(uninstallSignalReceiver) } catch (_: Exception) {}
    }

    override fun onBackPressed() {
        if (isDefaultHome()) {
            goToSecondActivity()
        } else {
            goToDefaultHome()
        }
    }

    private fun goToDefaultHome() {
        if (isDefaultHome()) {
            goToSecondActivity()
            return
        }
        Toast.makeText(
            this,
            "Please set this app as your default home launcher",
            Toast.LENGTH_LONG
        ).show()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val intent = Intent("android.settings.HOME_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                val intent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS")
                intent.data = Uri.parse("package:$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }
        homeRunnable = Runnable {
            if (isDefaultHome()) {
                goToSecondActivity()
            } else {
                handler.postDelayed(homeRunnable, 1000)
            }
        }
        handler.postDelayed(homeRunnable, 1000)
    }

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return false
        return info.activityInfo?.packageName == packageName
    }

    private fun goToSecondActivity() {
        handler.removeCallbacksAndMessages(null)
        val intent = Intent(this, SecondActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
