package org.proxydroid.engine;

import android.os.Bundle;
import android.os.SystemClock;
import com.topjohnwu.superuser.Shell;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The root host owns exactly one chain and one tagged OUTPUT jump. */
final class RedirectRules {
    private static final String CHAIN = "PDROID_OUT", OWNER = "ProxyDroid.engine";
    private final String iptables;
    private boolean created;
    RedirectRules() throws IOException { iptables = binary(); }
    private static String binary() throws IOException {
        for (String path : new String[]{"/system/bin/iptables", "/system/xbin/iptables"})
            if (new File(path).canExecute()) return path;
        throw new IOException("iptables is unavailable");
    }
    void install(Bundle options) throws IOException {
        remove();
        checked("-N", CHAIN);
        created = true;
        try {
            checked("-A", CHAIN, "-m", "mark", "--mark", "0x505844", "-m", "comment", "--comment", OWNER, "-j", "RETURN");
            checked("-A", CHAIN, "-d", "127.0.0.0/8", "-j", "RETURN");
            checked("-A", CHAIN, "-d", address(options.getString("proxy")), "-j", "RETURN");
            String[] bypass = options.getStringArray("bypass");
            if (bypass != null) for (String ip : bypass) checked("-A", CHAIN, "-d", address(ip), "-j", "RETURN");
            int[] uids = options.getIntArray("uids");
            boolean bypassApps = options.getBoolean("bypassApps");
            if (bypassApps && uids != null) for (int uid : uids) {
                if (uid < 0) throw new IOException("Invalid application uid");
                checked("-A", CHAIN, "-m", "owner", "--uid-owner", String.valueOf(uid), "-j", "RETURN");
            }
            if (options.getBoolean("allApps") || bypassApps) redirect(options.getString("type"), null);
            else if (uids != null) for (int uid : uids) {
                if (uid < 0) throw new IOException("Invalid application uid");
                redirect(options.getString("type"), uid);
            }
            // This is the final mutation, after the native engine reports ready.
            checked("-A", "OUTPUT", "-p", "tcp", "-m", "comment", "--comment", OWNER, "-j", CHAIN);
        } catch (IOException e) { remove(); throw e; }
    }
    private void redirect(String type, Integer uid) throws IOException {
        if ("socks4".equals(type) || "socks5".equals(type)) rule(uid, 0, 8123);
        else {
            rule(uid, 80, 8123);
            int tunnel = "http-tunnel".equals(type) ? 8123 : 8124;
            rule(uid, 443, tunnel); rule(uid, 5228, tunnel);
        }
    }
    private void rule(Integer uid, int destination, int local) throws IOException {
        List<String> args = new ArrayList<>(Arrays.asList("-A", CHAIN, "-p", "tcp"));
        if (uid != null) args.addAll(Arrays.asList("-m", "owner", "--uid-owner", String.valueOf(uid)));
        if (destination != 0) args.addAll(Arrays.asList("--dport", String.valueOf(destination)));
        List<String> redirect = new ArrayList<>(args);
        redirect.addAll(Arrays.asList("-j", "REDIRECT", "--to-ports", String.valueOf(local)));
        if (run(redirect.toArray(new String[0])).code != 0) {
            args.addAll(Arrays.asList("-j", "DNAT", "--to-destination", "127.0.0.1:"+local));
            checked(args.toArray(new String[0]));
        }
    }
    void remove() throws IOException {
        Result output = run("-S", "OUTPUT");
        if (output.code != 0) throw new IOException("Cannot inspect iptables OUTPUT");
        for (String line : output.output.split("\n")) if (line.contains(OWNER) && line.endsWith("-j "+CHAIN))
            checked("-D", "OUTPUT", "-p", "tcp", "-m", "comment", "--comment", OWNER, "-j", CHAIN);
        Result chain = run("-S", CHAIN);
        if (chain.code == 0) {
            if (!created && !chain.output.contains(OWNER)) throw new IOException("ProxyDroid chain ownership mismatch");
            checked("-F", CHAIN); checked("-X", CHAIN); created = false;
        }
    }
    private static String address(String text) throws IOException {
        if (text == null || !text.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?:/[0-9]{1,2})?")) throw new IOException("Invalid bypass/proxy address");
        String[] subnet = text.split("/", 2);
        for (String octet : subnet[0].split("\\."))
            if (Integer.parseInt(octet) > 255) throw new IOException("Invalid bypass/proxy address");
        if (subnet.length == 2 && Integer.parseInt(subnet[1]) > 32) throw new IOException("Invalid bypass/proxy address");
        return text;
    }
    private void checked(String... args) throws IOException {
        if (run(args).code != 0) throw new IOException("Failed to update ProxyDroid iptables chain");
    }
    private static final class Result {
        final int code; final String output;
        Result(int c, String o) { code = c; output = o; }
    }
    private Result run(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(Arrays.asList(iptables, "-t", "nat"));
        command.addAll(Arrays.asList(arguments));
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        long deadline = SystemClock.elapsedRealtime()+5000;
        try {
            for (;;) {
                while (in.available() > 0) {
                    int read = in.read(buffer); if (read < 0) break;
                    if (bytes.size() < 65536) bytes.write(buffer, 0, Math.min(read, 65536-bytes.size()));
                }
                try {
                    int code = process.exitValue();
                    // A command may exit between available() and exitValue().
                    // Drain its remaining output before inspecting rule ownership.
                    int read;
                    while ((read = in.read(buffer)) >= 0)
                        if (bytes.size() < 65536) bytes.write(buffer, 0, Math.min(read, 65536-bytes.size()));
                    return new Result(code, bytes.toString("UTF-8"));
                }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("iptables command timed out");
                    SystemClock.sleep(10);
                }
            }
        } finally { in.close(); process.destroy(); }
    }
    /** The App can clean tagged rules through its authorized root shell if the
     * hosting process was killed before RootService.onDestroy could run. */
    static boolean cleanupAfterBinderDeath() {
        try {
            String ip = binary()+" -t nat ";
            return Shell.cmd("while "+ip+"-C OUTPUT -p tcp -m comment --comment "+OWNER+" -j "+CHAIN+" 2>/dev/null; do "
                +ip+"-D OUTPUT -p tcp -m comment --comment "+OWNER+" -j "+CHAIN+" || exit 1; done\n"
                +"if "+ip+"-S "+CHAIN+" 2>/dev/null | grep -q '"+OWNER+"'; then "
                +ip+"-F "+CHAIN+" && "+ip+"-X "+CHAIN+"; fi").exec().isSuccess();
        } catch (IOException e) { return false; }
    }
}
