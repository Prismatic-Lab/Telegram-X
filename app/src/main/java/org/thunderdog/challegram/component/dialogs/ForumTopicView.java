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
 */
package org.thunderdog.challegram.component.dialogs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.ContentPreview;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.ForumTopics;
import org.thunderdog.challegram.util.text.Counter;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextEntity;

import java.util.concurrent.TimeUnit;

import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;

/**
 * TGX Mod: a row of the forum topics list.
 */
public class ForumTopicView extends View {
  private static final float HEIGHT = 72f;
  private static final float ICON_SIZE = 36f;
  private static final float TEXT_LEFT = 72f;
  private static final float PADDING_RIGHT = 16f;

  private final ComplexReceiver iconReceiver, textReceiver;
  private final Counter counter, mentionCounter;

  private @Nullable Tdlib tdlib;
  private @Nullable TdApi.ForumTopic topic;

  private @Nullable Text iconText, letterText, titleText, previewText;
  private @Nullable String timeText;
  private float timeWidth;
  private int lastWidth;

  public ForumTopicView (Context context) {
    super(context);
    iconReceiver = new ComplexReceiver(this);
    textReceiver = new ComplexReceiver(this);
    counter = new Counter.Builder().callback(this).build();
    mentionCounter = new Counter.Builder()
      .drawable(R.drawable.baseline_at_16, 16f, 0f, Gravity.CENTER)
      .callback(this)
      .build();
    setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(HEIGHT)));
  }

  @Nullable
  public TdApi.ForumTopic getTopic () {
    return topic;
  }

  public void setTopic (Tdlib tdlib, @Nullable TdApi.ForumTopic topic) {
    this.tdlib = tdlib;
    this.topic = topic;
    boolean muted = false;
    int unreadCount = 0, mentionCount = 0;
    if (topic != null) {
      unreadCount = topic.unreadCount;
      mentionCount = topic.unreadMentionCount;
      TdApi.ChatNotificationSettings settings = topic.notificationSettings;
      if (settings != null) {
        muted = settings.useDefaultMuteFor ? !tdlib.chatNotificationsEnabled(topic.info.chatId) : settings.muteFor > 0;
      }
    }
    counter.setCount(mentionCount > 0 && unreadCount == 1 ? 0 : unreadCount, muted, false);
    mentionCounter.setCount(mentionCount > 0 ? Tdlib.CHAT_MARKED_AS_UNREAD : 0, false, false);
    buildLayout(getMeasuredWidth());
    invalidate();
  }

  @Override
  protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Screen.dp(HEIGHT), MeasureSpec.EXACTLY));
    int width = getMeasuredWidth();
    if (width != lastWidth) {
      buildLayout(width);
    }
  }

  private void buildLayout (int width) {
    lastWidth = width;
    iconText = letterText = titleText = previewText = null;
    timeText = null;
    timeWidth = 0;
    if (width <= 0 || topic == null || tdlib == null) {
      iconReceiver.clear();
      textReceiver.clear();
      return;
    }
    TdApi.ForumTopicInfo info = topic.info;

    // Icon
    if (info.icon != null && info.icon.customEmojiId != 0) {
      String emoji = "\uD83D\uDCAC";
      TdApi.FormattedText iconFormatted = new TdApi.FormattedText(emoji, new TdApi.TextEntity[] {
        new TdApi.TextEntity(0, emoji.length(), new TdApi.TextEntityTypeCustomEmoji(info.icon.customEmojiId))
      });
      iconText = new Text.Builder(tdlib, iconFormatted, null, Screen.dp(ICON_SIZE) * 2, Paints.robotoStyleProvider(28), TextColorSets.Regular.NORMAL, (text, specificMedia) -> {
        if (iconText == text && !text.invalidateMediaContent(iconReceiver, specificMedia)) {
          text.requestMedia(iconReceiver);
        }
      }).singleLine().noClickable().build();
      iconText.requestMedia(iconReceiver);
    } else {
      iconReceiver.clear();
      letterText = new Text.Builder(ForumTopics.iconLetter(info), Screen.dp(ICON_SIZE), Paints.robotoStyleProvider(16), TextColorSets.Regular.AVATAR_CONTENT)
        .allBold()
        .singleLine()
        .noClickable()
        .build();
    }

    // Time
    TdApi.Message lastMessage = topic.lastMessage;
    int date = lastMessage != null ? lastMessage.date : info.creationDate;
    if (date != 0) {
      timeText = Lang.timeOrDateShort(date, TimeUnit.SECONDS);
      timeWidth = Paints.getRegularTextPaint(13f).measureText(timeText);
    }

    int textLeft = Screen.dp(TEXT_LEFT);
    int right = width - Screen.dp(PADDING_RIGHT);

    // Title
    int titleAvail = right - textLeft - (int) timeWidth - Screen.dp(8f);
    if (info.isClosed) {
      titleAvail -= Screen.dp(20f);
    }
    if (titleAvail > 0) {
      titleText = new Text.Builder(StringUtils.isEmpty(info.name) ? "#" : info.name, titleAvail, Paints.robotoStyleProvider(16), TextColorSets.Regular.NORMAL)
        .allBold()
        .singleLine()
        .noClickable()
        .build();
    }

    // Preview
    int previewAvail = right - textLeft - (int) Math.max(counter.getScaledWidth(Screen.dp(8f)) + mentionCounter.getScaledWidth(Screen.dp(8f)), topic.isPinned ? Screen.dp(24f) : 0);
    if (previewAvail > 0) {
      TdApi.FormattedText preview;
      if (lastMessage != null) {
        ContentPreview contentPreview = ContentPreview.getChatListPreview(tdlib, lastMessage.chatId, lastMessage, false);
        String text = contentPreview.buildText(true);
        TdApi.TextEntity[] entities = contentPreview.formattedText != null ? contentPreview.formattedText.entities : null;
        String sender = lastMessage.isOutgoing ? Lang.getString(R.string.FromYou) : tdlib.senderName(lastMessage, false, true);
        if (!StringUtils.isEmpty(sender) && !ForumTopics.isBotWithTopics(tdlib, tdlib.chat(lastMessage.chatId))) {
          String prefix = sender + ": ";
          int shift = prefix.length();
          if (entities != null) {
            TdApi.TextEntity[] shifted = new TdApi.TextEntity[entities.length];
            for (int i = 0; i < entities.length; i++) {
              shifted[i] = new TdApi.TextEntity(entities[i].offset + shift, entities[i].length, entities[i].type);
            }
            entities = shifted;
          }
          text = prefix + text;
        }
        preview = new TdApi.FormattedText(text, entities != null ? entities : new TdApi.TextEntity[0]);
      } else {
        preview = new TdApi.FormattedText(Lang.getString(R.string.NoMessages), new TdApi.TextEntity[0]);
      }
      previewText = new Text.Builder(preview.text, previewAvail, Paints.robotoStyleProvider(15), TextColorSets.Regular.LIGHT)
        .singleLine()
        .ignoreNewLines()
        .noClickable()
        .entities(TextEntity.valueOf(tdlib, preview.text, preview.entities, null), (text, specificMedia) -> {
          if (previewText == text && !text.invalidateMediaContent(textReceiver, specificMedia)) {
            text.requestMedia(textReceiver);
          }
        })
        .build();
      previewText.requestMedia(textReceiver);
    } else {
      textReceiver.clear();
    }
  }

  @Override
  protected void onAttachedToWindow () {
    super.onAttachedToWindow();
    iconReceiver.attach();
    textReceiver.attach();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    iconReceiver.detach();
    textReceiver.detach();
  }

  public void performDestroy () {
    iconReceiver.performDestroy();
    textReceiver.performDestroy();
  }

  @Override
  protected void onDraw (Canvas c) {
    if (topic == null) {
      return;
    }
    final int width = getMeasuredWidth();
    final int height = getMeasuredHeight();
    final int iconSize = Screen.dp(ICON_SIZE);
    final int iconLeft = Screen.dp(18f);
    final int iconTop = (height - iconSize) / 2;
    final float cx = iconLeft + iconSize / 2f;
    final float cy = iconTop + iconSize / 2f;

    if (iconText != null) {
      int x = (int) (cx - iconText.getWidth() / 2f);
      int y = (int) (cy - iconText.getHeight() / 2f);
      iconText.draw(c, x, y, null, 1f, iconReceiver);
    } else {
      c.drawCircle(cx, cy, iconSize / 2f, Paints.fillingPaint(ColorUtils.color(255, ForumTopics.iconColor(topic.info))));
      if (letterText != null) {
        letterText.draw(c, (int) (cx - letterText.getWidth() / 2f), (int) (cy - letterText.getHeight() / 2f));
      }
    }

    final int textLeft = Screen.dp(TEXT_LEFT);
    final int right = width - Screen.dp(PADDING_RIGHT);

    if (titleText != null) {
      int titleTop = Screen.dp(13f);
      titleText.draw(c, textLeft, titleTop);
      if (topic.info.isClosed) {
        Drawable lock = Drawables.get(getResources(), R.drawable.baseline_lock_16);
        if (lock != null) {
          Drawables.draw(c, lock, textLeft + titleText.getWidth() + Screen.dp(4f), titleTop + titleText.getHeight() / 2f - lock.getMinimumHeight() / 2f, Paints.getIconLightPorterDuffPaint());
        }
      }
    }

    if (timeText != null) {
      TextPaint paint = Paints.getRegularTextPaint(13f, Theme.textDecentColor());
      c.drawText(timeText, right - timeWidth, Screen.dp(28f), paint);
    }

    if (previewText != null) {
      previewText.draw(c, textLeft, Screen.dp(39f), null, 1f, textReceiver);
    }

    float counterRight = right;
    float counterCenterY = Screen.dp(50f);
    float radius = Screen.dp(11f);
    counter.draw(c, counterRight - radius, counterCenterY, Gravity.RIGHT, 1f);
    counterRight -= counter.getScaledWidth(Screen.dp(4f));
    mentionCounter.draw(c, counterRight - radius, counterCenterY, Gravity.RIGHT, 1f);
    counterRight -= mentionCounter.getScaledWidth(Screen.dp(4f));

    if (topic.isPinned && topic.unreadCount == 0 && topic.unreadMentionCount == 0) {
      Drawable pin = Drawables.get(getResources(), R.drawable.deproko_baseline_pin_16);
      if (pin != null) {
        Drawables.draw(c, pin, right - pin.getMinimumWidth(), counterCenterY - pin.getMinimumHeight() / 2f, Paints.getIconLightPorterDuffPaint());
      }
    }

    c.drawRect(textLeft, height - Math.max(1, Screen.dp(.5f)), width, height, Paints.fillingPaint(Theme.separatorColor()));
  }
}
