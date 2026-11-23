/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * TGX Mod: Java side of app/jni/tgvoip/tgcalls_group.cpp
 */
package org.thunderdog.challegram.voip;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;

/**
 * Thin wrapper around tgcalls::GroupInstanceCustomImpl.
 *
 * Mirrors the group methods of Telegram-Android's NativeInstance:
 * create → {@link #emitJoinPayload()} → (joinVideoChat with the payload) →
 * {@link #setConnectionMode(int)} RTC → {@link #setJoinResponsePayload(String)}.
 *
 * All callbacks arrive on tgcalls threads.
 */
@SuppressWarnings("JavaJniMissingFunction")
public final class GroupCallInstance {
  public static final int CONNECTION_MODE_NONE = 0;
  public static final int CONNECTION_MODE_RTC = 1;

  public interface Callback {
    void onEmitJoinPayload (GroupCallInstance instance, int audioSourceId, @NonNull String payload);
    void onNetworkStateUpdated (GroupCallInstance instance, boolean isConnected);
    void onAudioLevelsUpdated (GroupCallInstance instance, int[] audioSources, float[] levels, boolean[] voice);
  }

  private static Boolean isAvailable;

  /**
   * @return true if the loaded tgcallsjni library exports the group call bindings.
   * Builds made from sources without tgcalls_group.cpp (or without tgcalls at all) return false.
   */
  public static synchronized boolean isAvailable () {
    if (isAvailable == null) {
      boolean available;
      try {
        available = nativeIsAvailable();
      } catch (Throwable t) {
        Log.w(Log.TAG_VOIP, "Group calls native bindings are unavailable", t);
        available = false;
      }
      isAvailable = available;
    }
    return isAvailable;
  }

  private final Callback callback;
  private long nativePtr;

  public GroupCallInstance (@Nullable String logPath, boolean noiseSuppression, @NonNull Callback callback) {
    this.callback = callback;
    this.nativePtr = newGroupInstance(logPath, noiseSuppression);
    if (this.nativePtr == 0) {
      throw new IllegalStateException("tgcalls group instance was not created");
    }
  }

  public boolean isDestroyed () {
    return nativePtr == 0;
  }

  public void emitJoinPayload () {
    if (nativePtr != 0) {
      emitJoinPayload(nativePtr);
    }
  }

  public void setJoinResponsePayload (@NonNull String payload) {
    if (nativePtr != 0) {
      setJoinResponsePayload(nativePtr, payload);
    }
  }

  public void setConnectionMode (int mode) {
    if (nativePtr != 0) {
      setConnectionMode(nativePtr, mode);
    }
  }

  public void setIsMuted (boolean isMuted) {
    if (nativePtr != 0) {
      setIsMuted(nativePtr, isMuted);
    }
  }

  public void setNoiseSuppressionEnabled (boolean isEnabled) {
    if (nativePtr != 0) {
      setNoiseSuppressionEnabled(nativePtr, isEnabled);
    }
  }

  public void setVolume (int audioSourceId, double volume) {
    if (nativePtr != 0) {
      setVolume(nativePtr, audioSourceId, volume);
    }
  }

  public void removeSsrcs (int[] audioSourceIds) {
    if (nativePtr != 0 && audioSourceIds != null && audioSourceIds.length > 0) {
      removeSsrcs(nativePtr, audioSourceIds);
    }
  }

  public void destroy () {
    long ptr = nativePtr;
    if (ptr != 0) {
      nativePtr = 0;
      destroyGroupInstance(ptr);
    }
  }

  // Called from tgcalls_group.cpp

  @Keep
  private void onEmitJoinPayload (int audioSourceId, String payload) {
    callback.onEmitJoinPayload(this, audioSourceId, payload != null ? payload : "");
  }

  @Keep
  private void onNetworkStateUpdated (boolean isConnected) {
    callback.onNetworkStateUpdated(this, isConnected);
  }

  @Keep
  private void onAudioLevelsUpdated (int[] audioSources, float[] levels, boolean[] voice) {
    callback.onAudioLevelsUpdated(this, audioSources, levels, voice);
  }

  // Native

  private static boolean nativeIsAvailable () {
    try {
      return probeGroupCalls();
    } catch (UnsatisfiedLinkError e) {
      return false;
    }
  }

  private static native boolean probeGroupCalls ();

  private native long newGroupInstance (@Nullable String logPath, boolean noiseSuppression);
  private native void emitJoinPayload (long ptr);
  private native void setJoinResponsePayload (long ptr, String payload);
  private native void setConnectionMode (long ptr, int mode);
  private native void setIsMuted (long ptr, boolean isMuted);
  private native void setNoiseSuppressionEnabled (long ptr, boolean isEnabled);
  private native void setVolume (long ptr, int audioSourceId, double volume);
  private native void removeSsrcs (long ptr, int[] audioSourceIds);
  private native void destroyGroupInstance (long ptr);
}
