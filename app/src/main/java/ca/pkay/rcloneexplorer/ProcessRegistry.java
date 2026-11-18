package ca.pkay.rcloneexplorer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.pkay.rcloneexplorer.notifications.support.StatusObject;

/**
 * Tracks processes and background indexing/mapping tasks launched by this app.
 * Never stores raw command arguments or secrets.
 */
public final class ProcessRegistry {

    public static final String OPERATION_DAEMON = "daemon";
    public static final String OPERATION_SEARCH_INDEX = "search_index";

    public static final class ProcessInfo {
        public final String operation;
        public final String title;
        public final int percent;
        public final boolean determinate;
        public final String content;
        public final String eta;
        public final String speed;
        public final List<String> details;
        public final long startedAtMs;

        public ProcessInfo(
                String operation,
                String title,
                int percent,
                boolean determinate,
                String content,
                String eta,
                String speed,
                List<String> details,
                long startedAtMs
        ) {
            this.operation = operation != null ? operation : "rclone";
            this.title = (title != null && !title.isEmpty()) ? title : this.operation;
            this.percent = Math.max(0, Math.min(100, percent));
            this.determinate = determinate;
            this.content = content != null ? content : "";
            this.eta = eta != null ? eta : "";
            this.speed = speed != null ? speed : "";
            this.details = details != null
                    ? Collections.unmodifiableList(new ArrayList<>(details))
                    : Collections.emptyList();
            this.startedAtMs = startedAtMs;
        }

        public int getElapsedSeconds(long nowMs) {
            return (int) Math.max(0L, (nowMs - startedAtMs) / 1000L);
        }
    }

    private static final class Entry {
        final String operation;
        String title;
        int percent;
        boolean determinate;
        String content = "";
        String eta = "";
        String speed = "";
        List<String> details = Collections.emptyList();
        final long startedAtMs;

        Entry(String operation, String title, long startedAtMs) {
            this.operation = operation;
            this.title = (title != null && !title.isEmpty()) ? title : operation;
            this.startedAtMs = startedAtMs;
        }

        ProcessInfo toInfo() {
            return new ProcessInfo(
                    operation,
                    title,
                    percent,
                    determinate,
                    content,
                    eta,
                    speed,
                    details,
                    startedAtMs
            );
        }
    }

    private static final Map<Process, Entry> processes = new LinkedHashMap<>();
    private static final Map<String, Entry> tasks = new LinkedHashMap<>();
    private static final Map<Process, String> processToTaskKey = new LinkedHashMap<>();
    private static final List<String> OPERATIONS = Arrays.asList(
            "lsjson", "cat", "rcat", "copy", "copyto", "sync", "move", "moveto",
            "delete", "deletefile", "purge", "mkdir", "serve", "about", "link",
            "md5sum", "sha1sum", "config", "version", "lsd", "cleanup", "obscure",
            "listremotes"
    );

    private ProcessRegistry() {
    }

    public static synchronized void register(Process process, String[] command) {
        if (process == null) return;
        pruneFinishedLocked();
        String name = "rclone";
        // Flags and config paths precede the command. Only expose a known operation.
        if (command != null) {
            for (int i = 1; i < command.length; i++) {
                String arg = command[i];
                if ("--config".equals(arg)
                        || "--cache-chunk-path".equals(arg)
                        || "--cache-db-path".equals(arg)
                        || "--contimeout".equals(arg)
                        || "--timeout".equals(arg)
                        || "--retries".equals(arg)) {
                    i++;
                    continue;
                }
                if (OPERATIONS.contains(arg)) {
                    name = "rclone " + arg;
                    break;
                }
            }
        }
        processes.put(process, new Entry(name, name, System.currentTimeMillis()));
    }

    public static synchronized void registerRcd(Process process) {
        if (process == null) return;
        pruneFinishedLocked();
        processes.put(process, new Entry(OPERATION_DAEMON, OPERATION_DAEMON, System.currentTimeMillis()));
    }

    public static synchronized void updateProgress(
            @Nullable Process process,
            @Nullable String title,
            int percent,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        updateProgress(process, title, percent, true, content, eta, speed, details);
    }

    public static synchronized void updateProgress(
            @Nullable Process process,
            @Nullable String title,
            int percent,
            boolean determinate,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        if (process == null) return;
        pruneFinishedLocked();
        Entry entry = processes.get(process);
        if (entry == null) return;
        applyUpdate(entry, title, percent, determinate, content, eta, speed, details);
    }

    public static synchronized void updateFromStatusObject(
            @Nullable Process process,
            @Nullable String title,
            @Nullable StatusObject statusObject
    ) {
        if (process == null || statusObject == null) return;
        pruneFinishedLocked();
        Entry entry = processes.get(process);
        if (entry == null) return;
        if (title != null && !title.trim().isEmpty()) {
            entry.title = title;
        }
        String content = statusObject.getNotificationContent();
        int percent = statusObject.getNotificationPercent();
        List<String> bigText = statusObject.getNotificationBigText();
        if (content.isEmpty() && percent <= 0 && bigText.isEmpty()) {
            return;
        }
        applyUpdate(
                entry,
                title,
                percent,
                true,
                content,
                statusObject.getFormattedEta(),
                statusObject.getSpeed(),
                bigText
        );
    }

    public static synchronized void registerTask(
            String taskKey,
            String operation,
            String title,
            int percent,
            boolean determinate,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        if (taskKey == null || taskKey.isEmpty()) return;
        Entry entry = tasks.get(taskKey);
        if (entry == null) {
            entry = new Entry(
                    operation != null ? operation : "rclone",
                    title,
                    System.currentTimeMillis()
            );
            tasks.put(taskKey, entry);
        }
        applyUpdate(entry, title, percent, determinate, content, eta, speed, details);
    }

    public static synchronized void registerTask(
            String taskKey,
            String operation,
            String title,
            int percent,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        registerTask(taskKey, operation, title, percent, true, content, eta, speed, details);
    }

    public static synchronized void updateTaskProgress(
            String taskKey,
            @Nullable String title,
            int percent,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        if (taskKey == null || taskKey.isEmpty()) return;
        Entry entry = tasks.get(taskKey);
        if (entry == null) return;
        applyUpdate(entry, title, percent, true, content, eta, speed, details);
    }

    public static synchronized void associateProcessWithTask(@Nullable Process process, @Nullable String taskKey) {
        if (process == null || taskKey == null || taskKey.isEmpty()) return;
        processToTaskKey.put(process, taskKey);
    }

    public static synchronized void unregisterTask(@Nullable String taskKey) {
        if (taskKey == null) return;
        tasks.remove(taskKey);
        Iterator<Map.Entry<Process, String>> it = processToTaskKey.entrySet().iterator();
        while (it.hasNext()) {
            if (taskKey.equals(it.next().getValue())) {
                it.remove();
            }
        }
    }

    public static synchronized List<String> snapshot() {
        pruneFinishedLocked();
        List<String> operations = new ArrayList<>(processes.size());
        for (Entry entry : processes.values()) {
            operations.add(entry.operation);
        }
        return operations;
    }

    public static synchronized List<ProcessInfo> snapshotInfo() {
        pruneFinishedLocked();
        List<ProcessInfo> infos = new ArrayList<>(tasks.size() + processes.size());
        for (Entry entry : tasks.values()) {
            infos.add(entry.toInfo());
        }
        for (Map.Entry<Process, Entry> mapEntry : processes.entrySet()) {
            String associatedTask = processToTaskKey.get(mapEntry.getKey());
            if (associatedTask != null && tasks.containsKey(associatedTask)) {
                continue;
            }
            infos.add(mapEntry.getValue().toInfo());
        }
        return infos;
    }

    static synchronized void clearForTest() {
        processes.clear();
        tasks.clear();
        processToTaskKey.clear();
    }

    private static void applyUpdate(
            Entry entry,
            @Nullable String title,
            int percent,
            boolean determinate,
            @Nullable String content,
            @Nullable String eta,
            @Nullable String speed,
            @Nullable List<String> details
    ) {
        if (title != null && !title.trim().isEmpty()) {
            entry.title = title;
        }
        entry.percent = Math.max(0, Math.min(100, percent));
        entry.determinate = determinate;
        if (content != null) {
            entry.content = content;
        }
        if (eta != null) {
            entry.eta = eta;
        }
        if (speed != null) {
            entry.speed = speed;
        }
        if (details != null) {
            entry.details = new ArrayList<>(details);
        }
    }

    private static void pruneFinishedLocked() {
        Iterator<Map.Entry<Process, Entry>> iterator = processes.entrySet().iterator();
        while (iterator.hasNext()) {
            Process proc = iterator.next().getKey();
            try {
                proc.exitValue();
                iterator.remove();
                processToTaskKey.remove(proc);
            } catch (IllegalThreadStateException running) {
                // Still running.
            }
        }
    }
}
