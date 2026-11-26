package ca.pkay.rcloneexplorer.notifications.support

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import ca.pkay.rcloneexplorer.R
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StatusObject(var mContext: Context){

    private val TAG = "StatusObject"
    var notificationPercent: Int = 0
    var notificationContent: String = ""
    var notificationBigText = ArrayList<String>()
    var mErrorList = ArrayList<ErrorObject>()
    var mStats = JSONObject()
    var mLogline = JSONObject()

    var estimatedAverageSpeed = 0L
    var lastItemAverageSpeed = 0L
    var syncDirection: Int = 0

    fun getSpeed(): String {
        return Formatter.formatFileSize(mContext, mStats.optLong("speed", 0)) + "/s"
    }

    /**
     * Check if the sync direction is uploading (local to remote)
     */
    fun isUploadDirection(): Boolean {
        return syncDirection == SyncDirectionObject.SYNC_LOCAL_TO_REMOTE ||
               syncDirection == SyncDirectionObject.COPY_LOCAL_TO_REMOTE
    }

    /**
     * Check if the sync direction is downloading (remote to local)
     */
    fun isDownloadDirection(): Boolean {
        return syncDirection == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL ||
               syncDirection == SyncDirectionObject.COPY_REMOTE_TO_LOCAL
    }

    /**
     * This function is off by a bit. Afaik rclone calculates the average speed per file,
     * while we calculate the average speed overall. This means this estimate is likely a bit lower
     * than the real world speeds.
     * It also includes the time rclone requires to check the file after transfer, so it does not
     * reflect actual network speeds.
     */
    fun getEstimatedAverageSpeed(): String {
        return Formatter.formatFileSize(mContext, estimatedAverageSpeed) + "/s"
    }

    fun getLastItemAverageSpeed(): String {
        return Formatter.formatFileSize(mContext, lastItemAverageSpeed) + "/s"
    }

    fun getSize(): String {
        return Formatter.formatFileSize(mContext, mStats.optLong("bytes", 0))
    }

    fun getTotalSize(): String {
        return Formatter.formatFileSize(mContext, mStats.optLong("totalBytes", 0))
    }

    fun getPercentage(): Double = computePercentage(mStats)

    fun getEtaSeconds(): Int = extractEtaSeconds(mStats)

    fun getFormattedEta(): String {
        val eta = getEtaSeconds()
        return if (eta >= 0) prettyPrintDuration(eta) else ""
    }

    fun getElapsedSeconds(): Int = extractElapsedSeconds(mStats)

    fun getFormattedElapsed(): String {
        return prettyPrintDuration(getElapsedSeconds())
    }

    fun getTransfers(): Int {
        return mStats.optInt("transfers", 0)
    }

    fun getTotalTransfers(): Int {
        return mStats.optInt("totalTransfers", 0)
    }

    /**
     * Deletions reported by the current rclone process plus the totals folded in from earlier
     * processes of the same batch job (a multi-item delete runs several rclone processes and
     * each process only reports its own counts).
     */
    private var accumulatedDeletions = 0

    /** Folds the current process's counts into the total and resets them. */
    fun accumulateDeletions() {
        accumulatedDeletions += mStats.optInt("deletes", 0) + mStats.optInt("deletedDirs", 0)
        mStats = JSONObject()
    }

    fun getDeletions(): Int {
        return accumulatedDeletions + mStats.optInt("deletes", 0) + mStats.optInt("deletedDirs", 0)
    }

    fun getErrorMessage(): String {
        if(mLogline.has("msg") && mLogline.getString("level") == "error") {
            return mLogline.getString("msg")
        }
        return ""
    }

    fun getErrorObject(): String {
        if(mLogline.has("msg") && mLogline.getString("level") == "error") {
            return mLogline.optString("object", "")
        }
        return ""
    }

    fun parseLoglineToStatusObject(logLine: JSONObject) {
        if (logLine.optString("level") == "error") {
            clearObject()
            mLogline = logLine

            val error = ErrorObject(getErrorObject(), getErrorMessage())
            Log.e(TAG, error.mErrorObject + " - " + error.mErrorMessage)
            mErrorList.add(error)
        }

        if (logLine.has("stats")) {
            clearObject()
            mLogline = logLine
            mStats = mLogline.getJSONObject("stats")

            //available stats:
            //bytes,checks,deletedDirs,deletes,elapsedTime,errors,eta,fatalError,renames,retryError
            //speed,totalBytes,totalChecks,totalTransfers,transferTime,transfers

            val checks = mStats.optJSONArray("checking")
            if (checks != null && checks.length() > 0) {
                val filename = checks.optString(0, "")
                if (filename.isNotEmpty()) {
                    notificationBigText.add(
                        String.format(
                            mContext.getString(R.string.sync_notification_elapsed),
                            getFormattedElapsed()
                        )
                    )

                    notificationBigText.add(
                        String.format(
                            mContext.getString(R.string.sync_notification_file_checking),
                            filename
                        )
                    )
                    notificationContent = String.format(
                        mContext.getString(R.string.sync_notification_file_checking),
                        filename
                    )
                }
                notificationPercent = getPercentage().toInt()
                return
            }

            val transferringArr = mStats.optJSONArray("transferring")
            if (transferringArr != null && transferringArr.length() > 0) {
                val transferring = transferringArr.getJSONObject(0)
                lastItemAverageSpeed = transferring.optLong("speedAvg", 0)

                val divisor = getElapsedSeconds()
                estimatedAverageSpeed = if (divisor != 0) {
                    mStats.optLong("bytes", 0) / divisor
                } else {
                    0
                }
            }

            val speed = getSpeed()
            val size = getSize()
            val allsize = getTotalSize()
            val percent: Double = getPercentage()
            val etaSec = getEtaSeconds()
            val etaFormatted = if (etaSec >= 0) prettyPrintDuration(etaSec) else prettyPrintDuration(0)

            notificationContent = String.format(
                mContext.getString(R.string.sync_notification_short),
                size,
                allsize,
                etaFormatted
            )
            notificationBigText.clear()
            notificationBigText.add(
                String.format(
                    mContext.getString(R.string.sync_notification_transferred),
                    size,
                    allsize
                )
            )

            if (getDeletions() > 0) {
                notificationBigText.add(
                    String.format(
                        mContext.getString(R.string.sync_notification_deletions),
                        getDeletions()
                    )
                )
            }

            // Show direction-aware speed label
            val speedStringRes = when {
                isUploadDirection() -> R.string.sync_notification_upload_speed
                isDownloadDirection() -> R.string.sync_notification_download_speed
                else -> R.string.sync_notification_speed
            }
            notificationBigText.add(
                String.format(
                    mContext.getString(speedStringRes),
                    speed
                )
            )

            // Show session total transferred
            val sessionTotalStringRes = when {
                isUploadDirection() -> R.string.sync_notification_session_uploaded
                isDownloadDirection() -> R.string.sync_notification_session_downloaded
                else -> null
            }
            if (sessionTotalStringRes != null) {
                notificationBigText.add(
                    String.format(
                        mContext.getString(sessionTotalStringRes),
                        size
                    )
                )
            }

            notificationBigText.add(
                String.format(
                    mContext.getString(R.string.sync_notification_remaining),
                    etaFormatted
                )
            )
            val errorsCount = mStats.optInt("errors", 0)
            if (errorsCount > 0) {
                notificationBigText.add(
                    String.format(
                        mContext.getString(R.string.sync_notification_errors),
                        errorsCount
                    )
                )
            }

            notificationBigText.add(
                String.format(
                    mContext.getString(R.string.sync_notification_elapsed),
                    getFormattedElapsed()
                )
            )

            val transfers = mStats.optJSONArray("transferring")
            if (transfers != null && transfers.length() > 0) {
                val transferObject = transfers.getJSONObject(0)
                val filename = transferObject.optString("name", "")
                if (filename.isNotEmpty()) {
                    notificationBigText.add(
                        String.format(
                            mContext.getString(R.string.sync_notification_file_syncing),
                            filename
                        )
                    )
                }
            }

            notificationPercent = percent.toInt()
        }
    }

    fun clearObject() {
        notificationPercent = 0
        notificationContent = ""
        notificationBigText = ArrayList<String>()
        mLogline = JSONObject()
    }

    fun printErrors(){
        mErrorList.forEach {
            Log.e(TAG, it.mErrorObject + " - " + it.mErrorMessage)
        }
    }

    fun getAllErrorMessages(): String{
        var all = ""
        mErrorList.forEach {
            all += it.mErrorMessage + "\n"
            all += mContext.getString(R.string.status_offendingfile) + it.mErrorObject + "\n"
        }
        return all
    }

    override fun toString(): String {
        return "StatusObject(getPercentage=${getPercentage()}, getSpeed=${getSpeed()}, getEstimatedAverageSpeed=${getEstimatedAverageSpeed()}, getLastItemAverageSpeed=${getLastItemAverageSpeed()}, getSize=${getSize()}, getTotalSize=${getTotalSize()}, getTransfers=${getTransfers()}, getTotalTransfers=${getTotalTransfers()}, getErrorMessage=${getErrorMessage()}, getDeletions=${getDeletions()})"
    }


    fun prettyPrintDuration(secondDuration: Int): String {
        val clampedDuration = secondDuration.coerceAtLeast(0)
        var duration = clampedDuration.toLong()
        val days = TimeUnit.SECONDS.toDays(duration).toInt()
        duration -= (days * 60 * 60 * 24)
        val hours = TimeUnit.SECONDS.toHours(duration).toInt()
        duration -= (hours * 60 * 60)
        val minutes = TimeUnit.SECONDS.toMinutes(duration).toInt()
        duration -= (minutes * 60)
        val seconds = TimeUnit.SECONDS.toSeconds(duration).toInt()

        var daysText = String.format(
            mContext.resources.getQuantityString(R.plurals.modern_prettyprint_duration_d,
                days,
                days
            )
        )

        var hoursText = String.format(
            mContext.resources.getQuantityString(R.plurals.modern_prettyprint_duration_h,
                hours,
                hours
            )
        )

        var minutesText = String.format(
            mContext.resources.getQuantityString(R.plurals.modern_prettyprint_duration_m,
                minutes,
                minutes
            )
        )

        var secondsText = String.format(
            mContext.resources.getQuantityString(R.plurals.modern_prettyprint_duration_s,
                seconds,
                seconds
            )
        )

        if (days > 0) {
            daysText = "$daysText, "
        } else {
            daysText = ""
        }

        if (hours > 0) {
            hoursText = "$hoursText, "
        } else {
            hoursText = ""
        }

        if (minutes > 0) {
            minutesText = "$minutesText, "
        } else {
            minutesText = ""
        }

        //if the time is longer than an hour, dont show seconds
        return if (60 * 60 < clampedDuration) {
            "$daysText$hoursText$minutesText".trimEnd(' ', ',')
        } else {
            "$daysText$hoursText$minutesText$secondsText"
        }
    }

    companion object {
        @JvmStatic
        fun computePercentage(stats: JSONObject): Double {
            val totalBytes = stats.optLong("totalBytes", 0)
            if (totalBytes > 0) {
                return (stats.optLong("bytes", 0).toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0)
            }
            val totalTransfers = stats.optInt("totalTransfers", 0)
            if (totalTransfers > 0) {
                return (stats.optInt("transfers", 0).toDouble() / totalTransfers * 100.0).coerceIn(0.0, 100.0)
            }
            val totalChecks = stats.optInt("totalChecks", 0)
            if (totalChecks > 0) {
                return (stats.optInt("checks", 0).toDouble() / totalChecks * 100.0).coerceIn(0.0, 100.0)
            }
            return 0.0
        }

        @JvmStatic
        fun extractEtaSeconds(stats: JSONObject): Int {
            if (!stats.has("eta") || stats.isNull("eta")) {
                return -1
            }
            val raw = stats.optDouble("eta", -1.0)
            return if (raw.isNaN() || raw < 0.0) -1 else raw.toInt()
        }

        @JvmStatic
        fun extractElapsedSeconds(stats: JSONObject): Int {
            if (!stats.has("elapsedTime") || stats.isNull("elapsedTime")) {
                return 0
            }
            val raw = stats.optDouble("elapsedTime", 0.0)
            return if (raw.isNaN() || raw < 0.0) 0 else raw.toInt()
        }
    }
}