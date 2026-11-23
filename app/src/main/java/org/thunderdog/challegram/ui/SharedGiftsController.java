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
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.inline.CustomResultView;
import org.thunderdog.challegram.component.sticker.TGStickerObj;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.InlineResult;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.ImageReceiver;
import org.thunderdog.challegram.loader.gif.GifReceiver;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.EmptySmartView;

import java.util.ArrayList;
import java.util.List;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Profile tab with gifts received by a user or a channel ({@link TdApi.GetReceivedGifts}), shown as a grid.
 * Tapping a gift shows the same kind of info popup as the gift message bubble ({@code TGMessageStarGift}).
 */
public class SharedGiftsController extends SharedBaseController<SharedGiftsController.GiftRow> {
  private static final int PAGE_SIZE = 30;

  private volatile String nextOffset = "";
  private volatile int totalCount = -1;

  public SharedGiftsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.ProfileGifts);
  }

  @Override
  public int getIcon () {
    return R.drawable.baseline_gift_outline_24;
  }

  private boolean isOwnProfile () {
    return tdlib.isSelfChat(getChatId());
  }

  @Override
  protected TdApi.Function<?> buildRequest (long chatId, TdApi.MessageTopic topicId, String query, long offset, String secretOffset, int limit) {
    limit = Math.max(PAGE_SIZE, limit);
    limit += (ProfileGridRow.COLUMN_COUNT - limit % ProfileGridRow.COLUMN_COUNT) % ProfileGridRow.COLUMN_COUNT;
    String offsetString = offset == 0 ? "" : nextOffset;
    return new TdApi.GetReceivedGifts(
      "", tdlib.sender(chatId), 0,
      false, false, false, false, false, false, false, false,
      false,
      offsetString != null ? offsetString : "", limit
    );
  }

  @Override
  protected int calculateInitialLoadCount () {
    return PAGE_SIZE;
  }

  @Nullable
  @Override
  protected ArrayList<GiftRow> parseCustomResult (TdApi.Object object) {
    if (object.getConstructor() != TdApi.ReceivedGifts.CONSTRUCTOR) {
      return null;
    }
    TdApi.ReceivedGifts gifts = (TdApi.ReceivedGifts) object;
    this.nextOffset = gifts.nextOffset;
    this.totalCount = gifts.totalCount;
    ArrayList<TdApi.ReceivedGift> list = new ArrayList<>(gifts.gifts.length);
    for (TdApi.ReceivedGift gift : gifts.gifts) {
      if (gift != null && gift.gift != null) {
        list.add(gift);
      }
    }
    return ProfileGridRow.<TdApi.ReceivedGift, GiftRow>split(list, cells -> new GiftRow(context, tdlib, cells));
  }

  @Override
  protected boolean supportsLoadingMore (boolean isMore) {
    return !StringUtils.isEmpty(nextOffset);
  }

  @Override
  protected long getCurrentOffset (ArrayList<GiftRow> data, long emptyValue) {
    if (data == null || data.isEmpty()) {
      return emptyValue;
    }
    int count = 0;
    for (GiftRow row : data) {
      count += row.getCellCount();
    }
    return count;
  }

  @Override
  protected GiftRow parseObject (TdApi.Object object) {
    return null;
  }

  @Override
  protected boolean supportsMessageContent () {
    return false;
  }

  @Override
  protected boolean needDateSectionSplitting () {
    return false;
  }

  @Override
  protected boolean needItemSeparators () {
    return false;
  }

  @Override
  protected boolean canSearch () {
    return false;
  }

  @Override
  protected boolean needsDefaultLongPress () {
    return false;
  }

  @Override
  protected int provideViewType () {
    return ListItem.TYPE_CUSTOM_INLINE;
  }

  @Override
  protected int getEmptySmartMode () {
    return EmptySmartView.MODE_EMPTY_RESULTS;
  }

  @Override
  protected CharSequence buildTotalCount (ArrayList<GiftRow> data) {
    int count = 0;
    if (data != null) {
      for (GiftRow row : data) {
        count += row.getCellCount();
      }
    }
    return Lang.pluralBold(R.string.xProfileGifts, Math.max(count, totalCount));
  }

  @Override
  public void onClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item == null || item.getViewType() != ListItem.TYPE_CUSTOM_INLINE || !(item.getData() instanceof GiftRow)) {
      return;
    }
    GiftRow row = (GiftRow) item.getData();
    TdApi.ReceivedGift gift = row.findTouchedCell();
    if (gift != null) {
      showGiftInfo(row, gift);
    }
  }

  // Info popup, mirrors TGMessageStarGift#showGiftInfo

  private static String giftTitle (TdApi.ReceivedGift gift) {
    if (gift.gift.getConstructor() == TdApi.SentGiftUpgraded.CONSTRUCTOR) {
      TdApi.UpgradedGift u = ((TdApi.SentGiftUpgraded) gift.gift).gift;
      return Lang.getString(R.string.StarGiftUniqueName, u.title, Integer.toString(u.number));
    }
    return Lang.getString(R.string.StarGiftTitle);
  }

  private void showGiftInfo (GiftRow row, TdApi.ReceivedGift gift) {
    final ViewController.Options.Builder b = new ViewController.Options.Builder();
    b.title(giftTitle(gift));

    SpannableStringBuilder info = new SpannableStringBuilder();
    if (gift.senderId != null) {
      info.append(Lang.getString(R.string.ProfileGiftFrom, tdlib.senderName(gift.senderId)));
    } else {
      info.append(Lang.getString(R.string.ProfileGiftFromUnknown));
    }
    if (gift.date != 0) {
      info.append("\n").append(Lang.getRelativeTimestamp(gift.date, java.util.concurrent.TimeUnit.SECONDS));
    }
    switch (gift.gift.getConstructor()) {
      case TdApi.SentGiftRegular.CONSTRUCTOR: {
        TdApi.Gift g = ((TdApi.SentGiftRegular) gift.gift).gift;
        info.append("\n").append(Lang.plural(R.string.xStarGiftValue, g.starCount));
        if (gift.isPrivate) {
          info.append("\n").append(Lang.getString(R.string.StarGiftPrivate));
        }
        if (gift.canBeUpgraded) {
          info.append("\n").append(Lang.getString(R.string.StarGiftCanUpgrade));
        }
        break;
      }
      case TdApi.SentGiftUpgraded.CONSTRUCTOR: {
        TdApi.UpgradedGift u = ((TdApi.SentGiftUpgraded) gift.gift).gift;
        if (u.model != null) info.append("\n").append(Lang.getString(R.string.StarGiftModel, u.model.name));
        if (u.backdrop != null) info.append("\n").append(Lang.getString(R.string.StarGiftBackdrop, u.backdrop.name));
        if (u.symbol != null) info.append("\n").append(Lang.getString(R.string.StarGiftSymbol, u.symbol.name));
        break;
      }
    }
    if (gift.wasRefunded) {
      info.append("\n").append(Lang.getString(R.string.StarGiftRefundedInfo));
    }
    if (!Td.isEmpty(gift.text)) {
      info.append("\n\n").append(gift.text.text);
    }
    b.info(info);

    final boolean canManage = isOwnProfile() && !StringUtils.isEmpty(gift.receivedGiftId) && !gift.wasRefunded;
    if (canManage) {
      b.subtitle(new ViewController.OptionItem(0, Lang.getString(gift.isSaved ? R.string.StarGiftVisible : R.string.StarGiftNotVisible), ViewController.OptionColor.BLUE, R.drawable.baseline_info_24));
      b.item(new ViewController.OptionItem(R.id.btn_giftToggleSaved, Lang.getString(gift.isSaved ? R.string.StarGiftHideFromPage : R.string.StarGiftDisplayOnPage), ViewController.OptionColor.NORMAL, R.drawable.baseline_visibility_24));
    }
    b.item(new ViewController.OptionItem(R.id.btn_cancel, Lang.getString(R.string.StarGiftClose), ViewController.OptionColor.NORMAL, R.drawable.baseline_cancel_24));
    showOptions(b.build(), (v, id) -> {
      if (id == R.id.btn_giftToggleSaved && canManage) {
        final boolean newValue = !gift.isSaved;
        tdlib.send(new TdApi.ToggleGiftIsSaved(gift.receivedGiftId, newValue), (ok, error) -> runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            gift.isSaved = newValue;
            row.invalidate();
          }
        }));
      }
      return true;
    });
  }

  // Row

  public static class GiftRow extends ProfileGridRow<TdApi.ReceivedGift> {
    private final TGStickerObj[] stickers;
    private final Paint[] backdropPaints;
    private final int[] shaderKeys;

    public GiftRow (BaseActivity context, Tdlib tdlib, List<TdApi.ReceivedGift> cells) {
      super(context, tdlib, InlineResult.TYPE_STICKER, cells);
      this.stickers = new TGStickerObj[cells.size()];
      this.backdropPaints = new Paint[cells.size()];
      this.shaderKeys = new int[cells.size()];
      for (int i = 0; i < cells.size(); i++) {
        TdApi.Sticker sticker = sticker(cells.get(i));
        if (sticker != null) {
          stickers[i] = new TGStickerObj(tdlib, sticker, null, sticker.fullType);
        }
      }
    }

    @Nullable
    private static TdApi.Sticker sticker (TdApi.ReceivedGift gift) {
      switch (gift.gift.getConstructor()) {
        case TdApi.SentGiftRegular.CONSTRUCTOR:
          return ((TdApi.SentGiftRegular) gift.gift).gift.sticker;
        case TdApi.SentGiftUpgraded.CONSTRUCTOR: {
          TdApi.UpgradedGift u = ((TdApi.SentGiftUpgraded) gift.gift).gift;
          return u.model != null ? u.model.sticker : null;
        }
      }
      return null;
    }

    @Override
    protected int measureCellHeight (int cellWidth) {
      return cellWidth + Screen.dp(8f);
    }

    @Override
    protected void requestCell (ComplexReceiver receiver, int index, TdApi.ReceivedGift cell) {
      TGStickerObj sticker = stickers[index];
      if (sticker != null) {
        receiver.getImageReceiver(index).requestFile(sticker.getImage());
        receiver.getGifReceiver(index).requestFile(sticker.getPreviewAnimation());
      } else {
        receiver.getImageReceiver(index).requestFile(null);
        receiver.getGifReceiver(index).requestFile(null);
      }
    }

    @Override
    protected void drawCell (CustomResultView view, Canvas c, ComplexReceiver receiver, int index, TdApi.ReceivedGift gift, int left, int top, int right, int bottom) {
      final float radius = Screen.dp(10f);
      final RectF rectF = new RectF(left, top, right, bottom);
      TdApi.UpgradedGift upgraded = gift.gift.getConstructor() == TdApi.SentGiftUpgraded.CONSTRUCTOR ? ((TdApi.SentGiftUpgraded) gift.gift).gift : null;
      if (upgraded != null && upgraded.backdrop != null && upgraded.backdrop.colors != null) {
        TdApi.UpgradedGiftBackdropColors colors = upgraded.backdrop.colors;
        int key = left * 31 + top * 17 + (bottom - top);
        if (backdropPaints[index] == null || shaderKeys[index] != key) {
          Paint paint = backdropPaints[index] != null ? backdropPaints[index] : new Paint(Paint.ANTI_ALIAS_FLAG);
          paint.setShader(new RadialGradient((left + right) / 2f, (top + bottom) / 2f, Math.max(1f, (right - left) * .75f), 0xff000000 | colors.centerColor, 0xff000000 | colors.edgeColor, Shader.TileMode.CLAMP));
          backdropPaints[index] = paint;
          shaderKeys[index] = key;
        }
        c.drawRoundRect(rectF, radius, radius, backdropPaints[index]);
      } else {
        c.drawRoundRect(rectF, radius, radius, Paints.fillingPaint(Theme.backgroundColor()));
      }

      final boolean dim = !gift.isSaved;
      int width = right - left;
      int stickerSize = (int) (width * .62f);
      int stickerLeft = left + (width - stickerSize) / 2;
      int stickerTop = top + Screen.dp(10f);
      ImageReceiver image = receiver.getImageReceiver(index);
      GifReceiver gif = receiver.getGifReceiver(index);
      image.setBounds(stickerLeft, stickerTop, stickerLeft + stickerSize, stickerTop + stickerSize);
      gif.setBounds(stickerLeft, stickerTop, stickerLeft + stickerSize, stickerTop + stickerSize);
      if (dim) {
        image.setPaintAlpha(.45f);
        gif.setPaintAlpha(.45f);
      }
      if (gif.needPlaceholder()) {
        image.draw(c);
      }
      gif.draw(c);
      if (dim) {
        image.restorePaintAlpha();
        gif.restorePaintAlpha();
      }

      String label;
      int textColor;
      if (upgraded != null) {
        label = "#" + upgraded.number;
        textColor = upgraded.backdrop != null && upgraded.backdrop.colors != null ? 0xff000000 | upgraded.backdrop.colors.textColor : Theme.textAccentColor();
      } else if (gift.gift.getConstructor() == TdApi.SentGiftRegular.CONSTRUCTOR) {
        label = "\u2B50 " + Strings.buildCounter(((TdApi.SentGiftRegular) gift.gift).gift.starCount);
        textColor = Theme.textAccentColor();
      } else {
        label = null;
        textColor = 0;
      }
      if (label != null) {
        TextPaint paint = Paints.getMediumTextPaint(12f, textColor, false);
        CharSequence trimmed = TextUtils.ellipsize(label, paint, width - Screen.dp(8f), TextUtils.TruncateAt.END);
        float textWidth = paint.measureText(trimmed, 0, trimmed.length());
        c.drawText(trimmed, 0, trimmed.length(), left + (width - textWidth) / 2f, bottom - Screen.dp(8f), paint);
      }
    }
  }
}
