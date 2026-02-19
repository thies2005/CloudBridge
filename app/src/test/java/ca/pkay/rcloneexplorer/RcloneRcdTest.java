package ca.pkay.rcloneexplorer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RcloneRcdTest {

    @Test
    public void testRemoteNameAsFs() {
        assertEquals("remote:", RcloneRcd.remoteNameAsFs("remote"));
        assertEquals("remote::", RcloneRcd.remoteNameAsFs("remote:"));
        assertEquals(":", RcloneRcd.remoteNameAsFs(""));
    }
}
