package fr.groggy.racecontrol.tv.ui.settings

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsViewModel
import fr.groggy.racecontrol.tv.core.update.AppUpdateCheckResult
import fr.groggy.racecontrol.tv.core.update.AppUpdateManager
import fr.groggy.racecontrol.tv.core.update.AppUpdateManifest
import fr.groggy.racecontrol.tv.core.update.InstallPermissionRequiredException
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class SettingsFragment: LeanbackSettingsFragmentCompat() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onDestroy() {
        viewModel.applySettings()

        super.onDestroy()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = childFragmentManager.fragmentFactory.instantiate(
            requireActivity().classLoader,
            pref.fragment
        ).also {
            it.arguments = pref.extras
        }
        startPreferenceFragment(fragment)

        return true
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val fragment = PreferenceFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key) }
        }
        startPreferenceFragment(fragment)

        return true
    }

    override fun onPreferenceStartInitialScreen() {
        startPreferenceFragment(PreferenceFragment())
    }

    // Block Leanback's built-in plain-text EditText dialog for keys we handle
    // ourselves via setOnPreferenceClickListener in PreferenceFragment.
    // DialogPreference.onClick() calls showDialog() BEFORE the click listener fires,
    // so without this the Leanback dialog opens alongside our AlertDialog.
    override fun onPreferenceDisplayDialog(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        if (pref.key == Settings.KEY_F1_USERNAME ||
            pref.key == Settings.KEY_F1_PASSWORD ||
            pref.key == Settings.KEY_CUSTOM_RADIO_URL) {
            return true // suppressed — our AlertDialog handles it
        }
        return super.onPreferenceDisplayDialog(caller, pref)
    }

    @Keep
    @AndroidEntryPoint
    class PreferenceFragment: LeanbackPreferenceFragmentCompat() {
        private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

        @Inject lateinit var appUpdateManager: AppUpdateManager

        private var currentAccountPreference: Preference? = null
        private var updatePreference: Preference? = null
        private var pendingUpdate: AppUpdateManifest? = null
        private var progressDialog: AlertDialog? = null

        private val installPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (appUpdateManager.canInstallPackages()) {
                pendingUpdate?.let { startDownloadAndInstall(it) }
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.update_install_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            currentAccountPreference = findPreference("current_account")
            updateCurrentAccountSummary()

            updatePreference = findPreference<Preference>("check_for_updates")?.also { preference ->
                preference.summary = getString(
                    R.string.settings_check_for_updates_summary,
                    appUpdateManager.installedVersionName
                )
                preference.setOnPreferenceClickListener {
                    checkForUpdates()
                    true
                }
            }

            findPreference<Preference>(Settings.KEY_F1_USERNAME)?.setOnPreferenceChangeListener { _, _ ->
                updateCurrentAccountSummary()
                true
            }

            findPreference<EditTextPreference>(Settings.KEY_F1_PASSWORD)?.let { pref ->
                // Always show dots in the preference row — never expose the stored value
                pref.summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { "●●●●●●●●" }
                pref.setOnPreferenceChangeListener { _, _ ->
                    updateCurrentAccountSummary()
                    true
                }
            }

            findPreference<Preference>("reset_settings")?.setOnPreferenceClickListener {
                viewModel.resetSettings()
                activity?.finish()
                true
            }

            findPreference<Preference>("donations")?.setOnPreferenceClickListener {
                DonationDialog.show(parentFragmentManager)
                true
            }

            findPreference<Preference>("logout")?.setOnPreferenceClickListener {
                viewModel.logout()
                CookieManager.getInstance().removeAllCookies {
                    startActivity(SignInActivity.intentClearTask(requireContext()))
                    activity?.finish()
                }

                true
            }

            configureTextPreference(
                key = Settings.KEY_F1_USERNAME,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
            configureTextPreference(
                key = Settings.KEY_F1_PASSWORD,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            configureTextPreference(
                key = Settings.KEY_CUSTOM_RADIO_URL,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )

            findPreference<ListPreference>("app_locale")?.setOnPreferenceChangeListener { _, newValue ->
                val tag = newValue as String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val locales = if (tag == "system") {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(tag)
                    }
                    requireContext().getSystemService(android.app.LocaleManager::class.java)
                        .applicationLocales = locales
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Language change takes effect after restarting the app",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
        }

        private fun configureTextPreference(key: String, inputType: Int) {
            findPreference<EditTextPreference>(key)?.setOnPreferenceClickListener { preference ->
                showTextPreferenceDialog(preference as EditTextPreference, inputType)
                true
            }
        }

        private fun showTextPreferenceDialog(preference: EditTextPreference, inputType: Int) {
            val isPassword = inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD ==
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            val editText = EditText(requireContext()).apply {
                this.inputType = inputType
                if (!isPassword) {
                    // Pre-fill non-password fields with the current value
                    setText(preference.text.orEmpty())
                    setSelection(text.length)
                }
                // Password fields are left empty — type a new one to change it
            }
            AlertDialog.Builder(requireContext())
                .setTitle(preference.title)
                .setView(editText)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newValue = editText.text.toString()
                    // For password fields, only update if the user typed something new
                    if (newValue.isNotEmpty() || !isPassword) {
                        preference.text = newValue
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create().apply {
                    setOnShowListener {
                        val imm = requireContext().getSystemService(InputMethodManager::class.java)
                        val hideKeyboard = {
                            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                        }
                        getButton(AlertDialog.BUTTON_POSITIVE)?.onFocusChangeListener =
                            View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) hideKeyboard() }
                        getButton(AlertDialog.BUTTON_NEGATIVE)?.onFocusChangeListener =
                            View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) hideKeyboard() }
                        editText.requestFocus()
                        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                        editText.post {
                            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    setOnDismissListener {
                        requireContext().getSystemService(InputMethodManager::class.java)
                            ?.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                }
                .show()
        }

        override fun onDestroyView() {
            progressDialog?.dismiss()
            progressDialog = null
            super.onDestroyView()
        }

        private fun checkForUpdates() {
            showProgress(getString(R.string.update_checking))
            lifecycleScope.launch {
                when (val result = appUpdateManager.checkForUpdate()) {
                    is AppUpdateCheckResult.UpToDate -> {
                        dismissProgress()
                        AlertDialog.Builder(requireContext())
                            .setMessage(
                                getString(
                                    R.string.update_up_to_date,
                                    appUpdateManager.installedVersionName
                                )
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is AppUpdateCheckResult.UpdateAvailable -> promptInstallUpdate(result.manifest)
                    is AppUpdateCheckResult.Error -> {
                        dismissProgress()
                        Toast.makeText(
                            requireContext(),
                            R.string.update_check_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        private fun promptInstallUpdate(manifest: AppUpdateManifest) {
            dismissProgress()
            pendingUpdate = manifest
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.update_available_title)
                .setMessage(getString(R.string.update_available_message, manifest.versionName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (appUpdateManager.canInstallPackages()) {
                        startDownloadAndInstall(manifest)
                    } else {
                        requestInstallPermission()
                    }
                }
                .show()
        }

        private fun requestInstallPermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                pendingUpdate?.let { startDownloadAndInstall(it) }
                return
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                    installPermissionLauncher.launch(intent)
                }
                .show()
        }

        private fun startDownloadAndInstall(manifest: AppUpdateManifest) {
            showProgress(getString(R.string.update_downloading))
            lifecycleScope.launch {
                try {
                    val apkFile = appUpdateManager.downloadUpdate(manifest)
                    dismissProgress()
                    appUpdateManager.installDownloadedApk(apkFile)
                } catch (_: InstallPermissionRequiredException) {
                    dismissProgress()
                    requestInstallPermission()
                } catch (_: Exception) {
                    dismissProgress()
                    Toast.makeText(
                        requireContext(),
                        R.string.update_download_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        private fun showProgress(message: String) {
            progressDialog?.dismiss()
            progressDialog = AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }

        private fun dismissProgress() {
            progressDialog?.dismiss()
            progressDialog = null
        }

        private fun updateCurrentAccountSummary() {
            val preference = currentAccountPreference ?: return
            lifecycleScope.launch {
                val accountInfo = viewModel.getCurrentAccountInfo()
                preference.summary = when {
                    accountInfo.isLoggedIn && !accountInfo.email.isNullOrBlank() -> accountInfo.email
                    !accountInfo.email.isNullOrBlank() ->
                        getString(R.string.settings_current_account_saved_summary, accountInfo.email)
                    else -> getString(R.string.settings_current_account_none_summary)
                }
            }
        }
    }
}