/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.DeniedByServerException;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.IntDef;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
import com.google.android.exoplayer2.drm.ExoMediaDrm.OnEventListener;
import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import static com.google.android.exoplayer2.drm.DrmSession.STATE_CLOSED;
import static com.google.android.exoplayer2.drm.DrmSession.STATE_OPENED;
import static com.google.android.exoplayer2.drm.DrmSession.STATE_OPENED_WITH_KEYS;
import static com.google.android.exoplayer2.drm.DrmSession.STATE_OPENING;

/**
 * A {@link DrmSessionManager} that supports playbacks using {@link MediaDrm}.
 */
@TargetApi(18)
public class UplynkDrmSessionManager<T extends ExoMediaCrypto> implements DrmSessionManager<T> {

    /**
     * Listener of {@link UplynkDrmSessionManager} events.
     */
    public interface EventListener {

        /**
         * Called each time keys are loaded.
         */
        void onDrmKeysLoaded();

        /**
         * Called when a drm error occurs.
         *
         * @param e The corresponding exception.
         */
        void onDrmSessionManagerError(Exception e);

        /**
         * Called each time offline keys are restored.
         */
        void onDrmKeysRestored();

        /**
         * Called each time offline keys are removed.
         */
        void onDrmKeysRemoved();

    }

    /**
     * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
     */
    public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";

    private static final String TAG = "UplynkDrmSessionMgr";

    private static final int MAX_LICENSE_DURATION_TO_RENEW = 60;

    private final Handler eventHandler;
    private final EventListener eventListener;
    private final ExoMediaDrm<T> mediaDrm;
    private final HashMap<String, String> optionalKeyRequestParameters;

    /* package */ final MediaDrmCallback callback;
    /* package */ final UUID uuid;

    /* package */ MediaDrmHandler mediaDrmHandler;


    private Looper playbackLooper;
    private ArrayList<UplynkDrmSession<T>> drmRequests;

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
     * expired sets state to {@link com.google.android.exoplayer2.drm.DrmSession.State#STATE_ERROR}.
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

    private boolean provisioningInProgress;
    private int mode;
    // For the next request (via setMode())
    private byte[] nextOfflineLicenseKeySetId;
    // For the current request
    private byte[] schemeInitData;
    private String schemeMimeType;

    /**
     * Instantiates a new instance using the Widevine scheme.
     *
     * @param callback                     Performs key and provisioning requests.
     * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
     *                                     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
     * @param eventHandler                 A handler to use when delivering events to {@code eventListener}. May be
     *                                     null if delivery of events is not required.
     * @param eventListener                A listener of events. May be null if delivery of events is not required.
     * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
     */
    public static UplynkDrmSessionManager<FrameworkMediaCrypto> newWidevineInstance(
            MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
            Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
        return newFrameworkInstance(C.WIDEVINE_UUID, callback, optionalKeyRequestParameters,
                eventHandler, eventListener);
    }

    /**
     * Instantiates a new instance using the PlayReady scheme.
     * <p>
     * Note that PlayReady is unsupported by most Android devices, with the exception of Android TV
     * devices, which do provide support.
     *
     * @param callback      Performs key and provisioning requests.
     * @param customData    Optional custom data to include in requests generated by the instance.
     * @param eventHandler  A handler to use when delivering events to {@code eventListener}. May be
     *                      null if delivery of events is not required.
     * @param eventListener A listener of events. May be null if delivery of events is not required.
     * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
     */
    public static UplynkDrmSessionManager<FrameworkMediaCrypto> newPlayReadyInstance(
            MediaDrmCallback callback, String customData, Handler eventHandler,
            EventListener eventListener) throws UnsupportedDrmException {
        HashMap<String, String> optionalKeyRequestParameters;
        if (!TextUtils.isEmpty(customData)) {
            optionalKeyRequestParameters = new HashMap<>();
            optionalKeyRequestParameters.put(PLAYREADY_CUSTOM_DATA_KEY, customData);
        } else {
            optionalKeyRequestParameters = null;
        }
        return newFrameworkInstance(C.PLAYREADY_UUID, callback, optionalKeyRequestParameters,
                eventHandler, eventListener);
    }

    /**
     * Instantiates a new instance.
     *
     * @param uuid                         The UUID of the drm scheme.
     * @param callback                     Performs key and provisioning requests.
     * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
     *                                     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
     * @param eventHandler                 A handler to use when delivering events to {@code eventListener}. May be
     *                                     null if delivery of events is not required.
     * @param eventListener                A listener of events. May be null if delivery of events is not required.
     * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
     */
    public static UplynkDrmSessionManager<FrameworkMediaCrypto> newFrameworkInstance(
            UUID uuid, MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
            Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
        return new UplynkDrmSessionManager<>(uuid, FrameworkMediaDrm.newInstance(uuid), callback,
                optionalKeyRequestParameters, eventHandler, eventListener);
    }

    /**
     * @param uuid                         The UUID of the drm scheme.
     * @param mediaDrm                     An underlying {@link ExoMediaDrm} for use by the manager.
     * @param callback                     Performs key and provisioning requests.
     * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
     *                                     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
     * @param eventHandler                 A handler to use when delivering events to {@code eventListener}. May be
     *                                     null if delivery of events is not required.
     * @param eventListener                A listener of events. May be null if delivery of events is not required.
     */
    public UplynkDrmSessionManager(UUID uuid, ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback,
                                   HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
                                   EventListener eventListener) {
        this.uuid = uuid;
        this.mediaDrm = mediaDrm;
        this.callback = callback;
        this.optionalKeyRequestParameters = optionalKeyRequestParameters;
        this.eventHandler = eventHandler;
        this.eventListener = eventListener;
        mediaDrm.setPropertyString("sessionSharing", "enable");
        mediaDrm.setOnEventListener(new MediaDrmEventListener());
        drmRequests = new ArrayList<>();
        provisioningInProgress = false;
        mode = MODE_PLAYBACK;
        nextOfflineLicenseKeySetId = null;
        schemeInitData = null;
        schemeMimeType = "";

    }

    /**
     * Provides access to {@link MediaDrm#getPropertyString(String)}.
     * <p>
     * This method may be called when the manager is in any state.
     *
     * @param key The key to request.
     * @return The retrieved property.
     */
    public final String getPropertyString(String key) {
        return mediaDrm.getPropertyString(key);
    }

    /**
     * Provides access to {@link MediaDrm#setPropertyString(String, String)}.
     * <p>
     * This method may be called when the manager is in any state.
     *
     * @param key   The property to write.
     * @param value The value to write.
     */
    public final void setPropertyString(String key, String value) {
        mediaDrm.setPropertyString(key, value);
    }

    /**
     * Provides access to {@link MediaDrm#getPropertyByteArray(String)}.
     * <p>
     * This method may be called when the manager is in any state.
     *
     * @param key The key to request.
     * @return The retrieved property.
     */
    public final byte[] getPropertyByteArray(String key) {
        return mediaDrm.getPropertyByteArray(key);
    }

    /**
     * Provides access to {@link MediaDrm#setPropertyByteArray(String, byte[])}.
     * <p>
     * This method may be called when the manager is in any state.
     *
     * @param key   The property to write.
     * @param value The value to write.
     */
    public final void setPropertyByteArray(String key, byte[] value) {
        mediaDrm.setPropertyByteArray(key, value);
    }

    /**
     * Sets the mode, which determines the role of sessions acquired from the instance. This must be
     * called before {@link #acquireSession(Looper, DrmInitData)} is called.
     * <p>
     * <p>By default, the mode is {@link #MODE_PLAYBACK} and a streaming license is requested when
     * required.
     * <p>
     * <p>{@code mode} must be one of these:
     * <li>{@link #MODE_PLAYBACK}: If {@code offlineLicenseKeySetId} is null, a streaming license is
     * requested otherwise the offline license is restored.
     * <li>{@link #MODE_QUERY}: {@code offlineLicenseKeySetId} can not be null. The offline license
     * is restored.
     * <li>{@link #MODE_DOWNLOAD}: If {@code offlineLicenseKeySetId} is null, an offline license is
     * requested otherwise the offline license is renewed.
     * <li>{@link #MODE_RELEASE}: {@code offlineLicenseKeySetId} can not be null. The offline license
     * is released.
     *
     * @param modeValue                   The mode to be set.
     * @param offlineLicenseKeySetId The key set id of the license to be used with the given mode.
     */
    public void setMode(@Mode int modeValue, byte[] offlineLicenseKeySetId) {
        if (mode == MODE_QUERY || mode == MODE_RELEASE) {
            Assertions.checkNotNull(offlineLicenseKeySetId);
        }
        mode = modeValue;
        nextOfflineLicenseKeySetId = offlineLicenseKeySetId;
    }

    // DrmSessionManager implementation.

    @Override
    public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
        Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
        if (this.playbackLooper == null) {
            this.playbackLooper = playbackLooper;
            mediaDrmHandler = new MediaDrmHandler(playbackLooper);
        }

        UplynkDrmSession<T> newRequest = new UplynkDrmSession<>(mediaDrm);
        if (nextOfflineLicenseKeySetId != null) {
            newRequest.offlineLicenseKeySetId = nextOfflineLicenseKeySetId;
            // FIXME reset nextOfflineLicenseKeySetId?
            //nextOfflineLicenseKeySetId = null;
        }
        drmRequests.add(newRequest);

        if (newRequest.offlineLicenseKeySetId == null) {
            SchemeData schemeData = drmInitData.get(uuid);
            if (schemeData == null) {
                onError(newRequest, new IllegalStateException("Media does not support uuid: " + uuid));
                return newRequest;
            }
            schemeInitData = schemeData.data;
            schemeMimeType = schemeData.mimeType;
            if (Util.SDK_INT < 21) {
                // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
                byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeInitData, C.WIDEVINE_UUID);
                if (psshData != null) {
                    schemeInitData = psshData;
                }
                // else -- Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.

            }
        }
        newRequest.state = STATE_OPENING;

        openInternal(newRequest, true);
        return newRequest;
    }

    @Override
    public void releaseSession(DrmSession<T> session) {
        UplynkDrmSession ulSession = (UplynkDrmSession) session;
        drmRequests.remove(session);
        if (drmRequests.isEmpty()) {
            mediaDrmHandler.removeCallbacksAndMessages(null);
        }

        ulSession.state = STATE_CLOSED;
        ulSession.mediaCrypto = null;
        ulSession.lastException = null;
        if (ulSession.sessionId != null) {
            mediaDrm.closeSession(ulSession.sessionId);
            ulSession.sessionId = null;
        }

        schemeInitData = null;
        schemeMimeType = null;
        provisioningInProgress = false;
    }

    // Internal methods.

    private void openInternal(UplynkDrmSession session, boolean allowProvisioning) {
        try {
            session.sessionId = mediaDrm.openSession();
            session.mediaCrypto = mediaDrm.createMediaCrypto(uuid, session.sessionId);
            session.state = STATE_OPENED;
            doLicense(session);
        } catch (NotProvisionedException e) {
            if (allowProvisioning) {
                postProvisionRequest(session);
            } else {
                onError(session, e);
            }
        } catch (Exception e) {
            onError(session, e);
        }
    }

    private void postProvisionRequest(UplynkDrmSession session) {
        if (provisioningInProgress) {
            return;
        }

        Log.i(TAG, "Provisioning being requested");
        provisioningInProgress = true;
        ProvisionRequest request = mediaDrm.getProvisionRequest();
        try {
            byte[] response = callback.executeProvisionRequest(uuid, request);
            onProvisionResponse(session, response);
        } catch (Exception e) {
            onError(session, e);
        }
    }

    private void onProvisionResponse(UplynkDrmSession session, byte[] response) {
        provisioningInProgress = false;
        if (session.state != STATE_OPENING && session.state != STATE_OPENED && session.state != STATE_OPENED_WITH_KEYS) {
            // This event is stale.
            return;
        }

        try {
            mediaDrm.provideProvisionResponse(response);
            Log.i(TAG, "Provisioning done!");
            if (session.state == STATE_OPENING) {
                openInternal(session, false);
            } else {
                doLicense(session);
            }
        }
        catch (DeniedByServerException e) {
            onError(session, e);
        }
    }

    private void doLicense(UplynkDrmSession session) {
        switch (mode) {
            case MODE_PLAYBACK:
            case MODE_QUERY:
                if (session.offlineLicenseKeySetId == null) {
                    postKeyRequest(session, MediaDrm.KEY_TYPE_STREAMING);
                } else {
                    if (restoreKeys()) {
                        long licenseDurationRemainingSec = getLicenseDurationRemainingSec(session);
                        if (mode == MODE_PLAYBACK
                                && licenseDurationRemainingSec <= MAX_LICENSE_DURATION_TO_RENEW) {
                            Log.d(TAG, "Offline license has expired or will expire soon. "
                                    + "Remaining seconds: " + licenseDurationRemainingSec);
                            postKeyRequest(session, MediaDrm.KEY_TYPE_OFFLINE);
                        } else if (licenseDurationRemainingSec <= 0) {
                            onError(session, new KeysExpiredException());
                        } else {
                            session.state = STATE_OPENED_WITH_KEYS;
                            if (eventHandler != null && eventListener != null) {
                                eventHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        eventListener.onDrmKeysRestored();
                                    }
                                });
                            }
                        }
                    }
                }
                break;
            case MODE_DOWNLOAD:
                if (session.offlineLicenseKeySetId == null) {
                    postKeyRequest(session, MediaDrm.KEY_TYPE_OFFLINE);
                } else {
                    // Renew
                    if (restoreKeys()) {
                        postKeyRequest(session, MediaDrm.KEY_TYPE_OFFLINE);
                    }
                }
                break;
            case MODE_RELEASE:
                if (restoreKeys()) {
                    postKeyRequest(session, MediaDrm.KEY_TYPE_RELEASE);
                }
                break;
        }
    }

    private boolean restoreKeys() {
        boolean retVal = false;
        for (UplynkDrmSession session : drmRequests) {
            try {
                mediaDrm.restoreKeys(session.sessionId, session.offlineLicenseKeySetId);
                retVal = true;
            } catch (Exception e) {
                Log.e(TAG, "Error trying to restore Widevine keys.", e);
                onError(session, e);
            }
        }
        return retVal;
    }

    private long getLicenseDurationRemainingSec(UplynkDrmSession session) {
        if (!C.WIDEVINE_UUID.equals(uuid)) {
            return Long.MAX_VALUE;
        }
        Pair<Long, Long> pair = WidevineUtil.getLicenseDurationRemainingSec(session);
        return Math.min(pair.first, pair.second);
    }

    private void postKeyRequest(UplynkDrmSession session, int keyType) {
        try {
            KeyRequest keyRequest;
            if (keyType == MediaDrm.KEY_TYPE_RELEASE)
                keyRequest = mediaDrm.getKeyRequest(session.offlineLicenseKeySetId, schemeInitData, schemeMimeType, keyType,
                        optionalKeyRequestParameters);
            else
                keyRequest = mediaDrm.getKeyRequest(session.sessionId, schemeInitData, schemeMimeType, keyType,
                        optionalKeyRequestParameters);
            Log.d(TAG, "KeyRequest " + schemeMimeType + " Session: " + sessionIdToString(session.sessionId));
            Object response = callback.executeKeyRequest(uuid, keyRequest);
            onKeyResponse(session, response);
            schemeInitData = null;
            schemeMimeType = null;
        } catch (Exception e) {
            onKeysError(session, e);
        }
    }

    private String sessionIdToString(byte[] sessionId)
    {
        String stringVal = "0x";
        for (byte b : sessionId)
        {
            stringVal += String.format("%X ", b);
        }
        return stringVal;
    }

    private void onKeyResponse(UplynkDrmSession session, Object response) {
        if (session == null || (session.state != STATE_OPENED && session.state != STATE_OPENED_WITH_KEYS)) {
            // This event is stale.
            return;
        }

        if (response instanceof Exception) {
            onKeysError(session, (Exception) response);
            return;
        }

        try {
            if (mode == MODE_RELEASE) {
                mediaDrm.provideKeyResponse(session.offlineLicenseKeySetId, (byte[]) response);
                if (eventHandler != null && eventListener != null) {
                    eventHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            eventListener.onDrmKeysRemoved();
                        }
                    });
                }
            } else {
                Log.d(TAG, "KeyResponse " + schemeMimeType + " Session: " + sessionIdToString(session.sessionId));
                byte[] keySetId;
                keySetId = mediaDrm.provideKeyResponse(session.sessionId, (byte[]) response);
                if ((mode == MODE_DOWNLOAD || (mode == MODE_PLAYBACK && session.offlineLicenseKeySetId != null))
                        && keySetId != null && keySetId.length != 0) {
                    session.offlineLicenseKeySetId = keySetId;
                }
                session.state = STATE_OPENED_WITH_KEYS;
                if (eventHandler != null && eventListener != null) {
                    eventHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            eventListener.onDrmKeysLoaded();
                        }
                    });
                }
            }
        } catch (Exception e) {
            onKeysError(session, e);
        }
    }

    private void onKeysError(UplynkDrmSession session, Exception e) {
        if (e instanceof NotProvisionedException) {
            postProvisionRequest(session);
        } else {
            onError(session, e);
        }
    }

    private void onError(UplynkDrmSession session, final Exception e) {
        // TODO remove 2 lines:
        Log.e(TAG, "onError stacktrace:");
        e.printStackTrace();

        session.lastException = new UplynkDrmSession.DrmSessionException(e);
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable() {
                @Override
                public void run() {
                    eventListener.onDrmSessionManagerError(e);
                }
            });
        }
        if (session.state != STATE_OPENED_WITH_KEYS) {
            session.state = UplynkDrmSession.STATE_ERROR;
        }
    }

    @SuppressLint("HandlerLeak")
    private class MediaDrmHandler extends Handler {

        public MediaDrmHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            for (UplynkDrmSession session : drmRequests) {
                if (session.state == STATE_OPENED || session.state == STATE_OPENED_WITH_KEYS) {
                    switch (msg.what) {
                        case MediaDrm.EVENT_KEY_REQUIRED:
                            doLicense(session);
                            break;
                        case MediaDrm.EVENT_KEY_EXPIRED:
                            // When an already expired key is loaded MediaDrm sends this event immediately. Ignore
                            // this event if the state isn't STATE_OPENED_WITH_KEYS yet which means we're still
                            // waiting for key response.
                            if (session.state == STATE_OPENED_WITH_KEYS) {
                                session.state = STATE_OPENED;
                                onError(session, new KeysExpiredException());
                            }
                            break;
                        // MediaDrm.EVENT_PROVISION_REQUIRED is deprecated. Provisioning is signaled by NotHandledException instead
                        default:
                            break;
                    }
                }
            }
        }
    }

    private class MediaDrmEventListener implements OnEventListener<T> {

        @SuppressWarnings("deprecation")
        @Override
        public void onEvent(ExoMediaDrm<? extends T> md, byte[] sessionId, int event, int extra,
                            byte[] data) {
            if (mode != MODE_PLAYBACK) return;
            mediaDrmHandler.sendEmptyMessage(event);
        }

    }
}
