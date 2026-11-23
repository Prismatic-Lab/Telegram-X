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
 * TGX Mod: view-only player for RTMP live streams (TdApi.GroupCall.isRtmpStream).
 */
package org.thunderdog.challegram.voip;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.telegram.Tdlib;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Plays an RTMP live stream by downloading its 1-second segments through TDLib
 * ({@link TdApi.GetGroupCallStreams}, {@link TdApi.GetGroupCallStreamSegment}) and queueing them
 * into ExoPlayer as a playlist.
 *
 * Segment format (from tgcalls group/VideoStreamingPart.cpp, "unified broadcast" parts requested
 * with channel 1 and full quality): a little-endian header
 * <pre>
 *   int32  signature = 0xa12e810d
 *   string container   (TL-style serialized string, e.g. "mp4")
 *   int32  activeMask
 *   int32  eventCount
 *   event: int32 offset, string endpointId, int32 rotation, int32 extra
 * </pre>
 * followed by the media container that holds both the video (H.264) and the audio track.
 * {@code event.offset} is relative to the end of the header. Each part is a standalone file that
 * ExoPlayer's default extractors can sniff (MP4 / fragmented MP4 / MPEG-TS / Matroska).
 */
public class LiveStreamPlayer implements Player.Listener {
  public static final int STATE_LOADING = 0;
  public static final int STATE_PLAYING = 1;
  public static final int STATE_WAITING = 2;
  public static final int STATE_ERROR = 3;

  public interface Callback {
    void onStreamStateChanged (LiveStreamPlayer player, int state, @Nullable String error);
    default void onVideoSizeChanged (LiveStreamPlayer player, int width, int height, float pixelRatio) { }
  }

  private static final int UNIFIED_CHANNEL_ID = 1;
  private static final int BUFFER_SEGMENTS = 3;
  private static final int MAX_QUEUED_SEGMENTS = 6;
  private static final long RETRY_DELAY_MS = 500;
  private static final int MAX_FAILURES = 20;

  private final Tdlib tdlib;
  private final int groupCallId;
  private final Callback callback;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private ExoPlayer player;
  private boolean isRunning, isRequesting, isPrepared;
  private long nextTimestamp = -1;
  private int scale;
  private int channelId = UNIFIED_CHANNEL_ID;
  private int failureCount;
  private int queuedSegments;
  private int state = -1;
  private @Nullable String lastContainer;

  public LiveStreamPlayer (Tdlib tdlib, int groupCallId, Callback callback) {
    this.tdlib = tdlib;
    this.groupCallId = groupCallId;
    this.callback = callback;
  }

  @UiThread
  public void start (Context context, @Nullable TextureView textureView) {
    if (isRunning) {
      return;
    }
    isRunning = true;
    player = U.newExoPlayer(context, true);
    player.addListener(this);
    if (textureView != null) {
      player.setVideoTextureView(textureView);
    }
    player.setPlayWhenReady(true);
    setState(STATE_LOADING, null);
    syncWithServer();
  }

  @UiThread
  public void setTextureView (@Nullable TextureView textureView) {
    if (player != null) {
      if (textureView != null) {
        player.setVideoTextureView(textureView);
      } else {
        player.clearVideoSurface();
      }
    }
  }

  @UiThread
  public void setVolume (float volume) {
    if (player != null) {
      player.setVolume(volume);
    }
  }

  @UiThread
  public void stop () {
    isRunning = false;
    handler.removeCallbacksAndMessages(null);
    if (player != null) {
      player.removeListener(this);
      player.release();
      player = null;
    }
  }

  public boolean isRunning () {
    return isRunning;
  }

  private void setState (int state, @Nullable String error) {
    if (this.state != state || error != null) {
      this.state = state;
      callback.onStreamStateChanged(this, state, error);
    }
  }

  private long segmentDurationMs () {
    // TdApi.GroupCallStream.scale: segment duration is 1000 / 2^scale milliseconds
    return Math.max(1, 1000L >> Math.max(0, scale));
  }

  // Server sync

  private void syncWithServer () {
    if (!isRunning) {
      return;
    }
    tdlib.send(new TdApi.GetGroupCallStreams(groupCallId), (streams, error) -> handler.post(() -> {
      if (!isRunning) {
        return;
      }
      if (error != null) {
        onFailure(error, true);
        return;
      }
      TdApi.GroupCallStream stream = null;
      for (TdApi.GroupCallStream s : streams.streams) {
        if (s.channelId == UNIFIED_CHANNEL_ID) {
          stream = s;
          break;
        }
      }
      if (stream == null && streams.streams.length > 0) {
        stream = streams.streams[0];
      }
      if (stream == null) {
        // Stream not started yet (no data from the RTMP source)
        setState(STATE_WAITING, null);
        handler.postDelayed(this::syncWithServer, 2000);
        return;
      }
      this.channelId = stream.channelId;
      this.scale = stream.scale;
      long duration = segmentDurationMs();
      long aligned = (stream.timeOffset / duration) * duration;
      // Start slightly behind the live edge so that a few segments can be buffered
      this.nextTimestamp = Math.max(0, aligned - duration * (BUFFER_SEGMENTS - 1));
      requestNextSegment();
    }));
  }

  private void requestNextSegment () {
    if (!isRunning || isRequesting || nextTimestamp < 0) {
      return;
    }
    if (queuedSegments >= MAX_QUEUED_SEGMENTS) {
      handler.postDelayed(this::requestNextSegment, segmentDurationMs());
      return;
    }
    isRequesting = true;
    final long timestamp = nextTimestamp;
    tdlib.send(new TdApi.GetGroupCallStreamSegment(groupCallId, timestamp, scale, channelId, new TdApi.GroupCallVideoQualityFull()), (data, error) -> handler.post(() -> {
      isRequesting = false;
      if (!isRunning) {
        return;
      }
      if (error != null) {
        String message = error.message != null ? error.message : "";
        if (message.contains("TIME_TOO_BIG") || message.contains("TOO_BIG")) {
          // Not produced yet: wait for the live edge
          handler.postDelayed(this::requestNextSegment, RETRY_DELAY_MS);
        } else if (message.contains("TIME_TOO_SMALL") || message.contains("TIME_INVALID") || message.contains("TOO_SMALL")) {
          // Fell behind the server buffer: resync
          nextTimestamp = -1;
          syncWithServer();
        } else {
          onFailure(error, false);
        }
        return;
      }
      failureCount = 0;
      nextTimestamp = timestamp + segmentDurationMs();
      enqueue(timestamp, data.data);
      requestNextSegment();
    }));
  }

  private void onFailure (TdApi.Error error, boolean isSync) {
    failureCount++;
    Log.w(Log.TAG_VOIP, "Live stream request failed (%d): %s", failureCount, TD.toErrorString(error));
    if (failureCount >= MAX_FAILURES) {
      setState(STATE_ERROR, TD.toErrorString(error));
      return;
    }
    handler.postDelayed(isSync ? this::syncWithServer : this::requestNextSegment, RETRY_DELAY_MS * 2);
  }

  // Playback

  private void enqueue (long timestamp, byte[] part) {
    if (player == null || part == null || part.length == 0) {
      return;
    }
    byte[] media = extractMedia(part);
    if (media == null || media.length == 0) {
      return;
    }
    final byte[] mediaData = media;
    DataSource.Factory factory = () -> new ByteArrayDataSource(mediaData);
    MediaSource source = new ProgressiveMediaSource.Factory(factory)
      .createMediaSource(MediaItem.fromUri(Uri.parse("tgxstream://" + groupCallId + "/" + timestamp)));
    player.addMediaSource(source);
    queuedSegments++;
    if (!isPrepared) {
      if (queuedSegments >= BUFFER_SEGMENTS) {
        isPrepared = true;
        player.prepare();
      }
    } else if (player.getPlaybackState() == Player.STATE_ENDED) {
      // Ran out of segments before this one arrived: continue from it
      player.seekToDefaultPosition(player.getMediaItemCount() - 1);
      player.setPlayWhenReady(true);
    }
  }

  private void trimPlayedSegments () {
    if (player == null) {
      return;
    }
    int current = player.getCurrentMediaItemIndex();
    if (current > 0) {
      player.removeMediaItems(0, current);
    }
    queuedSegments = player.getMediaItemCount() - (player.getCurrentMediaItemIndex() + 1);
    if (queuedSegments < 0) {
      queuedSegments = 0;
    }
  }

  @Override
  public void onMediaItemTransition (@Nullable MediaItem mediaItem, int reason) {
    handler.post(this::trimPlayedSegments);
  }

  @Override
  public void onPlaybackStateChanged (int playbackState) {
    switch (playbackState) {
      case Player.STATE_READY:
        setState(STATE_PLAYING, null);
        break;
      case Player.STATE_BUFFERING:
      case Player.STATE_ENDED:
        if (state == STATE_PLAYING) {
          setState(STATE_LOADING, null);
        }
        break;
    }
  }

  @Override
  public void onPlayerError (@NonNull PlaybackException error) {
    Log.e(Log.TAG_VOIP, "Live stream playback error, container: %s", error, lastContainer);
    if (player == null) {
      return;
    }
    // Skip the broken segment and continue with the next ones
    int index = player.getCurrentMediaItemIndex();
    if (index + 1 < player.getMediaItemCount()) {
      player.removeMediaItems(0, index + 1);
      player.prepare();
    } else {
      player.clearMediaItems();
      isPrepared = false;
      queuedSegments = 0;
    }
    failureCount++;
    if (failureCount >= MAX_FAILURES) {
      setState(STATE_ERROR, error.getMessage());
    }
  }

  @Override
  public void onVideoSizeChanged (@NonNull VideoSize videoSize) {
    callback.onVideoSizeChanged(this, videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio);
  }

  // Segment parsing

  private static final int PART_SIGNATURE = 0xa12e810d;

  /**
   * Strips the tgcalls broadcast part header.
   *
   * @return the media container bytes, or the input itself if it has no header
   */
  @Nullable
  byte[] extractMedia (byte[] data) {
    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    try {
      if (buffer.remaining() < 4 || buffer.getInt() != PART_SIGNATURE) {
        return data;
      }
      lastContainer = readSerializedString(buffer);
      buffer.getInt(); // activeMask
      int eventCount = buffer.getInt();
      if (eventCount <= 0) {
        return null;
      }
      // tgcalls reads exactly one event (consumeVideoStreamInfo)
      int offset = buffer.getInt();
      readSerializedString(buffer); // endpointId
      buffer.getInt(); // rotation
      buffer.getInt(); // extra
      int headerSize = buffer.position();
      int start = headerSize + Math.max(0, offset);
      if (start >= data.length) {
        return null;
      }
      byte[] result = new byte[data.length - start];
      System.arraycopy(data, start, result, 0, result.length);
      return result;
    } catch (RuntimeException e) {
      Log.w(Log.TAG_VOIP, "Unable to parse live stream part header", e);
      return null;
    }
  }

  private static String readSerializedString (ByteBuffer buffer) {
    int first = buffer.get() & 0xff;
    int length;
    int padding;
    if (first == 254) {
      int b0 = buffer.get() & 0xff;
      int b1 = buffer.get() & 0xff;
      int b2 = buffer.get() & 0xff;
      length = b0 | (b1 << 8) | (b2 << 16);
      padding = roundUp(length, 4) - length;
    } else {
      length = first;
      padding = roundUp(length + 1, 4) - (length + 1);
    }
    byte[] bytes = new byte[length];
    buffer.get(bytes);
    buffer.position(buffer.position() + padding);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static int roundUp (int value, int multiple) {
    int remainder = value % multiple;
    return remainder == 0 ? value : value + multiple - remainder;
  }
}
