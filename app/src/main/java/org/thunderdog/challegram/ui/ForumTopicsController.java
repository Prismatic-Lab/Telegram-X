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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.component.dialogs.ForumTopicView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.Menu;
import org.thunderdog.challegram.navigation.MoreDelegate;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.ChatListener;
import org.thunderdog.challegram.telegram.MessageListener;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibUi;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.unsorted.Settings;
import org.thunderdog.challegram.util.StringList;
import org.thunderdog.challegram.v.CustomRecyclerView;
import me.vkryl.android.widget.FrameLayoutFix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.vkryl.core.StringUtils;
import me.vkryl.core.collection.IntList;
import tgx.td.MessageId;

/**
 * TGX Mod: list of topics of a forum supergroup or of a private chat with a bot that has topics.
 */
public class ForumTopicsController extends RecyclerViewController<ForumTopicsController.Args> implements Menu, MoreDelegate, View.OnClickListener, View.OnLongClickListener {
  public static class Args {
    public final long chatId;
    public final @Nullable TdApi.ChatList chatList;

    public Args (long chatId, @Nullable TdApi.ChatList chatList) {
      this.chatId = chatId;
      this.chatList = chatList;
    }
  }

  private static final int PAGE_SIZE = 40;

  private final List<TdApi.ForumTopic> topics = new ArrayList<>();
  private final Set<Integer> pendingRefresh = new HashSet<>();
  private TopicsAdapter adapter;
  private TextView emptyView;

  private long chatId;
  private String query = "";
  private int offsetDate;
  private long offsetMessageId;
  private int offsetForumTopicId;
  private boolean isLoading, endReached, initialLoadDone;
  private int loadContextId;

  public ForumTopicsController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public void setArguments (Args args) {
    super.setArguments(args);
    this.chatId = args.chatId;
  }

  @Override
  public long getChatId () {
    return chatId;
  }

  @Override
  public int getId () {
    return R.id.controller_forumTopics;
  }

  @Override
  public CharSequence getName () {
    TdApi.Chat chat = tdlib.chat(chatId);
    return chat != null ? tdlib.chatTitle(chat) : Lang.getString(R.string.ForumTopics);
  }

  @Nullable
  private TdApi.Chat chat () {
    return tdlib.chat(chatId);
  }

  // View

  @Override
  protected View onCreateView (Context context) {
    View view = super.onCreateView(context);
    if (view instanceof FrameLayout) {
      emptyView = new TextView(context);
      emptyView.setTextSize(15f);
      emptyView.setTextColor(Theme.textDecentColor());
      addThemeTextColorListener(emptyView, ColorId.textLight);
      emptyView.setGravity(Gravity.CENTER);
      emptyView.setText(Lang.getString(R.string.NoTopics));
      emptyView.setVisibility(View.GONE);
      emptyView.setPadding(Screen.dp(24f), 0, Screen.dp(24f), 0);
      emptyView.setLayoutParams(FrameLayoutFix.newParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
      ((FrameLayout) view).addView(emptyView);
    }
    return view;
  }

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new TopicsAdapter();
    recyclerView.setAdapter(adapter);
    recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
      @Override
      public void onScrolled (@NonNull RecyclerView recyclerView, int dx, int dy) {
        LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (manager != null && manager.findLastVisibleItemPosition() >= topics.size() - 8) {
          loadMore();
        }
      }
    });
    tdlib.listeners().subscribeToChatUpdates(chatId, chatListener);
    tdlib.listeners().subscribeToMessageUpdates(chatId, messageListener);
    reload();
  }

  @Override
  public boolean needAsynchronousAnimation () {
    return !initialLoadDone;
  }

  @Override
  public long getAsynchronousAnimationTimeout (boolean fastAnimation) {
    return 400L;
  }

  @Override
  public void destroy () {
    super.destroy();
    tdlib.listeners().unsubscribeFromChatUpdates(chatId, chatListener);
    tdlib.listeners().unsubscribeFromMessageUpdates(chatId, messageListener);
    loadContextId++;
  }

  private void updateEmptyView () {
    if (emptyView != null) {
      emptyView.setVisibility(initialLoadDone && topics.isEmpty() && !isLoading ? View.VISIBLE : View.GONE);
    }
  }

  // Loading

  private void reload () {
    loadContextId++;
    isLoading = false;
    endReached = false;
    offsetDate = 0;
    offsetMessageId = 0;
    offsetForumTopicId = 0;
    int count = topics.size();
    topics.clear();
    if (adapter != null && count > 0) {
      adapter.notifyItemRangeRemoved(0, count);
    }
    loadMore();
  }

  private void loadMore () {
    if (isLoading || endReached || isDestroyed()) {
      return;
    }
    isLoading = true;
    final int contextId = loadContextId;
    tdlib.send(new TdApi.GetForumTopics(chatId, query, offsetDate, offsetMessageId, offsetForumTopicId, PAGE_SIZE), (result, error) -> runOnUiThreadOptional(() -> {
      if (contextId != loadContextId) {
        return;
      }
      isLoading = false;
      if (error != null) {
        endReached = true;
        UI.showError(error);
      } else {
        offsetDate = result.nextOffsetDate;
        offsetMessageId = result.nextOffsetMessageId;
        offsetForumTopicId = result.nextOffsetForumTopicId;
        endReached = result.topics.length == 0 || (result.nextOffsetDate == 0 && result.nextOffsetMessageId == 0 && result.nextOffsetForumTopicId == 0);
        for (TdApi.ForumTopic topic : result.topics) {
          upsertTopic(topic, true);
        }
      }
      if (!initialLoadDone) {
        initialLoadDone = true;
        executeScheduledAnimation();
      }
      updateEmptyView();
    }));
  }

  private int indexOfTopic (int forumTopicId) {
    for (int i = 0; i < topics.size(); i++) {
      if (topics.get(i).info.forumTopicId == forumTopicId) {
        return i;
      }
    }
    return -1;
  }

  private static int compare (TdApi.ForumTopic a, TdApi.ForumTopic b) {
    if (a.isPinned != b.isPinned) {
      return a.isPinned ? -1 : 1;
    }
    if (a.order != b.order) {
      return Long.compare(b.order, a.order);
    }
    return Integer.compare(b.info.forumTopicId, a.info.forumTopicId);
  }

  private void upsertTopic (TdApi.ForumTopic topic, boolean allowInsert) {
    if (topic == null || topic.info == null) {
      return;
    }
    int oldIndex = indexOfTopic(topic.info.forumTopicId);
    if (topic.info.isHidden) {
      removeTopicAt(oldIndex);
      return;
    }
    if (oldIndex == -1 && !allowInsert) {
      return;
    }
    if (oldIndex != -1) {
      topics.remove(oldIndex);
    }
    int newIndex = 0;
    while (newIndex < topics.size() && compare(topics.get(newIndex), topic) <= 0) {
      newIndex++;
    }
    topics.add(newIndex, topic);
    if (adapter != null) {
      if (oldIndex == -1) {
        adapter.notifyItemInserted(newIndex);
      } else if (oldIndex == newIndex) {
        adapter.notifyItemChanged(newIndex);
      } else {
        adapter.notifyItemMoved(oldIndex, newIndex);
        adapter.notifyItemChanged(newIndex);
      }
    }
    updateEmptyView();
  }

  private void removeTopicAt (int index) {
    if (index >= 0 && index < topics.size()) {
      topics.remove(index);
      if (adapter != null) {
        adapter.notifyItemRemoved(index);
      }
      updateEmptyView();
    }
  }

  private void refreshTopic (int forumTopicId) {
    if (forumTopicId == 0 || isDestroyed() || !pendingRefresh.add(forumTopicId)) {
      return;
    }
    final int contextId = loadContextId;
    tdlib.send(new TdApi.GetForumTopic(chatId, forumTopicId), (topic, error) -> runOnUiThreadOptional(() -> {
      pendingRefresh.remove(forumTopicId);
      if (contextId != loadContextId) {
        return;
      }
      if (error != null) {
        removeTopicAt(indexOfTopic(forumTopicId));
      } else {
        upsertTopic(topic, StringUtils.isEmpty(query));
      }
    }));
  }

  private static int forumTopicIdOf (@Nullable TdApi.Message message) {
    if (message != null && message.topicId != null && message.topicId.getConstructor() == TdApi.MessageTopicForum.CONSTRUCTOR) {
      return ((TdApi.MessageTopicForum) message.topicId).forumTopicId;
    }
    return 0;
  }

  private int findTopicByLastMessage (long messageId) {
    for (TdApi.ForumTopic topic : topics) {
      if (topic.lastMessage != null && topic.lastMessage.id == messageId) {
        return topic.info.forumTopicId;
      }
    }
    return 0;
  }

  // Updates

  private final ChatListener chatListener = new ChatListener() {
    @Override
    public void onForumTopicInfoChanged (TdApi.ForumTopicInfo info) {
      if (info.chatId != chatId) {
        return;
      }
      runOnUiThreadOptional(() -> {
        int index = indexOfTopic(info.forumTopicId);
        if (index != -1) {
          TdApi.ForumTopic topic = topics.get(index);
          topic.info = info;
          upsertTopic(topic, false);
        } else {
          refreshTopic(info.forumTopicId);
        }
      });
    }

    @Override
    public void onForumTopicUpdated (long updateChatId, long messageThreadId, boolean isPinned, long lastReadInboxMessageId, long lastReadOutboxMessageId, TdApi.ChatNotificationSettings notificationSettings) {
      if (updateChatId != chatId) {
        return;
      }
      runOnUiThreadOptional(() -> refreshTopic((int) messageThreadId));
    }
  };

  private final MessageListener messageListener = new MessageListener() {
    @Override
    public void onNewMessage (TdApi.Message message) {
      int topicId = forumTopicIdOf(message);
      if (topicId != 0) {
        runOnUiThreadOptional(() -> refreshTopic(topicId));
      }
    }

    @Override
    public void onMessageSendSucceeded (TdApi.Message message, long oldMessageId) {
      int topicId = forumTopicIdOf(message);
      if (topicId != 0) {
        runOnUiThreadOptional(() -> refreshTopic(topicId));
      }
    }

    @Override
    public void onMessageContentChanged (long chatId, long messageId, TdApi.MessageContent newContent) {
      runOnUiThreadOptional(() -> refreshTopic(findTopicByLastMessage(messageId)));
    }

    @Override
    public void onMessagesDeleted (long chatId, long[] messageIds) {
      runOnUiThreadOptional(() -> {
        for (long messageId : messageIds) {
          refreshTopic(findTopicByLastMessage(messageId));
        }
      });
    }
  };

  // Opening

  private void openTopic (TdApi.ForumTopic topic) {
    TdApi.MessageTopicForum topicId = new TdApi.MessageTopicForum(topic.info.forumTopicId);
    TdlibUi.ChatOpenParameters params = new TdlibUi.ChatOpenParameters()
      .keepStack()
      .messageTopic(topicId)
      .forumTopic(topic);
    Args args = getArguments();
    if (args != null && args.chatList != null) {
      params.chatList(args.chatList);
    }
    if (topic.unreadCount > 0 && topic.lastReadInboxMessageId != 0) {
      params.highlightMessage(MessagesManager.HIGHLIGHT_MODE_UNREAD, new MessageId(chatId, topic.lastReadInboxMessageId));
    } else {
      Settings.SavedMessageId saved = Settings.instance().getScrollMessageId(tdlib.id(), chatId, topicId);
      if (saved != null && saved.id.getMessageId() != 0) {
        params.highlightMessage(MessagesManager.HIGHLIGHT_MODE_POSITION_RESTORE, saved.id);
      }
    }
    tdlib.ui().openChat(this, chatId, params);
  }

  private void openAsMessages () {
    TdApi.Chat chat = chat();
    if (chat == null) {
      return;
    }
    if (tdlib.isForum(chatId)) {
      tdlib.send(new TdApi.ToggleChatViewAsTopics(chatId, false), tdlib.typedOkHandler());
    }
    TdlibUi.ChatOpenParameters params = new TdlibUi.ChatOpenParameters().keepStack().viewAsMessages();
    Args args = getArguments();
    if (args != null && args.chatList != null) {
      params.chatList(args.chatList);
    }
    // Replace the topics list with the chat, like official apps do, once the transition is over
    params.after(openedChatId -> runOnUiThreadOptional(() -> {
      for (int i = stackSize() - 2; i >= 0; i--) {
        if (stackItemAt(i) == this) {
          destroyStackItemAt(i);
          break;
        }
      }
    }, null, 800L));
    tdlib.ui().openChat(this, chat, params);
  }

  @Override
  public void onClick (View v) {
    if (v instanceof ForumTopicView) {
      TdApi.ForumTopic topic = ((ForumTopicView) v).getTopic();
      if (topic != null) {
        openTopic(topic);
      }
    }
  }

  // Topic options

  @Override
  public boolean onLongClick (View v) {
    if (!(v instanceof ForumTopicView)) {
      return false;
    }
    TdApi.ForumTopic topic = ((ForumTopicView) v).getTopic();
    TdApi.Chat chat = chat();
    if (topic == null || chat == null) {
      return false;
    }
    final TdApi.ForumTopicInfo info = topic.info;
    IntList ids = new IntList(5);
    StringList strings = new StringList(5);
    IntList icons = new IntList(5);
    IntList colors = new IntList(5);
    if ((topic.unreadCount > 0 || topic.unreadMentionCount > 0) && topic.lastMessage != null) {
      ids.append(R.id.btn_readTopic);
      strings.append(R.string.MarkAsRead);
      icons.append(R.drawable.baseline_done_all_24);
      colors.append(OptionColor.NORMAL);
    }
    if (ForumTopics.canPinTopic(tdlib, chat)) {
      ids.append(R.id.btn_toggleTopicPinned);
      strings.append(topic.isPinned ? R.string.Unpin : R.string.Pin);
      icons.append(topic.isPinned ? R.drawable.deproko_baseline_pin_undo_24 : R.drawable.deproko_baseline_pin_24);
      colors.append(OptionColor.NORMAL);
    }
    if (ForumTopics.canEditTopic(tdlib, chat, info)) {
      ids.append(R.id.btn_editTopic);
      strings.append(R.string.EditTopic);
      icons.append(R.drawable.baseline_edit_24);
      colors.append(OptionColor.NORMAL);
    }
    if (ForumTopics.canCloseTopic(tdlib, chat, info)) {
      ids.append(R.id.btn_toggleTopicClosed);
      strings.append(info.isClosed ? R.string.ReopenTopic : R.string.CloseTopic);
      icons.append(R.drawable.baseline_lock_24);
      colors.append(OptionColor.NORMAL);
    }
    if (ForumTopics.canDeleteTopic(tdlib, chat, info)) {
      ids.append(R.id.btn_deleteTopic);
      strings.append(R.string.DeleteTopic);
      icons.append(R.drawable.baseline_delete_24);
      colors.append(OptionColor.RED);
    }
    if (ids.isEmpty()) {
      return false;
    }
    showOptions(info.name, ids.get(), strings.get(), colors.get(), icons.get(), (optionItemView, id) -> {
      if (id == R.id.btn_readTopic) {
        if (topic.lastMessage != null) {
          tdlib.send(new TdApi.ViewMessages(chatId, new long[] {topic.lastMessage.id}, new TdApi.MessageSourceForumTopicHistory(), true), tdlib.typedOkHandler());
        }
        if (topic.unreadMentionCount > 0) {
          tdlib.send(new TdApi.ReadAllForumTopicMentions(chatId, info.forumTopicId), tdlib.typedOkHandler());
        }
      } else if (id == R.id.btn_toggleTopicPinned) {
        tdlib.send(new TdApi.ToggleForumTopicIsPinned(chatId, info.forumTopicId, !topic.isPinned), tdlib.typedOkHandler(() -> runOnUiThreadOptional(() -> refreshTopic(info.forumTopicId))));
      } else if (id == R.id.btn_editTopic) {
        openInputAlert(Lang.getString(R.string.EditTopic), Lang.getString(R.string.TopicName), R.string.Save, R.string.Cancel, info.name, (inputView, result) -> {
          String name = result.trim();
          if (StringUtils.isEmpty(name)) {
            return false;
          }
          tdlib.send(new TdApi.EditForumTopic(chatId, info.forumTopicId, name, false, 0), tdlib.typedOkHandler());
          return true;
        }, true);
      } else if (id == R.id.btn_toggleTopicClosed) {
        tdlib.send(new TdApi.ToggleForumTopicIsClosed(chatId, info.forumTopicId, !info.isClosed), tdlib.typedOkHandler());
      } else if (id == R.id.btn_deleteTopic) {
        showOptions(Lang.getMarkdownString(this, R.string.DeleteTopicConfirm, info.name),
          new int[] {R.id.btn_deleteTopic, R.id.btn_cancel},
          new String[] {Lang.getString(R.string.DeleteTopic), Lang.getString(R.string.Cancel)},
          new int[] {OptionColor.RED, OptionColor.NORMAL},
          new int[] {R.drawable.baseline_delete_24, R.drawable.baseline_cancel_24},
          (confirmView, confirmId) -> {
            if (confirmId == R.id.btn_deleteTopic) {
              tdlib.send(new TdApi.DeleteForumTopic(chatId, info.forumTopicId), (ok, error) -> {
                if (error != null) {
                  UI.showError(error);
                } else {
                  runOnUiThreadOptional(() -> removeTopicAt(indexOfTopic(info.forumTopicId)));
                }
              });
            }
            return true;
          });
      }
      return true;
    });
    return true;
  }

  private void createTopic () {
    openInputAlert(Lang.getString(R.string.NewTopic), Lang.getString(R.string.TopicName), R.string.Done, R.string.Cancel, null, (inputView, result) -> {
      String name = result.trim();
      if (StringUtils.isEmpty(name)) {
        return false;
      }
      TdApi.ForumTopicIcon icon = new TdApi.ForumTopicIcon(ForumTopics.defaultIconColor(name), 0);
      tdlib.send(new TdApi.CreateForumTopic(chatId, name, false, icon), (info, error) -> {
        if (error != null) {
          UI.showError(error);
          return;
        }
        tdlib.send(new TdApi.GetForumTopic(chatId, info.forumTopicId), (topic, topicError) -> runOnUiThreadOptional(() -> {
          if (topic != null) {
            upsertTopic(topic, true);
            openTopic(topic);
          }
        }));
      });
      return true;
    }, true);
  }

  // Menu

  @Override
  protected int getMenuId () {
    return R.id.menu_forumTopics;
  }

  @Override
  protected int getSearchMenuId () {
    return R.id.menu_clear;
  }

  @Override
  public void fillMenuItems (int id, HeaderView header, LinearLayout menu) {
    if (id == R.id.menu_forumTopics) {
      header.addSearchButton(menu, this);
      header.addMoreButton(menu, this);
    } else if (id == R.id.menu_clear) {
      header.addClearButton(menu, this);
    }
  }

  @Override
  public void onMenuItemPressed (int id, View view) {
    if (id == R.id.menu_btn_search) {
      if (headerView != null) {
        headerView.openSearchMode();
      }
    } else if (id == R.id.menu_btn_clear) {
      clearSearchInput();
    } else if (id == R.id.menu_btn_more) {
      TdApi.Chat chat = chat();
      IntList ids = new IntList(3);
      StringList strings = new StringList(3);
      if (ForumTopics.canCreateTopics(tdlib, chat)) {
        ids.append(R.id.btn_createTopic);
        strings.append(R.string.CreateTopic);
      }
      ids.append(R.id.btn_viewAsMessages);
      strings.append(R.string.ViewAsMessages);
      ids.append(R.id.btn_openChat);
      strings.append(chat != null && TD.isPrivateChat(chat.type) ? R.string.TopicsBotInfo : R.string.GroupInfo);
      showMore(ids.get(), strings.get());
    }
  }

  @Override
  public void onMoreItemPressed (int id) {
    if (id == R.id.btn_createTopic) {
      createTopic();
    } else if (id == R.id.btn_viewAsMessages) {
      openAsMessages();
    } else if (id == R.id.btn_openChat) {
      tdlib.ui().openChatProfile(this, chatId, null, null);
    }
  }

  // Search

  @Override
  protected void onLeaveSearchMode () {
    setQuery("");
  }

  @Override
  protected void onSearchInputChanged (final String input) {
    super.onSearchInputChanged(input);
    setQuery(input != null ? input.trim() : "");
  }

  private void setQuery (String query) {
    if (!StringUtils.equalsOrBothEmpty(this.query, query)) {
      this.query = query;
      reload();
    }
  }

  @Override
  public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    if (inSearchMode()) {
      if (commit) {
        closeSearchMode(null);
      }
      return true;
    }
    return super.performOnBackPressed(fromTop, commit);
  }

  // Adapter

  private static class TopicHolder extends RecyclerView.ViewHolder {
    public TopicHolder (@NonNull View itemView) {
      super(itemView);
    }
  }

  private class TopicsAdapter extends RecyclerView.Adapter<TopicHolder> {
    @NonNull
    @Override
    public TopicHolder onCreateViewHolder (@NonNull ViewGroup parent, int viewType) {
      ForumTopicView view = new ForumTopicView(context());
      Views.setClickable(view);
      view.setOnClickListener(ForumTopicsController.this);
      view.setOnLongClickListener(ForumTopicsController.this);
      org.thunderdog.challegram.support.RippleSupport.setSimpleWhiteBackground(view, ForumTopicsController.this);
      return new TopicHolder(view);
    }

    @Override
    public void onBindViewHolder (@NonNull TopicHolder holder, int position) {
      ((ForumTopicView) holder.itemView).setTopic(tdlib, topics.get(position));
    }

    @Override
    public int getItemCount () {
      return topics.size();
    }

    @Override
    public void onViewRecycled (@NonNull TopicHolder holder) {
      ((ForumTopicView) holder.itemView).setTopic(tdlib, null);
    }
  }
}
