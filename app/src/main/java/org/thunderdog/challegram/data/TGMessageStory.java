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
 * File created on 23/11/2026
 */
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileLocal;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.mediaview.StoryViewController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.ViewSupport;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;

import me.vkryl.android.ViewUtils;
import tgx.td.Td;

/**
 * Renders {@link TdApi.MessageStory} (a story shared to a chat or a mention in a story)
 * as a portrait media preview. Tapping it opens {@link StoryViewController}.
 */
public class TGMessageStory extends TGMessage {
  private static final float MAX_PREVIEW_WIDTH_DP = 200f;
  private static final float PREVIEW_RATIO = 16f / 9f;

  private final long storyPosterChatId;
  private final int storyId;
  private final boolean viaMention;

  @Nullable private TdApi.Story story;
  private boolean isExpired;

  @Nullable private ImageFile miniThumbnail;
  @Nullable private ImageFile previewFile;

  private int previewWidth, previewHeight;
  private String label;
  private CharSequence displayLabel;
  private String posterName;
  private CharSequence displayPosterName;

  private static TextPaint labelPaint;

  private static TextPaint labelPaint () {
    if (labelPaint == null) {
      labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      labelPaint.setTypeface(Fonts.getRobotoMedium());
      labelPaint.setTextSize(Screen.dp(12f));
      labelPaint.setColor(0xffffffff);
    }
    return labelPaint;
  }

  public TGMessageStory (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageStory messageStory) {
    super(manager, msg);
    this.storyPosterChatId = messageStory.storyPosterChatId;
    this.storyId = messageStory.storyId;
    this.viaMention = messageStory.viaMention;
    this.posterName = nonNullTitle(tdlib.chatTitle(storyPosterChatId));
    loadStory();
  }

  private static String nonNullTitle (@Nullable String title) {
    return title != null ? title : "";
  }

  private void loadStory () {
    tdlib.send(new TdApi.GetStory(storyPosterChatId, storyId, false), (story, error) -> runOnUiThreadOptional(() -> {
      if (isDestroyed()) {
        return;
      }
      if (error != null) {
        setExpired();
      } else {
        setStory(story);
      }
    }));
  }

  private void setExpired () {
    this.isExpired = true;
    this.story = null;
    this.miniThumbnail = null;
    this.previewFile = null;
    rebuildAndUpdateContent();
    invalidatePreviewReceiver();
    invalidateContentReceiver();
  }

  private void setStory (@NonNull TdApi.Story story) {
    this.story = story;
    this.isExpired = false;
    this.posterName = nonNullTitle(tdlib.chatTitle(story.posterChatId));
    ImageFile mini = null, preview = null;
    switch (story.content.getConstructor()) {
      case TdApi.StoryContentPhoto.CONSTRUCTOR: {
        TdApi.Photo photo = ((TdApi.StoryContentPhoto) story.content).photo;
        if (photo.minithumbnail != null) {
          mini = new ImageFileLocal(photo.minithumbnail);
        }
        TdApi.PhotoSize size = Td.findBiggest(photo);
        if (size != null) {
          preview = new ImageFile(tdlib, size.photo);
          preview.setSize(Screen.dp(MAX_PREVIEW_WIDTH_DP * PREVIEW_RATIO));
        }
        break;
      }
      case TdApi.StoryContentVideo.CONSTRUCTOR: {
        TdApi.StoryVideo video = ((TdApi.StoryContentVideo) story.content).video;
        if (video.minithumbnail != null) {
          mini = new ImageFileLocal(video.minithumbnail);
        }
        preview = TD.toImageFile(tdlib, video.thumbnail);
        break;
      }
      default:
        break;
    }
    if (mini != null) {
      mini.setScaleType(ImageFile.CENTER_CROP);
    }
    if (preview != null) {
      preview.setScaleType(ImageFile.CENTER_CROP);
      preview.setNoBlur();
    }
    this.miniThumbnail = mini;
    this.previewFile = preview;
    rebuildAndUpdateContent();
    invalidatePreviewReceiver();
    invalidateContentReceiver();
  }

  // Layout

  @Override
  protected void buildContent (int maxWidth) {
    this.previewWidth = Math.min(maxWidth, Math.min(getSmallestMaxContentWidth(), Screen.dp(MAX_PREVIEW_WIDTH_DP)));
    this.previewHeight = Math.round(previewWidth * PREVIEW_RATIO);
    if (isExpired) {
      label = Lang.getString(R.string.StoryExpired);
    } else if (viaMention) {
      label = Lang.getString(R.string.StoryMentionedYou, posterName);
    } else {
      label = Lang.getString(R.string.StoryShared);
    }
    int maxLabelWidth = previewWidth - Screen.dp(8f) * 2 - Screen.dp(8f) * 2;
    displayLabel = TextUtils.ellipsize(label, labelPaint(), Math.max(0, maxLabelWidth), TextUtils.TruncateAt.END);
    displayPosterName = viaMention || posterName == null ? null : TextUtils.ellipsize(posterName, labelPaint(), Math.max(0, maxLabelWidth), TextUtils.TruncateAt.END);
  }

  @Override
  protected int getContentWidth () {
    return previewWidth;
  }

  @Override
  protected int getContentHeight () {
    return previewHeight;
  }

  @Override
  protected boolean drawBubbleTimeOverContent () {
    return true;
  }

  @Override
  protected boolean allowBubbleHorizontalExtend () {
    return false;
  }

  @Override
  protected boolean needBubbleCornerFix () {
    return true;
  }

  @Override
  protected int getBubbleContentPadding () {
    return xBubblePaddingSmall;
  }

  // Images

  @Override
  public boolean needImageReceiver () {
    return true;
  }

  @Override
  public void requestPreview (DoubleImageReceiver receiver) {
    receiver.requestFile(miniThumbnail, null);
  }

  @Override
  public void requestImage (ImageReceiver receiver) {
    receiver.requestFile(previewFile);
  }

  // Drawing

  private final RectF labelRect = new RectF();

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, Receiver preview, Receiver receiver) {
    final boolean clipped = useBubbles() && !useForward();
    final int saveCount = clipped ? ViewSupport.clipPath(c, getBubbleClipPath()) : Integer.MIN_VALUE;

    final int right = startX + previewWidth;
    final int bottom = startY + previewHeight;
    preview.setBounds(startX, startY, right, bottom);
    receiver.setBounds(startX, startY, right, bottom);
    if (receiver.needPlaceholder()) {
      if (preview.needPlaceholder()) {
        c.drawRect(startX, startY, right, bottom, Paints.getPlaceholderPaint());
      }
      preview.draw(c);
    }
    receiver.draw(c);

    if (clipped) {
      ViewSupport.restoreClipPath(c, saveCount);
    }

    // Labels
    TextPaint paint = labelPaint();
    final int padding = Screen.dp(8f);
    final int pillPadding = Screen.dp(8f);
    final int pillHeight = Screen.dp(22f);
    final float pillRadius = pillHeight / 2f;
    if (displayLabel != null && displayLabel.length() > 0) {
      float textWidth = paint.measureText(displayLabel, 0, displayLabel.length());
      float pillLeft = startX + padding;
      float pillTop = isExpired ? startY + (previewHeight - pillHeight) / 2f : startY + padding;
      if (isExpired) {
        pillLeft = startX + (previewWidth - textWidth - pillPadding * 2) / 2f;
      }
      labelRect.set(pillLeft, pillTop, pillLeft + textWidth + pillPadding * 2, pillTop + pillHeight);
      c.drawRoundRect(labelRect, pillRadius, pillRadius, Paints.fillingPaint(0x66000000));
      c.drawText(displayLabel, 0, displayLabel.length(), labelRect.left + pillPadding, labelRect.centerY() + Screen.dp(4f), paint);
    }
    if (!isExpired && displayPosterName != null && displayPosterName.length() > 0) {
      float textWidth = paint.measureText(displayPosterName, 0, displayPosterName.length());
      float pillLeft = startX + padding;
      float pillTop = startY + padding + pillHeight + Screen.dp(4f);
      labelRect.set(pillLeft, pillTop, pillLeft + textWidth + pillPadding * 2, pillTop + pillHeight);
      c.drawRoundRect(labelRect, pillRadius, pillRadius, Paints.fillingPaint(0x66000000));
      c.drawText(displayPosterName, 0, displayPosterName.length(), labelRect.left + pillPadding, labelRect.centerY() + Screen.dp(4f), paint);
    }
  }

  // Touch

  private float touchX, touchY;

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        float x = e.getX(), y = e.getY();
        int left = getContentX(), top = getContentY();
        if (!isExpired && x >= left && x <= left + previewWidth && y >= top && y <= top + previewHeight) {
          touchX = x;
          touchY = y;
          return true;
        }
        touchX = touchY = 0f;
        break;
      }
      case MotionEvent.ACTION_CANCEL: {
        touchX = touchY = 0f;
        break;
      }
      case MotionEvent.ACTION_UP: {
        if (touchX != 0f && touchY != 0f) {
          boolean isClick = Math.abs(e.getX() - touchX) < Screen.getTouchSlop() && Math.abs(e.getY() - touchY) < Screen.getTouchSlop();
          touchX = touchY = 0f;
          if (isClick) {
            ViewController<?> c = controller();
            if (c != null) {
              ViewUtils.onClick(view);
              StoryViewController.openStory(c, storyPosterChatId, storyId);
              return true;
            }
          }
        }
        break;
      }
    }
    return false;
  }
}
