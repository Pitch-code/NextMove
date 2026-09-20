package com.pitchcode.nextmove.scan;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
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
import java.util.LinkedHashSet;
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
        String body = collectMessageText(notification, extras);
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

    /**
     * Gathers all readable text from a message notification. This covers plain
     * notifications, expanded (BigText) notifications, bundled multi-line
     * (InboxStyle) notifications, and WhatsApp-style conversation notifications
     * (MessagingStyle), which carry each recent message separately.
     */
    private String collectMessageText(Notification notification, Bundle extras) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addPart(parts, charSequence(extras, Notification.EXTRA_TEXT));
        addPart(parts, charSequence(extras, Notification.EXTRA_BIG_TEXT));
        addPart(parts, charSequence(extras, Notification.EXTRA_SUB_TEXT));
        addPart(parts, charSequence(extras, Notification.EXTRA_SUMMARY_TEXT));

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                addPart(parts, line == null ? null : line.toString());
            }
        }

        // MessagingStyle notifications (WhatsApp and other chat apps) store each
        // recent message as a Bundle in EXTRA_MESSAGES. Reading it directly avoids
        // any AndroidX dependency and works on every supported version.
        try {
            Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages != null) {
                for (Parcelable parcel : messages) {
                    if (!(parcel instanceof Bundle)) continue;
                    Bundle messageBundle = (Bundle) parcel;
                    CharSequence text = messageBundle.getCharSequence("text");
                    if (text == null) continue;
                    CharSequence msgSender = messageBundle.getCharSequence("sender");
                    String name = msgSender == null ? "" : msgSender.toString().trim();
                    addPart(parts, (name.isEmpty() ? "" : name + ": ") + text);
                }
            }
        } catch (RuntimeException ignored) {
            // Some vendor notifications report a malformed message bundle array.
        }

        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(part);
        }
        return builder.toString().trim();
    }

    private static void addPart(LinkedHashSet<String> parts, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) parts.add(trimmed);
    }

    private static String charSequence(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? null : value.toString();
    }
}
