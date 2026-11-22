package ca.pkay.rcloneexplorer.Database.json;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ca.pkay.rcloneexplorer.Items.Task;

public class ImporterTest {

    private static final String DATA_DIR = "/data/user/0/de.schuelken.cloudbridge";

    @Test
    public void failsClosedWhenInternalDataDirMissing() {
        Task task = new Task(1L);
        task.setRemoteId("gdrive");
        task.setLocalPath("/storage/emulated/0/Documents");
        assertFalse(Importer.isSafeTask(task, null));
        assertFalse(Importer.isSafeTask(task, ""));
        assertFalse(Importer.isSafeTask(null, DATA_DIR));
    }

    @Test
    public void rejectsUnsafeRemoteIdsIncludingDestinationRemote() {
        Task badSource = new Task(1L);
        badSource.setRemoteId("--rc-addr=evil");
        badSource.setLocalPath("/storage/emulated/0/Sync");
        assertFalse(Importer.isSafeTask(badSource, DATA_DIR));

        Task badDest = new Task(2L);
        badDest.setRemoteId("gdrive");
        badDest.setRemoteId2("-h");
        assertFalse(Importer.isSafeTask(badDest, DATA_DIR));

        Task colonRemote = new Task(3L);
        colonRemote.setRemoteId("gdrive:secret");
        assertFalse(Importer.isSafeTask(colonRemote, DATA_DIR));
    }

    @Test
    public void rejectsInternalDataExactChildAncestorAndInvalidLocalPaths() {
        String[] unsafePaths = new String[]{
                DATA_DIR,
                DATA_DIR + "/files/rclone.conf",
                "/data/user/0/de.schuelken.cloudbridge/../de.schuelken.cloudbridge/rclone.conf",
                "/data/user/0",
                "/data",
                "/",
                "relative/path",
                ":local",
                "/storage/emulated/0/file\0name"
        };
        for (String path : unsafePaths) {
            Task task = new Task(10L);
            task.setRemoteId("gdrive");
            task.setLocalPath(path);
            assertFalse("Must reject unsafe localPath: " + path, Importer.isSafeTask(task, DATA_DIR));
        }
    }

    @Test
    public void acceptsSafeLocalAndCloudToCloudTasks() {
        Task localTask = new Task(11L);
        localTask.setRemoteId("My Drive_1.sync-test");
        localTask.setLocalPath("/storage/emulated/0/Documents/Notes");
        assertTrue(Importer.isSafeTask(localTask, DATA_DIR));

        Task cloudTask = new Task(12L);
        cloudTask.setRemoteId("gdrive");
        cloudTask.setRemoteId2("b2-backup");
        cloudTask.setLocalPath("");
        assertTrue(Importer.isSafeTask(cloudTask, DATA_DIR));
    }
}
