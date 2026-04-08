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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.btr.proxy.selector.pac.PacProxySelector;
import com.btr.proxy.selector.pac.PacScriptSource;
import com.btr.proxy.selector.pac.Proxy;
import com.btr.proxy.selector.pac.UrlPacScriptSource;

import org.proxydroid.utils.Utils;

import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

public class ProxyDroidService extends Service {

	/**
	 * Channel id for foreground notification; sound/vibration are applied on the channel (Android 8+).
	 */
	private static final String NOTIFICATION_CHANNEL_ID = "org.proxydroid.fgs";

	private NotificationManager notificationManager;
	private PendingIntent pendIntent;

	private String mNotificationChannelFingerprint;
	private String mLastNotifyTitle;
	private String mLastNotifyInfo;

	private final AppSettingsStore.Listener mAppSettingsListener = key -> {
		if (AppSettingsStore.KEY_RINGTONE.equals(key) || AppSettingsStore.KEY_IS_VIBRATE.equals(key)) {
			mNotificationChannelFingerprint = null;
			syncNotificationChannel();
			if (mLastNotifyTitle != null && mLastNotifyInfo != null) {
				notifyAlert(mLastNotifyTitle, mLastNotifyInfo);
			}
		}
	};

	private static final int MSG_CONNECT_START = 0;
	private static final int MSG_CONNECT_FINISH = 1;
	private static final int MSG_CONNECT_SUCCESS = 2;
	private static final int MSG_CONNECT_FAIL = 3;
	private static final int MSG_CONNECT_PAC_ERROR = 4;
	private static final int MSG_CONNECT_RESOLVE_ERROR = 5;

	final static String CMD_IPTABLES_RETURN = "iptables -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN\n";

	final static String CMD_IPTABLES_REDIRECT_ADD_HTTP = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8124\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j REDIRECT --to 8124\n";

	final static String CMD_IPTABLES_DNAT_ADD_HTTP = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8124\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j DNAT --to-destination 127.0.0.1:8124\n";

	final static String CMD_IPTABLES_REDIRECT_ADD_HTTP_TUNNEL = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j REDIRECT --to 8123\n";

	final static String CMD_IPTABLES_DNAT_ADD_HTTP_TUNNEL = "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j DNAT --to-destination 127.0.0.1:8123\n";

	final static String CMD_IPTABLES_REDIRECT_ADD_SOCKS = "iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to 8123\n";

	final static String CMD_IPTABLES_DNAT_ADD_SOCKS = "iptables -t nat -A OUTPUT -p tcp -j DNAT --to-destination 127.0.0.1:8123\n";

	private static final String TAG = "ProxyDroidService";

	private String host;
	private String hostName;
	private int port;
	private String bypassAddrs = "";
	private String user;
	private String password;
	private String domain;
	private String proxyType = "http";
	private String auth = "false";
	private boolean isAuth = false;
	private boolean isNTLM = false;
	private boolean isPAC = false;

	public String basePath = "/data/data/org.proxydroid/";

	private boolean hasRedirectSupport = true;
	private boolean isAutoSetProxy = false;
	private boolean isBypassApps = false;

	private ProxiedApp[] apps;

	/*
	 * This is a hack see
	 * http://www.mail-archive.com/android-developers@googlegroups
	 * .com/msg18298.html we are not really able to decide if the service was
	 * started. So we remember a week reference to it. We set it if we are
	 * running and clear it if we are stopped. If anything goes wrong, the
	 * reference will hopefully vanish
	 */
	private static WeakReference<ProxyDroidService> sRunningInstance = null;

	public static boolean isServiceStarted() {
		ProxyDroidService ref = sRunningInstance != null ? sRunningInstance.get() : null;
		if (ref == null) {
			sRunningInstance = null;
			return false;
		}
		return true;
	}

	private void markServiceStarted() {
		sRunningInstance = new WeakReference<ProxyDroidService>(this);
	}

	private void markServiceStopped() {
		sRunningInstance = null;
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	private void enableProxy() {

		String proxyHost = host;
		int proxyPort = port;

		try {
			if ("https".equals(proxyType)) {
				String src = "-L=http://127.0.0.1:8126";
				String auth = "";
				if (!user.isEmpty() && !password.isEmpty()) {
					auth = user + ":" + password + "@";
				}
				String dst = "-F=https://" + auth + hostName + ":" + port + "?ip=" + host;

				// Start gost here
				Utils.runRootCommand(basePath + "gost.sh " + basePath + " " + src + " " + dst);

				// Reset host / port
				proxyHost = "127.0.0.1";
				proxyPort = 8126;
				proxyType = "http";
			}

			if (proxyType.equals("http") && isAuth && isNTLM) {
				Utils.runRootCommand(basePath + "proxy.sh " + basePath + " start http 127.0.0.1 8025 false\n"
						+ basePath + "cntlm -P " + basePath + "cntlm.pid -l 8025 -u " + user
						+ (!domain.equals("") ? "@" + domain : "@local") + " -p " + password + " "
						+ proxyHost + ":" + proxyPort + "\n");
			} else {
				final String u = Utils.preserve(user);
				final String p = Utils.preserve(password);

				Utils.runRootCommand(basePath + "proxy.sh " + basePath + " start" + " " + proxyType + " " + proxyHost
						+ " " + proxyPort + " " + auth + " \"" + u + "\" \"" + p + "\"");
			}

			StringBuilder cmd = new StringBuilder();

			cmd.append(CMD_IPTABLES_RETURN.replace("0.0.0.0", host));

			if (bypassAddrs != null && !bypassAddrs.equals("")) {
				String[] addrs = Profile.decodeAddrs(bypassAddrs);
				for (String addr : addrs)
					cmd.append(CMD_IPTABLES_RETURN.replace("0.0.0.0", addr));
			}

			String redirectCmd = CMD_IPTABLES_REDIRECT_ADD_HTTP;
			String dnatCmd = CMD_IPTABLES_DNAT_ADD_HTTP;

			if (proxyType.equals("socks4") || proxyType.equals("socks5")) {
				redirectCmd = CMD_IPTABLES_REDIRECT_ADD_SOCKS;
				dnatCmd = CMD_IPTABLES_DNAT_ADD_SOCKS;
			} else if (proxyType.equals("http-tunnel")) {
				redirectCmd = CMD_IPTABLES_REDIRECT_ADD_HTTP_TUNNEL;
				dnatCmd = CMD_IPTABLES_DNAT_ADD_HTTP_TUNNEL;
			}

			if (isBypassApps) {
				// for host specified apps
				if (apps == null || apps.length <= 0)
					apps = AppManager.getProxiedApps(this, false);

				for (ProxiedApp app : apps) {
					if (app != null && app.isProxied()) {
						cmd.append(CMD_IPTABLES_RETURN.replace("-d 0.0.0.0", "").replace("-t nat",
								"-t nat -m owner --uid-owner " + app.getUid()));
					}
				}

			}

			if (isAutoSetProxy || isBypassApps) {
				cmd.append(hasRedirectSupport ? redirectCmd : dnatCmd);
			} else {
				// for host specified apps
				if (apps == null || apps.length <= 0)
					apps = AppManager.getProxiedApps(this, true);

				for (ProxiedApp app : apps) {
					if (app != null && app.isProxied()) {
						cmd.append((hasRedirectSupport ? redirectCmd : dnatCmd).replace("-t nat",
								"-t nat -m owner --uid-owner " + app.getUid()));
					}
				}
			}

			String rules = cmd.toString();

			rules = rules.replace("iptables", Utils.getIptables());

			Utils.runRootCommand(rules);

		} catch (Exception e) {
			Log.e(TAG, "Error setting up port forward during connect", e);
		}

	}

	/**
	 * Called when the activity is first created.
	 */
	public boolean handleCommand() {

		String filePath = getFilesDir().getAbsolutePath();

		Utils.runRootCommand(
				"chmod 700 " + filePath + "/redsocks\n"
						+ "chmod 700 " + filePath + "/proxy.sh\n"
						+ "chmod 700 " + filePath + "/gost.sh\n"
						+ "chmod 700 " + filePath + "/cntlm\n"
						+ "chmod 700 " + filePath + "/gost\n");

		enableProxy();

		return true;
	}

	/**
	 * Pre-Oreo only: from API 26, sound/vibration are controlled by {@link NotificationChannel}, not the builder.
	 */
	private void initSoundVibrateLights(NotificationCompat.Builder builder) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			return;
		}
		final String ringtone = AppSettingsStore.getString(this, AppSettingsStore.KEY_RINGTONE, null);
		AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
		if (audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
			builder.setSound(null);
		} else if (ringtone != null && !ringtone.isEmpty()) {
			builder.setSound(Uri.parse(ringtone));
		}

		if (AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_VIBRATE, false)) {
			builder.setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000});
		}
	}

	/**
	 * Applies ringtone and vibrate prefs to a {@link NotificationChannel}. On Android 8+, channel settings
	 * replace {@link NotificationCompat.Builder#setSound} / {@link NotificationCompat.Builder#setVibrate}.
	 * The channel is recreated when prefs change (channels are otherwise immutable).
	 */
	private void syncNotificationChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		String ringtone = AppSettingsStore.getString(this, AppSettingsStore.KEY_RINGTONE, null);
		boolean vibrate = AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_IS_VIBRATE, false);
		String fp = (ringtone != null ? ringtone : "") + "\u0000" + vibrate;
		if (fp.equals(mNotificationChannelFingerprint)
				&& notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
			return;
		}
		mNotificationChannelFingerprint = fp;

		notificationManager.deleteNotificationChannel("Service");
		notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID);

		CharSequence name = "ProxyDroid Service";
		String description = "ProxyDroid Background Service";
		int importance = NotificationManager.IMPORTANCE_DEFAULT;
		NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
		channel.setDescription(description);

		AudioAttributes audioAttrs = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();

		AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) {
			channel.setSound(null, null);
		} else if (ringtone != null && !ringtone.isEmpty()) {
			channel.setSound(Uri.parse(ringtone), audioAttrs);
		} else {
			channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttrs);
		}

		if (vibrate) {
			channel.enableVibration(true);
			channel.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
		} else {
			channel.enableVibration(false);
			channel.setVibrationPattern(null);
		}

		notificationManager.createNotificationChannel(channel);
	}

	private void notifyAlert(String title, String info) {
		mLastNotifyTitle = title;
		mLastNotifyInfo = info;
		syncNotificationChannel();

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);

		initSoundVibrateLights(builder);

		builder.setAutoCancel(false);
		builder.setTicker(title);
		builder.setContentTitle(getString(R.string.app_name) + " | "
				+ getProfileName());
		builder.setContentText(info);
		builder.setSmallIcon(R.drawable.ic_stat_proxydroid);
		builder.setContentIntent(pendIntent);
		builder.setPriority(NotificationCompat.PRIORITY_LOW);
		builder.setOngoing(true);

		startForeground(1, builder.build());
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		basePath = getFilesDir().getAbsolutePath() + "/";

		ProfileStore.ensureInitialized(this);
		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		syncNotificationChannel();
		AppSettingsStore.registerListener(mAppSettingsListener);

		Intent intent = new Intent(this, ProxyDroid.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
	}

	/**
	 * Called when the activity is closed.
	 */
	@Override
	public void onDestroy() {

		AppSettingsStore.unregisterListener(mAppSettingsListener);
		mLastNotifyTitle = null;
		mLastNotifyInfo = null;

		Utils.setConnecting(true);

		notificationManager.cancelAll();
		stopForeground(true);

		// Make sure the connection is closed, important here
		onDisconnect();

		updateWidgetToggle(false);

		AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false);

		try {
			notificationManager.cancel(0);
		} catch (Exception ignore) {
			// Nothing
		}

		markServiceStopped();

		Utils.setConnecting(false);

		super.onDestroy();

	}

	private void onDisconnect() {

		final StringBuilder sb = new StringBuilder();

		sb.append(Utils.getIptables()).append(" -t nat -F OUTPUT\n");

		if ("https".equals(proxyType)) {
			sb.append("kill -9 `cat " + basePath + "gost.pid`\n");
		}

		if (isAuth && isNTLM) {
			sb.append("kill -9 `cat " + basePath + "cntlm.pid`\n");
		}

		sb.append(basePath + "proxy.sh " + basePath + " stop\n");

		new Thread() {
			@Override
			public void run() {
				Utils.runRootCommand(sb.toString());
			}
		}.start();

	}

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_CONNECT_START:
					AppSettingsStore.putBoolean(ProxyDroidService.this, AppSettingsStore.KEY_IS_CONNECTING, true);
					Utils.setConnecting(true);
					break;
				case MSG_CONNECT_FINISH:
					AppSettingsStore.putBoolean(ProxyDroidService.this, AppSettingsStore.KEY_IS_CONNECTING, false);
					Utils.setConnecting(false);
					break;
				case MSG_CONNECT_SUCCESS:
					AppSettingsStore.putBoolean(ProxyDroidService.this, AppSettingsStore.KEY_IS_RUNNING, true);
					break;
				case MSG_CONNECT_FAIL:
					AppSettingsStore.putBoolean(ProxyDroidService.this, AppSettingsStore.KEY_IS_RUNNING, false);
					break;
				case MSG_CONNECT_PAC_ERROR:
					Toast.makeText(ProxyDroidService.this, R.string.msg_pac_error, Toast.LENGTH_SHORT)
							.show();
					break;
				case MSG_CONNECT_RESOLVE_ERROR:
					Toast.makeText(ProxyDroidService.this, R.string.msg_resolve_error,
							Toast.LENGTH_SHORT).show();
					break;
			}
			super.handleMessage(msg);
		}
	};

	// Local Ip address
	public String getLocalIpAddress() {
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en
					.hasMoreElements(); ) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
						.hasMoreElements(); ) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						return inetAddress.getHostAddress();
					}
				}
			}
		} catch (SocketException ex) {
			Log.e(TAG, ex.toString());
		}
		return null;
	}

	private boolean getAddress() {

		if (isPAC) {
			try {
				PacScriptSource src = new UrlPacScriptSource(host);
				PacProxySelector ps = new PacProxySelector(src);
				URI uri = new URI("http://gaednsproxy.appspot.com");
				List<Proxy> list = ps.select(uri);
				if (list != null && list.size() != 0) {

					Proxy p = list.get(0);

					// No proxy means error
					if (p.equals(Proxy.NO_PROXY) || p.host == null || p.port == 0 || p.type == null) {
						handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
						return false;
					}

					proxyType = p.type;
					host = p.host;
					port = p.port;

				} else {
					// No proxy means error
					handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
					return false;
				}
			} catch (URISyntaxException ignore) {
				handler.sendEmptyMessageDelayed(MSG_CONNECT_PAC_ERROR, 3000);
				return false;
			}
		}

		hostName = host;

		try {
			host = InetAddress.getByName(host).getHostAddress();
		} catch (UnknownHostException e) {
			host = hostName;
			handler.sendEmptyMessageDelayed(MSG_CONNECT_RESOLVE_ERROR, 3000);
			return false;
		}

		Log.d(TAG, "Proxy: " + host);
		Log.d(TAG, "Local Port: " + port);

		return true;
	}

	private String getProfileName() {
		long id = ProfileStore.getActiveProfileId(this);
		Profile p = ProfileStore.loadProfileById(this, id);
		if (p != null && p.getName() != null && !p.getName().isEmpty()) {
			return p.getName();
		}
		return getString(R.string.profile_base) + " " + id;
	}

	private void updateWidgetToggle(boolean on) {
		try {
			RemoteViews views = new RemoteViews(getPackageName(), R.layout.proxydroid_appwidget);
			views.setImageViewResource(R.id.serviceToggle, on ? R.drawable.on : R.drawable.off);
			AppWidgetManager awm = AppWidgetManager.getInstance(this);
			awm.updateAppWidget(
					awm.getAppWidgetIds(new ComponentName(this, ProxyDroidWidgetProvider.class)),
					views);
		} catch (Exception ignore) {
			// Nothing
		}
	}

	/**
	 * Keys must match {@link ProxyServiceHelper#buildStartExtras(Profile)} ({@link Profile.Columns}).
	 * {@link Profile.Columns#CERTIFICATE} and {@link Profile.Columns#IS_DNS_PROXY} are passed in extras but not yet applied here.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getExtras() == null) {
			return START_STICKY;
		}

		Log.d(TAG, "Service Start");

		Bundle bundle = intent.getExtras();
		host = bundle.getString(Profile.Columns.HOST);
		bypassAddrs = bundle.getString(Profile.Columns.BYPASS_ADDRS);
		proxyType = bundle.getString(Profile.Columns.PROXY_TYPE);
		port = bundle.getInt(Profile.Columns.PORT);
		isAutoSetProxy = bundle.getBoolean(Profile.Columns.IS_AUTO_SET_PROXY);
		isBypassApps = bundle.getBoolean(Profile.Columns.IS_BYPASS_APPS);
		isAuth = bundle.getBoolean(Profile.Columns.IS_AUTH);
		isNTLM = bundle.getBoolean(Profile.Columns.IS_NTLM);
		isPAC = bundle.getBoolean(Profile.Columns.IS_PAC);

		if (isAuth) {
			auth = "true";
			user = bundle.getString(Profile.Columns.USER);
			password = bundle.getString(Profile.Columns.PASSWORD);
		} else {
			auth = "false";
			user = "";
			password = "";
		}

		if (isNTLM) {
			domain = bundle.getString(Profile.Columns.DOMAIN);
		} else {
			domain = "";
		}

		new Thread(() -> {

			handler.sendEmptyMessage(MSG_CONNECT_START);

			hasRedirectSupport = Utils.getHasRedirectSupport();

			if (getAddress() && handleCommand()) {
				// Connection and forward successful
				notifyAlert(getString(R.string.forward_success) + " | " + getProfileName(),
						getString(R.string.service_running));

				handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);

				updateWidgetToggle(true);

			} else {
				// Connection or forward unsuccessful

				stopSelf();
				handler.sendEmptyMessage(MSG_CONNECT_FAIL);
			}

			handler.sendEmptyMessage(MSG_CONNECT_FINISH);

		}).start();

		markServiceStarted();
		return START_STICKY;
	}

}
