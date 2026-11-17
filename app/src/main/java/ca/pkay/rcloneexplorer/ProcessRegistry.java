package ca.pkay.rcloneexplorer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tracks only processes launched by this app. Never stores command arguments. */
public final class ProcessRegistry {
    private static final Map<Process, String> processes = new LinkedHashMap<>();
    private static final List<String> OPERATIONS = Arrays.asList("lsjson", "cat", "rcat", "copy", "copyto", "sync", "move", "moveto", "delete", "deletefile", "purge", "mkdir", "serve", "about", "link", "md5sum", "sha1sum", "config", "version");
    private ProcessRegistry() { }
    public static synchronized void register(Process process, String[] command) {
        snapshot();
        String name = "rclone";
        // Flags and config paths precede the command. Only expose a known operation.
        for (int i = 1; i < command.length; i++) {
            if ("--config".equals(command[i]) || "--cache-chunk-path".equals(command[i]) || "--cache-db-path".equals(command[i])) { i++; continue; }
            if (OPERATIONS.contains(command[i])) { name = "rclone " + command[i]; break; }
        }
        processes.put(process, name);
    }
    public static synchronized void registerRcd(Process process) { snapshot(); processes.put(process, "daemon"); }
    public static synchronized List<String> snapshot() {
        java.util.Iterator<Map.Entry<Process, String>> iterator = processes.entrySet().iterator();
        while (iterator.hasNext()) {
            try { iterator.next().getKey().exitValue(); iterator.remove(); }
            catch (IllegalThreadStateException running) { /* Still running. */ }
        }
        return new ArrayList<>(processes.values());
    }
}
