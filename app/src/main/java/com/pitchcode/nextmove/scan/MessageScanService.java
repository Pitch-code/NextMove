package com.pitchcode.nextmove.scan;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.pitchcode.nextmove.data.FlaggedStore;
import com.pitchcode.nextmove.data.HistoryStore;
import com.pitchcode.nextmove.notifications.NotificationHelper;
import com.pitchcode.nextmove.safety.VoiceRiskAssessment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads incoming message notifications (SMS, WhatsApp, Gmail and other chat apps)
 * and raises a scam alert when common fraud warning phrases are present.
 *
 * This uses Notification Access instead of the restricted SMS permissions, so it can
 * also see WhatsApp/email alerts and remains within Google Play policy. All scanning
 * happens on the device; nothing is uploaded.
 */
public final class MessageScanService extends NotificationListenerService {
    private static final String KEY_SCAN_ENABLED = "scan_enabled";
    private static final long DEDUP_WINDOW_MS = 60_000L;

    private static final Set<String> MESSAGING_PACKAGES = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.google.android.gm",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.instagram.android",
            "com.truecaller"
    ));

    // Small LRU of recently handled notifications to avoid duplicate alerts.
    private final Map<String, Long> recentlyHandled =
            new LinkedHashMap<String, Long>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 60;
                }
            };

    public static boolean isScanEnabled(Context context) {
        return HistoryStore.prefs(context).getBoolean(KEY_SCAN_ENABLED, true);
    }

    public static void setScanEnabled(Context context, boolean enabled) {
        HistoryStore.prefs(context).edit().putBoolean(KEY_SCAN_ENABLED, enabled).apply();
    }

    /** True when the user has granted Notification Access to NextMove in system settings. */
    public static boolean isListenerEnabled(Context context) {
        ComponentName component = new ComponentName(context, MessageScanService.class);
        String flat = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        for (String entry : flat.split(":")) {
            ComponentName parsed = ComponentName.unflattenFromString(entry);
            if (parsed != null && parsed.equals(component)) return true;
        }
        return false;
    }

    /** True when scanning is both permitted (listener granted) and switched on. */
    public static boolean isActive(Context context) {
        return isListenerEnabled(context) && isScanEnabled(context);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handleNotification(sbn);
        } catch (RuntimeException ignored) {
            // Never let a vendor notification quirk crash the listener.
        }
    }

    private void handleNotification(StatusBarNotification sbn) {
        if (sbn == null || !isScanEnabled(this)) return;

        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;
        if (sbn.isOngoing()) return;
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        boolean isMessaging = MESSAGING_PACKAGES.contains(pkg)
                || Notification.CATEGORY_MESSAGE.equals(notification.category);
        if (!isMessaging) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        String sender = charSequence(extras, Notification.EXTRA_TITLE);
        StringBuilder message = new StringBuilder();
        appendIfPresent(message, charSequence(extras, Notification.EXTRA_TEXT));
        appendIfPresent(message, charSequence(extras, Notification.EXTRA_BIG_TEXT));
        appendIfPresent(message, charSequence(extras, Notification.EXTRA_SUB_TEXT));
        appendIfPresent(message, charSequence(extras, Notification.EXTRA_SUMMARY_TEXT));

        String body = message.toString().trim();
        if (body.isEmpty()) return;

        String combined = (sender + " " + body).trim();
        VoiceRiskAssessment assessment = VoiceRiskAssessment.evaluate(combined);
        if (!assessment.highRisk) return;

        String dedupKey = pkg + "|" + sender + "|" + body.hashCode();
        long now = System.currentTimeMillis();
        Long last = recentlyHandled.get(dedupKey);
        if (last != null && now - last < DEDUP_WINDOW_MS) return;
        recentlyHandled.put(dedupKey, now);

        String source = appLabel(pkg, sender);
        String matched = TextUtils.join(", ", assessment.matchedTerms);
        FlaggedStore.Item item = FlaggedStore.add(
                this, source, body, assessment.signalCount, assessment.highRisk, matched);
        NotificationHelper.showScamAlert(this, item);
    }

    private String appLabel(String pkg, String sender) {
        String appName;
        try {
            appName = getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception unknownPackage) {
            appName = friendlyPackage(pkg);
        }
        if (sender != null && !sender.trim().isEmpty()) {
            return appName + " · " + sender.trim();
        }
        return appName;
    }

    private static String friendlyPackage(String pkg) {
        String lower = pkg.toLowerCase(Locale.ROOT);
        if (lower.contains("whatsapp")) return "WhatsApp";
        if (lower.contains("messaging") || lower.contains("mms")) return "Messages";
        if (lower.contains("gm") || lower.contains("gmail")) return "Gmail";
        if (lower.contains("telegram")) return "Telegram";
        return "Message";
    }

    private static void appendIfPresent(StringBuilder builder, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        if (builder.length() > 0) builder.append(' ');
        builder.append(trimmed);
    }

    private static String charSequence(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? null : value.toString();
    }
}
