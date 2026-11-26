package ca.pkay.rcloneexplorer.Dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;

public class ServeDialog extends DialogFragment {

    private Context context;
    private Callback callback;
    private RadioGroup protocol;
    private CheckBox allowRemoteAccess;
    private CheckBox allowWriteAccess;
    private TextView dlnaWarning;
    private TextView authenticationHeader;
    private TextInputLayout userInputLayout;
    private TextInputLayout passwordInputLayout;
    private EditText user;
    private EditText password;
    private AlertDialog dialog;

    public interface Callback {
        void onServeOptionsSelected(int protocol, boolean allowRemoteAccess, boolean allowWriteAccess, String user, String password);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (getParentFragment() != null) {
            callback = (Callback) getParentFragment();
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog);
        LayoutInflater layoutInflater = ((FragmentActivity) context).getLayoutInflater();
        View view = layoutInflater.inflate(R.layout.dialog_serve, null);

        protocol = view.findViewById(R.id.radio_group_protocol);
        allowRemoteAccess = view.findViewById(R.id.checkbox_allow_remote_access);
        allowWriteAccess = view.findViewById(R.id.checkbox_allow_write_access);
        dlnaWarning = view.findViewById(R.id.text_dlna_warning);
        authenticationHeader = view.findViewById(R.id.text_authentication_header);
        userInputLayout = view.findViewById(R.id.text_input_layout_user);
        passwordInputLayout = view.findViewById(R.id.text_input_layout_password);
        user = view.findViewById(R.id.edit_text_user);
        password = view.findViewById(R.id.edit_text_password);

        // Always default to local-only, read-only exposure on each open.
        allowRemoteAccess.setChecked(false);
        allowWriteAccess.setChecked(false);

        userInputLayout.setHint("Username");
        passwordInputLayout.setHint("Password");

        builder.setTitle(R.string.serve_dialog_title);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> sendCallback());
        builder.setNegativeButton(R.string.cancel, null);
        builder.setView(view);

        allowRemoteAccess.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                Snackbar.make(btn, R.string.serve_dialog_remote_notice_enabled, Snackbar.LENGTH_LONG)
                        .show();
            } else {
                Snackbar.make(btn, R.string.serve_dialog_remote_notice_disabled, Snackbar.LENGTH_LONG)
                        .show();
            }
            updateOkButtonState();
        });

        protocol.setOnCheckedChangeListener((group, checkedId) -> {
            updateProtocolDependentUi(checkedId);
            updateOkButtonState();
        });

        TextWatcher credentialsWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateOkButtonState();
            }
        };
        user.addTextChangedListener(credentialsWatcher);
        password.addTextChangedListener(credentialsWatcher);

        dialog = builder.create();
        dialog.setOnShowListener(d -> {
            updateProtocolDependentUi(protocol.getCheckedRadioButtonId());
            updateOkButtonState();
        });
        return dialog;
    }

    private void updateProtocolDependentUi(int checkedProtocolId) {
        boolean supportsWrite = checkedProtocolId == R.id.radio_webdav || checkedProtocolId == R.id.radio_ftp;
        if (allowWriteAccess != null) {
            allowWriteAccess.setVisibility(supportsWrite ? View.VISIBLE : View.GONE);
            if (!supportsWrite) {
                allowWriteAccess.setChecked(false);
            }
        }
        boolean isDlna = checkedProtocolId == R.id.radio_dlna;
        if (dlnaWarning != null) {
            dlnaWarning.setVisibility(isDlna ? View.VISIBLE : View.GONE);
        }
        int authVisibility = isDlna ? View.GONE : View.VISIBLE;
        if (authenticationHeader != null) {
            authenticationHeader.setVisibility(authVisibility);
        }
        if (userInputLayout != null) {
            userInputLayout.setVisibility(authVisibility);
        }
        if (passwordInputLayout != null) {
            passwordInputLayout.setVisibility(authVisibility);
        }
    }

    private void updateOkButtonState() {
        if (dialog == null) {
            return;
        }
        int checkedProtocolId = protocol.getCheckedRadioButtonId();
        boolean isDlna = checkedProtocolId == R.id.radio_dlna;
        boolean canProceed = !allowRemoteAccess.isChecked()
                || isDlna
                || (!user.getText().toString().trim().isEmpty()
                && !password.getText().toString().trim().isEmpty());
        android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setEnabled(canProceed);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("protocol", protocol.getCheckedRadioButtonId());
        outState.putBoolean("allowRemoteAccess", allowRemoteAccess.isChecked());
        outState.putBoolean("allowWriteAccess", allowWriteAccess.isChecked());
        if (!user.getText().toString().trim().isEmpty()) {
            outState.putString("user", user.getText().toString());
        }
        if (!password.getText().toString().trim().isEmpty()) {
            outState.putString("password", password.getText().toString());
        }
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }

        allowRemoteAccess.setChecked(savedInstanceState.getBoolean("allowRemoteAccess", false));
        allowWriteAccess.setChecked(savedInstanceState.getBoolean("allowWriteAccess", false));
        String savedUser = savedInstanceState.getString("user");
        if (savedUser != null) {
            user.setText(savedUser);
        }

        String savedPassword = savedInstanceState.getString("password");
        if (savedPassword != null) {
            password.setText(savedPassword);
        }

        int savedProtocol = savedInstanceState.getInt("protocol", -1);
        if (savedProtocol == R.id.radio_http || savedProtocol == R.id.radio_dlna
                || savedProtocol == R.id.radio_webdav || savedProtocol == R.id.radio_ftp) {
            protocol.check(savedProtocol);
            updateProtocolDependentUi(savedProtocol);
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;

        if (context instanceof Callback) {
            callback = (Callback) context;
        }
    }

    private void sendCallback() {
        int selectedProtocolId = protocol.getCheckedRadioButtonId();
        int selectedProtocol;
        boolean supportsWrite = false;
        if (selectedProtocolId == R.id.radio_ftp) {
            selectedProtocol = Rclone.SERVE_PROTOCOL_FTP;
            supportsWrite = true;
        } else if (selectedProtocolId == R.id.radio_dlna) {
            selectedProtocol = Rclone.SERVE_PROTOCOL_DLNA;
        } else if (selectedProtocolId == R.id.radio_webdav) {
            selectedProtocol = Rclone.SERVE_PROTOCOL_WEBDAV;
            supportsWrite = true;
        } else {
            selectedProtocol = Rclone.SERVE_PROTOCOL_HTTP;
        }

        boolean allowWrite = supportsWrite && allowWriteAccess != null && allowWriteAccess.isChecked();
        String username = selectedProtocol == Rclone.SERVE_PROTOCOL_DLNA ? "" : user.getText().toString();
        String pass = selectedProtocol == Rclone.SERVE_PROTOCOL_DLNA ? "" : password.getText().toString();
        callback.onServeOptionsSelected(selectedProtocol, allowRemoteAccess.isChecked(), allowWrite, username, pass);
    }
}
