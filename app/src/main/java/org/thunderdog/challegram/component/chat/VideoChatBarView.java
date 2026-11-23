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
 * TGX Mod: "join" bar shown above the messages when a chat has an active video chat / live stream.
 */
package org.thunderdog.challegram.component.chat;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.GroupCallListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.voip.GroupCallSession;

import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.Destroyable;

public class VideoChatBarView extends FrameLayout implements GroupCallListener, Destroyable {
  public interface Delegate {
    void onVideoChatBarClick (VideoChatBarView view, long chatId, int groupCallId, boolean join);
  }

  private final Tdlib tdlib;
  private final ImageView iconView;
  private final TextView titleView, subtitleView, joinButton;

  private @Nullable Delegate delegate;
  private long chatId;
  private int groupCallId;
  private @Nullable TdApi.GroupCall groupCall;
  private boolean isChannel;

  public VideoChatBarView (Context context, Tdlib tdlib) {
    super(context);
    this.tdlib = tdlib;

    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);

    iconView = new ImageView(context);
    iconView.setScaleType(ImageView.ScaleType.CENTER);
    iconView.setImageResource(R.drawable.baseline_mic_24);
    iconView.setColorFilter(Theme.getColor(ColorId.iconActive));
    row.addView(iconView, new LinearLayout.LayoutParams(Screen.dp(32f), Screen.dp(32f)));

    LinearLayout texts = new LinearLayout(context);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.setPadding(Screen.dp(12f), 0, Screen.dp(8f), 0);

    titleView = new TextView(context);
    titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    titleView.setTypeface(org.thunderdog.challegram.tool.Fonts.getRobotoMedium());
    titleView.setTextColor(Theme.getColor(ColorId.text));
    titleView.setSingleLine(true);
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(titleView);

    subtitleView = new TextView(context);
    subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
    subtitleView.setTextColor(Theme.getColor(ColorId.textLight));
    subtitleView.setSingleLine(true);
    subtitleView.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(subtitleView);

    row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    joinButton = new TextView(context);
    joinButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    joinButton.setTypeface(org.thunderdog.challegram.tool.Fonts.getRobotoMedium());
    joinButton.setTextColor(Theme.getColor(ColorId.textNeutral));
    joinButton.setText(Lang.getString(R.string.VideoChatJoin).toUpperCase());
    joinButton.setGravity(Gravity.CENTER);
    joinButton.setPadding(Screen.dp(12f), Screen.dp(6f), Screen.dp(12f), Screen.dp(6f));
    joinButton.setOnClickListener(v -> {
      if (delegate != null && groupCallId != 0) {
        delegate.onVideoChatBarClick(this, chatId, groupCallId, true);
      }
    });
    Views.setClickable(joinButton);
    row.addView(joinButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    setOnClickListener(v -> {
      if (delegate != null && groupCallId != 0) {
        delegate.onVideoChatBarClick(this, chatId, groupCallId, false);
      }
    });
  }

  public void setDelegate (@Nullable Delegate delegate) {
    this.delegate = delegate;
  }

  public void addThemeListeners (ViewController<?> themeProvider) {
    themeProvider.addThemeTextColorListener(titleView, ColorId.text);
    themeProvider.addThemeTextColorListener(subtitleView, ColorId.textLight);
    themeProvider.addThemeTextColorListener(joinButton, ColorId.textNeutral);
    themeProvider.addThemeFilterListener(iconView, ColorId.iconActive);
  }

  /**
   * @return whether the bar should be visible
   */
  public boolean setVideoChat (long chatId, @Nullable TdApi.VideoChat videoChat) {
    int newGroupCallId = videoChat != null ? videoChat.groupCallId : 0;
    if (this.groupCallId != newGroupCallId || this.chatId != chatId) {
      if (this.groupCallId != 0) {
        tdlib.listeners().unsubscribeFromGroupCallUpdates(this.groupCallId, this);
      }
      this.chatId = chatId;
      this.groupCallId = newGroupCallId;
      this.groupCall = null;
      this.isChannel = tdlib.isChannel(chatId);
      if (newGroupCallId != 0) {
        tdlib.listeners().subscribeToGroupCallUpdates(newGroupCallId, this);
        final int requestedId = newGroupCallId;
        tdlib.send(new TdApi.GetGroupCall(newGroupCallId), (groupCall, error) -> {
          if (groupCall != null) {
            UI.post(() -> {
              if (this.groupCallId == requestedId) {
                setGroupCall(groupCall);
              }
            });
          }
        });
      }
    }
    updateTexts();
    return newGroupCallId != 0;
  }

  private void setGroupCall (TdApi.GroupCall groupCall) {
    this.groupCall = groupCall;
    updateTexts();
  }

  private void updateTexts () {
    TdApi.GroupCall groupCall = this.groupCall;
    boolean isStream = isChannel || (groupCall != null && groupCall.isRtmpStream);
    String title;
    if (groupCall != null && !StringUtils.isEmpty(groupCall.title)) {
      title = groupCall.title;
    } else {
      title = Lang.getString(isStream ? R.string.LiveStreamBarTitle : R.string.VideoChatBarTitle);
    }
    titleView.setText(title);
    iconView.setImageResource(isStream ? R.drawable.baseline_videocam_24 : R.drawable.baseline_mic_24);

    String subtitle;
    if (groupCall == null) {
      subtitle = "";
    } else if (!groupCall.isActive && groupCall.scheduledStartDate != 0) {
      subtitle = Lang.getString(R.string.VideoChatStarts, Lang.getMessageTimestamp(groupCall.scheduledStartDate, TimeUnit.SECONDS));
    } else if (groupCall.participantCount == 0) {
      subtitle = Lang.getString(R.string.VideoChatNoParticipants);
    } else {
      subtitle = Lang.plural(isStream ? R.string.LiveStreamViewers : R.string.VideoChatParticipants, groupCall.participantCount);
    }
    subtitleView.setText(subtitle);
    subtitleView.setVisibility(StringUtils.isEmpty(subtitle) ? GONE : VISIBLE);

    GroupCallSession active = GroupCallSession.active();
    boolean isJoined = active != null && active.groupCallId == groupCallId && active.isJoined();
    joinButton.setText(Lang.getString(isJoined ? R.string.VideoChatOpen : isStream ? R.string.LiveStreamWatch : R.string.VideoChatJoin).toUpperCase());
  }

  @Override
  public void onGroupCallUpdated (TdApi.GroupCall groupCall) {
    if (groupCall.id == groupCallId) {
      UI.post(() -> {
        if (groupCall.id == this.groupCallId) {
          setGroupCall(groupCall);
        }
      });
    }
  }

  @Override
  public void performDestroy () {
    if (groupCallId != 0) {
      tdlib.listeners().unsubscribeFromGroupCallUpdates(groupCallId, this);
      groupCallId = 0;
    }
  }
}
