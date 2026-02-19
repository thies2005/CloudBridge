package ca.pkay.rcloneexplorer.Items

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TaskTest {

    @Test
    fun testSerializationAndDeserialization() {
        val task = Task(123)
        task.title = "Test Task"
        task.remoteId = "remote_1"
        task.remoteType = 1
        task.remotePath = "/path/to/remote"
        task.localPath = "/path/to/local"
        task.direction = 2
        task.md5sum = true
        task.wifionly = true
        task.filterId = 456
        task.deleteExcluded = true
        task.onFailFollowup = 789
        task.onSuccessFollowup = 101

        val jsonObject = task.asJSON()
        val jsonString = jsonObject.toString()

        val parsedTask = Task.fromString(jsonString)

        assertEquals(task.id, parsedTask.id)
        assertEquals(task.title, parsedTask.title)
        assertEquals(task.remoteId, parsedTask.remoteId)
        assertEquals(task.remoteType, parsedTask.remoteType)
        assertEquals(task.remotePath, parsedTask.remotePath)
        assertEquals(task.localPath, parsedTask.localPath)
        assertEquals(task.direction, parsedTask.direction)
        assertEquals(task.md5sum, parsedTask.md5sum)
        assertEquals(task.wifionly, parsedTask.wifionly)
        assertEquals(task.filterId, parsedTask.filterId)
        assertEquals(task.deleteExcluded, parsedTask.deleteExcluded)
        assertEquals(task.onFailFollowup, parsedTask.onFailFollowup)
        assertEquals(task.onSuccessFollowup, parsedTask.onSuccessFollowup)
    }

    @Test
    fun testDefaultValues() {
        val json = """{"id": 999}"""
        val task = Task.fromString(json)

        assertEquals(999, task.id)
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
    fun testAsJSONStructure() {
        val task = Task(555)
        task.title = "My Task"

        val jsonObject = task.asJSON()

        assertEquals(555L, jsonObject.getLong("id"))
        // Serialization uses the property name "title" by default.
        // @JsonNames("name") is only for deserialization aliases.
        assertEquals("My Task", jsonObject.getString("title"))
    }

    @Test
    fun testBackwardCompatibility() {
        // Test that "name" (alias) is correctly deserialized to "title" property
        val json = """{"id": 123, "name": "Old Name"}"""
        val task = Task.fromString(json)
        assertEquals("Old Name", task.title)
    }
}
