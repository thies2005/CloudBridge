package ca.pkay.rcloneexplorer.util;

import java.io.File;
import java.io.IOException;

public class FileUtil {

    /**
     * Creates a file in the given directory, ensuring that the file path is within the directory.
     * Prevents path traversal attacks (e.g. "../").
     *
     * @param directory The directory to create the file in.
     * @param fileName  The name of the file.
     * @return The created File object.
     * @throws IOException       If an I/O error occurs.
     * @throws SecurityException If the file path is outside the directory.
     */
    public static File createSafeFile(File directory, String fileName) throws IOException, SecurityException {
        File file = new File(directory, fileName);
        String canonicalPath = file.getCanonicalPath();
        String canonicalDirectory = directory.getCanonicalPath();

        if (!canonicalPath.startsWith(canonicalDirectory + File.separator)) {
            // Check if the file is the directory itself or something weird, though usually we want a child.
            // But if fileName is empty, it returns directory.
            // If fileName is ".", it returns directory.
            // We probably want to ensure it is a child or at least safely inside.
            // The requirement is "path traversal".
            // If canonicalPath equals canonicalDirectory, it means fileName was empty or ".".
            // In the context of SharingActivity, we are creating a file to write to. Writing to the cache dir itself (as a file) would fail or be bad.
            // So enforcing strict child is better.
            throw new SecurityException("Invalid file name: " + fileName);
        }
        return file;
    }
}
