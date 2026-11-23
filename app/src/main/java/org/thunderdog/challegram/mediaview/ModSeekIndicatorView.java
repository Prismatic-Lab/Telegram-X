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
 * TGX Mod: subtle indicator for ±10 s double-tap seeking in the media viewer.
 */
package org.thunderdog.challegram.mediaview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;

/** Non-interactive overlay: a flat translucent pill with "−10 s" / "+10 s" on the tapped side. */
public class ModSeekIndicatorView extends View {
  private static final long SHOW_DURATION_MS = 650;

  private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();

  private int direction;
  private int accumulatedSeconds;
  private String text = "";

  private final Runnable hideRunnable = () -> {
    animate().alpha(0f).setDuration(180).start();
    accumulatedSeconds = 0;
  };

  public ModSeekIndicatorView (Context context) {
    super(context);
    pillPaint.setColor(0x99000000);
    textPaint.setColor(0xFFFFFFFF);
    textPaint.setTypeface(Fonts.getRobotoMedium());
    textPaint.setTextSize(Screen.dp(14f));
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
      textPaint.setFontFeatureSettings("tnum");
    }
    setAlpha(0f);
    setClickable(false);
    setFocusable(false);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public void show (int direction, int seconds) {
    if (this.direction != direction) {
      accumulatedSeconds = 0;
    }
    this.direction = direction;
    accumulatedSeconds += seconds;
    text = Lang.getString(direction < 0 ? R.string.ModVideoSeekBack : R.string.ModVideoSeekForward, accumulatedSeconds);
    removeCallbacks(hideRunnable);
    animate().cancel();
    animate().alpha(1f).setDuration(100).start();
    postDelayed(hideRunnable, SHOW_DURATION_MS);
    invalidate();
  }

  @Override
  protected void onDetachedFromWindow () {
    super.onDetachedFromWindow();
    removeCallbacks(hideRunnable);
  }

  @Override
  protected void onDraw (Canvas c) {
    if (text.isEmpty()) {
      return;
    }
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    float cx = direction < 0 ? width * .18f : width * .82f;
    float cy = height / 2f;
    float textWidth = textPaint.measureText(text);
    float padH = Screen.dp(16f), halfH = Screen.dp(18f);
    rect.set(cx - textWidth / 2f - padH, cy - halfH, cx + textWidth / 2f + padH, cy + halfH);
    float r = Screen.dp(4f); // squarer Swiss pill
    c.drawRoundRect(rect, r, r, pillPaint);
    Paint.FontMetrics fm = textPaint.getFontMetrics();
    c.drawText(text, cx - textWidth / 2f, cy - (fm.ascent + fm.descent) / 2f, textPaint);
  }
}
