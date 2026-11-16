package ca.pkay.rcloneexplorer.Settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Activities.SettingsActivity

class ThemingPreferencesFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (!showMaterialListPreference(preference)) super.onDisplayPreferenceDialog(preference)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_theming_preferences, rootKey)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        requireActivity().title = getString(R.string.look_and_feel)

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if(key == activity?.getString(R.string.pref_key_theme)) {
            (requireActivity() as SettingsActivity).onThemeChanged()
        }
    }

}