package com.pitchcode.nextmove.notifications;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.pitchcode.nextmove.data.PlanState;
import com.pitchcode.nextmove.data.ReminderStore;

/**
 * Fires custom reminders and re-schedules them after a reboot or app update.
 *
 * Uses inexact alarms (no exact-alarm permission), so a reminder can arrive a few
 * minutes after the chosen time.
 */
public final class ReminderReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.pitchcode.nextmove.REMINDER_FIRE";
    private static final String EXTRA_ID = "reminder_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            rescheduleAll(context);
            return;
        }
        if (!ACTION_FIRE.equals(action)) return;
        long id = intent.getLongExtra(EXTRA_ID, -1L);
        ReminderStore.Item item = ReminderStore.find(context, id);
        if (item == null) return;
        // Reminders follow the same plan rule as other features.
        if (!PlanState.hasAccess(context)) return;
        NotificationHelper.createChannel(context);
        NotificationHelper.showReminder(context, item);
    }

    public static void schedule(Context context, ReminderStore.Item item) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null || item.timeMillis <= System.currentTimeMillis()) return;
        try {
            alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, item.timeMillis, pendingIntent(context, item.id));
        } catch (RuntimeException ignored) {
            // Some vendor builds limit alarms; the calendar option remains available.
        }
    }

    public static void cancel(Context context, long id) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        alarms.cancel(pendingIntent(context, id));
    }

    public static void rescheduleAll(Context context) {
        for (ReminderStore.Item item : ReminderStore.upcoming(context)) {
            schedule(context, item);
        }
    }

    private static PendingIntent pendingIntent(Context context, long id) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, requestCode(id), intent, flags);
    }

    static int requestCode(long id) {
        return ("reminder" + id).hashCode();
    }
}
