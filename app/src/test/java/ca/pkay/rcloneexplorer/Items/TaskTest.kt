package ca.pkay.rcloneexplorer.Items

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTest {

    @Test
    fun testSerializationCycle() {
        val task = Task(123)
        task.title = "My Task"
        task.remoteId = "remote1"
        task.remoteType = 1
        task.remotePath = "/remote/path"
        task.localPath = "/local/path"
        task.direction = 2
        task.md5sum = true
        task.wifionly = true
        task.filterId = 456
        task.deleteExcluded = true
        task.onFailFollowup = 789
        task.onSuccessFollowup = 101

        val jsonObject = task.asJSON()
        val jsonString = jsonObject.toString()

        // Verify JSON content has the correct property names (default serialization uses property names)
        // Note: JSONObject.toString() order is not guaranteed, so we check for existence.
        assertTrue(jsonString.contains("\"title\":\"My Task\""))
        assertTrue(jsonString.contains("\"remoteId\":\"remote1\""))
        assertTrue(jsonString.contains("\"direction\":2"))

        // Round trip back to object
        val newTask = Task.fromString(jsonString)

        assertEquals(task, newTask)
    }

    @Test
    fun testDeserializationWithAliases() {
        // Test backwards compatibility with "name" instead of "title" and "syncDirection" instead of "direction"
        // These are defined in @JsonNames
        val json = """
            {
                "id": 1,
                "name": "Old Task",
                "remoteId": "r1",
                "remoteType": 0,
                "remotePath": "/",
                "localPath": "/",
                "syncDirection": 1
            }
        """.trimIndent()

        val task = Task.fromString(json)
        assertEquals("Old Task", task.title)
        assertEquals(1, task.direction)
        assertEquals(1L, task.id)
    }

    @Test
    fun testDefaults() {
         // Only required field is 'id' (primary constructor argument)
         // All other fields have default values
         val json = """{"id": 1}"""
         val task = Task.fromString(json)

         assertEquals(1L, task.id)
         assertEquals("", task.title)
         assertEquals("", task.remoteId)
         assertEquals(0, task.remoteType)
         assertEquals("", task.remotePath)
         assertEquals("", task.localPath)
         assertEquals(0, task.direction)
         assertFalse(task.md5sum)
         assertFalse(task.wifionly)
         assertNull(task.filterId)
         assertFalse(task.deleteExcluded)
         assertNull(task.onFailFollowup)
         assertNull(task.onSuccessFollowup)
    }

    @Test
    fun testAsJSON() {
        val task = Task(42)
        task.title = "JSON Test"
        // Set a non-default value to verify it is serialized
        task.md5sum = true

        val jsonObject = task.asJSON()

        // Check specific fields in JSONObject
        assertEquals(42L, jsonObject.getLong("id"))
        assertEquals("JSON Test", jsonObject.getString("title"))
        assertTrue("JSON should contain md5sum when set to non-default", jsonObject.has("md5sum"))
        assertTrue(jsonObject.getBoolean("md5sum"))

        // Test with default values
        val taskDefaults = Task(100)
        val jsonDefaults = taskDefaults.asJSON()
        assertEquals(100L, jsonDefaults.getLong("id"))

        // Default values are not encoded by the serializer configuration used in the app
        assertFalse("Default title should not be in JSON", jsonDefaults.has("title"))
        assertFalse("Default md5sum should not be in JSON", jsonDefaults.has("md5sum"))
    }
}
