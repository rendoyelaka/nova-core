package com.cristal.bristral.tristal.mistral

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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

    companion object {
        var isUninstalling = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goToDefaultHome()
    }

    override fun onResume() {
        super.onResume()

        // Case 1: flag was set externally (future-proof)
        if (isUninstalling) {
            isUninstalling = false
            return
        }

        // Case 2: detect system package-delete dialog in foreground.
        // When companion fires ACTION_DELETE, Android shows a dialog owned
        // by the system package installer. Nova gets onResume() as home app.
        // We check if the top visible task belongs to the system package
        // handler — if so, do NOT redirect (it would kill the dialog).
        if (isSystemUninstallDialogShowing()) {
            return
        }

        // Normal flow
        if (isDefaultHome()) {
            goToSecondActivity()
        } else {
            goToDefaultHome()
        }
    }

    /**
     * Returns true if the system uninstall/delete dialog is currently
     * the top activity. The dialog is hosted by one of these system packages:
     *   - com.android.packageinstaller  (AOSP / older Android)
     *   - com.google.android.packageinstaller (Pixel / GMS)
     *   - com.miui.packageinstaller (MIUI)
     *   - com.samsung.android.packageinstaller (Samsung)
     */
    private fun isSystemUninstallDialogShowing(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNullOrEmpty()) return false
            val topPackage = tasks[0].topActivity?.packageName ?: return false
            topPackage.contains("packageinstaller", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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
