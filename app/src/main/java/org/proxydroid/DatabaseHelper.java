package org.proxydroid;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

public class DatabaseHelper extends SQLiteOpenHelper {

	private static final String DATABASE_NAME = "profiles.db";
	private static final int DATABASE_VERSION = 3;

	private final Context appContext;

	public DatabaseHelper(Context context) {
		super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
		this.appContext = context.getApplicationContext();
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		createProfilesTable(db);
		db.execSQL(
				"CREATE TABLE IF NOT EXISTS " + AppSettingsStore.TABLE_NAME + " ("
						+ AppSettingsStore.COLUMN_NAME + " TEXT PRIMARY KEY NOT NULL, "
						+ AppSettingsStore.COLUMN_VALUE + " TEXT"
						+ ");"
		);
		insertDefaultProfiles(db);
	}

	private static void createProfilesTable(SQLiteDatabase db) {
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
						+ Profile.Columns.NTLM_AUTH_MODE + " TEXT NOT NULL DEFAULT '" + NtlmSettings.DEFAULT_MODE + "', "
						+ Profile.Columns.NTLM_USE_HASHES + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.NTLM_PASS_NTLMV2 + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.NTLM_PASS_NT + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.NTLM_PASS_LM + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.NTLM_RECONFIGURE + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.CERTIFICATE + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.IS_AUTH + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_NTLM + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_AUTO_CONNECT + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_AUTO_SET_PROXY + " BOOLEAN NOT NULL DEFAULT 1, "
						+ Profile.Columns.IS_BYPASS_APPS + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_PAC + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.IS_DNS_PROXY + " BOOLEAN NOT NULL DEFAULT 0, "
						+ Profile.Columns.DOMAIN + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.SSID + " TEXT NOT NULL DEFAULT '', "
						+ Profile.Columns.EXCLUDED_SSID + " TEXT NOT NULL DEFAULT ''"
						+ ");"
		);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 3) {
			String table = Profile.TABLE_NAME;
			String legacy = "profiles_before_ntlm_ui";
			String columns = TextUtils.join(", ", Profile.Columns.BASE_COLUMNS);
			String reconfigure = oldVersion >= 2 ? "CASE WHEN cntlmConfigPath <> '' THEN 1 ELSE 0 END" : "0";
			db.execSQL("ALTER TABLE " + table + " RENAME TO " + legacy);
			createProfilesTable(db);
			db.execSQL("INSERT INTO " + table + " (" + columns + ", " + Profile.Columns.NTLM_RECONFIGURE
					+ ") SELECT " + columns + ", " + reconfigure + " FROM " + legacy);
			// Preserve AUTOINCREMENT even if the profile with the highest ID was deleted.
			db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", new String[]{table});
			db.execSQL("UPDATE sqlite_sequence SET name = ? WHERE name = ?", new String[]{table, legacy});
			db.execSQL("DROP TABLE " + legacy);
		}
	}

	private void insertDefaultProfiles(SQLiteDatabase db) {
		ContentValues first = new ContentValues();
		first.put(Profile.Columns.PROFILE_NAME, appContext.getString(R.string.profile_default));
		first.put(Profile.Columns.HOST, "");
		first.put(Profile.Columns.PROXY_TYPE, "http");
		first.put(Profile.Columns.PORT, 3128);
		first.put(Profile.Columns.IS_AUTO_SET_PROXY, true);
		db.insert(Profile.TABLE_NAME, null, first);
	}
}
