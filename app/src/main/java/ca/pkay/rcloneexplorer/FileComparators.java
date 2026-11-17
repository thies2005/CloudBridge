package ca.pkay.rcloneexplorer;

import java.io.File;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ca.pkay.rcloneexplorer.Dialogs.SortDialog;
import ca.pkay.rcloneexplorer.Items.FileItem;

public class FileComparators {

    public static int resolveSortOrder(int sortById, int sortOrderId) {
        boolean ascending = sortOrderId == R.id.radio_sort_ascending;
        if (sortById == R.id.radio_sort_date) {
            return ascending ? SortDialog.MOD_TIME_ASCENDING : SortDialog.MOD_TIME_DESCENDING;
        } else if (sortById == R.id.radio_sort_size) {
            return ascending ? SortDialog.SIZE_ASCENDING : SortDialog.SIZE_DESCENDING;
        } else {
            return ascending ? SortDialog.ALPHA_ASCENDING : SortDialog.ALPHA_DESCENDING;
        }
    }

    public static void sortFileItems(List<FileItem> items, int sortOrder) {
        if (items == null || items.size() <= 1) {
            return;
        }
        Collections.sort(items, forFileItem(sortOrder));
    }

    public static void sortFiles(List<File> files, int sortOrder) {
        if (files == null || files.size() <= 1) {
            return;
        }
        Collections.sort(files, forFile(sortOrder));
    }

    public static Comparator<FileItem> forFileItem(int sortOrder) {
        return new FileItemComparator(sortOrder);
    }

    public static Comparator<File> forFile(int sortOrder) {
        return new LocalFileComparator(sortOrder);
    }

    /**
     * Natural alphanumeric comparison (e.g. "file2" before "file10"), case-insensitive.
     */
    public static int compareNatural(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);

        int lenA = a.length();
        int lenB = b.length();
        int i = 0;
        int j = 0;

        while (i < lenA && j < lenB) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                while (i < lenA && Character.isDigit(a.charAt(i))) i++;
                int startB = j;
                while (j < lenB && Character.isDigit(b.charAt(j))) j++;

                int cmp = compareNumericSlices(a, startA, i, b, startB, j);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int startA = i;
                while (i < lenA && !Character.isDigit(a.charAt(i))) i++;
                int startB = j;
                while (j < lenB && !Character.isDigit(b.charAt(j))) j++;

                int cmp = collator.compare(a.substring(startA, i), b.substring(startB, j));
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return Integer.compare(lenA - i, lenB - j);
    }

    private static int compareNumericSlices(String a, int startA, int endA,
                                            String b, int startB, int endB) {
        int nzA = startA;
        while (nzA < endA && a.charAt(nzA) == '0') nzA++;
        int nzB = startB;
        while (nzB < endB && b.charAt(nzB) == '0') nzB++;

        int sigLenA = endA - nzA;
        int sigLenB = endB - nzB;
        if (sigLenA != sigLenB) {
            return Integer.compare(sigLenA, sigLenB);
        }

        for (int k = 0; k < sigLenA; k++) {
            char da = a.charAt(nzA + k);
            char db = b.charAt(nzB + k);
            if (da != db) {
                return Character.compare(da, db);
            }
        }

        // Same numeric value: fewer leading zeros first (e.g. "2" before "02")
        return Integer.compare(endA - startA, endB - startB);
    }

    private static int compareDirsFirst(boolean dirA, boolean dirB) {
        if (dirA && !dirB) return -1;
        if (!dirA && dirB) return 1;
        return 0;
    }

    private static int compareUnknownLastLong(long a, long b, boolean ascending, boolean unknownIfNonPositive) {
        boolean unknownA = unknownIfNonPositive ? a <= 0 : a < 0;
        boolean unknownB = unknownIfNonPositive ? b <= 0 : b < 0;
        if (unknownA && unknownB) return 0;
        if (unknownA) return 1;
        if (unknownB) return -1;
        return ascending ? Long.compare(a, b) : Long.compare(b, a);
    }

    public static class FileItemComparator implements Comparator<FileItem> {
        private final int sortOrder;

        public FileItemComparator(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        @Override
        public int compare(FileItem a, FileItem b) {
            int dirCmp = compareDirsFirst(a.isDir(), b.isDir());
            if (dirCmp != 0) return dirCmp;

            int cmp = 0;
            switch (sortOrder) {
                case SortDialog.MOD_TIME_ASCENDING:
                    cmp = compareUnknownLastLong(a.getModTime(), b.getModTime(), true, true);
                    break;
                case SortDialog.MOD_TIME_DESCENDING:
                    cmp = compareUnknownLastLong(a.getModTime(), b.getModTime(), false, true);
                    break;
                case SortDialog.SIZE_ASCENDING:
                    if (!a.isDir()) {
                        cmp = compareUnknownLastLong(a.getSize(), b.getSize(), true, false);
                    }
                    break;
                case SortDialog.SIZE_DESCENDING:
                    if (!a.isDir()) {
                        cmp = compareUnknownLastLong(a.getSize(), b.getSize(), false, false);
                    }
                    break;
                case SortDialog.ALPHA_DESCENDING:
                    return compareNatural(b.getName(), a.getName());
                case SortDialog.ALPHA_ASCENDING:
                default:
                    return compareNatural(a.getName(), b.getName());
            }

            if (cmp != 0) return cmp;
            return compareNatural(a.getName(), b.getName());
        }
    }

    public static class LocalFileComparator implements Comparator<File> {
        private final int sortOrder;

        public LocalFileComparator(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        @Override
        public int compare(File a, File b) {
            int dirCmp = compareDirsFirst(a.isDirectory(), b.isDirectory());
            if (dirCmp != 0) return dirCmp;

            int cmp = 0;
            switch (sortOrder) {
                case SortDialog.MOD_TIME_ASCENDING:
                    cmp = compareUnknownLastLong(a.lastModified(), b.lastModified(), true, true);
                    break;
                case SortDialog.MOD_TIME_DESCENDING:
                    cmp = compareUnknownLastLong(a.lastModified(), b.lastModified(), false, true);
                    break;
                case SortDialog.SIZE_ASCENDING:
                    if (!a.isDirectory()) {
                        cmp = compareUnknownLastLong(a.length(), b.length(), true, false);
                    }
                    break;
                case SortDialog.SIZE_DESCENDING:
                    if (!a.isDirectory()) {
                        cmp = compareUnknownLastLong(a.length(), b.length(), false, false);
                    }
                    break;
                case SortDialog.ALPHA_DESCENDING:
                    return compareNatural(b.getName(), a.getName());
                case SortDialog.ALPHA_ASCENDING:
                default:
                    return compareNatural(a.getName(), b.getName());
            }

            if (cmp != 0) return cmp;
            return compareNatural(a.getName(), b.getName());
        }
    }

    // Legacy aliases preserved for any direct references
    public static class SortAlphaDescending extends FileItemComparator {
        public SortAlphaDescending() { super(SortDialog.ALPHA_DESCENDING); }
    }

    public static class SortAlphaAscending extends FileItemComparator {
        public SortAlphaAscending() { super(SortDialog.ALPHA_ASCENDING); }
    }

    public static class SortSizeDescending extends FileItemComparator {
        public SortSizeDescending() { super(SortDialog.SIZE_DESCENDING); }
    }

    public static class SortSizeAscending extends FileItemComparator {
        public SortSizeAscending() { super(SortDialog.SIZE_ASCENDING); }
    }

    public static class SortModTimeDescending extends FileItemComparator {
        public SortModTimeDescending() { super(SortDialog.MOD_TIME_DESCENDING); }
    }

    public static class SortModTimeAscending extends FileItemComparator {
        public SortModTimeAscending() { super(SortDialog.MOD_TIME_ASCENDING); }
    }

    public static class SortFileAlphaDescending extends LocalFileComparator {
        public SortFileAlphaDescending() { super(SortDialog.ALPHA_DESCENDING); }
    }

    public static class SortFileAlphaAscending extends LocalFileComparator {
        public SortFileAlphaAscending() { super(SortDialog.ALPHA_ASCENDING); }
    }

    public static class SortFileSizeDescending extends LocalFileComparator {
        public SortFileSizeDescending() { super(SortDialog.SIZE_DESCENDING); }
    }

    public static class SortFileSizeAscending extends LocalFileComparator {
        public SortFileSizeAscending() { super(SortDialog.SIZE_ASCENDING); }
    }

    public static class SortFileModTimeDescending extends LocalFileComparator {
        public SortFileModTimeDescending() { super(SortDialog.MOD_TIME_DESCENDING); }
    }

    public static class SortFileModTimeAscending extends LocalFileComparator {
        public SortFileModTimeAscending() { super(SortDialog.MOD_TIME_ASCENDING); }
    }
}
