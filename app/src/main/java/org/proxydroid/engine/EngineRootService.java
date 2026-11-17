package org.proxydroid.engine;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import com.topjohnwu.superuser.ipc.RootService;
import java.io.IOException;

/** Regular bound mode: App death destroys the host and removes its rules. */
public final class EngineRootService extends RootService {
    private final Object lock = new Object();
    private final RemoteCallbackList<IEngineCallback> callbacks = new RemoteCallbackList<>();
    private NativeEngine engine;
    private RedirectRules rules;
    private String error = "";
    private int clientUid;
    private boolean watching;
    private final Handler monitor = new Handler(Looper.getMainLooper());
    private final Runnable checkEngine = new Runnable() {
        @Override public void run() {
            synchronized (lock) {
                if (watching && engine.status() != NativeEngine.RUNNING) {
                    watching = false;
                    error = "Native engine stopped unexpectedly";
                    try { stopEngine(); } catch (IOException e) { error = e.getMessage(); }
                    broadcast(NativeEngine.FAILED);
                }
            }
            monitor.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate() {
        clientUid = getApplicationInfo().uid;
        engine = new NativeEngine();
        monitor.postDelayed(checkEngine, 1000);
    }
    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid != clientUid && uid != 0) throw new SecurityException("Unexpected engine client");
    }
    private void broadcast() { broadcast(engine.status()); }
    private void broadcast(int state) {
        int n = callbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; ++i) try { callbacks.getBroadcastItem(i).onStateChanged(state, diagnostic()); }
                catch (RemoteException ignored) { }
        } finally { callbacks.finishBroadcast(); }
    }
    private String diagnostic() { return error.isEmpty() ? engine.diagnostics() : error; }
    private void stopEngine() throws IOException {
        watching = false;
        if (rules == null) rules = new RedirectRules();
        rules.remove(); // never stop a listener while a redirect still points to it
        engine.stop();
    }
    private final IRootEngine.Stub binder = new IRootEngine.Stub() {
        @Override public int start(Bundle options, ParcelFileDescriptor config, ParcelFileDescriptor trust, ParcelFileDescriptor cntlm) {
            checkCaller();
            synchronized (lock) {
                error = "";
                try {
                    stopEngine();
                    LegacyEngineCleanup.clean(getApplicationInfo().dataDir);
                    if (config == null || engine.start(config, trust, cntlm, false) != 0) return -1;
                    rules.install(options);
                    watching = true;
                    return 0;
                } catch (Exception e) {
                    error = e instanceof IOException ? e.getMessage() : "Root engine initialization failed";
                    try { stopEngine(); } catch (IOException ignored) { }
                    return -1;
                } finally { close(config); close(trust); close(cntlm); broadcast(); }
            }
        }
        @Override public void stop() {
            checkCaller(); synchronized (lock) {
                try { stopEngine(); error = ""; }
                catch (IOException e) { error = e.getMessage(); }
                broadcast();
            }
        }
        @Override public int getStatus() { checkCaller(); synchronized (lock) { return engine.status(); } }
        @Override public String getDiagnostics() { checkCaller(); synchronized (lock) { return diagnostic(); } }
        @Override public String getBuildInfo() { checkCaller(); return NativeEngine.nativeBuildInfo(); }
        @Override public int validate(ParcelFileDescriptor c, ParcelFileDescriptor t, ParcelFileDescriptor n) {
            checkCaller(); synchronized (lock) {
                try { return c == null ? -1 : engine.start(c, t, n, true); }
                finally { close(c); close(t); close(n); }
            }
        }
        @Override public String passwordHashes(String user, String domain, String password) {
            checkCaller(); synchronized (lock) { return NativeEngine.nativeHashes(user, domain, password); }
        }
        @Override public String detectAuthentication(ParcelFileDescriptor c, ParcelFileDescriptor t, ParcelFileDescriptor n, String url) {
            checkCaller(); synchronized (lock) {
                try { return c == null ? null : engine.detect(c, t, n, url); }
                finally { close(c); close(t); close(n); }
            }
        }
        @Override public void registerCallback(IEngineCallback callback) {
            checkCaller(); synchronized (lock) { if (callback != null) { callbacks.register(callback); broadcast(); } }
        }
        @Override public void unregisterCallback(IEngineCallback callback) { checkCaller(); if (callback != null) callbacks.unregister(callback); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        monitor.removeCallbacks(checkEngine);
        synchronized (lock) {
            try { stopEngine(); } catch (IOException e) { RedirectRules.cleanupAfterBinderDeath(); }
            engine.close(); callbacks.kill();
        }
    }
    private static void close(ParcelFileDescriptor fd) { if (fd != null) try { fd.close(); } catch (IOException ignored) { } }
    public static boolean cleanupDisconnectedHost() { return RedirectRules.cleanupAfterBinderDeath(); }
}
