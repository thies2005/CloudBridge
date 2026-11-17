package ca.pkay.rcloneexplorer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
    @Test public void onlyOperationNameIsShownAndFinishedProcessesArePruned() {
        FakeProcess process = new FakeProcess();
        ProcessRegistry.register(process, new String[]{"rclone", "--config", "/private/config", "lsjson", "secret:path"});
        assertTrue(ProcessRegistry.snapshot().contains("rclone lsjson"));
        for (String name : ProcessRegistry.snapshot()) assertFalse(name.contains("secret"));
        process.destroy();
        assertFalse(ProcessRegistry.snapshot().contains("rclone lsjson"));
    }
}
