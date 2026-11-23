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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Size;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Runner for bot Mini Apps (Web Apps): https://core.telegram.org/bots/webapps
 *
 * Exposes {@code window.TelegramWebviewProxy.postEvent(eventType, eventData)} as described in
 * https://core.telegram.org/api/web-events and answers via {@code window.Telegram.WebView.receiveEvent}.
 * Init data ({@code tgWebAppData}) and theme are part of the URL built by TDLib.
 */
public class WebAppController extends WebkitController<WebAppController.Args> implements Menu, MoreDelegate {
  public static final int SOURCE_INLINE_BUTTON = 0;
  public static final int SOURCE_KEYBOARD_BUTTON = 1;
  public static final int SOURCE_MENU_BUTTON = 2;
  public static final int SOURCE_MAIN_APP = 3;
  public static final int SOURCE_DIRECT_LINK = 4;
  public static final int SOURCE_ATTACHMENT_MENU = 5;
  public static final int SOURCE_INLINE_MODE = 6;

  public static class Args {
    public final long botUserId;
    public final long chatId;
    public final String url;
    public final int source;
    public long launchId;
    public boolean requireSameOrigin;
    @Nullable public String keyboardButtonText;
    @Nullable public String webAppShortName;
    @Nullable public String title;
    @Nullable public MessagesController owner;

    public Args (long botUserId, long chatId, String url, int source) {
      this.botUserId = botUserId;
      this.chatId = chatId;
      this.url = url;
      this.source = source;
    }
  }

  private static final ArrayList<WebAppController> activeControllers = new ArrayList<>();

  /** Called from TDLib thread on {@link TdApi.UpdateWebAppMessageSent}: the Mini App sent a message, so it must be closed. */
  public static void onWebAppMessageSent (Tdlib tdlib, long launchId) {
    if (launchId == 0) {
      return;
    }
    UI.post(() -> {
      for (WebAppController c : new ArrayList<>(activeControllers)) {
        if (c.tdlib == tdlib && c.getArguments() != null && c.getArgumentsStrict().launchId == launchId) {
          c.forceClose();
        }
      }
    });
  }

  private static final String BRIDGE_NAME = "TelegramWebviewProxy";

  @Nullable private WebView webView;
  private LinearLayout wrapView;
  private LinearLayout bottomBar;
  private BottomButton mainButton, secondaryButton;
  private String secondaryPosition = "left";

  private boolean isBackButtonVisible, isSettingsButtonVisible, needCloseConfirmation;
  private boolean isClosing, isPopupVisible;
  private long lastInteractionTime, lastLinkOpenTime;
  private final long[] popupTimes = new long[3];
  private int popupTimesIndex;
  private boolean isAddedToAttachmentMenu;
  private boolean isPhoneRequestVisible, isWriteAccessRequestVisible;

  private int customHeaderColor = -1; // -1: theme color
  private int lastViewportHeight = -1;

  public WebAppController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_webApp;
  }

  // Header

  @Override
  protected int getHeaderColorId () {
    return ColorId.filling;
  }

  @Override
  protected int getHeaderTextColorId () {
    return ColorId.text;
  }

  @Override
  protected int getHeaderIconColorId () {
    return ColorId.icon;
  }

  @Override
  protected int getMenuId () {
    return R.id.menu_webApp;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_webApp) {
      header.addMoreButton(menu, this);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_more) {
      Args args = getArgumentsStrict();
      ArrayList<Integer> ids = new ArrayList<>();
      ArrayList<String> titles = new ArrayList<>();
      ArrayList<Integer> icons = new ArrayList<>();
      if (isSettingsButtonVisible) {
        ids.add(R.id.btn_webAppSettings);
        titles.add(Lang.getString(R.string.Settings));
        icons.add(R.drawable.baseline_settings_24);
      }
      ids.add(R.id.btn_webAppReload);
      titles.add(Lang.getString(R.string.WebAppReload));
      icons.add(R.drawable.baseline_replay_24);
      if (args.chatId == 0 || tdlib.chatUserId(args.chatId) != args.botUserId || args.owner == null) {
        ids.add(R.id.btn_webAppOpenBot);
        titles.add(Lang.getString(R.string.OpenBot));
        icons.add(R.drawable.deproko_baseline_bots_24);
      }
      if (getShareLink() != null) {
        ids.add(R.id.btn_webAppShare);
        titles.add(Lang.getString(R.string.Share));
        icons.add(R.drawable.baseline_share_arrow_24);
      }
      if (isAddedToAttachmentMenu) {
        ids.add(R.id.btn_webAppRemoveFromMenu);
        titles.add(Lang.getString(R.string.WebAppRemoveFromMenu));
        icons.add(R.drawable.baseline_delete_24);
      }
      int[] idsArray = new int[ids.size()];
      int[] iconsArray = new int[icons.size()];
      for (int i = 0; i < idsArray.length; i++) {
        idsArray[i] = ids.get(i);
        iconsArray[i] = icons.get(i);
      }
      showMore(idsArray, titles.toArray(new String[0]), iconsArray, 0);
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (isDestroyed()) {
      return;
    }
    final Args args = getArgumentsStrict();
    if (id == R.id.btn_webAppSettings) {
      markUserInteraction();
      receiveEvent("settings_button_pressed", null);
    } else if (id == R.id.btn_webAppReload) {
      if (webView != null) {
        resetNativeState();
        webView.reload();
      }
    } else if (id == R.id.btn_webAppOpenBot) {
      tdlib.ui().openPrivateChat(this, args.botUserId, null);
    } else if (id == R.id.btn_webAppShare) {
      String link = getShareLink();
      if (link != null) {
        tdlib.ui().shareUrl(this, link);
      }
    } else if (id == R.id.btn_webAppRemoveFromMenu) {
      showConfirm(Lang.getString(R.string.WebAppRemoveFromMenuConfirm, botName()), Lang.getString(R.string.WebAppRemoveFromMenu), R.drawable.baseline_delete_24, OptionColor.RED, () ->
        tdlib.send(new TdApi.ToggleBotIsAddedToAttachmentMenu(args.botUserId, false, false), (ok, error) -> UI.post(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            isAddedToAttachmentMenu = false;
            UI.showToast(Lang.getString(R.string.WebAppRemovedFromMenu, botName()), android.widget.Toast.LENGTH_SHORT);
          }
        }))
      );
    }
  }

  @Nullable
  private String getShareLink () {
    Args args = getArgumentsStrict();
    TdApi.User bot = tdlib.cache().user(args.botUserId);
    String username = bot != null ? Td.primaryUsername(bot) : null;
    if (StringUtils.isEmpty(username)) {
      return null;
    }
    if (!StringUtils.isEmpty(args.webAppShortName)) {
      return "https://t.me/" + username + "/" + args.webAppShortName;
    }
    if (args.source == SOURCE_MAIN_APP || args.source == SOURCE_MENU_BUTTON) {
      return "https://t.me/" + username + "?startapp";
    }
    return "https://t.me/" + username;
  }

  private String botName () {
    Args args = getArgumentsStrict();
    String name = tdlib.cache().userDisplayName(args.botUserId, false, false);
    return StringUtils.isEmpty(name) ? (args.title != null ? args.title : "") : name;
  }

  // WebView setup

  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  @Override
  protected void onCreateWebView (DoubleHeaderView headerCell, WebView webView) {
    this.webView = webView;
    final Args args = getArgumentsStrict();
    activeControllers.add(this);

    headerCell.setTitle(!StringUtils.isEmpty(args.title) ? args.title : botName());
    TdApi.User bot = tdlib.cache().user(args.botUserId);
    String username = bot != null ? Td.primaryUsername(bot) : null;
    headerCell.setSubtitle(!StringUtils.isEmpty(username) ? "@" + username : Lang.getString(R.string.Bot));

    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setGeolocationEnabled(false);
    settings.setSupportMultipleWindows(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSaveFormData(false);
    settings.setMediaPlaybackRequiresUserGesture(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      settings.setAllowFileAccessFromFileURLs(false);
      settings.setAllowUniversalAccessFromFileURLs(false);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      // Official Telegram apps accept third-party cookies inside Mini Apps (needed by embedded auth / payment iframes).
      CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      settings.setSafeBrowsingEnabled(true);
    }

    webView.setWebViewClient(new WebViewClient() {
      @SuppressWarnings("deprecation")
      @Override
      public boolean shouldOverrideUrlLoading (WebView view, String url) {
        return handleNavigation(url, true);
      }

      @Override
      public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
        return handleNavigation(request.getUrl() != null ? request.getUrl().toString() : null, request.isForMainFrame());
      }

      @Override
      public void onPageFinished (WebView view, String url) {
        super.onPageFinished(view, url);
        onPageProgress(1f);
        sendViewport(true);
      }
    });
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged (WebView view, int newProgress) {
        onPageProgress((float) newProgress / 100f);
      }

      @Override
      public void onGeolocationPermissionsShowPrompt (String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, false, false);
      }

      @Override
      public void onPermissionRequest (PermissionRequest request) {
        request.deny();
      }

      @Override
      public boolean onShowFileChooser (WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        return false;
      }
    });
    webView.setOnTouchListener((v, event) -> {
      if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_DOWN) {
        markUserInteraction();
      }
      return false;
    });
    webView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      if (bottom - top != oldBottom - oldTop) {
        sendViewport(true);
      }
    });
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      // Below API 17 addJavascriptInterface is unsafe, so the bridge is not exposed there.
      webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
    }

    TdApi.UserFullInfo fullInfo = tdlib.cache().userFull(args.botUserId);
    if (fullInfo != null && fullInfo.botInfo != null) {
      int bg = Theme.isDark() ? fullInfo.botInfo.webAppBackgroundDarkColor : fullInfo.botInfo.webAppBackgroundLightColor;
      int header = Theme.isDark() ? fullInfo.botInfo.webAppHeaderDarkColor : fullInfo.botInfo.webAppHeaderLightColor;
      if (bg != -1) {
        webView.setBackgroundColor(0xff000000 | bg);
      }
      if (header != -1) {
        customHeaderColor = 0xff000000 | header;
      }
    }

    if (bot != null && bot.type instanceof TdApi.UserTypeBot && ((TdApi.UserTypeBot) bot.type).canBeAddedToAttachmentMenu) {
      tdlib.send(new TdApi.GetAttachmentMenuBot(args.botUserId), (attachmentMenuBot, error) -> {
        if (attachmentMenuBot != null) {
          UI.post(() -> isAddedToAttachmentMenu = attachmentMenuBot.isAdded);
        }
      });
    }

    if (isSecureUrl(args.url)) {
      webView.loadUrl(args.url);
    } else {
      UI.showToast(R.string.WebAppInsecureUrl, android.widget.Toast.LENGTH_SHORT);
    }
  }

  @Override
  protected void onCreateContentView (FrameLayoutFix contentView, WebView webView) {
    contentView.removeView(webView);

    wrapView = new LinearLayout(context);
    wrapView.setOrientation(LinearLayout.VERTICAL);
    wrapView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    webView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    wrapView.addView(webView);

    bottomBar = new LinearLayout(context);
    bottomBar.setOrientation(LinearLayout.HORIZONTAL);
    bottomBar.setPadding(Screen.dp(8f), Screen.dp(4f), Screen.dp(8f), Screen.dp(4f));
    bottomBar.setBackgroundColor(Theme.getColor(ColorId.filling));
    bottomBar.setVisibility(View.GONE);
    bottomBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    wrapView.addView(bottomBar);

    mainButton = new BottomButton(context);
    mainButton.setOnClickListener(v -> {
      if (mainButton.isActive) {
        markUserInteraction();
        receiveEvent("main_button_pressed", null);
      }
    });
    secondaryButton = new BottomButton(context);
    secondaryButton.setOnClickListener(v -> {
      if (secondaryButton.isActive) {
        markUserInteraction();
        receiveEvent("secondary_button_pressed", null);
      }
    });

    contentView.addView(wrapView);
  }

  @Override
  public View getViewForApplyingOffsets () {
    return wrapView;
  }

  @Override
  protected void onBottomInsetChanged (int extraBottomInset, int extraBottomInsetWithoutIme, boolean isImeInset) {
    super.onBottomInsetChanged(extraBottomInset, extraBottomInsetWithoutIme, isImeInset);
    if (wrapView != null) {
      wrapView.setPadding(0, 0, 0, extraBottomInset);
    }
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    return !needCloseConfirmation && !isBackButtonVisible && (y < Size.getHeaderPortraitSize() || x <= Screen.dp(15f));
  }

  // Back & close

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (isClosing) {
      return super.performOnBackPressed(fromTop, commit);
    }
    if (isBackButtonVisible && !fromTop) {
      if (commit) {
        receiveEvent("back_button_pressed", null);
      }
      return true;
    }
    if (needCloseConfirmation) {
      if (commit) {
        showCloseConfirmation();
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  private void showCloseConfirmation () {
    showConfirm(Lang.getString(R.string.WebAppCloseConfirm), Lang.getString(R.string.WebAppCloseAnyway), R.drawable.baseline_close_24, OptionColor.RED, this::forceClose);
  }

  public void forceClose () {
    if (isDestroyed() || isClosing) {
      return;
    }
    isClosing = true;
    NavigationController navigation = navigationController();
    if (navigation != null && navigation.getCurrentStackItem() == this) {
      navigateBack();
    } else if (navigation != null && navigation.getStack().indexOf(this) != -1) {
      navigation.getStack().destroy(navigation.getStack().indexOf(this));
    } else {
      destroy();
    }
  }

  private void requestClose () {
    if (needCloseConfirmation) {
      showCloseConfirmation();
    } else {
      forceClose();
    }
  }

  @Override
  public void onFocus () {
    super.onFocus();
    applyHeaderColor();
    evaluate("window.Telegram&&window.Telegram.WebView&&window.Telegram.WebView.receiveEvent('visibility_changed',{is_visible:true});");
  }

  @Override
  public void onBlur () {
    super.onBlur();
    evaluate("window.Telegram&&window.Telegram.WebView&&window.Telegram.WebView.receiveEvent('visibility_changed',{is_visible:false});");
  }

  @Override
  public void destroy () {
    activeControllers.remove(this);
    if (getArguments() != null && getArgumentsStrict().launchId != 0) {
      tdlib.send(new TdApi.CloseWebApp(getArgumentsStrict().launchId), tdlib.typedOkHandler());
    }
    super.destroy();
    webView = null;
  }

  // Theme

  @Override
  public void onThemeColorsChanged (boolean areTemp, ColorState state) {
    super.onThemeColorsChanged(areTemp, state);
    if (!areTemp) {
      sendTheme();
      if (bottomBar != null && bottomBarColor == -1) {
        bottomBar.setBackgroundColor(Theme.getColor(ColorId.filling));
      }
      updateButtons();
      applyHeaderColor();
    }
  }

  private int bottomBarColor = -1;

  private static String hex (int color) {
    return String.format(Locale.US, "#%06x", color & 0xffffff);
  }

  public static JSONObject buildThemeParams () {
    JSONObject theme = new JSONObject();
    try {
      theme.put("bg_color", hex(Theme.getColor(ColorId.filling)));
      theme.put("text_color", hex(Theme.getColor(ColorId.text)));
      theme.put("hint_color", hex(Theme.getColor(ColorId.textLight)));
      theme.put("link_color", hex(Theme.getColor(ColorId.textLink)));
      theme.put("button_color", hex(Theme.getColor(ColorId.fillingPositive)));
      theme.put("button_text_color", hex(Theme.getColor(ColorId.fillingPositiveContent)));
      theme.put("secondary_bg_color", hex(Theme.getColor(ColorId.background)));
      theme.put("header_bg_color", hex(Theme.getColor(ColorId.filling)));
      theme.put("bottom_bar_bg_color", hex(Theme.getColor(ColorId.filling)));
      theme.put("accent_text_color", hex(Theme.getColor(ColorId.textLink)));
      theme.put("section_bg_color", hex(Theme.getColor(ColorId.filling)));
      theme.put("section_header_text_color", hex(Theme.getColor(ColorId.textLink)));
      theme.put("section_separator_color", hex(Theme.getColor(ColorId.separator)));
      theme.put("subtitle_text_color", hex(Theme.getColor(ColorId.textLight)));
      theme.put("destructive_text_color", hex(Theme.getColor(ColorId.textNegative)));
    } catch (JSONException ignored) { }
    return theme;
  }

  /** Theme for TDLib: OpenWebApp & co. pass it to the server, which puts it into tgWebAppThemeParams. */
  public static TdApi.ThemeParameters buildTdThemeParameters () {
    return new TdApi.ThemeParameters(
      Theme.getColor(ColorId.filling) & 0xffffff,
      Theme.getColor(ColorId.background) & 0xffffff,
      Theme.getColor(ColorId.filling) & 0xffffff,
      Theme.getColor(ColorId.filling) & 0xffffff,
      Theme.getColor(ColorId.filling) & 0xffffff,
      Theme.getColor(ColorId.separator) & 0xffffff,
      Theme.getColor(ColorId.text) & 0xffffff,
      Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.textLight) & 0xffffff,
      Theme.getColor(ColorId.textNegative) & 0xffffff,
      Theme.getColor(ColorId.textLight) & 0xffffff,
      Theme.getColor(ColorId.textLink) & 0xffffff,
      Theme.getColor(ColorId.fillingPositive) & 0xffffff,
      Theme.getColor(ColorId.fillingPositiveContent) & 0xffffff
    );
  }

  private void sendTheme () {
    JSONObject data = new JSONObject();
    try {
      data.put("theme_params", buildThemeParams());
    } catch (JSONException ignored) { }
    receiveEvent("theme_changed", data);
  }

  private void applyHeaderColor () {
    if (headerView == null || !isFocused() || isDestroyed()) {
      return;
    }
    int color = customHeaderColor != -1 ? customHeaderColor : Theme.getColor(ColorId.filling);
    boolean isLight = isLightColor(color);
    int textColor = customHeaderColor != -1 ? (isLight ? 0xff000000 : 0xffffffff) : Theme.getColor(ColorId.text);
    int iconColor = customHeaderColor != -1 ? (isLight ? 0xff5a5a5a : 0xffffffff) : Theme.getColor(ColorId.icon);
    headerView.getFilling().setColor(color);
    headerView.updateBackButtonColor(this, iconColor);
    DoubleHeaderView headerCell = getCustomHeaderCell() instanceof DoubleHeaderView ? (DoubleHeaderView) getCustomHeaderCell() : null;
    if (headerCell != null) {
      headerCell.setTextColors(textColor, customHeaderColor != -1 ? (textColor & 0x00ffffff) | 0xa0000000 : Theme.getColor(ColorId.textLight));
    }
  }

  private static boolean isLightColor (int color) {
    double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
    return luminance > 0.6;
  }

  @Nullable
  private static Integer parseColor (@Nullable String color) {
    if (color == null || !color.matches("#[0-9a-fA-F]{6}")) {
      return null;
    }
    return 0xff000000 | Integer.parseInt(color.substring(1), 16);
  }

  // Viewport

  private void sendViewport (boolean force) {
    if (webView == null || isDestroyed()) {
      return;
    }
    float density = context.getResources().getDisplayMetrics().density;
    int height = (int) (webView.getHeight() / density);
    if (height <= 0 || (!force && height == lastViewportHeight)) {
      return;
    }
    lastViewportHeight = height;
    JSONObject data = new JSONObject();
    try {
      data.put("height", height);
      data.put("is_state_stable", true);
      data.put("is_expanded", true);
    } catch (JSONException ignored) { }
    receiveEvent("viewport_changed", data);
  }

  private void sendSafeArea (String eventType) {
    JSONObject data = new JSONObject();
    try {
      data.put("top", 0);
      data.put("bottom", 0);
      data.put("left", 0);
      data.put("right", 0);
    } catch (JSONException ignored) { }
    receiveEvent(eventType, data);
  }

  // JS bridge

  @SuppressWarnings("unused")
  private final class Bridge {
    @JavascriptInterface
    public void postEvent (final String eventType, final String eventData) {
      UI.post(() -> handleEvent(eventType, eventData));
    }
  }

  private void markUserInteraction () {
    lastInteractionTime = SystemClock.uptimeMillis();
  }

  private boolean hasRecentInteraction (long ttlMs) {
    return lastInteractionTime != 0 && SystemClock.uptimeMillis() - lastInteractionTime <= ttlMs;
  }

  private void receiveEvent (String eventType, @Nullable JSONObject eventData) {
    String data = eventData != null ? eventData.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") : "null";
    evaluate("window.Telegram&&window.Telegram.WebView&&window.Telegram.WebView.receiveEvent(" + JSONObject.quote(eventType) + "," + data + ");");
  }

  private void evaluate (String script) {
    if (webView == null || isDestroyed()) {
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      webView.evaluateJavascript(script, null);
    } else {
      webView.loadUrl("javascript:" + script);
    }
  }

  private static JSONObject jsonObject (Object... keyValues) {
    JSONObject object = new JSONObject();
    try {
      for (int i = 0; i + 1 < keyValues.length; i += 2) {
        object.put((String) keyValues[i], keyValues[i + 1]);
      }
    } catch (JSONException ignored) { }
    return object;
  }

  private void handleEvent (@Nullable String eventType, @Nullable String eventData) {
    if (isDestroyed() || eventType == null || webView == null) {
      return;
    }
    JSONObject data;
    try {
      data = StringUtils.isEmpty(eventData) || "null".equals(eventData) ? new JSONObject() : new JSONObject(eventData);
    } catch (JSONException e) {
      data = new JSONObject();
    }
    final Args args = getArgumentsStrict();
    switch (eventType) {
      case "web_app_ready":
        onPageProgress(1f);
        break;
      case "web_app_expand":
        // Always shown expanded.
        sendViewport(true);
        break;
      case "web_app_close":
        forceClose();
        break;
      case "web_app_request_viewport":
        sendViewport(true);
        break;
      case "web_app_request_theme":
        sendTheme();
        break;
      case "web_app_request_safe_area":
        sendSafeArea("safe_area_changed");
        break;
      case "web_app_request_content_safe_area":
        sendSafeArea("content_safe_area_changed");
        break;
      case "web_app_set_header_color": {
        String colorKey = data.optString("color_key", null);
        Integer color = parseColor(data.optString("color", null));
        if ("bg_color".equals(colorKey)) {
          customHeaderColor = -1;
        } else if ("secondary_bg_color".equals(colorKey)) {
          customHeaderColor = Theme.getColor(ColorId.background);
        } else if (color != null) {
          customHeaderColor = color;
        } else {
          customHeaderColor = -1;
        }
        applyHeaderColor();
        break;
      }
      case "web_app_set_background_color": {
        Integer color = parseColor(data.optString("color", null));
        if (color != null) {
          webView.setBackgroundColor(color);
        }
        break;
      }
      case "web_app_set_bottom_bar_color": {
        Integer color = parseColor(data.optString("color", null));
        bottomBarColor = color != null ? color : -1;
        bottomBar.setBackgroundColor(color != null ? color : Theme.getColor(ColorId.filling));
        break;
      }
      case "web_app_setup_main_button":
        mainButton.setup(data, false);
        updateButtons();
        break;
      case "web_app_setup_secondary_button":
        secondaryButton.setup(data, true);
        secondaryPosition = data.optString("position", "left");
        updateButtons();
        break;
      case "web_app_setup_back_button":
        isBackButtonVisible = data.optBoolean("is_visible", false);
        break;
      case "web_app_setup_settings_button":
        isSettingsButtonVisible = data.optBoolean("is_visible", false);
        break;
      case "web_app_setup_closing_behavior":
        needCloseConfirmation = data.optBoolean("need_confirmation", false);
        break;
      case "web_app_setup_swipe_behavior":
      case "web_app_toggle_orientation_lock":
      case "iframe_ready":
      case "iframe_will_reload":
        break;
      case "web_app_open_link":
        openExternalLink(data.optString("url", null), data.optBoolean("try_instant_view", false));
        break;
      case "web_app_open_tg_link":
        openTelegramLink(data.optString("path_full", null));
        break;
      case "web_app_open_invoice": {
        String slug = data.optString("slug", "");
        UI.showToast(R.string.WebAppPaymentsUnsupported, android.widget.Toast.LENGTH_SHORT);
        receiveEvent("invoice_closed", jsonObject("slug", slug, "status", "failed"));
        break;
      }
      case "web_app_open_popup":
        openPopup(data);
        break;
      case "web_app_request_phone":
        requestPhone();
        break;
      case "web_app_request_write_access":
        requestWriteAccess();
        break;
      case "web_app_data_send":
        sendData(data.optString("data", ""));
        break;
      case "web_app_switch_inline_query":
        switchInlineQuery(data);
        break;
      case "web_app_trigger_haptic_feedback":
        triggerHaptic(data);
        break;
      case "web_app_read_text_from_clipboard":
        readClipboard(data.optString("req_id", ""));
        break;
      case "web_app_invoke_custom_method":
        invokeCustomMethod(data);
        break;
      case "web_app_request_fullscreen":
        receiveEvent("fullscreen_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_exit_fullscreen":
        receiveEvent("fullscreen_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_hide_keyboard": {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
          imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
        break;
      }
      case "web_app_check_home_screen":
        receiveEvent("home_screen_checked", jsonObject("status", "unsupported"));
        break;
      case "web_app_add_to_home_screen":
        receiveEvent("home_screen_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_biometry_get_info":
      case "web_app_biometry_request_access":
        receiveEvent("biometry_info_received", jsonObject("available", false));
        break;
      case "web_app_biometry_request_auth":
        receiveEvent("biometry_auth_requested", jsonObject("status", "failed"));
        break;
      case "web_app_biometry_update_token":
        receiveEvent("biometry_token_updated", jsonObject("status", "failed"));
        break;
      case "web_app_start_accelerometer":
      case "web_app_stop_accelerometer":
        receiveEvent("accelerometer_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_start_gyroscope":
      case "web_app_stop_gyroscope":
        receiveEvent("gyroscope_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_start_device_orientation":
      case "web_app_stop_device_orientation":
        receiveEvent("device_orientation_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_check_location":
        receiveEvent("location_checked", jsonObject("available", false));
        break;
      case "web_app_request_location":
        receiveEvent("location_requested", jsonObject("available", false));
        break;
      case "web_app_open_scan_qr_popup":
        receiveEvent("scan_qr_popup_closed", null);
        break;
      case "web_app_request_file_download":
        receiveEvent("file_download_requested", jsonObject("status", "cancelled"));
        break;
      case "web_app_send_prepared_message":
        receiveEvent("prepared_message_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_set_emoji_status":
        receiveEvent("emoji_status_failed", jsonObject("error", "UNSUPPORTED"));
        break;
      case "web_app_request_emoji_status_access":
        receiveEvent("emoji_status_access_requested", jsonObject("status", "cancelled"));
        break;
      case "web_app_device_storage_save_key":
      case "web_app_device_storage_get_key":
      case "web_app_device_storage_clear":
        receiveEvent("device_storage_failed", jsonObject("req_id", data.optString("req_id", ""), "error", "UNSUPPORTED"));
        break;
      case "web_app_secure_storage_save_key":
      case "web_app_secure_storage_get_key":
      case "web_app_secure_storage_restore_key":
      case "web_app_secure_storage_clear":
        receiveEvent("secure_storage_failed", jsonObject("req_id", data.optString("req_id", ""), "error", "UNSUPPORTED"));
        break;
      case "web_app_share_to_story":
      case "web_app_request_chat":
      default:
        // Not supported.
        break;
    }
  }

  // Links

  private static boolean isSecureUrl (@Nullable String url) {
    if (StringUtils.isEmpty(url)) {
      return false;
    }
    try {
      Uri uri = Uri.parse(url);
      return "https".equalsIgnoreCase(uri.getScheme()) && !StringUtils.isEmpty(uri.getHost());
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean isTelegramHost (@Nullable String host) {
    if (host == null) {
      return false;
    }
    host = host.toLowerCase(Locale.US);
    return host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog");
  }

  /** @return true if the navigation must be cancelled inside the WebView. */
  private boolean handleNavigation (@Nullable String url, boolean isMainFrame) {
    if (StringUtils.isEmpty(url)) {
      return true;
    }
    Uri uri;
    try {
      uri = Uri.parse(url);
    } catch (Throwable t) {
      return true;
    }
    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
    if ("tg".equals(scheme) || ("https".equals(scheme) && isTelegramHost(uri.getHost()))) {
      UI.post(() -> {
        if (!isDestroyed()) {
          tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters());
        }
      });
      return true;
    }
    if ("https".equals(scheme)) {
      if (!isMainFrame) {
        return false;
      }
      Args args = getArgumentsStrict();
      if (args.requireSameOrigin) {
        Uri original = Uri.parse(args.url);
        if (original.getHost() == null || !original.getHost().equalsIgnoreCase(uri.getHost())) {
          UI.post(() -> {
            if (!isDestroyed()) {
              tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().requireOpenPrompt().disableInstantView());
            }
          });
          return true;
        }
      }
      return false;
    }
    if ("http".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
      // Only https is loaded inside the Mini App; anything else is opened outside after a confirmation.
      if (hasRecentInteraction(10000)) {
        UI.post(() -> {
          if (!isDestroyed()) {
            tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().requireOpenPrompt().disableInstantView());
          }
        });
      }
      return true;
    }
    // file:, content:, intent:, javascript:, data: and unknown schemes are blocked.
    return true;
  }

  private void openExternalLink (@Nullable String url, boolean tryInstantView) {
    if (StringUtils.isEmpty(url) || url.length() > 4096) {
      return;
    }
    // Must be called in response to a user interaction (1 second TTL), at most once per interaction.
    if (!hasRecentInteraction(1000) || lastLinkOpenTime >= lastInteractionTime) {
      return;
    }
    Uri uri;
    try {
      uri = Uri.parse(url);
    } catch (Throwable t) {
      return;
    }
    String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.US) : "";
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      return;
    }
    lastLinkOpenTime = SystemClock.uptimeMillis();
    TdlibUi.UrlOpenParameters params = new TdlibUi.UrlOpenParameters().requireOpenPrompt();
    if (!tryInstantView) {
      params.disableInstantView();
    }
    tdlib.ui().openUrl(this, url, params);
  }

  private void openTelegramLink (@Nullable String pathFull) {
    if (StringUtils.isEmpty(pathFull) || !pathFull.startsWith("/") || pathFull.length() > 4096) {
      return;
    }
    tdlib.ui().openUrl(this, "https://t.me" + pathFull, new TdlibUi.UrlOpenParameters());
  }

  // Popup

  private void openPopup (JSONObject data) {
    if (isPopupVisible) {
      return;
    }
    long now = SystemClock.uptimeMillis();
    long oldest = popupTimes[popupTimesIndex];
    if (oldest != 0 && now - oldest < 3000) {
      return; // max 3 popups in 3 seconds
    }
    String title = data.optString("title", "");
    String message = data.optString("message", "");
    JSONArray buttons = data.optJSONArray("buttons");
    if (StringUtils.isEmpty(message) || message.length() > 256 || title.length() > 64 || buttons == null || buttons.length() < 1 || buttons.length() > 3) {
      return;
    }
    final String[] buttonIds = new String[buttons.length()];
    final String[] buttonTexts = new String[buttons.length()];
    final boolean[] destructive = new boolean[buttons.length()];
    for (int i = 0; i < buttons.length(); i++) {
      JSONObject button = buttons.optJSONObject(i);
      if (button == null) {
        return;
      }
      String type = button.optString("type", "default");
      String text = button.optString("text", "");
      buttonIds[i] = button.optString("id", "");
      switch (type) {
        case "ok":
          text = Lang.getOK();
          break;
        case "close":
          text = Lang.getString(R.string.WebAppClose);
          break;
        case "cancel":
          text = Lang.getString(R.string.Cancel);
          break;
        case "destructive":
          destructive[i] = true;
          // fallthrough
        case "default":
          if (StringUtils.isEmpty(text.trim()) || text.length() > 64) {
            return;
          }
          break;
        default:
          return;
      }
      buttonTexts[i] = text;
    }
    popupTimes[popupTimesIndex] = now;
    popupTimesIndex = (popupTimesIndex + 1) % popupTimes.length;
    isPopupVisible = true;

    final String[] chosen = new String[1];
    AlertDialog.Builder b = new AlertDialog.Builder(context(), Theme.dialogTheme());
    if (!StringUtils.isEmpty(title)) {
      b.setTitle(title);
    }
    b.setMessage(message);
    DialogInterface.OnClickListener listener = (dialog, which) -> {
      int index;
      if (which == DialogInterface.BUTTON_POSITIVE) {
        index = buttonIds.length - 1;
      } else if (which == DialogInterface.BUTTON_NEGATIVE) {
        index = buttonIds.length >= 2 ? buttonIds.length - 2 : -1;
      } else {
        index = 0;
      }
      if (index >= 0) {
        chosen[0] = buttonIds[index];
      }
    };
    // Last button is the primary one, then the one before it, then the first one (when there are 3).
    b.setPositiveButton(buttonTexts[buttonTexts.length - 1], listener);
    if (buttonTexts.length >= 2) {
      b.setNegativeButton(buttonTexts[buttonTexts.length - 2], listener);
    }
    if (buttonTexts.length == 3) {
      b.setNeutralButton(buttonTexts[0], listener);
    }
    b.setOnDismissListener(dialog -> {
      isPopupVisible = false;
      markUserInteraction();
      receiveEvent("popup_closed", chosen[0] != null ? jsonObject("button_id", chosen[0]) : new JSONObject());
    });
    AlertDialog dialog = showAlert(b);
    if (dialog != null) {
      int negativeColor = Theme.getColor(ColorId.textNegative);
      if (destructive[buttonTexts.length - 1] && dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(negativeColor);
      }
      if (buttonTexts.length >= 2 && destructive[buttonTexts.length - 2] && dialog.getButton(DialogInterface.BUTTON_NEGATIVE) != null) {
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(negativeColor);
      }
      if (buttonTexts.length == 3 && destructive[0] && dialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null) {
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(negativeColor);
      }
    } else {
      isPopupVisible = false;
    }
  }

  // Phone & write access

  private void requestPhone () {
    if (isPhoneRequestVisible) {
      return;
    }
    final Args args = getArgumentsStrict();
    final TdApi.User me = tdlib.myUser();
    if (me == null || StringUtils.isEmpty(me.phoneNumber)) {
      receiveEvent("phone_requested", jsonObject("status", "cancelled"));
      return;
    }
    isPhoneRequestVisible = true;
    final boolean[] handled = new boolean[1];
    AlertDialog.Builder b = new AlertDialog.Builder(context(), Theme.dialogTheme());
    b.setTitle(Lang.getString(R.string.ShareYourPhoneNumberTitle));
    b.setMessage(Lang.getString(R.string.WebAppPhoneRequest, botName()));
    b.setPositiveButton(Lang.getString(R.string.WebAppShareAction), (dialog, which) -> {
      handled[0] = true;
      sharePhone(args.botUserId, me);
    });
    b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
    b.setOnDismissListener(dialog -> {
      isPhoneRequestVisible = false;
      if (!handled[0]) {
        receiveEvent("phone_requested", jsonObject("status", "cancelled"));
      }
    });
    if (showAlert(b) == null) {
      isPhoneRequestVisible = false;
    }
  }

  private void sharePhone (long botUserId, TdApi.User me) {
    TdApi.UserFullInfo fullInfo = tdlib.cache().userFull(botUserId);
    Runnable send = () -> tdlib.send(new TdApi.CreatePrivateChat(botUserId, false), (chat, error) -> {
      if (error != null) {
        UI.post(() -> receiveEvent("phone_requested", jsonObject("status", "cancelled")));
        return;
      }
      TdApi.InputMessageContact content = new TdApi.InputMessageContact(new TdApi.Contact(me.phoneNumber, me.firstName, me.lastName, "", me.id));
      tdlib.send(new TdApi.SendMessage(chat.id, null, null, null, null, content), (message, sendError) -> UI.post(() ->
        receiveEvent("phone_requested", jsonObject("status", sendError != null ? "cancelled" : "sent"))
      ));
    });
    if (fullInfo != null && fullInfo.blockList != null) {
      tdlib.send(new TdApi.SetMessageSenderBlockList(new TdApi.MessageSenderUser(botUserId), null), (ok, error) -> {
        if (error != null) {
          UI.post(() -> receiveEvent("phone_requested", jsonObject("status", "cancelled")));
        } else {
          send.run();
        }
      });
    } else {
      send.run();
    }
  }

  private void requestWriteAccess () {
    if (isWriteAccessRequestVisible) {
      return;
    }
    final long botUserId = getArgumentsStrict().botUserId;
    tdlib.send(new TdApi.CanBotSendMessages(botUserId), (ok, error) -> UI.post(() -> {
      if (isDestroyed()) {
        return;
      }
      if (error == null) {
        receiveEvent("write_access_requested", jsonObject("status", "allowed"));
        return;
      }
      isWriteAccessRequestVisible = true;
      final boolean[] handled = new boolean[1];
      AlertDialog.Builder b = new AlertDialog.Builder(context(), Theme.dialogTheme());
      b.setMessage(Lang.getString(R.string.WebAppWriteAccessRequest, botName()));
      b.setPositiveButton(Lang.getString(R.string.Allow), (dialog, which) -> {
        handled[0] = true;
        tdlib.send(new TdApi.AllowBotToSendMessages(botUserId), (allowed, allowError) -> UI.post(() ->
          receiveEvent("write_access_requested", jsonObject("status", allowError != null ? "cancelled" : "allowed"))
        ));
      });
      b.setNegativeButton(Lang.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
      b.setOnDismissListener(dialog -> {
        isWriteAccessRequestVisible = false;
        if (!handled[0]) {
          receiveEvent("write_access_requested", jsonObject("status", "cancelled"));
        }
      });
      if (showAlert(b) == null) {
        isWriteAccessRequestVisible = false;
      }
    }));
  }

  // Data & inline

  private boolean dataSent;

  private void sendData (String data) {
    final Args args = getArgumentsStrict();
    if (args.source != SOURCE_KEYBOARD_BUTTON || StringUtils.isEmpty(args.keyboardButtonText) || dataSent) {
      return;
    }
    if (data.isEmpty() || data.getBytes(StandardCharsets.UTF_8).length > 4096) {
      return;
    }
    dataSent = true;
    tdlib.send(new TdApi.SendWebAppData(args.botUserId, args.keyboardButtonText, data), tdlib.typedOkHandler());
    forceClose();
  }

  private void switchInlineQuery (JSONObject data) {
    final Args args = getArgumentsStrict();
    TdApi.User bot = tdlib.cache().user(args.botUserId);
    if (bot == null || !(bot.type instanceof TdApi.UserTypeBot) || !((TdApi.UserTypeBot) bot.type).isInline || !Td.hasUsername(bot)) {
      return;
    }
    String query = data.optString("query", "");
    if (query.length() > 256) {
      return;
    }
    JSONArray chatTypes = data.optJSONArray("chat_types");
    TdApi.TargetChat targetChat;
    if (chatTypes == null || chatTypes.length() == 0) {
      targetChat = new TdApi.TargetChatCurrent();
    } else {
      boolean users = false, bots = false, groups = false, channels = false;
      for (int i = 0; i < chatTypes.length(); i++) {
        switch (chatTypes.optString(i, "")) {
          case "users": users = true; break;
          case "bots": bots = true; break;
          case "groups": groups = true; break;
          case "channels": channels = true; break;
        }
      }
      if (!users && !bots && !groups && !channels) {
        return;
      }
      targetChat = new TdApi.TargetChatChosen(new TdApi.TargetChatTypes(users, bots, groups, channels));
    }
    final TdApi.InlineKeyboardButtonTypeSwitchInline switchInline = new TdApi.InlineKeyboardButtonTypeSwitchInline(query, targetChat);
    final MessagesController owner = args.owner;
    final String username = Td.primaryUsername(bot);
    forceClose();
    UI.post(() -> {
      if (owner != null && !owner.isDestroyed() && targetChat instanceof TdApi.TargetChatCurrent) {
        owner.switchInline(args.botUserId, switchInline);
      } else {
        ViewController<?> c = context.navigation().getCurrentStackItem();
        if (c != null && !c.isDestroyed()) {
          tdlib.ui().switchInline(c, username, query, false);
        }
      }
    }, 300);
  }

  // Haptics & clipboard

  private void triggerHaptic (JSONObject data) {
    if (webView == null) {
      return;
    }
    int feedback;
    switch (data.optString("type", "")) {
      case "impact": {
        String style = data.optString("impact_style", "medium");
        feedback = "heavy".equals(style) || "rigid".equals(style) ? HapticFeedbackConstants.LONG_PRESS :
          "light".equals(style) || "soft".equals(style) ? HapticFeedbackConstants.KEYBOARD_TAP :
          HapticFeedbackConstants.VIRTUAL_KEY;
        break;
      }
      case "notification": {
        String type = data.optString("notification_type", "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          feedback = "error".equals(type) || "warning".equals(type) ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.CONFIRM;
        } else {
          feedback = "error".equals(type) ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.VIRTUAL_KEY;
        }
        break;
      }
      case "selection_change":
        feedback = HapticFeedbackConstants.CLOCK_TICK;
        break;
      default:
        return;
    }
    webView.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
  }

  private void readClipboard (String reqId) {
    String text = null;
    // Only in response to a user interaction within the last 10 seconds.
    if (hasRecentInteraction(10000)) {
      try {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
          ClipData clip = clipboard.getPrimaryClip();
          if (clip != null && clip.getItemCount() > 0) {
            CharSequence value = clip.getItemAt(0).coerceToText(context);
            text = value != null ? value.toString() : null;
          }
        }
      } catch (Throwable ignored) { }
    }
    JSONObject result = jsonObject("req_id", reqId);
    if (text != null) {
      try {
        result.put("data", text);
      } catch (JSONException ignored) { }
    }
    receiveEvent("clipboard_text_received", result);
  }

  // Custom methods (CloudStorage, etc.)

  private void invokeCustomMethod (JSONObject data) {
    final String reqId = data.optString("req_id", "");
    final String method = data.optString("method", "");
    if (StringUtils.isEmpty(reqId) || StringUtils.isEmpty(method)) {
      return;
    }
    Object params = data.opt("params");
    String paramsJson = params != null ? params.toString() : "{}";
    tdlib.send(new TdApi.SendWebAppCustomRequest(getArgumentsStrict().botUserId, method, paramsJson), (result, error) -> UI.post(() -> {
      JSONObject response = jsonObject("req_id", reqId);
      try {
        if (error != null) {
          response.put("error", error.message);
        } else {
          Object value;
          try {
            value = new org.json.JSONTokener(result.result).nextValue();
          } catch (JSONException e) {
            value = result.result;
          }
          response.put("result", value);
        }
      } catch (JSONException ignored) { }
      receiveEvent("custom_method_invoked", response);
    }));
  }

  // Native buttons

  private void resetNativeState () {
    isBackButtonVisible = false;
    isSettingsButtonVisible = false;
    needCloseConfirmation = false;
    mainButton.reset();
    secondaryButton.reset();
    updateButtons();
  }

  private void updateButtons () {
    if (bottomBar == null) {
      return;
    }
    boolean main = mainButton.isButtonShown();
    boolean secondary = secondaryButton.isButtonShown();
    bottomBar.removeAllViews();
    if (!main && !secondary) {
      bottomBar.setVisibility(View.GONE);
      return;
    }
    boolean vertical = "top".equals(secondaryPosition) || "bottom".equals(secondaryPosition);
    bottomBar.setOrientation(vertical && main && secondary ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    BottomButton first, second;
    if ("right".equals(secondaryPosition) || "bottom".equals(secondaryPosition)) {
      first = main ? mainButton : null;
      second = secondary ? secondaryButton : null;
    } else {
      first = secondary ? secondaryButton : null;
      second = main ? mainButton : null;
    }
    boolean horizontal = bottomBar.getOrientation() == LinearLayout.HORIZONTAL;
    for (BottomButton button : new BottomButton[] {first, second}) {
      if (button == null) {
        continue;
      }
      LinearLayout.LayoutParams params = horizontal ?
        new LinearLayout.LayoutParams(0, Screen.dp(48f), 1f) :
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Screen.dp(48f));
      params.setMargins(Screen.dp(4f), Screen.dp(4f), Screen.dp(4f), Screen.dp(4f));
      button.setLayoutParams(params);
      button.applyColors();
      bottomBar.addView(button);
    }
    bottomBar.setVisibility(View.VISIBLE);
  }

  private static class BottomButton extends FrameLayout {
    private final TextView textView;
    private final ProgressBar progressView;
    private final GradientDrawable background;

    boolean isVisible, isActive = true, isProgressVisible;
    String text = "";
    @Nullable Integer color, textColor;

    public BottomButton (Context context) {
      super(context);
      background = new GradientDrawable();
      background.setCornerRadius(Screen.dp(8f));
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), background, null));
      } else {
        setBackgroundDrawable(background);
      }
      textView = new TextView(context);
      textView.setTypeface(Fonts.getRobotoMedium());
      textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
      textView.setSingleLine(true);
      textView.setEllipsize(TextUtils.TruncateAt.END);
      textView.setGravity(Gravity.CENTER);
      textView.setPadding(Screen.dp(12f), 0, Screen.dp(12f), 0);
      textView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      addView(textView);
      progressView = new ProgressBar(context);
      progressView.setIndeterminate(true);
      progressView.setVisibility(View.GONE);
      progressView.setLayoutParams(new FrameLayout.LayoutParams(Screen.dp(22f), Screen.dp(22f), Gravity.CENTER));
      addView(progressView);
    }

    void reset () {
      isVisible = false;
      isActive = true;
      isProgressVisible = false;
      text = "";
      color = textColor = null;
    }

    void setup (JSONObject data, boolean isSecondary) {
      isVisible = data.optBoolean("is_visible", false);
      isActive = data.optBoolean("is_active", true);
      isProgressVisible = data.optBoolean("is_progress_visible", false);
      text = data.optString("text", "");
      color = parseColor(data.optString("color", null));
      textColor = parseColor(data.optString("text_color", null));
    }

    boolean isButtonShown () {
      return isVisible && !StringUtils.isEmpty(text.trim());
    }

    void applyColors () {
      int bg = color != null ? color : Theme.getColor(ColorId.fillingPositive);
      int fg = textColor != null ? textColor : Theme.getColor(ColorId.fillingPositiveContent);
      background.setColor(bg);
      textView.setTextColor(fg);
      textView.setText(text);
      textView.setVisibility(isProgressVisible ? View.INVISIBLE : View.VISIBLE);
      progressView.setVisibility(isProgressVisible ? View.VISIBLE : View.GONE);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        progressView.setIndeterminateTintList(ColorStateList.valueOf(fg));
      }
      setAlpha(isActive ? 1f : 0.5f);
      setEnabled(isActive);
    }
  }
}
