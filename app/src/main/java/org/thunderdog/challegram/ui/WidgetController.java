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
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.navigation.DoubleHeaderView;
import org.thunderdog.challegram.navigation.NavigationController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.Size;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Full-screen, sandboxed runner for bot HTML widgets ({@link TdApi.MessageWidget}).
 *
 * The widget HTML is loaded from memory with a fake per-message origin. All network loads,
 * navigation, file/content access, cookies and geolocation are blocked. A small
 * {@code window.TelegramWidget} shim (https://core.telegram.org/bots/widgets) bridges
 * state, links, clipboard and bot requests to TDLib.
 */
public class WidgetController extends WebkitController<WidgetController.Args> {
  public static class Args {
    public final TdApi.Message message;
    public final TdApi.MessageWidget widget;
    public final String title;

    public Args (TdApi.Message message, TdApi.MessageWidget widget, String title) {
      this.message = message;
      this.widget = widget;
      this.title = title;
    }
  }

  private static final String BRIDGE_NAME = "TgxWidgetBridge";
  private static final int MIN_POLL_PERIOD_SECONDS = 5;

  @Nullable private WebView webView;
  private boolean isWatchingPublicData, isPolling;
  private boolean requestInFlight;

  public WidgetController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  protected boolean hasSpecialProcessing () {
    return false;
  }

  private static WebResourceResponse emptyResponse () {
    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
  }

  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  @Override
  protected void onCreateWebView (DoubleHeaderView headerCell, WebView webView) {
    this.webView = webView;
    final Args args = getArgumentsStrict();
    headerCell.setTitle(args.title);
    if (!Td.isEmpty(args.widget.caption)) {
      headerCell.setSubtitle(args.widget.caption.text);
    }

    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setGeolocationEnabled(false);
    settings.setBlockNetworkLoads(true);
    settings.setBlockNetworkImage(true);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setSaveFormData(false);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      settings.setAllowFileAccessFromFileURLs(false);
      settings.setAllowUniversalAccessFromFileURLs(false);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      settings.setSafeBrowsingEnabled(false); // nothing is loaded from the network anyway
    }

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest (WebView view, String url) {
        return emptyResponse();
      }

      @Override
      public WebResourceResponse shouldInterceptRequest (WebView view, WebResourceRequest request) {
        return emptyResponse();
      }

      @SuppressWarnings("deprecation")
      @Override
      public boolean shouldOverrideUrlLoading (WebView view, String url) {
        handleNavigation(url);
        return true;
      }

      @Override
      public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
        handleNavigation(request.getUrl() != null ? request.getUrl().toString() : null);
        return true;
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
    });

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      // Below API 17 addJavascriptInterface exposes reflection, so the widget runs without the bridge there.
      webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
    }

    String baseUrl = "https://w" + Math.abs(args.message.chatId) + "-" + args.message.id + ".widget.tgx.invalid/";
    webView.loadDataWithBaseURL(baseUrl, buildDocument(args), "text/html", "utf-8", null);
  }

  // Navigation & links

  private void handleNavigation (@Nullable String url) {
    if (StringUtils.isEmpty(url)) {
      return;
    }
    Uri uri;
    try {
      uri = Uri.parse(url);
    } catch (Throwable t) {
      return;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return; // about:, data:, javascript:, http: and other schemes are ignored
    }
    UI.post(() -> {
      if (isTelegramHost(uri.getHost())) {
        openTelegramLink(url);
      } else {
        openLink(url);
      }
    });
  }

  private static boolean isTelegramHost (@Nullable String host) {
    if (host == null) {
      return false;
    }
    host = host.toLowerCase(Locale.US);
    return host.equals("t.me") || host.equals("telegram.me") || host.equals("telegram.dog");
  }

  private static boolean isValidLink (@Nullable String url, boolean telegram) {
    if (url == null || url.length() > 256) {
      return false;
    }
    Uri uri;
    try {
      uri = Uri.parse(url);
    } catch (Throwable t) {
      return false;
    }
    return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.isEmpty(uri.getUserInfo()) && isTelegramHost(uri.getHost()) == telegram;
  }

  private void openLink (String url) {
    if (isDestroyed()) {
      return;
    }
    if (!isValidLink(url, false)) {
      UI.showToast(R.string.WidgetOpenLinkInvalid, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters().requireOpenPrompt().disableInstantView());
  }

  private void openTelegramLink (String url) {
    if (isDestroyed()) {
      return;
    }
    if (!isValidLink(url, true)) {
      UI.showToast(R.string.WidgetOpenLinkInvalid, android.widget.Toast.LENGTH_SHORT);
      return;
    }
    tdlib.ui().openUrl(this, url, new TdlibUi.UrlOpenParameters());
  }

  // JS bridge

  @SuppressWarnings("unused")
  private final class Bridge {
    @JavascriptInterface
    public void postEvent (final String eventType, final String eventData) {
      UI.post(() -> handleEvent(eventType, eventData));
    }
  }

  private void handleEvent (String eventType, String eventData) {
    if (isDestroyed() || eventType == null) {
      return;
    }
    JSONObject data;
    try {
      data = new JSONObject(StringUtils.isEmpty(eventData) ? "{}" : eventData);
    } catch (JSONException e) {
      data = new JSONObject();
    }
    switch (eventType) {
      case "copyText": {
        String text = data.optString("text", "");
        if (!text.isEmpty() && text.length() <= 256) {
          UI.copyText(text, R.string.CopiedText);
        }
        break;
      }
      case "openLink":
        openLink(data.optString("url", null));
        break;
      case "openTelegramLink":
        openTelegramLink(data.optString("url", null));
        break;
      case "request":
        sendRequest(data.optInt("id"), data.optString("data", ""));
        break;
      case "watchPublicData":
        isWatchingPublicData = true;
        schedulePoll(0);
        break;
      case "unwatchPublicData":
        isWatchingPublicData = false;
        break;
      case "ready":
      case "setResizeAnchor":
      default:
        break;
    }
  }

  private void sendRequest (final int requestId, String data) {
    final Args args = getArgumentsStrict();
    if (!args.widget.canSendQueries) {
      resolveRequest(requestId, false, "NOT_ALLOWED");
      return;
    }
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    if (bytes.length == 0 || bytes.length > 64) {
      resolveRequest(requestId, false, "FAILED");
      return;
    }
    if (requestInFlight) {
      resolveRequest(requestId, false, "BUSY");
      return;
    }
    requestInFlight = true;
    tdlib.send(new TdApi.GetWidgetQueryAnswer(args.message.chatId, args.message.id, new TdApi.WidgetQueryTypeData(false), bytes), (answer, error) -> UI.post(() -> {
      requestInFlight = false;
      if (error != null) {
        String message = error.message != null ? error.message.toUpperCase(Locale.US) : "";
        String code;
        if (error.code == 429 || message.contains("FLOOD") || message.contains("RATE")) {
          code = "RATE_LIMITED";
        } else if (message.contains("TIMEOUT")) {
          code = "TIMEOUT";
        } else {
          code = "FAILED";
        }
        resolveRequest(requestId, false, code);
      } else {
        resolveRequest(requestId, true, answer.data);
      }
    }));
  }

  private void resolveRequest (int requestId, boolean ok, @Nullable String value) {
    evaluate("window.TelegramWidget && window.TelegramWidget._tgxResolve(" + requestId + "," + ok + "," + (value == null ? "null" : jsString(value)) + ");");
  }

  // Public data polling

  private void schedulePoll (long delayMs) {
    if (isPolling || isDestroyed()) {
      return;
    }
    isPolling = true;
    UI.post(this::pollPublicData, delayMs);
  }

  private void pollPublicData () {
    isPolling = false;
    if (isDestroyed() || !isWatchingPublicData) {
      return;
    }
    final Args args = getArgumentsStrict();
    tdlib.send(new TdApi.GetWidgetPublicData(args.message.chatId, args.message.id), (publicData, error) -> UI.post(() -> {
      if (isDestroyed()) {
        return;
      }
      int period = MIN_POLL_PERIOD_SECONDS * 6;
      if (publicData != null) {
        period = Math.max(MIN_POLL_PERIOD_SECONDS, publicData.pollPeriod);
        evaluate("window.TelegramWidget && window.TelegramWidget._tgxSetPublicData(" + (StringUtils.isEmpty(publicData.data) ? "null" : jsString(publicData.data)) + ");");
      }
      if (isWatchingPublicData) {
        schedulePoll(period * 1000L);
      }
    }));
  }

  // Lifecycle

  @Override
  public void onActivityPause () {
    super.onActivityPause();
    evaluate("window.TelegramWidget && window.TelegramWidget._tgxSetActive(false);");
  }

  @Override
  public void onActivityResume () {
    super.onActivityResume();
    evaluate("window.TelegramWidget && window.TelegramWidget._tgxSetActive(true);");
  }

  @Override
  public void destroy () {
    isWatchingPublicData = false;
    super.destroy();
    webView = null;
  }

  @Override
  public boolean canSlideBackFrom (NavigationController navigationController, float x, float y) {
    return y < Size.getHeaderPortraitSize() || x <= Screen.dp(15f);
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

  // Document

  private static String jsString (String value) {
    return JSONObject.quote(value).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
  }

  private static String hex (int color) {
    return String.format(Locale.US, "#%06x", color & 0xffffff);
  }

  private JSONObject buildThemeParams () throws JSONException {
    JSONObject theme = new JSONObject();
    theme.put("bg_color", hex(Theme.getColor(ColorId.filling)));
    theme.put("text_color", hex(Theme.getColor(ColorId.text)));
    theme.put("hint_color", hex(Theme.getColor(ColorId.textLight)));
    theme.put("link_color", hex(Theme.getColor(ColorId.textLink)));
    theme.put("button_color", hex(Theme.getColor(ColorId.fillingPositive)));
    theme.put("button_text_color", hex(Theme.getColor(ColorId.fillingPositiveContent)));
    theme.put("secondary_bg_color", hex(Theme.getColor(ColorId.background)));
    theme.put("accent_text_color", hex(Theme.getColor(ColorId.textLink)));
    theme.put("subtitle_text_color", hex(Theme.getColor(ColorId.textLight)));
    theme.put("destructive_text_color", hex(Theme.getColor(ColorId.textNegative)));
    theme.put("section_bg_color", hex(Theme.getColor(ColorId.filling)));
    theme.put("section_header_text_color", hex(Theme.getColor(ColorId.textLink)));
    theme.put("section_separator_color", hex(Theme.getColor(ColorId.separator)));
    theme.put("header_bg_color", hex(Theme.getColor(ColorId.filling)));
    theme.put("bottom_bar_bg_color", hex(Theme.getColor(ColorId.filling)));
    return theme;
  }

  private String buildDocument (Args args) {
    final boolean isDark = Theme.isDark();
    String configJson;
    StringBuilder cssVars = new StringBuilder();
    try {
      JSONObject theme = buildThemeParams();
      java.util.Iterator<String> keys = theme.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        cssVars.append("--tg-theme-").append(key.replace('_', '-')).append(':').append(theme.getString(key)).append(" !important;");
      }
      JSONObject config = new JSONObject();
      config.put("colorScheme", isDark ? "dark" : "light");
      config.put("themeParams", theme);
      config.put("chatData", StringUtils.isEmpty(args.widget.chatData) ? JSONObject.NULL : args.widget.chatData);
      config.put("publicData", StringUtils.isEmpty(args.widget.publicData) ? JSONObject.NULL : args.widget.publicData);
      config.put("isForward", args.message.forwardInfo != null);
      config.put("canQuery", args.widget.canSendQueries);
      String chatType;
      if (args.message.forwardInfo != null) {
        chatType = null;
      } else if (tdlib.isUserChat(args.message.chatId)) {
        chatType = "private";
      } else if (tdlib.isChannel(args.message.chatId)) {
        chatType = "channel";
      } else if (tdlib.isSupergroup(args.message.chatId)) {
        chatType = "supergroup";
      } else {
        chatType = "group";
      }
      config.put("chatType", chatType != null ? chatType : JSONObject.NULL);
      TdApi.User me = args.widget.canSendQueries ? tdlib.myUser() : null;
      if (me != null) {
        JSONObject viewer = new JSONObject();
        viewer.put("id", me.id);
        if (!StringUtils.isEmpty(me.firstName)) viewer.put("first_name", me.firstName);
        if (!StringUtils.isEmpty(me.lastName)) viewer.put("last_name", me.lastName);
        if (me.usernames != null && !StringUtils.isEmpty(me.usernames.editableUsername)) viewer.put("username", me.usernames.editableUsername);
        if (me.isPremium) viewer.put("is_premium", true);
        config.put("viewer", viewer);
      } else {
        config.put("viewer", JSONObject.NULL);
      }
      configJson = config.toString().replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    } catch (JSONException e) {
      configJson = "{}";
    }

    String head =
      "<meta charset=\"utf-8\">" +
      "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">" +
      "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data:; media-src data:; font-src data:; connect-src 'none'; frame-src 'none'; form-action 'none'\">" +
      "<style>:root{" + cssVars + "--tg-color-scheme:" + (isDark ? "dark" : "light") + ";--tg-text-font-size:15px;--tg-text-line-height:1.35;--tg-text-padding:8px 12px;" +
      "--tg-content-safe-area-inset-top:0px;--tg-content-safe-area-inset-right:0px;--tg-content-safe-area-inset-bottom:0px;--tg-content-safe-area-inset-left:0px;" +
      "--tg-content-safe-area-corner-left:0px;--tg-content-safe-area-corner-right:0px;" +
      "--tg-button-bg:var(--tg-theme-button-color);--tg-button-text:var(--tg-theme-button-text-color);--tg-button-radius:8px;--tg-button-padding:8px 16px;--tg-button-height:36px;--tg-button-pressed-opacity:.7;--tg-button-disabled-opacity:.5;" +
      "color-scheme:" + (isDark ? "dark" : "light") + ";background:var(--tg-theme-bg-color)}" +
      "body{margin:8px 12px;color:var(--tg-theme-text-color);font-family:system-ui,sans-serif;font-size:var(--tg-text-font-size);line-height:var(--tg-text-line-height);-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}" +
      "a{color:var(--tg-theme-link-color)}" +
      "button{background:var(--tg-button-bg);color:var(--tg-button-text);border:0;border-radius:var(--tg-button-radius);padding:var(--tg-button-padding);min-height:var(--tg-button-height);font:inherit;font-weight:500}" +
      "button:active{opacity:var(--tg-button-pressed-opacity)}button[disabled]{opacity:var(--tg-button-disabled-opacity)}" +
      "hr,td,th{border-color:var(--tg-theme-section-separator-color)}</style>" +
      "<script>" + buildShim(configJson) + "</script>";

    String html = args.widget.html != null ? args.widget.html : "";
    int insertAt = afterTag(html, "<head");
    if (insertAt == -1) {
      insertAt = afterTag(html, "<html");
    }
    if (insertAt == -1) {
      int doctype = afterTag(html, "<!doctype");
      return doctype == -1 ? head + html : html.substring(0, doctype) + head + html.substring(doctype);
    }
    return html.substring(0, insertAt) + head + html.substring(insertAt);
  }

  /** Returns the index right after the end of the first {@code tagStart...>} tag, or -1. */
  private static int afterTag (String html, String tagStart) {
    int index = html.toLowerCase(Locale.US).indexOf(tagStart);
    if (index == -1) {
      return -1;
    }
    int end = html.indexOf('>', index);
    return end == -1 ? -1 : end + 1;
  }

  private static String buildShim (String configJson) {
    return "(function(){'use strict';" +
      "var cfg=" + configJson + ";" +
      "var B=window." + BRIDGE_NAME + ";try{delete window." + BRIDGE_NAME + ";}catch(e){}" +
      "var handlers={},pending={},seq=0,busy=false;" +
      "function post(t,d){try{if(B)B.postEvent(t,JSON.stringify(d||{}));}catch(e){}}" +
      "function fire(t){(handlers[t]||[]).slice().forEach(function(h){try{h.call(W);}catch(e){setTimeout(function(){throw e;});}});}" +
      "function err(c){var e=new Error(c);e.name='WidgetRequestError';e.code=c;return e;}" +
      "function isTg(h){h=(h||'').toLowerCase();return h==='t.me'||h==='telegram.me'||h==='telegram.dog';}" +
      "function check(url,tg){if(typeof url!=='string'||url.length>256)throw new TypeError('Invalid URL');var u;try{u=new URL(url);}catch(e){throw new TypeError('Invalid URL');}" +
      "if(u.protocol!=='https:'||u.username||u.password||isTg(u.hostname)!==tg)throw new TypeError('Invalid URL');}" +
      "var noop=function(){return this;};" +
      "var W={profile:1,isProfileAtLeast:function(n){return typeof n==='number'&&n<=1;}," +
      "colorScheme:cfg.colorScheme,theme:cfg.colorScheme,themeParams:Object.freeze(cfg.themeParams||{}),isActive:true,isPreview:false," +
      "isForward:!!cfg.isForward,chatType:cfg.chatType||null,viewer:cfg.viewer?Object.freeze(cfg.viewer):null," +
      "publicData:cfg.publicData==null?null:cfg.publicData,chatData:cfg.isForward||cfg.chatData==null?null:cfg.chatData,wallet:null," +
      "contentSafeAreaInset:Object.freeze({top:0,right:0,bottom:0,left:0,cornerLeft:0,cornerRight:0})," +
      "HapticFeedback:{impactOccurred:noop,notificationOccurred:noop,selectionChanged:noop}," +
      "onEvent:function(t,h){if(typeof h!=='function')return;var l=handlers[t]||(handlers[t]=[]);if(l.length<16&&l.indexOf(h)<0)l.push(h);}," +
      "offEvent:function(t,h){var l=handlers[t];if(l){var i=l.indexOf(h);if(i>=0)l.splice(i,1);}}," +
      "onTheme:function(h){W.onEvent('themeChanged',h);}," +
      "ready:function(){post('ready');}," +
      "setResizeAnchor:function(e){if(e!=='top'&&e!=='bottom')throw new TypeError('Invalid edge');post('setResizeAnchor',{edge:e});}," +
      "watchPublicData:function(){post('watchPublicData');},unwatchPublicData:function(){post('unwatchPublicData');}," +
      "copyText:function(t){if(typeof t!=='string'||t.length<1||t.length>256)throw new TypeError('Invalid text');post('copyText',{text:t});}," +
      "openLink:function(u){check(u,false);post('openLink',{url:u});}," +
      "openTelegramLink:function(u){check(u,true);post('openTelegramLink',{url:u});}," +
      "request:function(d,o){if(typeof d!=='string')throw new TypeError('Invalid data');var n=new TextEncoder().encode(d).length;if(n<1||n>64)throw new TypeError('Invalid data');" +
      "if(!cfg.canQuery||!B||(o&&o.wallet))return Promise.reject(err('NOT_ALLOWED'));if(busy)return Promise.reject(err('BUSY'));" +
      "busy=true;var id=++seq;return new Promise(function(res,rej){pending[id]={res:res,rej:rej};post('request',{id:id,data:d});});}};" +
      "Object.defineProperty(W,'_tgxResolve',{value:function(id,ok,v){var p=pending[id];if(!p)return;delete pending[id];busy=false;if(ok)p.res(Object.freeze({result:v==null?null:v}));else p.rej(err(v||'FAILED'));}});" +
      "Object.defineProperty(W,'_tgxSetPublicData',{value:function(d){if(d===W.publicData)return;W.publicData=d;fire('publicDataChanged');}});" +
      "Object.defineProperty(W,'_tgxSetActive',{value:function(a){if(W.isActive===a)return;W.isActive=a;fire(a?'activated':'deactivated');}});" +
      "Object.defineProperty(window,'TelegramWidget',{value:W,writable:false,configurable:false});" +
      "function NetworkDisabledError(){var e=new Error('Network access is disabled in widgets');e.name='NetworkDisabledError';return e;}" +
      "window.fetch=function(){return Promise.reject(NetworkDisabledError());};" +
      "['XMLHttpRequest','WebSocket','EventSource','Worker','SharedWorker','BroadcastChannel','RTCPeerConnection'].forEach(function(k){try{window[k]=function(){throw NetworkDisabledError();};}catch(e){}});" +
      "try{navigator.sendBeacon=function(){return false;};}catch(e){}" +
      "window.open=function(u){try{if(isTg(new URL(u).hostname))W.openTelegramLink(u);else W.openLink(u);}catch(e){}return null;};" +
      "window.alert=function(){};window.confirm=function(){return false;};window.prompt=function(){return null;};window.print=function(){};" +
      "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;var h=a.getAttribute('href')||'';if(h.charAt(0)==='#')return;e.preventDefault();" +
      "if(/^https:/i.test(a.href))window.open(a.href);},true);" +
      "})();";
  }
}
