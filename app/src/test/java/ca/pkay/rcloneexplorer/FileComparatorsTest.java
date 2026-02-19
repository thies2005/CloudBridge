package ca.pkay.rcloneexplorer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import ca.pkay.rcloneexplorer.Items.FileItem;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class FileComparatorsTest {

    @Test
    public void testSortAlphaDescending() {
        FileComparators.SortAlphaDescending comparator = new FileComparators.SortAlphaDescending();

        // 1. Dir vs File
        FileItem dir = mock(FileItem.class);
        when(dir.isDir()).thenReturn(true);

        FileItem file = mock(FileItem.class);
        when(file.isDir()).thenReturn(false);

        // Dir vs File: Dir comes first (return -1)
        assertTrue("Directory should come before File", comparator.compare(dir, file) < 0);
        // File vs Dir: File comes last (return 1)
        assertTrue("File should come after Directory", comparator.compare(file, dir) > 0);

        // 2. Dir vs Dir: Descending name
        FileItem dirA = mock(FileItem.class);
        when(dirA.isDir()).thenReturn(true);
        when(dirA.getName()).thenReturn("A");

        FileItem dirB = mock(FileItem.class);
        when(dirB.isDir()).thenReturn(true);
        when(dirB.getName()).thenReturn("B");

        // "B" comes before "A" in descending sort
        assertTrue("B should come before A in descending sort", comparator.compare(dirB, dirA) < 0);
        assertTrue("A should come after B in descending sort", comparator.compare(dirA, dirB) > 0);

        // 3. File vs File: Descending name
        FileItem fileA = mock(FileItem.class);
        when(fileA.isDir()).thenReturn(false);
        when(fileA.getName()).thenReturn("a");

        FileItem fileB = mock(FileItem.class);
        when(fileB.isDir()).thenReturn(false);
        when(fileB.getName()).thenReturn("B");

        // "B" comes before "a" (case insensitive sort: 'a' > 'b')
        assertTrue("a should come after B in descending sort", comparator.compare(fileA, fileB) > 0);
        assertTrue("B should come before a in descending sort", comparator.compare(fileB, fileA) < 0);
    }

    @Test
    public void testSortAlphaAscending() {
        FileComparators.SortAlphaAscending comparator = new FileComparators.SortAlphaAscending();

        FileItem dir = mock(FileItem.class);
        when(dir.isDir()).thenReturn(true);

        FileItem file = mock(FileItem.class);
        when(file.isDir()).thenReturn(false);

        assertTrue("Directory should come before File", comparator.compare(dir, file) < 0);

        FileItem fileA = mock(FileItem.class);
        when(fileA.isDir()).thenReturn(false);
        when(fileA.getName()).thenReturn("a");

        FileItem fileB = mock(FileItem.class);
        when(fileB.isDir()).thenReturn(false);
        when(fileB.getName()).thenReturn("B");

        // Ascending: "a" vs "B". "a" comes before "b".
        assertTrue("a should come before B in ascending sort", comparator.compare(fileA, fileB) < 0);
    }

    @Test
    public void testSortSizeDescending() {
        FileComparators.SortSizeDescending comparator = new FileComparators.SortSizeDescending();

        FileItem dir = mock(FileItem.class);
        when(dir.isDir()).thenReturn(true);

        FileItem file = mock(FileItem.class);
        when(file.isDir()).thenReturn(false);

        // Dir comes before File regardless of size
        assertTrue("Directory should come before File", comparator.compare(dir, file) < 0);

        // Files by size descending
        FileItem fileBig = mock(FileItem.class);
        when(fileBig.isDir()).thenReturn(false);
        when(fileBig.getSize()).thenReturn(1000L);

        FileItem fileSmall = mock(FileItem.class);
        when(fileSmall.isDir()).thenReturn(false);
        when(fileSmall.getSize()).thenReturn(10L);

        // Big comes before Small
        assertTrue("Big file should come before Small file", comparator.compare(fileBig, fileSmall) < 0);

        // Dirs by name ascending (special logic in code)
        FileItem dirA = mock(FileItem.class);
        when(dirA.isDir()).thenReturn(true);
        when(dirA.getName()).thenReturn("A");

        FileItem dirB = mock(FileItem.class);
        when(dirB.isDir()).thenReturn(true);
        when(dirB.getName()).thenReturn("B");

        // A before B
        assertTrue("Dir A should come before Dir B", comparator.compare(dirA, dirB) < 0);
    }

    @Test
    public void testSortSizeAscending() {
        FileComparators.SortSizeAscending comparator = new FileComparators.SortSizeAscending();

        FileItem fileBig = mock(FileItem.class);
        when(fileBig.isDir()).thenReturn(false);
        when(fileBig.getSize()).thenReturn(1000L);

        FileItem fileSmall = mock(FileItem.class);
        when(fileSmall.isDir()).thenReturn(false);
        when(fileSmall.getSize()).thenReturn(10L);

        // Small before Big
        assertTrue("Small file should come before Big file", comparator.compare(fileSmall, fileBig) < 0);
    }

    @Test
    public void testSortModTimeDescending() {
        FileComparators.SortModTimeDescending comparator = new FileComparators.SortModTimeDescending();

        FileItem fileNew = mock(FileItem.class);
        when(fileNew.isDir()).thenReturn(false);
        when(fileNew.getModTime()).thenReturn(2000L);

        FileItem fileOld = mock(FileItem.class);
        when(fileOld.isDir()).thenReturn(false);
        when(fileOld.getModTime()).thenReturn(1000L);

        // New before Old
        assertTrue("New file should come before Old file", comparator.compare(fileNew, fileOld) < 0);
    }

    @Test
    public void testSortModTimeAscending() {
        FileComparators.SortModTimeAscending comparator = new FileComparators.SortModTimeAscending();

        FileItem fileNew = mock(FileItem.class);
        when(fileNew.isDir()).thenReturn(false);
        when(fileNew.getModTime()).thenReturn(2000L);

        FileItem fileOld = mock(FileItem.class);
        when(fileOld.isDir()).thenReturn(false);
        when(fileOld.getModTime()).thenReturn(1000L);

        // Old before New
        assertTrue("Old file should come before New file", comparator.compare(fileOld, fileNew) < 0);
    }
}
