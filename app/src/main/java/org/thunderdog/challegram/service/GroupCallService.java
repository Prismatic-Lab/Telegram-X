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
 * TGX Mod: keeps the process alive while the user is in a video chat or watches a live stream.
 */
package org.thunderdog.challegram.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.Intents;
import org.thunderdog.challegram.voip.GroupCallSession;

import me.vkryl.core.StringUtils;

public class GroupCallService extends Service {
  private static final int NOTIFICATION_ID = Integer.MAX_VALUE - 40;
  private static final String ACTION_LEAVE = "org.thunderdog.challegram.GROUP_CALL_LEAVE";
  private static final String EXTRA_TITLE = "title";
  private static final String EXTRA_ACCOUNT_ID = "account_id";
  private static final String EXTRA_IS_STREAM = "is_stream";

  public static void start (Context context, GroupCallSession session) {
    TdApi.GroupCall groupCall = session.getGroupCall();
    String title = groupCall != null && !StringUtils.isEmpty(groupCall.title) ? groupCall.title : session.tdlib.chatTitle(session.chatId);
    Intent intent = new Intent(context, GroupCallService.class);
    intent.putExtra(EXTRA_TITLE, title);
    intent.putExtra(EXTRA_ACCOUNT_ID, session.tdlib.id());
    intent.putExtra(EXTRA_IS_STREAM, session.isRtmpStream());
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent);
      } else {
        context.startService(intent);
      }
    } catch (Throwable t) {
      Log.e(Log.TAG_VOIP, "Unable to start GroupCallService", t);
    }
  }

  public static void stop (Context context) {
    try {
      context.stopService(new Intent(context, GroupCallService.class));
    } catch (Throwable t) {
      Log.e(Log.TAG_VOIP, "Unable to stop GroupCallService", t);
    }
  }

  @Nullable
  @Override
  public IBinder onBind (Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand (Intent intent, int flags, int startId) {
    if (intent != null && ACTION_LEAVE.equals(intent.getAction())) {
      GroupCallSession.leaveActive();
      stopSelf();
      return START_NOT_STICKY;
    }
    String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
    int accountId = intent != null ? intent.getIntExtra(EXTRA_ACCOUNT_ID, 0) : 0;
    boolean isStream = intent != null && intent.getBooleanExtra(EXTRA_IS_STREAM, false);

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder = new Notification.Builder(this, U.getOngoingCallsNotificationChannel());
    } else {
      builder = new Notification.Builder(this);
    }
    Intent leaveIntent = new Intent(this, GroupCallService.class);
    leaveIntent.setAction(ACTION_LEAVE);
    PendingIntent leavePendingIntent = PendingIntent.getService(this, 0, leaveIntent, PendingIntent.FLAG_UPDATE_CURRENT | Intents.mutabilityFlags(false));
    PendingIntent contentIntent = PendingIntent.getActivity(this, 0, Intents.valueOfMain(accountId), PendingIntent.FLAG_UPDATE_CURRENT | Intents.mutabilityFlags(false));
    builder
      .setContentTitle(StringUtils.isEmpty(title) ? Lang.getString(isStream ? R.string.LiveStreamBarTitle : R.string.VideoChatBarTitle) : title)
      .setContentText(Lang.getString(isStream ? R.string.LiveStreamBarTitle : R.string.VideoChatBarTitle))
      .setSmallIcon(R.drawable.baseline_phone_24_white)
      .setOngoing(true)
      .setContentIntent(contentIntent);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
      builder.addAction(new Notification.Action.Builder(R.drawable.baseline_call_end_24, Lang.getString(R.string.VideoChatLeave), leavePendingIntent).build());
    }
    Notification notification = builder.build();
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        int type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        if (!isStream) {
          type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
          startForeground(NOTIFICATION_ID, notification, type);
        } catch (SecurityException e) {
          startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        }
      } else {
        startForeground(NOTIFICATION_ID, notification);
      }
    } catch (Throwable t) {
      Log.e(Log.TAG_VOIP, "Unable to start GroupCallService in foreground", t);
    }
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy () {
    super.onDestroy();
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
    } catch (Throwable ignored) { }
  }
}
