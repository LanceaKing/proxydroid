package org.proxydroid;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

public final class ProfileStore {
	public static final String NEW_PROFILE_SENTINEL = "0";

	/** Default stored value for {@link AppSettingsStore#KEY_ACTIVE_PROFILE} when unset (first install). */
	public static final String DEFAULT_ACTIVE_PROFILE_ID_STRING = "1";

	private ProfileStore() {
	}

	public static void ensureInitialized(Context context) {
		DatabaseHelper helper = new DatabaseHelper(context);
		SQLiteDatabase db = helper.getWritableDatabase();
		long firstId;
		try (Cursor c = db.query(
				Profile.TABLE_NAME,
				new String[]{Profile.Columns._ID},
				null,
				null,
				null,
				null,
				Profile.Columns._ID + " ASC",
				"1")) {
			if (!c.moveToFirst()) {
				return;
			}
			firstId = c.getLong(0);
		}
		String active = AppSettingsStore.getString(context, AppSettingsStore.KEY_ACTIVE_PROFILE, null);
		long activeId = parseStoredProfileId(active);
		if (activeId < 0) {
			AppSettingsStore.putString(context, AppSettingsStore.KEY_ACTIVE_PROFILE, String.valueOf(firstId));
			return;
		}
		try (Cursor c = db.query(
				Profile.TABLE_NAME,
				new String[]{Profile.Columns._ID},
				Profile.Columns._ID + " = ?",
				new String[]{String.valueOf(activeId)},
				null,
				null,
				null,
				"1")) {
			if (c.moveToFirst()) {
				return;
			}
		}
		AppSettingsStore.putString(context, AppSettingsStore.KEY_ACTIVE_PROFILE, String.valueOf(firstId));
	}

	/**
	 * Parses a stored active-profile preference value into a row id, or {@code -1} if invalid / new-profile sentinel.
	 */
	public static long parseStoredProfileId(String active) {
		if (active == null) {
			return -1;
		}
		if (NEW_PROFILE_SENTINEL.equals(active)) {
			return -1;
		}
		try {
			return Long.parseLong(active);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static long getActiveProfileId(Context context) {
		ensureInitialized(context);
		String s = AppSettingsStore.getString(context, AppSettingsStore.KEY_ACTIVE_PROFILE, DEFAULT_ACTIVE_PROFILE_ID_STRING);
		long id = parseStoredProfileId(s);
		if (id < 0) {
			SQLiteDatabase db = new DatabaseHelper(context).getReadableDatabase();
			try (Cursor c = db.query(
					Profile.TABLE_NAME,
					new String[]{Profile.Columns._ID},
					null,
					null,
					null,
					null,
					Profile.Columns._ID + " ASC",
					"1")) {
				if (c.moveToFirst()) {
					id = c.getLong(0);
				}
			}
		}
		return id;
	}

	public static Profile loadProfileById(Context context, long id) {
		if (id < 0) {
			return null;
		}
		Uri uri = ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, id);
		Cursor c = context.getContentResolver().query(
				uri,
				Profile.Columns.ALL_COLUMNS,
				null,
				null,
				null);
		if (c == null) {
			return null;
		}
		try {
			if (!c.moveToFirst()) {
				return null;
			}
			return Profile.fromCursor(c);
		} finally {
			c.close();
		}
	}

	public static Profile loadActiveProfile(Context context) {
		long id = getActiveProfileId(context);
		return loadProfileById(context, id);
	}

	/**
	 * Sets the active profile preference to {@code profileId} if a row with that id exists.
	 *
	 * @return true if preferences were updated
	 */
	public static boolean activateProfile(Context context, long profileId) {
		if (profileId < 0 || loadProfileById(context, profileId) == null) {
			return false;
		}
		AppSettingsStore.putString(context, AppSettingsStore.KEY_ACTIVE_PROFILE, String.valueOf(profileId));
		ProxyDroidCLI.notifyChanged(context);
		return true;
	}

	/**
	 * Human-readable label for the profile list / rename dialog (matches legacy {@code ProxyDroid}
	 * behavior).
	 */
	public static String getProfileDisplayName(Context context, String profileId) {
		if (NEW_PROFILE_SENTINEL.equals(profileId)) {
			return context.getString(R.string.profile_new);
		}
		try {
			Profile p = loadProfileById(context, Long.parseLong(profileId));
			if (p != null && p.getName() != null && !p.getName().isEmpty()) {
				return p.getName();
			}
		} catch (NumberFormatException e) {
			// ignore
		}
		return context.getString(R.string.profile_base) + " " + profileId;
	}
}
