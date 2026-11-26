package ca.pkay.rcloneexplorer.Database.json;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ca.pkay.rcloneexplorer.util.ActivityHelper;

public class SharedPreferencesBackupTest {

    @Test
    public void clampsTransfersToValidRange() {
        assertEquals("4", SharedPreferencesBackup.sanitizeTransfers(null));
        assertEquals("4", SharedPreferencesBackup.sanitizeTransfers("not-a-number"));
        assertEquals("1", SharedPreferencesBackup.sanitizeTransfers("0"));
        assertEquals("1", SharedPreferencesBackup.sanitizeTransfers("-5"));
        assertEquals("8", SharedPreferencesBackup.sanitizeTransfers(" 8 "));
        assertEquals("32", SharedPreferencesBackup.sanitizeTransfers("999"));
    }

    @Test
    public void validatesAndNormalizesThemeValues() {
        assertEquals(ActivityHelper.DARK, SharedPreferencesBackup.sanitizeTheme(true));
        assertEquals(ActivityHelper.LIGHT, SharedPreferencesBackup.sanitizeTheme(false));
        assertEquals(ActivityHelper.FOLLOW_SYSTEM, SharedPreferencesBackup.sanitizeTheme("0"));
        assertEquals(ActivityHelper.DARK, SharedPreferencesBackup.sanitizeTheme("1"));
        assertEquals(ActivityHelper.LIGHT, SharedPreferencesBackup.sanitizeTheme("2"));
        assertEquals(ActivityHelper.AMOLED, SharedPreferencesBackup.sanitizeTheme("3"));
        assertEquals(ActivityHelper.FOLLOW_SYSTEM, SharedPreferencesBackup.sanitizeTheme("99"));
        assertEquals(ActivityHelper.FOLLOW_SYSTEM, SharedPreferencesBackup.sanitizeTheme("bad"));
        assertEquals(ActivityHelper.FOLLOW_SYSTEM, SharedPreferencesBackup.sanitizeTheme(null));
    }
}
