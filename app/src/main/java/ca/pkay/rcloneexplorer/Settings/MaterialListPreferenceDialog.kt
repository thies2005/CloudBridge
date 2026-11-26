package ca.pkay.rcloneexplorer.Settings

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shows list preferences using the app's Material dialog while retaining preference validation. */
fun PreferenceFragmentCompat.showMaterialListPreference(preference: Preference): Boolean {
    if (preference !is ListPreference) return false
    val entries = preference.entries ?: return false
    val values = preference.entryValues ?: return false
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(preference.dialogTitle ?: preference.title)
        .setSingleChoiceItems(entries, preference.findIndexOfValue(preference.value)) { dialog, which ->
            if (which in values.indices) {
                val value = values[which].toString()
                if (preference.callChangeListener(value)) preference.value = value
            }
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    return true
}
