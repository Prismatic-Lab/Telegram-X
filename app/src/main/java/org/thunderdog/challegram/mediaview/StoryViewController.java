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
package org.thunderdog.challegram.mediaview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.loader.AvatarReceiver;
import org.thunderdog.challegram.loader.DoubleImageReceiver;
import org.thunderdog.challegram.loader.ImageFile;
import org.thunderdog.challegram.loader.ImageFileLocal;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.telegram.StoryListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibDataSource;
import org.thunderdog.challegram.telegram.TdlibFilesManager;
import org.thunderdog.challegram.telegram.TdlibManager;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemeId;
import org.thunderdog.challegram.theme.ThemeSet;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.TextEntity;
import org.thunderdog.challegram.widget.CustomTextView;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;
import org.thunderdog.challegram.widget.PopupLayout;

import java.util.concurrent.TimeUnit;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.FactorAnimator;
import me.vkryl.android.widget.FrameLayoutFix;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Fullscreen viewer of chat stories.
 *
 * Shows active stories of one or more chats (e.g. from the stories row in the chat list),
 * or a single story (e.g. from a shared story message).
 * Photos are shown for {@link #PHOTO_DURATION_MS}, videos for their duration.
 * Tap on the left third goes back, tap elsewhere goes forward, long press pauses,
 * swipe down closes the viewer.
 */
public class StoryViewController extends ViewController<StoryViewController.Args> implements
  PopupLayout.AnimatedPopupProvider, FactorAnimator.Target, Player.Listener, PopupLayout.TouchSectionProvider, StoryListener {

  private static final long PHOTO_DURATION_MS = 5000;
  private static final long PROGRESS_TICK_MS = 32;

  public static class Args {
    public final long[] chatIds;
    public final int chatIndex;
    public final int storyId;
    public final boolean singleStory;

    private Args (long[] chatIds, int chatIndex, int storyId, boolean singleStory) {
      this.chatIds = chatIds;
      this.chatIndex = chatIndex;
      this.storyId = storyId;
      this.singleStory = singleStory;
    }
  }

  // Entry points

  /** Opens all active stories of the given chat, starting with the first unread one. */
  public static void openChatStories (@NonNull ViewController<?> context, long chatId) {
    open(context, new Args(new long[] {chatId}, 0, 0, false));
  }

  /** Opens active stories of {@code chatIds[index]}, then continues with the following chats. */
  public static void openChatStories (@NonNull ViewController<?> context, long[] chatIds, int index) {
    if (chatIds == null || chatIds.length == 0 || index < 0 || index >= chatIds.length) {
      return;
    }
    open(context, new Args(chatIds, index, 0, false));
  }

  /** Opens exactly one story, e.g. from {@link TdApi.MessageStory} or {@link TdApi.MessageReplyToStory}. */
  public static void openStory (@NonNull ViewController<?> context, long storyPosterChatId, int storyId) {
    open(context, new Args(new long[] {storyPosterChatId}, 0, storyId, true));
  }

  private static void open (@NonNull ViewController<?> context, @NonNull Args args) {
    StoryViewController c = new StoryViewController(context.context(), context.tdlib());
    c.setArguments(args);
    c.open();
  }

  public StoryViewController (@NonNull Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_storyView;
  }

  // State

  private long[] chatIds;
  private int chatIndex;
  private int[] storyIds = new int[0];
  private int storyIndex;
  private long currentChatId;
  @Nullable private TdApi.Story currentStory;
  private boolean isVideo;
  private long videoDurationMs;
  private int contextId;

  private long openedStoryChatId;
  private int openedStoryId;

  private float storyProgress;
  private long photoElapsedMs;
  private long lastTickTime;

  private static final int PAUSE_HOLD = 1;
  private static final int PAUSE_REVEAL = 1 << 1;
  private static final int PAUSE_ACTIVITY = 1 << 2;
  private static final int PAUSE_INPUT = 1 << 3;
  private int pauseFlags = PAUSE_REVEAL;

  @Override
  public void setArguments (Args args) {
    super.setArguments(args);
    this.chatIds = args.chatIds;
    this.chatIndex = args.chatIndex;
  }

  // Views

  private PopupLayout popupView;
  private ContentView contentView;
  private TextureView textureView;
  private LinearLayout bottomWrap;
  private CustomTextView captionView;
  private LinearLayout actionsWrap;
  private android.widget.TextView replyButton;
  private android.widget.ImageView reactionButton;
  private android.widget.TextView messageView;

  private DoubleImageReceiver mediaReceiver;
  private AvatarReceiver avatarReceiver;
  private ExoPlayer exoPlayer;

  private ThemeDelegate getForcedTheme () {
    return ThemeSet.getBuiltinTheme(ThemeId.NIGHT_BLACK);
  }

  @Override
  protected View onCreateView (Context context) {
    popupView = new PopupLayout(context);
    popupView.setOverlayStatusBar(true);
    popupView.setTouchProvider(this);
    popupView.setNeedRootInsets();
    popupView.init(true);
    popupView.setIgnoreAllInsets(true);
    popupView.setBoundController(this);
    popupView.setDisableCancelOnTouchDown(true);
    popupView.setActivityListener(new BaseActivity.ActivityListener() {
      @Override
      public void onActivityPause () {
        setPaused(PAUSE_ACTIVITY, true);
      }

      @Override
      public void onActivityResume () {
        setPaused(PAUSE_ACTIVITY, false);
      }

      @Override
      public void onActivityDestroy () { }

      @Override
      public void onActivityPermissionResult (int code, boolean granted) { }
    });

    contentView = new ContentView(context);
    contentView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    contentView.setAlpha(0f);

    mediaReceiver = new DoubleImageReceiver(contentView, 0);
    avatarReceiver = new AvatarReceiver(contentView);

    textureView = new TextureView(context);
    textureView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    textureView.setAlpha(0f);
    contentView.addView(textureView);

    messageView = new android.widget.TextView(context);
    messageView.setTextColor(Color.WHITE);
    messageView.setTextSize(16f);
    messageView.setTypeface(Fonts.getRobotoRegular());
    messageView.setGravity(Gravity.CENTER);
    messageView.setPadding(Screen.dp(24f), 0, Screen.dp(24f), 0);
    messageView.setVisibility(View.GONE);
    messageView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    contentView.addView(messageView);

    bottomWrap = new LinearLayout(context);
    bottomWrap.setOrientation(LinearLayout.VERTICAL);
    bottomWrap.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

    captionView = new CustomTextView(context, tdlib);
    captionView.setPadding(Screen.dp(14f), Screen.dp(10f), Screen.dp(14f), Screen.dp(10f));
    captionView.setTextColorId(ColorId.white, true);
    captionView.setTextSize(Screen.dp(15f));
    captionView.setTextStyleProvider(TGMessage.getTextStyleProvider());
    captionView.setLinkColorId(ColorId.caption_textLink, ColorId.caption_textLinkPressHighlight);
    captionView.setForcedTheme(getForcedTheme());
    captionView.setMaxLineCount(8);
    captionView.setVisibility(View.GONE);
    captionView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bottomWrap.addView(captionView);

    actionsWrap = new LinearLayout(context);
    actionsWrap.setOrientation(LinearLayout.HORIZONTAL);
    actionsWrap.setGravity(Gravity.CENTER_VERTICAL);
    actionsWrap.setPadding(Screen.dp(12f), Screen.dp(4f), Screen.dp(4f), Screen.dp(12f) + Screen.getNavigationBarHeight());
    actionsWrap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    replyButton = new android.widget.TextView(context);
    replyButton.setText(Lang.getString(R.string.StoryReplyPrivately));
    replyButton.setTextColor(0xb3ffffff);
    replyButton.setTextSize(15f);
    replyButton.setTypeface(Fonts.getRobotoRegular());
    replyButton.setSingleLine(true);
    replyButton.setEllipsize(TextUtils.TruncateAt.END);
    replyButton.setGravity(Gravity.CENTER_VERTICAL);
    replyButton.setPadding(Screen.dp(16f), 0, Screen.dp(16f), 0);
    replyButton.setBackground(new android.graphics.drawable.GradientDrawable() {{
      setCornerRadius(Screen.dp(20f));
      setStroke(Math.max(1, Screen.dp(1f)), 0x66ffffff);
      setColor(0x33000000);
    }});
    replyButton.setOnClickListener(v -> openReplyInput());
    replyButton.setLayoutParams(new LinearLayout.LayoutParams(0, Screen.dp(40f), 1f));
    actionsWrap.addView(replyButton);

    reactionButton = new android.widget.ImageView(context);
    reactionButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
    reactionButton.setImageResource(R.drawable.baseline_favorite_24);
    reactionButton.setOnClickListener(v -> toggleReaction());
    reactionButton.setLayoutParams(new LinearLayout.LayoutParams(Screen.dp(52f), Screen.dp(48f)));
    actionsWrap.addView(reactionButton);
    actionsWrap.setVisibility(View.GONE);

    bottomWrap.addView(actionsWrap);
    contentView.addView(bottomWrap);

    exoPlayer = U.newExoPlayer(context, true);
    exoPlayer.addListener(this);
    exoPlayer.setVolume(1f);
    exoPlayer.setPlayWhenReady(false);
    exoPlayer.setVideoTextureView(textureView);

    tdlib.listeners().addStoryListener(this);

    return contentView;
  }

  @Override
  public boolean shouldTouchOutside (float x, float y) {
    return false;
  }

  public void open () {
    getValue();
    popupView.showAnimatedPopupView(contentView, this);
    loadChat(chatIndex, false);
  }

  public void close () {
    if (popupView != null) {
      popupView.hideWindow(true);
    }
  }

  // Reveal animation

  private static final int ANIMATOR_REVEAL = 0;
  private static final int ANIMATOR_DRAG = 1;
  private static final long REVEAL_DURATION = 220;
  private FactorAnimator revealAnimator;
  private float revealFactor;

  @Override
  public void prepareShowAnimation () {
    revealAnimator = new FactorAnimator(ANIMATOR_REVEAL, this, AnimatorUtils.DECELERATE_INTERPOLATOR, REVEAL_DURATION);
  }

  @Override
  public void launchShowAnimation (PopupLayout popup) {
    revealAnimator.animateTo(1f);
  }

  @Override
  public boolean launchHideAnimation (PopupLayout popup, FactorAnimator originalAnimator) {
    setPaused(PAUSE_REVEAL, true);
    revealAnimator.animateTo(0f);
    return true;
  }

  private void setRevealFactor (float factor) {
    if (this.revealFactor != factor) {
      this.revealFactor = factor;
      contentView.setAlpha(MathUtils.clamp(factor));
      float scale = .9f + .1f * factor;
      contentView.setScaleX(scale);
      contentView.setScaleY(scale);
      context.setPhotoRevealFactor(factor);
    }
  }

  private float dragFactor;
  private FactorAnimator dragAnimator;

  private void setDragOffset (float offset) {
    contentView.setTranslationY(offset);
    float alpha = 1f - MathUtils.clamp(offset / (float) Math.max(1, contentView.getMeasuredHeight())) * .5f;
    popupView.setBackgroundColor(ColorUtils.alphaColor(alpha, Color.BLACK));
  }

  private void animateDragBack () {
    if (dragAnimator == null) {
      dragAnimator = new FactorAnimator(ANIMATOR_DRAG, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l, contentView.getTranslationY());
    } else {
      dragAnimator.forceFactor(contentView.getTranslationY());
    }
    dragAnimator.animateTo(0f);
  }

  @Override
  public void onFactorChanged (int id, float factor, float fraction, FactorAnimator callee) {
    switch (id) {
      case ANIMATOR_REVEAL:
        setRevealFactor(factor);
        break;
      case ANIMATOR_DRAG:
        dragFactor = factor;
        setDragOffset(factor);
        break;
    }
  }

  @Override
  public void onFactorChangeFinished (int id, float finalFactor, FactorAnimator callee) {
    if (id == ANIMATOR_REVEAL) {
      if (finalFactor == 0f) {
        popupView.onCustomHideAnimationComplete();
      } else if (finalFactor == 1f) {
        popupView.onCustomShowComplete();
        setPaused(PAUSE_REVEAL, false);
      }
    }
  }

  // Loading

  private void loadChat (int index, boolean fromEnd) {
    if (index < 0 || index >= chatIds.length) {
      close();
      return;
    }
    closeOpenedStory();
    final int requestId = ++contextId;
    this.chatIndex = index;
    this.currentChatId = chatIds[index];
    this.currentStory = null;
    this.storyIds = new int[0];
    this.storyIndex = 0;
    resetProgress();
    resetMedia();
    avatarReceiver.requestMessageSender(tdlib, tdlib.sender(currentChatId), AvatarReceiver.Options.NONE);
    contentView.invalidate();

    final Args args = getArgumentsStrict();
    if (args.singleStory) {
      storyIds = new int[] {args.storyId};
      showStoryAt(0);
      return;
    }

    final long chatId = currentChatId;
    tdlib.send(new TdApi.GetChatActiveStories(chatId), (activeStories, error) -> runOnUiThreadOptional(() -> {
      if (requestId != contextId) {
        return;
      }
      if (error != null || activeStories.stories.length == 0) {
        if (error != null) {
          UI.showError(error);
        }
        // Nothing to show for this chat, continue in the same direction
        loadChat(fromEnd ? index - 1 : index + 1, fromEnd);
        return;
      }
      int[] ids = new int[activeStories.stories.length];
      int startIndex = -1;
      for (int i = 0; i < ids.length; i++) {
        ids[i] = activeStories.stories[i].storyId;
        if (startIndex == -1 && ids[i] > activeStories.maxReadStoryId) {
          startIndex = i;
        }
      }
      if (fromEnd) {
        startIndex = ids.length - 1;
      } else if (index == args.chatIndex && args.storyId != 0) {
        for (int i = 0; i < ids.length; i++) {
          if (ids[i] == args.storyId) {
            startIndex = i;
            break;
          }
        }
      }
      if (startIndex == -1) {
        startIndex = 0;
      }
      this.storyIds = ids;
      showStoryAt(startIndex);
    }));
  }

  private void showStoryAt (int index) {
    if (index < 0 || index >= storyIds.length) {
      return;
    }
    closeOpenedStory();
    final int requestId = ++contextId;
    final long chatId = currentChatId;
    final int storyId = storyIds[index];
    this.storyIndex = index;
    this.currentStory = null;
    resetProgress();
    resetMedia();
    contentView.invalidate();

    tdlib.send(new TdApi.GetStory(chatId, storyId, false), (story, error) -> runOnUiThreadOptional(() -> {
      if (requestId != contextId) {
        return;
      }
      if (error != null) {
        if (getArgumentsStrict().singleStory) {
          showMessage(Lang.getString(R.string.StoryExpired));
        } else {
          next();
        }
        return;
      }
      setStory(story);
    }));
  }

  private void resetProgress () {
    storyProgress = 0f;
    photoElapsedMs = 0;
    lastTickTime = SystemClock.uptimeMillis();
  }

  private void resetMedia () {
    isVideo = false;
    videoDurationMs = 0;
    if (exoPlayer != null) {
      exoPlayer.stop();
      exoPlayer.clearMediaItems();
    }
    textureView.setAlpha(0f);
    mediaReceiver.clear();
    messageView.setVisibility(View.GONE);
    captionView.setVisibility(View.GONE);
    actionsWrap.setVisibility(View.GONE);
  }

  private void showMessage (String text) {
    messageView.setText(text);
    messageView.setVisibility(View.VISIBLE);
  }

  private int mediaWidth, mediaHeight;

  private void setStory (@NonNull TdApi.Story story) {
    this.currentStory = story;
    resetProgress();

    mediaWidth = mediaHeight = 0;
    switch (story.content.getConstructor()) {
      case TdApi.StoryContentPhoto.CONSTRUCTOR: {
        TdApi.Photo photo = ((TdApi.StoryContentPhoto) story.content).photo;
        ImageFile preview = photo.minithumbnail != null ? new ImageFileLocal(photo.minithumbnail) : null;
        TdApi.PhotoSize size = Td.findBiggest(photo);
        ImageFile full = null;
        if (size != null) {
          full = new ImageFile(tdlib, size.photo);
          full.setScaleType(ImageFile.FIT_CENTER);
          full.setNoBlur();
          mediaWidth = size.width;
          mediaHeight = size.height;
        }
        if (preview != null) {
          preview.setScaleType(ImageFile.FIT_CENTER);
        }
        mediaReceiver.requestFile(preview, full);
        break;
      }
      case TdApi.StoryContentVideo.CONSTRUCTOR: {
        TdApi.StoryVideo video = ((TdApi.StoryContentVideo) story.content).video;
        ImageFile preview = video.minithumbnail != null ? new ImageFileLocal(video.minithumbnail) : null;
        ImageFile thumb = TD.toImageFile(tdlib, video.thumbnail);
        if (preview != null) {
          preview.setScaleType(ImageFile.FIT_CENTER);
        }
        if (thumb != null) {
          thumb.setScaleType(ImageFile.FIT_CENTER);
        }
        mediaReceiver.requestFile(preview, thumb);
        mediaWidth = video.width;
        mediaHeight = video.height;
        isVideo = true;
        videoDurationMs = (long) (video.duration * 1000.0);
        textureView.setAlpha(0f);
        exoPlayer.setMediaSource(U.newMediaSource(tdlib.id(), video.video, TdlibFilesManager.PRIORITY_STREAMING_VIDEO, TdlibDataSource.Flag.OPTIMIZE_CHUNKS, videoDurationMs));
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(pauseFlags == 0);
        layoutTexture();
        break;
      }
      case TdApi.StoryContentLive.CONSTRUCTOR:
      case TdApi.StoryContentUnsupported.CONSTRUCTOR:
      default: {
        showMessage(Lang.getString(R.string.StoryUnsupported));
        break;
      }
    }

    if (!Td.isEmpty(story.caption)) {
      captionView.setText(story.caption.text, TextEntity.valueOf(tdlib, story.caption, null), false);
      captionView.setVisibility(View.VISIBLE);
    } else {
      captionView.setVisibility(View.GONE);
    }
    actionsWrap.setVisibility(story.canBeReplied || story.chosenReactionType != null ? View.VISIBLE : View.GONE);
    replyButton.setVisibility(story.canBeReplied ? View.VISIBLE : View.INVISIBLE);
    updateReactionButton();

    if (story.posterId != null) {
      avatarReceiver.requestMessageSender(tdlib, story.posterId, AvatarReceiver.Options.NONE);
    }

    // Marks the story as viewed and lets TDLib know it is being watched
    openedStoryChatId = story.posterChatId;
    openedStoryId = story.id;
    tdlib.client().send(new TdApi.OpenStory(story.posterChatId, story.id), tdlib.silentHandler());

    contentView.invalidate();
    checkProgressTimer();
  }

  private void closeOpenedStory () {
    if (openedStoryId != 0) {
      tdlib.client().send(new TdApi.CloseStory(openedStoryChatId, openedStoryId), tdlib.silentHandler());
      openedStoryChatId = 0;
      openedStoryId = 0;
    }
  }

  // Navigation

  private void next () {
    if (storyIndex + 1 < storyIds.length) {
      showStoryAt(storyIndex + 1);
    } else if (!getArgumentsStrict().singleStory && chatIndex + 1 < chatIds.length) {
      loadChat(chatIndex + 1, false);
    } else {
      close();
    }
  }

  private void prev () {
    if (storyIndex > 0) {
      showStoryAt(storyIndex - 1);
    } else if (!getArgumentsStrict().singleStory && chatIndex > 0) {
      loadChat(chatIndex - 1, true);
    } else {
      // Restart the first story
      resetProgress();
      if (isVideo && exoPlayer != null) {
        exoPlayer.seekTo(0);
      }
      contentView.invalidate();
    }
  }

  // Pause & progress

  private void setPaused (int flag, boolean paused) {
    int newFlags = paused ? (pauseFlags | flag) : (pauseFlags & ~flag);
    if (this.pauseFlags != newFlags) {
      boolean wasPaused = this.pauseFlags != 0;
      this.pauseFlags = newFlags;
      boolean isPaused = newFlags != 0;
      if (wasPaused != isPaused) {
        if (exoPlayer != null && isVideo) {
          exoPlayer.setPlayWhenReady(!isPaused);
        }
        if (!isPaused) {
          lastTickTime = SystemClock.uptimeMillis();
          TdlibManager.instance().player().pauseWithReason(TGPlayerController.PAUSE_REASON_OPEN_VIDEO);
        }
        checkProgressTimer();
      }
    }
  }

  private final Handler handler = new Handler(Looper.getMainLooper()) {
    @Override
    public void handleMessage (@NonNull Message msg) {
      onProgressTick();
    }
  };

  private void checkProgressTimer () {
    handler.removeMessages(0);
    if (pauseFlags == 0 && currentStory != null && !isDestroyed()) {
      handler.sendMessageDelayed(Message.obtain(handler, 0), PROGRESS_TICK_MS);
    }
  }

  private boolean isMediaReady () {
    if (currentStory == null) {
      return false;
    }
    if (isVideo) {
      return true;
    }
    if (currentStory.content.getConstructor() != TdApi.StoryContentPhoto.CONSTRUCTOR) {
      return true;
    }
    return mediaReceiver.getImageReceiver().isLoaded();
  }

  private void onProgressTick () {
    if (currentStory == null || pauseFlags != 0) {
      return;
    }
    long now = SystemClock.uptimeMillis();
    long elapsed = Math.max(0, now - lastTickTime);
    lastTickTime = now;
    if (isVideo) {
      long duration = exoPlayer != null ? exoPlayer.getDuration() : C.TIME_UNSET;
      long position = exoPlayer != null ? exoPlayer.getCurrentPosition() : C.TIME_UNSET;
      if (duration == C.TIME_UNSET || duration <= 0) {
        duration = videoDurationMs;
      }
      if (duration > 0 && position != C.TIME_UNSET) {
        storyProgress = MathUtils.clamp((float) ((double) position / (double) duration));
      }
    } else {
      if (isMediaReady()) {
        photoElapsedMs += elapsed;
      }
      storyProgress = MathUtils.clamp((float) photoElapsedMs / (float) PHOTO_DURATION_MS);
      if (photoElapsedMs >= PHOTO_DURATION_MS) {
        next();
        return;
      }
    }
    contentView.invalidate(0, 0, contentView.getMeasuredWidth(), getProgressBottom());
    checkProgressTimer();
  }

  // Player.Listener

  @Override
  public void onPlaybackStateChanged (@Player.State int playbackState) {
    if (playbackState == Player.STATE_ENDED && isVideo && currentStory != null) {
      storyProgress = 1f;
      next();
    }
  }

  @Override
  public void onRenderedFirstFrame () {
    if (isVideo) {
      textureView.setAlpha(1f);
    }
  }

  @Override
  public void onPlayerError (@NonNull PlaybackException error) {
    Log.e(Log.TAG_VIDEO, "Unable to play story video", error);
    if (isVideo && currentStory != null) {
      showMessage(Lang.getString(R.string.StoryUnsupported));
      // Fall back to a timed display, so auto-advance still works
      isVideo = false;
      photoElapsedMs = 0;
      checkProgressTimer();
    }
  }

  // Story updates

  @Override
  public void onStoryUpdated (@NonNull TdApi.Story story) {
    runOnUiThreadOptional(() -> {
      TdApi.Story current = this.currentStory;
      if (current != null && current.posterChatId == story.posterChatId && current.id == story.id) {
        // Keep the currently displayed media, update interaction state
        current.chosenReactionType = story.chosenReactionType;
        current.interactionInfo = story.interactionInfo;
        current.canBeReplied = story.canBeReplied;
        updateReactionButton();
      }
    });
  }

  @Override
  public void onStoryDeleted (long storySenderChatId, int storyId) {
    runOnUiThreadOptional(() -> {
      TdApi.Story current = this.currentStory;
      if (current != null && current.posterChatId == storySenderChatId && current.id == storyId) {
        next();
      }
    });
  }

  // Reply & reaction

  private static final String DEFAULT_REACTION = "\u2764";

  private void updateReactionButton () {
    TdApi.Story story = currentStory;
    boolean isChosen = story != null && story.chosenReactionType != null;
    reactionButton.setColorFilter(isChosen ? 0xffff4d5d : Color.WHITE);
    reactionButton.setAlpha(isChosen ? 1f : .8f);
  }

  private void toggleReaction () {
    TdApi.Story story = currentStory;
    if (story == null) {
      return;
    }
    TdApi.ReactionType reactionType = story.chosenReactionType != null ? null : new TdApi.ReactionTypeEmoji(DEFAULT_REACTION);
    TdApi.ReactionType oldReactionType = story.chosenReactionType;
    story.chosenReactionType = reactionType;
    updateReactionButton();
    tdlib.send(new TdApi.SetStoryReaction(story.posterChatId, story.id, reactionType, true), (ok, error) -> {
      if (error != null) {
        runOnUiThreadOptional(() -> {
          if (currentStory == story) {
            story.chosenReactionType = oldReactionType;
            updateReactionButton();
          }
          UI.showError(error);
        });
      }
    });
  }

  private void openReplyInput () {
    TdApi.Story story = currentStory;
    if (story == null || !story.canBeReplied) {
      return;
    }
    setPaused(PAUSE_INPUT, true);
    MaterialEditTextGroup inputView = openInputAlert(Lang.getString(R.string.StoryReply), Lang.getString(R.string.StoryReplyPrivately), R.string.Send, R.string.Cancel, null, (editTextGroup, result) -> {
      setPaused(PAUSE_INPUT, false);
      String text = result != null ? result.trim() : null;
      if (StringUtils.isEmpty(text)) {
        return true;
      }
      sendReply(story, text);
      return true;
    }, true);
    if (inputView != null) {
      // Cancel dismisses the dialog without invoking the callback, so resume playback once the input is gone
      inputView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow (@NonNull View v) { }

        @Override
        public void onViewDetachedFromWindow (@NonNull View v) {
          v.removeOnAttachStateChangeListener(this);
          if (!isDestroyed()) {
            setPaused(PAUSE_INPUT, false);
          }
        }
      });
      inputView.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }
  }

  private void sendReply (@NonNull TdApi.Story story, @NonNull String text) {
    TdApi.FormattedText formattedText = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    TdApi.InputMessageContent content = new TdApi.InputMessageText(formattedText, null, false);
    TdApi.InputMessageReplyTo replyTo = new TdApi.InputMessageReplyToStory(story.posterChatId, story.id);
    tdlib.send(new TdApi.SendMessage(story.posterChatId, null, replyTo, null, null, content), (message, error) -> {
      if (error != null) {
        UI.showError(error);
      } else {
        UI.showToast(R.string.StoryReplySent, android.widget.Toast.LENGTH_SHORT);
      }
    });
  }

  // Layout & drawing

  private int getTopInset () {
    return HeaderView.getTopOffset();
  }

  private int getProgressBottom () {
    return getTopInset() + Screen.dp(8f) + Screen.dp(2f) + Screen.dp(4f);
  }

  private int getHeaderBottom () {
    return getProgressBottom() + Screen.dp(48f);
  }

  private final RectF mediaRect = new RectF();

  private void layoutMedia () {
    int width = contentView.getMeasuredWidth();
    int height = contentView.getMeasuredHeight();
    if (width == 0 || height == 0) {
      return;
    }
    mediaRect.set(0, 0, width, height);
    mediaReceiver.setBounds(0, 0, width, height);
    layoutTexture();
  }

  private void layoutTexture () {
    int width = contentView.getMeasuredWidth();
    int height = contentView.getMeasuredHeight();
    if (width == 0 || height == 0) {
      return;
    }
    int targetWidth = width, targetHeight = height;
    if (mediaWidth > 0 && mediaHeight > 0) {
      float scale = Math.min((float) width / (float) mediaWidth, (float) height / (float) mediaHeight);
      targetWidth = Math.round(mediaWidth * scale);
      targetHeight = Math.round(mediaHeight * scale);
    }
    FrameLayoutFix.LayoutParams params = (FrameLayoutFix.LayoutParams) textureView.getLayoutParams();
    if (params.width != targetWidth || params.height != targetHeight) {
      params.width = targetWidth;
      params.height = targetHeight;
      textureView.setLayoutParams(params);
    }
  }

  private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final RectF tmpRect = new RectF();
  private Paint topShadowPaint;
  private int topShadowHeight;

  private class ContentView extends FrameLayoutFix {
    private final Drawable closeIcon;

    public ContentView (@NonNull Context context) {
      super(context);
      setWillNotDraw(false);
      setBackgroundColor(Color.BLACK);
      closeIcon = Drawables.get(context.getResources(), R.drawable.baseline_close_24);
      namePaint.setTypeface(Fonts.getRobotoMedium());
      namePaint.setTextSize(Screen.dp(15f));
      namePaint.setColor(Color.WHITE);
      datePaint.setTypeface(Fonts.getRobotoRegular());
      datePaint.setTextSize(Screen.dp(13f));
      datePaint.setColor(0xb3ffffff);
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec) {
      super.onMeasure(widthMeasureSpec, heightMeasureSpec);
      layoutMedia();
    }

    @Override
    protected void onAttachedToWindow () {
      super.onAttachedToWindow();
      mediaReceiver.attach();
      avatarReceiver.attach();
    }

    @Override
    protected void onDetachedFromWindow () {
      super.onDetachedFromWindow();
      mediaReceiver.detach();
      avatarReceiver.detach();
    }

    @Override
    protected void dispatchDraw (@NonNull Canvas c) {
      // Media below children (TextureView, caption)
      if (mediaReceiver.needPlaceholder()) {
        mediaReceiver.drawPlaceholder(c);
      }
      mediaReceiver.draw(c);

      super.dispatchDraw(c);

      drawHeader(c);
    }

    private void drawHeader (Canvas c) {
      final int width = getMeasuredWidth();
      final int headerBottom = getHeaderBottom();

      if (topShadowPaint == null || topShadowHeight != headerBottom + Screen.dp(24f)) {
        topShadowHeight = headerBottom + Screen.dp(24f);
        topShadowPaint = new Paint();
        topShadowPaint.setShader(new LinearGradient(0, 0, 0, topShadowHeight, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
      }
      c.drawRect(0, 0, width, topShadowHeight, topShadowPaint);

      // Progress segments
      final int count = Math.max(1, storyIds.length);
      final int padding = Screen.dp(8f);
      final int gap = Screen.dp(3f);
      final int barHeight = Screen.dp(2f);
      final int top = getTopInset() + Screen.dp(8f);
      final float segmentWidth = (float) (width - padding * 2 - gap * (count - 1)) / (float) count;
      final float radius = barHeight / 2f;
      for (int i = 0; i < count; i++) {
        float left = padding + i * (segmentWidth + gap);
        tmpRect.set(left, top, left + segmentWidth, top + barHeight);
        progressPaint.setColor(0x59ffffff);
        c.drawRoundRect(tmpRect, radius, radius, progressPaint);
        float fill;
        if (i < storyIndex) {
          fill = 1f;
        } else if (i == storyIndex) {
          fill = storyProgress;
        } else {
          fill = 0f;
        }
        if (fill > 0f) {
          tmpRect.right = left + segmentWidth * fill;
          progressPaint.setColor(Color.WHITE);
          c.drawRoundRect(tmpRect, radius, radius, progressPaint);
        }
      }

      // Avatar, name and date
      final int avatarSize = Screen.dp(36f);
      final int avatarLeft = Screen.dp(12f);
      final int avatarTop = getProgressBottom() + (Screen.dp(48f) - avatarSize) / 2;
      avatarReceiver.setBounds(avatarLeft, avatarTop, avatarLeft + avatarSize, avatarTop + avatarSize);
      if (avatarReceiver.needPlaceholder()) {
        avatarReceiver.drawPlaceholder(c);
      }
      avatarReceiver.draw(c);

      final int textLeft = avatarLeft + avatarSize + Screen.dp(10f);
      final int closeSize = Screen.dp(48f);
      final int textMaxWidth = Math.max(0, width - textLeft - closeSize - Screen.dp(8f));
      final int cy = avatarTop + avatarSize / 2;

      String name = currentChatId != 0 ? tdlib.chatTitle(currentChatId) : null;
      if (name == null) {
        name = "";
      }
      CharSequence nameText = TextUtils.ellipsize(name, namePaint, textMaxWidth, TextUtils.TruncateAt.END);
      TdApi.Story story = currentStory;
      if (story != null) {
        c.drawText(nameText, 0, nameText.length(), textLeft, cy - Screen.dp(3f), namePaint);
        String date = Lang.getRelativeTimestamp(story.date, TimeUnit.SECONDS);
        if (story.isEdited) {
          date = Lang.getString(R.string.format_edited, date);
        }
        CharSequence dateText = TextUtils.ellipsize(date, datePaint, textMaxWidth, TextUtils.TruncateAt.END);
        c.drawText(dateText, 0, dateText.length(), textLeft, cy + Screen.dp(14f), datePaint);
      } else {
        c.drawText(nameText, 0, nameText.length(), textLeft, cy + Screen.dp(5f), namePaint);
      }

      Drawables.drawCentered(c, closeIcon, width - closeSize / 2f - Screen.dp(4f), cy, Paints.whitePorterDuffPaint());
    }

    // Touch handling

    private float startX, startY;
    private long startTime;
    private boolean isDragging, isTracking;

    @Override
    public boolean onTouchEvent (MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          startX = e.getX();
          startY = e.getY();
          startTime = SystemClock.uptimeMillis();
          isDragging = false;
          isTracking = true;
          setPaused(PAUSE_HOLD, true);
          return true;
        }
        case MotionEvent.ACTION_MOVE: {
          if (!isTracking) {
            return false;
          }
          float dx = e.getX() - startX;
          float dy = e.getY() - startY;
          if (!isDragging && dy > Screen.getTouchSlopBig() && Math.abs(dy) > Math.abs(dx)) {
            isDragging = true;
          }
          if (isDragging) {
            setDragOffset(Math.max(0f, dy));
          }
          return true;
        }
        case MotionEvent.ACTION_UP: {
          if (!isTracking) {
            return false;
          }
          isTracking = false;
          float dx = e.getX() - startX;
          float dy = e.getY() - startY;
          if (isDragging) {
            isDragging = false;
            if (dy > Screen.dp(120f)) {
              close();
            } else {
              animateDragBack();
              setPaused(PAUSE_HOLD, false);
            }
            return true;
          }
          setPaused(PAUSE_INPUT, false);
          setPaused(PAUSE_HOLD, false);
          boolean isTap = Math.abs(dx) < Screen.getTouchSlop() && Math.abs(dy) < Screen.getTouchSlop() && SystemClock.uptimeMillis() - startTime < 300;
          if (isTap) {
            onTap(e.getX(), e.getY());
          }
          return true;
        }
        case MotionEvent.ACTION_CANCEL: {
          if (isDragging) {
            isDragging = false;
            animateDragBack();
          }
          isTracking = false;
          setPaused(PAUSE_HOLD, false);
          return true;
        }
      }
      return super.onTouchEvent(e);
    }

    private void onTap (float x, float y) {
      int width = getMeasuredWidth();
      if (y >= getProgressBottom() && y < getHeaderBottom()) {
        if (x >= width - Screen.dp(56f)) {
          close();
          return;
        }
      }
      if (x < width / 3f) {
        prev();
      } else {
        next();
      }
    }
  }

  @Override
  public void destroy () {
    super.destroy();
    handler.removeMessages(0);
    closeOpenedStory();
    tdlib.listeners().removeStoryListener(this);
    if (exoPlayer != null) {
      exoPlayer.release();
      exoPlayer = null;
    }
    if (mediaReceiver != null) {
      mediaReceiver.destroy();
    }
    if (avatarReceiver != null) {
      avatarReceiver.destroy();
    }
    context.setPhotoRevealFactor(0f);
  }
}
