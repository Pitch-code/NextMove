package com.pitchcode.nextmove.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.pitchcode.nextmove.MainActivity;
import com.pitchcode.nextmove.R;
import com.pitchcode.nextmove.data.FlaggedStore;

public final class NotificationHelper {
    public static final String CHANNEL_ID = "nextmove_reminders";
    public static final String ALERT_CHANNEL_ID = "nextmove_alerts";

    private NotificationHelper() {}

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription(context.getString(R.string.alert_channel_description));
        alertChannel.enableVibration(true);
        manager.createNotificationChannel(alertChannel);
    }

    public static boolean areEnabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    public static void showReady(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !areEnabled(context)) return;

        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 101, launch, pendingFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_ready_title))
                .setContentText(context.getString(R.string.notification_ready_body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        manager.notify(101, builder.build());
    }

    /** Raises a high-priority scam alert that opens the flagged-message detail screen. */
    public static void showScamAlert(Context context, FlaggedStore.Item item) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !areEnabled(context)) return;

        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_FLAGGED_ID, item.id);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, (int) item.id, launch, pendingFlags());

        String title = context.getString(item.highRisk
                ? R.string.alert_high_title : R.string.alert_review_title);
        String body = context.getString(R.string.alert_body_source, item.source);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, ALERT_CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(
                        body + "\n\n" + context.getString(R.string.alert_tap_hint)))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH);
        }
        manager.notify((int) item.id, builder.build());
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }
}
