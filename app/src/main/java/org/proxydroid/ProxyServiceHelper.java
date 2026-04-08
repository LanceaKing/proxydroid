package org.proxydroid;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

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
		Intent it = new Intent(context, ProxyDroidService.class);
		it.putExtras(buildStartExtras(profile));
		return context.startService(it) != null;
	}

	public static boolean stopProxyService(Context context) {
		return context.stopService(new Intent(context, ProxyDroidService.class));
	}
}
