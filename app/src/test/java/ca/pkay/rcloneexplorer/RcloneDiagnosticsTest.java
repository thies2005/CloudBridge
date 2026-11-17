package ca.pkay.rcloneexplorer;

import org.junit.Test;
import static org.junit.Assert.*;

public class RcloneDiagnosticsTest {
    @Test public void detectsSharedClientRetirementWithoutTreatingOrdinaryDebugAsWarning() {
        assertTrue(RcloneDiagnostics.isSharedClientRetirement("WARNING: shared client_id is being retired"));
        assertFalse(RcloneDiagnostics.isSharedClientRetirement("DEBUG: ModTime is unsupported"));
        assertFalse(RcloneDiagnostics.isSharedClientRetirement("WARNING: personal client ID is deprecated"));
    }
    @Test public void recognizesSignalCancellationButNotQuotaFailure() {
        assertTrue(RcloneDiagnostics.isCancellation(130));
        assertTrue(RcloneDiagnostics.isCancellation(143));
        assertFalse(RcloneDiagnostics.isCancellation(1));
        assertFalse(RcloneDiagnostics.isCancellation(0));
    }
}
