package org.proxydroid.engine;
oneway interface IEngineCallback {
    void onStateChanged(int state, String diagnostic);
}
