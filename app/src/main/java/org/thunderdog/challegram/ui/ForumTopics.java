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

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibDelegate;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;

/**
 * TGX Mod: helpers for forum (topic) groups and bots with topics.
 */
public final class ForumTopics {
  private ForumTopics () { }

  private static final String KEY_TOPICS_LIST = "tgxmod_forum_topics_list";

  /** Preset colors used by official apps for topic icons. */
  public static final int[] ICON_COLORS = {
    0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F
  };

  public static boolean isEnabled () {
    return Settings.instance().getBoolean(KEY_TOPICS_LIST, true);
  }

  public static void setEnabled (boolean enabled) {
    Settings.instance().putBoolean(KEY_TOPICS_LIST, enabled);
  }

  @Nullable
  public static TdApi.UserTypeBot botType (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    if (chat == null || chat.type.getConstructor() != TdApi.ChatTypePrivate.CONSTRUCTOR) {
      return null;
    }
    TdApi.User user = tdlib.chatUser(chat);
    if (user != null && user.type.getConstructor() == TdApi.UserTypeBot.CONSTRUCTOR) {
      return (TdApi.UserTypeBot) user.type;
    }
    return null;
  }

  public static boolean isBotWithTopics (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    TdApi.UserTypeBot bot = botType(tdlib, chat);
    return bot != null && bot.hasTopics;
  }

  /** Forum supergroup or a private chat with a bot that has topics. */
  public static boolean hasTopics (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    return chat != null && (tdlib.isForum(chat.id) || isBotWithTopics(tdlib, chat));
  }

  /** Whether opening the chat must show the topics list instead of the message history. */
  public static boolean needTopicsList (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    if (chat == null || !isEnabled()) {
      return false;
    }
    if (tdlib.isForum(chat.id)) {
      return chat.viewAsTopics;
    }
    // Bot with topics: show the list only once the conversation was started
    return isBotWithTopics(tdlib, chat) && chat.lastMessage != null;
  }

  public static boolean canManageTopics (Tdlib tdlib, long chatId) {
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chatId);
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
        return true;
      case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
        return ((TdApi.ChatMemberStatusAdministrator) status).rights.canManageTopics;
    }
    return false;
  }

  public static boolean canCreateTopics (Tdlib tdlib, @Nullable TdApi.Chat chat) {
    if (chat == null) {
      return false;
    }
    TdApi.UserTypeBot bot = botType(tdlib, chat);
    if (bot != null) {
      return bot.hasTopics && bot.allowsUsersToCreateTopics;
    }
    if (!tdlib.isForum(chat.id)) {
      return false;
    }
    if (canManageTopics(tdlib, chat.id)) {
      return true;
    }
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
    if (status == null) {
      return false;
    }
    switch (status.getConstructor()) {
      case TdApi.ChatMemberStatusMember.CONSTRUCTOR:
        return chat.permissions != null && chat.permissions.canCreateTopics;
      case TdApi.ChatMemberStatusRestricted.CONSTRUCTOR: {
        TdApi.ChatMemberStatusRestricted restricted = (TdApi.ChatMemberStatusRestricted) status;
        return restricted.isMember && restricted.permissions.canCreateTopics;
      }
    }
    return false;
  }

  public static boolean canEditTopic (Tdlib tdlib, TdApi.Chat chat, TdApi.ForumTopicInfo info) {
    if (isBotWithTopics(tdlib, chat)) {
      return canCreateTopics(tdlib, chat);
    }
    return canManageTopics(tdlib, chat.id) || info.isOutgoing;
  }

  public static boolean canCloseTopic (Tdlib tdlib, TdApi.Chat chat, TdApi.ForumTopicInfo info) {
    if (isBotWithTopics(tdlib, chat)) {
      return false;
    }
    return canManageTopics(tdlib, chat.id) || (info.isOutgoing && !info.isGeneral);
  }

  public static boolean canPinTopic (Tdlib tdlib, TdApi.Chat chat) {
    if (isBotWithTopics(tdlib, chat)) {
      return true;
    }
    return canManageTopics(tdlib, chat.id);
  }

  public static boolean canDeleteTopic (Tdlib tdlib, TdApi.Chat chat, TdApi.ForumTopicInfo info) {
    if (info.isGeneral) {
      return false;
    }
    if (isBotWithTopics(tdlib, chat)) {
      return canCreateTopics(tdlib, chat);
    }
    TdApi.ChatMemberStatus status = tdlib.chatStatus(chat.id);
    if (status != null) {
      switch (status.getConstructor()) {
        case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
          return true;
        case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
          if (((TdApi.ChatMemberStatusAdministrator) status).rights.canDeleteMessages) {
            return true;
          }
          break;
      }
    }
    return info.isOutgoing;
  }

  /** Whether the current user may not send messages to the topic because it is closed. */
  public static boolean isInputBlocked (Tdlib tdlib, TdApi.Chat chat, @Nullable TdApi.ForumTopicInfo info) {
    return info != null && info.isClosed && !canManageTopics(tdlib, chat.id);
  }

  public static int defaultIconColor (String name) {
    int hash = name != null ? name.hashCode() : 0;
    return ICON_COLORS[Math.abs(hash % ICON_COLORS.length)];
  }

  public static String iconLetter (@Nullable TdApi.ForumTopicInfo info) {
    if (info == null) {
      return "#";
    }
    if (info.isGeneral) {
      return "#";
    }
    String name = info.name != null ? info.name.trim() : "";
    if (name.isEmpty()) {
      return "#";
    }
    int codePoint = name.codePointAt(0);
    return new String(Character.toChars(codePoint)).toUpperCase();
  }

  public static int iconColor (@Nullable TdApi.ForumTopicInfo info) {
    if (info == null || info.isGeneral || info.icon == null) {
      return 0x8E8E93;
    }
    return info.icon.color != 0 ? info.icon.color : defaultIconColor(info.name);
  }

  /** Opens the topics list of the given chat. */
  public static void openTopicsList (TdlibDelegate context, TdApi.Chat chat, @Nullable TdApi.ChatList chatList) {
    if (context.context().isNavigationBusy()) {
      return;
    }
    NavigationController navigation = context.context().navigation();
    ViewController<?> current = navigation.getCurrentStackItem();
    if (current instanceof ForumTopicsController && current.tdlib() == context.tdlib() && ((ForumTopicsController) current).getChatId() == chat.id) {
      return;
    }
    ForumTopicsController c = new ForumTopicsController(context.context(), context.tdlib());
    c.setArguments(new ForumTopicsController.Args(chat.id, chatList));
    if (navigation.isEmpty()) {
      navigation.initController(c);
      MainController main = new MainController(context.context(), context.tdlib());
      main.getValue();
      navigation.getStack().insert(main, 0);
    } else {
      navigation.navigateTo(c);
    }
  }
}
