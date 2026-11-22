package de.schuelken.cloudbridge.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

class ApkInstallHelperTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun detectsApkByExtensionOrMime() {
        assertTrue(ApkInstallHelper.isApkFile("app-release.apk", "application/octet-stream"))
        assertTrue(ApkInstallHelper.isApkFile("APP.APK", null))
        assertTrue(ApkInstallHelper.isApkFile("noext", ApkInstallHelper.MIME_APK))
        assertFalse(ApkInstallHelper.isApkFile("archive.zip", "application/zip"))
    }

    @Test
    fun detectsSplitApkFormats() {
        assertTrue(ApkInstallHelper.isSplitApkFile("bundle.apks"))
        assertTrue(ApkInstallHelper.isSplitApkFile("game.xapk"))
        assertTrue(ApkInstallHelper.isSplitApkFile("app.apkm"))
        assertFalse(ApkInstallHelper.isSplitApkFile("app.apk"))
    }

    @Test
    fun resolvesApkMimeEvenWhenOctetStream() {
        assertEquals(
            ApkInstallHelper.MIME_APK,
            ApkInstallHelper.resolveMimeType("CloudBridge.apk", "application/octet-stream", ApkInstallHelper.OPEN_AS_DEFAULT)
        )
        assertEquals(
            "text/*",
            ApkInstallHelper.resolveMimeType("CloudBridge.apk", "application/octet-stream", ApkInstallHelper.OPEN_AS_TEXT)
        )
        assertEquals(
            "image/*",
            ApkInstallHelper.resolveMimeType("photo.bin", "application/octet-stream", ApkInstallHelper.OPEN_AS_IMAGE)
        )
    }

    @Test
    fun computesExpectedSha256HexDigest() {
        val file = tempFolder.newFile("sample.apk")
        file.writeBytes("abc".toByteArray(StandardCharsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ApkInstallHelper.computeSha256Hex(file)
        )
    }
}
