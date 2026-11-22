package ca.pkay.rcloneexplorer.Activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SharingActivityTest {

    @Test
    public void allowsOnlyContentAndFileSchemes() {
        assertTrue(SharingActivity.isAllowedShareScheme("content"));
        assertTrue(SharingActivity.isAllowedShareScheme("CONTENT"));
        assertTrue(SharingActivity.isAllowedShareScheme("file"));
        assertFalse(SharingActivity.isAllowedShareScheme(null));
        assertFalse(SharingActivity.isAllowedShareScheme("http"));
        assertFalse(SharingActivity.isAllowedShareScheme("https"));
        assertFalse(SharingActivity.isAllowedShareScheme("android.resource"));
    }

    @Test
    public void stripsMultiUserPrefixAndRejectsOwnAuthorities() {
        Set<String> own = new HashSet<>(Arrays.asList(
                "de.schuelken.cloudbridge.vcp",
                "de.schuelken.cloudbridge.fileprovider"
        ));

        assertEquals("de.schuelken.cloudbridge.vcp", SharingActivity.normalizeAuthority("10@de.schuelken.cloudbridge.vcp"));
        assertNull(SharingActivity.normalizeAuthority(""));
        assertNull(SharingActivity.normalizeAuthority("10@"));

        assertTrue(SharingActivity.isOwnAuthority("de.schuelken.cloudbridge.vcp", own));
        assertTrue(SharingActivity.isOwnAuthority("0@DE.SCHUELKEN.CLOUDBRIDGE.FILEPROVIDER", own));
        assertFalse(SharingActivity.isOwnAuthority("com.android.externalstorage.documents", own));
    }

    @Test
    public void resolvedProviderCheckAllowsNullOnAndroid11AndBlocksOwnPackageOrUid() {
        assertFalse(SharingActivity.isResolvedOwnProvider(null, -1, "de.schuelken.cloudbridge", 10234));
        assertFalse(SharingActivity.isResolvedOwnProvider("com.google.android.apps.photos", 10555, "de.schuelken.cloudbridge", 10234));
        assertTrue(SharingActivity.isResolvedOwnProvider("de.schuelken.cloudbridge", 10999, "de.schuelken.cloudbridge", 10234));
        assertTrue(SharingActivity.isResolvedOwnProvider("com.other.pkg", 10234, "de.schuelken.cloudbridge", 10234));
    }
}
