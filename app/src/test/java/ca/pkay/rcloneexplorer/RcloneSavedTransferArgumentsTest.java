package ca.pkay.rcloneexplorer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.x0b.safdav.file.SafConstants;

public class RcloneSavedTransferArgumentsTest {

    @Test
    public void savedTransferArgumentsPlaceOptionsBeforePathSeparator() {
        List<String> options = Arrays.asList("--transfers", "4", "--stats=1s");

        ArrayList<String> arguments = Rclone.savedTransferArguments(
                "sync",
                options,
                "--remote-source",
                "--remote-destination"
        );

        assertEquals(
                Arrays.asList(
                        "sync",
                        "--transfers",
                        "4",
                        "--stats=1s",
                        "--",
                        "--remote-source",
                        "--remote-destination"
                ),
                arguments
        );
    }

    @Test
    public void safDavSourceUsesSingleStreamEvenForLargeFiles() {
        assertEquals(
                Arrays.asList("copy", "--transfers", "4", "--multi-thread-streams", "0", "--",
                        "saf:large-file", "/output"),
                Rclone.savedTransferArguments("copy", Arrays.asList("--transfers", "4"),
                        "saf:large-file", "/output", true)
        );
    }

    @Test
    public void remoteTypeRecognizesOnlyTheAppWebDavTransport() {
        assertEquals(SafConstants.SAF_REMOTE_NAME, Rclone.remoteType("webdav", SafConstants.SAF_REMOTE_URL));
        assertEquals(SafConstants.SAF_REMOTE_NAME,
                Rclone.remoteType("webdav", SafConstants.SAF_REMOTE_URL + "directory/"));
        assertEquals("webdav", Rclone.remoteType("webdav", "https://example.com/dav/"));
        assertEquals("http", Rclone.remoteType("http", SafConstants.SAF_REMOTE_URL));
    }
}
