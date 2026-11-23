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
import android.graphics.Rect;
import android.graphics.RectF;
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
import org.thunderdog.challegram.core.Background;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileLocal;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.Receiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.InstantViewController;
import org.thunderdog.challegram.unsorted.ModProfileFeatures;
import org.thunderdog.challegram.util.text.FormattedText;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.util.text.TextWrapper;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableData;
import tgx.td.Td;

/**
 * Rich message (article): a preview of its first blocks, cut off, and a button
 * that expands the whole content inside the bubble (TGX Mod; it is rendered in portions,
 * with "Show more" and "Collapse" buttons). The Instant View screen stays available
 * via a long press on the button and the message menu ("Open in full screen").
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

  // Inline expansion

  /** Initial amount of content (in characters, media count as ~400) rendered when expanded. */
  private static final int EXPANDED_INITIAL_BUDGET = 5000;
  /** Amount added by each "Show more". */
  private static final int EXPANDED_BUDGET_STEP = 10000;

  private static final class ExpandedPart {
    final RichMessageInlineContent.Part part;
    @Nullable TextWrapper text;
    @Nullable ImageFile preview, full;
    int mediaWidth, mediaHeight;
    boolean isVideo;
    int top, width, height;

    ExpandedPart (RichMessageInlineContent.Part part) {
      this.part = part;
    }
  }

  private boolean expanded;
  private int expandContextId;
  private @Nullable List<RichMessageInlineContent.Part> expandedParts;
  private final ArrayList<ExpandedPart> visibleParts = new ArrayList<>();
  private int expandedBudget;
  private @Nullable TGInlineKeyboard showMoreButton;
  private int showMoreButtonY = -1;
  private int lastContentX, lastContentY;
  private int pressedMediaIndex = -1;
  private final Rect clipRect = new Rect();

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
    resetExpanded();
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

    if (expanded) {
      y = buildExpandedContent(maxWidth, y);
    } else if (text != null) {
      if (y > 0) {
        y += Screen.dp(4f);
      }
      text.prepare(maxWidth);
      textTop = y;
      y += text.getHeight();
    }

    if (expanded && expandedParts != null && visibleParts.size() < expandedParts.size()) {
      if (showMoreButton == null) {
        showMoreButton = new TGInlineKeyboard(this, false);
        showMoreButton.setViewProvider(currentViews);
      }
      showMoreButton.setCustom(R.drawable.baseline_keyboard_arrow_down_24, Lang.getString(R.string.RichMessageShowMore), maxWidth, false, this);
      if (y > 0) {
        y += Screen.dp(10f);
      }
      showMoreButtonY = y;
      y += TGInlineKeyboard.getButtonHeight();
    } else {
      showMoreButtonY = -1;
    }

    if (rippleButton == null) {
      rippleButton = new TGInlineKeyboard(this, false);
      rippleButton.setViewProvider(currentViews);
    }
    if (expanded) {
      rippleButton.setCustom(R.drawable.baseline_keyboard_arrow_up_24, Lang.getString(R.string.RichMessageCollapse), maxWidth, false, this);
    } else {
      rippleButton.setCustom(R.drawable.deproko_baseline_instantview_24, Lang.getString(R.string.RichMessageRead), maxWidth, false, this);
    }
    if (y > 0) {
      y += Screen.dp(showMoreButtonY != -1 ? 6f : 10f);
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
    if (expanded) {
      textMediaReceiver.clear();
      for (int i = 0; i < visibleParts.size(); i++) {
        TextWrapper partText = visibleParts.get(i).text;
        if (partText != null) {
          partText.requestMedia(textMediaReceiver, (i + 1) * EXPANDED_MEDIA_KEY_STEP, EXPANDED_MEDIA_KEY_STEP);
        }
      }
    } else if (text != null) {
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
    lastContentX = startX;
    lastContentY = startY;
    if (title != null) {
      title.draw(c, startX, startY + titleTop, null, 1f);
    }
    if (expanded) {
      drawExpandedContent(view, c, startX, startY);
    } else if (text != null) {
      text.draw(c, startX, startY + textTop, null, getTranslationLoadingAlphaValue(), view.getTextMediaReceiver());
    }
    if (showMoreButton != null && showMoreButtonY != -1) {
      showMoreButton.draw(view, c, startX, startY + showMoreButtonY);
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
    if (showMoreButton != null && showMoreButtonY != -1 && showMoreButton.onTouchEvent(view, e)) {
      return true;
    }
    if (super.onTouchEvent(view, e)) {
      return true;
    }
    if (mediaWrapper != null && mediaWrapper.onTouchEvent(view, e)) {
      return true;
    }
    if (expanded) {
      return onExpandedTouchEvent(view, e);
    }
    return text != null && text.onTouchEvent(view, e);
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    boolean res = super.performLongPress(view, x, y);
    boolean a = rippleButton != null && rippleButton.performLongPress(view);
    boolean b = mediaWrapper != null && mediaWrapper.performLongPress(view);
    boolean t = !expanded && text != null && text.performLongPress(view);
    boolean m = showMoreButton != null && showMoreButtonY != -1 && showMoreButton.performLongPress(view);
    boolean p = false;
    if (expanded) {
      for (ExpandedPart part : visibleParts) {
        if (part.text != null && part.text.performLongPress(view)) {
          p = true;
        }
      }
    }
    pressedMediaIndex = -1;
    return a || b || t || m || p || res;
  }

  @Override
  protected void onMessageContainerDestroyed () {
    if (text != null) {
      text.performDestroy();
    }
    destroyVisibleParts();
  }

  // Full view

  @Override
  public void onClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    if (keyboard == showMoreButton) {
      showMore();
    } else if (expanded) {
      collapse();
    } else if (ModProfileFeatures.richMessageInlineEnabled()) {
      expand(view, button);
    } else {
      openFullView(view, button);
    }
  }

  @Override
  public boolean onLongClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    if (keyboard == rippleButton) {
      openFullView(view, button);
      return true;
    }
    return false;
  }

  /** Opens the article in the Instant View screen; used by the message menu. */
  public void openFullScreen (@Nullable View view) {
    openFullView(view, null);
  }

  @Override
  public boolean onClick (View view, MediaWrapper wrapper) {
    openFullView(view, rippleButton != null ? rippleButton.firstButton() : null);
    return true;
  }

  private void openFullView (@Nullable View view, @Nullable TGInlineKeyboard.Button button) {
    withFullMessage(view, button, fullMessage -> showFullView(view, button, fullMessage));
  }

  private void withFullMessage (@Nullable View view, @Nullable TGInlineKeyboard.Button button, RunnableData<TdApi.RichMessage> callback) {
    if (fullRichMessage != null) {
      callback.runWithData(fullRichMessage);
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
        if (button != null && view != null) {
          button.showTooltip(view, TD.toErrorString(error));
        } else {
          UI.showToast(TD.toErrorString(error), Toast.LENGTH_SHORT);
        }
        return;
      }
      if (msg.chatId == chatId && msg.id == messageId) {
        fullRichMessage = fullMessage;
      }
      callback.runWithData(fullMessage);
    }));
  }

  private void showFullView (@Nullable View view, @Nullable TGInlineKeyboard.Button button, @NonNull TdApi.RichMessage fullMessage) {
    String name = !StringUtils.isEmpty(titleText) ? titleText : Lang.getString(R.string.RichMessage);
    InstantViewController c = new InstantViewController(controller().context(), tdlib);
    c.setArguments(InstantViewController.Args.forRichMessage(tdlib, msg.chatId, msg.id, name, fullMessage));
    try {
      c.show();
    } catch (UnsupportedOperationException e) {
      Log.w("Unsupported rich message block", e);
      if (button != null && view != null) {
        button.showTooltip(view, R.string.InstantViewUnsupported);
      }
      c.destroy();
    } catch (Throwable t) {
      Log.e("Unable to open rich message", t);
      if (button != null && view != null) {
        button.showTooltip(view, R.string.InstantViewError);
      }
      c.destroy();
    }
  }

  // Inline expansion

  private static final long EXPANDED_MEDIA_KEY_STEP = 100000L;

  private void expand (View view, @Nullable TGInlineKeyboard.Button button) {
    withFullMessage(view, button, fullMessage -> {
      if (expandedParts != null) {
        applyExpanded(true);
        return;
      }
      final int contextId = ++expandContextId;
      final ViewController<?> context = controller();
      final TdlibUi.UrlOpenParameters openParameters = openParameters();
      final String skipTitle = titleText;
      final boolean skipCover = coverBlock != null;
      if (button != null) {
        button.makeActive();
        button.showProgressDelayed();
      }
      final int buttonContextId = button != null ? button.getContextId() : 0;
      Background.instance().post(() -> {
        List<RichMessageInlineContent.Part> parts;
        try {
          parts = RichMessageInlineContent.build(context, fullMessage, openParameters, skipTitle, skipCover);
        } catch (Throwable t) {
          Log.e("Unable to build inline rich message", t);
          parts = null;
        }
        final List<RichMessageInlineContent.Part> result = parts;
        runOnUiThreadOptional(() -> {
          if (button != null && button.getContextId() == buttonContextId) {
            button.makeInactive();
          }
          if (contextId != expandContextId || isDestroyed()) {
            return;
          }
          if (result == null || result.isEmpty()) {
            // Nothing that can be shown inline, fall back to the full screen view
            showFullView(view, button, fullMessage);
            return;
          }
          expandedParts = result;
          applyExpanded(true);
        });
      });
    });
  }

  private void applyExpanded (boolean expanded) {
    if (expanded) {
      this.expanded = true;
      this.expandedBudget = EXPANDED_INITIAL_BUDGET;
      prepareVisibleParts();
    } else {
      this.expanded = false;
      destroyVisibleParts();
    }
    rebuildAndUpdateContent();
    invalidateTextMediaReceiver();
    invalidateGiveawayReceiver();
  }

  private void collapseImpl () {
    expandContextId++;
    expanded = false;
    destroyVisibleParts();
  }

  private void collapse () {
    if (expanded) {
      applyExpanded(false);
    }
  }

  private void resetExpanded () {
    collapseImpl();
    expandedParts = null;
  }

  private void showMore () {
    if (!expanded || expandedParts == null) {
      return;
    }
    expandedBudget += EXPANDED_BUDGET_STEP;
    prepareVisibleParts();
    rebuildAndUpdateContent();
    invalidateTextMediaReceiver();
    invalidateGiveawayReceiver();
  }

  private void destroyVisibleParts () {
    for (ExpandedPart part : visibleParts) {
      if (part.text != null) {
        part.text.performDestroy();
      }
    }
    visibleParts.clear();
    pressedMediaIndex = -1;
    touchedTextIndex = -1;
  }

  private void prepareVisibleParts () {
    if (expandedParts == null) {
      return;
    }
    int weight = 0;
    for (ExpandedPart part : visibleParts) {
      weight += part.part.getWeight();
    }
    while (visibleParts.size() < expandedParts.size()) {
      RichMessageInlineContent.Part part = expandedParts.get(visibleParts.size());
      if (!visibleParts.isEmpty() && weight + part.getWeight() > expandedBudget) {
        break;
      }
      visibleParts.add(newExpandedPart(part));
      weight += part.getWeight();
    }
  }

  private ExpandedPart newExpandedPart (RichMessageInlineContent.Part part) {
    ExpandedPart expandedPart = new ExpandedPart(part);
    if (part.text != null) {
      final TextWrapper.TextMediaListener mediaListener = (wrapper, updatedText, specificMedia) -> {
        if (expanded && expandedPart.text == wrapper) {
          invalidateTextMediaReceiver(updatedText, specificMedia);
        }
      };
      expandedPart.text = new TextWrapper(part.text.text, getTextStyleProvider(), getTextColorSet(), part.text.entities, mediaListener)
        .setClickCallback(clickCallback());
      expandedPart.text.setViewProvider(currentViews);
    } else if (part.media != null) {
      TdApi.Minithumbnail minithumbnail = null;
      switch (part.media.getConstructor()) {
        case TdApi.PageBlockPhoto.CONSTRUCTOR: {
          TdApi.Photo photo = ((TdApi.PageBlockPhoto) part.media).photo;
          minithumbnail = photo.minithumbnail;
          TdApi.PhotoSize size = Td.findBiggest(photo);
          if (size != null) {
            // Avoid decoding huge images in the bubble
            TdApi.PhotoSize best = size;
            for (TdApi.PhotoSize s : photo.sizes) {
              if (Math.max(s.width, s.height) >= 800 && Math.max(s.width, s.height) < Math.max(best.width, best.height)) {
                best = s;
              }
            }
            expandedPart.full = new ImageFile(tdlib, best.photo);
            expandedPart.mediaWidth = best.width;
            expandedPart.mediaHeight = best.height;
          }
          break;
        }
        case TdApi.PageBlockAnimation.CONSTRUCTOR: {
          TdApi.Animation animation = ((TdApi.PageBlockAnimation) part.media).animation;
          minithumbnail = animation.minithumbnail;
          expandedPart.full = TD.toImageFile(tdlib, animation.thumbnail);
          expandedPart.mediaWidth = animation.width;
          expandedPart.mediaHeight = animation.height;
          expandedPart.isVideo = true;
          break;
        }
        case TdApi.PageBlockVideo.CONSTRUCTOR: {
          TdApi.Video video = ((TdApi.PageBlockVideo) part.media).video;
          minithumbnail = video.minithumbnail;
          expandedPart.full = TD.toImageFile(tdlib, video.thumbnail);
          expandedPart.mediaWidth = video.width;
          expandedPart.mediaHeight = video.height;
          expandedPart.isVideo = true;
          break;
        }
      }
      if (minithumbnail != null) {
        expandedPart.preview = new ImageFileLocal(minithumbnail);
        expandedPart.preview.setScaleType(ImageFile.CENTER_CROP);
      }
      if (expandedPart.full != null) {
        expandedPart.full.setScaleType(ImageFile.CENTER_CROP);
        expandedPart.full.setSize(Screen.dp(360f));
      }
    }
    return expandedPart;
  }

  private int buildExpandedContent (int maxWidth, int y) {
    for (ExpandedPart part : visibleParts) {
      if (y > 0) {
        y += Screen.dp(part.text != null ? 6f : 8f);
      }
      part.top = y;
      if (part.text != null) {
        part.text.prepare(maxWidth);
        part.width = maxWidth;
        part.height = part.text.getHeight();
      } else {
        int mediaWidth = Math.max(1, part.mediaWidth);
        int mediaHeight = Math.max(1, part.mediaHeight);
        if (part.mediaWidth == 0 || part.mediaHeight == 0) {
          mediaWidth = 16;
          mediaHeight = 9;
        }
        part.width = maxWidth;
        part.height = Math.max(Screen.dp(64f), Math.min((int) ((float) maxWidth * mediaHeight / mediaWidth), (int) (maxWidth * 1.25f)));
      }
      y += part.height;
    }
    return y;
  }

  private void drawExpandedContent (MessageView view, Canvas c, int startX, int startY) {
    if (!c.getClipBounds(clipRect)) {
      clipRect.set(Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
    }
    ComplexReceiver mediaReceiver = view.getGiveawayAvatarsReceiver();
    for (int i = 0; i < visibleParts.size(); i++) {
      ExpandedPart part = visibleParts.get(i);
      int top = startY + part.top;
      int bottom = top + part.height;
      if (bottom < clipRect.top || top > clipRect.bottom) {
        continue; // Off screen
      }
      if (part.text != null) {
        part.text.draw(c, startX, top, null, 1f, view.getTextMediaReceiver());
      } else {
        DoubleImageReceiver receiver = mediaReceiver.getPreviewReceiver(i);
        receiver.setRadius(Screen.dp(Theme.getImageRadius()));
        receiver.setBounds(startX, top, startX + part.width, bottom);
        if (receiver.needPlaceholder()) {
          RectF rectF = Paints.getRectF(startX, top, startX + part.width, bottom);
          c.drawRoundRect(rectF, Screen.dp(Theme.getImageRadius()), Screen.dp(Theme.getImageRadius()), Paints.fillingPaint(Theme.placeholderColor()));
        }
        receiver.draw(c);
        if (part.isVideo) {
          float cx = startX + part.width / 2f, cy = top + part.height / 2f;
          c.drawCircle(cx, cy, Screen.dp(22f), Paints.fillingPaint(0x66000000));
          android.graphics.Path path = new android.graphics.Path();
          float r = Screen.dp(9f);
          path.moveTo(cx - r * .6f, cy - r);
          path.lineTo(cx + r, cy);
          path.lineTo(cx - r * .6f, cy + r);
          path.close();
          c.drawPath(path, Paints.fillingPaint(0xffffffff));
        }
      }
    }
  }

  @Override
  public void requestGiveawayAvatars (ComplexReceiver complexReceiver, boolean isUpdate) {
    if (!expanded) {
      complexReceiver.clear();
      return;
    }
    for (int i = 0; i < visibleParts.size(); i++) {
      ExpandedPart part = visibleParts.get(i);
      if (part.text == null && (part.preview != null || part.full != null)) {
        complexReceiver.getPreviewReceiver(i).requestFile(part.preview, part.full);
      }
    }
  }

  private int findMediaPartAt (float x, float y) {
    for (int i = 0; i < visibleParts.size(); i++) {
      ExpandedPart part = visibleParts.get(i);
      if (part.text == null) {
        int left = lastContentX, top = lastContentY + part.top;
        if (x >= left && x < left + part.width && y >= top && y < top + part.height) {
          return i;
        }
      }
    }
    return -1;
  }

  private int touchedTextIndex = -1;

  private int findTextPartAt (float y) {
    for (int i = 0; i < visibleParts.size(); i++) {
      ExpandedPart part = visibleParts.get(i);
      int top = lastContentY + part.top;
      if (part.text != null && y >= top && y < top + part.height) {
        return i;
      }
    }
    return -1;
  }

  private boolean onExpandedTouchEvent (MessageView view, MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      touchedTextIndex = findTextPartAt(e.getY());
    }
    if (touchedTextIndex >= 0 && touchedTextIndex < visibleParts.size()) {
      TextWrapper partText = visibleParts.get(touchedTextIndex).text;
      if (partText != null && partText.onTouchEvent(view, e)) {
        return true;
      }
    }
    switch (e.getAction()) {
      case MotionEvent.ACTION_DOWN: {
        pressedMediaIndex = findMediaPartAt(e.getX(), e.getY());
        return pressedMediaIndex != -1;
      }
      case MotionEvent.ACTION_MOVE: {
        return pressedMediaIndex != -1;
      }
      case MotionEvent.ACTION_CANCEL: {
        boolean caught = pressedMediaIndex != -1;
        pressedMediaIndex = -1;
        return caught;
      }
      case MotionEvent.ACTION_UP: {
        int index = pressedMediaIndex;
        pressedMediaIndex = -1;
        if (index != -1 && findMediaPartAt(e.getX(), e.getY()) == index) {
          openFullView(view, null);
          return true;
        }
        return index != -1;
      }
    }
    return false;
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
    if (expanded) {
      collapseImpl();
    }
    setText();
    rebuildAndUpdateContent();
  }
}
