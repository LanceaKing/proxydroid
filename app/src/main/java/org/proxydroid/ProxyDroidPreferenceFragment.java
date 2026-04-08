package org.proxydroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;

import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.ksmaze.android.preference.ListPreferenceMultiSelect;

import org.proxydroid.utils.Constraints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Hosts the main settings hierarchy for {@link ProxyDroid}.
 */
public class ProxyDroidPreferenceFragment extends PreferenceFragmentCompat {

	private static final int REQUEST_NOTIFICATION_RINGTONE = 100;

	CheckBoxPreference isAutoConnectCheck;
	CheckBoxPreference isAutoSetProxyCheck;
	CheckBoxPreference isAuthCheck;
	CheckBoxPreference isNTLMCheck;
	CheckBoxPreference isPACCheck;
	ListPreference profileList;
	EditTextPreference hostText;
	EditTextPreference portText;
	EditTextPreference userText;
	EditTextPreference passwordText;
	EditTextPreference domainText;
	EditTextPreference certificateText;
	ListPreferenceMultiSelect ssidList;
	ListPreferenceMultiSelect excludedSsidList;
	ListPreference proxyTypeList;
	SwitchPreferenceCompat isRunningCheck;
	CheckBoxPreference isBypassAppsCheck;
	Preference proxiedAppsPref;
	Preference bypassAddrs;
	Preference ringtonePref;

	private final ContentObserver profileContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
		@Override
		public void onChange(boolean selfChange) {
			Activity activity = getActivity();
			if (activity == null || activity.isFinishing()) {
				return;
			}
			if (profileList == null) {
				return;
			}
			loadProfileList();
			String active = AppSettingsStore.getString(requireContext(), AppSettingsStore.KEY_ACTIVE_PROFILE,
					ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
			if (activity instanceof ProxyDroid) {
				((ProxyDroid) activity).setCachedActiveProfileId(active);
			}
			profileList.setValue(active);
			profileList.setSummary(ProfileStore.getProfileDisplayName(requireContext(), active));
			refreshFromActiveProfile();
		}
	};

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		Context appContext = requireContext().getApplicationContext();
		getPreferenceManager().setPreferenceDataStore(new ProxyDroidPreferenceDataStore(appContext));
		setPreferencesFromResource(R.xml.proxydroid_preference, rootKey);
		wirePreferences();
		if (requireActivity() instanceof ProxyDroid) {
			((ProxyDroid) requireActivity()).bindPreferenceUi(this);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		requireContext().getContentResolver().registerContentObserver(
				ProxyDroidCLI.CONTENT_URI,
				true,
				profileContentObserver);
	}

	@Override
	public void onStop() {
		requireContext().getContentResolver().unregisterContentObserver(profileContentObserver);
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		if (getActivity() instanceof ProxyDroid) {
			((ProxyDroid) getActivity()).bindPreferenceUi(null);
		}
		super.onDestroyView();
	}

	private void wirePreferences() {
		hostText = findPreference(Profile.Columns.HOST);
		portText = findPreference(Profile.Columns.PORT);
		userText = findPreference(Profile.Columns.USER);
		passwordText = findPreference(Profile.Columns.PASSWORD);
		domainText = findPreference(Profile.Columns.DOMAIN);
		certificateText = findPreference(Profile.Columns.CERTIFICATE);
		bypassAddrs = findPreference(Profile.Columns.BYPASS_ADDRS);
		ssidList = findPreference(Profile.Columns.SSID);
		excludedSsidList = findPreference(Profile.Columns.EXCLUDED_SSID);
		proxyTypeList = findPreference(Profile.Columns.PROXY_TYPE);
		proxiedAppsPref = findPreference(Profile.Columns.PROXIED_APPS);
		profileList = findPreference(AppSettingsStore.KEY_ACTIVE_PROFILE);
		isRunningCheck = findPreference(AppSettingsStore.KEY_IS_RUNNING);
		isAutoSetProxyCheck = findPreference(Profile.Columns.IS_AUTO_SET_PROXY);
		isAuthCheck = findPreference(Profile.Columns.IS_AUTH);
		isNTLMCheck = findPreference(Profile.Columns.IS_NTLM);
		isPACCheck = findPreference(Profile.Columns.IS_PAC);
		isAutoConnectCheck = findPreference(Profile.Columns.IS_AUTO_CONNECT);
		isBypassAppsCheck = findPreference(Profile.Columns.IS_BYPASS_APPS);
		if (passwordText != null) {
			passwordText.setOnBindEditTextListener(editText -> editText.setInputType(
					InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
		}
		if (bypassAddrs != null) {
			bypassAddrs.setOnPreferenceClickListener(pref -> {
				startActivity(new Intent(requireContext(), BypassListActivity.class));
				return true;
			});
		}
		if (proxiedAppsPref != null) {
			proxiedAppsPref.setOnPreferenceClickListener(pref -> {
				startActivity(new Intent(requireContext(), AppManager.class));
				return true;
			});
		}
		ringtonePref = findPreference(AppSettingsStore.KEY_RINGTONE);
		if (ringtonePref != null) {
			ringtonePref.setOnPreferenceClickListener(pref -> {
				openNotificationRingtonePicker();
				return true;
			});
			updateRingtonePreferenceSummary();
		}
	}

	void openNotificationRingtonePicker() {
		Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
		intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
		String cur = AppSettingsStore.getString(ctx, AppSettingsStore.KEY_RINGTONE, null);
		if (cur != null && !cur.isEmpty()) {
			try {
				intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
			} catch (Exception ignored) {
			}
		}
		startActivityForResult(intent, REQUEST_NOTIFICATION_RINGTONE);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_NOTIFICATION_RINGTONE) {
			return;
		}
		Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		if (resultCode != Activity.RESULT_OK || data == null) {
			return;
		}
		Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
		if (uri != null) {
			AppSettingsStore.putString(ctx, AppSettingsStore.KEY_RINGTONE, uri.toString());
		} else {
			AppSettingsStore.remove(ctx, AppSettingsStore.KEY_RINGTONE);
		}
		updateRingtonePreferenceSummary();
	}

	void updateRingtonePreferenceSummary() {
		Context ctx = getContext();
		if (ctx == null || ringtonePref == null) {
			return;
		}
		String s = AppSettingsStore.getString(ctx, AppSettingsStore.KEY_RINGTONE, null);
		if (s == null || s.isEmpty()) {
			ringtonePref.setSummary(ctx.getString(R.string.notif_ringtone_summary));
			return;
		}
		try {
			Uri uri = Uri.parse(s);
			Ringtone rt = RingtoneManager.getRingtone(ctx, uri);
			CharSequence title = rt != null ? rt.getTitle(ctx) : null;
			if (title != null && title.length() > 0) {
				ringtonePref.setSummary(title);
			} else {
				ringtonePref.setSummary(ctx.getString(R.string.notif_ringtone_summary));
			}
		} catch (Exception e) {
			ringtonePref.setSummary(ctx.getString(R.string.notif_ringtone_summary));
		}
	}

	/**
	 * Reloads active profile from storage into preference widgets and summaries.
	 */
	void refreshFromActiveProfile() {
		if (hostText == null) {
			return;
		}
		Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		long rowId = ProfileStore.getActiveProfileId(ctx);
		Profile loaded = rowId >= 0 ? ProfileStore.loadProfileById(ctx, rowId) : null;
		Profile model;
		if (loaded == null) {
			model = new Profile();
			if (rowId >= 0) {
				model.init();
				model.setName(ctx.getString(R.string.profile_base) + " " + rowId);
			}
		} else {
			model = loaded;
		}
		if (model.isAutoConnect()) {
			loadNetworkList();
		}
		applyProfileToWidgets(model);
		if (!AppSettingsStore.getBoolean(ctx, AppSettingsStore.KEY_IS_RUNNING, false)) {
			enableAll();
		}
		applySummaries(model);
	}

	/**
	 * Pushes {@link Profile} into preference widgets (DataStore-backed values are not always rebound
	 * when switching rows).
	 */
	private void applyProfileToWidgets(Profile p) {
		if (p == null || hostText == null) {
			return;
		}
		hostText.setText(p.getHost() != null ? p.getHost() : "");
		userText.setText(p.getUser() != null ? p.getUser() : "");
		passwordText.setText(p.getPassword() != null ? p.getPassword() : "");
		domainText.setText(p.getDomain() != null ? p.getDomain() : "");
		certificateText.setText(p.getCertificate() != null ? p.getCertificate() : "");
		proxyTypeList.setValue(p.getProxyType() != null ? p.getProxyType() : "http");
		ssidList.setValue(p.getSsid() != null ? p.getSsid() : "");
		excludedSsidList.setValue(p.getExcludedSsid() != null ? p.getExcludedSsid() : "");
		isPACCheck.setChecked(p.isPAC());
		isAuthCheck.setChecked(p.isAuth());
		isNTLMCheck.setChecked(p.isNTLM());
		isAutoConnectCheck.setChecked(p.isAutoConnect());
		isAutoSetProxyCheck.setChecked(p.isAutoSetProxy());
		isBypassAppsCheck.setChecked(p.isBypassApps());
		portText.setText(String.valueOf(p.getPort()));
	}

	private void applySummaries(Profile p) {
		if (hostText == null) {
			return;
		}
		Context ctx = requireContext();
		if (p == null) {
			p = new Profile();
		}
		String ssid = p.getSsid() != null ? p.getSsid() : "";
		if (ssid.isEmpty()) {
			ssidList.setSummary(ctx.getString(R.string.ssid_summary));
		} else {
			ssidList.setSummary(ssid);
		}
		String excluded = p.getExcludedSsid() != null ? p.getExcludedSsid() : "";
		if (excluded.isEmpty()) {
			excludedSsidList.setSummary(ctx.getString(R.string.excluded_ssid_summary));
		} else {
			excludedSsidList.setSummary(excluded);
		}
		String user = p.getUser() != null ? p.getUser() : "";
		if (user.isEmpty()) {
			userText.setSummary(ctx.getString(R.string.user_summary));
		} else {
			userText.setSummary(user);
		}
		String cert = p.getCertificate() != null ? p.getCertificate() : "";
		if (cert.isEmpty()) {
			certificateText.setSummary(ctx.getString(R.string.certificate_summary));
		} else {
			certificateText.setSummary(cert);
		}
		String bypass = p.getBypassAddrs() != null ? p.getBypassAddrs() : "";
		if (bypass.isEmpty()) {
			bypassAddrs.setSummary(ctx.getString(R.string.set_bypass_summary));
		} else {
			bypassAddrs.setSummary(bypass.replace("|", ", "));
		}
		String portStr = String.valueOf(p.getPort());
		if ("-1".equals(portStr) || portStr.isEmpty()) {
			portText.setSummary(ctx.getString(R.string.port_summary));
		} else {
			portText.setSummary(portStr);
		}
		String host = p.getHost() != null ? p.getHost() : "";
		if (host.isEmpty()) {
			hostText.setSummary(ctx.getString(p.isPAC() ? R.string.host_pac_summary : R.string.host_summary));
		} else {
			hostText.setSummary(host);
		}
		String pw = p.getPassword() != null ? p.getPassword() : "";
		if (pw.isEmpty()) {
			passwordText.setSummary(ctx.getString(R.string.password_summary));
		} else {
			passwordText.setSummary("*********");
		}
		String proxyType = p.getProxyType() != null ? p.getProxyType() : "";
		if (proxyType.isEmpty()) {
			proxyTypeList.setSummary(ctx.getString(R.string.proxy_type_summary));
		} else {
			proxyTypeList.setSummary(proxyType.toUpperCase(Locale.US));
		}
		String domain = p.getDomain() != null ? p.getDomain() : "";
		if (domain.isEmpty()) {
			domainText.setSummary(ctx.getString(R.string.domain_summary));
		} else {
			domainText.setSummary(domain);
		}
	}

	void loadProfileList() {
		if (profileList == null) {
			return;
		}
		Context ctx = requireContext();
		ArrayList<CharSequence> entries = new ArrayList<>();
		ArrayList<String> values = new ArrayList<>();
		try (Cursor c = ctx.getContentResolver().query(
				ProxyDroidCLI.CONTENT_URI,
				new String[]{Profile.Columns._ID, Profile.Columns.PROFILE_NAME},
				null,
				null,
				Profile.Columns._ID + " ASC")) {
			if (c != null) {
				while (c.moveToNext()) {
					entries.add(c.getString(1));
					values.add(String.valueOf(c.getLong(0)));
				}
			}
		}
		entries.add(ctx.getString(R.string.profile_new));
		values.add(ProfileStore.NEW_PROFILE_SENTINEL);
		profileList.setEntries(entries.toArray(new CharSequence[0]));
		profileList.setEntryValues(values.toArray(new String[0]));
	}

	@SuppressLint("MissingPermission")
	void loadNetworkList() {
		if (ssidList == null || excludedSsidList == null) {
			return;
		}
		Context ctx = requireContext();
		WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		List<WifiConfiguration> wcs = wm.getConfiguredNetworks();
		String[] ssidEntries;
		String[] pureSsid;
		int n = 3;
		int wifiIndex = n;

		if (wcs == null) {
			ssidEntries = new String[n];

			ssidEntries[0] = Constraints.WIFI_AND_3G;
			ssidEntries[1] = Constraints.ONLY_WIFI;
			ssidEntries[2] = Constraints.ONLY_3G;
		} else {
			ssidEntries = new String[wcs.size() + n];

			ssidEntries[0] = Constraints.WIFI_AND_3G;
			ssidEntries[1] = Constraints.ONLY_WIFI;
			ssidEntries[2] = Constraints.ONLY_3G;

			for (WifiConfiguration wc : wcs) {
				if (wc != null && wc.SSID != null) {
					ssidEntries[n++] = wc.SSID.replace("\"", "");
				} else {
					ssidEntries[n++] = "unknown";
				}
			}
		}
		ssidList.setEntries(ssidEntries);
		ssidList.setEntryValues(ssidEntries);

		pureSsid = Arrays.copyOfRange(ssidEntries, wifiIndex, ssidEntries.length);
		excludedSsidList.setEntries(pureSsid);
		excludedSsidList.setEntryValues(pureSsid);
	}

	void syncProfileListFromStore() {
		if (profileList == null) {
			return;
		}
		Context ctx = requireContext();
		String active = AppSettingsStore.getString(ctx, AppSettingsStore.KEY_ACTIVE_PROFILE,
				ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
		profileList.setValue(active);
		profileList.setSummary(ProfileStore.getProfileDisplayName(ctx, active));
	}

	void enableAll() {
		if (hostText == null) {
			return;
		}
		hostText.setEnabled(true);

		if (isPACCheck.isChecked()) {
			portText.setEnabled(false);
			proxyTypeList.setEnabled(false);
			hostText.setTitle(R.string.host_pac);
		} else {
			portText.setEnabled(true);
			proxyTypeList.setEnabled(true);
			hostText.setTitle(R.string.host);
		}

		bypassAddrs.setEnabled(true);

		if (isAuthCheck.isChecked()) {
			userText.setEnabled(true);
			passwordText.setEnabled(true);
			isNTLMCheck.setEnabled(true);
			if (isNTLMCheck.isChecked()) {
				domainText.setEnabled(true);
			} else {
				domainText.setEnabled(false);
			}
		} else {
			userText.setEnabled(false);
			passwordText.setEnabled(false);
			isNTLMCheck.setEnabled(false);
			domainText.setEnabled(false);
		}
		if ("https".equals(proxyTypeList.getValue())) {
			certificateText.setEnabled(true);
		} else {
			certificateText.setEnabled(false);
		}
		if (!isAutoSetProxyCheck.isChecked()) {
			proxiedAppsPref.setEnabled(true);
			isBypassAppsCheck.setEnabled(true);
		} else {
			proxiedAppsPref.setEnabled(false);
			isBypassAppsCheck.setEnabled(false);
		}
		if (isAutoConnectCheck.isChecked()) {
			ssidList.setEnabled(true);
			excludedSsidList.setEnabled(true);
		} else {
			ssidList.setEnabled(false);
			excludedSsidList.setEnabled(false);
		}

		profileList.setEnabled(true);
		isAutoSetProxyCheck.setEnabled(true);
		isAuthCheck.setEnabled(true);
		isAutoConnectCheck.setEnabled(true);
		isPACCheck.setEnabled(true);
	}

	void disableAll() {
		if (hostText == null) {
			return;
		}
		hostText.setEnabled(false);
		portText.setEnabled(false);
		userText.setEnabled(false);
		passwordText.setEnabled(false);
		domainText.setEnabled(false);
		certificateText.setEnabled(false);
		ssidList.setEnabled(false);
		excludedSsidList.setEnabled(false);
		proxyTypeList.setEnabled(false);
		proxiedAppsPref.setEnabled(false);
		profileList.setEnabled(false);
		bypassAddrs.setEnabled(false);

		isAuthCheck.setEnabled(false);
		isNTLMCheck.setEnabled(false);
		isAutoSetProxyCheck.setEnabled(false);
		isAutoConnectCheck.setEnabled(false);
		isPACCheck.setEnabled(false);
		isBypassAppsCheck.setEnabled(false);
	}
}
