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
package org.thunderdog.challegram.data;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.component.chat.MessageView;
import org.thunderdog.challegram.component.chat.MessagesManager;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.loader.ComplexReceiver;
import org.thunderdog.challegram.navigation.TooltipOverlayView;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.tool.Views;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.widget.MaterialEditTextGroup;
import org.thunderdog.challegram.widget.SimplestCheckBox;

import java.util.HashMap;
import java.util.Map;

import me.vkryl.android.util.ClickHelper;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

/**
 * Renders {@link TdApi.MessageChecklist}: title, list of tasks with check boxes,
 * who completed each task, "X of Y completed" counter and an optional "Add task" button.
 */
public class TGMessageChecklist extends TGMessage implements ClickHelper.Delegate {
  private static final float TASK_TEXT_OFFSET = 34f;
  private static final float TASK_MIN_HEIGHT = 46f;
  private static final float STATUS_HEIGHT = 18f;
  private static final float BUTTON_HEIGHT = 40f;

  private static final int CLICK_NONE = -1;
  private static final int CLICK_ADD_TASK = -2;

  private static class TaskEntry {
    private final int taskId;
    private TdApi.FormattedText textSource;
    @Nullable private TextWrapper text;
    @Nullable private String completedByStr;
    @Nullable private Text completedByText;

    TaskEntry (int taskId) {
      this.taskId = taskId;
    }
  }

  private TdApi.Checklist checklist;
  private TaskEntry[] tasks;

  private TdApi.FormattedText titleSource;
  @Nullable private TextWrapper titleText;
  private Text statusText;
  @Nullable private Text addTaskText;

  private String counterStr;
  private int counterStrWidth;

  // Optimistic UI: taskId -> expected "done" state, until TDLib confirms via updateMessageContent
  private final Map<Integer, Boolean> pendingStates = new HashMap<>();

  private final ClickHelper clickHelper;
  private int clickTarget = CLICK_NONE;
  private int highlightTarget = CLICK_NONE;

  public TGMessageChecklist (MessagesManager manager, TdApi.Message msg, @NonNull TdApi.Checklist checklist) {
    super(manager, msg);
    this.checklist = checklist;
    this.clickHelper = new ClickHelper(this);
  }

  // Data

  private static boolean isTaskDone (TdApi.ChecklistTask task) {
    return task.completedBy != null || task.completionDate != 0;
  }

  private boolean isTaskDisplayedDone (int index) {
    TdApi.ChecklistTask task = checklist.tasks[index];
    Boolean pending = pendingStates.get(task.id);
    return pending != null ? pending : isTaskDone(task);
  }

  private int getCompletedCount () {
    int count = 0;
    for (int i = 0; i < checklist.tasks.length; i++) {
      if (isTaskDisplayedDone(i)) {
        count++;
      }
    }
    return count;
  }

  private boolean needShowCompletedBy (TdApi.ChecklistTask task) {
    // Show the name only for shared (group) checklists, where it matters who completed a task
    return task.completedBy != null && checklist.othersCanMarkTasksAsDone && !pendingStates.containsKey(task.id);
  }

  private void setTitle (@NonNull TdApi.FormattedText title) {
    if (!Td.equalsTo(this.titleSource, title)) {
      this.titleSource = title;
      this.titleText = Td.isEmpty(title) ? null : new TextWrapper(tdlib, title, getBiggerTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> TGMessageChecklist.this.invalidateTextMediaReceiver(text, specificMedia))
        .addTextFlags(Text.FLAG_ALL_BOLD)
        .setViewProvider(currentViews);
    }
  }

  private void setTasks (TdApi.ChecklistTask[] tasks) {
    TaskEntry[] entries = new TaskEntry[tasks.length];
    for (int i = 0; i < tasks.length; i++) {
      TdApi.ChecklistTask task = tasks[i];
      TaskEntry entry = null;
      if (this.tasks != null) {
        for (TaskEntry existing : this.tasks) {
          if (existing.taskId == task.id) {
            entry = existing;
            break;
          }
        }
      }
      if (entry == null) {
        entry = new TaskEntry(task.id);
      }
      TdApi.FormattedText textToSet = task.text != null ? task.text : Td.emptyFormattedText();
      if (!Td.equalsTo(entry.textSource, textToSet)) {
        entry.textSource = textToSet;
        entry.text = new TextWrapper(tdlib, textToSet, getTextStyleProvider(), getTextColorSet(), openParameters(), (wrapper, text, specificMedia) -> TGMessageChecklist.this.invalidateTextMediaReceiver(text, specificMedia))
          .setViewProvider(currentViews);
      }
      String completedByStr = needShowCompletedBy(task) ? Lang.getString(R.string.ChecklistCompletedBy, tdlib.senderName(task.completedBy, true)) : null;
      if (!StringUtils.equalsOrBothEmpty(entry.completedByStr, completedByStr)) {
        entry.completedByStr = completedByStr;
        entry.completedByText = null; // built in buildContent
      }
      entries[i] = entry;
    }
    this.tasks = entries;
  }

  private void setTexts () {
    int statusRes = checklist.othersCanMarkTasksAsDone ? R.string.ChecklistGroup : R.string.Checklist;
    this.statusText = new Text.Builder(Lang.getString(statusRes), getContentMaxWidth(), Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
    if (checklist.canAddTasks) {
      this.addTaskText = new Text.Builder(Lang.getString(R.string.ChecklistAddTask), getContentMaxWidth(), getNameStyleProvider(), getLinkColorSet()).singleLine().allBold(true).build();
    } else {
      this.addTaskText = null;
    }
    updateCounter();
  }

  private void updateCounter () {
    int total = checklist.tasks.length;
    this.counterStr = Lang.plural(R.string.ChecklistXOfYCompleted, total, getCompletedCount());
    this.counterStrWidth = (int) U.measureText(counterStr, Paints.getRegularTextPaint(12f));
  }

  // Layout

  @Override
  protected void buildContent (int maxWidth) {
    if (tasks == null) {
      setTitle(checklist.title);
      setTasks(checklist.tasks);
      setTexts();
    }
    if (titleText != null) {
      titleText.prepare(maxWidth);
    }
    int taskWidth = maxWidth - Screen.dp(TASK_TEXT_OFFSET);
    for (TaskEntry entry : tasks) {
      if (entry.text != null) {
        entry.text.prepare(taskWidth);
      }
      if (entry.completedByStr != null) {
        entry.completedByText = new Text.Builder(entry.completedByStr, taskWidth, Paints.robotoStyleProvider(12f), getDecentColorSet()).singleLine().build();
      } else {
        entry.completedByText = null;
      }
    }
  }

  private int getTitleHeight () {
    return titleText != null ? titleText.getHeight() + Screen.dp(5f) : 0;
  }

  private int getTextTopOffset (TaskEntry entry) {
    return Math.max(Screen.dp(8f), Screen.dp(TASK_MIN_HEIGHT) / 2 - (entry.text != null ? entry.text.getLineHeight() / 2 : 0));
  }

  private int getTaskHeight (TaskEntry entry) {
    int textHeight = entry.text != null ? entry.text.getHeight() : 0;
    if (entry.completedByText != null) {
      textHeight += Screen.dp(4f) + entry.completedByText.getHeight();
    }
    return Math.max(Screen.dp(TASK_MIN_HEIGHT), getTextTopOffset(entry) + textHeight + Screen.dp(12f)) + Screen.separatorSize();
  }

  private int getTasksTop () {
    return getTitleHeight() + Screen.dp(STATUS_HEIGHT);
  }

  private int getTasksHeight () {
    int height = 0;
    if (tasks != null) {
      for (TaskEntry entry : tasks) {
        height += getTaskHeight(entry);
      }
    } else {
      height = (Screen.dp(TASK_MIN_HEIGHT) + Screen.separatorSize()) * checklist.tasks.length;
    }
    return height;
  }

  private boolean hasAddTaskButton () {
    return checklist.canAddTasks && addTaskText != null && !isEventLog();
  }

  @Override
  protected int getContentHeight () {
    int height = getTasksTop() + getTasksHeight();
    if (hasAddTaskButton()) {
      height += Screen.dp(BUTTON_HEIGHT);
    }
    height += Screen.dp(10f) + Screen.dp(14f) + Screen.dp(12f); // counter
    if (useBubbles()) {
      height += Screen.dp(8f);
    }
    return height;
  }

  @Override
  protected int getBottomLineContentWidth () {
    return counterStrWidth;
  }

  // Text media (custom emoji)

  @Override
  public void requestTextMedia (ComplexReceiver textMediaReceiver) {
    if (tasks == null) {
      textMediaReceiver.clear();
      return;
    }
    int idOffset = Integer.MAX_VALUE / (tasks.length + 1);
    if (titleText != null) {
      titleText.requestMedia(textMediaReceiver, 0, idOffset);
    } else {
      textMediaReceiver.clearReceiversRange(0, idOffset);
    }
    int key = 0;
    for (TaskEntry entry : tasks) {
      key += idOffset;
      if (entry.text != null) {
        entry.text.requestMedia(textMediaReceiver, key, idOffset);
      } else {
        textMediaReceiver.clearReceiversRange(key, key + idOffset);
      }
    }
  }

  // Drawing

  @Override
  protected void drawContent (MessageView view, Canvas c, final int startX, int startY, int maxWidth) {
    final int decentColor = getDecentColor();
    final int rightX = startX + maxWidth + (useBubbles() ? getBubblePaddingRight() : 0);
    final int leftX = startX - (useBubbles() ? getBubbleContentPadding() : 0);

    if (titleText != null) {
      titleText.draw(c, startX, startX + maxWidth, 0, startY, null, 1f, view.getTextMediaReceiver());
      startY += getTitleHeight();
    }

    if (statusText != null) {
      statusText.draw(c, startX, startY);
    }
    startY += Screen.dp(STATUS_HEIGHT);

    final int lineColor = getVerticalLineColor();
    final int contentColor = getVerticalLineContentColor();

    for (int i = 0; i < tasks.length; i++) {
      TaskEntry entry = tasks[i];
      int taskHeight = getTaskHeight(entry);

      if (highlightTarget == i) {
        c.drawRect(leftX, startY, rightX, startY + taskHeight, Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }

      // separator
      int separatorY = startY + taskHeight - Screen.separatorSize();
      c.drawLine(startX + Screen.dp(TASK_TEXT_OFFSET), separatorY, rightX, separatorY, Paints.getProgressPaint(getSeparatorColor(), Screen.separatorSize()));

      int textY = startY + getTextTopOffset(entry);
      if (entry.text != null) {
        entry.text.draw(c, startX + Screen.dp(TASK_TEXT_OFFSET), startX + maxWidth, 0, textY, null, 1f, view.getTextMediaReceiver());
        textY += entry.text.getHeight();
      }
      if (entry.completedByText != null) {
        entry.completedByText.draw(c, startX + Screen.dp(TASK_TEXT_OFFSET), textY + Screen.dp(4f));
      }

      // check box
      int boxCx = startX + Screen.dp(12f);
      int boxCy = startY + Screen.dp(22f);
      if (isTaskDisplayedDone(i)) {
        float scale = .9f;
        final int restoreToCount = Views.save(c);
        c.scale(scale, scale, boxCx, boxCy);
        SimplestCheckBox.draw(c, boxCx, boxCy, 1f, null, null, lineColor, contentColor, SimplestCheckBox.MODE_NORMAL, 1f);
        Views.restore(c, restoreToCount);
      } else {
        int radius = Screen.dp(9f) - Screen.dp(1f);
        RectF rectF = Paints.getRectF();
        rectF.set(boxCx - radius, boxCy - radius, boxCx + radius, boxCy + radius);
        int squareRadius = Screen.dp(3f);
        c.drawRoundRect(rectF, squareRadius, squareRadius, Paints.getProgressPaint(checklist.canMarkTasksAsDone ? decentColor : ColorUtils.alphaColor(.5f, decentColor), Screen.dp(1f)));
      }

      startY += taskHeight;
    }

    if (hasAddTaskButton()) {
      if (highlightTarget == CLICK_ADD_TASK) {
        c.drawRect(leftX, startY, rightX, startY + Screen.dp(BUTTON_HEIGHT), Paints.fillingPaint(Theme.getColor(getPressColorId())));
      }
      int x = startX + maxWidth / 2 - addTaskText.getWidth() / 2;
      int y = startY + Screen.dp(BUTTON_HEIGHT) / 2 - addTaskText.getHeight() / 2;
      addTaskText.draw(c, x, y);
      startY += Screen.dp(BUTTON_HEIGHT);
    }

    // "X of Y completed"
    startY += Screen.dp(10f) + Screen.dp(12f);
    int textCx = startX + maxWidth / 2 - counterStrWidth / 2;
    int textCy = startY + Screen.dp(12f) - Screen.dp(useBubbles() ? 5f : 7f);
    c.drawText(counterStr, textCx, textCy, Paints.getRegularTextPaint(12f, decentColor));
  }

  // Updates

  private void applyChecklist (TdApi.Checklist updated) {
    this.checklist = updated;
    // Drop optimistic states that TDLib has confirmed (or rejected by a newer state)
    if (!pendingStates.isEmpty()) {
      for (TdApi.ChecklistTask task : updated.tasks) {
        Boolean pending = pendingStates.get(task.id);
        if (pending != null && pending == isTaskDone(task)) {
          pendingStates.remove(task.id);
        }
      }
    }
    setTitle(updated.title);
    setTasks(updated.tasks);
    setTexts();
    rebuildAndUpdateContent();
    invalidateTextMediaReceiver();
  }

  @Override
  protected boolean onMessageContentChanged (TdApi.Message message, TdApi.MessageContent oldContent, TdApi.MessageContent newContent, boolean isBottomMessage) {
    if (newContent.getConstructor() == TdApi.MessageChecklist.CONSTRUCTOR) {
      applyChecklist(((TdApi.MessageChecklist) newContent).list);
      return true;
    }
    return false;
  }

  @Override
  protected boolean updateMessageContent (TdApi.Message message, TdApi.MessageContent newContent, boolean isBottomMessage) {
    applyChecklist(((TdApi.MessageChecklist) newContent).list);
    return true;
  }

  @Override
  protected boolean onLocaleChange () {
    setTasks(checklist.tasks);
    setTexts();
    return true;
  }

  // Touch

  @Override
  public boolean onTouchEvent (MessageView view, MotionEvent e) {
    boolean res = super.onTouchEvent(view, e);
    return (!isEventLog() && clickHelper.onTouchEvent(view, e)) || res;
  }

  @Override
  public boolean performLongPress (View view, float x, float y) {
    clickHelper.cancel(view, x, y);
    return super.performLongPress(view, x, y);
  }

  @Override
  public boolean needClickAt (View view, float originalX, float originalY) {
    final float x = originalX - getContentX();
    final float y = originalY - getContentY();
    final int maxWidth = getContentMaxWidth();

    if (x < 0 || y < 0 || x > maxWidth || isNotSent() || tasks == null) {
      return false;
    }

    int startY = getTasksTop();
    for (int i = 0; i < tasks.length; i++) {
      int taskHeight = getTaskHeight(tasks[i]);
      if (y >= startY && y < startY + taskHeight) {
        clickTarget = i;
        return true;
      }
      startY += taskHeight;
    }

    if (hasAddTaskButton() && y >= startY && y < startY + Screen.dp(BUTTON_HEIGHT)) {
      clickTarget = CLICK_ADD_TASK;
      return true;
    }

    return false;
  }

  @Override
  public void onClickTouchDown (View view, float x, float y) {
    if (clickTarget == CLICK_ADD_TASK || (clickTarget >= 0 && checklist.canMarkTasksAsDone)) {
      setHighlightTarget(clickTarget);
    }
  }

  @Override
  public void onClickTouchUp (View view, float x, float y) {
    setHighlightTarget(CLICK_NONE);
  }

  private void setHighlightTarget (int target) {
    if (highlightTarget != target) {
      highlightTarget = target;
      invalidate();
    }
  }

  @Override
  public void onClickAt (View view, float x, float y) {
    final int target = clickTarget;
    clickTarget = CLICK_NONE;
    if (target == CLICK_NONE) {
      return;
    }
    if (isScheduled()) {
      showContentHint(view, makeTargetLocationProvider(target), R.string.ErrorScheduled);
      return;
    }
    if (target == CLICK_ADD_TASK) {
      openAddTaskInput();
    } else if (target >= 0 && target < checklist.tasks.length) {
      if (checklist.canMarkTasksAsDone) {
        toggleTask(view, target);
      } else {
        showContentHint(view, makeTargetLocationProvider(target), R.string.ChecklistCantMarkTasks);
      }
    }
  }

  private TooltipOverlayView.LocationProvider makeTargetLocationProvider (int target) {
    return (targetView, outRect) -> {
      int startY = getTasksTop();
      if (tasks != null) {
        for (int i = 0; i < tasks.length; i++) {
          int taskHeight = getTaskHeight(tasks[i]);
          if (i == target) {
            int boxCx = Screen.dp(12f);
            int boxCy = startY + Screen.dp(22f);
            int radius = Screen.dp(9f);
            outRect.set(boxCx - radius, boxCy - radius, boxCx + radius, boxCy + radius);
            return;
          }
          startY += taskHeight;
        }
      }
      outRect.set(0, startY, getContentMaxWidth(), startY + Screen.dp(BUTTON_HEIGHT));
    };
  }

  private void toggleTask (View view, int index) {
    final TdApi.ChecklistTask task = checklist.tasks[index];
    final int taskId = task.id;
    final boolean markAsDone = !isTaskDisplayedDone(index);

    pendingStates.put(taskId, markAsDone);
    setTasks(checklist.tasks);
    updateCounter();
    rebuildAndUpdateContent();

    final int[] done = markAsDone ? new int[] {taskId} : new int[0];
    final int[] notDone = markAsDone ? new int[0] : new int[] {taskId};
    tdlib.send(new TdApi.MarkChecklistTasksAsDone(msg.chatId, msg.id, done, notDone), (ok, error) -> {
      if (error != null) {
        runOnUiThreadOptional(() -> {
          Boolean pending = pendingStates.get(taskId);
          if (pending != null && pending == markAsDone) {
            pendingStates.remove(taskId);
            setTasks(checklist.tasks);
            updateCounter();
            rebuildAndUpdateContent();
          }
          int currentIndex = indexOfTask(taskId);
          showContentHint(view, makeTargetLocationProvider(currentIndex != -1 ? currentIndex : CLICK_ADD_TASK), TD.toFormattedText(TD.toErrorString(error), false));
        });
      }
    });
  }

  private int indexOfTask (int taskId) {
    for (int i = 0; i < checklist.tasks.length; i++) {
      if (checklist.tasks[i].id == taskId) {
        return i;
      }
    }
    return -1;
  }

  private int nextTaskId () {
    int maxId = 0;
    for (TdApi.ChecklistTask task : checklist.tasks) {
      maxId = Math.max(maxId, task.id);
    }
    return maxId + 1;
  }

  private void openAddTaskInput () {
    ViewController<?> c = messagesController();
    if (c == null) {
      return;
    }
    MaterialEditTextGroup inputView = c.openInputAlert(Lang.getString(R.string.ChecklistAddTask), Lang.getString(R.string.ChecklistTaskPlaceholder), R.string.ChecklistAddTaskDone, R.string.Cancel, null, (editTextGroup, result) -> {
      String text = result != null ? result.trim().replace('\n', ' ') : null;
      if (StringUtils.isEmpty(text)) {
        return false;
      }
      TdApi.InputChecklistTask task = new TdApi.InputChecklistTask(nextTaskId(), new TdApi.FormattedText(text, new TdApi.TextEntity[0]));
      tdlib.send(new TdApi.AddChecklistTasks(msg.chatId, msg.id, new TdApi.InputChecklistTask[] {task}), (ok, error) -> {
        if (error != null) {
          UI.showError(error);
        }
      });
      return true;
    }, true);
    if (inputView != null) {
      inputView.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }
  }
}
