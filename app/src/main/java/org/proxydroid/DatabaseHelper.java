package org.proxydroid;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

	private static final String DATABASE_NAME = "profiles.db";
	private static final int DATABASE_VERSION = 1;

	private final Context appContext;

	public DatabaseHelper(Context context) {
		super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
		this.appContext = context.getApplicationContext();
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(
				"CREATE TABLE " + Profile.TABLE_NAME + " ("
						+ Profile.Columns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
						+ Profile.Columns.PROFILE_NAME + " TEXT NOT NULL, "
						+ Profile.Columns.HOST + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.PROXY_TYPE + " TEXT NOT NULL DEFAULT 'http', "
						+ Profile.Columns.PORT + " INTEGER NOT NULL DEFAULT 3128, "
						+ Profile.Columns.BYPASS_ADDRS + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.PROXIED_APPS + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.USER + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.PASSWORD + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.CERTIFICATE + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.IS_AUTH + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_NTLM + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_AUTO_CONNECT + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_AUTO_SET_PROXY + " INTEGER NOT NULL DEFAULT 1, "
						+ Profile.Columns.IS_BYPASS_APPS + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_PAC + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_DNS_PROXY + " INTEGER NOT NULL DEFAULT 0, "
						+ Profile.Columns.DOMAIN + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.SSID + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.EXCLUDED_SSID + " TEXT NOT NULL DEFAULT ''"
						+ ");"
		);
		db.execSQL(
				"CREATE TABLE IF NOT EXISTS " + AppSettingsStore.TABLE_NAME + " ("
						+ AppSettingsStore.COLUMN_NAME + " TEXT PRIMARY KEY NOT NULL, "
						+ AppSettingsStore.COLUMN_VALUE + " TEXT"
						+ ");"
		);

		insertDefaultProfiles(db);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		db.execSQL("DROP TABLE IF EXISTS " + Profile.TABLE_NAME);
		db.execSQL("DROP TABLE IF EXISTS " + AppSettingsStore.TABLE_NAME);
		onCreate(db);
	}

	private void insertDefaultProfiles(SQLiteDatabase db) {
		ContentValues first = new ContentValues();
		first.put(Profile.Columns.PROFILE_NAME, appContext.getString(R.string.profile_default));
		first.put(Profile.Columns.HOST, "");
		first.put(Profile.Columns.PROXY_TYPE, "http");
		first.put(Profile.Columns.PORT, 3128);
		first.put(Profile.Columns.IS_AUTO_SET_PROXY, 1);
		db.insert(Profile.TABLE_NAME, null, first);
	}
}
