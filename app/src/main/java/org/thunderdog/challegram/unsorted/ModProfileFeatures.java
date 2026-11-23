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
package org.thunderdog.challegram.unsorted;

/**
 * Toggles for TGX Mod experimental features in the profile and the rich message bubble.
 * All of them are enabled by default.
 */
public final class ModProfileFeatures {
  private ModProfileFeatures () { }

  private static final String KEY_PROFILE_EXTRAS = "mod_profile_extras";
  private static final String KEY_RICH_INLINE = "mod_rich_message_inline";

  /** Music, personal channel, gifts and stories on profiles. */
  public static boolean profileExtrasEnabled () {
    return Settings.instance().getBoolean(KEY_PROFILE_EXTRAS, true);
  }

  public static void setProfileExtrasEnabled (boolean enabled) {
    Settings.instance().putBoolean(KEY_PROFILE_EXTRAS, enabled);
  }

  /** Expand rich messages (articles) inside the bubble instead of opening a new screen. */
  public static boolean richMessageInlineEnabled () {
    return Settings.instance().getBoolean(KEY_RICH_INLINE, true);
  }

  public static void setRichMessageInlineEnabled (boolean enabled) {
    Settings.instance().putBoolean(KEY_RICH_INLINE, enabled);
  }
}
