package org.proxydroid.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import org.proxydroid.ProxyDroidService;
import org.proxydroid.R;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Utils {

	public static final String TAG = "ProxyDroid";
	public static final String DEFAULT_IPTABLES = "iptables";
	public static final String ALTERNATIVE_IPTABLES = "/system/bin/iptables";
	public static final int TIME_OUT = -99;

	private static final int ROOT_CMD_TIMEOUT_MS = 10_000;

	private static boolean initialized;
	private static int hasRedirectSupport = -1;
	private static String iptables;
	private static String data_path;
	private static boolean isConnecting;

	public static boolean isConnecting() {
		return isConnecting;
	}

	public static void setConnecting(boolean value) {
		isConnecting = value;
	}

	public static String preserve(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '$' || c == '`' || c == '"')
				sb.append('\\');
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Lines for libsu jobs; omit {@code exit} so the main shell stays alive.
	 */
	private static List<String> normalizeScriptLines(String script) {
		if (script == null || script.isEmpty())
			return Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (String line : script.split("\n")) {
			String t = line.trim();
			if (!t.isEmpty() && !"exit".equalsIgnoreCase(t))
				out.add(t);
		}
		return out;
	}

	private static void checkIptables() {
		if (!isRoot()) {
			iptables = DEFAULT_IPTABLES;
			return;
		}

		iptables = DEFAULT_IPTABLES;
		StringBuilder sb = new StringBuilder();
		String probe = iptables + " --version\n" + iptables + " -L -t nat -n\nexit\n";
		if (runRootShellScript(probe, sb, ROOT_CMD_TIMEOUT_MS) == TIME_OUT)
			return;

		String lines = sb.toString();
		if (lines.contains("OUTPUT") && lines.contains("v1.4."))
			return;

		iptables = ALTERNATIVE_IPTABLES;
		if (!new File(iptables).exists())
			iptables = "iptables";
	}

	public static String getDataPath(Context ctx) {
		if (data_path == null) {
			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
				data_path = Environment.getExternalStorageDirectory().getAbsolutePath();
			else
				data_path = "/sdcard";
			Log.d(TAG, "Python Data Path: " + data_path);
		}
		return data_path;
	}

	public static boolean getHasRedirectSupport() {
		if (hasRedirectSupport == -1)
			initHasRedirectSupported();
		return hasRedirectSupport == 1;
	}

	public static String getIptables() {
		if (iptables == null)
			checkIptables();
		return iptables;
	}

	public static String getSignature(Context ctx) {
		try {
			Signature[] sigs = ctx.getPackageManager().getPackageInfo(
					ctx.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
			if (sigs != null && sigs.length > 0)
				return sigs[0].toCharsString().substring(11, 256);
		} catch (Exception ignore) {
		}
		return null;
	}

	public static void initHasRedirectSupported() {
		if (!isRoot())
			return;

		String cmd = getIptables()
				+ " -t nat -A OUTPUT -p udp --dport 54 -j REDIRECT --to 8154";
		StringBuilder sb = new StringBuilder();
		int exitcode = runRootShellScript(cmd, sb, ROOT_CMD_TIMEOUT_MS);
		String lines = sb.toString();

		hasRedirectSupport = 1;
		runRootCommand(cmd.replace("-A", "-D"));

		if (exitcode != TIME_OUT && lines.contains("No chain/target/match"))
			hasRedirectSupport = 0;
	}

	/**
	 * First call returns false and marks initialized; later calls return true.
	 */
	public static boolean isInitialized() {
		boolean was = initialized;
		initialized = true;
		return was;
	}

	public static boolean isRoot() {
		try {
			Boolean g = Shell.isAppGrantedRoot();
			if (g == null) {
				Shell.getShell();
				g = Shell.isAppGrantedRoot();
			}
			if (g != null)
				return g;
			Shell shell = Shell.getCachedShell();
			return shell != null && shell.isRoot();
		} catch (Throwable e) {
			Log.e(TAG, "Root check failed", e);
			return false;
		}
	}

	public static boolean runRootCommand(String command, int timeout) {
		Log.d(TAG, command);
		runRootShellScript(command, null, timeout);
		return true;
	}

	public static boolean runRootCommand(String command) {
		return runRootCommand(command, ROOT_CMD_TIMEOUT_MS);
	}

	private static int runRootShellScript(String script, StringBuilder res, long timeoutMs) {
		List<String> cmds = normalizeScriptLines(script);
		if (cmds.isEmpty())
			return 0;

		List<String> out = res != null ? new ArrayList<>() : null;
		try {
			Shell.Job job = Shell.getShell().newJob().add(cmds.toArray(new String[0]));
			if (out != null)
				job = job.to(out);
			Future<Shell.Result> future = job.enqueue();

			try {
				Shell.Result result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
				if (out != null) {
					for (String line : out)
						res.append(line).append('\n');
				}
				return result.getCode();
			} catch (TimeoutException e) {
				future.cancel(true);
				return TIME_OUT;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return TIME_OUT;
		} catch (ExecutionException e) {
			Throwable c = e.getCause() != null ? e.getCause() : e;
			Log.e(TAG, "Cannot execute root command", c);
			if (res != null)
				res.append('\n').append(c);
			return -1;
		} catch (Exception e) {
			Log.e(TAG, "Cannot execute root command", e);
			if (res != null)
				res.append('\n').append(e);
			return -1;
		}
	}

	public static boolean isWorking() {
		return ProxyDroidService.isServiceStarted();
	}

	public static void CopyStream(InputStream is, OutputStream os) {
		byte[] buf = new byte[1024];
		try {
			int n;
			while ((n = is.read(buf)) != -1)
				os.write(buf, 0, n);
		} catch (Exception ignored) {
		}
	}

	public static Drawable getAppIcon(Context c, int uid) {
		PackageManager pm = c.getPackageManager();
		Drawable icon = c.getResources().getDrawable(R.drawable.sym_def_app_icon);
		String[] packages = pm.getPackagesForUid(uid);
		if (packages == null) {
			Log.e(c.getPackageName(), "Package not found for uid " + uid);
			return icon;
		}
		if (packages.length != 1)
			return icon;
		try {
			return pm.getApplicationIcon(pm.getApplicationInfo(packages[0], 0));
		} catch (NameNotFoundException e) {
			Log.e(c.getPackageName(), "No package found matching with the uid " + uid);
			return icon;
		}
	}
}
