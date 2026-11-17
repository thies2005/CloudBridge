package de.schuelken.cloudbridge.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

object ApkInstallHelper {

    const val MIME_APK = "application/vnd.android.package-archive"
    const val OPEN_AS_DEFAULT = -1
    const val OPEN_AS_TEXT = 1
    const val OPEN_AS_IMAGE = 2

    private val SPLIT_APK_EXTENSIONS = setOf("apks", "xapk", "apkm")

    @JvmStatic
    fun isApkFile(fileNameOrPath: String?, mimeType: String?): Boolean {
        if (MIME_APK.equals(mimeType, ignoreCase = true)) {
            return true
        }
        return FileItem.extractExtension(fileNameOrPath) == "apk"
    }

    @JvmStatic
    fun isSplitApkFile(fileNameOrPath: String?): Boolean {
        return FileItem.extractExtension(fileNameOrPath) in SPLIT_APK_EXTENSIONS
    }

    @JvmStatic
    fun resolveMimeType(fileNameOrPath: String?, declaredMimeType: String?, openAs: Int): String {
        return when (openAs) {
            OPEN_AS_TEXT -> "text/*"
            OPEN_AS_IMAGE -> "image/*"
            else -> {
                if (isApkFile(fileNameOrPath, declaredMimeType)) {
                    MIME_APK
                } else {
                    val resolved = FileItem.getMimeType(declaredMimeType, fileNameOrPath ?: "")
                    if (resolved.isNullOrEmpty() || "application/octet-stream" == resolved) {
                        "*/*"
                    } else {
                        resolved
                    }
                }
            }
        }
    }

    @JvmStatic
    fun openFile(
        context: Context,
        file: File,
        declaredMimeType: String?,
        openAs: Int,
        requestInstallPermission: ((Intent, File) -> Unit)? = null
    ) {
        val name = file.name
        if (openAs == OPEN_AS_DEFAULT && isSplitApkFile(name)) {
            Toast.makeText(context, R.string.apk_split_not_supported, Toast.LENGTH_LONG).show()
            return
        }

        val mimeType = resolveMimeType(name, declaredMimeType, openAs)
        if (mimeType == MIME_APK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog)
                    .setTitle(R.string.apk_install_permission_title)
                    .setMessage(R.string.apk_install_permission_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.apk_install_permission_action) { _, _ ->
                        if (requestInstallPermission != null) {
                            requestInstallPermission(settingsIntent, file)
                        } else {
                            try {
                                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(settingsIntent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
                return
            }
        }

        launchViewIntent(context, file, mimeType)
    }

    @JvmStatic
    fun launchViewIntent(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mimeType == MIME_APK || mimeType == "*/*" || mimeType.endsWith("/*")) {
                setDataAndType(uri, mimeType)
            } else {
                setDataAndTypeAndNormalize(uri, mimeType.lowercase(Locale.ROOT))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val chooser = Intent.createChooser(intent, context.getString(R.string.open_with))
            try {
                context.startActivity(chooser)
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(context, R.string.no_app_to_open_file, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
