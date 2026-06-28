package com.cristal.bristral.tristral.mistral

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

    // Listens for ACTION_DELETE being fired for our own package.
    // Companion fires ACTION_DELETE with FLAG_ACTIVITY_NEW_TASK which
    // brings Nova back to foreground before dialog renders.
    // We intercept via onNewIntent + this receiver to set isUninstalling = true
    // so onResume() does not redirect away from the system dialog.
    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = intent.data?.toString() ?: return
            if (data == "package:$packageName") {
                isUninstalling = true
            }
        }
    }

    companion object {
        var isUninstalling = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register receiver for when companion fires ACTION_DELETE
        // against our package — set flag before onResume fires
        val filter = IntentFilter(Intent.ACTION_DELETE).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deleteReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(deleteReceiver, filter)
        }

        goToDefaultHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // FLAG_ACTIVITY_NEW_TASK routes back through onNewIntent on singleTask
        // Detect if this is the uninstall flow returning focus to us
        if (intent?.action == Intent.ACTION_DELETE) {
            val data = intent.data?.toString() ?: ""
            if (data == "package:$packageName") {
                isUninstalling = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        try { unregisterReceiver(deleteReceiver) } catch (_: Exception) {}
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
