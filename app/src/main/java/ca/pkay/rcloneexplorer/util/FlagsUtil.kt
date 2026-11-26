package ca.pkay.rcloneexplorer.util

import android.app.PendingIntent

class FlagsUtil {

    companion object {

        fun getFlagImmutable() : Int {
            return PendingIntent.FLAG_IMMUTABLE
        }

        fun getFlagImmutable(flag: Int) : Int {
            return PendingIntent.FLAG_IMMUTABLE or flag
        }
    }

}