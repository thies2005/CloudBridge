package ca.pkay.rcloneexplorer.Fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.LogRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.util.SyncLog;

public class LogFragment extends Fragment {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ArrayList<JSONObject> entries = new ArrayList<>();
    private View root;
    private int generation;
    private boolean logFilesExist;
    public static LogFragment newInstance() { return new LogFragment(); }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_logs, container, false);
        RecyclerView list = root.findViewById(R.id.log_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        root.findViewById(R.id.btn_copy_all_logs).setOnClickListener(v -> copyLogs());
        root.findViewById(R.id.btn_clear_logs).setOnClickListener(v -> confirmClearLogs());
        setEntries(new ArrayList<>());
        return root;
    }
    @Override public void onResume() { super.onResume(); requireActivity().setTitle(R.string.logFragment); load(); }
    private void load() {
        final int request = ++generation;
        Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            ArrayList<JSONObject> result = SyncLog.getLog(context);
            boolean legacy = SyncLog.hasLogFiles(context);
            main.post(() -> { if (root != null && request == generation) { logFilesExist = legacy; setEntries(result); } });
        });
    }
    private void setEntries(ArrayList<JSONObject> result) {
        entries = result;
        RecyclerView list = root.findViewById(R.id.log_list);
        list.setAdapter(new LogRecyclerViewAdapter(entries));
        list.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.logs_empty_state).setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        View guidance = root.findViewById(R.id.logs_oauth_guidance);
        if (guidance != null) {
            guidance.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        }
        root.findViewById(R.id.btn_copy_all_logs).setEnabled(!entries.isEmpty());
        root.findViewById(R.id.btn_clear_logs).setEnabled(!entries.isEmpty() || logFilesExist);
    }
    private void confirmClearLogs() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.RoundedCornersDialog)
                .setTitle(R.string.logs_clear_confirm_title)
                .setMessage(R.string.logs_clear_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logs_clear, (dialog, which) -> clearLogs())
                .show();
    }

    private void clearLogs() {
        ++generation;
        Context context = requireContext().getApplicationContext();
        root.findViewById(R.id.btn_clear_logs).setEnabled(false);
        root.findViewById(R.id.btn_copy_all_logs).setEnabled(false);
        executor.execute(() -> {
            boolean ok = SyncLog.delete(context);
            ArrayList<JSONObject> result = SyncLog.getLog(context);
            boolean legacy = SyncLog.hasLogFiles(context);
            main.post(() -> {
                if (root == null) return;
                logFilesExist = legacy;
                setEntries(result);
                if (ok) {
                    Toast.makeText(context, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.logs_clear_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }
    private void copyLogs() {
        StringBuilder text = new StringBuilder();
        for (JSONObject entry : entries) {
            if (text.length() > 0) text.append("\n\n");
            text.append(LogRecyclerViewAdapter.formatEntry(entry));
            if (text.length() > ca.pkay.rcloneexplorer.util.LogClipboard.MAX_CHARS) {
                Toast.makeText(requireContext(), R.string.logs_too_large, Toast.LENGTH_LONG).show(); return;
            }
        }
        ca.pkay.rcloneexplorer.util.LogClipboard.copy(requireContext(), text.toString());
    }
    @Override public void onDestroyView() { ++generation; root = null; super.onDestroyView(); }
    @Override public void onDestroy() { executor.shutdown(); super.onDestroy(); }
}
