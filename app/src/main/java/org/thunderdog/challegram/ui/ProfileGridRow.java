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

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.component.inline.CustomResultView;
import org.thunderdog.challegram.data.InlineResult;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * One row of a profile grid (stories, gifts), shown with {@link ListItem#TYPE_CUSTOM_INLINE},
 * so the grid scrolls together with the profile pager like the other shared media tabs.
 */
public abstract class ProfileGridRow<C> extends InlineResult<Object> {
  public static final int COLUMN_COUNT = 3;

  protected final ArrayList<C> cells;
  private int cellWidth, cellHeight;
  private int lastTouchX = -1;

  protected ProfileGridRow (BaseActivity context, Tdlib tdlib, int type, List<C> cells) {
    super(context, tdlib, type, null, null);
    this.cells = new ArrayList<>(cells);
  }

  public final List<C> getCells () {
    return cells;
  }

  public final int getCellCount () {
    return cells.size();
  }

  protected static int spacing () {
    return Screen.dp(3f);
  }

  protected static int sidePadding () {
    return Screen.dp(3f);
  }

  /** Cell height for the given cell width. */
  protected abstract int measureCellHeight (int cellWidth);

  protected abstract void requestCell (ComplexReceiver receiver, int index, C cell);

  protected abstract void drawCell (CustomResultView view, Canvas c, ComplexReceiver receiver, int index, C cell, int left, int top, int right, int bottom);

  @Override
  protected void layoutInternal (int contentWidth) {
    int available = contentWidth - sidePadding() * 2 - spacing() * (COLUMN_COUNT - 1);
    cellWidth = Math.max(1, available / COLUMN_COUNT);
    cellHeight = measureCellHeight(cellWidth);
  }

  @Override
  protected int getContentHeight () {
    return cellHeight + spacing();
  }

  protected final int getCellLeft (int index) {
    return sidePadding() + index * (cellWidth + spacing());
  }

  @Override
  public void requestContent (ComplexReceiver receiver, boolean isInvalidate) {
    if (!isInvalidate) {
      receiver.clear();
    }
    for (int i = 0; i < cells.size(); i++) {
      requestCell(receiver, i, cells.get(i));
    }
  }

  @Override
  protected void drawInternal (CustomResultView view, Canvas c, ComplexReceiver receiver, int viewWidth, int viewHeight, int startY) {
    int top = startY + spacing();
    for (int i = 0; i < cells.size(); i++) {
      int left = getCellLeft(i);
      drawCell(view, c, receiver, i, cells.get(i), left, top, left + cellWidth, top + cellHeight);
    }
  }

  @Override
  public boolean onTouchEvent (View view, MotionEvent e) {
    if (e.getAction() == MotionEvent.ACTION_DOWN) {
      lastTouchX = (int) e.getX();
    }
    return false; // Let the view handle clicks
  }

  /** Cell under the last touch, or {@code null} if the touch was outside of any cell. */
  @Nullable
  public final C findTouchedCell () {
    if (lastTouchX < 0 || cellWidth <= 0) {
      return cells.isEmpty() ? null : cells.get(0);
    }
    for (int i = 0; i < cells.size(); i++) {
      int left = getCellLeft(i);
      if (lastTouchX >= left - spacing() / 2 && lastTouchX < left + cellWidth + spacing() / 2) {
        return cells.get(i);
      }
    }
    return null;
  }

  /** Splits cells into rows of {@link #COLUMN_COUNT}. */
  public interface RowFactory<C, R extends ProfileGridRow<C>> {
    R newRow (List<C> cells);
  }

  public static <C, R extends ProfileGridRow<C>> ArrayList<R> split (List<C> cells, RowFactory<C, R> factory) {
    ArrayList<R> rows = new ArrayList<>((cells.size() + COLUMN_COUNT - 1) / COLUMN_COUNT);
    for (int i = 0; i < cells.size(); i += COLUMN_COUNT) {
      rows.add(factory.newRow(cells.subList(i, Math.min(cells.size(), i + COLUMN_COUNT))));
    }
    return rows;
  }
}
