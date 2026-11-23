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
 * TGX Mod: video chat (voice chat) screen.
 */
package org.thunderdog.challegram.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.DoubleTextWrapper;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.voip.GroupCallSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

public class GroupCallController extends RecyclerViewController<GroupCallController.Args> implements View.OnClickListener, GroupCallSession.Listener {
  public static class Args {
    public final long chatId;
    public final int groupCallId;
    public final @Nullable String inviteHash;
    public final boolean autoJoin;

    public Args (long chatId, int groupCallId, @Nullable String inviteHash, boolean autoJoin) {
      this.chatId = chatId;
      this.groupCallId = groupCallId;
      this.inviteHash = inviteHash;
      this.autoJoin = autoJoin;
    }
  }

  public GroupCallController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_groupCall;
  }

  @Override
  public CharSequence getName () {
    TdApi.GroupCall groupCall = session != null ? session.getGroupCall() : null;
    if (groupCall != null && !StringUtils.isEmpty(groupCall.title)) {
      return groupCall.title;
    }
    return Lang.getString(R.string.VideoChatBarTitle);
  }

  private GroupCallSession session;
  private SettingsAdapter adapter;
  private DoubleHeaderView headerCell;
  private final Map<String, DoubleTextWrapper> wrappers = new HashMap<>();
  private boolean autoJoinPending;

  @Override
  public View getCustomHeaderCell () {
    return headerCell;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    Args args = getArgumentsStrict();
    session = GroupCallSession.obtain(tdlib, args.chatId, args.groupCallId);
    if (!session.isJoined()) {
      session.setInviteHash(args.inviteHash);
    }
    autoJoinPending = args.autoJoin && !session.isJoined();

    headerCell = new DoubleHeaderView(context);
    headerCell.setThemedTextColor(this);
    headerCell.initWithMargin(0, true);
    headerCell.setTitle(getName());
    headerCell.setSubtitle(tdlib.chatTitle(args.chatId));

    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_videoChatSpeaker) {
          view.getToggler().setRadioEnabled(session.isSpeakerphoneOn(), isUpdate);
        } else if (itemId == R.id.btn_videoChatMuteNew) {
          TdApi.GroupCall groupCall = session.getGroupCall();
          view.getToggler().setRadioEnabled(groupCall != null && groupCall.muteNewParticipants, isUpdate);
        } else if (itemId == R.id.btn_videoChatNotify) {
          TdApi.GroupCall groupCall = session.getGroupCall();
          view.getToggler().setRadioEnabled(groupCall != null && groupCall.enabledStartNotification, isUpdate);
        }
      }
    };
    recyclerView.setAdapter(adapter);
    session.addListener(this);
    buildItems();
    session.load();
  }

  @Override
  public void destroy () {
    super.destroy();
    if (session != null) {
      session.removeListener(this);
    }
  }

  // Items

  private void buildItems () {
    if (adapter == null) {
      return;
    }
    TdApi.GroupCall groupCall = session.getGroupCall();
    List<ListItem> items = new ArrayList<>();
    items.add(new ListItem(ListItem.TYPE_EMPTY_OFFSET_SMALL));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));

    boolean isJoined = session.isJoined();
    boolean isEnded = session.getState() == GroupCallSession.STATE_ENDED || (groupCall != null && !groupCall.isActive && groupCall.scheduledStartDate == 0);
    boolean isScheduled = groupCall != null && !groupCall.isActive && groupCall.scheduledStartDate != 0;

    if (groupCall == null) {
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatStatus, R.drawable.baseline_call_24, R.string.LoadingInformation));
    } else if (isEnded) {
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatStatus, R.drawable.baseline_call_end_24, R.string.VideoChatHasEnded));
    } else if (isScheduled) {
      String when = Lang.getMessageTimestamp(groupCall.scheduledStartDate, TimeUnit.SECONDS);
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatStatus, R.drawable.baseline_call_24, Lang.getString(R.string.VideoChatStarts, when)));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_videoChatNotify, 0, R.string.VideoChatNotifyMe));
      if (session.canManage()) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatStartNow, R.drawable.baseline_call_24, R.string.VideoChatStartNow));
      }
    } else {
      if (groupCall.isRtmpStream) {
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatWatch, R.drawable.baseline_videocam_24, R.string.LiveStreamWatch));
      } else if (isJoined) {
        boolean mutedByAdmin = session.isMutedByAdmin();
        if (mutedByAdmin) {
          items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatRaiseHand, R.drawable.baseline_front_hand_24, session.isHandRaised() ? R.string.VideoChatHandRaised : R.string.VideoChatRaiseHand));
        } else {
          items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatMute, session.isMuted() ? R.drawable.baseline_mic_off_24 : R.drawable.baseline_mic_24, session.isMuted() ? R.string.VideoChatUnmute : R.string.VideoChatMute));
        }
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_videoChatSpeaker, 0, R.string.VideoChatSpeaker));
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatLeave, R.drawable.baseline_call_end_24, R.string.VideoChatLeave).setTextColorId(ColorId.textNegative));
      } else {
        items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatJoin, R.drawable.baseline_call_24, session.getState() == GroupCallSession.STATE_JOINING ? R.string.VideoChatConnecting : R.string.VideoChatJoinAction));
      }
      if (session.canManage() && !groupCall.isRtmpStream && groupCall.canToggleMuteNewParticipants) {
        items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
        items.add(new ListItem(ListItem.TYPE_RADIO_SETTING, R.id.btn_videoChatMuteNew, 0, R.string.VideoChatMuteNewParticipants));
      }
    }
    if (groupCall != null && session.canManage() && !isEnded) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR_FULL));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_videoChatEnd, R.drawable.baseline_call_end_24, groupCall.isRtmpStream ? R.string.LiveStreamEnd : R.string.VideoChatEnd).setTextColorId(ColorId.textNegative));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    CharSequence status = statusText();
    if (!StringUtils.isEmpty(status)) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, R.id.btn_videoChatDescription, 0, status, false));
    }

    if (groupCall != null && !isEnded && !groupCall.isRtmpStream) {
      List<TdApi.GroupCallParticipant> participants = session.getSortedParticipants();
      items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, Lang.plural(R.string.VideoChatParticipants, groupCall.participantCount), false));
      items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
      if (participants.isEmpty()) {
        items.add(new ListItem(ListItem.TYPE_INFO, 0, 0, R.string.VideoChatNoParticipants));
      } else {
        boolean first = true;
        Map<String, DoubleTextWrapper> unused = new HashMap<>(wrappers);
        for (TdApi.GroupCallParticipant participant : participants) {
          String key = GroupCallSession.senderKey(participant.participantId);
          DoubleTextWrapper wrapper = wrappers.get(key);
          if (wrapper == null) {
            wrapper = newWrapper(participant.participantId);
            if (wrapper == null) {
              continue;
            }
            wrappers.put(key, wrapper);
          }
          unused.remove(key);
          wrapper.setForcedSubtitle(participantStatus(participant));
          if (!first) {
            items.add(new ListItem(ListItem.TYPE_SEPARATOR));
          }
          first = false;
          items.add(new ListItem(ListItem.TYPE_CHAT_SMALL, R.id.btn_videoChatParticipant).setData(wrapper));
        }
        for (String key : unused.keySet()) {
          wrappers.remove(key);
        }
      }
      items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    }
    adapter.setItems(items, false);
  }

  @Nullable
  private DoubleTextWrapper newWrapper (TdApi.MessageSender sender) {
    switch (sender.getConstructor()) {
      case TdApi.MessageSenderUser.CONSTRUCTOR:
        return new DoubleTextWrapper(tdlib, ((TdApi.MessageSenderUser) sender).userId, true);
      case TdApi.MessageSenderChat.CONSTRUCTOR: {
        TdApi.Chat chat = tdlib.chat(((TdApi.MessageSenderChat) sender).chatId);
        return chat != null ? new DoubleTextWrapper(tdlib, chat) : null;
      }
    }
    return null;
  }

  private CharSequence participantStatus (TdApi.GroupCallParticipant participant) {
    boolean isSpeaking = participant.isSpeaking || (participant.audioSourceId != 0 && session.getAudioLevel(participant.audioSourceId) > 0.1f && !participant.isMutedForAllUsers);
    String status;
    if (participant.isHandRaised) {
      status = Lang.getString(R.string.VideoChatStatusRaisedHand);
    } else if (isSpeaking) {
      status = Lang.getString(R.string.VideoChatStatusSpeaking);
    } else if (participant.isMutedForAllUsers || participant.isMutedForCurrentUser) {
      status = Lang.getString(participant.isMutedForAllUsers && !participant.canUnmuteSelf && !participant.isCurrentUser ? R.string.VideoChatStatusMutedByAdmin : R.string.VideoChatStatusMuted);
    } else {
      status = Lang.getString(R.string.VideoChatStatusListening);
    }
    if (participant.isCurrentUser) {
      status = Lang.getString(R.string.VideoChatStatusYou, status);
    } else if (!StringUtils.isEmpty(participant.bio) && !isSpeaking && !participant.isHandRaised) {
      status = participant.bio;
    }
    return status;
  }

  private CharSequence statusText () {
    String error = session.getLastError();
    switch (session.getState()) {
      case GroupCallSession.STATE_JOINING:
      case GroupCallSession.STATE_CONNECTING:
        return Lang.getString(R.string.VideoChatConnecting);
      case GroupCallSession.STATE_CONNECTED:
        return Lang.getString(R.string.VideoChatConnected);
      case GroupCallSession.STATE_STREAM:
        return Lang.getString(R.string.LiveStreamJoined);
      case GroupCallSession.STATE_FAILED:
        return error;
    }
    TdApi.GroupCall groupCall = session.getGroupCall();
    if (groupCall != null && groupCall.isRtmpStream && groupCall.isActive) {
      return Lang.plural(R.string.LiveStreamViewers, groupCall.participantCount);
    }
    return null;
  }

  private boolean updateScheduled;

  private void scheduleSubtitleUpdate () {
    if (updateScheduled) {
      return;
    }
    updateScheduled = true;
    runOnUiThreadOptional(() -> {
      updateScheduled = false;
      for (TdApi.GroupCallParticipant participant : session.getSortedParticipants()) {
        DoubleTextWrapper wrapper = wrappers.get(GroupCallSession.senderKey(participant.participantId));
        if (wrapper != null) {
          wrapper.setForcedSubtitle(participantStatus(participant));
        }
      }
    }, null, 250);
  }

  // GroupCallSession.Listener

  @Override
  public void onGroupCallChanged (GroupCallSession session) {
    if (isDestroyed()) {
      return;
    }
    if (headerCell != null) {
      headerCell.setTitle(getName());
    }
    buildItems();
    TdApi.GroupCall groupCall = session.getGroupCall();
    if (autoJoinPending && groupCall != null && groupCall.isActive) {
      autoJoinPending = false;
      join();
    }
  }

  @Override
  public void onParticipantsChanged (GroupCallSession session) {
    if (!isDestroyed()) {
      buildItems();
    }
  }

  @Override
  public void onSpeakingChanged (GroupCallSession session) {
    if (!isDestroyed()) {
      scheduleSubtitleUpdate();
    }
  }

  @Override
  public void onSessionStateChanged (GroupCallSession session) {
    if (!isDestroyed()) {
      buildItems();
    }
  }

  // Actions

  private void join () {
    TdApi.GroupCall groupCall = session.getGroupCall();
    if (groupCall == null) {
      return;
    }
    if (groupCall.isRtmpStream) {
      openStream();
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      context().requestCustomPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, (code, permissions, grantResults, grantCount) -> {
        if (grantCount == permissions.length) {
          joinImpl();
        } else {
          UI.showToast(R.string.VideoChatMicRequired, Toast.LENGTH_SHORT);
        }
      });
      return;
    }
    joinImpl();
  }

  private void joinImpl () {
    session.join(reason -> UI.showToast(reason, Toast.LENGTH_LONG));
  }

  private void openStream () {
    Args args = getArgumentsStrict();
    LiveStreamController c = new LiveStreamController(context, tdlib);
    c.setArguments(new LiveStreamController.Args(args.chatId, args.groupCallId, args.inviteHash));
    navigateTo(c);
  }

  @Override
  public void onClick (View v) {
    final int viewId = v.getId();
    if (viewId == R.id.btn_videoChatJoin) {
      join();
    } else if (viewId == R.id.btn_videoChatWatch) {
      openStream();
    } else if (viewId == R.id.btn_videoChatLeave) {
      session.leave();
    } else if (viewId == R.id.btn_videoChatMute) {
      session.setMuted(!session.isMuted());
    } else if (viewId == R.id.btn_videoChatRaiseHand) {
      session.setHandRaised(!session.isHandRaised());
    } else if (viewId == R.id.btn_videoChatSpeaker) {
      session.setSpeakerphoneOn(adapter.toggleView(v));
    } else if (viewId == R.id.btn_videoChatMuteNew) {
      session.setMuteNewParticipants(adapter.toggleView(v));
    } else if (viewId == R.id.btn_videoChatNotify) {
      session.setStartNotification(adapter.toggleView(v));
    } else if (viewId == R.id.btn_videoChatStartNow) {
      session.startScheduled();
    } else if (viewId == R.id.btn_videoChatEnd) {
      TdApi.GroupCall groupCall = session.getGroupCall();
      boolean isStream = groupCall != null && groupCall.isRtmpStream;
      showConfirm(Lang.getString(isStream ? R.string.LiveStreamEndConfirm : R.string.VideoChatEndConfirm), Lang.getString(isStream ? R.string.LiveStreamEnd : R.string.VideoChatEnd), R.drawable.baseline_call_end_24, OptionColor.RED, () -> session.end());
    } else if (viewId == R.id.btn_videoChatParticipant) {
      Object tag = v.getTag();
      if (tag instanceof ListItem) {
        Object data = ((ListItem) tag).getData();
        String key = null;
        for (Map.Entry<String, DoubleTextWrapper> entry : wrappers.entrySet()) {
          if (entry.getValue() == data) {
            key = entry.getKey();
            break;
          }
        }
        if (key == null) {
          return;
        }
        for (TdApi.GroupCallParticipant participant : session.getSortedParticipants()) {
          if (GroupCallSession.senderKey(participant.participantId).equals(key)) {
            showParticipantOptions(participant);
            break;
          }
        }
      }
    }
  }

  private void showParticipantOptions (TdApi.GroupCallParticipant participant) {
    List<Integer> ids = new ArrayList<>();
    List<String> titles = new ArrayList<>();
    List<Integer> colors = new ArrayList<>();
    List<Integer> icons = new ArrayList<>();
    if (!participant.isCurrentUser) {
      if (participant.canBeMutedForAllUsers) {
        ids.add(R.id.btn_videoChatMuteParticipant);
        titles.add(Lang.getString(R.string.VideoChatMuteForAll));
        colors.add(OptionColor.RED);
        icons.add(R.drawable.baseline_mic_off_24);
      } else if (participant.canBeUnmutedForAllUsers) {
        ids.add(R.id.btn_videoChatUnmuteParticipant);
        titles.add(Lang.getString(R.string.VideoChatUnmuteForAll));
        colors.add(OptionColor.NORMAL);
        icons.add(R.drawable.baseline_mic_24);
      } else if (participant.canBeMutedForCurrentUser) {
        ids.add(R.id.btn_videoChatMuteParticipant);
        titles.add(Lang.getString(R.string.VideoChatMuteForMe));
        colors.add(OptionColor.NORMAL);
        icons.add(R.drawable.baseline_mic_off_24);
      } else if (participant.canBeUnmutedForCurrentUser) {
        ids.add(R.id.btn_videoChatUnmuteParticipant);
        titles.add(Lang.getString(R.string.VideoChatUnmuteForMe));
        colors.add(OptionColor.NORMAL);
        icons.add(R.drawable.baseline_mic_24);
      }
    }
    ids.add(R.id.btn_openChat);
    titles.add(Lang.getString(R.string.OpenProfile));
    colors.add(OptionColor.NORMAL);
    icons.add(R.drawable.baseline_person_24);

    int[] idsArray = new int[ids.size()];
    int[] colorsArray = new int[ids.size()];
    int[] iconsArray = new int[ids.size()];
    for (int i = 0; i < ids.size(); i++) {
      idsArray[i] = ids.get(i);
      colorsArray[i] = colors.get(i);
      iconsArray[i] = icons.get(i);
    }
    showOptions(null, idsArray, titles.toArray(new String[0]), colorsArray, iconsArray, (itemView, id) -> {
      if (id == R.id.btn_videoChatMuteParticipant) {
        session.toggleParticipantMuted(participant, true);
      } else if (id == R.id.btn_videoChatUnmuteParticipant) {
        session.toggleParticipantMuted(participant, false);
      } else if (id == R.id.btn_openChat) {
        tdlib.ui().openSenderProfile(this, participant.participantId, null);
      }
      return true;
    });
  }
}
