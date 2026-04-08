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

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.proxydroid.utils.Utils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import androidx.appcompat.app.AppCompatActivity;

public class ProxyDroid extends AppCompatActivity implements AppSettingsStore.Listener {

	private static final String TAG = "ProxyDroid";
	private static final int MSG_UPDATE_FINISHED = 0;
	private static final int MSG_NO_ROOT = 1;
	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_UPDATE_FINISHED:
					Toast.makeText(ProxyDroid.this, getString(R.string.update_finished), Toast.LENGTH_LONG)
							.show();
					break;
				case MSG_NO_ROOT:
					showAToast(getString(R.string.require_root_alert));
					break;
			}
			super.handleMessage(msg);
		}
	};
	private ProgressDialog pd = null;
	private String profile;
	private ProxyDroidPreferenceFragment preferenceUi;

	private BroadcastReceiver ssidReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
				Log.w(TAG, "onReceived() called uncorrectly");
				return;
			}

			if (preferenceUi != null) {
				preferenceUi.loadNetworkList();
			}
		}
	};

	void bindPreferenceUi(ProxyDroidPreferenceFragment fragment) {
		preferenceUi = fragment;
	}

	/**
	 * Keeps {@link #profile} in sync when the profile list is refreshed from a {@link android.database.ContentObserver}.
	 */
	void setCachedActiveProfileId(String activeId) {
		profile = activeId;
	}

	private void showAbout() {

		WebView web = new WebView(this);
		web.loadUrl("file:///android_asset/pages/about.html");
		web.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
				return true;
			}
		});

		String versionName = "";
		try {
			versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (NameNotFoundException ex) {
			versionName = "";
		}

		new AlertDialog.Builder(this).setTitle(
						String.format(getString(R.string.about_title), versionName))
				.setCancelable(false)
				.setNegativeButton(getString(R.string.ok_iknow), (dialog, id) -> dialog.cancel())
				.setView(web)
				.create()
				.show();
	}

	private void copyAssets() {
		AssetManager assetManager = getAssets();
		String[] files = null;
		String abi = null;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
			abi = Build.SUPPORTED_ABIS[0];
		} else {
			abi = Build.CPU_ABI;
		}
		try {
			if (abi.matches("armeabi-v7a|arm64-v8a"))
				files = assetManager.list("armeabi-v7a");
			else
				files = assetManager.list("x86");
		} catch (IOException e) {
			Log.e(TAG, e.getMessage());
		}
		if (files != null) {
			for (String file : files) {
				InputStream in = null;
				OutputStream out = null;
				try {
					if (abi.matches("armeabi-v7a|arm64-v8a"))
						in = assetManager.open("armeabi-v7a/" + file);
					else
						in = assetManager.open("x86/" + file);
					out = new FileOutputStream(getFilesDir().getAbsolutePath() + "/" + file);
					copyFile(in, out);
					in.close();
					in = null;
					out.flush();
					out.close();
					out = null;
				} catch (Exception e) {
					Log.e(TAG, e.getMessage());
				}
			}
		}
	}

	private void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	private boolean isTextEmpty(String s, String msg) {
		if (s == null || s.length() <= 0) {
			showAToast(msg);
			return true;
		}
		return false;
	}

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_proxydroid);
		if (savedInstanceState == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.fragment_container, new ProxyDroidPreferenceFragment(), "prefs")
					.commitNow();
		}

		ProfileStore.ensureInitialized(this);
		profile = AppSettingsStore.getString(this, AppSettingsStore.KEY_ACTIVE_PROFILE, ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
		if (preferenceUi != null && preferenceUi.profileList != null) {
			preferenceUi.profileList.setDefaultValue(profile);
		}

		registerReceiver(ssidReceiver,
				new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));

		if (preferenceUi != null) {
			preferenceUi.loadProfileList();
			preferenceUi.loadNetworkList();
		}

		long initialRowId = ProfileStore.getActiveProfileId(this);
		if (initialRowId >= 0 && preferenceUi != null) {
			preferenceUi.refreshFromActiveProfile();
		}

		new Thread() {
			@Override
			public void run() {

				try {
					// Try not to block activity
					Thread.sleep(2000);
				} catch (InterruptedException ignore) {
					// Nothing
				}

				if (!Utils.isRoot()) {
					handler.sendEmptyMessage(MSG_NO_ROOT);
				}

				String versionName;
				try {
					versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
				} catch (NameNotFoundException e) {
					versionName = "NONE";
				}

				if (!AppSettingsStore.getBoolean(ProxyDroid.this, versionName, false)) {

					reset();

					AppSettingsStore.putBoolean(ProxyDroid.this, versionName, true, false);

					handler.sendEmptyMessage(MSG_UPDATE_FINISHED);
				}
			}
		}.start();
	}

	/**
	 * Called when the activity is closed.
	 */
	@Override
	public void onDestroy() {

		if (ssidReceiver != null) unregisterReceiver(ssidReceiver);

		super.onDestroy();
	}

	private boolean serviceStop() {

		if (!Utils.isWorking()) return false;

		try {
			return ProxyServiceHelper.stopProxyService(ProxyDroid.this);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Called when connect button is clicked.
	 */
	private boolean serviceStart() {

		if (Utils.isWorking()) return false;

		Profile p = ProfileStore.loadActiveProfile(this);
		if (p == null) {
			return false;
		}

		try {
			return ProxyServiceHelper.startProxyService(ProxyDroid.this, p);
		} catch (Exception ignore) {
			return false;
		}
	}

	private void onProfileChange() {
		String newIdStr = AppSettingsStore.getString(this, AppSettingsStore.KEY_ACTIVE_PROFILE, ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
		long newId;
		try {
			newId = Long.parseLong(newIdStr);
		} catch (NumberFormatException e) {
			newId = ProfileStore.getActiveProfileId(this);
		}
		if (newId < 0) {
			return;
		}
		if (preferenceUi != null) {
			preferenceUi.refreshFromActiveProfile();
		}
	}

	private void showAToast(String msg) {
		if (!isFinishing()) {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(msg)
					.setCancelable(false)
					.setNegativeButton(getString(R.string.ok_iknow), (dialog, id) -> dialog.cancel());
			AlertDialog alert = builder.create();
			alert.show();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		if (Utils.isWorking()) {
			AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_IS_RUNNING, true, false);
		} else {
			if (AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false)) {
				new Thread() {
					@Override
					public void run() {
						reset();
					}
				}.start();
			}
			AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false, false);
		}

		if (preferenceUi != null) {
			preferenceUi.refreshFromActiveProfile();
		}

		if (AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false)) {
			if (preferenceUi != null && preferenceUi.isRunningCheck != null) {
				preferenceUi.isRunningCheck.setChecked(true);
			}
			if (preferenceUi != null) {
				preferenceUi.disableAll();
			}
		} else {
			if (preferenceUi != null && preferenceUi.isRunningCheck != null) {
				preferenceUi.isRunningCheck.setChecked(false);
			}
		}

		profile = AppSettingsStore.getString(this, AppSettingsStore.KEY_ACTIVE_PROFILE, ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
		if (preferenceUi != null) {
			preferenceUi.syncProfileListFromStore();
		}

		if (preferenceUi != null) {
			preferenceUi.updateRingtonePreferenceSummary();
		}

		AppSettingsStore.registerListener(this);

		// Prefs can change while we are paused (e.g. root grant dialog). Listener was unregistered,
		// so we must sync UI here or "Connecting" ProgressDialog and switch state stay wrong.
		boolean connecting = AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_CONNECTING, false);
		if (preferenceUi != null && preferenceUi.isRunningCheck != null) {
			preferenceUi.isRunningCheck.setEnabled(!connecting);
		}
		if (!connecting && pd != null) {
			pd.dismiss();
			pd = null;
		}
	}

	@Override
	protected void onPause() {
		AppSettingsStore.unregisterListener(this);
		super.onPause();
	}

	@Override
	public void onAppSettingChanged(String key) {
		if (AppSettingsStore.KEY_ACTIVE_PROFILE.equals(key) && preferenceUi != null && preferenceUi.profileList != null) {
			String profileString = AppSettingsStore.getString(this, AppSettingsStore.KEY_ACTIVE_PROFILE, "");
			if (ProfileStore.NEW_PROFILE_SENTINEL.equals(profileString)) {
				Profile tmpl = new Profile();
				tmpl.init();
				tmpl.setName(getString(R.string.profile_default));
				Uri inserted = getContentResolver().insert(
						ProxyDroidCLI.CONTENT_URI,
						tmpl.toContentValues());
				if (inserted == null) {
					return;
				}
				long newId = ContentUris.parseId(inserted);
				tmpl = ProfileStore.loadProfileById(this, newId);
				if (tmpl != null) {
					tmpl.setName(getString(R.string.profile_base) + " " + newId);
					getContentResolver().update(
							ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, newId),
							tmpl.toContentValues(),
							null,
							null);
				}
				ProfileStore.activateProfile(this, newId);
				profile = String.valueOf(newId);
				preferenceUi.loadProfileList();
				preferenceUi.profileList.setValue(profile);
				preferenceUi.refreshFromActiveProfile();
				preferenceUi.profileList.setSummary(ProfileStore.getProfileDisplayName(this, profile));
			} else {
				profile = profileString;
				preferenceUi.profileList.setValue(profile);
				onProfileChange();
				preferenceUi.profileList.setSummary(ProfileStore.getProfileDisplayName(this, profileString));
			}
		}

		if (AppSettingsStore.KEY_IS_CONNECTING.equals(key) && preferenceUi != null && preferenceUi.isRunningCheck != null) {
			if (AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_CONNECTING, false)) {
				Log.d(TAG, "Connecting start");
				preferenceUi.isRunningCheck.setEnabled(false);
				pd = ProgressDialog.show(this, "", getString(R.string.connecting), true, true);
			} else {
				Log.d(TAG, "Connecting finish");
				if (pd != null) {
					pd.dismiss();
					pd = null;
				}
				preferenceUi.isRunningCheck.setEnabled(true);
			}
		}

		if (AppSettingsStore.KEY_IS_RUNNING.equals(key)) {
			if (AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false)) {
				if (preferenceUi != null) {
					preferenceUi.disableAll();
				}
				if (preferenceUi != null && preferenceUi.isRunningCheck != null) {
					preferenceUi.isRunningCheck.setChecked(true);
				}
				if (!Utils.isConnecting()) serviceStart();
			} else {
				if (!Utils.isConnecting()) serviceStop();
				if (preferenceUi != null && preferenceUi.isRunningCheck != null) {
					preferenceUi.isRunningCheck.setChecked(false);
				}
				if (preferenceUi != null) {
					preferenceUi.refreshFromActiveProfile();
				}
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		menu.add(Menu.NONE, Menu.FIRST + 1, 4, getString(R.string.recovery))
				.setIcon(android.R.drawable.ic_menu_close_clear_cancel)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(Menu.NONE, Menu.FIRST + 2, 2, getString(R.string.profile_del))
				.setIcon(android.R.drawable.ic_menu_delete)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		menu.add(Menu.NONE, Menu.FIRST + 3, 5, getString(R.string.about))
				.setIcon(android.R.drawable.ic_menu_info_details)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
		menu.add(Menu.NONE, Menu.FIRST + 4, 1, getString(R.string.change_name))
				.setIcon(android.R.drawable.ic_menu_edit)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case Menu.FIRST + 1:
				new Thread() {
					@Override
					public void run() {
						reset();
					}
				}.start();
				return true;
			case Menu.FIRST + 2:
				AlertDialog ad = new AlertDialog.Builder(this).setTitle(R.string.profile_del)
						.setMessage(R.string.profile_del_confirm)
						.setPositiveButton(R.string.alert_dialog_ok, (dialog, whichButton) -> {
							/* User clicked OK so do some stuff */
							delProfile(profile);
						})
						.setNegativeButton(R.string.alert_dialog_cancel, (dialog, whichButton) -> {
							/* User clicked Cancel so do some stuff */
							dialog.dismiss();
						})
						.create();

				ad.show();

				return true;
			case Menu.FIRST + 3:
				showAbout();
				return true;
			case Menu.FIRST + 4:
				rename();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void rename() {
		LayoutInflater factory = LayoutInflater.from(this);
		final View textEntryView = factory.inflate(R.layout.alert_dialog_text_entry, null);
		final EditText profileName = (EditText) textEntryView.findViewById(R.id.text_edit);
		profileName.setText(ProfileStore.getProfileDisplayName(this, profile));

		AlertDialog ad = new AlertDialog.Builder(this).setTitle(R.string.change_name)
				.setView(textEntryView)
				.setPositiveButton(R.string.alert_dialog_ok, (dialog, whichButton) -> {
					EditText profileName1 = (EditText) textEntryView.findViewById(R.id.text_edit);
					String name = profileName1.getText().toString();
					if (name == null) return;
					name = name.replace("|", "");
					if (name.length() <= 0) return;
					try {
						long id = Long.parseLong(profile);
						ContentValues cv = new ContentValues();
						cv.put(Profile.Columns.PROFILE_NAME, name);
						getContentResolver().update(
								ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, id),
								cv,
								null,
								null);
					} catch (NumberFormatException e) {
						return;
					}

					if (preferenceUi != null && preferenceUi.profileList != null) {
						preferenceUi.profileList.setSummary(ProfileStore.getProfileDisplayName(ProxyDroid.this, profile));
					}
					if (preferenceUi != null) {
						preferenceUi.loadProfileList();
					}
				})
				.setNegativeButton(R.string.alert_dialog_cancel, (dialog, whichButton) -> {
					/* User clicked cancel so do some stuff */
				})
				.create();
		ad.show();
	}

	private void delProfile(String profileId) {
		Cursor c = getContentResolver().query(
				ProxyDroidCLI.CONTENT_URI,
				new String[]{Profile.Columns._ID},
				null,
				null,
				null);
		int count = c != null ? c.getCount() : 0;
		if (c != null) {
			c.close();
		}
		Log.d(TAG, "Profile :" + profileId);
		if (count <= 1) {
			return;
		}
		if (ProfileStore.NEW_PROFILE_SENTINEL.equals(profileId)) {
			return;
		}
		try {
			long id = Long.parseLong(profileId);
			getContentResolver().delete(
					ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, id),
					null,
					null);
		} catch (NumberFormatException e) {
			return;
		}

		if (preferenceUi != null) {
			preferenceUi.loadProfileList();
		}
		profile = AppSettingsStore.getString(this, AppSettingsStore.KEY_ACTIVE_PROFILE, ProfileStore.DEFAULT_ACTIVE_PROFILE_ID_STRING);
		if (preferenceUi != null && preferenceUi.profileList != null) {
			preferenceUi.profileList.setValue(profile);
			preferenceUi.refreshFromActiveProfile();
			preferenceUi.profileList.setSummary(ProfileStore.getProfileDisplayName(this, profile));
		}
	}

	private void reset() {
		try {
			ProxyServiceHelper.stopProxyService(ProxyDroid.this);
		} catch (Exception e) {
			// Nothing
		}

		copyAssets();

		String filePath = getFilesDir().getAbsolutePath();

		Utils.runRootCommand(Utils.getIptables()
				+ " -t nat -F OUTPUT\n"
				+ getFilesDir().getAbsolutePath()
				+ "/proxy.sh stop\n"
				+ "kill -9 `cat " + filePath + "cntlm.pid`\n");

		Utils.runRootCommand(
				"chmod 700 " + filePath + "/redsocks\n"
						+ "chmod 700 " + filePath + "/proxy.sh\n"
						+ "chmod 700 " + filePath + "/gost.sh\n"
						+ "chmod 700 " + filePath + "/cntlm\n"
						+ "chmod 700 " + filePath + "/gost\n");
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
			try {
				finish();
			} catch (Exception ignore) {
				// Nothing
			}
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}
}
