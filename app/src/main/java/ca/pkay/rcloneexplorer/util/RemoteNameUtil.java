package ca.pkay.rcloneexplorer.util;

public class RemoteNameUtil {
    /**
     * Convert a rcloneExplorer remote name into fs parameter format
     * @param remoteName
     * @return
     */
    public static String remoteNameAsFs(String remoteName) {
        remoteName += ':';
        return remoteName;
    }
}
