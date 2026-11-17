package org.proxydroid.engine;

import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Upgrade-only cleanup. Never signal a pid without verifying its executable. */
final class LegacyEngineCleanup {
    static void clean(String dataDir) throws IOException {
        File[] directories = { new File(dataDir), new File(dataDir, "files") };
        for (File directory : directories) {
            for (String name : new String[]{"redsocks", "gost", "cntlm"}) {
                File binary = new File(directory, name).getCanonicalFile();
                File pidfile = new File(directory, name+".pid");
                if (pidfile.isFile()) {
                    String text = read(pidfile).trim();
                    if (text.matches("[1-9][0-9]{0,8}")) {
                        int pid = Integer.parseInt(text);
                        if (owned(pid, binary)) {
                            Process.sendSignal(pid, 15);
                            SystemClock.sleep(100);
                            if (owned(pid, binary)) Process.sendSignal(pid, 9);
                        }
                    }
                    pidfile.delete();
                }
                binary.delete();
            }
            for (String name : new String[]{"proxy.sh", "gost.sh"}) new File(directory, name).delete();
        }
    }
    private static boolean owned(int pid, File binary) {
        if (pid <= 1 || pid == Process.myPid()) return false;
        try { return new File("/proc/"+pid+"/exe").getCanonicalFile().equals(binary); }
        catch (IOException e) { return false; }
    }
    private static String read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[64]; int count = in.read(bytes);
            return count < 0 ? "" : new String(bytes, 0, count, "US-ASCII");
        }
    }
}
