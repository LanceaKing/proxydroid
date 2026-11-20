package org.proxydroid;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public final class ProxyServiceHelper {

	private ProxyServiceHelper() {
	}

	public static Bundle buildStartExtras(Profile profile) {
		Bundle bundle = new Bundle();
		bundle.putString(Profile.Columns.HOST, profile.getHost());
		bundle.putString(Profile.Columns.USER, profile.getUser());
		bundle.putString(Profile.Columns.BYPASS_ADDRS, profile.getBypassAddrs());
		bundle.putString(Profile.Columns.PASSWORD, profile.getPassword());
		bundle.putString(Profile.Columns.DOMAIN, profile.getDomain());
		bundle.putString(Profile.Columns.CERTIFICATE, profile.getCertificate());
		bundle.putString(Profile.Columns.NTLM_AUTH_MODE, profile.getNtlmAuthMode());
		bundle.putBoolean(Profile.Columns.NTLM_USE_HASHES, profile.usesNtlmHashes());
		bundle.putString(Profile.Columns.NTLM_PASS_NTLMV2, profile.getNtlmPassNTLMv2());
		bundle.putString(Profile.Columns.NTLM_PASS_NT, profile.getNtlmPassNT());
		bundle.putString(Profile.Columns.NTLM_PASS_LM, profile.getNtlmPassLM());
		bundle.putString(Profile.Columns.PROXIED_APPS, profile.getProxiedApps());
		bundle.putString(Profile.Columns.PROXY_TYPE, profile.getProxyType());
		bundle.putBoolean(Profile.Columns.IS_AUTO_SET_PROXY, profile.isAutoSetProxy());
		bundle.putBoolean(Profile.Columns.IS_BYPASS_APPS, profile.isBypassApps());
		bundle.putBoolean(Profile.Columns.IS_AUTH, profile.isAuth());
		bundle.putBoolean(Profile.Columns.IS_NTLM, profile.isNTLM());
		bundle.putBoolean(Profile.Columns.IS_DNS_PROXY, profile.isDNSProxy());
		bundle.putBoolean(Profile.Columns.IS_PAC, profile.isPAC());
		bundle.putInt(Profile.Columns.PORT, profile.getPort());
		return bundle;
	}

	public static boolean startProxyService(Context context, Profile profile) {
		if (profile == null) return false;
		Bundle extras = buildStartExtras(profile);
		int error = startError(extras);
		if (error != 0) {
			Context appContext = context.getApplicationContext();
			new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext, error, Toast.LENGTH_LONG).show());
			return false;
		}
		Intent it = new Intent(context, ProxyDroidService.class);
		it.putExtras(extras);
		return (android.os.Build.VERSION.SDK_INT >= 26 ? context.startForegroundService(it) : context.startService(it)) != null;
	}

	static int startError(Bundle extras) {
		return NtlmSettings.startError(extras, extras.getBoolean(Profile.Columns.IS_PAC)
				? null : extras.getString(Profile.Columns.PROXY_TYPE, "http"));
	}

	public static boolean stopProxyService(Context context) {
		return context.stopService(new Intent(context, ProxyDroidService.class));
	}
}
