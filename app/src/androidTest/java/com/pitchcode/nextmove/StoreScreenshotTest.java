package com.pitchcode.nextmove;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.uiautomator.UiDevice;

import com.pitchcode.nextmove.data.FlaggedStore;
import com.pitchcode.nextmove.data.ReminderStore;
import com.pitchcode.nextmove.data.TrustedContact;
import com.pitchcode.nextmove.safety.VoiceRiskAssessment;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

/**
 * Captures real app screens with example data for the Google Play listing.
 * Runs only when CI passes the instrumentation argument storeShots=true
 * (see .github/workflows/store-screenshots.yml); skipped in normal test runs.
 */
@RunWith(AndroidJUnit4.class)
public final class StoreScreenshotTest {
    private static final String PREFS = "nextmove_local";
    private static final String OUT = "/data/local/tmp/store-shots";
    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Rule
    public GrantPermissionRule mic = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    private Context context;
    private UiDevice device;

    @Before
    public void seedExampleData() throws Exception {
        assumeTrue("Store screenshots run only on request", "true".equals(
                InstrumentationRegistry.getArguments().getString("storeShots")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("mkdir -p " + OUT);
        // Touch mode hides keyboard-focus highlights (e.g. on the bottom tabs).
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Calendar now = Calendar.getInstance();
        int week = now.get(Calendar.YEAR) * 53 + now.get(Calendar.WEEK_OF_YEAR);
        prefs.edit().clear()
                .putBoolean("onboarding_done", true)
                .putBoolean("setup_done", true)
                .putBoolean("premium", true)          // no ad banner in screenshots
                .putLong("trial_start", System.currentTimeMillis())
                .putBoolean("scan_enabled", true)
                .putString("language", "en")
                .putInt("sum_week", week)
                .putInt("sum_checked_week", 248)
                .putInt("sum_flagged_week", 3)
                .putInt("sum_checked_total", 1062)
                .putInt("sum_flagged_total", 11)
                .commit();

        TrustedContact.set(context, "Priya (daughter)", "98765 43210");

        FlaggedStore.add(context, "WhatsApp · +44 7700 900123",
                "Congratulations! You have won ₹25,00,000 in the lucky draw. Pay a refund "
                        + "processing fee now to claim. Send money immediately.",
                3, true, "lottery, send money, immediately");
        FlaggedStore.add(context, "Messages · VK-PARCEL",
                "Your parcel is held at customs. Pay ₹49 urgent redelivery charge: "
                        + "parcel-redelivery.top/pay", 3, true, "customs, parcel, urgent");

        long tomorrow10 = at(1, 10, 0);
        ReminderStore.add(context, ReminderStore.KIND_BILL, "Electricity bill",
                "Amount: ₹1,240", "Pay to: Consumer no. 41020375", tomorrow10);
        ReminderStore.add(context, ReminderStore.KIND_APPOINTMENT, "Dentist check-up",
                "Place: City Dental Clinic", "Notes: Carry old X-ray", at(3, 17, 30));
        ReminderStore.add(context, ReminderStore.KIND_RETURN, "Return running shoes",
                "Order: 402-1234567", "Refund: ₹2,799", at(5, 19, 0));
    }

    @Test
    public void captureStoreScreens() throws Exception {
        // 1. Home
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a -> assertNotNull(a.findViewById(R.id.screen_home)));
            shot("01-home");
        }

        // 2. Scam alert detail, opened the way the alert notification opens it.
        String message = "Dear customer, your bank account will be blocked today. Update KYC "
                + "immediately at bank-kyc-update.xyz and share the OTP to confirm.";
        VoiceRiskAssessment risk = VoiceRiskAssessment.evaluate(message);
        FlaggedStore.Item alert = FlaggedStore.add(context, "Messages · AX-KYCUPD", message,
                risk.signalCount, true, TextUtils.join(", ", risk.matchedTerms));
        backdate(alert.id, 8L * 60L * 1000L); // shows "8 minutes ago"
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_FLAGGED_ID, alert.id);
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(open)) {
            s.onActivity(a -> assertNotNull(a.findViewById(R.id.screen_flagged)));
            shot("02-scam-alert");
        }

        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            // 3. Number / link checker result
            s.onActivity(a -> {
                a.findViewById(R.id.open_checker).performClick();
                ((EditText) a.findViewById(R.id.checker_input)).setText("bank-kyc-update.xyz/claim");
                a.findViewById(R.id.checker_check).performClick();
                assertNotNull(a.findViewById(R.id.screen_checker_result));
            });
            shot("03-link-check");

            // 4. "I've been scammed" emergency steps
            s.onActivity(a -> {
                a.findViewById(R.id.nav_home).performClick();
                a.findViewById(R.id.open_panic).performClick();
                assertNotNull(a.findViewById(R.id.screen_panic));
            });
            shot("04-panic");

            // 5. Custom reminder form with an example bill
            s.onActivity(a -> {
                a.findViewById(R.id.nav_home).performClick();
                a.findViewById(R.id.sample_custom).performClick();
                ((EditText) a.findViewById(R.id.reminder_title_input)).setText("Credit card bill");
                ((EditText) a.findViewById(R.id.reminder_detail1_input)).setText("18450");
                ((EditText) a.findViewById(R.id.reminder_detail2_input)).setText("Card ending 4821");
                assertNotNull(a.findViewById(R.id.screen_reminder));
            });
            shot("05-reminder");

            // 6. Activity: weekly summary, upcoming reminders, recent alerts
            s.onActivity(a -> {
                a.findViewById(R.id.nav_activity).performClick();
                assertNotNull(a.findViewById(R.id.screen_activity));
            });
            shot("06-activity");
        }

        // 7. Settings in Hindi, showing the 6-language picker
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("language", "hi").commit();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            s.onActivity(a -> {
                a.findViewById(R.id.nav_settings).performClick();
                assertNotNull(a.findViewById(R.id.screen_settings));
            });
            shot("07-languages");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("language", "en").commit();
    }

    /** Moves a saved alert's timestamp into the past so it reads naturally. */
    private void backdate(long id, long ageMillis) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONArray array = new org.json.JSONArray(prefs.getString("flagged_alerts", "[]"));
        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject o = array.getJSONObject(i);
            if (o.optLong("id") == id) o.put("time", System.currentTimeMillis() - ageMillis);
        }
        prefs.edit().putString("flagged_alerts", array.toString()).commit();
    }

    private static long at(int daysFromNow, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, daysFromNow);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void shot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(1200); // let the screen transition and fonts settle
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        device.executeShellCommand("screencap -p " + OUT + "/" + name + ".png");
    }
}
