/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package org.proxydroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.database.Cursor;
import android.text.TextUtils;

import com.ksmaze.android.preference.ListPreferenceMultiSelect;

import org.proxydroid.utils.Constraints;
import org.proxydroid.utils.Utils;

public class ConnectivityBroadcastReceiver extends BroadcastReceiver {

	private final Handler mHandler = new Handler();

	private static final String TAG = "ConnectivityBroadcastReceiver";

	@Override
	public void onReceive(final Context context, final Intent intent) {

		if (Utils.isConnecting()) return;

		if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) return;

		mHandler.post(() -> {

			// only switching profiles when needed
			ConnectivityManager manager = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo networkInfo = manager.getActiveNetworkInfo();

			if (networkInfo != null) {
				if (networkInfo.getState() == NetworkInfo.State.CONNECTING
						|| networkInfo.getState() == NetworkInfo.State.DISCONNECTING
						|| networkInfo.getState() == NetworkInfo.State.UNKNOWN)
					return;
			} else {
				if (!Utils.isWorking()) return;
			}

			ProfileStore.ensureInitialized(context);

			Cursor c = context.getContentResolver().query(
					ProxyDroidCLI.CONTENT_URI,
					Profile.Columns.ALL_COLUMNS,
					null,
					null,
					Profile.Columns._ID + " ASC");
			String curSSID = null;
			String lastSSID = AppSettingsStore.getString(context, AppSettingsStore.KEY_LAST_SSID, "-1");
			boolean autoConnect = false;
			Profile profile;

			if (c != null) {
				while (c.moveToNext()) {
					long pid = c.getLong(c.getColumnIndexOrThrow(Profile.Columns._ID));
					profile = Profile.fromCursor(c);
					curSSID = onlineSSID(context, profile.getSsid(), profile.getExcludedSsid());
					if (profile.isAutoConnect() && curSSID != null) {
						autoConnect = true;
						ProfileStore.activateProfile(context, pid);
						break;
					}
				}
				c.close();
			}

			if (networkInfo == null) {
				if (!lastSSID.equals(Constraints.ONLY_3G)
						&& !lastSSID.equals(Constraints.WIFI_AND_3G)
						&& !lastSSID.equals(Constraints.ONLY_WIFI)) {
					if (Utils.isWorking()) {
						ProxyServiceHelper.stopProxyService(context);
					}
				}
			} else {
				// no network available now
				if (networkInfo.getState() != NetworkInfo.State.CONNECTED)
					return;

				if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
					// if no last SSID, should give up here
					if (!lastSSID.equals("-1")) {
						// get WIFI info
						WifiManager wm = (WifiManager) context.getApplicationContext()
								.getSystemService(Context.WIFI_SERVICE);
						WifiInfo wInfo = wm.getConnectionInfo();
						if (wInfo != null) {
							// compare with the current SSID
							String current = wInfo.getSSID();
							if (current != null) current = current.replace("\"", "");
							if (current != null && !current.equals(lastSSID)) {
								// need to switch profile, so stop service first
								if (Utils.isWorking()) {
									ProxyServiceHelper.stopProxyService(context);
								}
							}
						}
					}
				} else {
					// still satisfy the last trigger
					if (!lastSSID.equals(Constraints.ONLY_3G)
							&& !lastSSID.equals(Constraints.WIFI_AND_3G)) {
						if (Utils.isWorking()) {
							ProxyServiceHelper.stopProxyService(context);
						}
					}
				}
			}

			if (autoConnect) {
				if (!Utils.isWorking()) {
					ProxyDroidReceiver pdr = new ProxyDroidReceiver();
					AppSettingsStore.putString(context, AppSettingsStore.KEY_LAST_SSID, curSSID);
					Utils.setConnecting(true);
					pdr.onReceive(context, intent);
				}
			}
		});
	}

	public String onlineSSID(Context context, String ssid, String excludedSsid) {
		String[] ssids = ListPreferenceMultiSelect.parseStoredValue(ssid);
		String[] excludedSsids = ListPreferenceMultiSelect.parseStoredValue(excludedSsid);
		if (ssids == null)
			return null;
		if (ssids.length < 1)
			return null;
		ConnectivityManager manager = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = manager.getActiveNetworkInfo();
		if (networkInfo == null)
			return null;
		if (networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
			for (String item : ssids) {
				if (Constraints.WIFI_AND_3G.equals(item))
					return item;
				if (Constraints.ONLY_3G.equals(item))
					return item;
			}
			return null;
		}
		WifiManager wm = (WifiManager) context.getApplicationContext()
				.getSystemService(Context.WIFI_SERVICE);
		WifiInfo wInfo = wm.getConnectionInfo();
		if (wInfo == null || wInfo.getSSID() == null)
			return null;
		String current = wInfo.getSSID();
		if (current == null || TextUtils.isEmpty(current))
			return null;
		current = current.replace("\"", "");

		if (excludedSsids != null) {
			for (String item : excludedSsids) {
				if (current.equals(item)) {
					return null; // Never connect proxy on excluded ssid
				}
			}
		}

		for (String item : ssids) {
			if (Constraints.WIFI_AND_3G.equals(item))
				return item;
			if (Constraints.ONLY_WIFI.equals(item))
				return item;
			if (current.equals(item))
				return item;
		}
		return null;
	}

}
