/* Copyright (c) 2009, Nathan Freitas, Orbot / The Guardian Project - http://openideals.com/guardian */
/* See LICENSE for licensing information */

package org.proxydroid;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.proxydroid.utils.ImageLoader;
import org.proxydroid.utils.ImageLoaderFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.ArrayList;

public class AppManager extends AppCompatActivity implements OnCheckedChangeListener,
		OnClickListener {

	private ProxiedApp[] apps = null;

	private ListView listApps;

	private AppManager mAppManager;

	private TextView overlay;

	private ProgressDialog pd = null;
	private ListAdapter adapter;

	private ImageLoader dm;

	private static final int MSG_LOAD_START = 1;
	private static final int MSG_LOAD_FINISH = 2;

	private boolean appsLoaded = false;

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_LOAD_START:
					pd = ProgressDialog.show(AppManager.this, "",
							getString(R.string.loading), true, true);
					break;
				case MSG_LOAD_FINISH:

					listApps.setAdapter(adapter);

					listApps.setOnScrollListener(new OnScrollListener() {

						boolean visible;

						@Override
						public void onScrollStateChanged(AbsListView view,
														 int scrollState) {
							visible = true;
							if (scrollState == ListView.OnScrollListener.SCROLL_STATE_IDLE) {
								overlay.setVisibility(View.INVISIBLE);
							}
						}

						@Override
						public void onScroll(AbsListView view,
											 int firstVisibleItem, int visibleItemCount,
											 int totalItemCount) {
							if (visible) {
								String name = apps[firstVisibleItem].getName();
								if (name != null && name.length() > 1)
									overlay.setText(apps[firstVisibleItem]
											.getName().substring(0, 1));
								else
									overlay.setText("*");
								overlay.setVisibility(View.VISIBLE);
							}
						}
					});

					if (pd != null) {
						pd.dismiss();
						pd = null;
					}
					break;
			}
			super.handleMessage(msg);
		}
	};

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case android.R.id.home:
				// app icon in action bar clicked; go home
//			Intent intent = new Intent(this, ProxyDroid.class);
//			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
//			startActivity(intent);
				finish();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		this.setContentView(R.layout.layout_apps);

		dm = ImageLoaderFactory.getImageLoader(this);

		this.overlay = (TextView) View.inflate(this, R.layout.overlay, null);
		getWindowManager()
				.addView(
						overlay,
						new WindowManager.LayoutParams(
								LayoutParams.WRAP_CONTENT,
								LayoutParams.WRAP_CONTENT,
								WindowManager.LayoutParams.TYPE_APPLICATION,
								WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
										| WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
								PixelFormat.TRANSLUCENT));

		mAppManager = this;

	}

	/**
	 * Called when the activity is closed.
	 */
	@Override
	public void onDestroy() {

		getWindowManager().removeView(overlay);

		super.onDestroy();
	}

	@Override
	protected void onResume() {
		super.onResume();

		new Thread() {

			@Override
			public void run() {
				handler.sendEmptyMessage(MSG_LOAD_START);

				listApps = (ListView) findViewById(R.id.applistview);

				if (!appsLoaded)
					loadApps();
				handler.sendEmptyMessage(MSG_LOAD_FINISH);
			}
		}.start();

	}

	private void loadApps() {
		getApps(this);

		Arrays.sort(apps, new Comparator<ProxiedApp>() {
			@Override
			public int compare(ProxiedApp o1, ProxiedApp o2) {
				if (o1 == null || o2 == null || o1.getName() == null
						|| o2.getName() == null)
					return 1;
				if (o1.isProxied() == o2.isProxied())
					return o1.getName().compareTo(o2.getName());
				if (o1.isProxied())
					return -1;
				return 1;
			}
		});

		final LayoutInflater inflater = getLayoutInflater();

		adapter = new ArrayAdapter<ProxiedApp>(this, R.layout.layout_apps_item,
				R.id.itemtext, apps) {
			@Override
			public View getView(int position, View convertView, ViewGroup parent) {
				ListEntry entry;
				if (convertView == null) {
					// Inflate a new view
					convertView = inflater.inflate(R.layout.layout_apps_item,
							parent, false);
					entry = new ListEntry();
					entry.icon = (ImageView) convertView
							.findViewById(R.id.itemicon);
					entry.box = (CheckBox) convertView
							.findViewById(R.id.itemcheck);
					entry.text = (TextView) convertView
							.findViewById(R.id.itemtext);

					entry.text.setOnClickListener(mAppManager);

					convertView.setTag(entry);

					entry.box.setOnCheckedChangeListener(mAppManager);
				} else {
					// Convert an existing view
					entry = (ListEntry) convertView.getTag();
				}

				final ProxiedApp app = apps[position];

				entry.icon.setTag(app.getUid());

				dm.DisplayImage(app.getUid(),
						(Activity) convertView.getContext(), entry.icon);

				entry.text.setText(app.getName());

				final CheckBox box = entry.box;
				box.setTag(app);
				box.setChecked(app.isProxied());

				entry.text.setTag(box);

				return convertView;
			}
		};

		appsLoaded = true;

	}

	private static class ListEntry {
		private CheckBox box;
		private TextView text;
		private ImageView icon;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see android.app.Activity#onStop()
	 */
	@Override
	protected void onStop() {
		super.onStop();

		// Log.d(getClass().getName(),"Exiting Preferences");
	}

	public static ProxiedApp[] getProxiedApps(Context context, boolean self) {

		Profile active = ProfileStore.loadActiveProfile(context);
		String tordAppString = active != null && active.getProxiedApps() != null
				? active.getProxiedApps() : "";
		String[] tordApps;

		StringTokenizer st = new StringTokenizer(tordAppString, "|");
		tordApps = new String[st.countTokens()];
		int tordIdx = 0;
		while (st.hasMoreTokens()) {
			tordApps[tordIdx++] = st.nextToken();
		}

		Arrays.sort(tordApps);

		// else load the apps up
		PackageManager pMgr = context.getPackageManager();

		List<ApplicationInfo> lAppInfo = pMgr.getInstalledApplications(0);

		Iterator<ApplicationInfo> itAppInfo = lAppInfo.iterator();

		ArrayList<ProxiedApp> vectorApps = new ArrayList<>();

		ApplicationInfo aInfo = null;

		int appIdx = 0;

		while (itAppInfo.hasNext()) {
			aInfo = itAppInfo.next();

			// ignore all system apps
			if (aInfo.uid < 10000)
				continue;

			ProxiedApp app = new ProxiedApp();

			app.setUid(aInfo.uid);

			app.setUsername(pMgr.getNameForUid(app.getUid()));

			// check if this application is allowed
			if (aInfo.packageName != null
					&& aInfo.packageName.equals("org.proxydroid")) {
				if (self)
					app.setProxied(true);
			} else if (Arrays.binarySearch(tordApps, app.getUsername()) >= 0) {
				app.setProxied(true);
			} else {
				app.setProxied(false);
			}

			if (app.isProxied())
				vectorApps.add(app);

		}

		ProxiedApp[] apps = new ProxiedApp[vectorApps.size()];
		vectorApps.toArray(apps);
		return apps;
	}

	public void getApps(Context context) {

		Profile active = ProfileStore.loadActiveProfile(context);
		String tordAppString = active != null && active.getProxiedApps() != null
				? active.getProxiedApps() : "";
		String[] tordApps;

		StringTokenizer st = new StringTokenizer(tordAppString, "|");
		tordApps = new String[st.countTokens()];
		int tordIdx = 0;
		while (st.hasMoreTokens()) {
			tordApps[tordIdx++] = st.nextToken();
		}

		Arrays.sort(tordApps);

		ArrayList<ProxiedApp> vectorApps = new ArrayList<>();

		// else load the apps up
		PackageManager pMgr = context.getPackageManager();

		List<ApplicationInfo> lAppInfo = pMgr.getInstalledApplications(0);

		Iterator<ApplicationInfo> itAppInfo = lAppInfo.iterator();

		ApplicationInfo aInfo = null;

		while (itAppInfo.hasNext()) {
			aInfo = itAppInfo.next();

			// ignore system apps
			if (aInfo.uid < 10000)
				continue;

			if (aInfo.processName == null)
				continue;
			if (pMgr.getApplicationLabel(aInfo) == null
					|| pMgr.getApplicationLabel(aInfo).toString().equals(""))
				continue;
			if (pMgr.getApplicationIcon(aInfo) == null)
				continue;

			ProxiedApp tApp = new ProxiedApp();

			tApp.setEnabled(aInfo.enabled);
			tApp.setUid(aInfo.uid);
			tApp.setUsername(pMgr.getNameForUid(tApp.getUid()));
			tApp.setProcname(aInfo.processName);
			tApp.setName(pMgr.getApplicationLabel(aInfo).toString());

			// check if this application is allowed
			if (Arrays.binarySearch(tordApps, tApp.getUsername()) >= 0) {
				tApp.setProxied(true);
			} else {
				tApp.setProxied(false);
			}

			vectorApps.add(tApp);
		}

		apps = new ProxiedApp[vectorApps.size()];
		vectorApps.toArray(apps);

	}

	public void saveAppSettings(Context context) {
		if (apps == null)
			return;

		StringBuilder tordApps = new StringBuilder();

		for (int i = 0; i < apps.length; i++) {
			if (apps[i].isProxied()) {
				tordApps.append(apps[i].getUsername());
				tordApps.append("|");
			}
		}

		long id = ProfileStore.getActiveProfileId(context);
		if (id >= 0) {
			ContentValues cv = new ContentValues();
			cv.put(Profile.Columns.PROXIED_APPS, tordApps.toString());
			context.getContentResolver().update(
					ContentUris.withAppendedId(ProxyDroidCLI.CONTENT_URI, id),
					cv,
					null,
					null);
		}

	}

	/**
	 * Called an application is check/unchecked
	 */
	@Override
	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
		final ProxiedApp app = (ProxiedApp) buttonView.getTag();
		if (app != null) {
			app.setProxied(isChecked);
		}

		saveAppSettings(this);

	}

	@Override
	public void onClick(View v) {

		CheckBox cbox = (CheckBox) v.getTag();

		final ProxiedApp app = (ProxiedApp) cbox.getTag();
		if (app != null) {
			app.setProxied(!app.isProxied());
			cbox.setChecked(app.isProxied());
		}

		saveAppSettings(this);

	}

}
