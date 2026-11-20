package org.proxydroid;

import android.os.Bundle;

import java.io.IOException;
import java.util.regex.Pattern;

/** Shared NTLM validation and cntlm configuration generation for a profile. */
public final class NtlmSettings {

	public static final String DEFAULT_MODE = "NTLMv2";
	private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{32}");

	public enum Mode {
		NTLMv2(Profile.Columns.NTLM_PASS_NTLMV2),
		NTLM2SR(Profile.Columns.NTLM_PASS_NT),
		NT(Profile.Columns.NTLM_PASS_NT),
		NTLM(Profile.Columns.NTLM_PASS_NT, Profile.Columns.NTLM_PASS_LM),
		LM(Profile.Columns.NTLM_PASS_LM);

		private final String[] hashKeys;

		Mode(String... hashKeys) {
			this.hashKeys = hashKeys;
		}

		public boolean needsHash(String key) {
			for (String hashKey : hashKeys) {
				if (hashKey.equals(key)) return true;
			}
			return false;
		}
	}

	private NtlmSettings() { }

	public static Mode mode(String name) {
		for (Mode mode : Mode.values()) {
			if (mode.name().equals(name)) return mode;
		}
		return null;
	}

	public static boolean isHash(String value) {
		return value != null && HASH.matcher(value).matches();
	}

	public static boolean supportsProxyType(String type) {
		return "http".equals(type) || "https".equals(type) || "http-tunnel".equals(type);
	}

	public static boolean requested(Bundle profile) {
		return profile.getBoolean(Profile.Columns.IS_AUTH) && profile.getBoolean(Profile.Columns.IS_NTLM);
	}

	/** A null effective type means the Profile's PAC has not yet been evaluated. */
	public static int startError(Bundle profile, String effectiveType) {
		if (!requested(profile)) return 0;
		if (effectiveType != null && !supportsProxyType(effectiveType)) {
			// Stored NTLM settings are dormant for a directly selected SOCKS profile.
			return profile.getBoolean(Profile.Columns.IS_PAC) ? R.string.ntlm_proxy_type_error : 0;
		}
		if (profile.getString(Profile.Columns.HOST, "").trim().isEmpty()) return R.string.ntlm_host_required;
		Mode mode = mode(profile.getString(Profile.Columns.NTLM_AUTH_MODE, DEFAULT_MODE));
		if (mode == null) return R.string.ntlm_mode_error;
		if (profile.getBoolean(Profile.Columns.NTLM_USE_HASHES)) {
			for (String key : mode.hashKeys) {
				if (!isHash(profile.getString(key))) return R.string.ntlm_hashes_required;
			}
		} else if (profile.getString(Profile.Columns.PASSWORD, "").isEmpty()) {
			return R.string.ntlm_password_required;
		}
		return 0;
	}

	public static String configuration(Bundle profile, String host, int port) throws IOException {
		Mode mode = mode(profile.getString(Profile.Columns.NTLM_AUTH_MODE, DEFAULT_MODE));
		if (mode == null) throw new IOException("Invalid NTLM authentication method");
		StringBuilder config = new StringBuilder();
		append(config, "Proxy", host + ":" + port);
		append(config, "Username", profile.getString(Profile.Columns.USER, ""));
		append(config, "Domain", profile.getString(Profile.Columns.DOMAIN, ""));
		append(config, "Auth", mode.name());
		if (profile.getBoolean(Profile.Columns.NTLM_USE_HASHES)) {
			if (mode.needsHash(Profile.Columns.NTLM_PASS_NTLMV2))
				append(config, "PassNTLMv2", profile.getString(Profile.Columns.NTLM_PASS_NTLMV2));
			if (mode.needsHash(Profile.Columns.NTLM_PASS_NT))
				append(config, "PassNT", profile.getString(Profile.Columns.NTLM_PASS_NT));
			if (mode.needsHash(Profile.Columns.NTLM_PASS_LM))
				append(config, "PassLM", profile.getString(Profile.Columns.NTLM_PASS_LM));
		} else {
			append(config, "Password", profile.getString(Profile.Columns.PASSWORD));
		}
		return config.toString();
	}

	private static void append(StringBuilder config, String key, String value) throws IOException {
		if (value == null || value.isEmpty()) return;
		if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
			throw new IOException("An NTLM configuration field contains an invalid character");
		config.append(key).append(" \"")
				.append(value.replace("\\", "\\\\").replace("\"", "\\\""))
				.append("\"\n");
	}
}
