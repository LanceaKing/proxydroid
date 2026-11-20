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
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.btr.proxy.selector.pac.PacProxySelector;
import com.btr.proxy.selector.pac.PacScriptSource;
import com.btr.proxy.selector.pac.Proxy;
import com.btr.proxy.selector.pac.UrlPacScriptSource;
import com.topjohnwu.superuser.ipc.RootService;

import org.proxydroid.engine.EngineConfig;
import org.proxydroid.engine.EngineRootService;
import org.proxydroid.engine.IEngineCallback;
import org.proxydroid.engine.IRootEngine;
import org.proxydroid.utils.Utils;

import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

	private static final String TAG = "ProxyDroidService";

	private String host;
	private String hostName;
	private int port;
	private String proxyType = "http";
	private boolean isPAC = false;

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
		desired = false; destroyed = true; ++generation;
		AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_ENGINE_DESIRED, false);

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
		final IRootEngine old = rootEngine;
		control.execute(() -> {
			try {
				if (old != null) {
					old.unregisterCallback(callback);
					old.stop();
					if (old.getStatus() == 2) {
						EngineRootService.cleanupDisconnectedHost();
						old.stop();
					}
				} else EngineRootService.cleanupDisconnectedHost();
			} catch (RemoteException e) { EngineRootService.cleanupDisconnectedHost(); }
			finally { handler.post(() -> { rootEngine = null; unbindRoot(); }); }
		});
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

	// Keep stop/cleanup ordered before a replacement Service instance starts.
	private static final ExecutorService control = Executors.newSingleThreadExecutor();
	private volatile IRootEngine rootEngine;
	private volatile boolean desired, destroyed;
	private boolean binding, bound;
	private boolean nativeReady;
	private volatile int generation;
	private int rebinds;
	private Bundle pendingProfile;
	private final IEngineCallback callback = new IEngineCallback.Stub() {
		@Override public void onStateChanged(int state, String diagnostic) {
			if (state == 4) handler.post(() -> {
				if (desired && !destroyed && nativeReady) {
					nativeReady = false;
					connectionFailed(diagnostic == null || diagnostic.isEmpty() ? "Native engine stopped unexpectedly" : diagnostic);
				}
			});
		}
	};
	private final ServiceConnection connection = new ServiceConnection() {
		@Override public void onServiceConnected(ComponentName name, IBinder service) {
			binding = false; bound = true;
			if (!desired || destroyed) { unbindRoot(); return; }
			rootEngine = IRootEngine.Stub.asInterface(service);
			try { service.linkToDeath(() -> handler.post(ProxyDroidService.this::hostDied), 0); }
			catch (RemoteException e) { hostDied(); return; }
			startNative(generation, new Bundle(pendingProfile), rootEngine);
		}
		@Override public void onServiceDisconnected(ComponentName name) { hostDied(); }
		@Override public void onBindingDied(ComponentName name) { hostDied(); }
		@Override public void onNullBinding(ComponentName name) { connectionFailed("Root host returned no Binder"); }
	};
	private void bindRoot() {
		if (binding || bound || destroyed || !desired) return;
		binding = true;
		try { RootService.bind(new Intent(this, EngineRootService.class), connection); }
		catch (RuntimeException e) { binding = false; connectionFailed("Unable to bind root engine"); return; }
		final int token = generation;
		handler.postDelayed(() -> {
			if (binding && desired && token == generation) connectionFailed("Root engine startup timed out");
		}, 20000);
	}
	private void unbindRoot() {
		if (bound || binding) {
			RootService.unbind(connection);
			bound = binding = false;
		}
	}
	private void hostDied() {
		if (destroyed || rootEngine == null && !binding && !bound) return;
		rootEngine = null;
		nativeReady = false;
		unbindRoot();
		AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_IS_RUNNING, false);
		updateWidgetToggle(false);
		if (desired) notifyAlert(getString(R.string.engine_disconnected), getString(R.string.engine_disconnected));
		control.execute(() -> {
			EngineRootService.cleanupDisconnectedHost();
			handler.post(() -> {
				if (destroyed || !desired) return;
				if (rebinds++ == 0) bindRoot();
				else connectionFailed("Root engine disconnected again");
			});
		});
	}
	private void connectionFailed(String message) {
		if (destroyed) return;
		Log.e(TAG, message);
		Toast.makeText(this, message, Toast.LENGTH_LONG).show();
		handler.sendEmptyMessage(MSG_CONNECT_FAIL);
		handler.sendEmptyMessage(MSG_CONNECT_FINISH);
		stopSelf();
	}

	/**
	 * Keys must match {@link ProxyServiceHelper#buildStartExtras(Profile)} ({@link Profile.Columns}).
	 * {@link Profile.Columns#CERTIFICATE} and {@link Profile.Columns#IS_DNS_PROXY} are passed in extras but not yet applied here.
	 */
	private void startNative(int token, Bundle profile, IRootEngine remote) {
		nativeReady = false;
		control.execute(() -> {
			if (!desired || destroyed || token != generation) return;
			try {
				host = profile.getString(Profile.Columns.HOST, "");
				port = profile.getInt(Profile.Columns.PORT);
				proxyType = profile.getString(Profile.Columns.PROXY_TYPE, "http");
				isPAC = profile.getBoolean(Profile.Columns.IS_PAC);
				if (!getAddress()) throw new java.io.IOException("Unable to resolve proxy/PAC address");
				profile.putString(Profile.Columns.PROXY_TYPE, proxyType);
				try (EngineConfig config = EngineConfig.prepare(this, profile, hostName, host, port)) {
					if (!desired || destroyed || token != generation) return;
					remote.registerCallback(callback);
					if (remote.start(config.rules, config.config, config.trust, config.cntlm) != 0)
						throw new java.io.IOException(remote.getDiagnostics());
				}
				handler.post(() -> {
					if (!desired || destroyed || token != generation) return;
					nativeReady = true;
					notifyAlert(getString(R.string.forward_success) + " | " + getProfileName(), getString(R.string.service_running));
					handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);
					handler.sendEmptyMessage(MSG_CONNECT_FINISH);
					updateWidgetToggle(true);
				});
			} catch (Exception e) {
				final String message = e instanceof java.io.IOException && e.getMessage() != null
					? e.getMessage() : "Proxy engine startup failed";
				handler.post(() -> { if (desired && !destroyed && token == generation) connectionFailed(message); });
			}
		});
	}
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null || intent.getExtras() == null) {
			if (!AppSettingsStore.getBoolean(this, AppSettingsStore.KEY_ENGINE_DESIRED, false)) { stopSelf(); return START_NOT_STICKY; }
			Profile active = ProfileStore.loadActiveProfile(this);
			if (active == null) { stopSelf(); return START_NOT_STICKY; }
			pendingProfile = ProxyServiceHelper.buildStartExtras(active);
		} else pendingProfile = new Bundle(intent.getExtras());
		int error = ProxyServiceHelper.startError(pendingProfile);
		if (error != 0) {
			String message = getString(error);
			notifyAlert(getString(R.string.engine_connecting), message);
			connectionFailed(message);
			return START_NOT_STICKY;
		}
		desired = true;
		AppSettingsStore.putBoolean(this, AppSettingsStore.KEY_ENGINE_DESIRED, true);
		++generation; rebinds = 0;
		handler.sendEmptyMessage(MSG_CONNECT_START);
		notifyAlert(getString(R.string.engine_connecting), getString(R.string.engine_connecting));
		markServiceStarted();
		if (rootEngine != null) startNative(generation, new Bundle(pendingProfile), rootEngine);
		else bindRoot();
		return START_STICKY;
	}
}
