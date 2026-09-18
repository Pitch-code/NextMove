package com.pitchcode.nextmove;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public final class MainActivityFlowTest
        extends ActivityInstrumentationTestCase2<MainActivity> {

    public MainActivityFlowTest() {
        super(MainActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(true);
    }

    public void testBackNavigationAndExactSampleContext() throws Throwable {
        MainActivity activity = getActivity();
        assertNotNull(activity.findViewById(R.id.screen_home));

        click(activity, R.id.nav_settings);
        assertNotNull(activity.findViewById(R.id.screen_settings));

        runTestOnUiThread(activity::onBackPressed);
        getInstrumentation().waitForIdleSync();
        assertFalse(activity.isFinishing());
        assertNotNull(activity.findViewById(R.id.screen_home));

        click(activity, R.id.sample_bill);
        Thread.sleep(1800);
        getInstrumentation().waitForIdleSync();
        View result = activity.findViewById(R.id.screen_result);
        assertNotNull(result);
        assertTrue(containsText(result, "SAMPLE · Electricity bill"));

        runTestOnUiThread(activity::onBackPressed);
        getInstrumentation().waitForIdleSync();
        assertFalse(activity.isFinishing());
        assertNotNull(activity.findViewById(R.id.screen_home));
    }

    public void testSharedTextAndVoiceDraftSurviveBack() throws Throwable {
        Intent sharedText = new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "pay-now-share-OTP");
        setActivityIntent(sharedText);

        MainActivity activity = getActivity();
        assertNotNull(activity.findViewById(R.id.screen_voice));
        EditText input = activity.findViewById(R.id.voice_input);
        assertNotNull(input);
        assertEquals("pay-now-share-OTP", input.getText().toString());

        click(activity, R.id.voice_check);
        assertNotNull(activity.findViewById(R.id.screen_voice_result));

        runTestOnUiThread(activity::onBackPressed);
        getInstrumentation().waitForIdleSync();
        assertNotNull(activity.findViewById(R.id.screen_voice));
        EditText restored = activity.findViewById(R.id.voice_input);
        assertNotNull(restored);
        assertEquals("pay-now-share-OTP", restored.getText().toString());
    }

    private void click(MainActivity activity, int viewId) throws Throwable {
        View view = activity.findViewById(viewId);
        assertNotNull("Missing view " + viewId, view);
        runTestOnUiThread(view::performClick);
        getInstrumentation().waitForIdleSync();
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
