package org.proxydroid;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

public class ProxyDroidCLI extends ContentProvider {

	public static final String AUTHORITY = "org.proxydroid.cli";
	public static final String PATH_PROFILES = "profiles";

	public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_PROFILES);

	public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.org.proxydroid.profile";
	public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.org.proxydroid.profile";

	private static final int PROFILES = 1;
	private static final int PROFILE_ACTIVE = 2;
	private static final int PROFILE_ID = 3;

	private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

	static {
		URI_MATCHER.addURI(AUTHORITY, PATH_PROFILES, PROFILES);
		URI_MATCHER.addURI(AUTHORITY, PATH_PROFILES + "/active", PROFILE_ACTIVE);
		URI_MATCHER.addURI(AUTHORITY, PATH_PROFILES + "/#", PROFILE_ID);
	}

	private DatabaseHelper dbHelper;

	@Override
	public boolean onCreate() {
		dbHelper = new DatabaseHelper(getContext());
		ProfileStore.ensureInitialized(getContext());
		return true;
	}

	@Override
	public String getType(Uri uri) {
		switch (URI_MATCHER.match(uri)) {
			case PROFILES:
				return CONTENT_TYPE;
			case PROFILE_ACTIVE:
			case PROFILE_ID:
				return CONTENT_ITEM_TYPE;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}
	}

	@Override
	public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs,
						String sortOrder) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		Cursor c;
		switch (URI_MATCHER.match(uri)) {
			case PROFILES:
				c = db.query(
						Profile.TABLE_NAME,
						projection,
						selection,
						selectionArgs,
						null,
						null,
						sortOrder == null ? Profile.Columns._ID + " ASC" : sortOrder);
				break;
			case PROFILE_ACTIVE:
				long activeId = ProfileStore.getActiveProfileId(getContext());
				if (activeId >= 0) {
					c = db.query(
							Profile.TABLE_NAME,
							projection,
							Profile.Columns._ID + " = ?",
							new String[]{String.valueOf(activeId)},
							null,
							null,
							null);
				} else {
					c = db.query(
							Profile.TABLE_NAME,
							projection,
							"1 = 0",
							null,
							null,
							null,
							null);
				}
				break;
			case PROFILE_ID:
				long id = ContentUris.parseId(uri);
				c = db.query(
						Profile.TABLE_NAME,
						projection,
						Profile.Columns._ID + " = ?",
						new String[]{String.valueOf(id)},
						null,
						null,
						null);
				break;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}
		c.setNotificationUri(getContext().getContentResolver(), CONTENT_URI);
		return c;
	}

	@Override
	public Uri insert(@NonNull Uri uri, ContentValues values) {
		if (URI_MATCHER.match(uri) != PROFILES) {
			throw new IllegalArgumentException("Unknown URI " + uri);
		}
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		long id = db.insert(Profile.TABLE_NAME, null, values);
		if (id < 0) {
			return null;
		}
		Uri rowUri = ContentUris.withAppendedId(CONTENT_URI, id);
		notifyChanged(getContext());
		return rowUri;
	}

	@Override
	public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		int count;
		switch (URI_MATCHER.match(uri)) {
			case PROFILES:
				count = db.update(Profile.TABLE_NAME, values, selection, selectionArgs);
				break;
			case PROFILE_ID:
				long id = ContentUris.parseId(uri);
				count = db.update(
						Profile.TABLE_NAME,
						values,
						Profile.Columns._ID + " = ?",
						new String[]{String.valueOf(id)});
				break;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}
		if (count > 0) {
			notifyChanged(getContext());
		}
		return count;
	}

	@Override
	public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		int count;
		switch (URI_MATCHER.match(uri)) {
			case PROFILES:
				count = db.delete(Profile.TABLE_NAME, selection, selectionArgs);
				break;
			case PROFILE_ID:
				long id = ContentUris.parseId(uri);
				reassignActiveProfileIfDeleted(id);
				count = db.delete(
						Profile.TABLE_NAME,
						Profile.Columns._ID + " = ?",
						new String[]{String.valueOf(id)});
				break;
			default:
				throw new IllegalArgumentException("Unknown URI " + uri);
		}
		if (count > 0) {
			notifyChanged(getContext());
		}
		return count;
	}

	private void reassignActiveProfileIfDeleted(long deletedId) {
		Context ctx = getContext();
		if (ctx == null) {
			return;
		}
		String activeStr = AppSettingsStore.getString(ctx, AppSettingsStore.KEY_ACTIVE_PROFILE, null);
		long activeId = ProfileStore.parseStoredProfileId(activeStr);
		if (activeId != deletedId) {
			return;
		}
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		long newId = -1;
		try (Cursor c = db.query(
				Profile.TABLE_NAME,
				new String[]{Profile.Columns._ID},
				Profile.Columns._ID + " != ?",
				new String[]{String.valueOf(deletedId)},
				null,
				null,
				Profile.Columns._ID + " ASC",
				"1")) {
			if (c.moveToFirst()) {
				newId = c.getLong(0);
			}
		}
		if (newId >= 0) {
			AppSettingsStore.putString(ctx, AppSettingsStore.KEY_ACTIVE_PROFILE, String.valueOf(newId));
		}
	}

	public static void notifyChanged(Context context) {
		context.getContentResolver().notifyChange(CONTENT_URI, null);
	}

	public static final String METHOD_START = "start";
	public static final String METHOD_STOP = "stop";
	public static final String METHOD_ACTIVATE = "activate";
	public static final String EXTRA_SUCCESS = "success";

	@Override
	public Bundle call(@NonNull String method, String arg, Bundle extras) {
		switch (method) {
			case METHOD_START:
				Profile p = ProfileStore.loadActiveProfile(getContext());
				return callResult(ProxyServiceHelper.startProxyService(getContext(), p));
			case METHOD_STOP:
				return callResult(ProxyServiceHelper.stopProxyService(getContext()));
			case METHOD_ACTIVATE:
				long id = Long.parseLong(arg);
				return callResult(ProfileStore.activateProfile(getContext(), id));
		}
		return super.call(method, arg, extras);
	}

	private Bundle callResult(boolean ok) {
		Bundle result = new Bundle();
		result.putBoolean(EXTRA_SUCCESS, ok);
		return result;
	}
}
