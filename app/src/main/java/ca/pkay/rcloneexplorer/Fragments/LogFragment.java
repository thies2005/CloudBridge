package ca.pkay.rcloneexplorer.Fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.LogRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.util.SyncLog;

public class LogFragment extends Fragment {

    private View fragmentView;

    public LogFragment() {
        // Required empty public constructor
    }

    public static LogFragment newInstance() {
        return new LogFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((FragmentActivity) getContext()).setTitle(R.string.logFragment);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);
        fragmentView = view;

        MaterialButton copyBtn = view.findViewById(R.id.btn_copy_all_logs);
        MaterialButton clearBtn = view.findViewById(R.id.btn_clear_logs);
        copyBtn.setOnClickListener(v -> copyAllLogs());
        clearBtn.setOnClickListener(v -> confirmClearLogs());

        populateLogs(fragmentView);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        populateLogs(fragmentView);
    }

    private void populateLogs(View v) {
        if (v == null) return;
        Context c = v.getContext();

        RecyclerView recyclerView = v.findViewById(R.id.log_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(c));

        ArrayList<JSONObject> logs = SyncLog.getLog(c);
        LogRecyclerViewAdapter recyclerViewAdapter = new LogRecyclerViewAdapter(logs);
        recyclerView.setAdapter(recyclerViewAdapter);

        View emptyState = v.findViewById(R.id.logs_empty_state);
        View actionBar = v.findViewById(R.id.logs_action_bar);
        int itemCount = recyclerViewAdapter.getItemCount();
        recyclerView.setVisibility(itemCount == 0 ? View.GONE : View.VISIBLE);
        if (emptyState != null) {
            emptyState.setVisibility(itemCount == 0 ? View.VISIBLE : View.GONE);
        }
        if (actionBar != null) {
            actionBar.setVisibility(itemCount == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void copyAllLogs() {
        Context c = getContext();
        if (c == null) return;
        ArrayList<JSONObject> logs = SyncLog.getLog(c);
        if (logs.isEmpty()) return;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (JSONObject entry : logs) {
            long ts = entry.optLong(SyncLog.TIMESTAMP, 0L);
            String title = entry.optString(SyncLog.TITLE, "");
            String content = entry.optString(SyncLog.CONTENT, "");
            int type = entry.optInt(SyncLog.TYPE, SyncLog.TYPE_INFO);
            String level = (type == SyncLog.TYPE_ERROR) ? "ERROR" : "INFO";
            String timeStr = ts > 0 ? sdf.format(new Date(ts)) : "";
            sb.append('[').append(timeStr).append("] [").append(level).append("] ")
                    .append(title);
            if (!content.isEmpty()) {
                sb.append(": ").append(content);
            }
            sb.append('\n');
        }

        ClipboardManager clipboard = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("CloudBridge Logs", sb.toString()));
            Toast.makeText(c, R.string.logs_copied_full, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearLogs() {
        Context c = getContext();
        if (c == null) return;
        new MaterialAlertDialogBuilder(c, R.style.RoundedCornersDialog)
                .setTitle(R.string.logs_clear_confirm_title)
                .setMessage(R.string.logs_clear_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logs_clear, (dialog, which) -> {
                    SyncLog.delete(c);
                    populateLogs(fragmentView);
                    Toast.makeText(c, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }
}
