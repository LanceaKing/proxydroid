package org.proxydroid.engine;

import android.os.ParcelFileDescriptor;
import android.os.Process;

/** Loaded and called exclusively by the bound root host. */
final class NativeEngine implements AutoCloseable {
    static final int STOPPED = 0, STARTING = 1, RUNNING = 2, STOPPING = 3, FAILED = 4;
    static final int SOCKET_MARK = 0x505844;
    static {
        if (Process.myUid() == 0) System.loadLibrary("redsocks");
    }
    private long handle;
    NativeEngine() {
        if (Process.myUid() != 0) throw new IllegalStateException("Native engine requires root host");
        handle = nativeCreate();
        if (handle == 0) throw new OutOfMemoryError("native engine");
    }
    int start(ParcelFileDescriptor config, ParcelFileDescriptor trust, ParcelFileDescriptor cntlm, boolean validate) {
        return nativeStart(handle, config.getFd(), fd(trust), fd(cntlm), SOCKET_MARK, validate);
    }
    void stop() { if (handle != 0) nativeStop(handle); }
    int status() { return nativeStatus(handle); }
    String diagnostics() { return nativeDiagnostics(handle); }
    String detect(ParcelFileDescriptor config, ParcelFileDescriptor trust, ParcelFileDescriptor cntlm, String url) {
        return nativeDetect(handle, config.getFd(), fd(trust), fd(cntlm), SOCKET_MARK, url);
    }
    @Override public void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    private static int fd(ParcelFileDescriptor p) { return p == null ? -1 : p.getFd(); }
    private static native long nativeCreate();
    private static native int nativeStart(long h, int config, int trust, int cntlm, int mark, boolean validate);
    private static native void nativeStop(long h);
    private static native void nativeDestroy(long h);
    private static native int nativeStatus(long h);
    private static native String nativeDiagnostics(long h);
    static native String nativeBuildInfo();
    static native String nativeHashes(String user, String domain, String password);
    private static native String nativeDetect(long h, int config, int trust, int cntlm, int mark, String url);
}
