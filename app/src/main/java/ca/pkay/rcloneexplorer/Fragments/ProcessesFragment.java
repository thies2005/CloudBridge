package ca.pkay.rcloneexplorer.Fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import java.util.List;
import ca.pkay.rcloneexplorer.ProcessRegistry;
import ca.pkay.rcloneexplorer.R;

public class ProcessesFragment extends Fragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView text;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (text == null) return;
            List<String> processes = ProcessRegistry.snapshot();
            for (int i = 0; i < processes.size(); i++) if ("daemon".equals(processes.get(i))) processes.set(i, getString(R.string.rclone_daemon));
            text.setText(processes.isEmpty() ? getString(R.string.no_current_processes) : android.text.TextUtils.join("\n\n", processes));
            handler.postDelayed(this, 1000);
        }
    };
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        text = new TextView(requireContext());
        text.setTextIsSelectable(true);
        text.setTextSize(15f);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(text);
        return scroll;
    }
    @Override public void onResume() { super.onResume(); requireActivity().setTitle(R.string.current_processes); refresh.run(); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroyView() { text = null; super.onDestroyView(); }
}
