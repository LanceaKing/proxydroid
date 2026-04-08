package org.proxydroid;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;

/**
 * Preference backing store: app-level keys in {@link AppSettingsStore}; profile columns via
 * {@link ProxyDroidCLI}.
 */
public class ProxyDroidPreferenceDataStore extends PreferenceDataStore {

	private final Context appContext;

	public ProxyDroidPreferenceDataStore(Context context) {
		this.appContext = context.getApplicationContext();
	}

	private void persistProfile(ContentValues cv) {
		if (cv.size() == 0) {
			return;
		}
		long id = ProfileStore.getActiveProfileId(appContext);
		if (id < 0) {
			return;
		}
		appContext.getContentResolver().update(
				ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, id),
				cv,
				null,
				null);
	}

	private Profile activeProfile() {
		Profile p = ProfileStore.loadActiveProfile(appContext);
		return p != null ? p : new Profile();
	}

	@Override
	public void putString(String key, @Nullable String value) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			if (value == null) {
				AppSettingsStore.remove(appContext, key);
			} else {
				AppSettingsStore.putString(appContext, key, value);
			}
			return;
		}

		ContentValues cv = new ContentValues();
		String v = value != null ? value : "";
		switch (key) {
			case Profile.Columns.PROFILE_NAME:
				cv.put(Profile.Columns.PROFILE_NAME, v);
				break;
			case Profile.Columns.HOST:
				cv.put(Profile.Columns.HOST, v);
				break;
			case Profile.Columns.PROXY_TYPE:
				cv.put(Profile.Columns.PROXY_TYPE, value != null ? value : "http");
				break;
			case Profile.Columns.USER:
				cv.put(Profile.Columns.USER, v);
				break;
			case Profile.Columns.PASSWORD:
				cv.put(Profile.Columns.PASSWORD, v);
				break;
			case Profile.Columns.DOMAIN:
				cv.put(Profile.Columns.DOMAIN, v);
				break;
			case Profile.Columns.CERTIFICATE:
				cv.put(Profile.Columns.CERTIFICATE, v);
				break;
			case Profile.Columns.SSID:
				cv.put(Profile.Columns.SSID, v);
				break;
			case Profile.Columns.EXCLUDED_SSID:
				cv.put(Profile.Columns.EXCLUDED_SSID, v);
				break;
			case Profile.Columns.BYPASS_ADDRS:
				cv.put(Profile.Columns.BYPASS_ADDRS, v);
				break;
			case Profile.Columns.PROXIED_APPS:
				cv.put(Profile.Columns.PROXIED_APPS, v);
				break;
			default:
				return;
		}
		persistProfile(cv);
	}

	@Override
	public String getString(String key, String defValue) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			return AppSettingsStore.getString(appContext, key, defValue);
		}

		Profile p = activeProfile();
		switch (key) {
			case Profile.Columns.PROFILE_NAME:
				return p.getName() != null ? p.getName() : defValue;
			case Profile.Columns.HOST:
				return p.getHost() != null ? p.getHost() : defValue;
			case Profile.Columns.PROXY_TYPE:
				return p.getProxyType() != null ? p.getProxyType() : defValue;
			case Profile.Columns.USER:
				return p.getUser() != null ? p.getUser() : defValue;
			case Profile.Columns.PASSWORD:
				return p.getPassword() != null ? p.getPassword() : defValue;
			case Profile.Columns.DOMAIN:
				return p.getDomain() != null ? p.getDomain() : defValue;
			case Profile.Columns.CERTIFICATE:
				return p.getCertificate() != null ? p.getCertificate() : defValue;
			case Profile.Columns.SSID:
				return p.getSsid() != null ? p.getSsid() : defValue;
			case Profile.Columns.EXCLUDED_SSID:
				return p.getExcludedSsid() != null ? p.getExcludedSsid() : defValue;
			case Profile.Columns.BYPASS_ADDRS:
				return p.getBypassAddrs() != null ? p.getBypassAddrs() : defValue;
			case Profile.Columns.PROXIED_APPS:
				return p.getProxiedApps() != null ? p.getProxiedApps() : defValue;
		}
		return defValue;
	}

	@Override
	public void putBoolean(String key, boolean value) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			AppSettingsStore.putBoolean(appContext, key, value);
			return;
		}

		ContentValues cv = new ContentValues();
		switch (key) {
			case Profile.Columns.IS_PAC:
				cv.put(Profile.Columns.IS_PAC, value);
				break;
			case Profile.Columns.IS_AUTH:
				cv.put(Profile.Columns.IS_AUTH, value);
				break;
			case Profile.Columns.IS_NTLM:
				cv.put(Profile.Columns.IS_NTLM, value);
				break;
			case Profile.Columns.IS_AUTO_CONNECT:
				cv.put(Profile.Columns.IS_AUTO_CONNECT, value);
				break;
			case Profile.Columns.IS_AUTO_SET_PROXY:
				cv.put(Profile.Columns.IS_AUTO_SET_PROXY, value);
				break;
			case Profile.Columns.IS_BYPASS_APPS:
				cv.put(Profile.Columns.IS_BYPASS_APPS, value);
				break;
			case Profile.Columns.IS_DNS_PROXY:
				cv.put(Profile.Columns.IS_DNS_PROXY, value);
				break;
			default:
				return;
		}
		persistProfile(cv);
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			return AppSettingsStore.getBoolean(appContext, key, defValue);
		}

		Profile p = activeProfile();
		switch (key) {
			case Profile.Columns.IS_PAC:
				return p.isPAC();
			case Profile.Columns.IS_AUTH:
				return p.isAuth();
			case Profile.Columns.IS_NTLM:
				return p.isNTLM();
			case Profile.Columns.IS_AUTO_CONNECT:
				return p.isAutoConnect();
			case Profile.Columns.IS_AUTO_SET_PROXY:
				return p.isAutoSetProxy();
			case Profile.Columns.IS_BYPASS_APPS:
				return p.isBypassApps();
			case Profile.Columns.IS_DNS_PROXY:
				return p.isDNSProxy();
		}
		return defValue;
	}

	@Override
	public void putInt(String key, int value) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			AppSettingsStore.putInt(appContext, key, value);
			return;
		}

		ContentValues cv = new ContentValues();
		if (Profile.Columns.PORT.equals(key)) {
			cv.put(Profile.Columns.PORT, value);
		}
		persistProfile(cv);
	}

	@Override
	public int getInt(String key, int defValue) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			return AppSettingsStore.getInt(appContext, key, defValue);
		}

		Profile p = activeProfile();
		if (Profile.Columns.PORT.equals(key)) {
			return p.getPort();
		}
		return defValue;
	}

	@Override
	public void putLong(String key, long value) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			AppSettingsStore.putLong(appContext, key, value);
		}
	}

	@Override
	public long getLong(String key, long defValue) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			return AppSettingsStore.getLong(appContext, key, defValue);
		}
		return defValue;
	}

	@Override
	public void putFloat(String key, float value) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			AppSettingsStore.putFloat(appContext, key, value);
		}
	}

	@Override
	public float getFloat(String key, float defValue) {
		if (AppSettingsStore.isAppSettingsKey(key)) {
			return AppSettingsStore.getFloat(appContext, key, defValue);
		}
		return defValue;
	}
}
