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
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.InstantViewController;
import org.thunderdog.challegram.util.text.FormattedText;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Rich message (article): a preview of its first blocks, cut off, and a button
 * that opens the whole content with the Instant View renderer.
 */
public class TGMessageRichMessage extends TGMessage implements MediaWrapper.OnClickListener, TGInlineKeyboard.ClickListener {
  private static final int PREVIEW_MAX_LENGTH = 600;
  private static final int PREVIEW_MAX_LINES = 12;

  private TdApi.RichMessage richMessage;
  private @Nullable TdApi.RichMessage fullRichMessage;

  private @Nullable String titleText;
  private @Nullable TextWrapper title;

  private @Nullable FormattedText previewText;
  private @Nullable TdApi.FormattedText previewTextToTranslate;
  private @Nullable TdApi.FormattedText translatedText;
  private @Nullable TextWrapper text;

  private @Nullable TdApi.PageBlock coverBlock;
  private @Nullable MediaWrapper mediaWrapper;

  private @Nullable TGInlineKeyboard rippleButton;
  private int rippleButtonY;

  public TGMessageRichMessage (MessagesManager context, TdApi.Message msg, @NonNull TdApi.RichMessage richMessage) {
    super(context, msg);
    setRichMessage(richMessage);
  }

  // Preview

  private void setRichMessage (@NonNull TdApi.RichMessage richMessage) {
    this.richMessage = richMessage;
    this.fullRichMessage = richMessage.isFull ? richMessage : null;
    parsePreview();
    setTitle();
    setText();
  }

  private void parsePreview () {
    this.titleText = null;
    this.coverBlock = null;
    List<FormattedText> texts = new ArrayList<>();
    int length = 0;
    for (TdApi.PageBlock block : richMessage.blocks) {
      if (length >= PREVIEW_MAX_LENGTH) {
        break;
      }
      final TdApi.RichText richText;
      switch (block.getConstructor()) {
        case TdApi.PageBlockCover.CONSTRUCTOR: {
          pickCover(((TdApi.PageBlockCover) block).cover);
          continue;
        }
        case TdApi.PageBlockPhoto.CONSTRUCTOR:
        case TdApi.PageBlockAnimation.CONSTRUCTOR: {
          // Media before any text works as a cover
          if (texts.isEmpty() && titleText == null) {
            pickCover(block);
          }
          continue;
        }
        case TdApi.PageBlockTitle.CONSTRUCTOR: {
          TdApi.RichText titleRichText = ((TdApi.PageBlockTitle) block).title;
          if (titleText == null && texts.isEmpty()) {
            titleText = TD.getText(titleRichText).trim();
            continue;
          }
          richText = titleRichText;
          break;
        }
        case TdApi.PageBlockHeader.CONSTRUCTOR:
          richText = ((TdApi.PageBlockHeader) block).header;
          break;
        case TdApi.PageBlockSubheader.CONSTRUCTOR:
          richText = ((TdApi.PageBlockSubheader) block).subheader;
          break;
        case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
          richText = ((TdApi.PageBlockSectionHeading) block).text;
          break;
        case TdApi.PageBlockSubtitle.CONSTRUCTOR:
          richText = ((TdApi.PageBlockSubtitle) block).subtitle;
          break;
        case TdApi.PageBlockParagraph.CONSTRUCTOR:
          richText = ((TdApi.PageBlockParagraph) block).text;
          break;
        case TdApi.PageBlockPreformatted.CONSTRUCTOR:
          richText = ((TdApi.PageBlockPreformatted) block).text;
          break;
        case TdApi.PageBlockThinking.CONSTRUCTOR:
          richText = new TdApi.RichTextItalic(((TdApi.PageBlockThinking) block).text);
          break;
        case TdApi.PageBlockMathematicalExpression.CONSTRUCTOR:
          richText = new TdApi.RichTextFixed(new TdApi.RichTextPlain(((TdApi.PageBlockMathematicalExpression) block).expression));
          break;
        case TdApi.PageBlockPullQuote.CONSTRUCTOR:
          richText = ((TdApi.PageBlockPullQuote) block).text;
          break;
        case TdApi.PageBlockExpandableBlockQuote.CONSTRUCTOR:
          richText = ((TdApi.PageBlockExpandableBlockQuote) block).text;
          break;
        case TdApi.PageBlockBlockQuote.CONSTRUCTOR: {
          TdApi.PageBlock[] quoteBlocks = ((TdApi.PageBlockBlockQuote) block).blocks;
          richText = quoteBlocks.length > 0 && quoteBlocks[0].getConstructor() == TdApi.PageBlockParagraph.CONSTRUCTOR ? ((TdApi.PageBlockParagraph) quoteBlocks[0]).text : null;
          break;
        }
        case TdApi.PageBlockKicker.CONSTRUCTOR:
        case TdApi.PageBlockAuthorDate.CONSTRUCTOR:
        case TdApi.PageBlockAnchor.CONSTRUCTOR:
        case TdApi.PageBlockDivider.CONSTRUCTOR:
          continue;
        default:
          // Lists, tables, media and the rest are seen in the full view
          richText = null;
          break;
      }
      if (Td.isEmpty(richText)) {
        continue;
      }
      if (titleText == null && texts.isEmpty() && isHeading(block)) {
        titleText = TD.getText(richText).trim();
        if (!StringUtils.isEmpty(titleText)) {
          continue;
        }
      }
      FormattedText formattedText = FormattedText.parseRichText(controller(), richText, openParameters());
      if (!StringUtils.isEmpty(formattedText.text.trim())) {
        texts.add(formattedText);
        length += formattedText.text.length();
      }
    }
    if (StringUtils.isEmpty(titleText)) {
      titleText = null;
    }
    if (texts.isEmpty()) {
      this.previewText = null;
      this.previewTextToTranslate = null;
    } else {
      this.previewText = FormattedText.concat("\n", texts.toArray(new FormattedText[0]));
      this.previewTextToTranslate = new TdApi.FormattedText(previewText.text, new TdApi.TextEntity[0]);
    }
  }

  private static boolean isHeading (TdApi.PageBlock block) {
    switch (block.getConstructor()) {
      case TdApi.PageBlockHeader.CONSTRUCTOR:
      case TdApi.PageBlockSubheader.CONSTRUCTOR:
      case TdApi.PageBlockSectionHeading.CONSTRUCTOR:
        return true;
    }
    return false;
  }

  private void pickCover (@Nullable TdApi.PageBlock block) {
    if (coverBlock != null || block == null) {
      return;
    }
    switch (block.getConstructor()) {
      case TdApi.PageBlockPhoto.CONSTRUCTOR:
        if (((TdApi.PageBlockPhoto) block).photo != null) {
          coverBlock = block;
        }
        break;
      case TdApi.PageBlockAnimation.CONSTRUCTOR:
        if (((TdApi.PageBlockAnimation) block).animation != null) {
          coverBlock = block;
        }
        break;
    }
  }

  private void setTitle () {
    if (titleText != null) {
      title = new TextWrapper(titleText, getBiggerTextStyleProvider(), getTextColorSet())
        .addTextFlags(Text.FLAG_ALL_BOLD)
        .setClickCallback(clickCallback());
      title.setViewProvider(currentViews);
    } else {
      title = null;
    }
  }

  private void setText () {
    if (this.text != null) {
      this.text.performDestroy();
    }
    final TextWrapper.TextMediaListener mediaListener = (wrapper, updatedText, specificMedia) -> {
      if (this.text == wrapper) {
        invalidateTextMediaReceiver(updatedText, specificMedia);
      }
    };
    // An AI summary is shown in place of the preview, over a translation
    final TdApi.FormattedText summary = getShownSummary();
    final TdApi.FormattedText alternativeText = summary != null ? summary : translatedText;
    if (alternativeText != null) {
      this.text = new TextWrapper(alternativeText.text, getTextStyleProvider(), getTextColorSet())
        .setEntities(TextEntity.valueOf(tdlib, alternativeText, openParameters()), mediaListener)
        .setClickCallback(clickCallback());
      if (summary == null) {
        this.text.setMaxLines(PREVIEW_MAX_LINES);
      }
    } else if (previewText != null) {
      this.text = new TextWrapper(previewText.text, getTextStyleProvider(), getTextColorSet(), previewText.entities, mediaListener)
        .setClickCallback(clickCallback())
        .setMaxLines(PREVIEW_MAX_LINES);
    } else {
      this.text = null;
    }
    if (this.text != null) {
      this.text.setViewProvider(currentViews);
    }
    invalidateTextMediaReceiver();
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    TdApi.RichMessage newRichMessage = ((TdApi.MessageRichMessage) newContent).message;
    resetSummary();
    translatedText = null;
    if (mediaWrapper != null) {
      mediaWrapper = null;
    }
    setRichMessage(newRichMessage);
    rebuildContent();
    invalidateContent(this);
    invalidatePreviewReceiver();
    return true;
  }

  // Layout

  @Override
  protected int getBubbleContentPadding () {
    return xBubblePadding + xBubblePaddingSmall;
  }

  private static int getBlockSpacing () {
    return Screen.dp(6f);
  }

  private int textTop, titleTop, mediaTop;
  private int contentHeight;

  @Override
  protected void buildContent (int maxWidth) {
    int y = 0;

    if (coverBlock != null) {
      if (mediaWrapper == null) {
        if (coverBlock.getConstructor() == TdApi.PageBlockAnimation.CONSTRUCTOR) {
          mediaWrapper = new MediaWrapper(context(), tdlib, ((TdApi.PageBlockAnimation) coverBlock).animation, msg.chatId, msg.id, this, false);
        } else {
          mediaWrapper = new MediaWrapper(context(), tdlib, ((TdApi.PageBlockPhoto) coverBlock).photo, msg.chatId, msg.id, this, false);
        }
        mediaWrapper.setViewProvider(currentViews);
        mediaWrapper.setOnClickListener(this);
      }
      int photoWidth = Math.max(1, mediaWrapper.getContentWidth());
      int photoHeight = Math.max(1, mediaWrapper.getContentHeight());
      int maxHeight = maxWidth; // at most a square
      float ratio = Math.min((float) maxWidth / (float) photoWidth, (float) maxHeight / (float) photoHeight);
      mediaWrapper.buildContent(Math.max(1, (int) (ratio * photoWidth)), Math.max(1, (int) (ratio * photoHeight)));
      mediaTop = y;
      y += mediaWrapper.getCellHeight();
    } else {
      mediaWrapper = null;
    }

    if (title != null) {
      if (y > 0) {
        y += getBlockSpacing();
      }
      title.prepare(maxWidth);
      titleTop = y;
      y += title.getHeight();
    }

    if (text != null) {
      if (y > 0) {
        y += Screen.dp(4f);
      }
      text.prepare(maxWidth);
      textTop = y;
      y += text.getHeight();
    }

    if (rippleButton == null) {
      rippleButton = new TGInlineKeyboard(this, false);
      rippleButton.setViewProvider(currentViews);
    }
    rippleButton.setCustom(R.drawable.deproko_baseline_instantview_24, Lang.getString(R.string.RichMessageRead), maxWidth, false, this);
    if (y > 0) {
      y += Screen.dp(10f);
    }
    rippleButtonY = y;
    y += TGInlineKeyboard.getButtonHeight();
    y += Screen.dp(2f);

    contentHeight = y;
  }

  @Override
  protected int getContentHeight () {
    return contentHeight;
  }

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    if (text != null) {
      text.requestMedia(textMediaReceiver);
    } else {
      textMediaReceiver.clear();
    }
  }

  // Media

  @Override
  public boolean needImageReceiver () {
    // Asked before the layout is built, so it is decided by the cover block, not by mediaWrapper
    return coverBlock != null;
  }

  @Override
  public boolean needGifReceiver () {
    return coverBlock != null && coverBlock.getConstructor() == TdApi.PageBlockAnimation.CONSTRUCTOR;
  }

  @Override
  public int getImageContentRadius (boolean isPreview) {
    return Screen.dp(Theme.getImageRadius());
  }

  @Override
  public void requestPreview (DoubleImageReceiver receiver) {
    if (mediaWrapper != null) {
      mediaWrapper.requestPreview(receiver);
    } else {
      receiver.clear();
    }
  }

  @Override
  public void requestImage (ImageReceiver receiver) {
    if (mediaWrapper != null) {
      mediaWrapper.requestImage(receiver);
    } else {
      receiver.requestFile(null);
    }
  }

  @Override
  public void requestGif (GifReceiver receiver) {
    if (mediaWrapper != null) {
      mediaWrapper.requestGif(receiver);
    } else {
      receiver.requestFile(null);
    }
  }

  @Override
  public void autoDownloadContent (TdApi.ChatType type) {
    if (mediaWrapper != null) {
      mediaWrapper.getFileProgress().downloadAutomatically(type);
    }
  }

  @Override
  protected void onMessageIdChanged (long oldMessageId, long newMessageId, boolean success) {
    if (mediaWrapper != null) {
      mediaWrapper.updateMessageId(oldMessageId, newMessageId, success);
    }
  }

  @Override
  protected void onMessageAttachedToView (@NonNull MessageView view, boolean attached) {
    if (mediaWrapper != null) {
      mediaWrapper.getFileProgress().notifyInvalidateTargetsChanged();
    }
  }

  // Drawing

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth, Receiver preview, Receiver receiver) {
    if (mediaWrapper != null) {
      mediaWrapper.draw(view, c, startX, startY + mediaTop, preview, receiver, 1f);
    }
    drawRichContent(view, c, startX, startY);
  }

  @Override
  protected void drawContent (MessageView view, Canvas c, int startX, int startY, int maxWidth) {
    drawRichContent(view, c, startX, startY);
  }

  private void drawRichContent (MessageView view, Canvas c, int startX, int startY) {
    if (title != null) {
      title.draw(c, startX, startY + titleTop, null, 1f);
    }
    if (text != null) {
      text.draw(c, startX, startY + textTop, null, getTranslationLoadingAlphaValue(), view.getTextMediaReceiver());
    }
    if (rippleButton != null) {
      rippleButton.draw(view, c, startX, startY + rippleButtonY);
    }
  }

  // Touch

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    if (rippleButton != null && rippleButton.onTouchEvent(view, e)) {
      return true;
    }
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    if (mediaWrapper != null && mediaWrapper.onTouchEvent(view, e)) {
      return true;
    }
    return text != null && text.onTouchEvent(view, e);
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    boolean res = super.performLongPress(view, x, y);
    boolean a = rippleButton != null && rippleButton.performLongPress(view);
    boolean b = mediaWrapper != null && mediaWrapper.performLongPress(view);
    boolean t = text != null && text.performLongPress(view);
    return a || b || t || res;
  }

  @Override
  protected void onMessageContainerDestroyed () {
    if (text != null) {
      text.performDestroy();
    }
  }

  // Full view

  @Override
  public void onClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    openFullView(view, button);
  }

  @Override
  public boolean onClick (View view, MediaWrapper wrapper) {
    openFullView(view, rippleButton != null ? rippleButton.firstButton() : null);
    return true;
  }

  private void openFullView (View view, @Nullable TGInlineKeyboard.Button button) {
    if (fullRichMessage != null) {
      showFullView(view, button, fullRichMessage);
      return;
    }
    if (isPendingStreamedMessage()) {
      // The bot is still writing: there is no message to fetch the full article from yet
      return;
    }
    final long chatId = msg.chatId, messageId = msg.id;
    final int contextId = button != null ? button.getContextId() : 0;
    if (button != null) {
      button.makeActive();
      button.showProgressDelayed();
    }
    tdlib.send(new TdApi.GetFullRichMessage(chatId, messageId), (fullMessage, error) -> runOnUiThreadOptional(() -> {
      if (button != null) {
        if (contextId != button.getContextId()) {
          return;
        }
        button.makeInactive();
      }
      if (error != null) {
        if (button != null) {
          button.showTooltip(view, TD.toErrorString(error));
        } else {
          UI.showToast(TD.toErrorString(error), Toast.LENGTH_SHORT);
        }
        return;
      }
      if (msg.chatId == chatId && msg.id == messageId) {
        fullRichMessage = fullMessage;
      }
      showFullView(view, button, fullMessage);
    }));
  }

  private void showFullView (View view, @Nullable TGInlineKeyboard.Button button, @NonNull TdApi.RichMessage fullMessage) {
    String name = !StringUtils.isEmpty(titleText) ? titleText : Lang.getString(R.string.RichMessage);
    InstantViewController c = new InstantViewController(controller().context(), tdlib);
    c.setArguments(InstantViewController.Args.forRichMessage(tdlib, msg.chatId, msg.id, name, fullMessage));
    try {
      c.show();
    } catch (UnsupportedOperationException e) {
      Log.w("Unsupported rich message block", e);
      if (button != null) {
        button.showTooltip(view, R.string.InstantViewUnsupported);
      }
      c.destroy();
    } catch (Throwable t) {
      Log.e("Unable to open rich message", t);
      if (button != null) {
        button.showTooltip(view, R.string.InstantViewError);
      }
      c.destroy();
    }
  }

  // Translation & summary of the preview

  @Nullable
  @Override
  protected TdApi.FormattedText getTextToTranslateImpl () {
    return previewTextToTranslate;
  }

  @Override
  protected void setTranslationResult (@Nullable TdApi.FormattedText text) {
    translatedText = text;
    setText();
    rebuildAndUpdateContent();
    super.setTranslationResult(text);
  }

  @Override
  protected boolean supportsSummary () {
    return true;
  }

  @Override
  protected void onSummaryVisibilityChanged () {
    setText();
    rebuildAndUpdateContent();
  }
}
