package com.google.android.exoplayer2.drm;

import java.util.Map;

public class UplynkDrmSession<T extends ExoMediaCrypto> implements DrmSession<T> {

    @State
    int state;
    byte[] sessionId;
    byte[] offlineLicenseKeySetId;
    byte[] schemeInitData;
    String schemeMimeType;
    T mediaCrypto;
    DrmSessionException lastException;
    private final ExoMediaDrm<T> mediaDrm;

    public UplynkDrmSession(ExoMediaDrm<T> exoMediaDrm) {
        state = STATE_RELEASED;
        mediaDrm = exoMediaDrm;
    }

    @Override
    @State
    public final int getState() {
        return state;
    }

    @Override
    public final T getMediaCrypto() {
        if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
            throw new IllegalStateException();
        }
        return mediaCrypto;
    }

    @Override
    public final DrmSessionException getError() {
        return state == STATE_ERROR ? lastException : null;
    }

    @Override
    public Map<String, String> queryKeyStatus() {
        // User may call this method rightfully even if state == STATE_ERROR. So only check if there is
        // a sessionId
        if (sessionId == null) {
            throw new IllegalStateException();
        }
        return mediaDrm.queryKeyStatus(sessionId);
    }

    @Override
    public byte[] getOfflineLicenseKeySetId() {
        return offlineLicenseKeySetId;
    }
}
