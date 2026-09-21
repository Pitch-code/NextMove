package com.pitchcode.nextmove;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.SpannedString;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public final class MainActivityFlowTest {

    private static final String PREFS = "nextmove_local";
    private static final String KEY_SETUP_DONE = "setup_done";

    private static final String KEY_PREMIUM = "premium";
    private static final String KEY_TRIAL_START = "trial_start";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private void setOnboardingComplete(boolean complete) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_DONE, complete).apply();
    }

    private void setSetupComplete(boolean complete) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SETUP_DONE, complete).apply();
    }

    private void setPlan(boolean premium, long trialStart) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PREMIUM, premium)
                .putLong(KEY_TRIAL_START, trialStart)
                .apply();
    }

    @Before
    public void markSetupCompleteByDefault() {
        setOnboardingComplete(true);
        setSetupComplete(true);
        // Keep the trial active so feature screens are reachable by default.
        setPlan(false, System.currentTimeMillis());
    }

    @Test
    public void firstLaunchShowsOnboardingThenReachesHome() {
        setOnboardingComplete(false);
        setSetupComplete(true);
        setPlan(false, System.currentTimeMillis());
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_onboarding));
                assertNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.onboarding_skip);
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }

    @Test
    public void expiredTrialShowsPaywallThenUpgradeUnlocks() {
        setSetupComplete(true);
        setPlan(false, System.currentTimeMillis() - 8L * DAY_MS);
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_paywall));
                assertNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.plan_upgrade);
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }

    @Test
    public void firstRunShowsSetupThenReachesHome() {
        setSetupComplete(false);
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_setup));
                assertNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.setup_skip);
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }

    @Test
    public void backNavigationAndExactSampleContext() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.nav_settings);
                assertNotNull(activity.findViewById(R.id.screen_settings));
            });

            pressSystemBack(scenario);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertNotNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.sample_bill);
            });

            Thread.sleep(1800);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                View result = activity.findViewById(R.id.screen_result);
                assertNotNull(result);
                assertTrue(containsText(result, "SAMPLE · Electricity bill"));
            });

            pressSystemBack(scenario);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }

    @Test
    public void styledSharedTextAndVoiceDraftSurviveBack() {
        Intent sharedText = new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                MainActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, new SpannedString("pay-now-share-OTP"));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(sharedText)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_voice));
                EditText input = activity.findViewById(R.id.voice_input);
                assertNotNull(input);
                assertEquals("pay-now-share-OTP", input.getText().toString());

                click(activity, R.id.voice_check);
                assertNotNull(activity.findViewById(R.id.screen_voice_result));
                activity.onBackPressed();

                assertNotNull(activity.findViewById(R.id.screen_voice));
                EditText restored = activity.findViewById(R.id.voice_input);
                assertNotNull(restored);
                assertEquals("pay-now-share-OTP", restored.getText().toString());
            });
        }
    }

    @Test
    public void unfinishedVoiceDraftSurvivesTabRoundTripAndDisclosurePrecedesCapture() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                click(activity, R.id.voice_open);
                EditText input = activity.findViewById(R.id.voice_input);
                View disclosure = activity.findViewById(R.id.voice_disclosure);
                View start = activity.findViewById(R.id.voice_start);
                assertNotNull(input);
                assertNotNull(disclosure);
                assertNotNull(start);
                ViewGroup disclosureParent = (ViewGroup) disclosure.getParent();
                assertEquals("Disclosure and capture must share the ordered voice form",
                        disclosureParent, start.getParent());
                assertTrue("Disclosure must appear before voice capture",
                        disclosureParent.indexOfChild(disclosure)
                                < disclosureParent.indexOfChild(start));
                input.setText("unfinished fraud description");
                click(activity, R.id.nav_settings);
                activity.onBackPressed();

                EditText restored = activity.findViewById(R.id.voice_input);
                assertNotNull(restored);
                assertEquals("unfinished fraud description", restored.getText().toString());
            });
        }
    }

    @Test
    public void officialIndiaActionsUseSafeSystemIntents() {
        Intent helpline = MainActivity.createCyberHelplineIntent();
        assertEquals(Intent.ACTION_DIAL, helpline.getAction());
        assertEquals("tel:1930", helpline.getDataString());

        Intent portal = MainActivity.createCybercrimePortalIntent();
        assertEquals(Intent.ACTION_VIEW, portal.getAction());
        assertEquals("https://cybercrime.gov.in/", portal.getDataString());
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    public void notificationPermissionAppearsOnlyAfterUserTap() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String packageName = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();

        try {
            device.executeShellCommand("pm revoke " + packageName + " "
                    + Manifest.permission.POST_NOTIFICATIONS);
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not reset notification permission", error);
        }
        device.waitForIdle();

        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(PackageManager.PERMISSION_DENIED,
                        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
                assertNotNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.notification_enable);
            });

            UiObject2 allow = device.wait(Until.findObject(By.res(
                    Pattern.compile(".*permission_allow_button"))), 5000);
            assertNotNull("Notification permission dialog should follow the explicit tap", allow);
            allow.click();
            device.waitForIdle();

            scenario.onActivity(activity -> assertEquals(
                    PackageManager.PERMISSION_GRANTED,
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)));
        }
    }

    private void click(MainActivity activity, int viewId) {
        View view = activity.findViewById(viewId);
        assertNotNull("Missing view " + viewId, view);
        assertTrue("View should handle click " + viewId, view.performClick());
    }

    private void pressSystemBack(ActivityScenario<MainActivity> scenario) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack();
        } else {
            scenario.onActivity(MainActivity::onBackPressed);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private boolean containsText(View view, String expected) {
        if (view instanceof TextView text && expected.contentEquals(text.getText())) return true;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }
}
