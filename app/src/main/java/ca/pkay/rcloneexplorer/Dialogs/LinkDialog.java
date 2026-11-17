package ca.pkay.rcloneexplorer.Dialogs;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;

public class LinkDialog extends DialogFragment {

    private static final String SAVED_LINK_URL = "ca.pkay.rcexplorer.LinkDialog.LINK_URL";
    private static final String SAVED_REMOTE = "ca.pkay.rcexplorer.LinkDialog.REMOTE";
    private static final String SAVED_PATH = "ca.pkay.rcexplorer.LinkDialog.PATH";

    private Context context;
    private String linkUrl;
    @Nullable private RemoteItem remote;
    @Nullable private String linkPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public LinkDialog() {}

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            linkUrl = savedInstanceState.getString(SAVED_LINK_URL);
            remote = savedInstanceState.getParcelable(SAVED_REMOTE);
            linkPath = savedInstanceState.getString(SAVED_PATH);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog);
        LayoutInflater inflater = ((FragmentActivity) context).getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_link, null);

        TextView textView = view.findViewById(R.id.text_view);
        textView.setText(linkUrl);
        textView.setOnClickListener(v -> copyLink());

        MaterialButton copyBtn = view.findViewById(R.id.btn_copy_link);
        MaterialButton shareBtn = view.findViewById(R.id.btn_share_link);
        MaterialButton unlinkBtn = view.findViewById(R.id.btn_unlink);
        ChipGroup expiryGroup = view.findViewById(R.id.expiry_chip_group);
        View expiryLabel = view.findViewById(R.id.expiry_label);

        copyBtn.setOnClickListener(v -> copyLink());
        shareBtn.setOnClickListener(v -> shareLink());

        if (remote == null || linkPath == null) {
            expiryGroup.setVisibility(View.GONE);
            expiryLabel.setVisibility(View.GONE);
            unlinkBtn.setVisibility(View.GONE);
        } else {
            expiryGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                String expire = null;
                if (id == R.id.chip_expire_1h) expire = "1h";
                else if (id == R.id.chip_expire_1d) expire = "24h";
                else if (id == R.id.chip_expire_7d) expire = "168h";
                regenerateWithExpiry(textView, expire);
            });

            unlinkBtn.setOnClickListener(v -> {
                unlinkBtn.setEnabled(false);
                executor.execute(() -> {
                    Rclone rclone = new Rclone(context.getApplicationContext());
                    Rclone.LinkResult result = rclone.linkWithOptions(remote, linkPath, null, true);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        unlinkBtn.setEnabled(true);
                        if (result.isSuccess()) {
                            Toast.makeText(context, R.string.public_link_unlinked, Toast.LENGTH_SHORT).show();
                            dismissAllowingStateLoss();
                        } else {
                            Toast.makeText(context, result.errorMessage, Toast.LENGTH_LONG).show();
                        }
                    });
                });
            });
        }

        builder.setView(view)
                .setPositiveButton(R.string.ok, null);

        return builder.create();
    }

    private void regenerateWithExpiry(TextView textView, @Nullable String expire) {
        if (remote == null || linkPath == null) return;
        executor.execute(() -> {
            Rclone rclone = new Rclone(context.getApplicationContext());
            Rclone.LinkResult result = rclone.linkWithOptions(remote, linkPath, expire, false);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                if (result.isSuccess() && result.url != null) {
                    linkUrl = result.url;
                    textView.setText(linkUrl);
                } else if (result.errorMessage != null) {
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void copyLink() {
        if (linkUrl == null || linkUrl.isEmpty()) return;
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) return;
        ClipData clipData = ClipData.newPlainText("Copied link", linkUrl);
        clipboardManager.setPrimaryClip(clipData);
        Toast.makeText(context, getString(R.string.link_copied_to_clipboard), Toast.LENGTH_SHORT).show();
    }

    private void shareLink() {
        if (linkUrl == null || linkUrl.isEmpty()) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, linkUrl);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.public_link_share)));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_LINK_URL, linkUrl);
        outState.putParcelable(SAVED_REMOTE, remote);
        outState.putString(SAVED_PATH, linkPath);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    public LinkDialog setLinkUrl(String url) {
        this.linkUrl = url;
        return this;
    }

    public LinkDialog setTarget(RemoteItem remote, String linkPath) {
        this.remote = remote;
        this.linkPath = linkPath;
        return this;
    }
}
