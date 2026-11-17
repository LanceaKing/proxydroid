package org.proxydroid;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Refreshes the executable for both Activity and CLI service starts. */
final class NativeBinaryInstaller {

	private static final String TAG = "NativeBinaryInstaller";

	private NativeBinaryInstaller() {
	}

	static synchronized boolean ensureRedsocks(Context context) {
		File temporary = null;
		try {
			String abi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
					? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
			String asset = (abi.startsWith("arm") ? "armeabi-v7a" : "x86") + "/redsocks";
			File executable = new File(context.getFilesDir(), "redsocks");
			byte[] expected;
			try (InputStream input = context.getAssets().open(asset)) {
				expected = sha256(input);
			}
			if (executable.isFile()) {
				try (InputStream input = new FileInputStream(executable)) {
					if (Arrays.equals(expected, sha256(input))) {
						makeExecutable(executable);
						return true;
					}
				}
			}
			// A same-directory rename replaces an old/running executable atomically.
			temporary = File.createTempFile("redsocks-", ".tmp", context.getFilesDir());
			try (InputStream input = context.getAssets().open(asset);
				 FileOutputStream output = new FileOutputStream(temporary)) {
				byte[] buffer = new byte[16384];
				int count;
				while ((count = input.read(buffer)) != -1) {
					output.write(buffer, 0, count);
				}
				output.getFD().sync();
			}
			try (InputStream input = new FileInputStream(temporary)) {
				if (!Arrays.equals(expected, sha256(input))) {
					throw new IOException("Bundled redsocks checksum mismatch");
				}
			}
			makeExecutable(temporary);
			if (!temporary.renameTo(executable)) {
				throw new IOException("Cannot atomically replace redsocks");
			}
			temporary = null;
			Log.i(TAG, "Updated bundled redsocks from " + asset);
			return true;
		} catch (IOException | NoSuchAlgorithmException | SecurityException e) {
			Log.e(TAG, "Cannot deploy bundled redsocks", e);
			return false;
		} finally {
			if (temporary != null && !temporary.delete()) {
				Log.w(TAG, "Cannot remove temporary redsocks file");
			}
		}
	}

	private static byte[] sha256(InputStream input) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] buffer = new byte[16384];
		int count;
		while ((count = input.read(buffer)) != -1) {
			digest.update(buffer, 0, count);
		}
		return digest.digest();
	}

	private static void makeExecutable(File file) throws IOException {
		if (!file.setReadable(true, true) || !file.setWritable(true, true)
				|| !file.setExecutable(true, true)) {
			throw new IOException("Cannot set redsocks executable permissions");
		}
	}
}
