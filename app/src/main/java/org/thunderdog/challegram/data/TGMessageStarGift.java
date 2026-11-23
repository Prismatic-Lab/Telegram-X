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
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.SpannableStringBuilder;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.gif.GifFile;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.text.TextWrapper;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Renders star gifts ({@link TdApi.MessageGift}) and unique (upgraded) gifts ({@link TdApi.MessageUpgradedGift}).
 * Tapping the button shows gift info with "Display on my page"/"Hide" and "Convert to Stars" where allowed.
 */
public class TGMessageStarGift extends TGMessageGiveawayBase {
  private static final int STICKER_SIZE_DP = 120;

  @Nullable private TdApi.MessageGift gift;
  @Nullable private TdApi.MessageUpgradedGift upgradedGift;
  private boolean busy;

  public TGMessageStarGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageGift gift) {
    super(manager, msg);
    this.gift = gift;
  }

  public TGMessageStarGift (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.MessageUpgradedGift upgradedGift) {
    super(manager, msg);
    this.upgradedGift = upgradedGift;
  }

  // Data helpers

  @Nullable
  private TdApi.MessageSender senderId () {
    return gift != null ? gift.senderId : upgradedGift != null ? upgradedGift.senderId : null;
  }

  @Nullable
  private TdApi.MessageSender receiverId () {
    return gift != null ? gift.receiverId : upgradedGift != null ? upgradedGift.receiverId : null;
  }

  @Nullable
  private String receivedGiftId () {
    return gift != null ? gift.receivedGiftId : upgradedGift != null ? upgradedGift.receivedGiftId : null;
  }

  @Nullable
  private TdApi.FormattedText giftText () {
    return gift != null ? gift.text : upgradedGift != null ? upgradedGift.text : null;
  }

  private boolean isSaved () {
    return gift != null ? gift.isSaved : upgradedGift != null && upgradedGift.isSaved;
  }

  private void setSaved (boolean isSaved) {
    if (gift != null) {
      gift.isSaved = isSaved;
    } else if (upgradedGift != null) {
      upgradedGift.isSaved = isSaved;
    }
  }

  private boolean isReceivedByMe () {
    TdApi.MessageSender receiverId = receiverId();
    if (receiverId != null) {
      return tdlib.isSelfSender(receiverId);
    }
    return !msg.isOutgoing;
  }

  private boolean canManage () {
    if (StringUtils.isEmpty(receivedGiftId()) || !isReceivedByMe()) {
      return false;
    }
    if (gift != null) {
      return !gift.wasConverted && !gift.wasUpgraded && !gift.wasRefunded;
    }
    return upgradedGift != null;
  }

  private boolean canConvert () {
    return gift != null && canManage() && gift.sellStarCount > 0;
  }

  @Nullable
  private TdApi.Sticker sticker () {
    if (gift != null) {
      return gift.gift.sticker;
    } else if (upgradedGift != null && upgradedGift.gift.model != null) {
      return upgradedGift.gift.model.sticker;
    }
    return null;
  }

  private String giftTitle () {
    if (upgradedGift != null) {
      TdApi.UpgradedGift u = upgradedGift.gift;
      return Lang.getString(R.string.StarGiftUniqueName, u.title, Integer.toString(u.number));
    }
    return Lang.getString(R.string.StarGiftTitle);
  }

  private CharSequence headline () {
    TdApi.MessageSender senderId = senderId();
    TdApi.MessageSender receiverId = receiverId();
    if (!isReceivedByMe() && receiverId != null) {
      return Lang.getString(R.string.StarGiftYouSent, tdlib.senderName(receiverId));
    }
    if (senderId == null) {
      return Lang.getString(R.string.StarGiftUnknownSender);
    }
    if (tdlib.isSelfSender(senderId)) {
      return Lang.getString(R.string.StarGiftYouBought);
    }
    return Lang.getString(R.string.StarGiftSentYou, tdlib.senderName(senderId));
  }

  // Layout

  @Override
  protected int onBuildContent (int maxWidth) {
    content = new Content(maxWidth - Screen.dp(CONTENT_PADDING_DP * 2));

    content.padding(Screen.dp(16));
    TdApi.Sticker sticker = sticker();
    int[] backdrop = null;
    if (upgradedGift != null && upgradedGift.gift.backdrop != null && upgradedGift.gift.backdrop.colors != null) {
      TdApi.UpgradedGiftBackdropColors colors = upgradedGift.gift.backdrop.colors;
      backdrop = new int[] {0xff000000 | colors.centerColor, 0xff000000 | colors.edgeColor};
    }
    if (sticker != null) {
      content.add(new ContentSticker(new TGStickerObj(tdlib, sticker, null, sticker.fullType), Screen.dp(STICKER_SIZE_DP), backdrop));
    } else {
      content.add(new ContentDrawable(R.drawable.baseline_gift_72));
    }
    content.padding(Screen.dp(12));

    content.add(Lang.boldify(upgradedGift != null ? giftTitle() : headline().toString()), getTextColorSet(), currentViews);
    content.padding(Screen.dp(6));

    if (upgradedGift != null) {
      content.add(headline(), getTextColorSet(), currentViews);
      TdApi.UpgradedGift u = upgradedGift.gift;
      StringBuilder attrs = new StringBuilder();
      if (u.model != null) {
        attrs.append(Lang.getString(R.string.StarGiftModel, u.model.name));
      }
      if (u.backdrop != null) {
        if (attrs.length() > 0) attrs.append('\n');
        attrs.append(Lang.getString(R.string.StarGiftBackdrop, u.backdrop.name));
      }
      if (u.symbol != null) {
        if (attrs.length() > 0) attrs.append('\n');
        attrs.append(Lang.getString(R.string.StarGiftSymbol, u.symbol.name));
      }
      if (attrs.length() > 0) {
        content.padding(Screen.dp(6));
        content.add(attrs.toString(), () -> Theme.getColor(ColorId.textLight), currentViews);
      }
    } else if (gift != null) {
      content.add(Lang.boldify(Lang.plural(R.string.xStarGiftValue, gift.gift.starCount)), getTextColorSet(), currentViews);
      CharSequence status = null;
      if (gift.wasRefunded) {
        status = Lang.getString(R.string.StarGiftRefundedInfo);
      } else if (gift.wasConverted) {
        status = Lang.getString(R.string.StarGiftConverted);
      } else if (gift.wasUpgraded) {
        status = Lang.getString(R.string.StarGiftUpgradedInfo);
      }
      if (status != null) {
        content.padding(Screen.dp(6));
        content.add(status, () -> Theme.getColor(ColorId.textLight), currentViews);
      }
    }

    TdApi.FormattedText text = giftText();
    if (!Td.isEmpty(text)) {
      content.padding(Screen.dp(10));
      content.add(new TextWrapper(tdlib, text, getGiveawayTextStyleProvider(), getTextColorSet(), new TdlibUi.UrlOpenParameters().sourceMessage(this), null).setViewProvider(currentViews));
    }

    invalidateGiveawayReceiver();
    return content.getHeight();
  }

  @Override
  protected String getButtonText () {
    return Lang.getString(R.string.StarGiftView);
  }

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    return applyContent(newContent);
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    return applyContent(newContent);
  }

  private boolean applyContent (TdApi.MessageContent newContent) {
    switch (newContent.getConstructor()) {
      case TdApi.MessageGift.CONSTRUCTOR:
        if (gift == null) return false;
        this.gift = (TdApi.MessageGift) newContent;
        break;
      case TdApi.MessageUpgradedGift.CONSTRUCTOR:
        if (upgradedGift == null) return false;
        this.upgradedGift = (TdApi.MessageUpgradedGift) newContent;
        break;
      default:
        return false;
    }
    rebuildAndUpdateContent();
    return true;
  }

  // Popup

  @Override
  public void onClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    showGiftInfo();
  }

  @Override
  public boolean onLongClick (View view, TGInlineKeyboard keyboard, TGInlineKeyboard.Button button) {
    return false;
  }

  private void showGiftInfo () {
    ViewController<?> c = controller();
    if (c == null || isDestroyed()) {
      return;
    }
    final ViewController.Options.Builder b = new ViewController.Options.Builder();
    b.title(giftTitle());

    SpannableStringBuilder info = new SpannableStringBuilder();
    info.append(headline());
    if (gift != null) {
      info.append("\n");
      info.append(Lang.plural(R.string.xStarGiftValue, gift.gift.starCount));
      if (gift.isPrivate) {
        info.append("\n").append(Lang.getString(R.string.StarGiftPrivate));
      }
      if (gift.canBeUpgraded && !gift.wasUpgraded) {
        info.append("\n").append(Lang.getString(R.string.StarGiftCanUpgrade));
      }
    } else if (upgradedGift != null) {
      TdApi.UpgradedGift u = upgradedGift.gift;
      if (u.model != null) info.append("\n").append(Lang.getString(R.string.StarGiftModel, u.model.name));
      if (u.backdrop != null) info.append("\n").append(Lang.getString(R.string.StarGiftBackdrop, u.backdrop.name));
      if (u.symbol != null) info.append("\n").append(Lang.getString(R.string.StarGiftSymbol, u.symbol.name));
    }
    TdApi.FormattedText text = giftText();
    if (!Td.isEmpty(text)) {
      info.append("\n\n").append(text.text);
    }
    b.info(info);

    if (canManage()) {
      b.subtitle(new ViewController.OptionItem(0, Lang.getString(isSaved() ? R.string.StarGiftVisible : R.string.StarGiftNotVisible), ViewController.OptionColor.BLUE, R.drawable.baseline_info_24));
      b.item(new ViewController.OptionItem(R.id.btn_giftToggleSaved, Lang.getString(isSaved() ? R.string.StarGiftHideFromPage : R.string.StarGiftDisplayOnPage), ViewController.OptionColor.NORMAL, R.drawable.baseline_visibility_24));
    }
    if (canConvert()) {
      b.item(new ViewController.OptionItem(R.id.btn_giftConvert, Lang.getString(R.string.StarGiftConvert, Lang.plural(R.string.xStarGiftValue, gift.sellStarCount)), ViewController.OptionColor.NORMAL, R.drawable.baseline_premium_star_24));
    }
    b.item(new ViewController.OptionItem(R.id.btn_cancel, Lang.getString(R.string.StarGiftClose), ViewController.OptionColor.NORMAL, R.drawable.baseline_cancel_24));
    c.showOptions(b.build(), (v, id) -> {
      if (id == R.id.btn_giftToggleSaved) {
        toggleSaved();
      } else if (id == R.id.btn_giftConvert) {
        confirmConvert();
      }
      return true;
    });
  }

  private void toggleSaved () {
    final String receivedGiftId = receivedGiftId();
    if (busy || StringUtils.isEmpty(receivedGiftId)) {
      return;
    }
    busy = true;
    final boolean newValue = !isSaved();
    tdlib.send(new TdApi.ToggleGiftIsSaved(receivedGiftId, newValue), (ok, error) -> UI.post(() -> {
      busy = false;
      if (error != null) {
        UI.showError(error);
      } else if (!isDestroyed()) {
        setSaved(newValue);
        rebuildAndUpdateContent();
      }
    }));
  }

  private void confirmConvert () {
    ViewController<?> c = controller();
    if (c == null || gift == null) {
      return;
    }
    final String value = Lang.plural(R.string.xStarGiftValue, gift.sellStarCount);
    c.showOptions(Lang.getString(R.string.StarGiftConvertConfirm, value),
      new int[] {R.id.btn_giftConvert, R.id.btn_cancel},
      new String[] {Lang.getString(R.string.StarGiftConvert, value), Lang.getString(R.string.Cancel)},
      new int[] {ViewController.OptionColor.RED, ViewController.OptionColor.NORMAL},
      new int[] {R.drawable.baseline_premium_star_24, R.drawable.baseline_cancel_24},
      (v, id) -> {
        if (id == R.id.btn_giftConvert) {
          convert();
        }
        return true;
      });
  }

  private void convert () {
    final String receivedGiftId = receivedGiftId();
    if (busy || StringUtils.isEmpty(receivedGiftId)) {
      return;
    }
    busy = true;
    tdlib.send(new TdApi.SellGift("", receivedGiftId), (ok, error) -> UI.post(() -> {
      busy = false;
      if (error != null) {
        UI.showError(error);
      } else if (!isDestroyed() && gift != null) {
        gift.wasConverted = true;
        rebuildAndUpdateContent();
      }
    }));
  }

  // Sticker part

  private static class ContentSticker extends ContentPart {
    private final TGStickerObj sticker;
    private final int size;
    @Nullable private final int[] backdropColors;
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int width;
    private int lastShaderX = Integer.MIN_VALUE, lastShaderY = Integer.MIN_VALUE;

    ContentSticker (TGStickerObj sticker, int size, @Nullable int[] backdropColors) {
      this.sticker = sticker;
      this.size = size;
      this.backdropColors = backdropColors;
    }

    @Override
    public void build (int width) {
      this.width = width;
    }

    @Override
    public int getWidth () {
      return size;
    }

    @Override
    public int getHeight () {
      return size;
    }

    @Override
    public void draw (Canvas c, MessageView v, int x, int y) {
      final int left = x + (width - size) / 2;
      if (backdropColors != null) {
        float cx = left + size / 2f, cy = y + size / 2f;
        if (lastShaderX != (int) cx || lastShaderY != (int) cy) {
          lastShaderX = (int) cx;
          lastShaderY = (int) cy;
          backdropPaint.setShader(new RadialGradient(cx, cy, size / 2f, backdropColors[0], backdropColors[1], Shader.TileMode.CLAMP));
        }
        c.drawCircle(cx, cy, size / 2f, backdropPaint);
      }
      ComplexReceiver receiver = v.getGiveawayAvatarsReceiver();
      ImageReceiver image = receiver.getImageReceiver(0);
      GifReceiver gif = receiver.getGifReceiver(0);
      final int inset = backdropColors != null ? size / 8 : 0;
      image.setBounds(left + inset, y + inset, left + size - inset, y + size - inset);
      gif.setBounds(left + inset, y + inset, left + size - inset, y + size - inset);
      if (gif.needPlaceholder()) {
        image.draw(c);
      }
      gif.draw(c);
    }

    @Override
    public void requestFiles (ComplexReceiver r) {
      ImageFile imageFile = sticker.getImage();
      GifFile animation = sticker.getFullAnimation();
      r.getImageReceiver(0).requestFile(imageFile);
      r.getGifReceiver(0).requestFile(animation);
    }
  }
}
