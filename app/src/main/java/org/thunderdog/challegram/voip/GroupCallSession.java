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
 * TGX Mod: state of one video chat / voice chat / live stream (TdApi.GroupCall).
 */
package org.thunderdog.challegram.voip;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.service.GroupCallService;
import org.thunderdog.challegram.telegram.GroupCallListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.vkryl.core.StringUtils;

/**
 * One group call (video chat in a group, voice chat, or RTMP live stream in a channel).
 *
 * At most one session is "joined" at a time ({@link #active()}).
 * Sessions are driven from the UI thread; TDLib and tgcalls callbacks are posted to it.
 */
public final class GroupCallSession implements GroupCallListener, GroupCallInstance.Callback {
  // Settings toggle (Settings → Notifications & Sounds → Calls)
  private static final String PREF_ENABLED = "tgxmod_video_chats";

  public static boolean isFeatureEnabled () {
    return Settings.instance().getBoolean(PREF_ENABLED, true);
  }

  public static void setFeatureEnabled (boolean isEnabled) {
    Settings.instance().putBoolean(PREF_ENABLED, isEnabled);
  }

  public static boolean canManageVideoChats (Tdlib tdlib, long chatId) {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageVideoChats;
    }
    return false;
  }

  public interface Listener {
    default void onGroupCallChanged (GroupCallSession session) { }
    default void onParticipantsChanged (GroupCallSession session) { }
    default void onSpeakingChanged (GroupCallSession session) { }
    default void onSessionStateChanged (GroupCallSession session) { }
  }

  public static final int STATE_IDLE = 0;
  public static final int STATE_JOINING = 1;
  public static final int STATE_CONNECTING = 2;
  public static final int STATE_CONNECTED = 3;
  /** Joined as a viewer of a stream (RTMP live stream). Media is fetched by {@link LiveStreamPlayer}. */
  public static final int STATE_STREAM = 4;
  public static final int STATE_FAILED = 5;
  public static final int STATE_ENDED = 6;

  // Active session

  private static GroupCallSession activeSession;

  @Nullable
  public static GroupCallSession active () {
    return activeSession;
  }

  /** Returns the joined session for this call, or creates a new (not joined) one. */
  @UiThread
  public static GroupCallSession obtain (Tdlib tdlib, long chatId, int groupCallId) {
    GroupCallSession session = activeSession;
    if (session != null && session.tdlib == tdlib && session.groupCallId == groupCallId) {
      return session;
    }
    return new GroupCallSession(tdlib, chatId, groupCallId);
  }

  public static void leaveActive () {
    UI.post(() -> {
      GroupCallSession session = activeSession;
      if (session != null) {
        session.leave();
      }
    });
  }

  // Instance

  public final Tdlib tdlib;
  public final long chatId;
  public final int groupCallId;
  private @Nullable String inviteHash;

  private @Nullable TdApi.GroupCall groupCall;
  private final LinkedHashMap<String, TdApi.GroupCallParticipant> participants = new LinkedHashMap<>();
  private final Map<Integer, String> audioSourceToKey = new HashMap<>();
  private final Map<Integer, Float> audioLevels = new HashMap<>();
  private final Map<Integer, Long> lastVoiceTime = new HashMap<>();
  private final Map<Integer, Boolean> reportedSpeaking = new HashMap<>();

  private final List<Listener> listeners = new ArrayList<>();
  private int state = STATE_IDLE;
  private @Nullable String lastError;
  private boolean isMuted = true;
  private int myAudioSourceId;
  private @Nullable GroupCallInstance instance;
  private boolean isSubscribed;
  private boolean isLoadingParticipants;

  private int previousAudioMode = -2;
  private boolean previousSpeakerphone;

  private GroupCallSession (Tdlib tdlib, long chatId, int groupCallId) {
    this.tdlib = tdlib;
    this.chatId = chatId;
    this.groupCallId = groupCallId;
  }

  public void setInviteHash (@Nullable String inviteHash) {
    this.inviteHash = inviteHash;
  }

  // Listeners

  @UiThread
  public void addListener (Listener listener) {
    if (!listeners.contains(listener)) {
      listeners.add(listener);
    }
    subscribe();
  }

  @UiThread
  public void removeListener (Listener listener) {
    listeners.remove(listener);
    if (listeners.isEmpty() && !isJoined()) {
      unsubscribe();
    }
  }

  private void subscribe () {
    if (!isSubscribed) {
      isSubscribed = true;
      tdlib.listeners().subscribeToGroupCallUpdates(groupCallId, this);
    }
  }

  private void unsubscribe () {
    if (isSubscribed) {
      isSubscribed = false;
      tdlib.listeners().unsubscribeFromGroupCallUpdates(groupCallId, this);
    }
  }

  private void notifyCallChanged () {
    for (Listener listener : new ArrayList<>(listeners)) {
      listener.onGroupCallChanged(this);
    }
  }

  private void notifyParticipantsChanged () {
    for (Listener listener : new ArrayList<>(listeners)) {
      listener.onParticipantsChanged(this);
    }
  }

  private void notifySpeakingChanged () {
    for (Listener listener : new ArrayList<>(listeners)) {
      listener.onSpeakingChanged(this);
    }
  }

  private void setState (int state, @Nullable String error) {
    if (this.state != state || !StringUtils.equalsOrBothEmpty(this.lastError, error)) {
      this.state = state;
      this.lastError = error;
      for (Listener listener : new ArrayList<>(listeners)) {
        listener.onSessionStateChanged(this);
      }
    }
  }

  // Getters

  @Nullable
  public TdApi.GroupCall getGroupCall () {
    return groupCall;
  }

  public int getState () {
    return state;
  }

  @Nullable
  public String getLastError () {
    return lastError;
  }

  public boolean isJoined () {
    return state == STATE_JOINING || state == STATE_CONNECTING || state == STATE_CONNECTED || state == STATE_STREAM;
  }

  public boolean isRtmpStream () {
    return groupCall != null && groupCall.isRtmpStream;
  }

  public boolean isMuted () {
    return isMuted;
  }

  public boolean canManage () {
    return groupCall != null && groupCall.canBeManaged;
  }

  @Nullable
  public TdApi.GroupCallParticipant getMyParticipant () {
    for (TdApi.GroupCallParticipant participant : participants.values()) {
      if (participant.isCurrentUser) {
        return participant;
      }
    }
    return null;
  }

  /** @return participants sorted as TDLib requires: by {@code order}, descending. */
  public List<TdApi.GroupCallParticipant> getSortedParticipants () {
    List<TdApi.GroupCallParticipant> list = new ArrayList<>(participants.values());
    java.util.Collections.sort(list, (a, b) -> compareOrder(b.order, a.order));
    return list;
  }

  private static int compareOrder (String a, String b) {
    if (a.length() != b.length()) {
      return Integer.compare(a.length(), b.length());
    }
    return a.compareTo(b);
  }

  public float getAudioLevel (int audioSourceId) {
    Float level = audioLevels.get(audioSourceId);
    return level != null ? level : 0f;
  }

  public static String senderKey (TdApi.MessageSender sender) {
    if (sender == null) {
      return "";
    }
    switch (sender.getConstructor()) {
      case TdApi.MessageSenderUser.CONSTRUCTOR:
        return "u" + ((TdApi.MessageSenderUser) sender).userId;
      case TdApi.MessageSenderChat.CONSTRUCTOR:
        return "c" + ((TdApi.MessageSenderChat) sender).chatId;
    }
    return "";
  }

  // Loading

  @UiThread
  public void load () {
    subscribe();
    tdlib.send(new TdApi.GetGroupCall(groupCallId), (groupCall, error) -> UI.post(() -> {
      if (error != null) {
        setState(STATE_FAILED, TD.toErrorString(error));
      } else {
        applyGroupCall(groupCall);
      }
    }));
  }

  @UiThread
  public void loadMoreParticipants () {
    if (isLoadingParticipants || groupCall == null || groupCall.loadedAllParticipants || !groupCall.isActive) {
      return;
    }
    isLoadingParticipants = true;
    // Participants arrive as updateGroupCallParticipant
    tdlib.send(new TdApi.LoadGroupCallParticipants(groupCallId, 100), (ok, error) -> UI.post(() -> {
      isLoadingParticipants = false;
      if (error != null) {
        Log.w(Log.TAG_VOIP, "loadGroupCallParticipants failed: %s", TD.toErrorString(error));
      }
    }));
  }

  @UiThread
  private void applyGroupCall (TdApi.GroupCall groupCall) {
    boolean wasActive = this.groupCall != null && this.groupCall.isActive;
    boolean needLoad = this.groupCall == null;
    this.groupCall = groupCall;
    if (!groupCall.isActive && groupCall.scheduledStartDate == 0) {
      if (isJoined() || wasActive) {
        cleanup(false);
      }
      setState(STATE_ENDED, null);
    } else if (isJoined() && groupCall.needRejoin && !groupCall.isRtmpStream && instance != null) {
      rejoin();
    }
    notifyCallChanged();
    if (needLoad && groupCall.isActive) {
      loadMoreParticipants();
    }
  }

  // GroupCallListener (TDLib threads)

  @Override
  public void onGroupCallUpdated (TdApi.GroupCall groupCall) {
    if (groupCall.id != groupCallId) {
      return;
    }
    UI.post(() -> applyGroupCall(groupCall));
  }

  @Override
  public void onGroupCallParticipantUpdated (int groupCallId, TdApi.GroupCallParticipant participant) {
    if (groupCallId != this.groupCallId) {
      return;
    }
    UI.post(() -> applyParticipant(participant));
  }

  @UiThread
  private void applyParticipant (TdApi.GroupCallParticipant participant) {
    String key = senderKey(participant.participantId);
    if (StringUtils.isEmpty(participant.order)) {
      TdApi.GroupCallParticipant removed = participants.remove(key);
      if (removed != null) {
        audioSourceToKey.remove(removed.audioSourceId);
        if (instance != null && !removed.isCurrentUser && removed.audioSourceId != 0) {
          instance.removeSsrcs(new int[] {removed.audioSourceId});
        }
      }
    } else {
      participants.put(key, participant);
      if (participant.audioSourceId != 0) {
        audioSourceToKey.put(participant.audioSourceId, key);
      }
    }
    if (participant.isCurrentUser) {
      // Muted by an admin: the participant can't unmute itself
      boolean mutedByServer = participant.isMutedForAllUsers && !participant.canUnmuteSelf;
      if (mutedByServer && !isMuted) {
        setMutedInternal(true);
      }
    } else if (instance != null && participant.audioSourceId != 0) {
      instance.setVolume(participant.audioSourceId, participant.isMutedForCurrentUser ? 0.0 : participant.volumeLevel / 10000.0);
    }
    notifyParticipantsChanged();
  }

  // Joining

  public interface JoinCallback {
    void onJoinFailed (@NonNull String reason);
  }

  private @Nullable JoinCallback joinCallback;

  /**
   * Joins the call. Caller is responsible for the microphone permission (voice chats only).
   */
  @UiThread
  public void join (@Nullable JoinCallback callback) {
    if (isJoined()) {
      return;
    }
    GroupCallSession current = activeSession;
    if (current != null && current != this) {
      current.leave();
    }
    activeSession = this;
    this.joinCallback = callback;
    subscribe();
    setState(STATE_JOINING, null);

    boolean isStream = groupCall != null && groupCall.isRtmpStream;
    if (GroupCallInstance.isAvailable()) {
      try {
        File logDir = new File(UI.getContext().getCacheDir(), "tgcalls_group");
        //noinspection ResultOfMethodCallIgnored
        logDir.mkdirs();
        instance = new GroupCallInstance(new File(logDir, "group_" + groupCallId + ".log").getPath(), true, this);
        instance.setIsMuted(true);
        instance.emitJoinPayload();
        return;
      } catch (Throwable t) {
        Log.e(Log.TAG_VOIP, "Unable to create tgcalls group instance", t);
        instance = null;
      }
    }
    if (isStream) {
      // Viewing a stream needs no WebRTC connection: the payload is only required by the server
      // to register the viewer. Without the native bindings, a syntactically valid one is generated.
      int audioSource = randomAudioSource();
      sendJoin(audioSource, syntheticJoinPayload(audioSource));
    } else {
      failJoin(org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.VideoChatNativeMissing));
    }
  }

  private void rejoin () {
    GroupCallInstance instance = this.instance;
    if (instance == null) {
      return;
    }
    setState(STATE_JOINING, null);
    instance.setConnectionMode(GroupCallInstance.CONNECTION_MODE_NONE);
    instance.emitJoinPayload();
  }

  @UiThread
  private void failJoin (@NonNull String reason) {
    JoinCallback callback = this.joinCallback;
    this.joinCallback = null;
    cleanup(false);
    setState(STATE_FAILED, reason);
    if (callback != null) {
      callback.onJoinFailed(reason);
    }
  }

  @Override
  public void onEmitJoinPayload (GroupCallInstance instance, int audioSourceId, @NonNull String payload) {
    UI.post(() -> {
      if (this.instance != instance || !isJoined()) {
        return;
      }
      sendJoin(audioSourceId, payload);
    });
  }

  @UiThread
  private void sendJoin (int audioSourceId, String payload) {
    this.myAudioSourceId = audioSourceId;
    TdApi.Chat chat = tdlib.chat(chatId);
    TdApi.MessageSender participantId = chat != null && chat.videoChat != null ? chat.videoChat.defaultParticipantId : null;
    TdApi.GroupCallJoinParameters parameters = new TdApi.GroupCallJoinParameters(audioSourceId, payload, true, false);
    tdlib.send(new TdApi.JoinVideoChat(groupCallId, participantId, parameters, inviteHash), (response, error) -> UI.post(() -> {
      if (activeSession != this || !isJoined()) {
        return;
      }
      if (error != null) {
        failJoin(TD.toErrorString(error));
        return;
      }
      onJoined(response.text);
    }));
  }

  @UiThread
  private void onJoined (String responsePayload) {
    this.joinCallback = null;
    boolean isStreamResponse = responsePayload != null && responsePayload.contains("\"stream\"") && responsePayload.contains("true");
    boolean isStream = isRtmpStream() || isStreamResponse;
    if (isStream) {
      // Media for streams comes from GetGroupCallStreamSegment, no RTC connection needed
      if (instance != null) {
        instance.destroy();
        instance = null;
      }
      if (!isRtmpStream()) {
        // A regular video chat that the server switched to broadcast mode (too many listeners).
        // Its audio comes as multichannel OGG/Opus parts, which needs tgcalls StreamingMediaContext.
        failJoin(org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.VideoChatStreamMode));
        tdlib.send(new TdApi.LeaveGroupCall(groupCallId), tdlib.typedOkHandler());
        return;
      }
      setState(STATE_STREAM, null);
      GroupCallService.start(UI.getContext(), this);
      return;
    }
    if (instance == null) {
      failJoin(org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.VideoChatNativeMissing));
      return;
    }
    enterCommunicationAudioMode();
    instance.setConnectionMode(GroupCallInstance.CONNECTION_MODE_RTC);
    instance.setJoinResponsePayload(responsePayload);
    instance.setIsMuted(isMuted);
    setState(STATE_CONNECTING, null);
    GroupCallService.start(UI.getContext(), this);
    loadMoreParticipants();
  }

  @Override
  public void onNetworkStateUpdated (GroupCallInstance instance, boolean isConnected) {
    UI.post(() -> {
      if (this.instance != instance || !isJoined() || state == STATE_JOINING) {
        return;
      }
      setState(isConnected ? STATE_CONNECTED : STATE_CONNECTING, null);
    });
  }

  private static final float SPEAKING_LEVEL_THRESHOLD = 0.1f;
  private static final long SPEAKING_TIMEOUT_MS = 500;

  @Override
  public void onAudioLevelsUpdated (GroupCallInstance instance, int[] audioSources, float[] levels, boolean[] voice) {
    UI.post(() -> {
      if (this.instance != instance || !isJoined()) {
        return;
      }
      long now = SystemClock.uptimeMillis();
      boolean changed = false;
      for (int i = 0; i < audioSources.length; i++) {
        // tgcalls reports the local microphone under audio source 0
        int audioSource = audioSources[i] == 0 ? myAudioSourceId : audioSources[i];
        if (audioSource == 0) {
          continue;
        }
        float level = levels[i];
        Float prev = audioLevels.put(audioSource, level);
        if (prev == null || Math.abs(prev - level) > 0.02f) {
          changed = true;
        }
        if (voice[i] && level > SPEAKING_LEVEL_THRESHOLD) {
          lastVoiceTime.put(audioSource, now);
        }
      }
      // Report speaking state changes to TDLib (it updates GroupCallParticipant.isSpeaking & order)
      for (Map.Entry<Integer, Long> entry : lastVoiceTime.entrySet()) {
        int audioSource = entry.getKey();
        boolean isSpeaking = now - entry.getValue() < SPEAKING_TIMEOUT_MS;
        if (audioSource == myAudioSourceId && isMuted) {
          isSpeaking = false;
        }
        Boolean reported = reportedSpeaking.get(audioSource);
        if (reported == null || reported != isSpeaking) {
          reportedSpeaking.put(audioSource, isSpeaking);
          tdlib.send(new TdApi.SetGroupCallParticipantIsSpeaking(groupCallId, audioSource, isSpeaking), (sender, error) -> { });
        }
      }
      if (changed) {
        notifySpeakingChanged();
      }
    });
  }

  // Actions

  @UiThread
  public boolean isMutedByAdmin () {
    TdApi.GroupCallParticipant me = getMyParticipant();
    return me != null && me.isMutedForAllUsers && !me.canUnmuteSelf;
  }

  @UiThread
  public boolean isHandRaised () {
    TdApi.GroupCallParticipant me = getMyParticipant();
    return me != null && me.isHandRaised;
  }

  @UiThread
  public void setMuted (boolean isMuted) {
    if (!isMuted && isMutedByAdmin()) {
      return;
    }
    setMutedInternal(isMuted);
    TdApi.GroupCallParticipant me = getMyParticipant();
    if (me != null) {
      tdlib.send(new TdApi.ToggleGroupCallParticipantIsMuted(groupCallId, me.participantId, isMuted), tdlib.typedOkHandler());
    }
  }

  private void setMutedInternal (boolean isMuted) {
    this.isMuted = isMuted;
    if (instance != null) {
      instance.setIsMuted(isMuted);
    }
    for (Listener listener : new ArrayList<>(listeners)) {
      listener.onSessionStateChanged(this);
    }
  }

  @UiThread
  public void setHandRaised (boolean isRaised) {
    TdApi.GroupCallParticipant me = getMyParticipant();
    if (me != null) {
      tdlib.send(new TdApi.ToggleGroupCallParticipantIsHandRaised(groupCallId, me.participantId, isRaised), tdlib.typedOkHandler());
    }
  }

  @UiThread
  public void toggleParticipantMuted (TdApi.GroupCallParticipant participant, boolean isMuted) {
    tdlib.send(new TdApi.ToggleGroupCallParticipantIsMuted(groupCallId, participant.participantId, isMuted), (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
  }

  @UiThread
  public void setMuteNewParticipants (boolean mute) {
    tdlib.send(new TdApi.ToggleVideoChatMuteNewParticipants(groupCallId, mute), (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
  }

  @UiThread
  public void startScheduled () {
    tdlib.send(new TdApi.StartScheduledVideoChat(groupCallId), (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
  }

  @UiThread
  public void setStartNotification (boolean enabled) {
    tdlib.send(new TdApi.ToggleVideoChatEnabledStartNotification(groupCallId, enabled), (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
  }

  @UiThread
  public void leave () {
    if (isJoined()) {
      tdlib.send(new TdApi.LeaveGroupCall(groupCallId), (ok, error) -> {
        if (error != null) {
          Log.w(Log.TAG_VOIP, "leaveGroupCall failed: %s", TD.toErrorString(error));
        }
      });
    }
    cleanup(true);
  }

  @UiThread
  public void end () {
    tdlib.send(new TdApi.EndGroupCall(groupCallId), (ok, error) -> {
      if (error != null) {
        UI.showError(error);
      }
    });
    cleanup(true);
  }

  @UiThread
  private void cleanup (boolean updateState) {
    if (instance != null) {
      instance.destroy();
      instance = null;
    }
    exitCommunicationAudioMode();
    audioLevels.clear();
    lastVoiceTime.clear();
    reportedSpeaking.clear();
    myAudioSourceId = 0;
    isMuted = true;
    if (activeSession == this) {
      activeSession = null;
      GroupCallService.stop(UI.getContext());
    }
    if (listeners.isEmpty()) {
      unsubscribe();
    }
    if (updateState && state != STATE_ENDED) {
      setState(STATE_IDLE, null);
    }
  }

  // Audio routing

  private void enterCommunicationAudioMode () {
    try {
      AudioManager am = (AudioManager) UI.getContext().getSystemService(Context.AUDIO_SERVICE);
      if (am != null && previousAudioMode == -2) {
        previousAudioMode = am.getMode();
        previousSpeakerphone = am.isSpeakerphoneOn();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        // Voice chats are usually listened to on the loudspeaker, like in official apps
        am.setSpeakerphoneOn(true);
      }
    } catch (Throwable t) {
      Log.w(Log.TAG_VOIP, "Unable to set audio mode", t);
    }
  }

  private void exitCommunicationAudioMode () {
    if (previousAudioMode == -2) {
      return;
    }
    try {
      AudioManager am = (AudioManager) UI.getContext().getSystemService(Context.AUDIO_SERVICE);
      if (am != null) {
        am.setSpeakerphoneOn(previousSpeakerphone);
        am.setMode(previousAudioMode);
      }
    } catch (Throwable t) {
      Log.w(Log.TAG_VOIP, "Unable to restore audio mode", t);
    }
    previousAudioMode = -2;
  }

  public boolean isSpeakerphoneOn () {
    try {
      AudioManager am = (AudioManager) UI.getContext().getSystemService(Context.AUDIO_SERVICE);
      return am != null && am.isSpeakerphoneOn();
    } catch (Throwable t) {
      return false;
    }
  }

  public void setSpeakerphoneOn (boolean on) {
    try {
      AudioManager am = (AudioManager) UI.getContext().getSystemService(Context.AUDIO_SERVICE);
      if (am != null) {
        am.setSpeakerphoneOn(on);
      }
    } catch (Throwable t) {
      Log.w(Log.TAG_VOIP, "Unable to toggle speakerphone", t);
    }
  }

  // Synthetic join payload (streams only)

  private static final SecureRandom random = new SecureRandom();

  private static int randomAudioSource () {
    int value;
    do {
      value = random.nextInt();
    } while (value == 0);
    return value;
  }

  private static String randomString (int length) {
    final String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/";
    StringBuilder b = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      b.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return b.toString();
  }

  private static String syntheticJoinPayload (int audioSource) {
    StringBuilder fingerprint = new StringBuilder();
    for (int i = 0; i < 32; i++) {
      if (i > 0) {
        fingerprint.append(':');
      }
      fingerprint.append(String.format(java.util.Locale.US, "%02X", random.nextInt(256)));
    }
    try {
      org.json.JSONObject fp = new org.json.JSONObject();
      fp.put("hash", "sha-256");
      fp.put("setup", "active");
      fp.put("fingerprint", fingerprint.toString());
      org.json.JSONObject object = new org.json.JSONObject();
      object.put("ufrag", randomString(4));
      object.put("pwd", randomString(22));
      object.put("fingerprints", new org.json.JSONArray().put(fp));
      object.put("ssrc", audioSource);
      return object.toString();
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
