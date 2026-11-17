package org.proxydroid.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.proxydroid.Profile;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** App-side preparation: the root host receives read-only private descriptors. */
public final class EngineConfig implements Closeable {
    public final ParcelFileDescriptor config, trust, cntlm;
    public final Bundle rules;
    private EngineConfig(ParcelFileDescriptor c, ParcelFileDescriptor t, ParcelFileDescriptor n, Bundle r) {
        config = c; trust = t; cntlm = n; rules = r;
    }
    public static EngineConfig prepare(Context context, Bundle profile, String originalHost, String address, int port) throws Exception {
        String type = profile.getString(Profile.Columns.PROXY_TYPE, "http");
        boolean tls = "https".equals(type);
        String path = profile.getString(Profile.Columns.CNTLM_CONFIG_PATH, "");
        boolean ntlm = !path.isEmpty() || (profile.getBoolean(Profile.Columns.IS_AUTH) && profile.getBoolean(Profile.Columns.IS_NTLM));
        if (ntlm && !(type.equals("http") || tls || type.equals("http-tunnel")))
            throw new IOException("cntlm requires HTTP or HTTPS proxy type");
        if (!address.matches("[0-9.]+") || port < 1 || port > 65535) throw new IOException("IPv4 proxy address and valid port required");
        StringBuilder config = new StringBuilder("base { log_debug = off; log_info = off; daemon = off; redirector = iptables; }\n");
        if (type.equals("http") || tls) {
            section(config, profile, address, originalHost, port, 8123, "http-relay", tls, ntlm);
            section(config, profile, address, originalHost, port, 8124, "http-connect", tls, ntlm);
        } else {
            String relay = type.equals("http-tunnel") ? "http-connect" : type;
            if (!(relay.equals("socks4") || relay.equals("socks5") || relay.equals("http-connect"))) throw new IOException("Unknown proxy type");
            section(config, profile, address, originalHost, port, 8123, relay, false, ntlm);
        }
        Bundle rules = new Bundle();
        rules.putString("type", type);
        rules.putString("proxy", address);
        rules.putBoolean("allApps", profile.getBoolean(Profile.Columns.IS_AUTO_SET_PROXY, true));
        rules.putBoolean("bypassApps", profile.getBoolean(Profile.Columns.IS_BYPASS_APPS));
        rules.putStringArray("bypass", Profile.decodeAddrs(profile.getString(Profile.Columns.BYPASS_ADDRS, "")));
        Set<String> names = new HashSet<>(Arrays.asList(profile.getString(Profile.Columns.PROXIED_APPS, "").split("\\|")));
        Set<Integer> uids = new HashSet<>();
        for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) {
            String name = context.getPackageManager().getNameForUid(app.uid);
            if (names.contains(name) || (!profile.getBoolean(Profile.Columns.IS_BYPASS_APPS) && app.packageName.equals(context.getPackageName()))) uids.add(app.uid);
        }
        int[] ids = new int[uids.size()]; int pos = 0;
        for (int uid : uids) ids[pos++] = uid;
        Arrays.sort(ids); rules.putIntArray("uids", ids);
        ParcelFileDescriptor c = null, t = null, n = null;
        try {
            c = privateDescriptor(context, config.toString().getBytes(StandardCharsets.UTF_8));
            if (tls) t = privateDescriptor(context, trustAnchors());
            if (ntlm) n = privateDescriptor(context, mergeCntlm(profile, originalHost, port).getBytes(StandardCharsets.UTF_8));
            return new EngineConfig(c, t, n, rules);
        } catch (Exception e) {
            if (c != null) c.close(); if (t != null) t.close(); if (n != null) n.close(); throw e;
        }
    }
    private static String quote(String text) throws IOException {
        if (text == null) text = "";
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) throw new IOException("Configuration value contains a line break");
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static void section(StringBuilder out, Bundle p, String ip, String host, int port, int local, String type, boolean tls, boolean ntlm) throws IOException {
        out.append("redsocks { local_ip = 127.0.0.1; local_port = ").append(local)
            .append("; ip = ").append(ip).append("; port = ").append(port).append("; type = ").append(quote(type)).append(';');
        if (tls) out.append(" tls = on; tls_host = ").append(quote(host)).append(';');
        if (ntlm) out.append(" cntlm_context = \"default\";");
        else if (p.getBoolean(Profile.Columns.IS_AUTH)) out.append(" login = ").append(quote(p.getString(Profile.Columns.USER, "")))
            .append("; password = ").append(quote(p.getString(Profile.Columns.PASSWORD, ""))).append(';');
        out.append(" }\n");
    }
    /* Retain the original file, repeated options, comments and section syntax.
     * Only absent settings are appended. Any supplied hash suppresses fallback
     * App password injection, including a different selected Auth dialect. */
    static String mergeCntlm(Bundle p, String host, int port) throws IOException {
        String path = p.getString(Profile.Columns.CNTLM_CONFIG_PATH, "");
        String source = "";
        if (!path.isEmpty()) {
            try (FileInputStream in = new FileInputStream(path); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (bytes.size()+count > 1024*1024) throw new IOException("cntlm configuration exceeds 1 MiB");
                    bytes.write(buffer, 0, count);
                }
                source = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        Set<String> keys = new HashSet<>();
        for (String line : source.split("\\r?\\n")) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#") || text.startsWith(";") || text.startsWith("[")) continue;
            keys.add(text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT));
        }
        StringBuilder out = new StringBuilder(source).append('\n');
        fallback(out, keys, "Proxy", host+":"+port);
        fallback(out, keys, "Username", p.getString(Profile.Columns.USER, ""));
        fallback(out, keys, "Domain", p.getString(Profile.Columns.DOMAIN, ""));
        if (!keys.contains("passlm") && !keys.contains("passnt") && !keys.contains("passntlmv2"))
            fallback(out, keys, "Password", p.getString(Profile.Columns.PASSWORD, ""));
        return out.toString();
    }
    private static void fallback(StringBuilder out, Set<String> keys, String key, String value) throws IOException {
        if (!keys.contains(key.toLowerCase(Locale.ROOT)) && value != null && !value.isEmpty()) out.append(key).append(' ').append(quote(value)).append('\n');
    }
    private static byte[] trustAnchors() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore)null);
        StringBuilder pem = new StringBuilder();
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager)
            for (X509Certificate cert : ((X509TrustManager)manager).getAcceptedIssuers()) {
                pem.append("-----BEGIN CERTIFICATE-----\n");
                pem.append(Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP)).append('\n');
                pem.append("-----END CERTIFICATE-----\n");
            }
        if (pem.length() == 0) throw new IOException("System trust store is empty");
        return pem.toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static ParcelFileDescriptor privateDescriptor(Context context, byte[] bytes) throws IOException {
        File temporary = File.createTempFile("engine-", ".conf", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally {
            Arrays.fill(bytes, (byte)0);
            if (!temporary.delete()) temporary.deleteOnExit();
        }
    }
    @Override public void close() throws IOException {
        try { config.close(); } finally { try { if (trust != null) trust.close(); } finally { if (cntlm != null) cntlm.close(); } }
    }
}
