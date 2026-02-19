package ca.pkay.rcloneexplorer.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class FileUtilTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createSafeFile_validName() throws IOException {
        File parent = folder.newFolder("cache");
        String fileName = "test.txt";
        File result = FileUtil.createSafeFile(parent, fileName);
        assertEquals(new File(parent, fileName).getCanonicalPath(), result.getCanonicalPath());
    }

    @Test(expected = SecurityException.class)
    public void createSafeFile_parentTraversal() throws IOException {
        File parent = folder.newFolder("cache");
        String fileName = "../test.txt";
        FileUtil.createSafeFile(parent, fileName);
    }

    @Test
    public void createSafeFile_absolutePath() throws IOException {
         File parent = folder.newFolder("cache");
         File grandParent = parent.getParentFile();
         String fileName = grandParent.getAbsolutePath() + File.separator + "escaped.txt";

         try {
             File result = FileUtil.createSafeFile(parent, fileName);
             // If it didn't throw, verify it is indeed safe (nested inside parent)
             // This handles environments where File(parent, absPath) creates a nested file.
             String canonicalPath = result.getCanonicalPath();
             String canonicalParent = parent.getCanonicalPath();
             if (!canonicalPath.startsWith(canonicalParent + File.separator)) {
                 fail("File created outside parent: " + canonicalPath);
             }
         } catch (SecurityException e) {
             // This is also acceptable (and expected on systems where absolute path ignores parent)
         }
    }

    @Test(expected = SecurityException.class)
    public void createSafeFile_currentDirectory() throws IOException {
        File parent = folder.newFolder("cache");
        String fileName = ".";
        FileUtil.createSafeFile(parent, fileName);
    }

    @Test(expected = SecurityException.class)
    public void createSafeFile_emptyName() throws IOException {
        File parent = folder.newFolder("cache");
        String fileName = "";
        FileUtil.createSafeFile(parent, fileName);
    }
}
