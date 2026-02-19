package ca.pkay.rcloneexplorer.Items;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteItemTest {

    @Test
    public void testHasTrashCan() {
        // Test cases that should return true
        assertTrue("Google Drive should have trash can", new RemoteItem("drive", "drive").hasTrashCan());
        assertTrue("pCloud should have trash can", new RemoteItem("pcloud", "pcloud").hasTrashCan());
        assertTrue("Yandex should have trash can", new RemoteItem("yandex", "yandex").hasTrashCan());

        // Test cases that should return false
        assertFalse("Dropbox should not have trash can", new RemoteItem("dropbox", "dropbox").hasTrashCan());
        assertFalse("Local should not have trash can", new RemoteItem("local", "local").hasTrashCan());
        assertFalse("SFTP should not have trash can", new RemoteItem("sftp", "sftp").hasTrashCan());
    }

    @Test
    public void testIsDirectoryModifiedTimeSupported() {
        // Test cases that should return false
        assertFalse("Dropbox should not support directory mod time", new RemoteItem("dropbox", "dropbox").isDirectoryModifiedTimeSupported());
        assertFalse("B2 should not support directory mod time", new RemoteItem("b2", "b2").isDirectoryModifiedTimeSupported());
        assertFalse("Google Photos should not support directory mod time", new RemoteItem("photos", "google photos").isDirectoryModifiedTimeSupported());

        // Test cases that should return true
        assertTrue("Google Drive should support directory mod time", new RemoteItem("drive", "drive").isDirectoryModifiedTimeSupported());
        assertTrue("Local should support directory mod time", new RemoteItem("local", "local").isDirectoryModifiedTimeSupported());
        assertTrue("SFTP should support directory mod time", new RemoteItem("sftp", "sftp").isDirectoryModifiedTimeSupported());
    }
}
