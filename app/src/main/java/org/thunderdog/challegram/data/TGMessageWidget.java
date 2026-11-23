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
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.ui.WidgetController;
import org.thunderdog.challegram.util.text.TextWrapper;

import me.vkryl.android.ViewUtils;
import me.vkryl.core.ColorUtils;
import tgx.td.Td;

/**
 * Placeholder bubble for bot HTML widgets ({@link TdApi.MessageWidget}).
 * The widget itself runs only after a tap, full-screen, in a sandboxed {@link WidgetController}.
 */
public class TGMessageWidget extends TGMessage {
  private static final int DEFAULT_HEIGHT_DP = 160;
  private static final int MIN_HEIGHT_DP = 50, MAX_HEIGHT_DP = 600;
  private static final int MIN_WIDTH_DP = 200;

  private TdApi.MessageWidget widget;

  private int placeholderWidth, placeholderHeight;
  private CharSequence displayTitle, displayHint;
  @Nullable private TextWrapper captionWrapper;
  private int captionTop;

  private static TextPaint titlePaint, hintPaint;

  private static TextPaint titlePaint () {
    if (titlePaint == null) {
      titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      titlePaint.setTypeface(Fonts.getRobotoMedium());
      titlePaint.setTextSize(Screen.dp(15f));
    }
    return titlePaint;
  }

  private static TextPaint hintPaint () {
    if (hintPaint == null) {
      hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      hintPaint.setTypeface(Fonts.getRobotoRegular());
      hintPaint.setTextSize(Screen.dp(13f));
    }
    return hintPaint;
  }

  public TGMessageWidget (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageWidget widget) {
    super(manager, msg);
    this.widget = widget;
  }

  public TdApi.MessageWidget getWidget () {
    return widget;
  }

  private String widgetTitle () {
    String botName = msg.viaBotUserId != 0 ? tdlib.cache().userFirstName(msg.viaBotUserId) : tdlib.senderName(msg.senderId);
    return TextUtils.isEmpty(botName) ? Lang.getString(R.string.WidgetTitle) : Lang.getString(R.string.WidgetFrom, botName);
  }

  // Layout

  @Override
  protected void buildContent (int maxWidth) {
    int width = Math.min(maxWidth, getSmallestMaxContentWidth());
    if (widget.width > 0) {
      width = Math.min(width, Math.max(Screen.dp(MIN_WIDTH_DP), Screen.dp(widget.width)));
    }
    int height = widget.height > 0 ? Screen.dp(Math.max(MIN_HEIGHT_DP, Math.min(MAX_HEIGHT_DP, widget.height))) : Screen.dp(DEFAULT_HEIGHT_DP);
    height = Math.min(height, (int) (Screen.currentHeight() * .6f));
    this.placeholderWidth = width;
    this.placeholderHeight = height;

    int maxTextWidth = Math.max(0, width - Screen.dp(16f) * 2);
    displayTitle = TextUtils.ellipsize(widgetTitle(), titlePaint(), maxTextWidth, TextUtils.TruncateAt.END);
    displayHint = TextUtils.ellipsize(Lang.getString(R.string.WidgetTapToLoad), hintPaint(), maxTextWidth, TextUtils.TruncateAt.END);

    if (!Td.isEmpty(widget.caption)) {
      captionWrapper = new TextWrapper(tdlib, widget.caption, getTextStyleProvider(), getTextColorSet(), new TdlibUi.UrlOpenParameters().sourceMessage(this), null);
      captionWrapper.setViewProvider(currentViews);
      captionWrapper.prepare(Math.max(0, width - getBubbleContentPadding() * 2));
    } else {
      captionWrapper = null;
    }
    captionTop = widget.showCaptionAboveMedia ? 0 : placeholderHeight + Screen.dp(8f);
  }

  private int captionHeight () {
    return captionWrapper != null ? captionWrapper.getHeight() + Screen.dp(8f) : 0;
  }

  private int placeholderTop () {
    return widget.showCaptionAboveMedia ? captionHeight() : 0;
  }

  @Override
  protected int getContentWidth () {
    return placeholderWidth;
  }

  @Override
  protected int getContentHeight () {
    return placeholderHeight + captionHeight();
  }

  @Override
  protected boolean drawBubbleTimeOverContent () {
    return captionWrapper == null || widget.showCaptionAboveMedia;
  }

  @Override
  protected boolean allowBubbleHorizontalExtend () {
    return false;
  }

  @Override
  protected int getBubbleContentPadding () {
    return xBubblePaddingSmall;
  }

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    return updateMessageContent(message, newContent, isBottomMessage);
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() != TdApi.MessageWidget.CONSTRUCTOR) {
      return false;
    }
    this.widget = (TdApi.MessageWidget) newContent;
    rebuildAndUpdateContent();
    return true;
  }

  // Drawing

  private final RectF rect = new RectF();

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth) {
    final int top = startY + placeholderTop();
    rect.set(startX, top, startX + placeholderWidth, top + placeholderHeight);
    final float radius = Screen.dp(8f);
    c.drawRoundRect(rect, radius, radius, Paints.fillingPaint(ColorUtils.alphaColor(.12f, Theme.getColor(isOutgoingBubble() ? ColorId.bubbleOut_fillingActive : ColorId.bubbleIn_fillingActive))));

    TextPaint tp = titlePaint();
    tp.setColor(Theme.getColor(isOutgoingBubble() ? ColorId.bubbleOut_text : ColorId.bubbleIn_text));
    TextPaint hp = hintPaint();
    hp.setColor(Theme.getColor(isOutgoingBubble() ? ColorId.bubbleOut_time : ColorId.bubbleIn_time));

    float centerY = top + placeholderHeight / 2f;
    if (displayTitle != null) {
      float w = tp.measureText(displayTitle, 0, displayTitle.length());
      c.drawText(displayTitle, 0, displayTitle.length(), startX + (placeholderWidth - w) / 2f, centerY - Screen.dp(4f), tp);
    }
    if (displayHint != null) {
      float w = hp.measureText(displayHint, 0, displayHint.length());
      c.drawText(displayHint, 0, displayHint.length(), startX + (placeholderWidth - w) / 2f, centerY + Screen.dp(16f), hp);
    }

    if (captionWrapper != null) {
      captionWrapper.draw(c, startX + getBubbleContentPadding(), startY + captionTop);
    }
  }

  // Touch

  private float touchX, touchY;

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (captionWrapper != null && captionWrapper.onTouchEvent(view, e)) {
      return true;
    }
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        float x = e.getX(), y = e.getY();
        int left = getContentX(), top = getContentY() + placeholderTop();
        if (x >= left && x <= left + placeholderWidth && y >= top && y <= top + placeholderHeight) {
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
              WidgetController controller = new WidgetController(c.context(), tdlib);
              controller.setArguments(new WidgetController.Args(msg, widget, widgetTitle()));
              c.navigateTo(controller);
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
