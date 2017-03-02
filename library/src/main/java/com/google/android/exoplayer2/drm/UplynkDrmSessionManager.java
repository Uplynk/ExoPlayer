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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

  private static final int MSG_PROVISION = 0;
  private static final int MSG_KEYS = 1;

  private static final int MAX_LICENSE_DURATION_TO_RENEW = 60;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final ExoMediaDrm<T> mediaDrm;
  private final HashMap<String, String> optionalKeyRequestParameters;

  /* package */ final MediaDrmCallback callback;
  /* package */ final UUID uuid;

  /* package */ MediaDrmHandler mediaDrmHandler;
  /* package */ PostResponseHandler postResponseHandler;


  private Looper playbackLooper;
  private ArrayList<UplynkDrmSession<T>> drmRequests;
  private AtomicBoolean provisioningInProgress;

  /**
   * Instantiates a new instance using the Widevine scheme.
   *
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
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
   * @param callback Performs key and provisioning requests.
   * @param customData Optional custom data to include in requests generated by the instance.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
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
   * @param uuid The UUID of the drm scheme.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static UplynkDrmSessionManager<FrameworkMediaCrypto> newFrameworkInstance(
      UUID uuid, MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
      Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
    return new UplynkDrmSessionManager<>(uuid, FrameworkMediaDrm.newInstance(uuid), callback,
        optionalKeyRequestParameters, eventHandler, eventListener);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param mediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
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
    provisioningInProgress = new AtomicBoolean(false);
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
    synchronized (mediaDrm) {
      return mediaDrm.getPropertyString(key);
    }
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyString(String, String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyString(String key, String value) {
    synchronized (mediaDrm) {
      mediaDrm.setPropertyString(key, value);
    }
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
    synchronized (mediaDrm) {
      return mediaDrm.getPropertyByteArray(key);
    }
  }

  /**
   * Provides access to {@link MediaDrm#setPropertyByteArray(String, byte[])}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyByteArray(String key, byte[] value) {
    synchronized (mediaDrm) {
      mediaDrm.setPropertyByteArray(key, value);
    }
  }

//  /**
//   * Sets the mode, which determines the role of sessions acquired from the instance. This must be
//   * called before {@link #acquireSession(Looper, DrmInitData)} is called.
//   *
//   * <p>By default, the mode is {@link #MODE_PLAYBACK} and a streaming license is requested when
//   * required.
//   *
//   * <p>{@code mode} must be one of these:
//   * <li>{@link #MODE_PLAYBACK}: If {@code offlineLicenseKeySetId} is null, a streaming license is
//   *     requested otherwise the offline license is restored.
//   * <li>{@link #MODE_QUERY}: {@code offlineLicenseKeySetId} can not be null. The offline license
//   *     is restored.
//   * <li>{@link #MODE_DOWNLOAD}: If {@code offlineLicenseKeySetId} is null, an offline license is
//   *     requested otherwise the offline license is renewed.
//   * <li>{@link #MODE_RELEASE}: {@code offlineLicenseKeySetId} can not be null. The offline license
//   *     is released.
//   *
//   * @param mode The mode to be set.
//   * @param offlineLicenseKeySetId The key set id of the license to be used with the given mode.
//   */
//  public void setMode(@Mode int mode, byte[] offlineLicenseKeySetId) {
//    Assertions.checkState(openCount == 0);
//    if (mode == MODE_QUERY || mode == MODE_RELEASE) {
//      Assertions.checkNotNull(offlineLicenseKeySetId);
//    }
//    this.mode = mode;
//    this.offlineLicenseKeySetId = offlineLicenseKeySetId;
//  }

  // DrmSessionManager implementation.

  @Override
  public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    if (this.playbackLooper == null) {
      this.playbackLooper = playbackLooper;
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
      postResponseHandler = new PostResponseHandler(playbackLooper);
    }

    UplynkDrmSession<T> newRequest = new UplynkDrmSession<>(mediaDrm);
    newRequest.requestHandlerThread = new HandlerThread("DrmRequestHandler");
    newRequest.requestHandlerThread.start();
    newRequest.postRequestHandler = new PostRequestHandler(newRequest.requestHandlerThread.getLooper());
    synchronized (drmRequests) {
      drmRequests.add(newRequest);
    }

    if (newRequest.offlineLicenseKeySetId == null) {
      SchemeData schemeData = drmInitData.get(uuid);
      if (schemeData == null) {
        onError(newRequest, new IllegalStateException("Media does not support uuid: " + uuid));
        return newRequest;
      }
      newRequest.schemeInitData = schemeData.data;
      newRequest.schemeMimeType = schemeData.mimeType;
      if (Util.SDK_INT < 21) {
        // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
        byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(newRequest.schemeInitData, C.WIDEVINE_UUID);
        if (psshData != null) {
          newRequest.schemeInitData = psshData;
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
    synchronized (drmRequests) {
      drmRequests.remove(session);
      if (drmRequests.isEmpty()) {
        mediaDrmHandler.removeCallbacksAndMessages(null);
        postResponseHandler.removeCallbacksAndMessages(null);
      }
    }

    ulSession.state = STATE_CLOSED;
    ulSession.provisioningInProgress = false;
    ulSession.postRequestHandler.removeCallbacksAndMessages(null);
    ulSession.postRequestHandler = null;
    ulSession.requestHandlerThread.quit();
    ulSession.requestHandlerThread = null;
    ulSession.schemeInitData = null;
    ulSession.schemeMimeType = null;
    ulSession.mediaCrypto = null;
    ulSession.lastException = null;
    if (ulSession.sessionId != null) {
      synchronized (mediaDrm) {
        mediaDrm.closeSession(ulSession.sessionId);
      }
      ulSession.sessionId = null;
    }
  }

  // Internal methods.

  private void openInternal(UplynkDrmSession session, boolean allowProvisioning) {
    try {
      synchronized (mediaDrm) {
        session.sessionId = mediaDrm.openSession();
        session.mediaCrypto = mediaDrm.createMediaCrypto(uuid, session.sessionId);
      }
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
    session.provisioningInProgress = true;
    if (!provisioningInProgress.get()) {
      Log.e(TAG, "Provisioning being requested");
      provisioningInProgress.set(true);
      ProvisionRequest request;
      synchronized (mediaDrm) {
        request = mediaDrm.getProvisionRequest();
      }
      session.postRequestHandler.obtainMessage(MSG_PROVISION, request).sendToTarget();
    }
  }

  private void onProvisionResponse(Object response) {
    synchronized (drmRequests) {
      for (UplynkDrmSession session : drmRequests) {
        if (session.provisioningInProgress) {
          session.provisioningInProgress = false;

          if (session.state != STATE_OPENING && session.state != STATE_OPENED && session.state != STATE_OPENED_WITH_KEYS) {
            // This event is stale.
            return;
          }

          if (response instanceof Exception) {
            onError(session, (Exception) response);
            //return;
          }

          try {
            synchronized (mediaDrm) {
              mediaDrm.provideProvisionResponse((byte[]) response);
            }
            if (session.state == STATE_OPENING) {
              openInternal(session, false);
            } else {
              doLicense(session);
            }
          } catch (DeniedByServerException e) {
            onError(session, e);
          }
        }
      }
    }
    provisioningInProgress.set(false);
  }

  private void doLicense(UplynkDrmSession session) {
    switch (session.mode) {
      case UplynkDrmSession.MODE_PLAYBACK:
      case UplynkDrmSession.MODE_QUERY:
        if (session.offlineLicenseKeySetId == null) {
          postKeyRequest(session, MediaDrm.KEY_TYPE_STREAMING);
        } else {
          if (restoreKeys()) {
            long licenseDurationRemainingSec = getLicenseDurationRemainingSec(session);
            if (session.mode == UplynkDrmSession.MODE_PLAYBACK
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
      case UplynkDrmSession.MODE_DOWNLOAD:
        if (session.offlineLicenseKeySetId == null) {
          postKeyRequest(session, MediaDrm.KEY_TYPE_OFFLINE);
        } else {
          // Renew
          if (restoreKeys()) {
            postKeyRequest(session, MediaDrm.KEY_TYPE_OFFLINE);
          }
        }
        break;
      case UplynkDrmSession.MODE_RELEASE:
        if (restoreKeys()) {
          postKeyRequest(session, MediaDrm.KEY_TYPE_RELEASE);
        }
        break;
    }
  }

  private boolean restoreKeys() {
    boolean retVal = false;
    synchronized ( drmRequests) {
      for (UplynkDrmSession session : drmRequests) {
        try {
          synchronized (mediaDrm) {
            mediaDrm.restoreKeys(session.sessionId, session.offlineLicenseKeySetId);
          }
          retVal = true;
        } catch (Exception e) {
          Log.e(TAG, "Error trying to restore Widevine keys.", e);
          onError(session, e);
        }
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
      synchronized (mediaDrm) {
        if (keyType == MediaDrm.KEY_TYPE_RELEASE)
          keyRequest = mediaDrm.getKeyRequest(session.offlineLicenseKeySetId, session.schemeInitData, session.schemeMimeType, keyType,
                  optionalKeyRequestParameters);
        else
          keyRequest = mediaDrm.getKeyRequest(session.sessionId, session.schemeInitData, session.schemeMimeType, keyType,
                  optionalKeyRequestParameters);
      }
      Log.d(TAG, "KeyRequest " + session.schemeMimeType);
      session.postRequestHandler.obtainMessage(MSG_KEYS, keyRequest).sendToTarget();
    } catch (Exception e) {
      onKeysError(session, e);
    }
  }

  private void onKeyResponse(Object response) {
    UplynkDrmSession session = null;
    synchronized (drmRequests) {
      for (UplynkDrmSession t : drmRequests) {
        if (t.state == STATE_OPENED || (t.state == STATE_OPENED_WITH_KEYS && t.mode == UplynkDrmSession.MODE_RELEASE)) {
          session = t;
          break;
        }
      }
    }
    if (session == null) return;

    if (response instanceof Exception) {
      onKeysError(session, (Exception) response);
      return;
    }

    try {
      if (session.mode == UplynkDrmSession.MODE_RELEASE) {
        synchronized (mediaDrm) {
          mediaDrm.provideKeyResponse(session.offlineLicenseKeySetId, (byte[]) response);
        }
        if (eventHandler != null && eventListener != null) {
          eventHandler.post(new Runnable() {
            @Override
            public void run() {
              eventListener.onDrmKeysRemoved();
            }
          });
        }
      } else {
        Log.d(TAG, "KeyResponse " + session.schemeMimeType);
        byte[] keySetId;
        synchronized (mediaDrm) {
          keySetId = mediaDrm.provideKeyResponse(session.sessionId, (byte[]) response);
        }
        if ((session.mode == UplynkDrmSession.MODE_DOWNLOAD || (session.mode == UplynkDrmSession.MODE_PLAYBACK && session.offlineLicenseKeySetId != null))
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

    @SuppressWarnings("deprecation")
    @Override
    public void handleMessage(Message msg) {
      UplynkDrmSession session = null;
      synchronized (drmRequests) {
        for (UplynkDrmSession t : drmRequests) {
          if (t.state == STATE_OPENED || t.state == STATE_OPENED_WITH_KEYS) {
            session = t;
            break;
          }
        }
      }
      if (session == null) return;

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
        case MediaDrm.EVENT_PROVISION_REQUIRED:
          session.state = STATE_OPENED;
          postProvisionRequest(session);
          break;
      }
    }

  }

  private class MediaDrmEventListener implements OnEventListener<T> {

    @Override
    public void onEvent(ExoMediaDrm<? extends T> md, byte[] sessionId, int event, int extra,
        byte[] data) {
      synchronized (drmRequests) {
        for (UplynkDrmSession t : drmRequests) {
          if (t.mode == UplynkDrmSession.MODE_PLAYBACK) {
            mediaDrmHandler.sendEmptyMessage(event);
            break;
          }
        }
      }
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostResponseHandler extends Handler {

    public PostResponseHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      switch (msg.what) {
        case MSG_PROVISION:
          onProvisionResponse(msg.obj);
          break;
        case MSG_KEYS:
          onKeyResponse(msg.obj);
          break;
      }
    }

  }

  @SuppressLint("HandlerLeak")
  private class PostRequestHandler extends Handler {

    public PostRequestHandler(Looper backgroundLooper) {
      super(backgroundLooper);
    }

    @Override
    public void handleMessage(Message msg) {
      Object response;
      try {
        switch (msg.what) {
          case MSG_PROVISION:
            response = callback.executeProvisionRequest(uuid, (ProvisionRequest) msg.obj);
            break;
          case MSG_KEYS:
            response = callback.executeKeyRequest(uuid, (KeyRequest) msg.obj);
            break;
          default:
            throw new RuntimeException();
        }
      } catch (Exception e) {
        response = e;
      }
      postResponseHandler.obtainMessage(msg.what, response).sendToTarget();
    }

  }

}
