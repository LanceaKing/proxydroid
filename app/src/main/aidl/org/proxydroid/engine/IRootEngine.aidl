package org.proxydroid.engine;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.proxydroid.engine.IEngineCallback;
interface IRootEngine {
    int start(in Bundle rules, in ParcelFileDescriptor config, in ParcelFileDescriptor trust, in ParcelFileDescriptor cntlm);
    void stop();
    int getStatus();
    String getDiagnostics();
    String getBuildInfo();
    int validate(in ParcelFileDescriptor config, in ParcelFileDescriptor trust, in ParcelFileDescriptor cntlm);
    String passwordHashes(String user, String domain, String password);
    String detectAuthentication(in ParcelFileDescriptor config, in ParcelFileDescriptor trust, in ParcelFileDescriptor cntlm, String url);
    void registerCallback(IEngineCallback callback);
    void unregisterCallback(IEngineCallback callback);
}
