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
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibDelegate;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Settings;

import me.vkryl.core.StringUtils;
import me.vkryl.core.lambda.RunnableBool;

/**
 * Entry points for bot Mini Apps: inline keyboard buttons, reply keyboard buttons,
 * the bot menu button, inline mode buttons and t.me links.
 */
public final class WebAppLauncher {
  private WebAppLauncher () { }

  private static final String KEY_ENABLED = "mod_mini_apps";
  private static final String KEY_CONFIRMED_PREFIX = "mod_mini_app_ok_";
  private static final String APPLICATION_NAME = "android";

  public static boolean isEnabled () {
    return Settings.instance().getBoolean(KEY_ENABLED, true);
  }

  public static void setEnabled (boolean enabled) {
    Settings.instance().putBoolean(KEY_ENABLED, enabled);
  }

  public static TdApi.WebAppOpenParameters newOpenParameters (@Nullable TdApi.WebAppOpenMode mode) {
    return new TdApi.WebAppOpenParameters(WebAppController.buildTdThemeParameters(), APPLICATION_NAME, mode != null ? mode : new TdApi.WebAppOpenModeFullSize());
  }

  private static String botName (Tdlib tdlib, long botUserId) {
    return tdlib.cache().userDisplayName(botUserId, false, false);
  }

  /**
   * TDLib: "For each bot, a confirmation alert about data sent to the bot must be shown once."
   */
  private static void confirmOpen (ViewController<?> host, Tdlib tdlib, long botUserId, Runnable onConfirm) {
    final String key = KEY_CONFIRMED_PREFIX + tdlib.id() + "_" + botUserId;
    if (Settings.instance().getBoolean(key, false)) {
      onConfirm.run();
      return;
    }
    host.showConfirm(Lang.getString(R.string.WebAppOpenConfirm, botName(tdlib, botUserId)), Lang.getString(R.string.WebAppOpenAction), R.drawable.deproko_baseline_bots_24, ViewController.OptionColor.NORMAL, () -> {
      Settings.instance().putBoolean(key, true);
      onConfirm.run();
    });
  }

  private static void open (ViewController<?> host, Tdlib tdlib, WebAppController.Args args) {
    if (host.isDestroyed()) {
      return;
    }
    WebAppController c = new WebAppController(host.context(), tdlib);
    c.setArguments(args);
    host.navigateTo(c);
  }

  private static void openExternally (ViewController<?> host, Tdlib tdlib, String url) {
    tdlib.ui().openUrl(host, url, new TdlibUi.UrlOpenParameters().requireOpenPrompt().disableInstantView());
  }

  // Inline keyboard button (also used for the bot menu button and attachment menu links)

  public static void openWithLaunch (ViewController<?> host, Tdlib tdlib, long chatId, long botUserId, String url, @Nullable TdApi.MessageTopic topicId, int source, @Nullable MessagesController owner, @Nullable RunnableBool after) {
    if (!isEnabled()) {
      if (!StringUtils.isEmpty(url)) {
        openExternally(host, tdlib, url);
      }
      if (after != null) {
        after.runWithBool(false);
      }
      return;
    }
    confirmOpen(host, tdlib, botUserId, () ->
      tdlib.send(new TdApi.OpenWebApp(chatId, botUserId, url, topicId, null, newOpenParameters(null)), (info, error) -> UI.post(() -> {
        if (error != null) {
          UI.showError(error);
          if (after != null) {
            after.runWithBool(false);
          }
          return;
        }
        WebAppController.Args args = new WebAppController.Args(botUserId, chatId, info.url.url, source);
        args.launchId = info.launchId;
        args.requireSameOrigin = info.url.requireSameOrigin;
        args.owner = owner;
        open(host, tdlib, args);
        if (after != null) {
          after.runWithBool(true);
        }
      }))
    );
  }

  public static void openFromInlineButton (ViewController<?> host, Tdlib tdlib, TdApi.Message message, String url, @Nullable MessagesController owner) {
    long botUserId = message.viaBotUserId != 0 ? message.viaBotUserId : tgx.td.Td.getSenderUserId(message);
    if (botUserId == 0) {
      openExternally(host, tdlib, url);
      return;
    }
    TdApi.MessageTopic topicId = owner != null && owner.getChatId() == message.chatId ? owner.getMessageTopicId() : null;
    openWithLaunch(host, tdlib, message.chatId, botUserId, url, topicId, WebAppController.SOURCE_INLINE_BUTTON, owner, null);
  }

  // Reply keyboard button

  public static void openFromKeyboardButton (MessagesController host, long botUserId, String buttonText, String url) {
    final Tdlib tdlib = host.tdlib();
    if (!isEnabled()) {
      openExternally(host, tdlib, url);
      return;
    }
    confirmOpen(host, tdlib, botUserId, () ->
      tdlib.send(new TdApi.GetWebAppUrl(botUserId, url, newOpenParameters(null)), (webAppUrl, error) -> UI.post(() -> {
        if (error != null) {
          UI.showError(error);
          return;
        }
        WebAppController.Args args = new WebAppController.Args(botUserId, host.getChatId(), webAppUrl.url, WebAppController.SOURCE_KEYBOARD_BUTTON);
        args.keyboardButtonText = buttonText;
        args.requireSameOrigin = webAppUrl.requireSameOrigin;
        args.owner = host;
        open(host, tdlib, args);
      }))
    );
  }

  // Inline mode "switch to Mini App" button

  public static void openFromInlineMode (ViewController<?> host, Tdlib tdlib, long botUserId, String url) {
    if (!isEnabled()) {
      openExternally(host, tdlib, url);
      return;
    }
    confirmOpen(host, tdlib, botUserId, () ->
      tdlib.send(new TdApi.GetWebAppUrl(botUserId, url, newOpenParameters(null)), (webAppUrl, error) -> UI.post(() -> {
        if (error != null) {
          UI.showError(error);
          return;
        }
        WebAppController.Args args = new WebAppController.Args(botUserId, host instanceof MessagesController ? host.getChatId() : 0, webAppUrl.url, WebAppController.SOURCE_INLINE_MODE);
        args.requireSameOrigin = webAppUrl.requireSameOrigin;
        args.owner = host instanceof MessagesController ? (MessagesController) host : null;
        open(host, tdlib, args);
      }))
    );
  }

  // Bot menu button

  public static boolean isWebAppMenuButton (@Nullable TdApi.BotMenuButton menuButton) {
    return menuButton != null && !StringUtils.isEmpty(menuButton.url) && isEnabled();
  }

  public static void openFromMenuButton (MessagesController host, long botUserId, TdApi.BotMenuButton menuButton) {
    openWithLaunch(host, host.tdlib(), host.getChatId(), botUserId, menuButton.url, host.getMessageTopicId(), WebAppController.SOURCE_MENU_BUTTON, host, null);
  }

  public static TextView newMenuButtonView (Context context, ViewController<?> themeProvider) {
    TextView view = new TextView(context);
    view.setTypeface(Fonts.getRobotoMedium());
    view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
    view.setSingleLine(true);
    view.setEllipsize(TextUtils.TruncateAt.END);
    view.setMaxWidth(Screen.dp(120f));
    view.setGravity(Gravity.CENTER);
    view.setPadding(Screen.dp(10f), 0, Screen.dp(10f), 0);
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(Screen.dp(15f));
    background.setColor(Theme.getColor(ColorId.fillingPositive));
    view.setBackground(background);
    view.setTextColor(Theme.getColor(ColorId.fillingPositiveContent));
    if (themeProvider != null) {
      themeProvider.addThemeTextColorListener(view, ColorId.fillingPositiveContent);
    }
    return view;
  }

  public static void updateMenuButtonColors (TextView view) {
    if (view.getBackground() instanceof GradientDrawable) {
      ((GradientDrawable) view.getBackground()).setColor(Theme.getColor(ColorId.fillingPositive));
    }
    view.setTextColor(Theme.getColor(ColorId.fillingPositiveContent));
  }

  // Links: t.me/bot/app, t.me/bot?startapp, t.me/bot?startattach

  public static void openInternalLink (TdlibDelegate context, Tdlib tdlib, TdApi.InternalLinkType linkType, @Nullable RunnableBool after) {
    final String botUsername;
    switch (linkType.getConstructor()) {
      case TdApi.InternalLinkTypeWebApp.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeWebApp) linkType).botUsername;
        break;
      case TdApi.InternalLinkTypeMainWebApp.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeMainWebApp) linkType).botUsername;
        break;
      case TdApi.InternalLinkTypeAttachmentMenuBot.CONSTRUCTOR:
        botUsername = ((TdApi.InternalLinkTypeAttachmentMenuBot) linkType).botUsername;
        break;
      default:
        if (after != null) {
          after.runWithBool(false);
        }
        return;
    }
    tdlib.send(new TdApi.SearchPublicChat(botUsername), (botChat, error) -> UI.post(() -> {
      ViewController<?> host = context.context().navigation().getCurrentStackItem();
      long botUserId = botChat != null ? tdlib.chatUserId(botChat) : 0;
      TdApi.User bot = botUserId != 0 ? tdlib.cache().user(botUserId) : null;
      if (error != null || host == null || bot == null || !(bot.type instanceof TdApi.UserTypeBot)) {
        if (error != null) {
          UI.showError(error);
        }
        if (after != null) {
          after.runWithBool(false);
        }
        return;
      }
      MessagesController owner = host instanceof MessagesController ? (MessagesController) host : null;
      long currentChatId = owner != null ? owner.getChatId() : 0;
      switch (linkType.getConstructor()) {
        case TdApi.InternalLinkTypeWebApp.CONSTRUCTOR:
          openDirectLink(host, tdlib, botUserId, currentChatId, owner, (TdApi.InternalLinkTypeWebApp) linkType, after);
          break;
        case TdApi.InternalLinkTypeMainWebApp.CONSTRUCTOR:
          openMainWebApp(host, tdlib, botUserId, currentChatId, owner, (TdApi.InternalLinkTypeMainWebApp) linkType, after);
          break;
        case TdApi.InternalLinkTypeAttachmentMenuBot.CONSTRUCTOR:
          openAttachmentMenuBot(host, tdlib, botUserId, botChat.id, owner, (TdApi.InternalLinkTypeAttachmentMenuBot) linkType, after);
          break;
      }
    }));
  }

  private static void openDirectLink (ViewController<?> host, Tdlib tdlib, long botUserId, long currentChatId, @Nullable MessagesController owner, TdApi.InternalLinkTypeWebApp link, @Nullable RunnableBool after) {
    tdlib.send(new TdApi.SearchWebApp(botUserId, link.webAppShortName), (found, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
        if (after != null) {
          after.runWithBool(false);
        }
        return;
      }
      RunnableBool launch = allowWriteAccess ->
        tdlib.send(new TdApi.GetWebAppLinkUrl(currentChatId, botUserId, link.webAppShortName, link.startParameter, allowWriteAccess, newOpenParameters(link.mode)), (webAppUrl, urlError) -> UI.post(() -> {
          if (urlError != null) {
            UI.showError(urlError);
            if (after != null) {
              after.runWithBool(false);
            }
            return;
          }
          WebAppController.Args args = new WebAppController.Args(botUserId, currentChatId, webAppUrl.url, WebAppController.SOURCE_DIRECT_LINK);
          args.requireSameOrigin = webAppUrl.requireSameOrigin;
          args.webAppShortName = link.webAppShortName;
          args.title = found.webApp.title;
          args.owner = owner;
          open(host, tdlib, args);
          if (after != null) {
            after.runWithBool(true);
          }
        }));
      if (found.skipConfirmation && !found.requestWriteAccess) {
        launch.runWithBool(false);
      } else {
        CharSequence text = found.requestWriteAccess ?
          Lang.getString(R.string.WebAppOpenConfirmWriteAccess, found.webApp.title, botName(tdlib, botUserId)) :
          Lang.getString(R.string.WebAppOpenConfirm, botName(tdlib, botUserId));
        host.showConfirm(text, Lang.getString(R.string.WebAppOpenAction), R.drawable.deproko_baseline_bots_24, ViewController.OptionColor.NORMAL, () ->
          launch.runWithBool(found.requestWriteAccess)
        );
      }
    }));
  }

  private static void openMainWebApp (ViewController<?> host, Tdlib tdlib, long botUserId, long currentChatId, @Nullable MessagesController owner, TdApi.InternalLinkTypeMainWebApp link, @Nullable RunnableBool after) {
    confirmOpen(host, tdlib, botUserId, () ->
      tdlib.send(new TdApi.GetMainWebApp(currentChatId, botUserId, link.startParameter, newOpenParameters(link.mode)), (mainWebApp, error) -> UI.post(() -> {
        if (error != null) {
          UI.showError(error);
          if (after != null) {
            after.runWithBool(false);
          }
          return;
        }
        WebAppController.Args args = new WebAppController.Args(botUserId, currentChatId, mainWebApp.url.url, WebAppController.SOURCE_MAIN_APP);
        args.requireSameOrigin = mainWebApp.url.requireSameOrigin;
        args.owner = owner;
        open(host, tdlib, args);
        if (after != null) {
          after.runWithBool(true);
        }
      }))
    );
  }

  private static void openAttachmentMenuBot (ViewController<?> host, Tdlib tdlib, long botUserId, long botChatId, @Nullable MessagesController owner, TdApi.InternalLinkTypeAttachmentMenuBot link, @Nullable RunnableBool after) {
    tdlib.send(new TdApi.GetAttachmentMenuBot(botUserId), (attachmentMenuBot, error) -> UI.post(() -> {
      if (error != null) {
        UI.showError(error);
        if (after != null) {
          after.runWithBool(false);
        }
        return;
      }
      boolean useCurrentChat = owner != null && link.targetChat instanceof TdApi.TargetChatCurrent;
      long chatId = useCurrentChat ? owner.getChatId() : botChatId;
      MessagesController chatOwner = useCurrentChat ? owner : null;
      Runnable launch = () -> openWithLaunch(host, tdlib, chatId, botUserId, link.url, useCurrentChat ? owner.getMessageTopicId() : null, WebAppController.SOURCE_ATTACHMENT_MENU, chatOwner, after);
      if (attachmentMenuBot.isAdded) {
        launch.run();
      } else {
        host.showConfirm(Lang.getString(R.string.WebAppAddToMenuConfirm, attachmentMenuBot.name), Lang.getString(R.string.WebAppAddToMenu), R.drawable.baseline_apps_24, ViewController.OptionColor.NORMAL, () ->
          tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(botUserId, true, attachmentMenuBot.requestWriteAccess), (ok, toggleError) -> UI.post(() -> {
            if (toggleError != null) {
              UI.showError(toggleError);
            }
            launch.run();
          }))
        );
      }
    }));
  }
}
