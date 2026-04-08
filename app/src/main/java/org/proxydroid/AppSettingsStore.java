package org.proxydroid;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Key-value app settings in SQLite ({@link DatabaseHelper} / {@code app_settings}).
 * Replaces {@link PreferenceManager#getDefaultSharedPreferences(Context)} for migrated keys.
 */
public final class AppSettingsStore {

	public static final String TABLE_NAME = "appSettings";
	public static final String COLUMN_NAME = "name";
	public static final String COLUMN_VALUE = "value";

	public static final String KEY_ACTIVE_PROFILE = "activeProfile";
	public static final String KEY_IS_RUNNING = "isRunning";
	public static final String KEY_IS_CONNECTING = "isConnecting";
	public static final String KEY_RINGTONE = "ringtone";
	public static final String KEY_IS_VIBRATE = "isVibrate";
	public static final String KEY_LAST_SSID = "lastSsid";
	private static final Set<String> ALL_KEYS;

	static {
		HashSet<String> s = new HashSet<>();
		s.add(KEY_ACTIVE_PROFILE);
		s.add(KEY_IS_CONNECTING);
		s.add(KEY_IS_RUNNING);
		s.add(KEY_LAST_SSID);
		s.add(KEY_RINGTONE);
		s.add(KEY_IS_VIBRATE);
		ALL_KEYS = Collections.unmodifiableSet(s);
	}

	public interface Listener {
		void onAppSettingChanged(String key);
	}

	private static final Object LOCK = new Object();
	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
	private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private AppSettingsStore() {
	}

	public static void registerListener(Listener listener) {
		if (listener != null) {
			LISTENERS.add(listener);
		}
	}

	public static void unregisterListener(Listener listener) {
		LISTENERS.remove(listener);
	}

	private static void notifyListeners(String key) {
		if (LISTENERS.isEmpty()) {
			return;
		}
		MAIN_HANDLER.post(() -> {
			for (Listener l : LISTENERS) {
				l.onAppSettingChanged(key);
			}
		});
	}

	private static String getStringRaw(SQLiteDatabase db, String key, String defValue) {
		try (Cursor c = db.query(
				TABLE_NAME,
				new String[]{COLUMN_VALUE},
				COLUMN_NAME + " = ?",
				new String[]{key},
				null,
				null,
				null)) {
			if (c.moveToFirst() && !c.isNull(0)) {
				return c.getString(0);
			}
		}
		return defValue;
	}

	private static void putStringRaw(SQLiteDatabase db, String key, String value) {
		ContentValues cv = new ContentValues();
		cv.put(COLUMN_NAME, key);
		cv.put(COLUMN_VALUE, value);
		db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	private static void deleteRaw(SQLiteDatabase db, String key) {
		db.delete(TABLE_NAME, COLUMN_NAME + " = ?", new String[]{key});
	}

	public static String getString(Context context, String key, String defValue) {
		synchronized (LOCK) {
			SQLiteDatabase db = new DatabaseHelper(context).getReadableDatabase();
			String v = getStringRaw(db, key, null);
			return v != null ? v : defValue;
		}
	}

	public static void putString(Context context, String key, String value) {
		putString(context, key, value, true);
	}

	public static void putString(Context context, String key, String value, boolean notifyListeners) {
		synchronized (LOCK) {
			SQLiteDatabase db = new DatabaseHelper(context).getWritableDatabase();
			putStringRaw(db, key, value);
		}
		if (notifyListeners) {
			notifyListeners(key);
		}
	}

	public static void remove(Context context, String key) {
		remove(context, key, true);
	}

	public static void remove(Context context, String key, boolean notifyListeners) {
		synchronized (LOCK) {
			SQLiteDatabase db = new DatabaseHelper(context).getWritableDatabase();
			deleteRaw(db, key);
		}
		if (notifyListeners) {
			notifyListeners(key);
		}
	}

	public static boolean getBoolean(Context context, String key, boolean defValue) {
		String s = getString(context, key, null);
		if (s == null) {
			return defValue;
		}
		return "1".equals(s) || "true".equalsIgnoreCase(s);
	}

	public static void putBoolean(Context context, String key, boolean value) {
		putBoolean(context, key, value, true);
	}

	public static void putBoolean(Context context, String key, boolean value, boolean notifyListeners) {
		putString(context, key, value ? "1" : "0", notifyListeners);
	}

	public static int getInt(Context context, String key, int defValue) {
		String s = getString(context, key, null);
		if (s == null) {
			return defValue;
		}
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return defValue;
		}
	}

	public static void putInt(Context context, String key, int value) {
		putString(context, key, String.valueOf(value), true);
	}

	public static long getLong(Context context, String key, long defValue) {
		String s = getString(context, key, null);
		if (s == null) {
			return defValue;
		}
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return defValue;
		}
	}

	public static void putLong(Context context, String key, long value) {
		putString(context, key, String.valueOf(value));
	}

	public static float getFloat(Context context, String key, float defValue) {
		String s = getString(context, key, null);
		if (s == null) {
			return defValue;
		}
		try {
			return Float.parseFloat(s);
		} catch (NumberFormatException e) {
			return defValue;
		}
	}

	public static void putFloat(Context context, String key, float value) {
		putString(context, key, String.valueOf(value));
	}

	public static boolean isAppSettingsKey(@Nullable String key) {
		return key != null && ALL_KEYS.contains(key);
	}
}
