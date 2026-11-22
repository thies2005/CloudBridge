package ca.pkay.rcloneexplorer.util

import android.app.PendingIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class FlagsUtilTest {

    @Test
    fun combinesImmutableFlagWithBitwiseOr() {
        assertEquals(PendingIntent.FLAG_IMMUTABLE, FlagsUtil.getFlagImmutable())
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            FlagsUtil.getFlagImmutable(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            FlagsUtil.getFlagImmutable(PendingIntent.FLAG_CANCEL_CURRENT)
        )
    }
}
