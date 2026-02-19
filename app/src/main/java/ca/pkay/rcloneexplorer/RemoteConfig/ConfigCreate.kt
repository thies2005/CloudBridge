package ca.pkay.rcloneexplorer.RemoteConfig

import android.annotation.SuppressLint
import android.content.Context
import ca.pkay.rcloneexplorer.Rclone
import android.os.AsyncTask
import es.dmoral.toasty.Toasty
import ca.pkay.rcloneexplorer.R
import android.widget.Toast
import android.content.Intent
import android.view.View
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.util.FLog
import java.util.ArrayList

@SuppressLint("StaticFieldLeak")
class ConfigCreate internal constructor(
    options: ArrayList<String>?,
    formView: View,
    authView: View,
    context: Context,
    rclone: Rclone,
    private val providerType: String = ""
) : AsyncTask<Void?, Void?, Boolean>() {
    private val options: ArrayList<String>
    private var process: Process? = null
    private val mContext: Context
    private val mRclone: Rclone
    private val mFormView: View
    private val mAuthView: View

    companion object {
        private const val TAG = "ConfigCreate"
    }

    init {
        this.options = ArrayList(options)
        mFormView = formView
        mAuthView = authView
        mContext = context
        mRclone = rclone
    }

    override fun onPreExecute() {
        super.onPreExecute()
        mAuthView.visibility = View.VISIBLE
        mFormView.visibility = View.GONE
    }

    override fun doInBackground(vararg params: Void?): Boolean {
        return if (providerType.equals("internxt", ignoreCase = true)) {
            createInternxtWithTwoFactor()
        } else {
            OauthHelper.createOptionsWithOauth(options, mRclone, mContext)
        }
    }

    /**
     * Creates an Internxt remote with 2FA support.
     * Uses manual buffer reading to handle both 2FA and non-2FA accounts.
     * 
     * Flow:
     * 1. Ask user for Auth Method (Temporary vs Auto-Login)
     * 2. If Auto-Login: Ask for TOTP Secret (Seed) and add to options
     * 3. rclone authenticates with email/password (+ totp_secret if provided)
     * 4. If 2FA enabled: prompts "Two-factor authentication code" -> show dialog
     * 5. Shows "Keep this" confirmation -> respond with 'y'
     */
    private fun createInternxtWithTwoFactor(): Boolean {
        android.util.Log.e(TAG, "=== INTERNXT AUTH START ===")
        android.util.Log.e(TAG, "Options: $options")

        // Step 0: Ask for Auth Method (Temporary vs Auto-Login)
        val authMethod = getAuthPreferenceFromUser()
        if (authMethod == "CANCEL") {
             return false
        }

        if (authMethod == "PERMANENT") {
            val totpSecret = getTOTPSecretFromUser()
            if (totpSecret.isEmpty()) {
                // User cancelled or entered empty string
                return false
            }
            // Add totp_secret to options
            options.add("totp_secret")
            options.add(totpSecret)
            android.util.Log.e(TAG, "Added totp_secret to options")
        }
        
        // Step 1: Create the remote entry (this just saves email/pass, no auth)
        android.util.Log.e(TAG, "Step 1: Running config create...")
        process = mRclone.configCreate(options)
        if (process == null) {
            android.util.Log.e(TAG, "Step 1 FAILED: process is null")
            return false
        }
        
        var createProc = process!!
        android.util.Log.e(TAG, "Step 1: Waiting for config create to finish...")
        
        // Drain output to prevent blocking
        val createOutput = StringBuilder()
        Thread {
            try {
                createProc.inputStream.bufferedReader().forEachLine { createOutput.appendLine(it) }
            } catch (e: Exception) {}
        }.start()
        Thread {
            try {
                createProc.errorStream.bufferedReader().forEachLine { createOutput.appendLine(it) }
            } catch (e: Exception) {}
        }.start()
        
        try {
            val finished = createProc.waitFor(1, java.util.concurrent.TimeUnit.MINUTES)
            android.util.Log.e(TAG, "Step 1 finished=$finished, exitCode=${if (finished) createProc.exitValue() else -1}")
            android.util.Log.e(TAG, "Step 1 output: $createOutput")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Step 1 EXCEPTION: ${e.message}")
            createProc.destroyForcibly()
            return false
        }
        
        // Extract remote name from options (first element)
        val remoteName = if (options.isNotEmpty()) options[0] else {
            android.util.Log.e(TAG, "ERROR: options empty, cannot get remote name")
            return false
        }
        android.util.Log.e(TAG, "Step 2: Running config reconnect for '$remoteName'...")
        
        // Step 2: Run config reconnect to complete the interactive auth
        return runConfigReconnect(remoteName)
    }
    
    /**
     * Runs config reconnect to complete Internxt authentication.
     * Handles both 2FA and mnemonic confirmation interactively.
     */
    private fun runConfigReconnect(remoteName: String): Boolean {
        // rclone config reconnect expects format: remoteName:
        val reconnectOptions = arrayListOf("$remoteName:")
        android.util.Log.e(TAG, "Starting config reconnect for: $remoteName:")
        process = mRclone.config("reconnect", reconnectOptions)
        
        if (process == null) {
            android.util.Log.e(TAG, "Failed to start config reconnect")
            return false
        }
        
        val proc = process!!
        var twoFactorHandled = false
        var confirmHandled = false
        var processOutput = ""
        
        val outputReader = Thread {
            try {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                val errorReader = java.io.BufferedReader(java.io.InputStreamReader(proc.errorStream))
                val buffer = StringBuilder()
                
                while (!Thread.currentThread().isInterrupted) {
                    var readSomething = false
                    while (errorReader.ready()) {
                        val char = errorReader.read()
                        if (char == -1) break
                        buffer.append(char.toChar())
                        readSomething = true
                    }
                    while (reader.ready()) {
                        val char = reader.read()
                        if (char == -1) break
                        buffer.append(char.toChar())
                        readSomething = true
                    }
                    
                    if (readSomething) {
                        val output = buffer.toString()
                        processOutput = output
                        android.util.Log.e(TAG, "Reconnect buffer: $output")
                        
                        // Check for 2FA prompt
                        if (!twoFactorHandled && output.contains("Two-factor authentication code")) {
                            android.util.Log.e(TAG, "2FA prompt detected, showing dialog")
                            twoFactorHandled = true
                            val code = getTwoFactorCodeFromUser()
                            if (code.isNotEmpty()) {
                                proc.outputStream.write("$code\n".toByteArray())
                                proc.outputStream.flush()
                                android.util.Log.e(TAG, "2FA code sent")
                            }
                            buffer.clear()
                        }
                        
                        // Check for mnemonic confirmation (various patterns)
                        if (!confirmHandled && (output.contains("Keep this") || output.contains("y/n"))) {
                            android.util.Log.e(TAG, "Confirmation detected, sending 'y'")
                            confirmHandled = true
                            proc.outputStream.write("y\n".toByteArray())
                            proc.outputStream.flush()
                        }
                    }
                    Thread.sleep(50)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Reader thread ended: ${e.message}")
            }
        }
        outputReader.start()
        
        return try {
            val completed = proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
            outputReader.interrupt()
            outputReader.join(1000)
            
            if (!completed) {
                FLog.e(TAG, "Config reconnect timed out")
                proc.destroyForcibly()
                return false
            }
            
            val exitCode = proc.exitValue()
            FLog.d(TAG, "Config reconnect exited with code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            FLog.e(TAG, "Config reconnect failed", e)
            proc.destroyForcibly()
            false
        }
    }

    private fun getAuthPreferenceFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice = "CANCEL"

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_auth_method_title)

            
            val options = arrayOf(
                mContext.getString(R.string.internxt_auth_option_temp),
                mContext.getString(R.string.internxt_auth_option_perm)
            )
            
            builder.setItems(options) { dialog, which ->
                if (which == 0) {
                    choice = "TEMPORARY"
                } else {
                    choice = "PERMANENT"
                }
                latch.countDown()
            }
            
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }

        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user auth preference", e)
        }
        return choice
    }

    private fun getTOTPSecretFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var secret = ""
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_totp_secret_title)
            builder.setMessage(R.string.internxt_totp_secret_message)
            
            val inputLayout = com.google.android.material.textfield.TextInputLayout(mContext)
            inputLayout.hint = mContext.getString(R.string.internxt_totp_secret_hint)
            
            val input = com.google.android.material.textfield.TextInputEditText(mContext)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            inputLayout.addView(input)
            
            val padding = (16 * mContext.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)
            builder.setView(inputLayout)
            
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                secret = input.text?.toString()?.trim() ?: ""
                latch.countDown()
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }
        
        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user TOTP secret", e)
        }
        return secret
    }

    private fun getTwoFactorCodeFromUser(): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var code = ""
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(mContext)
            builder.setTitle(R.string.internxt_2fa_title)
            builder.setMessage(R.string.internxt_2fa_message)
            
            val inputLayout = com.google.android.material.textfield.TextInputLayout(mContext)
            inputLayout.hint = mContext.getString(R.string.internxt_2fa_hint)
            
            val input = com.google.android.material.textfield.TextInputEditText(mContext)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            inputLayout.addView(input)
            
            val padding = (16 * mContext.resources.displayMetrics.density).toInt()
            inputLayout.setPadding(padding, 0, padding, 0)
            builder.setView(inputLayout)
            
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                code = input.text?.toString()?.trim() ?: ""
                latch.countDown()
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                latch.countDown()
            }
            builder.setCancelable(false)
            builder.show()
        }
        
        try {
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: Exception) {
            FLog.e(TAG, "Error waiting for user input", e)
        }
        return code
    }

    override fun onCancelled() {
        super.onCancelled()
        process?.destroy()
    }

    override fun onPostExecute(success: Boolean) {
        super.onPostExecute(success)
        if (!success) {
            Toasty.error(
                mContext,
                mContext.getString(R.string.error_creating_remote),
                Toast.LENGTH_SHORT,
                true
            ).show()
        } else {
            Toasty.success(
                mContext,
                mContext.getString(R.string.remote_creation_success),
                Toast.LENGTH_SHORT,
                true
            ).show()
        }
        val intent = Intent(mContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
    }
}

