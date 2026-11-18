package ca.pkay.rcloneexplorer.notifications.support

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StatusObjectTest {

    @Test
    fun percentageFallsBackWithoutNaNWhenTotalBytesIsZero() {
        val empty = JSONObject()
        val pctEmpty = StatusObject.computePercentage(empty)
        assertFalse(pctEmpty.isNaN())
        assertEquals(0.0, pctEmpty, 0.001)

        val transfersOnly = JSONObject()
            .put("bytes", 0)
            .put("totalBytes", 0)
            .put("transfers", 3)
            .put("totalTransfers", 4)
        assertEquals(75.0, StatusObject.computePercentage(transfersOnly), 0.001)

        val checksOnly = JSONObject()
            .put("checks", 2)
            .put("totalChecks", 5)
        assertEquals(40.0, StatusObject.computePercentage(checksOnly), 0.001)

        val bytesDominant = JSONObject()
            .put("bytes", 500)
            .put("totalBytes", 1000)
            .put("transfers", 1)
            .put("totalTransfers", 10)
        assertEquals(50.0, StatusObject.computePercentage(bytesDominant), 0.001)
    }

    @Test
    fun etaAndElapsedExtractSafelyFromPartialOrNullStats() {
        assertEquals(-1, StatusObject.extractEtaSeconds(JSONObject()))
        assertEquals(-1, StatusObject.extractEtaSeconds(JSONObject().put("eta", JSONObject.NULL)))
        assertEquals(-1, StatusObject.extractEtaSeconds(JSONObject().put("eta", -3)))
        assertEquals(18, StatusObject.extractEtaSeconds(JSONObject().put("eta", 18.7)))

        assertEquals(0, StatusObject.extractElapsedSeconds(JSONObject()))
        assertEquals(0, StatusObject.extractElapsedSeconds(JSONObject().put("elapsedTime", JSONObject.NULL)))
        assertEquals(42, StatusObject.extractElapsedSeconds(JSONObject().put("elapsedTime", 42.3)))
    }
}
