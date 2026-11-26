package ca.pkay.rcloneexplorer.Fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

import ca.pkay.rcloneexplorer.ProcessRegistry;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.notifications.support.StatusObject;

public class ProcessesFragment extends Fragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout container;
    private View scrollView;
    private TextView emptyView;
    private StatusObject statusHelper;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (container == null || !isAdded()) return;
            renderProcesses();
            handler.postDelayed(this, 1000L);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup containerView, @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_processes, containerView, false);
        container = root.findViewById(R.id.processes_container);
        scrollView = root.findViewById(R.id.processes_scroll);
        emptyView = root.findViewById(R.id.processes_empty);
        statusHelper = new StatusObject(requireContext());
        return root;
    }

    private void renderProcesses() {
        List<ProcessRegistry.ProcessInfo> infos = ProcessRegistry.snapshotInfo();
        if (infos.isEmpty()) {
            container.removeAllViews();
            scrollView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }

        scrollView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        long nowMs = System.currentTimeMillis();

        // Reuse or add/trim child cards to avoid resetting scroll/selection unnecessarily.
        while (container.getChildCount() > infos.size()) {
            container.removeViewAt(container.getChildCount() - 1);
        }
        while (container.getChildCount() < infos.size()) {
            View card = inflater.inflate(R.layout.item_process_card, container, false);
            container.addView(card);
        }

        for (int i = 0; i < infos.size(); i++) {
            bindCard(container.getChildAt(i), infos.get(i), nowMs);
        }
    }

    private void bindCard(View card, ProcessRegistry.ProcessInfo info, long nowMs) {
        TextView titleView = card.findViewById(R.id.process_title);
        TextView percentView = card.findViewById(R.id.process_percent);
        TextView operationView = card.findViewById(R.id.process_operation);
        LinearProgressIndicator progressBar = card.findViewById(R.id.process_progress);
        TextView summaryView = card.findViewById(R.id.process_summary);
        TextView detailsView = card.findViewById(R.id.process_details);

        String displayTitle = formatOperation(info.title);
        String displayOperation = formatOperation(info.operation);
        titleView.setText(displayTitle);
        percentView.setText(getString(R.string.process_percent, info.percent));

        if (!displayTitle.equals(displayOperation)) {
            operationView.setVisibility(View.VISIBLE);
            operationView.setText(displayOperation);
        } else {
            operationView.setVisibility(View.GONE);
        }

        boolean wantIndeterminate = !info.determinate;
        if (progressBar.isIndeterminate() != wantIndeterminate) {
            progressBar.setVisibility(View.GONE);
            progressBar.setIndeterminate(wantIndeterminate);
            progressBar.setVisibility(View.VISIBLE);
        }
        if (info.determinate) {
            progressBar.setProgressCompat(info.percent, true);
        }

        String elapsedFormatted = statusHelper.prettyPrintDuration(info.getElapsedSeconds(nowMs));
        if (!info.content.isEmpty()) {
            summaryView.setText(info.content);
        } else if (ProcessRegistry.OPERATION_DAEMON.equals(info.operation)) {
            summaryView.setText(getString(R.string.process_daemon_idle_elapsed, elapsedFormatted));
        } else {
            String etaText = !info.eta.isEmpty() ? info.eta : getString(R.string.process_eta_calculating);
            summaryView.setText(getString(R.string.process_elapsed_eta_fallback, elapsedFormatted, etaText));
        }

        List<String> detailLines = new ArrayList<>();
        if (!info.details.isEmpty()) {
            detailLines.addAll(info.details);
        } else {
            if (!info.speed.isEmpty()) {
                detailLines.add(getString(R.string.sync_notification_speed, info.speed));
            }
            if (!info.eta.isEmpty()) {
                detailLines.add(getString(R.string.sync_notification_remaining, info.eta));
            }
            detailLines.add(getString(R.string.sync_notification_elapsed, elapsedFormatted));
        }

        if (!detailLines.isEmpty()) {
            detailsView.setVisibility(View.VISIBLE);
            detailsView.setText(TextUtils.join("\n", detailLines));
        } else {
            detailsView.setVisibility(View.GONE);
        }
    }

    private String formatOperation(String raw) {
        if (ProcessRegistry.OPERATION_DAEMON.equals(raw)) {
            return getString(R.string.rclone_daemon);
        }
        if (ProcessRegistry.OPERATION_SEARCH_INDEX.equals(raw)) {
            return getString(R.string.global_search_map_drives);
        }
        return raw;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.current_processes);
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refresh);
        container = null;
        scrollView = null;
        emptyView = null;
        statusHelper = null;
        super.onDestroyView();
    }
}
