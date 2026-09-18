package com.pitchcode.nextmove;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MainActivityFlowTest {

    @Test
    public void backNavigationAndExactSampleContext() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.screen_home));
                click(activity, R.id.nav_settings);
                assertNotNull(activity.findViewById(R.id.screen_settings));
                activity.onBackPressed();
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
                activity.onBackPressed();
                assertFalse(activity.isFinishing());
                assertNotNull(activity.findViewById(R.id.screen_home));
            });
        }
    }

    @Test
    public void sharedTextAndVoiceDraftSurviveBack() {
        Intent sharedText = new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                MainActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "pay-now-share-OTP");

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

    private void click(MainActivity activity, int viewId) {
        View view = activity.findViewById(viewId);
        assertNotNull("Missing view " + viewId, view);
        assertTrue("View should handle click " + viewId, view.performClick());
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
