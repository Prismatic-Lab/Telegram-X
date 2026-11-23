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
package org.thunderdog.challegram.component.dialogs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.mediaview.StoryViewController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.support.RippleSupport;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.widget.AttachDelegate;

import me.vkryl.core.lambda.Destroyable;

/**
 * Horizontal row of chats with active stories, shown on top of the main chat list.
 * Avatars of chats with unread stories are surrounded by an accent ring, read ones by a gray ring.
 */
public final class StoriesBarView extends HorizontalScrollView implements AttachDelegate, Destroyable {
  public static final float DEFAULT_HEIGHT_DP = 92f;
  private static final float AVATAR_SIZE_DP = 54f;
  private static final float ITEM_WIDTH_DP = 72f;

  private final Tdlib tdlib;
  private final LinearLayout itemsLayout;
  @Nullable private ViewController<?> parentController;
  private TdApi.ChatActiveStories[] stories = new TdApi.ChatActiveStories[0];

  public StoriesBarView (Context context, Tdlib tdlib) {
    super(context);
    this.tdlib = tdlib;
    setHorizontalScrollBarEnabled(false);
    setOverScrollMode(View.OVER_SCROLL_NEVER);
    setFillViewport(false);
    itemsLayout = new LinearLayout(context);
    itemsLayout.setOrientation(LinearLayout.HORIZONTAL);
    itemsLayout.setPadding(Screen.dp(4f), 0, Screen.dp(4f), 0);
    itemsLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Screen.dp(DEFAULT_HEIGHT_DP)));
    addView(itemsLayout);
    setMinimumHeight(Screen.dp(DEFAULT_HEIGHT_DP));
  }

  public void setParentController (@Nullable ViewController<?> parentController) {
    this.parentController = parentController;
  }

  public void setStories (@NonNull TdApi.ChatActiveStories[] stories) {
    this.stories = stories;
    final int count = stories.length;
    while (itemsLayout.getChildCount() > count) {
      View view = itemsLayout.getChildAt(itemsLayout.getChildCount() - 1);
      itemsLayout.removeViewAt(itemsLayout.getChildCount() - 1);
      if (view instanceof ItemView) {
        ((ItemView) view).performDestroy();
      }
    }
    for (int i = 0; i < count; i++) {
      ItemView itemView;
      if (i < itemsLayout.getChildCount()) {
        itemView = (ItemView) itemsLayout.getChildAt(i);
      } else {
        itemView = new ItemView(getContext());
        itemView.setLayoutParams(new LinearLayout.LayoutParams(Screen.dp(ITEM_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        itemView.setOnClickListener(this::onItemClick);
        itemsLayout.addView(itemView);
        if (isAttached) {
          itemView.attach();
        }
      }
      itemView.setStories(stories[i]);
    }
  }

  private void onItemClick (View view) {
    if (!(view instanceof ItemView) || parentController == null) {
      return;
    }
    int index = itemsLayout.indexOfChild(view);
    if (index == -1 || index >= stories.length) {
      return;
    }
    long[] chatIds = new long[stories.length];
    for (int i = 0; i < stories.length; i++) {
      chatIds[i] = stories[i].chatId;
    }
    StoryViewController.openChatStories(parentController, chatIds, index);
  }

  public static boolean hasUnreadStories (@Nullable TdApi.ChatActiveStories activeStories) {
    if (activeStories == null || activeStories.stories == null || activeStories.stories.length == 0) {
      return false;
    }
    for (TdApi.StoryInfo info : activeStories.stories) {
      if (info.storyId > activeStories.maxReadStoryId) {
        return true;
      }
    }
    return false;
  }

  private boolean isAttached;

  @Override
  public void attach () {
    isAttached = true;
    for (int i = 0; i < itemsLayout.getChildCount(); i++) {
      ((ItemView) itemsLayout.getChildAt(i)).attach();
    }
  }

  @Override
  public void detach () {
    isAttached = false;
    for (int i = 0; i < itemsLayout.getChildCount(); i++) {
      ((ItemView) itemsLayout.getChildAt(i)).detach();
    }
  }

  @Override
  public void performDestroy () {
    for (int i = 0; i < itemsLayout.getChildCount(); i++) {
      ((ItemView) itemsLayout.getChildAt(i)).performDestroy();
    }
    itemsLayout.removeAllViews();
  }

  private final class ItemView extends View implements AttachDelegate, Destroyable {
    private final AvatarReceiver receiver;
    private final Paint ringPaint;
    private final TextPaint textPaint;
    private long chatId;
    private boolean hasUnread;
    private String title = "";
    private CharSequence displayTitle;
    private int displayTitleWidth = -1;

    public ItemView (Context context) {
      super(context);
      receiver = new AvatarReceiver(this);
      ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      ringPaint.setStyle(Paint.Style.STROKE);
      textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
      textPaint.setTypeface(Fonts.getRobotoRegular());
      textPaint.setTextSize(Screen.dp(12f));
      Views.setClickable(this);
      RippleSupport.setTransparentSelector(this);
    }

    public void setStories (TdApi.ChatActiveStories activeStories) {
      boolean hasUnread = hasUnreadStories(activeStories);
      String title = tdlib.isSelfChat(activeStories.chatId) ? Lang.getString(R.string.MyStory) : tdlib.chatTitle(activeStories.chatId, false, true);
      if (this.chatId != activeStories.chatId) {
        this.chatId = activeStories.chatId;
        receiver.requestChat(tdlib, activeStories.chatId, AvatarReceiver.Options.NONE);
      }
      if (title == null) {
        title = "";
      }
      if (!title.equals(this.title)) {
        this.title = title;
        this.displayTitleWidth = -1;
      }
      this.hasUnread = hasUnread;
      invalidate();
    }

    @Override
    public void attach () {
      receiver.attach();
    }

    @Override
    public void detach () {
      receiver.detach();
    }

    @Override
    public void performDestroy () {
      receiver.destroy();
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      final int width = getMeasuredWidth();
      final int avatarSize = Screen.dp(AVATAR_SIZE_DP);
      final int cx = width / 2;
      final int top = Screen.dp(8f);
      final int cy = top + avatarSize / 2;
      final int ringGap = Screen.dp(3.5f);

      final float strokeWidth = hasUnread ? Screen.dp(2f) : Screen.dp(1.5f);
      ringPaint.setStrokeWidth(strokeWidth);
      ringPaint.setColor(hasUnread ? Theme.getColor(ColorId.textNeutral) : Theme.getColor(ColorId.iconLight));
      c.drawCircle(cx, cy, avatarSize / 2f, ringPaint);

      final int innerRadius = avatarSize / 2 - ringGap;
      receiver.setBounds(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius);
      if (receiver.needPlaceholder()) {
        receiver.drawPlaceholder(c);
      }
      receiver.draw(c);

      final int maxTextWidth = width - Screen.dp(6f);
      if (displayTitleWidth != maxTextWidth || displayTitle == null) {
        displayTitleWidth = maxTextWidth;
        displayTitle = TextUtils.ellipsize(title, textPaint, maxTextWidth, TextUtils.TruncateAt.END);
      }
      textPaint.setColor(hasUnread ? Theme.textAccentColor() : Theme.textDecentColor());
      float textWidth = textPaint.measureText(displayTitle, 0, displayTitle.length());
      c.drawText(displayTitle, 0, displayTitle.length(), cx - textWidth / 2f, top + avatarSize + Screen.dp(18f), textPaint);
    }
  }
}
