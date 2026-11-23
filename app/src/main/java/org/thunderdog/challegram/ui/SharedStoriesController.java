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
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.inline.CustomResultView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.InlineResult;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileLocal;
import org.thunderdog.challegram.mediaview.StoryViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.widget.EmptySmartView;

import java.util.ArrayList;

/**
 * Profile tab with stories posted to the chat page (or, for the current user, the story archive),
 * shown as a grid of thumbnails. Tapping a story opens {@link StoryViewController} at that story.
 */
public class SharedStoriesController extends SharedBaseController<SharedStoriesController.StoryRow> {
  private static final int PAGE_SIZE = 30;

  private boolean archive;
  private volatile int totalCount = -1;

  public SharedStoriesController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  public SharedStoriesController setArchive (boolean archive) {
    this.archive = archive;
    return this;
  }

  public boolean isArchive () {
    return archive;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(archive ? R.string.ProfileStoriesArchive : R.string.Stories);
  }

  @Override
  public int getIcon () {
    return R.drawable.baseline_camera_alt_24;
  }

  @Override
  protected TdApi.Function<?> buildRequest (long chatId, TdApi.MessageTopic topicId, String query, long offset, String secretOffset, int limit) {
    limit = Math.max(PAGE_SIZE, limit);
    limit += (ProfileGridRow.COLUMN_COUNT - limit % ProfileGridRow.COLUMN_COUNT) % ProfileGridRow.COLUMN_COUNT;
    if (archive) {
      return new TdApi.GetChatArchivedStories(chatId, (int) offset, limit);
    }
    return new TdApi.GetChatPostedToChatPageStories(chatId, (int) offset, limit);
  }

  @Override
  protected int calculateInitialLoadCount () {
    return PAGE_SIZE;
  }

  @Nullable
  @Override
  protected ArrayList<StoryRow> parseCustomResult (TdApi.Object object) {
    if (object.getConstructor() != TdApi.Stories.CONSTRUCTOR) {
      return null;
    }
    TdApi.Stories stories = (TdApi.Stories) object;
    this.totalCount = stories.totalCount;
    ArrayList<TdApi.Story> list = new ArrayList<>(stories.stories.length);
    for (TdApi.Story story : stories.stories) {
      if (story != null && story.content != null) {
        list.add(story);
      }
    }
    final int[] pinnedIds = stories.pinnedStoryIds;
    return ProfileGridRow.<TdApi.Story, StoryRow>split(list, cells -> new StoryRow(context, tdlib, cells, pinnedIds));
  }

  @Override
  protected long getCurrentOffset (ArrayList<StoryRow> data, long emptyValue) {
    if (data == null || data.isEmpty()) {
      return emptyValue;
    }
    StoryRow lastRow = data.get(data.size() - 1);
    return lastRow.getCells().get(lastRow.getCellCount() - 1).id;
  }

  @Override
  protected StoryRow parseObject (TdApi.Object object) {
    return null; // Not built from messages
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
    return EmptySmartView.MODE_EMPTY_MEDIA;
  }

  private int countLoaded () {
    int count = 0;
    if (data != null) {
      for (StoryRow row : data) {
        count += row.getCellCount();
      }
    }
    return count;
  }

  @Override
  protected CharSequence buildTotalCount (ArrayList<StoryRow> data) {
    int count = Math.max(totalCount, countLoaded());
    return Lang.pluralBold(R.string.xProfileStories, count);
  }

  @Override
  public void onClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item == null || item.getViewType() != ListItem.TYPE_CUSTOM_INLINE || !(item.getData() instanceof StoryRow)) {
      return;
    }
    TdApi.Story story = ((StoryRow) item.getData()).findTouchedCell();
    if (story == null || data == null) {
      return;
    }
    int[] ids = new int[countLoaded()];
    int index = 0, foundIndex = 0;
    for (StoryRow row : data) {
      for (TdApi.Story s : row.getCells()) {
        if (s == story) {
          foundIndex = index;
        }
        ids[index++] = s.id;
      }
    }
    long posterChatId = story.posterChatId != 0 ? story.posterChatId : getChatId();
    StoryViewController.openStoryList(this, posterChatId, ids, foundIndex);
  }

  // Row

  public static class StoryRow extends ProfileGridRow<TdApi.Story> {
    private final int[] pinnedIds;

    public StoryRow (BaseActivity context, Tdlib tdlib, java.util.List<TdApi.Story> cells, int[] pinnedIds) {
      super(context, tdlib, InlineResult.TYPE_PHOTO, cells);
      this.pinnedIds = pinnedIds != null ? pinnedIds : new int[0];
    }

    @Override
    protected int measureCellHeight (int cellWidth) {
      return cellWidth * 4 / 3;
    }

    @Nullable
    private static TdApi.PhotoSize pickSize (TdApi.Photo photo, int targetSize) {
      TdApi.PhotoSize best = null;
      if (photo == null || photo.sizes == null) {
        return null;
      }
      for (TdApi.PhotoSize size : photo.sizes) {
        if (best == null) {
          best = size;
          continue;
        }
        int bestMax = Math.max(best.width, best.height);
        int max = Math.max(size.width, size.height);
        if (bestMax < targetSize ? max > bestMax : (max >= targetSize && max < bestMax)) {
          best = size;
        }
      }
      return best;
    }

    @Override
    protected void requestCell (ComplexReceiver receiver, int index, TdApi.Story story) {
      ImageFile preview = null, full = null;
      final int targetSize = Screen.dp(180f);
      switch (story.content.getConstructor()) {
        case TdApi.StoryContentPhoto.CONSTRUCTOR: {
          TdApi.Photo photo = ((TdApi.StoryContentPhoto) story.content).photo;
          if (photo.minithumbnail != null) {
            preview = new ImageFileLocal(photo.minithumbnail);
          }
          TdApi.PhotoSize size = pickSize(photo, targetSize);
          if (size != null) {
            full = new ImageFile(tdlib, size.photo);
            full.setSize(targetSize);
          }
          break;
        }
        case TdApi.StoryContentVideo.CONSTRUCTOR: {
          TdApi.StoryVideo video = ((TdApi.StoryContentVideo) story.content).video;
          if (video.minithumbnail != null) {
            preview = new ImageFileLocal(video.minithumbnail);
          }
          full = TD.toImageFile(tdlib, video.thumbnail);
          break;
        }
      }
      if (preview != null) {
        preview.setScaleType(ImageFile.CENTER_CROP);
      }
      if (full != null) {
        full.setScaleType(ImageFile.CENTER_CROP);
      }
      receiver.getPreviewReceiver(index).requestFile(preview, full);
    }

    private boolean isPinned (int storyId) {
      for (int id : pinnedIds) {
        if (id == storyId) {
          return true;
        }
      }
      return false;
    }

    @Override
    protected void drawCell (CustomResultView view, Canvas c, ComplexReceiver receiver, int index, TdApi.Story story, int left, int top, int right, int bottom) {
      DoubleImageReceiver image = receiver.getPreviewReceiver(index);
      image.setRadius(Screen.dp(4f));
      image.setBounds(left, top, right, bottom);
      if (image.needPlaceholder()) {
        RectF rectF = Paints.getRectF(left, top, right, bottom);
        c.drawRoundRect(rectF, Screen.dp(4f), Screen.dp(4f), Paints.fillingPaint(Theme.placeholderColor()));
      }
      image.draw(c);

      String label = null;
      if (story.content.getConstructor() == TdApi.StoryContentVideo.CONSTRUCTOR) {
        label = Strings.buildDuration((int) Math.round(((TdApi.StoryContentVideo) story.content).video.duration));
      }
      if (isPinned(story.id)) {
        label = label != null ? "\uD83D\uDCCC " + label : "\uD83D\uDCCC";
      }
      if (label != null) {
        TextPaint paint = Paints.getMediumTextPaint(12f, 0xffffffff, false);
        float textWidth = paint.measureText(label);
        int padding = Screen.dp(5f);
        int labelLeft = left + Screen.dp(5f);
        int labelBottom = bottom - Screen.dp(5f);
        RectF rectF = Paints.getRectF(labelLeft, labelBottom - Screen.dp(18f), labelLeft + textWidth + padding * 2, labelBottom);
        c.drawRoundRect(rectF, Screen.dp(9f), Screen.dp(9f), Paints.fillingPaint(0x66000000));
        c.drawText(label, labelLeft + padding, labelBottom - Screen.dp(5f), paint);
      }
    }
  }
}
