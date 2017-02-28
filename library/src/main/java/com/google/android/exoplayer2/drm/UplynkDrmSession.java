package com.google.android.exoplayer2.drm;

import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/**
 * Created by cspencer on 2/27/17.
 */

public class UplynkDrmSession<T extends ExoMediaCrypto> implements DrmSession<T> {

    /**
     * Determines the action to be done after a session acquired.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MODE_PLAYBACK, MODE_QUERY, MODE_DOWNLOAD, MODE_RELEASE})
    public @interface Mode {
    }

    /**
     * Loads and refreshes (if necessary) a license for playback. Supports streaming and offline
     * licenses.
     */
    public static final int MODE_PLAYBACK = 0;
    /**
     * Restores an offline license to allow its status to be queried. If the offline license is
     * expired sets state to {@link #STATE_ERROR}.
     */
    public static final int MODE_QUERY = 1;
    /**
     * Downloads an offline license or renews an existing one.
     */
    public static final int MODE_DOWNLOAD = 2;
    /**
     * Releases an existing offline license.
     */
    public static final int MODE_RELEASE = 3;

    HandlerThread requestHandlerThread;
    Handler postRequestHandler;
    int mode;
    boolean provisioningInProgress;
    @State
    int state;
    byte[] schemeInitData;
    String schemeMimeType;
    byte[] sessionId;
    byte[] offlineLicenseKeySetId;
    T mediaCrypto;
    DrmSessionException lastException;
    private final ExoMediaDrm<T> mediaDrm;

    public UplynkDrmSession(ExoMediaDrm<T> exoMediaDrm) {
        state = STATE_CLOSED;
        mode = MODE_PLAYBACK;
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
    public boolean requiresSecureDecoderComponent(String mimeType) {
        if (state != STATE_OPENED && state != STATE_OPENED_WITH_KEYS) {
            throw new IllegalStateException();
        }
        return mediaCrypto.requiresSecureDecoderComponent(mimeType);
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
