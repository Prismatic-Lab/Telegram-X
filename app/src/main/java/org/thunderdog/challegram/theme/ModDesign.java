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
 * TGX Mod: "Swiss" visual identity switch.
 */
package org.thunderdog.challegram.theme;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.unsorted.Settings;

/**
 * Single entry point for the TGX Mod design ("Swiss Light" / "Swiss Dark" themes plus
 * small layout tweaks). Everything mod-specific checks {@link #isEnabled()}, so turning
 * the setting off restores the original Telegram X look.
 */
public final class ModDesign {
  private ModDesign () { }

  private static final String KEY_ENABLED = "tgxmod_design";
  private static final String KEY_AVATAR_SHAPE = "tgxmod_avatar_shape";

  public static final int AVATAR_SHAPE_CIRCLE = 0;
  public static final int AVATAR_SHAPE_ROUNDED_SQUARE = 1;

  /** Corner factor (of half the avatar size): ~8dp on a 52dp chat-list avatar. */
  public static final float ROUNDED_SQUARE_RADIUS = .3f;

  // Grid & geometry used by layout tweaks (dp)
  public static final float GRID = 8f;
  public static final float HEADER_TITLE_SIZE = 20f;
  public static final float HEADER_TITLE_MARGIN = 16f;   // 2 × grid
  public static final float HEADER_TITLE_MARGIN_BACK = 72f; // 9 × grid, standard keyline

  private static Boolean enabled;
  private static Integer avatarShape;

  public static boolean isEnabled () {
    if (enabled == null) {
      enabled = Settings.instance().getBoolean(KEY_ENABLED, true);
    }
    return enabled;
  }

  public static int getAvatarShape () {
    if (avatarShape == null) {
      avatarShape = Settings.instance().getInt(KEY_AVATAR_SHAPE, AVATAR_SHAPE_CIRCLE);
    }
    return avatarShape;
  }

  public static void setAvatarShape (int shape) {
    if (getAvatarShape() != shape) {
      avatarShape = shape;
      Settings.instance().putInt(KEY_AVATAR_SHAPE, shape);
    }
  }

  public static boolean isSwissTheme (int themeId) {
    return themeId == ThemeId.SWISS_LIGHT || themeId == ThemeId.SWISS_DARK;
  }

  public static int defaultLightTheme () {
    return isEnabled() ? ThemeId.SWISS_LIGHT : ThemeId.CLASSIC;
  }

  public static int defaultDarkTheme () {
    return isEnabled() ? ThemeId.SWISS_DARK : ThemeId.NIGHT_BLUE;
  }

  /** Theme used when nothing was saved yet (fresh install). */
  public static int defaultTheme () {
    return isEnabled() ? ThemeId.SWISS_LIGHT : ThemeId.NIGHT_BLUE;
  }

  /**
   * Applied to every {@link Theme#getProperty(int)} read for the current theme.
   * Only the avatar shape is user-overridable.
   */
  public static float filterProperty (int propertyId, float value) {
    if ((propertyId == PropertyId.AVATAR_RADIUS || propertyId == PropertyId.AVATAR_RADIUS_FORUM || propertyId == PropertyId.AVATAR_RADIUS_CHAT_LIST || propertyId == PropertyId.AVATAR_RADIUS_CHAT_LIST_FORUM)
      && isEnabled() && getAvatarShape() == AVATAR_SHAPE_ROUNDED_SQUARE) {
      return ROUNDED_SQUARE_RADIUS;
    }
    return value;
  }

  /**
   * Turns the mod design on/off. Swaps the stock defaults (Classic / Night Blue) with
   * Swiss Light / Swiss Dark in the account's day/night theme slots and applies the change.
   * Custom or other built-in themes picked by the user are left untouched.
   */
  public static void setEnabled (@Nullable Tdlib tdlib, boolean isEnabled) {
    if (isEnabled() == isEnabled) {
      return;
    }
    enabled = isEnabled;
    Settings.instance().putBoolean(KEY_ENABLED, isEnabled);
    if (tdlib == null) {
      return;
    }
    final int fromLight = isEnabled ? ThemeId.CLASSIC : ThemeId.SWISS_LIGHT;
    final int toLight = isEnabled ? ThemeId.SWISS_LIGHT : ThemeId.CLASSIC;
    final int fromDark = isEnabled ? ThemeId.NIGHT_BLUE : ThemeId.SWISS_DARK;
    final int toDark = isEnabled ? ThemeId.SWISS_DARK : ThemeId.NIGHT_BLUE;

    ThemeManager themes = ThemeManager.instance();
    int currentThemeId = themes.currentThemeId();
    int targetThemeId = currentThemeId == fromLight ? toLight : currentThemeId == fromDark ? toDark : ThemeId.NONE;
    if (targetThemeId != ThemeId.NONE) {
      // changeGlobalTheme -> setGlobalTheme also switches the default wallpaper of the slot
      themes.changeGlobalTheme(tdlib, themes.getTheme(targetThemeId), true, null);
    }
    // Remaining (inactive) day / night slot
    boolean lightSlotChanged = tdlib.settings().globalDaylightTheme() == fromLight;
    boolean darkSlotChanged = tdlib.settings().globalNightTheme() == fromDark;
    tdlib.settings().replaceThemeId(fromLight, toLight);
    tdlib.settings().replaceThemeId(fromDark, toDark);
    if (lightSlotChanged && targetThemeId != toLight) {
      tdlib.wallpaper().onThemeSwitched(fromLight, toLight);
    }
    if (darkSlotChanged && targetThemeId != toDark) {
      tdlib.wallpaper().onThemeSwitched(fromDark, toDark);
    }
  }
}
