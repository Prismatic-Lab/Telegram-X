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
import android.view.View;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.InlineResult;
import org.thunderdog.challegram.data.InlineResultCommon;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.player.TGPlayerController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.widget.EmptySmartView;

import java.util.ArrayList;
import java.util.List;

/**
 * Profile "Music" tab: audio files the user added to their profile ({@link TdApi.GetUserProfileAudios}).
 * Tracks are played with the regular player, the whole list being the play list.
 */
public class SharedProfileAudioController extends SharedBaseController<InlineResult<?>> implements TGPlayerController.TrackChangeListener, TGPlayerController.PlayListBuilder {
  private static final int PAGE_SIZE = 50;

  private volatile int totalCount = -1;
  private volatile int requestedOffset;
  private volatile boolean hasMore;

  public SharedProfileAudioController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.Music);
  }

  @Override
  public int getIcon () {
    return R.drawable.baseline_music_note_24;
  }

  private boolean isOwnProfile () {
    return tdlib.isSelfChat(getChatId());
  }

  // Fake messages for the player

  /** Wraps a profile audio into a fake message, so it can be played by {@link TGPlayerController}. */
  public static TdApi.Message newProfileAudioMessage (TdApi.Audio audio) {
    TdApi.Message message = TD.newFakeMessage(audio);
    message.id = audio.audio.id;
    return message;
  }

  /** Play list over profile audios, used both by this tab and by the profile music row. */
  public static class ProfileAudioPlayList implements TGPlayerController.PlayListBuilder {
    private final List<TdApi.Message> messages;

    public ProfileAudioPlayList (List<TdApi.Message> messages) {
      this.messages = messages;
    }

    @Nullable
    @Override
    public TGPlayerController.PlayList buildPlayList (TdApi.Message fromMessage) {
      return buildPlayList(messages, fromMessage);
    }

    @Override
    public boolean wouldReusePlayList (TdApi.Message fromMessage, boolean isReverse, boolean hasAltered, List<TdApi.Message> trackList, long playListChatId) {
      return false;
    }

    @Nullable
    static TGPlayerController.PlayList buildPlayList (List<TdApi.Message> messages, TdApi.Message fromMessage) {
      if (fromMessage == null || fromMessage.content.getConstructor() != TdApi.MessageAudio.CONSTRUCTOR || messages.isEmpty()) {
        return null;
      }
      ArrayList<TdApi.Message> out = new ArrayList<>(messages.size());
      int foundIndex = -1;
      // Same order as in InstantViewController: last item first
      for (int i = messages.size() - 1; i >= 0; i--) {
        TdApi.Message msg = messages.get(i);
        if (foundIndex == -1 && TGPlayerController.compareTracks(fromMessage, msg)) {
          foundIndex = out.size();
          msg = fromMessage;
        }
        out.add(msg);
      }
      if (foundIndex == -1) {
        return null;
      }
      return new TGPlayerController.PlayList(out, foundIndex);
    }
  }

  // Loading

  @Override
  protected TdApi.Function<?> buildRequest (long chatId, TdApi.MessageTopic topicId, String query, long offset, String secretOffset, int limit) {
    this.requestedOffset = (int) offset;
    return new TdApi.GetUserProfileAudios(tdlib.chatUserId(chatId), (int) offset, Math.max(PAGE_SIZE, limit));
  }

  @Override
  protected int calculateInitialLoadCount () {
    return PAGE_SIZE;
  }

  @Nullable
  @Override
  protected ArrayList<InlineResult<?>> parseCustomResult (TdApi.Object object) {
    if (object.getConstructor() != TdApi.Audios.CONSTRUCTOR) {
      return null;
    }
    TdApi.Audios audios = (TdApi.Audios) object;
    this.totalCount = audios.totalCount;
    this.hasMore = audios.audios.length > 0 && requestedOffset + audios.audios.length < audios.totalCount;
    ArrayList<InlineResult<?>> items = new ArrayList<>(audios.audios.length);
    for (TdApi.Audio audio : audios.audios) {
      items.add(newResult(context, tdlib, audio, this));
    }
    return items;
  }

  static InlineResultCommon newResult (BaseActivity context, Tdlib tdlib, TdApi.Audio audio, TGPlayerController.PlayListBuilder builder) {
    TdApi.Message message = newProfileAudioMessage(audio);
    InlineResultCommon result = new InlineResultCommon(context, tdlib, message, (TdApi.MessageAudio) message.content, builder);
    result.setMessage(message);
    result.setIsTrack(false);
    return result;
  }

  @Override
  protected boolean supportsLoadingMore (boolean isMore) {
    return hasMore;
  }

  @Override
  protected long getCurrentOffset (ArrayList<InlineResult<?>> data, long emptyValue) {
    return data == null || data.isEmpty() ? emptyValue : data.size();
  }

  @Override
  protected InlineResult<?> parseObject (TdApi.Object object) {
    return null;
  }

  @Override
  protected boolean supportsMessageContent () {
    return false;
  }

  @Override
  protected boolean needDateSectionSplitting () {
    return false;
  }

  @Override
  protected boolean canSearch () {
    return false;
  }

  @Override
  protected boolean probablyHasEmoji () {
    return true;
  }

  @Override
  protected int provideViewType () {
    return ListItem.TYPE_CUSTOM_INLINE;
  }

  @Override
  protected int getEmptySmartMode () {
    return EmptySmartView.MODE_EMPTY_MUSIC;
  }

  @Override
  protected CharSequence buildTotalCount (ArrayList<InlineResult<?>> data) {
    return Lang.pluralBold(R.string.xProfileAudios, Math.max(totalCount, data != null ? data.size() : 0));
  }

  @Override
  protected void onCreateView (Context context, org.thunderdog.challegram.v.MediaRecyclerView recyclerView, SettingsAdapter adapter) {
    super.onCreateView(context, recyclerView, adapter);
    tdlib.context().player().addTrackChangeListener(this);
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.context().player().removeTrackChangeListener(this);
  }

  // Clicks

  @Override
  public void onClick (View v) {
    ListItem item = (ListItem) v.getTag();
    if (item != null && item.getViewType() == ListItem.TYPE_CUSTOM_INLINE && item.getData() instanceof InlineResult) {
      InlineResult<?> result = (InlineResult<?>) item.getData();
      if (result.getMessage() != null) {
        tdlib.context().player().playPauseMessage(tdlib, result.getMessage(), this);
      }
    }
  }

  @Override
  protected boolean needsCustomLongClickListener () {
    return true;
  }

  @Override
  protected boolean onLongClick (View v, ListItem item) {
    if (!isOwnProfile() || !(item.getData() instanceof InlineResult)) {
      return false;
    }
    final InlineResult<?> result = (InlineResult<?>) item.getData();
    final TdApi.Message message = result.getMessage();
    if (message == null || message.content.getConstructor() != TdApi.MessageAudio.CONSTRUCTOR) {
      return false;
    }
    final int fileId = ((TdApi.MessageAudio) message.content).audio.audio.id;
    showOptions(null, new int[] {R.id.btn_removeProfileAudio, R.id.btn_cancel}, new String[] {Lang.getString(R.string.ProfileMusicRemove), Lang.getString(R.string.Cancel)}, new int[] {ViewController.OptionColor.RED, ViewController.OptionColor.NORMAL}, new int[] {R.drawable.baseline_delete_24, R.drawable.baseline_cancel_24}, (itemView, id) -> {
      if (id == R.id.btn_removeProfileAudio) {
        tdlib.send(new TdApi.RemoveProfileAudio(fileId), (ok, error) -> runOnUiThreadOptional(() -> {
          if (error != null) {
            UI.showError(error);
          } else {
            removeResult(result);
          }
        }));
      }
      return true;
    });
    return true;
  }

  private void removeResult (InlineResult<?> result) {
    if (data == null) {
      return;
    }
    int index = data.indexOf(result);
    if (index == -1) {
      return;
    }
    data.remove(index);
    if (totalCount > 0) {
      totalCount--;
    }
    int position = adapter.indexOfViewByData(result);
    if (position != -1) {
      adapter.removeItem(position);
      // Remove the separator that preceded or followed the item
      if (position < adapter.getItems().size() && adapter.getItems().get(position).getViewType() == ListItem.TYPE_SEPARATOR) {
        adapter.removeItem(position);
      } else if (position > 0 && adapter.getItems().get(position - 1).getViewType() == ListItem.TYPE_SEPARATOR) {
        adapter.removeItem(position - 1);
      }
      onItemsHeightProbablyChanged();
    }
    if (data.isEmpty()) {
      buildCells();
    }
  }

  // Player

  @Override
  public void onTrackChanged (Tdlib tdlib, @Nullable TdApi.Message newTrack, int fileId, int state, float progress, boolean byUser) {
    if (data == null) {
      return;
    }
    for (InlineResult<?> result : data) {
      if (result instanceof InlineResultCommon) {
        ((InlineResultCommon) result).setIsTrackCurrent(newTrack != null && TGPlayerController.compareTracks(result.getMessage(), newTrack));
      }
    }
  }

  @Nullable
  @Override
  public TGPlayerController.PlayList buildPlayList (TdApi.Message fromMessage) {
    if (data == null) {
      return null;
    }
    ArrayList<TdApi.Message> messages = new ArrayList<>(data.size());
    for (InlineResult<?> result : data) {
      if (result.getMessage() != null) {
        messages.add(result.getMessage());
      }
    }
    return ProfileAudioPlayList.buildPlayList(messages, fromMessage);
  }

  @Override
  public boolean wouldReusePlayList (TdApi.Message fromMessage, boolean isReverse, boolean hasAltered, List<TdApi.Message> trackList, long playListChatId) {
    return false;
  }
}
