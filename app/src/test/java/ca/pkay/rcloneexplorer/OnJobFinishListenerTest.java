package ca.pkay.rcloneexplorer;

import org.junit.Test;

import java.io.FileNotFoundException;

import static org.junit.Assert.*;

public class OnJobFinishListenerTest {
    private final VirtualContentProvider.OnJobFinishListener listener =
            new VirtualContentProvider.OnJobFinishListener();

    @Test
    public void callbackBeforeWaitRetainsSuccessfulCompletion() throws FileNotFoundException {
        RcloneRcd.JobStatusResponse status = new RcloneRcd.JobStatusResponse();
        status.success = true;
        listener.handleJobStatus(status);
        listener.awaitSuccess(0);
    }

    @Test
    public void unsuccessfulCompletionIsReportedToCaller() {
        listener.handleJobStatus(new RcloneRcd.JobStatusResponse());
        assertThrows(FileNotFoundException.class, () -> listener.awaitSuccess(0));
    }

    @Test
    public void pendingJobTimesOutAndCanStillComplete() throws FileNotFoundException {
        assertThrows(FileNotFoundException.class, () -> listener.awaitSuccess(0));
        RcloneRcd.JobStatusResponse status = new RcloneRcd.JobStatusResponse();
        status.success = true;
        listener.handleJobStatus(status);
        listener.awaitSuccess(0);
    }

    @Test
    public void interruptedWaitRestoresInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(FileNotFoundException.class, () -> listener.awaitSuccess(0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
