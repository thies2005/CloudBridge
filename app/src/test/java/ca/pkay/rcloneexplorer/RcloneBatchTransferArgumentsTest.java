package ca.pkay.rcloneexplorer;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RcloneBatchTransferArgumentsTest {
    @Test
    public void usesRawNamesAndOptionSeparatorForBatchCopies() {
        File manifest = new File("selected names");
        File report = new File("comparison report");
        List<String> arguments = Rclone.savedTransferArguments("copy",
                Rclone.batchCopyOptions(manifest, report, "4"), "src:path", "-target");

        assertEquals(Arrays.asList("copy", "--files-from-raw", manifest.getAbsolutePath(),
                "--no-traverse", "--combined", report.getAbsolutePath(), "--transfers", "4",
                "--stats=1s", "--stats-log-level", "NOTICE", "--use-json-log",
                "--", "src:path", "-target"), arguments);
    }
}
