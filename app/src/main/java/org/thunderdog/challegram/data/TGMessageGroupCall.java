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
 * TGX Mod: bubble for TdApi.MessageGroupCall (end-to-end encrypted group calls / conference
 * calls in private chats), modelled after TGMessageCall.
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.widget.FileProgressComponent;

import java.util.concurrent.TimeUnit;

import me.vkryl.core.StringUtils;

public class TGMessageGroupCall extends TGMessage {
  private TdApi.MessageGroupCall groupCall;

  public TGMessageGroupCall (MessagesManager context, TdApi.Message msg, TdApi.MessageGroupCall groupCall) {
    super(context, msg);
    this.groupCall = groupCall;
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() == TdApi.MessageGroupCall.CONSTRUCTOR) {
      this.groupCall = (TdApi.MessageGroupCall) newContent;
      rebuildContent();
      return true;
    }
    return false;
  }

  private String trimmedTitle, trimmedSubtitle;
  private boolean needFakeTitle;
  private float titleWidth, subtitleWidth;

  private String buildSubtitle () {
    String status;
    if (groupCall.isActive) {
      status = Lang.getString(R.string.GroupCallMessageOngoing);
    } else if (groupCall.wasMissed) {
      status = Lang.getString(isOutgoing() ? R.string.GroupCallMessageNotAnswered : R.string.GroupCallMessageMissed);
    } else if (groupCall.duration > 0) {
      status = Lang.getCallDuration(groupCall.duration);
    } else {
      status = null;
    }
    int count = groupCall.otherParticipantIds != null ? groupCall.otherParticipantIds.length : 0;
    if (count > 0) {
      String people = Lang.plural(R.string.VideoChatParticipants, count + 1);
      status = StringUtils.isEmpty(status) ? people : status + ", " + people;
    }
    return status;
  }

  @Override
  protected void buildContent (int maxWidth) {
    String title = Lang.getString(groupCall.isVideo ? R.string.GroupCallMessageVideo : R.string.GroupCallMessage);
    String subtitle = buildSubtitle();
    if (useBubbles()) {
      StringBuilder b = new StringBuilder();
      b.append(Lang.time(msg.date, TimeUnit.SECONDS));
      if (!StringUtils.isEmpty(subtitle)) {
        b.append(", ");
        b.append(subtitle);
      }
      subtitle = b.toString();
    } else {
      maxWidth -= Screen.dp(40f) + Screen.dp(11f);
    }
    if (subtitle == null) {
      subtitle = "";
    }
    this.needFakeTitle = Text.needFakeBold(title);
    this.trimmedTitle = TextUtils.ellipsize(title, Paints.getMediumTextPaint(15f, needFakeTitle), maxWidth, TextUtils.TruncateAt.END).toString();
    this.trimmedSubtitle = TextUtils.ellipsize(subtitle, Paints.getSubtitlePaint(), maxWidth, TextUtils.TruncateAt.END).toString();
    this.titleWidth = U.measureText(trimmedTitle, Paints.getMediumTextPaint(15f, needFakeTitle));
    this.subtitleWidth = U.measureText(trimmedSubtitle, Paints.getSubtitlePaint());
  }

  @Override
  protected int getBubbleContentPadding () {
    return xBubblePadding + xBubblePaddingSmall;
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth) {
    Drawable phoneIcon = view.getSparseDrawable(groupCall.isVideo ? R.drawable.baseline_videocam_24 : R.drawable.baseline_group_24, ColorId.NONE);
    if (useBubbles()) {
      int colorId = groupCall.isActive ? ColorId.online : isOutgoingBubble() ? ColorId.bubbleOut_file : ColorId.file;
      Drawables.draw(c, phoneIcon, startX + getContentWidth() - getContentHeight() / 2f - phoneIcon.getMinimumWidth() / 2f, startY + getContentHeight() / 2f - phoneIcon.getMinimumHeight() / 2f, Paints.getPorterDuffPaint(Theme.getColor(colorId)));
    } else {
      int radius = Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS);
      c.drawCircle(startX + radius, startY + radius, radius, Paints.fillingPaint(Theme.getColor(groupCall.isActive ? ColorId.online : ColorId.file)));
      Drawables.draw(c, phoneIcon, startX + radius - phoneIcon.getMinimumWidth() / 2f, startY + radius - phoneIcon.getMinimumHeight() / 2f, Paints.whitePorterDuffPaint());
      startX += radius * 2 + Screen.dp(11f);
    }
    if (useBubbles()) {
      startY -= Screen.dp(4f);
    }
    c.drawText(trimmedTitle, startX, startY + Screen.dp(21f), Paints.getMediumTextPaint(15f, getTextColor(), needFakeTitle));
    c.drawText(trimmedSubtitle, startX, startY + Screen.dp(41f), Paints.getRegularTextPaint(13f, groupCall.wasMissed && !groupCall.isActive ? Theme.getColor(ColorId.textNegative) : getDecentColor()));
  }

  private boolean caught;
  private float touchStartX, touchStartY;

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    float x = e.getX();
    float y = e.getY();
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        caught = x >= getContentX() && x <= getContentX() + getContentWidth() && y >= getContentY() && y <= getContentY() + getContentHeight();
        touchStartX = x;
        touchStartY = y;
        return caught;
      }
      case MotionEvent.ACTION_CANCEL: {
        if (caught) {
          caught = false;
          return true;
        }
        break;
      }
      case MotionEvent.ACTION_MOVE: {
        if (caught && Math.abs(x - touchStartX) > Screen.getTouchSlop() && Math.abs(y - touchStartY) > Screen.getTouchSlop()) {
          caught = false;
          return true;
        }
        break;
      }
      case MotionEvent.ACTION_UP: {
        if (caught) {
          caught = false;
          performClickSoundFeedback();
          onBubbleClick();
          return true;
        }
        break;
      }
    }
    return caught;
  }

  private void onBubbleClick () {
    if (!groupCall.isActive) {
      return;
    }
    ViewController<?> c = controller();
    if (c == null) {
      return;
    }
    final long chatId = msg.chatId;
    final long messageId = msg.id;
    if (isOutgoing()) {
      c.showOptions(null, new int[] {R.id.btn_videoChatJoin}, new String[] {Lang.getString(R.string.VideoChatJoinAction)}, (itemView, id) -> {
        UI.showToast(R.string.GroupCallConferenceUnsupported, Toast.LENGTH_LONG);
        return true;
      });
      return;
    }
    c.showOptions(null,
      new int[] {R.id.btn_videoChatJoin, R.id.btn_videoChatDecline},
      new String[] {Lang.getString(R.string.VideoChatJoinAction), Lang.getString(R.string.GroupCallDecline)},
      new int[] {ViewController.OptionColor.NORMAL, ViewController.OptionColor.RED},
      new int[] {R.drawable.baseline_call_24, R.drawable.baseline_call_end_24},
      (itemView, id) -> {
        if (id == R.id.btn_videoChatJoin) {
          // Conference calls are end-to-end encrypted: tgcalls needs a per-frame
          // e2eEncryptDecrypt callback backed by tde2e, which this build does not link.
          UI.showToast(R.string.GroupCallConferenceUnsupported, Toast.LENGTH_LONG);
        } else if (id == R.id.btn_videoChatDecline) {
          tdlib.send(new TdApi.DeclineGroupCallInvitation(chatId, messageId), (ok, error) -> {
            if (error != null) {
              UI.showError(error);
            }
          });
        }
        return true;
      });
  }

  @Override
  protected int getContentWidth () {
    return (int) Math.max(Math.max(titleWidth, subtitleWidth), useBubbles() ? Screen.dp(182f) : 0) + Screen.dp(40f) + Screen.dp(11f);
  }

  @Override
  protected int getContentHeight () {
    return useBubbles() ? Screen.dp(46f) : Screen.dp(FileProgressComponent.DEFAULT_FILE_RADIUS) * 2;
  }
}
