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
 * TGX Mod: view-only player screen for RTMP live streams in channels.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.voip.GroupCallSession;
import org.thunderdog.challegram.voip.LiveStreamPlayer;

import me.vkryl.core.StringUtils;

public class LiveStreamController extends ViewController<LiveStreamController.Args> implements GroupCallSession.Listener, LiveStreamPlayer.Callback {
  public static class Args {
    public final long chatId;
    public final int groupCallId;
    public final @Nullable String inviteHash;

    public Args (long chatId, int groupCallId, @Nullable String inviteHash) {
      this.chatId = chatId;
      this.groupCallId = groupCallId;
      this.inviteHash = inviteHash;
    }
  }

  public LiveStreamController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_liveStream;
  }

  @Override
  public CharSequence getName () {
    TdApi.GroupCall groupCall = session != null ? session.getGroupCall() : null;
    if (groupCall != null && !StringUtils.isEmpty(groupCall.title)) {
      return groupCall.title;
    }
    return tdlib.chatTitle(getArgumentsStrict().chatId);
  }

  private GroupCallSession session;
  private LiveStreamPlayer player;
  private FrameLayout contentView;
  private TextureView textureView;
  private TextView statusView, viewersView;
  private int videoWidth, videoHeight;
  private boolean joinRequested;

  @Override
  protected View onCreateView (Context context) {
    Args args = getArgumentsStrict();
    session = GroupCallSession.obtain(tdlib, args.chatId, args.groupCallId);
    if (!session.isJoined()) {
      session.setInviteHash(args.inviteHash);
    }

    contentView = new FrameLayout(context) {
      @Override
      protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        layoutTexture();
      }
    };
    contentView.setBackgroundColor(Color.BLACK);

    textureView = new TextureView(context);
    contentView.addView(textureView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

    statusView = new TextView(context);
    statusView.setTextColor(Color.WHITE);
    statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
    statusView.setGravity(Gravity.CENTER);
    statusView.setPadding(Screen.dp(24f), 0, Screen.dp(24f), 0);
    statusView.setText(Lang.getString(R.string.LiveStreamLoading));
    contentView.addView(statusView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

    LinearLayout bottomBar = new LinearLayout(context);
    bottomBar.setOrientation(LinearLayout.HORIZONTAL);
    bottomBar.setGravity(Gravity.CENTER_VERTICAL);
    bottomBar.setPadding(Screen.dp(16f), Screen.dp(12f), Screen.dp(16f), Screen.dp(12f));
    bottomBar.setBackgroundColor(0x80000000);

    viewersView = new TextView(context);
    viewersView.setTextColor(0xccffffff);
    viewersView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    bottomBar.addView(viewersView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    if (GroupCallSession.canManageVideoChats(tdlib, args.chatId)) {
      TextView settingsButton = newButton(context, Lang.getString(R.string.LiveStreamRtmpSettings));
      settingsButton.setOnClickListener(v -> showRtmpSettings());
      bottomBar.addView(settingsButton);
    }
    TextView leaveButton = newButton(context, Lang.getString(R.string.VideoChatLeave));
    leaveButton.setOnClickListener(v -> {
      session.leave();
      navigateBack();
    });
    bottomBar.addView(leaveButton);

    contentView.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

    session.addListener(this);
    session.load();
    return contentView;
  }

  private static TextView newButton (Context context, String text) {
    TextView button = new TextView(context);
    button.setText(text.toUpperCase());
    button.setTextColor(Color.WHITE);
    button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    button.setPadding(Screen.dp(12f), Screen.dp(8f), Screen.dp(12f), Screen.dp(8f));
    button.setClickable(true);
    return button;
  }

  private void layoutTexture () {
    if (textureView == null || contentView == null || videoWidth <= 0 || videoHeight <= 0) {
      return;
    }
    int width = contentView.getMeasuredWidth();
    int height = contentView.getMeasuredHeight();
    if (width == 0 || height == 0) {
      return;
    }
    float scale = Math.min((float) width / videoWidth, (float) height / videoHeight);
    int targetWidth = (int) (videoWidth * scale);
    int targetHeight = (int) (videoHeight * scale);
    ViewGroup.LayoutParams params = textureView.getLayoutParams();
    if (params.width != targetWidth || params.height != targetHeight) {
      params.width = targetWidth;
      params.height = targetHeight;
      textureView.post(() -> textureView.setLayoutParams(params));
    }
  }

  private void showRtmpSettings () {
    long chatId = getArgumentsStrict().chatId;
    tdlib.send(new TdApi.GetVideoChatRtmpUrl(chatId), (rtmpUrl, error) -> runOnUiThreadOptional(() -> {
      if (error != null) {
        UI.showError(error);
        return;
      }
      String info = Lang.getString(R.string.LiveStreamRtmpInfo, rtmpUrl.url, rtmpUrl.streamKey);
      showOptions(info, new int[] {R.id.btn_copyLink, R.id.btn_copyText}, new String[] {Lang.getString(R.string.LiveStreamCopyUrl), Lang.getString(R.string.LiveStreamCopyKey)}, (itemView, id) -> {
        if (id == R.id.btn_copyLink) {
          UI.copyText(rtmpUrl.url, R.string.CopiedLink);
        } else if (id == R.id.btn_copyText) {
          UI.copyText(rtmpUrl.streamKey, R.string.CopiedText);
        }
        return true;
      });
    }));
  }

  private void updateViewers () {
    TdApi.GroupCall groupCall = session.getGroupCall();
    if (viewersView != null && groupCall != null) {
      viewersView.setText(Lang.plural(R.string.LiveStreamViewers, groupCall.participantCount));
    }
  }

  private void setStatus (@Nullable CharSequence status) {
    if (statusView != null) {
      statusView.setText(status);
      statusView.setVisibility(StringUtils.isEmpty(status) ? View.GONE : View.VISIBLE);
    }
  }

  private void startPlayerIfNeeded () {
    if (player == null && session.getState() == GroupCallSession.STATE_STREAM) {
      player = new LiveStreamPlayer(tdlib, session.groupCallId, this);
      player.start(context, textureView);
    }
  }

  // GroupCallSession.Listener

  @Override
  public void onGroupCallChanged (GroupCallSession session) {
    if (isDestroyed()) {
      return;
    }
    updateViewers();
    TdApi.GroupCall groupCall = session.getGroupCall();
    if (groupCall == null) {
      return;
    }
    if (!groupCall.isActive) {
      setStatus(Lang.getString(groupCall.scheduledStartDate != 0 ? R.string.LiveStreamNotStarted : R.string.VideoChatHasEnded));
      stopPlayer();
      return;
    }
    if (!session.isJoined() && !joinRequested) {
      joinRequested = true;
      session.join(reason -> UI.showToast(reason, Toast.LENGTH_LONG));
    }
    startPlayerIfNeeded();
  }

  @Override
  public void onSessionStateChanged (GroupCallSession session) {
    if (isDestroyed()) {
      return;
    }
    if (session.getState() == GroupCallSession.STATE_FAILED) {
      setStatus(Lang.getString(R.string.LiveStreamError, StringUtils.isEmpty(session.getLastError()) ? "?" : session.getLastError()));
      stopPlayer();
    } else if (session.getState() == GroupCallSession.STATE_ENDED) {
      setStatus(Lang.getString(R.string.VideoChatHasEnded));
      stopPlayer();
    }
    startPlayerIfNeeded();
  }

  // LiveStreamPlayer.Callback

  @Override
  public void onStreamStateChanged (LiveStreamPlayer player, int state, @Nullable String error) {
    if (isDestroyed()) {
      return;
    }
    switch (state) {
      case LiveStreamPlayer.STATE_LOADING:
        setStatus(Lang.getString(R.string.LiveStreamLoading));
        break;
      case LiveStreamPlayer.STATE_WAITING:
        setStatus(Lang.getString(R.string.LiveStreamNotStarted));
        break;
      case LiveStreamPlayer.STATE_PLAYING:
        setStatus(null);
        break;
      case LiveStreamPlayer.STATE_ERROR:
        setStatus(Lang.getString(R.string.LiveStreamError, StringUtils.isEmpty(error) ? "?" : error));
        break;
    }
  }

  @Override
  public void onVideoSizeChanged (LiveStreamPlayer player, int width, int height, float pixelRatio) {
    this.videoWidth = (int) (width * (pixelRatio > 0 ? pixelRatio : 1f));
    this.videoHeight = height;
    layoutTexture();
  }

  private void stopPlayer () {
    if (player != null) {
      player.stop();
      player = null;
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    stopPlayer();
    if (session != null) {
      session.removeListener(this);
      // Watching is view-only: closing the player leaves the stream
      if (session.isJoined() && session == GroupCallSession.active()) {
        session.leave();
      }
    }
  }
}
