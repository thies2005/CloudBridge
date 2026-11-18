package ca.pkay.rcloneexplorer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProcessRegistryTest {
    private static final class FakeProcess extends Process {
        boolean running = true;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { running = false; return 0; }
        @Override public int exitValue() { if (running) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { running = false; }
    }

    @Before
    public void setUp() {
        ProcessRegistry.clearForTest();
    }

    @After
    public void tearDown() {
        ProcessRegistry.clearForTest();
    }

    @Test
    public void onlyOperationNameIsShownAndFinishedProcessesArePruned() {
        FakeProcess process = new FakeProcess();
        ProcessRegistry.register(process, new String[]{"rclone", "--config", "/private/config", "lsjson", "secret:path"});
        assertTrue(ProcessRegistry.snapshot().contains("rclone lsjson"));
        for (String name : ProcessRegistry.snapshot()) assertFalse(name.contains("secret"));
        process.destroy();
        assertFalse(ProcessRegistry.snapshot().contains("rclone lsjson"));
    }

    @Test
    public void tracksProgressPercentageEtaSpeedAndDetailsForProcesses() {
        FakeProcess process = new FakeProcess();
        ProcessRegistry.register(process, new String[]{"rclone", "--config", "/private/config", "copy", "a:", "b:"});
        ProcessRegistry.updateProgress(
                process,
                "Backup Photos",
                42,
                "21.0 MB of 50.0 MB, 15 Seconds remaining",
                "15 Seconds",
                "2.0 MB/s",
                Arrays.asList("Transferred 21.0 MB of 50.0 MB", "Remaining: 15 Seconds")
        );

        List<ProcessRegistry.ProcessInfo> infos = ProcessRegistry.snapshotInfo();
        assertEquals(1, infos.size());
        ProcessRegistry.ProcessInfo info = infos.get(0);
        assertEquals("rclone copy", info.operation);
        assertEquals("Backup Photos", info.title);
        assertEquals(42, info.percent);
        assertTrue(info.determinate);
        assertEquals("21.0 MB of 50.0 MB, 15 Seconds remaining", info.content);
        assertEquals("15 Seconds", info.eta);
        assertEquals("2.0 MB/s", info.speed);
        assertEquals(2, info.details.size());
        assertTrue(info.getElapsedSeconds(info.startedAtMs + 5000L) >= 5);

        // Percent is clamped to [0, 100] and null title preserves existing title.
        ProcessRegistry.updateProgress(process, null, 137, "Done", "0 Seconds", "0 B/s", Collections.emptyList());
        info = ProcessRegistry.snapshotInfo().get(0);
        assertEquals("Backup Photos", info.title);
        assertEquals(100, info.percent);

        process.destroy();
        assertTrue(ProcessRegistry.snapshotInfo().isEmpty());
    }

    @Test
    public void taskRegistrationAndProcessAssociationSuppressesDuplicateChildEntry() {
        String taskKey = "search_index_drive1";
        ProcessRegistry.registerTask(
                taskKey,
                ProcessRegistry.OPERATION_SEARCH_INDEX,
                "Mapping My Drive for search",
                0,
                false,
                "Preparing drive scan…",
                "",
                "",
                Collections.emptyList()
        );

        FakeProcess child = new FakeProcess();
        ProcessRegistry.register(child, new String[]{"rclone", "--config", "/private/config", "lsjson", "-R", "drive1:"});
        ProcessRegistry.associateProcessWithTask(child, taskKey);
        ProcessRegistry.updateTaskProgress(
                taskKey,
                "Mapping My Drive for search",
                65,
                "650 items mapped, 4 Seconds remaining",
                "4 Seconds",
                "160 items/s",
                Collections.singletonList("Mapped 650 items")
        );

        List<ProcessRegistry.ProcessInfo> infos = ProcessRegistry.snapshotInfo();
        assertEquals(1, infos.size());
        assertEquals(ProcessRegistry.OPERATION_SEARCH_INDEX, infos.get(0).operation);
        assertEquals(65, infos.get(0).percent);
        assertEquals("4 Seconds", infos.get(0).eta);
        assertTrue(infos.get(0).determinate);

        child.destroy();
        ProcessRegistry.unregisterTask(taskKey);
        assertTrue(ProcessRegistry.snapshotInfo().isEmpty());
    }
}
