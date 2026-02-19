package ca.pkay.rcloneexplorer.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RemoteNameUtilTest {

    @Test
    public void remoteNameAsFs() {
        assertEquals("remote:", RemoteNameUtil.remoteNameAsFs("remote"));
        assertEquals("remote::", RemoteNameUtil.remoteNameAsFs("remote:"));
        assertEquals(":", RemoteNameUtil.remoteNameAsFs(""));
    }
}
