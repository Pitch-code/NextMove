package com.pitchcode.nextmove;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.pitchcode.nextmove.billing.BillingManager;
import com.pitchcode.nextmove.data.FlaggedStore;
import com.pitchcode.nextmove.data.HistoryStore;
import com.pitchcode.nextmove.data.PlanState;
import com.pitchcode.nextmove.data.ReminderStore;
import com.pitchcode.nextmove.data.SampleAnalysis;
import com.pitchcode.nextmove.notifications.ReminderReceiver;
import com.pitchcode.nextmove.notifications.NotificationHelper;
import com.pitchcode.nextmove.data.ScamDatabase;
import com.pitchcode.nextmove.data.ScamTips;
import com.pitchcode.nextmove.data.SummaryStore;
import com.pitchcode.nextmove.data.TrustedContact;
import com.pitchcode.nextmove.safety.LinkNumberCheck;
import com.pitchcode.nextmove.safety.VoiceRiskAssessment;
import com.pitchcode.nextmove.scan.MessageScanService;
import com.pitchcode.nextmove.ui.Design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements BillingManager.Listener {
    public static final String EXTRA_FLAGGED_ID = "com.pitchcode.nextmove.FLAGGED_ID";

    private static final int REQUEST_IMAGE = 200;
    private static final int REQUEST_NOTIFICATIONS = 201;
    private static final int REQUEST_MICROPHONE = 202;
    private static final int REQUEST_SETUP_NOTIFICATIONS = 203;
    private static final int REQUEST_SETUP_MIC = 204;
    private static final int REQUEST_REMINDER_NOTIFICATIONS = 205;
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_NOTIFICATION_REQUESTED = "notification_requested";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_DARK = "theme_dark";
    private static final String KEY_HIGH_CONTRAST = "high_contrast";
    private static final String KEY_TEXT_SCALE = "text_scale";
    private static final String CYBERCRIME_URL = "https://cybercrime.gov.in/";
    static final String PRIVACY_POLICY_URL = "https://pitch-code.github.io/NextMove/privacy.html";

    private enum Screen {
        HOME, PROCESSING, RESULT, ACTIVITY, SETTINGS, VOICE, VOICE_RESULT, SETUP, FLAGGED, PAYWALL,
        PANIC, CHECKER, CHECKER_RESULT, TIP, ONBOARDING, REMINDER
    }

    private static final class ScreenState {
        final Screen screen;
        final SampleAnalysis.Kind sampleKind;
        final String voiceDescription;
        final long flaggedId;

        ScreenState(Screen screen, SampleAnalysis.Kind sampleKind, String voiceDescription) {
            this(screen, sampleKind, voiceDescription, -1L);
        }

        ScreenState(Screen screen, SampleAnalysis.Kind sampleKind,
                    String voiceDescription, long flaggedId) {
            this.screen = screen;
            this.sampleKind = sampleKind;
            this.voiceDescription = voiceDescription;
            this.flaggedId = flaggedId;
        }

        static ScreenState of(Screen screen) {
            return new ScreenState(screen, null, null);
        }

        static ScreenState flagged(long id) {
            return new ScreenState(Screen.FLAGGED, null, null, id);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<ScreenState> screenHistory = new ArrayDeque<>();
    private FrameLayout content;
    private LinearLayout navigation;
    private int selectedTab = 0;
    private ScreenState currentScreen;
    private boolean restoringPreviousScreen;
    private Runnable pendingAnalysis;
    private SpeechRecognizer speechRecognizer;
    private EditText voiceInput;
    private EditText checkerInput;
    private TextView voiceStatus;
    private boolean startListeningAfterPermission;
    private boolean returningFromNotificationSettings;
    private boolean returningFromListenerSettings;
    private boolean launchPermissionsAsked;
    private BillingManager billing;
    // Custom reminder form state (kept while switching type or navigating away).
    private EditText reminderTitleInput;
    private EditText reminderDetail1Input;
    private EditText reminderDetail2Input;
    private String reminderDraftTitle = "";
    private String reminderDraftDetail1 = "";
    private String reminderDraftDetail2 = "";
    private final Calendar reminderWhen = Calendar.getInstance();
    private AdView adView;
    private static final String TEST_BANNER_UNIT = "ca-app-pub-3940256099942544/6300978111";

    @Override
    protected void attachBaseContext(Context newBase) {
        Context localizedContext = newBase;
        String code = savedLanguage(newBase);
        if (code != null) {
            try {
                Locale locale = Locale.forLanguageTag(code);
                Configuration configuration = new Configuration(
                        newBase.getResources().getConfiguration());
                configuration.setLocale(locale);
                localizedContext = newBase.createConfigurationContext(configuration);
            } catch (RuntimeException vendorFailure) {
                // Some vendor Android builds can reject a wrapped configuration context.
                // Falling back keeps the app usable in the device's current language.
                localizedContext = newBase;
            }
        }
        super.attachBaseContext(localizedContext);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyTheme();
        try {
            MobileAds.initialize(this, status -> { });
        } catch (Throwable adsInitFailure) {
            // Never let the ads SDK stop the app from launching.
        }
        NotificationHelper.createChannel(this);
        ReminderReceiver.rescheduleAll(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::navigateBack);
        }
        buildShell();
        if (!isOnboardingComplete()) {
            renderOnboarding(0);
        } else if (PlanState.isLocked(this)) {
            renderPaywall();
        } else if (isSharedText(getIntent())) {
            renderVoice(sharedText(getIntent()));
        } else if (hasFlaggedExtra(getIntent())) {
            renderFlaggedDetail(flaggedExtra(getIntent()));
        } else {
            renderHome();
            requestPendingPermissions();
            if (isSharedImage(getIntent())) {
                handler.postDelayed(this::showImageSelectedDialog, 350);
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroySpeechRecognizer();
        if (billing != null) billing.end();
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        super.onDestroy();
    }

    // --- Billing callbacks ---------------------------------------------------

    @Override
    public void onBillingState(String state) {
        runOnUiThread(() -> {
            if (isFinishing()) return;
            switch (state) {
                case "cancelled" -> Toast.makeText(this, R.string.billing_cancelled,
                        Toast.LENGTH_SHORT).show();
                case "purchased" -> Toast.makeText(this, R.string.billing_purchased,
                        Toast.LENGTH_LONG).show();
                case "connecting" -> Toast.makeText(this, R.string.billing_connecting,
                        Toast.LENGTH_SHORT).show();
                case "error" -> Toast.makeText(this, R.string.billing_error,
                        Toast.LENGTH_LONG).show();
                case "unavailable" -> new AlertDialog.Builder(this)
                        .setTitle(R.string.billing_unavailable_title)
                        .setMessage(R.string.billing_unavailable)
                        .setPositiveButton(R.string.billing_ok, null)
                        .show();
                default -> { }
            }
        });
    }

    @Override
    public void onPremiumChanged() {
        runOnUiThread(() -> {
            screenHistory.clear();
            currentScreen = null;
            renderHome();
        });
    }

    private void ensureBilling() {
        if (billing == null) {
            billing = new BillingManager(this, this);
            billing.start();
        }
    }

    private void startPurchase(String productId) {
        ensureBilling();
        billing.launch(this, productId);
    }

    private void requestPendingPermissions() {
        if (launchPermissionsAsked) return;
        launchPermissionsAsked = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_SETUP_NOTIFICATIONS);
            return;
        }
        requestMicIfPending();
    }

    private void requestMicIfPending() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_SETUP_MIC);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content == null || currentScreen == null) return;
        if (returningFromListenerSettings) {
            returningFromListenerSettings = false;
            boolean active = MessageScanService.isListenerEnabled(this);
            Toast.makeText(this,
                    active ? R.string.scan_enabled_toast : R.string.scan_not_enabled_toast,
                    active ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            ScreenState refresh = currentScreen;
            handler.post(() -> {
                restoringPreviousScreen = true;
                renderState(refresh);
                restoringPreviousScreen = false;
            });
        } else if (returningFromNotificationSettings) {
            returningFromNotificationSettings = false;
            if (NotificationHelper.areEnabled(this)) {
                NotificationHelper.showReady(this);
                Toast.makeText(this, R.string.notification_enabled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show();
            }
            ScreenState refresh = currentScreen;
            handler.post(() -> {
                restoringPreviousScreen = true;
                renderState(refresh);
                restoringPreviousScreen = false;
            });
        } else if (currentScreen.screen == Screen.SETTINGS) {
            handler.post(this::renderSettings);
        } else if (currentScreen.screen == Screen.SETUP) {
            handler.post(this::renderSetup);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (PlanState.isLocked(this)) {
            renderPaywall();
        } else if (isSharedText(intent)) {
            renderVoice(sharedText(intent));
        } else if (hasFlaggedExtra(intent)) {
            renderFlaggedDetail(flaggedExtra(intent));
        } else if (isSharedImage(intent)) {
            showImageSelectedDialog();
        }
    }

    private void buildShell() {
        LinearLayout shell = Design.column(this);
        shell.setBackgroundColor(Design.PAPER);
        shell.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(content);

        navigation = Design.row(this);
        navigation.setPadding(Design.dp(this, 14), Design.dp(this, 7),
                Design.dp(this, 14), Design.dp(this, 9));
        navigation.setBackgroundColor(Design.PAPER);
        shell.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(this, 70)));
        buildNavigation();
        setContentView(shell);
    }

    private void buildNavigation() {
        navigation.removeAllViews();
        navigation.addView(navItem("⌂", R.string.home_nav, 0, this::renderHome), navParams());
        navigation.addView(navItem("✓", R.string.activity_nav, 1, this::renderActivity), navParams());
        navigation.addView(navItem("••", R.string.settings_nav, 2, this::renderSettings), navParams());
    }

    private LinearLayout.LayoutParams navParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    }

    private View navItem(String symbol, int label, int index, Runnable action) {
        LinearLayout item = Design.column(this);
        item.setGravity(Gravity.CENTER);
        item.setId(switch (index) {
            case 1 -> R.id.nav_activity;
            case 2 -> R.id.nav_settings;
            default -> R.id.nav_home;
        });
        item.setMinimumHeight(Design.dp(this, 52));
        item.setContentDescription(getString(label));
        item.setClickable(true);
        item.setFocusable(true);

        TextView icon = Design.text(this, symbol, 19,
                selectedTab == index ? Design.INK : Design.MUTED, true);
        icon.setGravity(Gravity.CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Design.dp(this, 25)));

        TextView text = Design.text(this, getString(label), 11,
                selectedTab == index ? Design.INK : Design.MUTED, selectedTab == index);
        text.setGravity(Gravity.CENTER);
        item.addView(text, Design.match());

        item.setOnClickListener(view -> action.run());
        return item;
    }

    private ScrollView scrollPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return scroll;
    }

    private LinearLayout pageBody() {
        LinearLayout body = Design.column(this);
        body.setPadding(Design.dp(this, 22), Design.dp(this, 18),
                Design.dp(this, 22), Design.dp(this, 30));
        return body;
    }

    private void showScreen(View screen, int tab, ScreenState next, boolean replaceCurrent) {
        if (currentScreen != null
                && currentScreen.screen == Screen.VOICE
                && next.screen != Screen.VOICE
                && voiceInput != null) {
            currentScreen = new ScreenState(
                    Screen.VOICE, null, voiceInput.getText().toString());
        }
        if (currentScreen != null && currentScreen.screen == Screen.REMINDER) {
            captureReminderDraft();
        }
        if (currentScreen != null
                && currentScreen.screen == Screen.CHECKER
                && next.screen != Screen.CHECKER
                && checkerInput != null) {
            currentScreen = new ScreenState(
                    Screen.CHECKER, null, checkerInput.getText().toString());
        }
        if (currentScreen != null
                && currentScreen.screen == Screen.PROCESSING
                && next.screen != Screen.RESULT) {
            cancelPendingAnalysis();
        }
        if (currentScreen != null
                && currentScreen.screen == Screen.VOICE
                && next.screen != Screen.VOICE) {
            destroySpeechRecognizer();
        }
        if (!restoringPreviousScreen && currentScreen != null && !sameScreen(currentScreen, next)) {
            if (!replaceCurrent) screenHistory.push(currentScreen);
        }
        currentScreen = next;
        selectedTab = tab;
        buildNavigation();
        content.removeAllViews();
        content.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.setAlpha(0f);
        screen.setTranslationY(Design.dp(this, 7));
        screen.animate().alpha(1f).translationY(0f).setDuration(230).start();
    }

    private void showScreen(View screen, int tab, ScreenState next) {
        showScreen(screen, tab, next, false);
    }

    private boolean sameScreen(ScreenState first, ScreenState second) {
        return first.screen == second.screen
                && first.sampleKind == second.sampleKind
                && first.flaggedId == second.flaggedId;
    }

    private View brandHeader() {
        LinearLayout row = Design.row(this);
        TextView mark = Design.text(this, "N↗", 21, Design.INK, true);
        mark.setId(R.id.brand_mark);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Design.rounded(Design.SAFFRON, 15, this));
        // Hidden entry for the Google Play review team's access code.
        mark.setOnLongClickListener(view -> {
            showReviewerAccessDialog();
            return true;
        });
        row.addView(mark, new LinearLayout.LayoutParams(Design.dp(this, 48), Design.dp(this, 48)));

        LinearLayout names = Design.column(this);
        names.setPadding(Design.dp(this, 12), 0, 0, 0);
        names.addView(Design.text(this, getString(R.string.app_name), 18, Design.INK, true));
        names.addView(Design.text(this, getString(R.string.tagline), 11, Design.MUTED, false));
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView language = Design.chip(this, "🌐", false);
        language.setContentDescription(getString(R.string.language_label));
        language.setOnClickListener(view -> renderSettings());
        row.addView(language);
        return row;
    }

    private void renderHome() {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_home);
        LinearLayout body = pageBody();
        scroll.addView(body);
        body.addView(brandHeader());
        body.addView(Design.space(this, 16));
        body.addView(buildTrialBanner(), Design.match());
        body.addView(Design.space(this, 16));
        body.addView(Design.label(this, getString(R.string.home_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.home_title), 34, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(R.string.home_body), 16, Design.MUTED, false));
        body.addView(Design.space(this, 24));
        body.addView(buildShareCard(), Design.match());
        body.addView(Design.space(this, 24));
        body.addView(Design.label(this, getString(R.string.try_sample)));
        body.addView(Design.space(this, 10));
        body.addView(buildSampleRow());
        body.addView(Design.space(this, 24));
        body.addView(buildTodayCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildPrivacyStrip(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildTipCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildSafetyToolsCard(), Design.match());
        addAdIfFree(body);
        showScreen(scroll, 0, ScreenState.of(Screen.HOME));
    }

    private View buildShareCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 20), Design.dp(this, 22),
                Design.dp(this, 20), Design.dp(this, 20));
        Design.card(card, Design.INK, 26, this);

        TextView badge = Design.text(this, "＋", 20, Design.INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Design.rounded(Design.SAFFRON, 16, this));
        card.addView(badge, new LinearLayout.LayoutParams(Design.dp(this, 48), Design.dp(this, 48)));
        card.addView(Design.space(this, 18));
        card.addView(Design.text(this, getString(R.string.share_title), 25, Design.ON_INK, true));
        card.addView(Design.space(this, 7));
        card.addView(Design.text(this, getString(R.string.share_body), 14,
                Design.ON_INK_MUTED, false));
        card.addView(Design.space(this, 18));
        TextView choose = Design.button(this, getString(R.string.choose_image),
                Design.SAFFRON, Design.INK);
        choose.setContentDescription(getString(R.string.choose_image));
        choose.setOnClickListener(view -> openImagePicker());
        card.addView(choose, Design.match());
        card.addView(Design.space(this, 10));
        TextView voice = Design.button(this, "🎙  " + getString(R.string.voice_button),
                Color.TRANSPARENT, Design.ON_INK);
        voice.setId(R.id.voice_open);
        voice.setBackground(Design.outlined(Color.TRANSPARENT, Design.SAFFRON, 17, this));
        voice.setOnClickListener(view -> renderVoice());
        card.addView(voice, Design.match());
        return card;
    }

    private View buildSampleRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = Design.row(this);
        row.setPadding(0, 0, Design.dp(this, 12), 0);
        addCustomReminderChip(row);
        addSampleChip(row, SampleAnalysis.Kind.BILL, 1);
        addSampleChip(row, SampleAnalysis.Kind.VISIT, 2);
        addSampleChip(row, SampleAnalysis.Kind.RETURN, 3);
        addSampleChip(row, SampleAnalysis.Kind.SCAM, 4);
        scroll.addView(row);
        return scroll;
    }

    /** First chip in the row: lets the user create a reminder for anything not listed. */
    private void addCustomReminderChip(LinearLayout row) {
        TextView chip = Design.chip(this, "＋  " + getString(R.string.sample_custom), false);
        chip.setId(R.id.sample_custom);
        chip.setOnClickListener(view -> {
            view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70)
                    .withEndAction(() ->
                            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                    .start();
            startNewReminder(ReminderStore.KIND_BILL);
        });
        LinearLayout.LayoutParams params = Design.match();
        params.setMarginEnd(Design.dp(this, 9));
        row.addView(chip, params);
        chip.setAlpha(0f);
        chip.setTranslationY(Design.dp(this, 10));
        chip.animate().alpha(1f).translationY(0f).setDuration(280).start();
    }

    private void addSampleChip(LinearLayout row, SampleAnalysis.Kind kind, int index) {
        SampleAnalysis sample = SampleAnalysis.of(kind);
        // All sample chips share the same (unselected) style for a consistent look.
        TextView chip = Design.chip(this, getString(sample.chip), false);
        chip.setId(switch (kind) {
            case BILL -> R.id.sample_bill;
            case VISIT -> R.id.sample_visit;
            case RETURN -> R.id.sample_return;
            case SCAM -> R.id.sample_scam;
        });
        chip.setOnClickListener(view -> {
            // Cosmetic tap flourish; the analysis starts immediately so navigation
            // is not delayed.
            view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70)
                    .withEndAction(() ->
                            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                    .start();
            simulateAnalysis(kind);
        });
        LinearLayout.LayoutParams params = Design.match();
        params.setMarginEnd(Design.dp(this, 9));
        row.addView(chip, params);

        // Staggered entrance animation.
        chip.setAlpha(0f);
        chip.setTranslationY(Design.dp(this, 10));
        chip.animate().alpha(1f).translationY(0f)
                .setStartDelay(index * 70L).setDuration(280).start();
    }

    private View buildTodayCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 19), Design.dp(this, 18),
                Design.dp(this, 19), Design.dp(this, 18));
        Design.card(card, Design.CARD, 22, this);
        card.addView(Design.label(this, getString(R.string.today_label)));
        card.addView(Design.space(this, 8));
        card.addView(Design.text(this, getString(R.string.today_title), 18, Design.INK, true));
        card.addView(Design.space(this, 5));
        card.addView(Design.text(this, getString(R.string.today_body), 13, Design.MUTED, false));
        return card;
    }

    private View buildPrivacyStrip() {
        LinearLayout strip = Design.row(this);
        strip.setPadding(Design.dp(this, 15), Design.dp(this, 13),
                Design.dp(this, 15), Design.dp(this, 13));
        strip.setBackground(Design.rounded(Design.MINT, 18, this));
        TextView shield = Design.text(this, "✓", 15, Design.INK, true);
        shield.setGravity(Gravity.CENTER);
        strip.addView(shield, new LinearLayout.LayoutParams(Design.dp(this, 34), Design.dp(this, 34)));
        LinearLayout copy = Design.column(this);
        copy.addView(Design.text(this, getString(R.string.privacy_short), 13, Design.INK, true));
        copy.addView(Design.text(this, getString(R.string.privacy_short_body), 11, Design.MUTED, false));
        strip.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return strip;
    }

    private View buildNotificationSetupCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.rounded(Design.CREAM, 20, this));
        card.addView(Design.text(this, getString(R.string.notification_card_title), 16,
                Design.INK, true));
        card.addView(Design.space(this, 6));
        card.addView(Design.text(this, getString(R.string.notification_card_body), 12,
                Design.INK, false));
        card.addView(Design.space(this, 12));
        TextView enable = Design.chip(this,
                getString(NotificationHelper.areEnabled(this)
                        ? R.string.send_test_notification : R.string.enable_notifications),
                true);
        enable.setId(R.id.notification_enable);
        enable.setOnClickListener(view -> requestNotificationAccess());
        card.addView(enable, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildScanStatusCard() {
        boolean active = MessageScanService.isActive(this);
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.rounded(
                active ? Design.MINT : Design.CREAM, 20, this));
        LinearLayout titleRow = Design.row(this);
        TextView dot = Design.text(this, "🛡", 15, Design.INK, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(Design.rounded(active ? Design.MINT_STRONG : Design.CARD, 13, this));
        titleRow.addView(dot, new LinearLayout.LayoutParams(Design.dp(this, 34), Design.dp(this, 34)));
        TextView title = Design.text(this,
                getString(active ? R.string.scan_card_on_title : R.string.scan_card_off_title),
                16, Design.INK, true);
        title.setPadding(Design.dp(this, 10), 0, 0, 0);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(titleRow, Design.match());
        card.addView(Design.space(this, 8));
        card.addView(Design.text(this,
                getString(active ? R.string.scan_card_on_body : R.string.scan_card_off_body),
                12, Design.INK, false));
        card.addView(Design.space(this, 12));
        TextView chip = Design.chip(this,
                getString(active ? R.string.scan_manage : R.string.scan_enable_button), true);
        chip.setId(R.id.scan_status_action);
        chip.setOnClickListener(view -> {
            // The card now lives in Settings: "Manage" opens the system
            // Notification Access screen; otherwise guide the user to enable it.
            if (active) openListenerSettings();
            else promptEnableScan();
        });
        card.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildSafetyToolsCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        card.addView(Design.text(this, getString(R.string.safety_tools_title), 17,
                Design.INK, true));
        card.addView(Design.space(this, 6));
        card.addView(Design.text(this, getString(R.string.safety_tools_body), 13,
                Design.MUTED, false));
        card.addView(Design.space(this, 14));

        TextView voice = Design.button(this, "🎙  " + getString(R.string.open_voice_check),
                Design.INK, Design.ON_INK);
        voice.setOnClickListener(view -> renderVoice());
        card.addView(voice, Design.match());
        card.addView(Design.space(this, 9));

        TextView checker = Design.button(this, "🔍  " + getString(R.string.open_checker),
                Color.TRANSPARENT, Design.INK);
        checker.setId(R.id.open_checker);
        checker.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        checker.setOnClickListener(view -> renderChecker(""));
        card.addView(checker, Design.match());
        card.addView(Design.space(this, 9));

        TextView panic = Design.button(this, "🆘  " + getString(R.string.open_panic),
                Design.DANGER, Design.ON_INK);
        panic.setId(R.id.open_panic);
        panic.setOnClickListener(view -> renderPanic());
        card.addView(panic, Design.match());
        return card;
    }

    private View buildTipCard() {
        ScamTips.Tip tip = ScamTips.current();
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.rounded(Design.MINT, 20, this));
        card.addView(Design.label(this, getString(R.string.tip_of_week_label)));
        card.addView(Design.space(this, 7));
        card.addView(Design.text(this, getString(tip.titleRes), 16, Design.INK, true));
        card.addView(Design.space(this, 5));
        TextView preview = Design.text(this, getString(tip.bodyRes), 12, Design.INK, false);
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(preview, Design.match());
        card.addView(Design.space(this, 12));
        TextView more = Design.chip(this, getString(R.string.tip_read_more), true);
        more.setOnClickListener(view -> renderTip(ScamTips.currentIndex()));
        card.addView(more, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void simulateAnalysis(SampleAnalysis.Kind kind) {
        cancelPendingAnalysis();
        SampleAnalysis sample = SampleAnalysis.of(kind);
        renderProcessing(sample);
        pendingAnalysis = () -> {
            if (currentScreen != null && currentScreen.screen == Screen.PROCESSING) {
                pendingAnalysis = null;
                renderResult(sample);
            }
        };
        handler.postDelayed(pendingAnalysis, 1450);
    }

    private void renderProcessing(SampleAnalysis sample) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        LinearLayout page = Design.column(this);
        page.setId(R.id.screen_processing);
        page.setGravity(Gravity.CENTER);
        page.setPadding(Design.dp(this, 30), Design.dp(this, 30),
                Design.dp(this, 30), Design.dp(this, 30));
        TextView orbit = Design.text(this, "N↗", 30, Design.INK, true);
        orbit.setGravity(Gravity.CENTER);
        orbit.setBackground(Design.rounded(Design.SAFFRON, 30, this));
        page.addView(orbit, new LinearLayout.LayoutParams(Design.dp(this, 82), Design.dp(this, 82)));
        orbit.animate().rotationBy(360).setDuration(1200).start();
        page.addView(Design.space(this, 18));
        TextView sampleContext = Design.label(this,
                getString(R.string.processing_sample, getString(sample.chip)));
        sampleContext.setGravity(Gravity.CENTER);
        page.addView(sampleContext, Design.match());
        page.addView(Design.space(this, 8));
        TextView title = Design.text(this, getString(R.string.processing_title), 25, Design.INK, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, Design.match());
        page.addView(Design.space(this, 20));
        page.addView(processingStep("1", R.string.processing_step_1));
        page.addView(Design.space(this, 9));
        page.addView(processingStep("2", R.string.processing_step_2));
        page.addView(Design.space(this, 9));
        page.addView(processingStep("3", R.string.processing_step_3));
        showScreen(page, 0, new ScreenState(Screen.PROCESSING, sample.kind, null));
    }

    private View processingStep(String number, int stringId) {
        LinearLayout row = Design.row(this);
        row.setPadding(Design.dp(this, 13), Design.dp(this, 10),
                Design.dp(this, 13), Design.dp(this, 10));
        row.setBackground(Design.rounded(Design.CARD, 15, this));
        TextView count = Design.text(this, number, 12, Design.INK, true);
        count.setGravity(Gravity.CENTER);
        count.setBackground(Design.rounded(Design.MINT, 12, this));
        row.addView(count, new LinearLayout.LayoutParams(Design.dp(this, 30), Design.dp(this, 30)));
        TextView text = Design.text(this, getString(stringId), 13, Design.MUTED, false);
        text.setPadding(Design.dp(this, 11), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void renderResult(SampleAnalysis sample) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_result);
        LinearLayout body = pageBody();
        scroll.addView(body);

        LinearLayout top = Design.row(this);
        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        top.addView(back);
        TextView prototype = Design.label(this,
                getString(R.string.sample_context, getString(sample.chip)));
        prototype.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(prototype, new LinearLayout.LayoutParams(0, Design.dp(this, 44), 1));
        body.addView(top);
        body.addView(Design.space(this, 24));

        TextView ready = Design.text(this, "✓  " + getString(R.string.result_ready), 13,
                sample.danger ? Design.DANGER : Design.INK, true);
        ready.setPadding(Design.dp(this, 13), Design.dp(this, 8),
                Design.dp(this, 13), Design.dp(this, 8));
        ready.setBackground(Design.rounded(sample.danger ? Design.DANGER_SOFT : Design.MINT, 14, this));
        body.addView(ready, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 15));
        body.addView(Design.text(this, getString(sample.title), 31, Design.INK, true));
        body.addView(Design.space(this, 24));

        LinearLayout summary = Design.column(this);
        summary.setPadding(Design.dp(this, 18), Design.dp(this, 18),
                Design.dp(this, 18), Design.dp(this, 18));
        Design.card(summary, Design.CARD, 22, this);
        summary.addView(Design.label(this, getString(R.string.result_summary_label)));
        summary.addView(Design.space(this, 8));
        summary.addView(Design.text(this, getString(sample.summary), 15, Design.INK, false));
        summary.addView(Design.space(this, 16));
        summary.addView(Design.divider(this));
        summary.addView(Design.space(this, 14));
        summary.addView(buildFacts(sample));
        body.addView(summary, Design.match());
        body.addView(Design.space(this, 14));

        LinearLayout action = Design.column(this);
        action.setPadding(Design.dp(this, 18), Design.dp(this, 18),
                Design.dp(this, 18), Design.dp(this, 18));
        action.setBackground(Design.rounded(sample.danger ? Design.DANGER_SOFT : Design.CREAM, 22, this));
        action.addView(Design.label(this, getString(R.string.next_move_label)));
        action.addView(Design.space(this, 8));
        action.addView(Design.text(this, getString(sample.action), 16, Design.INK, true));
        action.addView(Design.space(this, 16));
        if (sample.danger) {
            TextView helpline = Design.button(this, getString(R.string.call_1930),
                    Design.INK, Design.ON_INK);
            helpline.setOnClickListener(view -> dialCyberHelpline());
            action.addView(helpline, Design.match());
            action.addView(Design.space(this, 9));
            TextView portal = Design.button(this, getString(R.string.report_cybercrime),
                    Color.TRANSPARENT, Design.INK);
            portal.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
            portal.setOnClickListener(view -> openCybercrimePortal());
            action.addView(portal, Design.match());
        } else {
            TextView calendar = Design.button(this, getString(R.string.add_reminder),
                    Design.INK, Design.ON_INK);
            calendar.setOnClickListener(view -> openCalendar(sample));
            action.addView(calendar, Design.match());
        }
        action.addView(Design.space(this, 9));
        TextView handled = Design.button(this, getString(R.string.mark_handled),
                Color.TRANSPARENT, Design.INK);
        handled.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        handled.setOnClickListener(view -> markHandled(sample));
        action.addView(handled, Design.match());
        body.addView(action, Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildEvidence(sample), Design.match());
        if (sample.danger) {
            body.addView(Design.space(this, 14));
            body.addView(buildOfficialIndiaCard(), Design.match());
        }
        body.addView(Design.space(this, 12));
        TextView notice = Design.text(this, getString(R.string.prototype_notice), 11, Design.MUTED, false);
        notice.setPadding(Design.dp(this, 4), 0, Design.dp(this, 4), 0);
        body.addView(notice);
        showScreen(scroll, 0,
                new ScreenState(Screen.RESULT, sample.kind, null),
                currentScreen != null && currentScreen.screen == Screen.PROCESSING);
    }

    private View buildFacts(SampleAnalysis sample) {
        LinearLayout row = Design.row(this);
        row.addView(fact(getString(sample.primaryLabel), getString(sample.primaryValue), sample.danger), Design.weight());
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Design.dp(this, 12), 1));
        row.addView(fact(getString(R.string.date_label), getString(sample.date), false), Design.weight());
        return row;
    }

    private View fact(String label, String value, boolean danger) {
        LinearLayout fact = Design.column(this);
        fact.setPadding(Design.dp(this, 13), Design.dp(this, 12),
                Design.dp(this, 13), Design.dp(this, 12));
        fact.setBackground(Design.rounded(danger ? Design.DANGER_SOFT : Design.PAPER, 15, this));
        fact.addView(Design.label(this, label));
        fact.addView(Design.space(this, 4));
        fact.addView(Design.text(this, value, 17, danger ? Design.DANGER : Design.INK, true));
        return fact;
    }

    private View buildEvidence(SampleAnalysis sample) {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        card.addView(Design.text(this, getString(R.string.why_label), 15, Design.INK, true));
        card.addView(Design.space(this, 8));
        card.addView(Design.label(this, getString(R.string.evidence_label)));
        card.addView(Design.space(this, 7));
        TextView quote = Design.text(this, getString(sample.evidence), 14, Design.INK, false);
        quote.setPadding(Design.dp(this, 12), Design.dp(this, 11),
                Design.dp(this, 12), Design.dp(this, 11));
        quote.setBackground(Design.rounded(Design.CREAM_SOFT, 12, this));
        card.addView(quote, Design.match());
        return card;
    }

    private View buildOfficialIndiaCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        card.setBackground(Design.rounded(Design.MINT, 20, this));
        card.addView(Design.label(this, getString(R.string.official_india_label)));
        card.addView(Design.space(this, 8));
        card.addView(Design.text(this, getString(R.string.official_india_body), 13,
                Design.INK, false));
        return card;
    }

    private void dialCyberHelpline() {
        try {
            startActivity(createCyberHelplineIntent());
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.dial_error, Toast.LENGTH_SHORT).show();
        }
    }

    static Intent createCyberHelplineIntent() {
        return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"));
    }

    private void openCybercrimePortal() {
        try {
            startActivity(createCybercrimePortalIntent());
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.open_link_error, Toast.LENGTH_SHORT).show();
        }
    }

    static Intent createCybercrimePortalIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(CYBERCRIME_URL));
    }

    private void markHandled(SampleAnalysis sample) {
        HistoryStore.add(this, sample.kind);
        Toast.makeText(this, R.string.done_toast, Toast.LENGTH_SHORT).show();
        renderActivity();
    }

    private void openCalendar(SampleAnalysis sample) {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_MONTH, sample.kind == SampleAnalysis.Kind.VISIT ? 4 : 1);
        start.set(Calendar.HOUR_OF_DAY, sample.kind == SampleAnalysis.Kind.VISIT ? 10 : 18);
        start.set(Calendar.MINUTE, sample.kind == SampleAnalysis.Kind.VISIT ? 30 : 0);

        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, getString(sample.calendarTitle))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.getTimeInMillis())
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.getTimeInMillis() + 30 * 60 * 1000L);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.calendar_chooser)));
        } catch (Exception error) {
            Toast.makeText(this, R.string.image_picker_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderActivity() {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_activity);
        LinearLayout body = pageBody();
        scroll.addView(body);
        body.addView(brandHeader());
        body.addView(Design.space(this, 28));
        body.addView(Design.text(this, getString(R.string.activity_title), 31, Design.INK, true));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.activity_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 22));

        body.addView(buildSummaryCard(), Design.match());
        body.addView(Design.space(this, 18));

        List<ReminderStore.Item> reminders = ReminderStore.upcoming(this);
        body.addView(Design.label(this, getString(R.string.activity_reminders_label)));
        body.addView(Design.space(this, 10));
        for (ReminderStore.Item reminder : reminders) {
            body.addView(reminderActivityCard(reminder), Design.match());
            body.addView(Design.space(this, 10));
        }
        TextView addReminder = Design.chip(this, "＋  " + getString(R.string.sample_custom), true);
        addReminder.setId(R.id.activity_add_reminder);
        addReminder.setOnClickListener(view -> startNewReminder(ReminderStore.KIND_BILL));
        body.addView(addReminder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));

        List<FlaggedStore.Item> alerts = FlaggedStore.read(this);
        if (!alerts.isEmpty()) {
            body.addView(Design.label(this, getString(R.string.activity_alerts_label)));
            body.addView(Design.space(this, 10));
            for (FlaggedStore.Item alert : alerts) {
                body.addView(flaggedActivityCard(alert), Design.match());
                body.addView(Design.space(this, 10));
            }
            TextView clearAlerts = Design.chip(this, getString(R.string.clear_alerts), false);
            clearAlerts.setOnClickListener(view -> {
                FlaggedStore.clear(this);
                renderActivity();
            });
            body.addView(clearAlerts, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(Design.space(this, 22));
            body.addView(Design.label(this, getString(R.string.activity_handled_label)));
            body.addView(Design.space(this, 10));
        }

        List<SampleAnalysis.Kind> history = HistoryStore.read(this);
        if (history.isEmpty()) {
            LinearLayout empty = Design.column(this);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Design.dp(this, 22), Design.dp(this, 32),
                    Design.dp(this, 22), Design.dp(this, 32));
            Design.card(empty, Design.CARD, 23, this);
            TextView icon = Design.text(this, "✓", 28, Design.INK, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Design.rounded(Design.MINT, 23, this));
            empty.addView(icon, new LinearLayout.LayoutParams(Design.dp(this, 58), Design.dp(this, 58)));
            empty.addView(Design.space(this, 15));
            TextView title = Design.text(this, getString(R.string.activity_empty), 18, Design.INK, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title, Design.match());
            empty.addView(Design.space(this, 6));
            TextView copy = Design.text(this, getString(R.string.activity_empty_body), 13, Design.MUTED, false);
            copy.setGravity(Gravity.CENTER);
            empty.addView(copy, Design.match());
            body.addView(empty, Design.match());
        } else {
            for (SampleAnalysis.Kind kind : history) {
                body.addView(historyCard(SampleAnalysis.of(kind)), Design.match());
                body.addView(Design.space(this, 10));
            }
            TextView clear = Design.chip(this, getString(R.string.clear_history), false);
            clear.setOnClickListener(view -> {
                HistoryStore.clear(this);
                renderActivity();
            });
            body.addView(clear, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        addAdIfFree(body);
        showScreen(scroll, 1, ScreenState.of(Screen.ACTIVITY));
    }

    private View historyCard(SampleAnalysis sample) {
        LinearLayout card = Design.row(this);
        card.setPadding(Design.dp(this, 15), Design.dp(this, 15),
                Design.dp(this, 15), Design.dp(this, 15));
        Design.card(card, Design.CARD, 19, this);
        TextView check = Design.text(this, "✓", 15, Design.INK, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(Design.rounded(Design.MINT, 15, this));
        card.addView(check, new LinearLayout.LayoutParams(Design.dp(this, 40), Design.dp(this, 40)));
        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 12), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, getString(sample.title), 14, Design.INK, true));
        copy.addView(Design.text(this, getString(R.string.handled_time), 11, Design.MUTED, false));
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(Design.text(this, "›", 25, Design.MUTED, false));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> renderResult(sample));
        return card;
    }

    private View buildSummaryCard() {
        int checked = SummaryStore.checkedThisWeek(this);
        int flagged = SummaryStore.flaggedThisWeek(this);
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 18),
                Design.dp(this, 18), Design.dp(this, 18));
        Design.card(card, Design.INK, 22, this);
        card.addView(Design.label(this, getString(R.string.summary_label)));
        card.addView(Design.space(this, 10));

        LinearLayout stats = Design.row(this);
        stats.addView(summaryStat(String.valueOf(checked), getString(R.string.summary_checked)),
                Design.weight());
        View gap = new View(this);
        stats.addView(gap, new LinearLayout.LayoutParams(Design.dp(this, 14), 1));
        stats.addView(summaryStat(String.valueOf(flagged), getString(R.string.summary_flagged)),
                Design.weight());
        card.addView(stats, Design.match());
        card.addView(Design.space(this, 12));
        card.addView(Design.text(this,
                getString(checked == 0 ? R.string.summary_body_idle : R.string.summary_body_active),
                12, Design.ON_INK_MUTED, false));
        return card;
    }

    private View summaryStat(String value, String label) {
        LinearLayout box = Design.column(this);
        box.setPadding(Design.dp(this, 14), Design.dp(this, 12),
                Design.dp(this, 14), Design.dp(this, 12));
        box.setBackground(Design.rounded(Color.argb(28, 255, 255, 255), 14, this));
        box.addView(Design.text(this, value, 28, Design.ON_INK, true));
        box.addView(Design.space(this, 2));
        box.addView(Design.text(this, label, 11, Design.ON_INK_MUTED, false));
        return box;
    }

    private View flaggedActivityCard(FlaggedStore.Item item) {
        LinearLayout card = Design.row(this);
        card.setPadding(Design.dp(this, 15), Design.dp(this, 15),
                Design.dp(this, 15), Design.dp(this, 15));
        card.setBackground(Design.outlined(Design.DANGER_SOFT, Design.SOFT, 19, this));
        TextView warn = Design.text(this, "⚠", 15, Design.DANGER, true);
        warn.setGravity(Gravity.CENTER);
        warn.setBackground(Design.rounded(Design.ON_INK, 15, this));
        card.addView(warn, new LinearLayout.LayoutParams(Design.dp(this, 40), Design.dp(this, 40)));
        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 12), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, getString(item.highRisk
                ? R.string.alert_high_title : R.string.alert_review_title), 14, Design.INK, true));
        copy.addView(Design.text(this,
                (item.source.isEmpty() ? getString(R.string.alert_unknown_source) : item.source)
                        + " · " + DateUtils.getRelativeTimeSpanString(item.timeMillis),
                11, Design.MUTED, false));
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(Design.text(this, "›", 25, Design.MUTED, false));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> renderFlaggedDetail(item.id));
        return card;
    }

    private void renderVoice() {
        renderVoice("");
    }

    private void renderVoice(String initialDescription) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_voice);
        LinearLayout body = pageBody();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 24));
        body.addView(Design.label(this, getString(R.string.voice_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.voice_title), 31, Design.INK, true));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.voice_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 16));
        View disclosure = infoCard(R.string.voice_privacy_title, R.string.voice_privacy_body,
                Design.MINT);
        disclosure.setId(R.id.voice_disclosure);
        body.addView(disclosure, Design.match());
        body.addView(Design.space(this, 12));
        body.addView(infoCard(R.string.preliminary_check, R.string.voice_live_boundary,
                Design.CREAM), Design.match());
        body.addView(Design.space(this, 20));

        voiceInput = new EditText(this);
        voiceInput.setTextSize(Design.scaled(15));
        voiceInput.setTextColor(Design.INK);
        voiceInput.setHintTextColor(Design.MUTED);
        voiceInput.setHint(R.string.voice_hint);
        voiceInput.setGravity(Gravity.TOP | Gravity.START);
        voiceInput.setMinHeight(Design.dp(this, 150));
        voiceInput.setPadding(Design.dp(this, 16), Design.dp(this, 15),
                Design.dp(this, 16), Design.dp(this, 15));
        voiceInput.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        voiceInput.setId(R.id.voice_input);
        if (initialDescription != null && !initialDescription.isEmpty()) {
            voiceInput.setText(initialDescription);
            voiceInput.setSelection(voiceInput.length());
        }
        body.addView(voiceInput, Design.match());
        body.addView(Design.space(this, 10));

        voiceStatus = Design.text(this, getString(R.string.voice_ready), 12,
                Design.MUTED, false);
        body.addView(voiceStatus, Design.match());
        body.addView(Design.space(this, 14));

        TextView speak = Design.button(this, "🎙  " + getString(R.string.start_listening),
                Design.SAFFRON, Design.INK);
        speak.setId(R.id.voice_start);
        speak.setOnClickListener(view -> requestMicrophone(true));
        body.addView(speak, Design.match());
        body.addView(Design.space(this, 10));

        TextView check = Design.button(this, getString(R.string.check_situation),
                Design.INK, Design.ON_INK);
        check.setId(R.id.voice_check);
        check.setOnClickListener(view -> analyzeVoiceDescription());
        body.addView(check, Design.match());

        showScreen(scroll, 0,
                new ScreenState(Screen.VOICE, null,
                        initialDescription == null ? "" : initialDescription));
    }

    private View infoCard(int titleId, int bodyId, int color) {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        card.setBackground(Design.rounded(color, 19, this));
        card.addView(Design.text(this, getString(titleId), 14, Design.INK, true));
        card.addView(Design.space(this, 6));
        card.addView(Design.text(this, getString(bodyId), 12, Design.INK, false));
        return card;
    }

    private void analyzeVoiceDescription() {
        String description = voiceInput == null ? "" : voiceInput.getText().toString().trim();
        if (description.isEmpty()) {
            Toast.makeText(this, R.string.describe_first, Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethod != null && voiceInput != null) {
            inputMethod.hideSoftInputFromWindow(voiceInput.getWindowToken(), 0);
        }
        currentScreen = new ScreenState(Screen.VOICE, null, description);
        renderVoiceResult(description);
    }

    private void renderVoiceResult(String description) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        VoiceRiskAssessment assessment = VoiceRiskAssessment.evaluate(description);
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_voice_result);
        LinearLayout body = pageBody();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.preliminary_check)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this,
                getString(assessment.highRisk
                        ? R.string.voice_high_risk_title : R.string.voice_review_title),
                29, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this,
                getString(assessment.highRisk
                        ? R.string.voice_high_risk_body : R.string.voice_review_body),
                14, Design.MUTED, false));
        body.addView(Design.space(this, 18));

        LinearLayout signals = Design.column(this);
        signals.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        signals.setBackground(Design.rounded(
                assessment.highRisk ? Design.DANGER_SOFT : Design.CREAM,
                19, this));
        signals.addView(Design.text(this,
                getString(R.string.signals_found, assessment.signalCount),
                15, assessment.highRisk ? Design.DANGER : Design.INK, true));
        body.addView(signals, Design.match());
        body.addView(Design.space(this, 14));

        LinearLayout descriptionCard = Design.column(this);
        descriptionCard.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        Design.card(descriptionCard, Design.CARD, 20, this);
        descriptionCard.addView(Design.label(this, getString(R.string.your_description)));
        descriptionCard.addView(Design.space(this, 8));
        descriptionCard.addView(Design.text(this, description, 14, Design.INK, false));
        body.addView(descriptionCard, Design.match());
        body.addView(Design.space(this, 14));

        LinearLayout steps = Design.column(this);
        steps.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        Design.card(steps, Design.CARD, 20, this);
        steps.addView(Design.label(this, getString(R.string.safe_next_steps)));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("1", R.string.safe_step_1));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("2", R.string.safe_step_2));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("3", R.string.safe_step_3));
        body.addView(steps, Design.match());
        body.addView(Design.space(this, 14));

        TextView helpline = Design.button(this, getString(R.string.call_1930),
                Design.INK, Design.ON_INK);
        helpline.setOnClickListener(view -> dialCyberHelpline());
        body.addView(helpline, Design.match());
        body.addView(Design.space(this, 9));
        TextView report = Design.button(this, getString(R.string.report_cybercrime),
                Color.TRANSPARENT, Design.INK);
        report.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        report.setOnClickListener(view -> openCybercrimePortal());
        body.addView(report, Design.match());
        body.addView(Design.space(this, 16));
        body.addView(infoCard(R.string.preliminary_check, R.string.voice_live_boundary,
                Design.CREAM), Design.match());

        showScreen(scroll, 0,
                new ScreenState(Screen.VOICE_RESULT, null, description));
    }

    private View safetyStep(String number, int textId) {
        LinearLayout row = Design.row(this);
        TextView count = Design.text(this, number, 12, Design.INK, true);
        count.setGravity(Gravity.CENTER);
        count.setBackground(Design.rounded(Design.MINT, 13, this));
        row.addView(count, new LinearLayout.LayoutParams(Design.dp(this, 32), Design.dp(this, 32)));
        TextView text = Design.text(this, getString(textId), 13, Design.INK, false);
        text.setPadding(Design.dp(this, 11), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    // ---------------------------------------------------------------------
    // Custom reminders ("Set your own reminder")
    // ---------------------------------------------------------------------

    /** Field labels/hints for each reminder type: name, detail 1, detail 2, date. */
    private static final class ReminderForm {
        final int nameLabel, nameHint, d1Label, d1Hint, d1Short, d2Label, d2Hint, d2Short, dateLabel;
        final boolean d1Money, d2Money;

        ReminderForm(int nameLabel, int nameHint, int d1Label, int d1Hint, int d1Short,
                     boolean d1Money, int d2Label, int d2Hint, int d2Short, boolean d2Money,
                     int dateLabel) {
            this.nameLabel = nameLabel; this.nameHint = nameHint;
            this.d1Label = d1Label; this.d1Hint = d1Hint; this.d1Short = d1Short;
            this.d1Money = d1Money;
            this.d2Label = d2Label; this.d2Hint = d2Hint; this.d2Short = d2Short;
            this.d2Money = d2Money;
            this.dateLabel = dateLabel;
        }

        static ReminderForm of(int kind) {
            return switch (kind) {
                case ReminderStore.KIND_APPOINTMENT -> new ReminderForm(
                        R.string.rem_appt_name, R.string.rem_appt_name_hint,
                        R.string.rem_appt_place, R.string.rem_appt_place_hint,
                        R.string.rem_short_place, false,
                        R.string.rem_appt_fee, R.string.rem_appt_fee_hint,
                        R.string.rem_short_notes, false,
                        R.string.rem_appt_date);
                case ReminderStore.KIND_RETURN -> new ReminderForm(
                        R.string.rem_return_name, R.string.rem_return_name_hint,
                        R.string.rem_return_order, R.string.rem_return_order_hint,
                        R.string.rem_short_order, false,
                        R.string.rem_return_amount, R.string.rem_amount_hint,
                        R.string.rem_short_refund, true,
                        R.string.rem_return_date);
                case ReminderStore.KIND_OTHER -> new ReminderForm(
                        R.string.rem_other_name, R.string.rem_other_name_hint,
                        R.string.rem_other_notes, R.string.rem_other_notes_hint,
                        R.string.rem_short_notes, false,
                        R.string.rem_other_amount, R.string.rem_amount_hint,
                        R.string.rem_short_amount, true,
                        R.string.rem_other_date);
                default -> new ReminderForm(
                        R.string.rem_bill_name, R.string.rem_bill_name_hint,
                        R.string.rem_bill_amount, R.string.rem_amount_hint,
                        R.string.rem_short_amount, true,
                        R.string.rem_bill_payee, R.string.rem_bill_payee_hint,
                        R.string.rem_short_payee, false,
                        R.string.rem_bill_date);
            };
        }
    }

    /** Opens a fresh reminder form (clears any earlier draft). */
    private void startNewReminder(int kind) {
        reminderDraftTitle = "";
        reminderDraftDetail1 = "";
        reminderDraftDetail2 = "";
        reminderWhen.setTimeInMillis(System.currentTimeMillis());
        reminderWhen.add(Calendar.DAY_OF_MONTH, 1);
        reminderWhen.set(Calendar.HOUR_OF_DAY, 10);
        reminderWhen.set(Calendar.MINUTE, 0);
        reminderWhen.set(Calendar.SECOND, 0);
        reminderWhen.set(Calendar.MILLISECOND, 0);
        renderReminder(kind);
    }

    private void captureReminderDraft() {
        if (reminderTitleInput != null) reminderDraftTitle = reminderTitleInput.getText().toString();
        if (reminderDetail1Input != null) {
            reminderDraftDetail1 = reminderDetail1Input.getText().toString();
        }
        if (reminderDetail2Input != null) {
            reminderDraftDetail2 = reminderDetail2Input.getText().toString();
        }
    }

    private void renderReminder(int kind) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ReminderForm form = ReminderForm.of(kind);
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_reminder);
        LinearLayout body = pageBody();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.reminder_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.reminder_title_screen), 30,
                Design.INK, true));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.reminder_body), 14,
                Design.MUTED, false));
        body.addView(Design.space(this, 18));

        // Type picker: changing it swaps the questions below.
        body.addView(Design.label(this, getString(R.string.reminder_type_label)));
        body.addView(Design.space(this, 8));
        LinearLayout typeRow1 = Design.row(this);
        typeRow1.addView(reminderTypeChip(kind, ReminderStore.KIND_BILL,
                R.string.reminder_type_bill, R.id.reminder_type_bill), Design.weight());
        View g1 = new View(this);
        typeRow1.addView(g1, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        typeRow1.addView(reminderTypeChip(kind, ReminderStore.KIND_APPOINTMENT,
                R.string.reminder_type_appointment, R.id.reminder_type_appointment),
                Design.weight());
        body.addView(typeRow1, Design.match());
        body.addView(Design.space(this, 8));
        LinearLayout typeRow2 = Design.row(this);
        typeRow2.addView(reminderTypeChip(kind, ReminderStore.KIND_RETURN,
                R.string.reminder_type_return, R.id.reminder_type_return), Design.weight());
        View g2 = new View(this);
        typeRow2.addView(g2, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        typeRow2.addView(reminderTypeChip(kind, ReminderStore.KIND_OTHER,
                R.string.reminder_type_other, R.id.reminder_type_other), Design.weight());
        body.addView(typeRow2, Design.match());
        body.addView(Design.space(this, 20));

        reminderTitleInput = reminderField(body, form.nameLabel, form.nameHint,
                reminderDraftTitle, false, R.id.reminder_title_input);
        reminderDetail1Input = reminderField(body, form.d1Label, form.d1Hint,
                reminderDraftDetail1, form.d1Money, R.id.reminder_detail1_input);
        reminderDetail2Input = reminderField(body, form.d2Label, form.d2Hint,
                reminderDraftDetail2, form.d2Money, R.id.reminder_detail2_input);

        body.addView(Design.text(this, getString(form.dateLabel), 13, Design.INK, true));
        body.addView(Design.space(this, 6));
        LinearLayout whenRow = Design.row(this);
        TextView date = Design.chip(this, "📅  " + formatReminderDate(reminderWhen), true);
        date.setId(R.id.reminder_date);
        date.setOnClickListener(view -> pickReminderDate(kind));
        whenRow.addView(date, Design.weight());
        View g3 = new View(this);
        whenRow.addView(g3, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        TextView time = Design.chip(this, "⏰  " + formatReminderTime(reminderWhen), true);
        time.setId(R.id.reminder_time);
        time.setOnClickListener(view -> pickReminderTime(kind));
        whenRow.addView(time, Design.weight());
        body.addView(whenRow, Design.match());
        body.addView(Design.space(this, 22));

        TextView save = Design.button(this, getString(R.string.reminder_save),
                Design.SAFFRON, Design.INK);
        save.setId(R.id.reminder_save);
        save.setOnClickListener(view -> saveReminder(kind));
        body.addView(save, Design.match());
        body.addView(Design.space(this, 14));
        body.addView(Design.text(this, getString(R.string.reminder_note), 11,
                Design.MUTED, false));

        showScreen(scroll, 0, new ScreenState(Screen.REMINDER, null, null, kind));
    }

    private TextView reminderTypeChip(int selected, int kind, int labelRes, int id) {
        TextView chip = Design.chip(this, getString(labelRes), selected == kind);
        chip.setId(id);
        chip.setOnClickListener(view -> {
            if (selected == kind) return;
            captureReminderDraft();
            restoringPreviousScreen = true; // replace the form, don't stack it
            renderReminder(kind);
            restoringPreviousScreen = false;
        });
        return chip;
    }

    private EditText reminderField(LinearLayout body, int labelRes, int hintRes, String value,
                                   boolean money, int id) {
        body.addView(Design.text(this, getString(labelRes), 13, Design.INK, true));
        body.addView(Design.space(this, 6));
        EditText field = new EditText(this);
        field.setId(id);
        field.setTextSize(Design.scaled(15));
        field.setTextColor(Design.INK);
        field.setHintTextColor(Design.MUTED);
        field.setHint(hintRes);
        field.setSingleLine(true);
        if (money) {
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        } else {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        field.setPadding(Design.dp(this, 16), Design.dp(this, 13),
                Design.dp(this, 16), Design.dp(this, 13));
        field.setBackground(Design.outlined(Design.CARD, Design.SOFT, 16, this));
        if (value != null && !value.isEmpty()) field.setText(value);
        body.addView(field, Design.match());
        body.addView(Design.space(this, 14));
        return field;
    }

    private void pickReminderDate(int kind) {
        captureReminderDraft();
        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(this,
                (picker, year, month, day) -> {
                    reminderWhen.set(Calendar.YEAR, year);
                    reminderWhen.set(Calendar.MONTH, month);
                    reminderWhen.set(Calendar.DAY_OF_MONTH, day);
                    rerenderReminder(kind);
                },
                reminderWhen.get(Calendar.YEAR),
                reminderWhen.get(Calendar.MONTH),
                reminderWhen.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        dialog.show();
    }

    private void pickReminderTime(int kind) {
        captureReminderDraft();
        new android.app.TimePickerDialog(this,
                (picker, hour, minute) -> {
                    reminderWhen.set(Calendar.HOUR_OF_DAY, hour);
                    reminderWhen.set(Calendar.MINUTE, minute);
                    reminderWhen.set(Calendar.SECOND, 0);
                    rerenderReminder(kind);
                },
                reminderWhen.get(Calendar.HOUR_OF_DAY),
                reminderWhen.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void rerenderReminder(int kind) {
        if (currentScreen == null || currentScreen.screen != Screen.REMINDER) return;
        restoringPreviousScreen = true;
        renderReminder(kind);
        restoringPreviousScreen = false;
    }

    private String formatReminderDate(Calendar when) {
        return DateUtils.formatDateTime(this, when.getTimeInMillis(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                        | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY);
    }

    private String formatReminderTime(Calendar when) {
        return DateUtils.formatDateTime(this, when.getTimeInMillis(), DateUtils.FORMAT_SHOW_TIME);
    }

    private String formatReminderWhen(long millis) {
        return DateUtils.formatDateTime(this, millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                        | DateUtils.FORMAT_SHOW_TIME);
    }

    /** "Amount: ₹1,240" — prefixes rupee for money fields. */
    private String reminderDetail(int shortLabel, String raw, boolean money) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "";
        if (money && !value.startsWith("₹")) value = "₹" + value;
        return getString(R.string.reminder_detail_format, getString(shortLabel), value);
    }

    private void saveReminder(int kind) {
        captureReminderDraft();
        ReminderForm form = ReminderForm.of(kind);
        String title = reminderDraftTitle.trim();
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.reminder_name_required, Toast.LENGTH_SHORT).show();
            if (reminderTitleInput != null) reminderTitleInput.requestFocus();
            return;
        }
        long when = reminderWhen.getTimeInMillis();
        if (when <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.reminder_time_past, Toast.LENGTH_LONG).show();
            return;
        }
        String d1 = reminderDetail(form.d1Short, reminderDraftDetail1, form.d1Money);
        String d2 = reminderDetail(form.d2Short, reminderDraftDetail2, form.d2Money);
        ReminderStore.Item item = ReminderStore.add(this, kind, title, d1, d2, when);
        ReminderReceiver.schedule(this, item);

        InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethod != null && reminderTitleInput != null) {
            inputMethod.hideSoftInputFromWindow(reminderTitleInput.getWindowToken(), 0);
        }
        Toast.makeText(this, getString(R.string.reminder_saved, formatReminderWhen(when)),
                Toast.LENGTH_LONG).show();

        // Notifications are how the reminder reaches the user.
        if (!NotificationHelper.areEnabled(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_REMINDER_NOTIFICATIONS);
            } else {
                Toast.makeText(this, R.string.reminder_notifications_off,
                        Toast.LENGTH_LONG).show();
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.reminder_add_calendar_title)
                .setMessage(R.string.reminder_add_calendar_body)
                .setNegativeButton(R.string.reminder_add_calendar_no, null)
                .setPositiveButton(R.string.reminder_add_calendar_yes,
                        (dialog, which) -> addReminderToCalendar(item))
                .show();
        // Show it in the Activity list, replacing the form in the history.
        restoringPreviousScreen = true;
        renderActivity();
        restoringPreviousScreen = false;
    }

    private void addReminderToCalendar(ReminderStore.Item item) {
        StringBuilder description = new StringBuilder();
        if (!item.detail1.isEmpty()) description.append(item.detail1);
        if (!item.detail2.isEmpty()) {
            if (description.length() > 0) description.append('\n');
            description.append(item.detail2);
        }
        Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, item.title)
                .putExtra(CalendarContract.Events.DESCRIPTION, description.toString())
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.timeMillis)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.timeMillis + 30 * 60 * 1000L);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.calendar_chooser)));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.image_picker_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private View reminderActivityCard(ReminderStore.Item item) {
        LinearLayout card = Design.row(this);
        card.setPadding(Design.dp(this, 15), Design.dp(this, 15),
                Design.dp(this, 15), Design.dp(this, 15));
        Design.card(card, Design.CARD, 19, this);
        String icon = switch (item.kind) {
            case ReminderStore.KIND_BILL -> "₹";
            case ReminderStore.KIND_APPOINTMENT -> "🗓";
            case ReminderStore.KIND_RETURN -> "↩";
            default -> "⏰";
        };
        TextView badge = Design.text(this, icon, 15, Design.INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Design.rounded(Design.CREAM, 15, this));
        card.addView(badge, new LinearLayout.LayoutParams(Design.dp(this, 40), Design.dp(this, 40)));
        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 12), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, item.title, 14, Design.INK, true));
        copy.addView(Design.text(this, formatReminderWhen(item.timeMillis), 11,
                Design.MUTED, false));
        if (!item.detail1.isEmpty()) {
            copy.addView(Design.text(this, item.detail1, 11, Design.MUTED, false));
        }
        if (!item.detail2.isEmpty()) {
            copy.addView(Design.text(this, item.detail2, 11, Design.MUTED, false));
        }
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(Design.text(this, "›", 25, Design.MUTED, false));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage(R.string.reminder_manage_body)
                .setNeutralButton(R.string.reminder_delete, (dialog, which) -> {
                    ReminderReceiver.cancel(this, item.id);
                    ReminderStore.remove(this, item.id);
                    Toast.makeText(this, R.string.reminder_deleted, Toast.LENGTH_SHORT).show();
                    restoringPreviousScreen = true;
                    renderActivity();
                    restoringPreviousScreen = false;
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reminder_add_calendar_yes,
                        (dialog, which) -> addReminderToCalendar(item))
                .show());
        return card;
    }

    // ---------------------------------------------------------------------
    // Safety pack: panic flow, number/link checker, scam-of-week tip
    // ---------------------------------------------------------------------

    private void renderPanic() {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_panic);
        LinearLayout body = pageBody();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 20));
        body.addView(Design.label(this, getString(R.string.panic_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.panic_title), 29, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(R.string.panic_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 18));

        LinearLayout steps = Design.column(this);
        steps.setPadding(Design.dp(this, 18), Design.dp(this, 17),
                Design.dp(this, 18), Design.dp(this, 17));
        steps.setBackground(Design.rounded(Design.DANGER_SOFT, 20, this));
        steps.addView(Design.label(this, getString(R.string.panic_steps_label)));
        steps.addView(Design.space(this, 12));
        steps.addView(safetyStep("1", R.string.panic_step_1));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("2", R.string.panic_step_2));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("3", R.string.panic_step_3));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("4", R.string.panic_step_4));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("5", R.string.panic_step_5));
        body.addView(steps, Design.match());
        body.addView(Design.space(this, 16));

        TextView helpline = Design.button(this, getString(R.string.call_1930),
                Design.INK, Design.ON_INK);
        helpline.setOnClickListener(view -> dialCyberHelpline());
        body.addView(helpline, Design.match());
        body.addView(Design.space(this, 9));
        TextView portal = Design.button(this, getString(R.string.report_cybercrime),
                Color.TRANSPARENT, Design.INK);
        portal.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        portal.setOnClickListener(view -> openCybercrimePortal());
        body.addView(portal, Design.match());
        body.addView(Design.space(this, 9));
        TextView ask = trustedAlertButton(getString(R.string.panic_share_text),
                Color.TRANSPARENT, Design.INK);
        ask.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        body.addView(ask, Design.match());
        body.addView(Design.space(this, 16));
        body.addView(buildOfficialIndiaCard(), Design.match());

        showScreen(scroll, 0, ScreenState.of(Screen.PANIC));
    }

    private void renderChecker(String initial) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_checker);
        LinearLayout body = pageBody();
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.checker_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.checker_title), 30, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(R.string.checker_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 18));

        checkerInput = new EditText(this);
        checkerInput.setTextSize(Design.scaled(15));
        checkerInput.setTextColor(Design.INK);
        checkerInput.setHintTextColor(Design.MUTED);
        checkerInput.setHint(R.string.checker_hint);
        checkerInput.setSingleLine(true);
        checkerInput.setPadding(Design.dp(this, 16), Design.dp(this, 15),
                Design.dp(this, 16), Design.dp(this, 15));
        checkerInput.setBackground(Design.outlined(Design.CARD, Design.SOFT, 18, this));
        checkerInput.setId(R.id.checker_input);
        if (initial != null && !initial.isEmpty()) {
            checkerInput.setText(initial);
            checkerInput.setSelection(checkerInput.length());
        }
        body.addView(checkerInput, Design.match());
        body.addView(Design.space(this, 12));

        TextView check = Design.button(this, getString(R.string.checker_check),
                Design.SAFFRON, Design.INK);
        check.setId(R.id.checker_check);
        check.setOnClickListener(view -> analyzeLinkNumber());
        body.addView(check, Design.match());
        body.addView(Design.space(this, 14));
        body.addView(infoCard(R.string.checker_disclosure_title, R.string.checker_disclosure_body,
                Design.CREAM), Design.match());

        showScreen(scroll, 0,
                new ScreenState(Screen.CHECKER, null, initial == null ? "" : initial));
    }

    private void analyzeLinkNumber() {
        String input = checkerInput == null ? "" : checkerInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, R.string.checker_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager inputMethod = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethod != null && checkerInput != null) {
            inputMethod.hideSoftInputFromWindow(checkerInput.getWindowToken(), 0);
        }
        currentScreen = new ScreenState(Screen.CHECKER, null, input);
        renderCheckerResult(input);
    }

    private void renderCheckerResult(String input) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        LinkNumberCheck result = LinkNumberCheck.evaluate(input);
        int reports = ScamDatabase.reports(this, input);
        boolean high = result.highRisk || reports > 0;
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_checker_result);
        LinearLayout body = pageBody();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.checker_result_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(high
                ? R.string.checker_high_title : R.string.checker_low_title), 28, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(high
                ? R.string.checker_high_body : R.string.checker_low_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 16));

        if (reports > 0) {
            LinearLayout rep = Design.column(this);
            rep.setPadding(Design.dp(this, 18), Design.dp(this, 15),
                    Design.dp(this, 18), Design.dp(this, 15));
            rep.setBackground(Design.rounded(Design.DANGER_SOFT, 19, this));
            rep.addView(Design.text(this, getString(R.string.reputation_reported, reports),
                    15, Design.DANGER, true));
            rep.addView(Design.space(this, 4));
            rep.addView(Design.text(this, getString(R.string.reputation_body), 12,
                    Design.INK, false));
            body.addView(rep, Design.match());
            body.addView(Design.space(this, 12));
        }

        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 16),
                Design.dp(this, 18), Design.dp(this, 16));
        card.setBackground(Design.rounded(high ? Design.DANGER_SOFT : Design.CREAM, 19, this));
        card.addView(Design.text(this,
                getString(R.string.checker_signals_found, result.score),
                15, high ? Design.DANGER : Design.INK, true));
        if (!result.reasons.isEmpty()) {
            card.addView(Design.space(this, 10));
            for (String reason : result.reasons) {
                card.addView(checkerReasonRow(reason));
                card.addView(Design.space(this, 6));
            }
        }
        body.addView(card, Design.match());
        body.addView(Design.space(this, 12));

        LinearLayout echo = Design.column(this);
        echo.setPadding(Design.dp(this, 18), Design.dp(this, 16),
                Design.dp(this, 18), Design.dp(this, 16));
        Design.card(echo, Design.CARD, 20, this);
        echo.addView(Design.label(this, getString(R.string.checker_you_entered)));
        echo.addView(Design.space(this, 8));
        echo.addView(Design.text(this, input, 14, Design.INK, false));
        body.addView(echo, Design.match());
        body.addView(Design.space(this, 14));

        LinearLayout steps = Design.column(this);
        steps.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        Design.card(steps, Design.CARD, 20, this);
        steps.addView(Design.label(this, getString(R.string.safe_next_steps)));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("1", R.string.safe_step_1));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("2", R.string.safe_step_2));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("3", R.string.safe_step_3));
        body.addView(steps, Design.match());
        body.addView(Design.space(this, 14));

        body.addView(trustedAlertButton(
                getString(R.string.checker_share_prefix) + " " + input,
                Design.INK, Design.ON_INK), Design.match());
        body.addView(Design.space(this, 9));
        TextView reportIt = Design.button(this, getString(R.string.report_scam_button),
                Color.TRANSPARENT, Design.DANGER);
        reportIt.setBackground(Design.outlined(Color.TRANSPARENT, Design.DANGER, 17, this));
        reportIt.setOnClickListener(view -> {
            ScamDatabase.report(this, input);
            Toast.makeText(this, R.string.report_scam_toast, Toast.LENGTH_SHORT).show();
            renderCheckerResult(input);
        });
        body.addView(reportIt, Design.match());
        body.addView(Design.space(this, 9));
        TextView helpline = Design.button(this, getString(R.string.call_1930),
                Color.TRANSPARENT, Design.INK);
        helpline.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        helpline.setOnClickListener(view -> dialCyberHelpline());
        body.addView(helpline, Design.match());
        body.addView(Design.space(this, 16));
        body.addView(infoCard(R.string.checker_disclosure_title, R.string.checker_disclosure_body,
                Design.CREAM), Design.match());

        showScreen(scroll, 0, new ScreenState(Screen.CHECKER_RESULT, null, input));
    }

    private View checkerReasonRow(String reason) {
        LinearLayout row = Design.row(this);
        TextView dot = Design.text(this, "•", 15, Design.DANGER, true);
        dot.setGravity(Gravity.CENTER);
        row.addView(dot, new LinearLayout.LayoutParams(Design.dp(this, 18), Design.dp(this, 22)));
        TextView text = Design.text(this, checkerReasonText(reason), 13, Design.INK, false);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private String checkerReasonText(String reason) {
        int id = switch (reason) {
            case "no_https" -> R.string.reason_no_https;
            case "shortener" -> R.string.reason_shortener;
            case "risky_tld" -> R.string.reason_risky_tld;
            case "lookalike_brand" -> R.string.reason_lookalike_brand;
            case "raw_ip" -> R.string.reason_raw_ip;
            case "at_symbol" -> R.string.reason_at_symbol;
            case "long_url" -> R.string.reason_long_url;
            case "too_long" -> R.string.reason_too_long;
            case "too_short" -> R.string.reason_too_short;
            case "foreign_number" -> R.string.reason_foreign_number;
            case "unusual_prefix" -> R.string.reason_unusual_prefix;
            case "scam_phrases" -> R.string.reason_scam_phrases;
            default -> R.string.reason_generic;
        };
        return getString(id);
    }

    private void renderTip(int index) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScamTips.Tip tip = ScamTips.at(index);
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_tip);
        LinearLayout body = pageBody();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.tip_of_week_label)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(tip.titleRes), 28, Design.INK, true));
        body.addView(Design.space(this, 12));

        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 18),
                Design.dp(this, 18), Design.dp(this, 18));
        Design.card(card, Design.CARD, 20, this);
        card.addView(Design.text(this, getString(tip.bodyRes), 15, Design.INK, false));
        body.addView(card, Design.match());
        body.addView(Design.space(this, 14));

        TextView another = Design.chip(this, getString(R.string.tip_show_another), true);
        another.setOnClickListener(view -> renderTip(index + 1));
        body.addView(another, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 16));
        body.addView(infoCard(R.string.tip_footer_title, R.string.tip_footer_body,
                Design.MINT), Design.match());

        showScreen(scroll, 0, new ScreenState(Screen.TIP, null, null, index));
    }

    private void shareToTrustedPerson(String text) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.ask_trusted_chooser)));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.open_link_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Alerts the saved trusted contact. If one is set, opens the SMS app pre-filled
     * (the user taps Send — no SMS permission needed); otherwise falls back to the
     * system share sheet.
     */
    private void alertTrustedContact(String message) {
        if (!TrustedContact.isSet(this)) {
            shareToTrustedPerson(message);
            return;
        }
        String number = TrustedContact.getNumber(this);
        Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number))
                .putExtra("sms_body", message);
        try {
            startActivity(sms);
        } catch (RuntimeException error) {
            shareToTrustedPerson(message);
        }
    }

    /** A button that adapts to whether a trusted contact is saved. */
    private TextView trustedAlertButton(String message, int background, int foreground) {
        String label = TrustedContact.isSet(this)
                ? getString(R.string.alert_trusted_named, TrustedContact.getName(this))
                : getString(R.string.ask_trusted_button);
        TextView button = Design.button(this, "👥  " + label, background, foreground);
        button.setOnClickListener(view -> alertTrustedContact(message));
        return button;
    }

    // ---------------------------------------------------------------------
    // Trial / premium (paywall)
    // ---------------------------------------------------------------------

    private void renderPaywall() {
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_paywall);
        LinearLayout body = pageBody();
        scroll.addView(body);

        body.addView(brandHeader());
        body.addView(Design.space(this, 28));

        boolean premium = PlanState.isPremium(this);
        body.addView(Design.label(this, getString(premium
                ? R.string.paywall_premium_eyebrow : R.string.paywall_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(premium
                ? R.string.paywall_premium_title : R.string.paywall_title), 30, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(premium
                ? R.string.paywall_premium_body : R.string.paywall_body), 15, Design.MUTED, false));
        body.addView(Design.space(this, 22));

        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 20), Design.dp(this, 20),
                Design.dp(this, 20), Design.dp(this, 20));
        Design.card(card, Design.INK, 24, this);
        card.addView(Design.text(this, getString(R.string.paywall_plan_name), 20, Design.ON_INK, true));
        card.addView(Design.space(this, 4));
        card.addView(Design.text(this, getString(R.string.paywall_plan_price), 13,
                Design.ON_INK_MUTED, false));
        card.addView(Design.space(this, 16));
        card.addView(paywallBenefit(R.string.paywall_benefit_1));
        card.addView(Design.space(this, 9));
        card.addView(paywallBenefit(R.string.paywall_benefit_2));
        card.addView(Design.space(this, 9));
        card.addView(paywallBenefit(R.string.paywall_benefit_3));
        card.addView(Design.space(this, 9));
        card.addView(paywallBenefit(R.string.paywall_benefit_4));
        body.addView(card, Design.match());
        body.addView(Design.space(this, 16));

        if (!premium) {
            TextView buy = Design.button(this, getString(R.string.premium_price_onetime),
                    Design.SAFFRON, Design.INK);
            buy.setId(R.id.plan_upgrade);
            buy.setOnClickListener(view -> startPurchase(BillingManager.PREMIUM));
            body.addView(buy, Design.match());
            body.addView(Design.space(this, 10));
            body.addView(Design.text(this, getString(R.string.paywall_billing_note), 11,
                    Design.MUTED, false));
        } else {
            TextView continueButton = Design.button(this, getString(R.string.paywall_continue),
                    Design.INK, Design.ON_INK);
            continueButton.setOnClickListener(view -> {
                screenHistory.clear();
                currentScreen = null;
                renderHome();
            });
            body.addView(continueButton, Design.match());
        }

        // Demo controls unlock Premium for free, so they exist only in test (debug)
        // builds. The Play Store release build never shows them.
        if (isDebugBuild()) {
            body.addView(Design.space(this, 18));
            body.addView(buildDemoControls(), Design.match());
        }

        showScreen(scroll, 0, ScreenState.of(Screen.PAYWALL));
    }

    private View paywallBenefit(int textId) {
        LinearLayout row = Design.row(this);
        TextView tick = Design.text(this, "✓", 13, Design.INK, true);
        tick.setGravity(Gravity.CENTER);
        tick.setBackground(Design.rounded(Design.SAFFRON, 12, this));
        row.addView(tick, new LinearLayout.LayoutParams(Design.dp(this, 26), Design.dp(this, 26)));
        TextView text = Design.text(this, getString(textId), 14, Design.ON_INK, false);
        text.setPadding(Design.dp(this, 11), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void simulateUpgrade() {
        PlanState.setPremium(this, true);
        Toast.makeText(this, R.string.upgraded_toast, Toast.LENGTH_SHORT).show();
        screenHistory.clear();
        currentScreen = null;
        renderHome();
    }

    /** Prototype-only controls so both trial and locked states can be exercised. */
    private View buildDemoControls() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        card.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        card.addView(Design.label(this, getString(R.string.demo_controls_label)));
        card.addView(Design.space(this, 6));
        card.addView(Design.text(this, getString(R.string.demo_controls_body), 12,
                Design.MUTED, false));
        card.addView(Design.space(this, 12));

        LinearLayout row = Design.row(this);
        boolean premium = PlanState.isPremium(this);
        TextView premiumToggle = Design.chip(this, getString(premium
                ? R.string.demo_premium_off : R.string.demo_premium_on), premium);
        premiumToggle.setId(R.id.plan_demo_premium);
        premiumToggle.setOnClickListener(view -> {
            PlanState.setPremium(this, !premium);
            screenHistory.clear();
            currentScreen = null;
            if (PlanState.isLocked(this)) renderPaywall(); else renderHome();
        });
        row.addView(premiumToggle, Design.weight());
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Design.dp(this, 10), 1));
        TextView resetTrial = Design.chip(this, getString(R.string.demo_reset_trial), false);
        resetTrial.setOnClickListener(view -> {
            PlanState.setPremium(this, false);
            PlanState.resetTrial(this);
            Toast.makeText(this, R.string.trial_reset_toast, Toast.LENGTH_SHORT).show();
            screenHistory.clear();
            currentScreen = null;
            renderHome();
        });
        row.addView(resetTrial, Design.weight());
        card.addView(row, Design.match());
        card.addView(Design.space(this, 10));
        TextView expire = Design.chip(this, getString(R.string.demo_expire_trial), false);
        expire.setOnClickListener(view -> {
            PlanState.setPremium(this, false);
            PlanState.expireTrial(this);
            Toast.makeText(this, R.string.trial_expired_toast, Toast.LENGTH_SHORT).show();
            screenHistory.clear();
            currentScreen = null;
            renderPaywall();
        });
        card.addView(expire, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildTrialBanner() {
        LinearLayout strip = Design.row(this);
        strip.setPadding(Design.dp(this, 15), Design.dp(this, 12),
                Design.dp(this, 15), Design.dp(this, 12));
        boolean premium = PlanState.isPremium(this);
        strip.setBackground(Design.rounded(
                premium ? Design.MINT : Design.CREAM, 16, this));
        LinearLayout copy = Design.column(this);
        if (premium) {
            copy.addView(Design.text(this, getString(R.string.premium_badge), 13, Design.INK, true));
        } else {
            int days = PlanState.daysLeft(this);
            copy.addView(Design.text(this,
                    getString(R.string.trial_banner_text, days), 13, Design.INK, true));
        }
        strip.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!premium) {
            TextView upgrade = Design.chip(this, getString(R.string.trial_banner_upgrade), true);
            upgrade.setOnClickListener(view -> renderPaywall());
            strip.addView(upgrade);
        }
        return strip;
    }

    /** Adds the banner ad to the bottom of a tab, only for non-premium users. */
    private void addAdIfFree(LinearLayout body) {
        if (PlanState.isPremium(this)) return;
        body.addView(Design.space(this, 14));
        View ad = buildAdBanner();
        ad.setId(R.id.ad_banner);
        body.addView(ad, Design.match());
    }

    private View buildAdBanner() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 12), Design.dp(this, 12),
                Design.dp(this, 12), Design.dp(this, 12));
        card.setBackground(Design.outlined(Design.AD_BG, Design.SOFT, 16, this));
        card.setGravity(Gravity.CENTER);
        TextView tag = Design.text(this, getString(R.string.ad_label), 10, Design.MUTED, true);
        tag.setLetterSpacing(0.12f);
        tag.setGravity(Gravity.CENTER);
        card.addView(tag, Design.match());
        card.addView(Design.space(this, 6));
        try {
            if (adView != null) adView.destroy();
            adView = new AdView(this);
            adView.setAdUnitId(TEST_BANNER_UNIT);
            adView.setAdSize(AdSize.BANNER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER;
            card.addView(adView, params);
            adView.loadAd(new AdRequest.Builder().build());
        } catch (Throwable adFailure) {
            card.addView(Design.text(this, getString(R.string.ad_placeholder_body), 12,
                    Design.MUTED, false));
        }
        return card;
    }

    // ---------------------------------------------------------------------
    // Theme & accessibility
    // ---------------------------------------------------------------------

    private void applyTheme() {
        Design.apply(isDark(), isHighContrast(), textScale());
    }

    private boolean isDark() {
        return HistoryStore.prefs(this).getBoolean(KEY_DARK, false);
    }

    private boolean isHighContrast() {
        return HistoryStore.prefs(this).getBoolean(KEY_HIGH_CONTRAST, false);
    }

    private float textScale() {
        String scale = HistoryStore.prefs(this).getString(KEY_TEXT_SCALE, "normal");
        return switch (scale == null ? "normal" : scale) {
            case "large" -> 1.15f;
            case "xlarge" -> 1.3f;
            default -> 1f;
        };
    }

    private void setDark(boolean dark) {
        HistoryStore.prefs(this).edit().putBoolean(KEY_DARK, dark).apply();
        recreate();
    }

    private void setHighContrast(boolean on) {
        HistoryStore.prefs(this).edit().putBoolean(KEY_HIGH_CONTRAST, on).apply();
        recreate();
    }

    private void setTextScale(String scale) {
        HistoryStore.prefs(this).edit().putString(KEY_TEXT_SCALE, scale).apply();
        recreate();
    }

    private View settingsDisplayCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.display_label)));
        card.addView(Design.space(this, 12));

        card.addView(Design.text(this, getString(R.string.theme_label), 12, Design.MUTED, false));
        card.addView(Design.space(this, 8));
        LinearLayout themeRow = Design.row(this);
        boolean dark = isDark();
        TextView light = Design.chip(this, getString(R.string.theme_light), !dark);
        light.setOnClickListener(view -> { if (isDark()) setDark(false); });
        TextView darkChip = Design.chip(this, getString(R.string.theme_dark), dark);
        darkChip.setOnClickListener(view -> { if (!isDark()) setDark(true); });
        themeRow.addView(light, Design.weight());
        View gap1 = new View(this);
        themeRow.addView(gap1, new LinearLayout.LayoutParams(Design.dp(this, 10), 1));
        themeRow.addView(darkChip, Design.weight());
        card.addView(themeRow, Design.match());
        card.addView(Design.space(this, 14));

        card.addView(Design.text(this, getString(R.string.text_size_label), 12, Design.MUTED, false));
        card.addView(Design.space(this, 8));
        LinearLayout sizeRow = Design.row(this);
        String scale = HistoryStore.prefs(this).getString(KEY_TEXT_SCALE, "normal");
        String current = scale == null ? "normal" : scale;
        TextView normal = Design.chip(this, getString(R.string.text_size_normal),
                current.equals("normal"));
        normal.setOnClickListener(view -> setTextScale("normal"));
        TextView large = Design.chip(this, getString(R.string.text_size_large),
                current.equals("large"));
        large.setOnClickListener(view -> setTextScale("large"));
        TextView xlarge = Design.chip(this, getString(R.string.text_size_xlarge),
                current.equals("xlarge"));
        xlarge.setOnClickListener(view -> setTextScale("xlarge"));
        sizeRow.addView(normal, Design.weight());
        View gap2 = new View(this);
        sizeRow.addView(gap2, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        sizeRow.addView(large, Design.weight());
        View gap3 = new View(this);
        sizeRow.addView(gap3, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        sizeRow.addView(xlarge, Design.weight());
        card.addView(sizeRow, Design.match());
        card.addView(Design.space(this, 14));

        LinearLayout contrastRow = Design.row(this);
        LinearLayout contrastCopy = Design.column(this);
        contrastCopy.addView(Design.text(this, getString(R.string.high_contrast_title), 13,
                Design.INK, true));
        contrastCopy.addView(Design.text(this, getString(R.string.high_contrast_reason), 11,
                Design.MUTED, false));
        contrastRow.addView(contrastCopy,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean hc = isHighContrast();
        TextView contrastToggle = Design.chip(this,
                getString(hc ? R.string.on_status : R.string.off_status), hc);
        contrastToggle.setOnClickListener(view -> setHighContrast(!hc));
        contrastRow.addView(contrastToggle);
        card.addView(contrastRow, Design.match());
        return card;
    }

    // ---------------------------------------------------------------------
    // Onboarding walkthrough (first launch)
    // ---------------------------------------------------------------------

    private static final int ONBOARDING_PAGES = 3;

    private void renderOnboarding(int page) {
        int clamped = Math.max(0, Math.min(page, ONBOARDING_PAGES - 1));
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_onboarding);
        LinearLayout body = pageBody();
        scroll.addView(body);

        body.addView(Design.space(this, 20));
        TextView mark = Design.text(this, "N↗", 26, Design.INK, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Design.rounded(Design.SAFFRON, 20, this));
        body.addView(mark, new LinearLayout.LayoutParams(Design.dp(this, 64), Design.dp(this, 64)));
        body.addView(Design.space(this, 26));

        int titleRes;
        int bodyRes;
        switch (clamped) {
            case 1 -> { titleRes = R.string.onboard2_title; bodyRes = R.string.onboard2_body; }
            case 2 -> { titleRes = R.string.onboard3_title; bodyRes = R.string.onboard3_body; }
            default -> { titleRes = R.string.onboard1_title; bodyRes = R.string.onboard1_body; }
        }
        body.addView(Design.label(this,
                getString(R.string.onboard_step, clamped + 1, ONBOARDING_PAGES)));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(titleRes), 30, Design.INK, true));
        body.addView(Design.space(this, 12));
        body.addView(Design.text(this, getString(bodyRes), 16, Design.MUTED, false));
        body.addView(Design.space(this, 30));

        boolean last = clamped == ONBOARDING_PAGES - 1;
        TextView primary = Design.button(this,
                getString(last ? R.string.onboard_get_started : R.string.onboard_next),
                Design.SAFFRON, Design.INK);
        primary.setId(R.id.onboarding_next);
        primary.setOnClickListener(view -> {
            if (last) finishOnboarding();
            else renderOnboarding(clamped + 1);
        });
        body.addView(primary, Design.match());
        body.addView(Design.space(this, 10));
        TextView skip = Design.button(this, getString(R.string.onboard_skip),
                Color.TRANSPARENT, Design.INK);
        skip.setId(R.id.onboarding_skip);
        skip.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        skip.setOnClickListener(view -> finishOnboarding());
        body.addView(skip, Design.match());

        showScreen(scroll, 0, new ScreenState(Screen.ONBOARDING, null, null, clamped));
    }

    private void finishOnboarding() {
        markOnboardingComplete();
        screenHistory.clear();
        currentScreen = null;
        if (PlanState.isLocked(this)) {
            renderPaywall();
        } else {
            renderHome();
            requestPendingPermissions();
        }
    }

    private boolean isOnboardingComplete() {
        return HistoryStore.prefs(this).getBoolean(KEY_ONBOARDING_DONE, false);
    }

    private void markOnboardingComplete() {
        HistoryStore.prefs(this).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();
    }

    // ---------------------------------------------------------------------
    // First-run setup flow
    // ---------------------------------------------------------------------

    private void renderSetup() {
        boolean notificationsReady = NotificationHelper.areEnabled(this);
        boolean micReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean scanReady = MessageScanService.isListenerEnabled(this);
        boolean allReady = notificationsReady && micReady && scanReady;

        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_setup);
        LinearLayout body = pageBody();
        scroll.addView(body);

        body.addView(brandHeader());
        body.addView(Design.space(this, 26));
        body.addView(Design.label(this, getString(R.string.setup_eyebrow)));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.setup_title), 30, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(R.string.setup_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 22));

        LinearLayout steps = Design.column(this);
        steps.setPadding(Design.dp(this, 18), Design.dp(this, 8),
                Design.dp(this, 18), Design.dp(this, 8));
        Design.card(steps, Design.CARD, 22, this);
        steps.addView(setupRow(R.id.setup_row_notifications, "🔔",
                R.string.setup_notifications_title, R.string.setup_notifications_body,
                notificationsReady));
        steps.addView(Design.divider(this));
        steps.addView(setupRow(R.id.setup_row_mic, "🎙",
                R.string.setup_mic_title, R.string.setup_mic_body, micReady));
        steps.addView(Design.divider(this));
        steps.addView(setupRow(R.id.setup_row_scan, "🛡",
                R.string.setup_scan_title, R.string.setup_scan_body, scanReady));
        body.addView(steps, Design.match());
        body.addView(Design.space(this, 14));

        body.addView(infoCard(R.string.setup_privacy_title, R.string.setup_privacy_body,
                Design.MINT), Design.match());
        body.addView(Design.space(this, 18));

        if (allReady) {
            TextView finish = Design.button(this, getString(R.string.setup_finish_button),
                    Design.INK, Design.ON_INK);
            finish.setId(R.id.setup_finish);
            finish.setOnClickListener(view -> finishSetup());
            body.addView(finish, Design.match());
        } else {
            TextView continueButton = Design.button(this, getString(R.string.setup_continue_button),
                    Design.SAFFRON, Design.INK);
            continueButton.setId(R.id.setup_continue);
            continueButton.setOnClickListener(view -> beginSetupPermissionFlow());
            body.addView(continueButton, Design.match());
        }
        body.addView(Design.space(this, 10));
        TextView skip = Design.button(this, getString(R.string.setup_skip_button),
                Color.TRANSPARENT, Design.INK);
        skip.setId(R.id.setup_skip);
        skip.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        skip.setOnClickListener(view -> finishSetup());
        body.addView(skip, Design.match());

        showScreen(scroll, 0, ScreenState.of(Screen.SETUP));
    }

    private View setupRow(int rowId, String icon, int titleId, int bodyId, boolean ready) {
        LinearLayout row = Design.row(this);
        row.setId(rowId);
        row.setPadding(0, Design.dp(this, 13), 0, Design.dp(this, 13));
        TextView symbol = Design.text(this, icon, 16, Design.INK, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(Design.rounded(Design.PAPER, 14, this));
        row.addView(symbol, new LinearLayout.LayoutParams(Design.dp(this, 42), Design.dp(this, 42)));

        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 12), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, getString(titleId), 14, Design.INK, true));
        copy.addView(Design.text(this, getString(bodyId), 11, Design.MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView status = Design.chip(this,
                getString(ready ? R.string.ready_status : R.string.needed_status), ready);
        row.addView(status);
        return row;
    }

    private void beginSetupPermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            HistoryStore.prefs(this).edit()
                    .putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply();
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_SETUP_NOTIFICATIONS);
            return;
        }
        setupStepMicrophone();
    }

    private void setupStepMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_SETUP_MIC);
            return;
        }
        setupStepListener();
    }

    private void setupStepListener() {
        if (MessageScanService.isListenerEnabled(this)) {
            if (currentScreen != null && currentScreen.screen == Screen.SETUP) renderSetup();
            return;
        }
        showListenerExplainer();
    }

    private void showListenerExplainer() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_listener_dialog_title)
                .setMessage(R.string.setup_listener_dialog_body)
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    if (currentScreen != null && currentScreen.screen == Screen.SETUP) renderSetup();
                })
                .setPositiveButton(R.string.setup_open_settings,
                        (dialog, which) -> openListenerSettings())
                .show();
    }

    private void openListenerSettings() {
        returningFromListenerSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException error) {
            returningFromListenerSettings = false;
            Toast.makeText(this, R.string.scan_settings_error, Toast.LENGTH_LONG).show();
        }
    }

    private void promptEnableScan() {
        if (MessageScanService.isListenerEnabled(this)) {
            MessageScanService.setScanEnabled(this, true);
            Toast.makeText(this, R.string.scan_enabled_toast, Toast.LENGTH_SHORT).show();
            refreshCurrentScreen();
            return;
        }
        showListenerExplainer();
    }

    private void refreshCurrentScreen() {
        if (currentScreen == null) {
            renderHome();
            return;
        }
        restoringPreviousScreen = true;
        renderState(currentScreen);
        restoringPreviousScreen = false;
    }

    private void finishSetup() {
        markSetupComplete();
        screenHistory.clear();
        currentScreen = null;
        renderHome();
    }

    private boolean isSetupComplete() {
        return HistoryStore.prefs(this).getBoolean(KEY_SETUP_DONE, false);
    }

    private void markSetupComplete() {
        HistoryStore.prefs(this).edit().putBoolean(KEY_SETUP_DONE, true).apply();
    }

    // ---------------------------------------------------------------------
    // Flagged-message alert detail
    // ---------------------------------------------------------------------

    private boolean hasFlaggedExtra(Intent intent) {
        return intent != null && intent.getLongExtra(EXTRA_FLAGGED_ID, -1L) > 0L;
    }

    private long flaggedExtra(Intent intent) {
        return intent == null ? -1L : intent.getLongExtra(EXTRA_FLAGGED_ID, -1L);
    }

    private void renderFlaggedDetail(long id) {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        FlaggedStore.Item item = FlaggedStore.find(this, id);
        if (item == null) {
            Toast.makeText(this, R.string.flagged_missing, Toast.LENGTH_SHORT).show();
            if (!isSetupComplete()) {
                renderSetup();
            } else {
                renderActivity();
            }
            return;
        }

        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_flagged);
        LinearLayout body = pageBody();
        scroll.addView(body);

        TextView back = Design.chip(this, "‹ " + getString(R.string.back), false);
        back.setOnClickListener(view -> navigateBack());
        body.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 22));
        body.addView(Design.label(this, getString(R.string.alert_eyebrow)));
        body.addView(Design.space(this, 8));

        TextView ready = Design.text(this, "⚠  " + getString(item.highRisk
                ? R.string.alert_high_title : R.string.alert_review_title), 13,
                Design.DANGER, true);
        ready.setPadding(Design.dp(this, 13), Design.dp(this, 8),
                Design.dp(this, 13), Design.dp(this, 8));
        ready.setBackground(Design.rounded(Design.DANGER_SOFT, 14, this));
        body.addView(ready, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(Design.space(this, 14));
        body.addView(Design.text(this, getString(R.string.alert_detail_title), 28, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(R.string.alert_detail_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 18));

        LinearLayout sourceCard = Design.column(this);
        sourceCard.setPadding(Design.dp(this, 18), Design.dp(this, 16),
                Design.dp(this, 18), Design.dp(this, 16));
        Design.card(sourceCard, Design.CARD, 20, this);
        sourceCard.addView(Design.label(this, getString(R.string.alert_from_label)));
        sourceCard.addView(Design.space(this, 6));
        sourceCard.addView(Design.text(this,
                item.source.isEmpty() ? getString(R.string.alert_unknown_source) : item.source,
                15, Design.INK, true));
        sourceCard.addView(Design.space(this, 4));
        sourceCard.addView(Design.text(this,
                DateUtils.getRelativeTimeSpanString(item.timeMillis).toString(),
                11, Design.MUTED, false));
        body.addView(sourceCard, Design.match());
        body.addView(Design.space(this, 12));

        LinearLayout messageCard = Design.column(this);
        messageCard.setPadding(Design.dp(this, 18), Design.dp(this, 16),
                Design.dp(this, 18), Design.dp(this, 16));
        messageCard.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        messageCard.addView(Design.label(this, getString(R.string.alert_message_label)));
        messageCard.addView(Design.space(this, 8));
        TextView quote = Design.text(this, item.snippet, 14, Design.INK, false);
        quote.setPadding(Design.dp(this, 12), Design.dp(this, 11),
                Design.dp(this, 12), Design.dp(this, 11));
        quote.setBackground(Design.rounded(Design.CREAM_SOFT, 12, this));
        messageCard.addView(quote, Design.match());
        if (item.matched != null && !item.matched.isEmpty()) {
            messageCard.addView(Design.space(this, 12));
            messageCard.addView(Design.label(this, getString(R.string.alert_signals_label)));
            messageCard.addView(Design.space(this, 6));
            messageCard.addView(Design.text(this,
                    getString(R.string.alert_signals_value, item.signals, item.matched),
                    13, Design.DANGER, true));
        }
        body.addView(messageCard, Design.match());
        body.addView(Design.space(this, 14));

        LinearLayout steps = Design.column(this);
        steps.setPadding(Design.dp(this, 17), Design.dp(this, 16),
                Design.dp(this, 17), Design.dp(this, 16));
        Design.card(steps, Design.CARD, 20, this);
        steps.addView(Design.label(this, getString(R.string.safe_next_steps)));
        steps.addView(Design.space(this, 11));
        steps.addView(safetyStep("1", R.string.safe_step_1));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("2", R.string.safe_step_2));
        steps.addView(Design.space(this, 10));
        steps.addView(safetyStep("3", R.string.safe_step_3));
        body.addView(steps, Design.match());
        body.addView(Design.space(this, 14));

        body.addView(trustedAlertButton(
                getString(R.string.flagged_alert_prefix) + " " + item.source,
                Design.INK, Design.ON_INK), Design.match());
        body.addView(Design.space(this, 9));
        TextView helpline = Design.button(this, getString(R.string.call_1930),
                Color.TRANSPARENT, Design.INK);
        helpline.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        helpline.setOnClickListener(view -> dialCyberHelpline());
        body.addView(helpline, Design.match());
        body.addView(Design.space(this, 9));
        TextView report = Design.button(this, getString(R.string.report_cybercrime),
                Color.TRANSPARENT, Design.INK);
        report.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        report.setOnClickListener(view -> openCybercrimePortal());
        body.addView(report, Design.match());
        body.addView(Design.space(this, 9));
        TextView reportSender = Design.button(this, getString(R.string.report_scam_button),
                Color.TRANSPARENT, Design.DANGER);
        reportSender.setBackground(Design.outlined(Color.TRANSPARENT, Design.DANGER, 17, this));
        reportSender.setOnClickListener(view -> {
            ScamDatabase.report(this, item.source);
            Toast.makeText(this, R.string.report_scam_toast, Toast.LENGTH_SHORT).show();
        });
        body.addView(reportSender, Design.match());
        body.addView(Design.space(this, 16));
        body.addView(infoCard(R.string.scan_disclaimer_title, R.string.scan_disclaimer_body,
                Design.CREAM), Design.match());

        showScreen(scroll, 0, ScreenState.flagged(id));
    }

    private void renderSettings() {
        if (PlanState.isLocked(this)) { renderPaywall(); return; }
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_settings);
        LinearLayout body = pageBody();
        scroll.addView(body);
        body.addView(brandHeader());
        body.addView(Design.space(this, 28));
        body.addView(Design.text(this, getString(R.string.settings_title), 31, Design.INK, true));
        body.addView(Design.space(this, 8));
        body.addView(Design.text(this, getString(R.string.settings_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 24));
        body.addView(settingsPlanCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsLanguageCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsDisplayCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsPermissionsCard(), Design.match());
        body.addView(Design.space(this, 14));
        View scanCard = buildScanStatusCard();
        scanCard.setId(R.id.settings_scan_card);
        body.addView(scanCard, Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsTrustedContactCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsShareSafelyCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsPrivacyCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsAccuracyCard(), Design.match());
        addAdIfFree(body);
        body.addView(Design.space(this, 22));
        TextView version = Design.text(this, getString(R.string.version), 11, Design.MUTED, false);
        version.setGravity(Gravity.CENTER);
        body.addView(version, Design.match());
        showScreen(scroll, 2, ScreenState.of(Screen.SETTINGS));
    }

    private View settingsPlanCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.plan_label)));
        card.addView(Design.space(this, 8));
        boolean premium = PlanState.isPremium(this);
        if (premium) {
            card.addView(Design.text(this, getString(R.string.plan_premium_status), 16,
                    Design.INK, true));
        } else {
            int days = PlanState.daysLeft(this);
            card.addView(Design.text(this,
                    getString(R.string.plan_trial_status, days), 16, Design.INK, true));
            card.addView(Design.space(this, 12));
            TextView upgrade = Design.chip(this, getString(R.string.trial_banner_upgrade), true);
            upgrade.setOnClickListener(view -> renderPaywall());
            card.addView(upgrade, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    /**
     * SHA-256 of the private access code given only to Google Play's review team in
     * Play Console ("Sign in details"). Only the hash is stored, so the code cannot
     * be read from this public source code. To rotate: generate a new code, replace
     * this hash, and update Play Console.
     */
    private static final String REVIEWER_CODE_SHA256 =
            "c628317ec568aa40562ea3e95581f730b650b9063463f715eaa036f8d6c8db24";

    static boolean isReviewerCode(String input) {
        if (input == null) return false;
        String normalized = input.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return java.security.MessageDigest.isEqual(
                    hex.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    REVIEWER_CODE_SHA256.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return false;
        }
    }

    private void showReviewerAccessDialog() {
        final EditText field = new EditText(this);
        field.setId(R.id.reviewer_code_input);
        field.setSingleLine(true);
        field.setHint(R.string.reviewer_code_hint);
        int pad = Design.dp(this, 20);
        field.setPadding(pad, Design.dp(this, 12), pad, Design.dp(this, 12));
        new AlertDialog.Builder(this)
                .setTitle(R.string.reviewer_code_title)
                .setView(field)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.billing_ok, (dialog, which) ->
                        applyReviewerCode(field.getText() == null ? "" : field.getText().toString()))
                .show();
    }

    void applyReviewerCode(String code) {
        if (!isReviewerCode(code)) {
            Toast.makeText(this, R.string.reviewer_code_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        PlanState.setPremium(this, true);
        Toast.makeText(this, R.string.reviewer_code_ok, Toast.LENGTH_LONG).show();
        screenHistory.clear();
        currentScreen = null;
        renderHome();
    }

    /** True for test builds (debug APKs); false for the Play Store release build. */
    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private View settingsLanguageCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.language_label)));
        card.addView(Design.space(this, 12));
        card.addView(languageRow("en", "English", "hi", "हिंदी", "ta", "தமிழ்"), Design.match());
        card.addView(Design.space(this, 10));
        card.addView(languageRow("te", "తెలుగు", "mr", "मराठी", "bn", "বাংলা"), Design.match());
        return card;
    }

    private View languageRow(String c1, String l1, String c2, String l2, String c3, String l3) {
        LinearLayout row = Design.row(this);
        row.addView(langChip(c1, l1), Design.weight());
        View g1 = new View(this);
        row.addView(g1, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        row.addView(langChip(c2, l2), Design.weight());
        View g2 = new View(this);
        row.addView(g2, new LinearLayout.LayoutParams(Design.dp(this, 8), 1));
        row.addView(langChip(c3, l3), Design.weight());
        return row;
    }

    private TextView langChip(String code, String label) {
        TextView chip = Design.chip(this, label, currentLanguage().equals(code));
        chip.setOnClickListener(view -> setLanguage(code));
        return chip;
    }

    private View settingsPermissionsCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.permissions_label)));
        card.addView(Design.space(this, 8));
        card.addView(Design.text(this, getString(R.string.permissions_body), 13,
                Design.MUTED, false));
        card.addView(Design.space(this, 14));
        card.addView(permissionRow(
                "🔔",
                R.string.notifications_title,
                R.string.notifications_reason,
                NotificationHelper.areEnabled(this) ? R.string.allowed : R.string.not_allowed,
                this::requestNotificationAccess));
        card.addView(Design.space(this, 12));
        card.addView(permissionRow(
                "🎙",
                R.string.microphone_title,
                R.string.microphone_reason,
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        ? R.string.allowed : R.string.ask_when_used,
                this::renderVoice));
        card.addView(Design.space(this, 12));
        card.addView(permissionRow(
                "□",
                R.string.calendar_title,
                R.string.calendar_reason,
                R.string.not_required,
                null));
        card.addView(Design.space(this, 12));
        card.addView(buildScanPermissionRow());
        boolean listenerEnabled = MessageScanService.isListenerEnabled(this);
        if (listenerEnabled) {
            card.addView(Design.space(this, 12));
            card.addView(buildScanToggleRow());
        }
        card.addView(Design.space(this, 14));
        card.addView(Design.divider(this));
        card.addView(Design.space(this, 12));
        card.addView(Design.text(this, getString(R.string.permission_center_note), 12,
                Design.MUTED, false));
        card.addView(Design.space(this, 12));
        TextView runSetup = Design.chip(this, getString(R.string.recheck_permissions), false);
        runSetup.setOnClickListener(view -> {
            launchPermissionsAsked = false;
            requestPendingPermissions();
        });
        card.addView(runSetup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildScanPermissionRow() {
        boolean listenerEnabled = MessageScanService.isListenerEnabled(this);
        boolean scanOn = listenerEnabled && MessageScanService.isScanEnabled(this);
        int statusId;
        if (!listenerEnabled) {
            statusId = R.string.set_up_status;
        } else if (scanOn) {
            statusId = R.string.on_status;
        } else {
            statusId = R.string.paused_status;
        }
        return permissionRow(
                "🛡",
                R.string.messages_title,
                R.string.messages_reason,
                statusId,
                listenerEnabled ? this::openListenerSettings : this::promptEnableScan);
    }

    private View buildScanToggleRow() {
        boolean scanOn = MessageScanService.isScanEnabled(this);
        LinearLayout row = Design.row(this);
        TextView symbol = Design.text(this, "⟳", 16, Design.INK, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(Design.rounded(Design.PAPER, 14, this));
        row.addView(symbol, new LinearLayout.LayoutParams(Design.dp(this, 40), Design.dp(this, 40)));

        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 11), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, getString(R.string.scan_toggle_title), 13, Design.INK, true));
        copy.addView(Design.text(this, getString(R.string.scan_toggle_reason), 11, Design.MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView toggle = Design.chip(this,
                getString(scanOn ? R.string.on_status : R.string.off_status), scanOn);
        toggle.setId(R.id.scan_toggle);
        toggle.setOnClickListener(view -> {
            MessageScanService.setScanEnabled(this, !scanOn);
            renderSettings();
        });
        row.addView(toggle);
        return row;
    }

    private View permissionRow(
            String icon,
            int titleId,
            int reasonId,
            int statusId,
            Runnable action) {
        LinearLayout row = Design.row(this);
        TextView symbol = Design.text(this, icon, 16, Design.INK, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(Design.rounded(Design.PAPER, 14, this));
        row.addView(symbol, new LinearLayout.LayoutParams(Design.dp(this, 40), Design.dp(this, 40)));

        LinearLayout copy = Design.column(this);
        copy.setPadding(Design.dp(this, 11), 0, Design.dp(this, 8), 0);
        copy.addView(Design.text(this, getString(titleId), 13, Design.INK, true));
        copy.addView(Design.text(this, getString(reasonId), 11, Design.MUTED, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView status = Design.chip(this, getString(statusId), statusId == R.string.allowed);
        if (action != null) status.setOnClickListener(view -> action.run());
        row.addView(status);
        return row;
    }

    private View settingsTrustedContactCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.trusted_label)));
        card.addView(Design.space(this, 8));
        card.addView(Design.text(this, getString(R.string.trusted_body), 13, Design.MUTED, false));
        card.addView(Design.space(this, 12));

        if (TrustedContact.isSet(this)) {
            card.addView(Design.text(this, TrustedContact.getName(this), 15, Design.INK, true));
            card.addView(Design.text(this, TrustedContact.getNumber(this), 12, Design.MUTED, false));
            card.addView(Design.space(this, 12));
            LinearLayout row = Design.row(this);
            TextView edit = Design.chip(this, getString(R.string.trusted_edit), true);
            edit.setOnClickListener(view -> showTrustedContactDialog());
            row.addView(edit);
            View gap = new View(this);
            row.addView(gap, new LinearLayout.LayoutParams(Design.dp(this, 10),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView remove = Design.chip(this, getString(R.string.trusted_remove), false);
            remove.setOnClickListener(view -> {
                TrustedContact.clear(this);
                renderSettings();
            });
            row.addView(remove);
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(Design.space(this, 14));
            LinearLayout toggleRow = Design.row(this);
            TextView toggleLabel = Design.text(this, getString(R.string.trusted_alert_toggle), 13,
                    Design.INK, true);
            toggleRow.addView(toggleLabel,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            boolean on = TrustedContact.isAlertEnabled(this);
            TextView toggle = Design.chip(this,
                    getString(on ? R.string.on_status : R.string.off_status), on);
            toggle.setOnClickListener(view -> {
                TrustedContact.setAlertEnabled(this, !on);
                renderSettings();
            });
            toggleRow.addView(toggle);
            card.addView(toggleRow, Design.match());
        } else {
            TextView add = Design.chip(this, getString(R.string.trusted_add), true);
            add.setOnClickListener(view -> showTrustedContactDialog());
            card.addView(add, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private void showTrustedContactDialog() {
        LinearLayout box = Design.column(this);
        int pad = Design.dp(this, 20);
        box.setPadding(pad, Design.dp(this, 8), pad, 0);
        EditText nameField = new EditText(this);
        nameField.setHint(R.string.trusted_name_hint);
        nameField.setSingleLine(true);
        nameField.setText(TrustedContact.getName(this));
        box.addView(nameField, Design.match());
        EditText numberField = new EditText(this);
        numberField.setHint(R.string.trusted_number_hint);
        numberField.setSingleLine(true);
        numberField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        numberField.setText(TrustedContact.getNumber(this));
        box.addView(numberField, Design.match());

        new AlertDialog.Builder(this)
                .setTitle(R.string.trusted_dialog_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.trusted_save, (dialog, which) -> {
                    String name = nameField.getText() == null ? "" : nameField.getText().toString().trim();
                    String number = numberField.getText() == null
                            ? "" : numberField.getText().toString().trim();
                    if (number.isEmpty()) {
                        Toast.makeText(this, R.string.trusted_number_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.isEmpty()) name = getString(R.string.trusted_default_name);
                    TrustedContact.set(this, name, number);
                    Toast.makeText(this, R.string.trusted_saved, Toast.LENGTH_SHORT).show();
                    renderSettings();
                })
                .show();
    }

    private View settingsShareSafelyCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        card.setBackground(Design.rounded(Design.CREAM, 21, this));
        card.addView(Design.text(this, getString(R.string.share_safely_title), 16,
                Design.INK, true));
        card.addView(Design.space(this, 7));
        card.addView(Design.text(this, getString(R.string.share_safely_body), 13,
                Design.INK, false));
        return card;
    }

    private View settingsPrivacyCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        card.setBackground(Design.rounded(Design.MINT, 21, this));
        card.addView(Design.label(this, getString(R.string.privacy_label)));
        card.addView(Design.space(this, 12));
        card.addView(checkLine(R.string.privacy_point_1));
        card.addView(Design.space(this, 10));
        card.addView(checkLine(R.string.privacy_point_2));
        card.addView(Design.space(this, 10));
        card.addView(checkLine(R.string.privacy_point_3));
        card.addView(Design.space(this, 14));
        TextView policy = Design.chip(this, getString(R.string.privacy_policy_link), false);
        policy.setId(R.id.privacy_policy_link);
        policy.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)));
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.open_link_error, Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(policy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View checkLine(int stringId) {
        LinearLayout row = Design.row(this);
        TextView check = Design.text(this, "✓", 12, Design.INK, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(Design.rounded(Design.CARD, 12, this));
        row.addView(check, new LinearLayout.LayoutParams(Design.dp(this, 28), Design.dp(this, 28)));
        TextView text = Design.text(this, getString(stringId), 13, Design.INK, false);
        text.setPadding(Design.dp(this, 10), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private View settingsAccuracyCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        card.setBackground(Design.outlined(Design.CARD, Design.SOFT, 21, this));
        card.addView(Design.label(this, getString(R.string.accuracy_label)));
        card.addView(Design.space(this, 9));
        card.addView(Design.text(this, getString(R.string.accuracy_body), 14, Design.INK, false));
        return card;
    }

    private void requestNotificationAccess() {
        if (NotificationHelper.areEnabled(this)) {
            NotificationHelper.showReady(this);
            Toast.makeText(this, R.string.notification_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean requestedBefore = HistoryStore.prefs(this)
                    .getBoolean(KEY_NOTIFICATION_REQUESTED, false);
            if (!requestedBefore || shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS)) {
                HistoryStore.prefs(this).edit()
                        .putBoolean(KEY_NOTIFICATION_REQUESTED, true)
                        .apply();
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            } else {
                openNotificationSettings();
            }
        } else {
            openNotificationSettings();
        }
    }

    private void openNotificationSettings() {
        returningFromNotificationSettings = true;
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (RuntimeException error) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(fallback);
        }
    }

    private void requestMicrophone(boolean startListening) {
        startListeningAfterPermission = startListening;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            if (startListening) startVoiceRecognition();
            else Toast.makeText(this, R.string.allowed, Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (granted) {
                NotificationHelper.showReady(this);
                Toast.makeText(this, R.string.notification_enabled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show();
            }
            if (currentScreen != null && currentScreen.screen == Screen.SETTINGS) renderSettings();
        } else if (requestCode == REQUEST_MICROPHONE) {
            if (granted && startListeningAfterPermission) {
                startVoiceRecognition();
            } else if (!granted) {
                if (voiceStatus != null) voiceStatus.setText(R.string.voice_permission_denied);
                Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show();
            }
            if (currentScreen != null && currentScreen.screen == Screen.SETTINGS) renderSettings();
        } else if (requestCode == REQUEST_SETUP_NOTIFICATIONS) {
            requestMicIfPending();
        } else if (requestCode == REQUEST_REMINDER_NOTIFICATIONS) {
            if (!granted) {
                Toast.makeText(this, R.string.reminder_notifications_off,
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_SETUP_MIC) {
            // Runtime permission chain complete; message-alert access is enabled
            // separately from the Home card because it needs a settings visit.
        }
    }

    private void startVoiceRecognition() {
        if (voiceInput == null || currentScreen == null || currentScreen.screen != Screen.VOICE) {
            renderVoice();
            handler.postDelayed(this::startVoiceRecognition, 250);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus.setText(R.string.voice_unavailable);
            return;
        }
        destroySpeechRecognizer();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (voiceStatus != null) voiceStatus.setText(R.string.listening);
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (voiceStatus != null) voiceStatus.setText(R.string.voice_error);
            }
            @Override public void onResults(Bundle results) {
                applyVoiceResults(results);
            }
            @Override public void onPartialResults(Bundle partialResults) {
                applyVoiceResults(partialResults);
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                        currentLanguage().equals("hi") ? "hi-IN" : "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            speechRecognizer.startListening(intent);
        } catch (RuntimeException error) {
            voiceStatus.setText(R.string.voice_unavailable);
            destroySpeechRecognizer();
        }
    }

    private void applyVoiceResults(Bundle results) {
        if (results == null || voiceInput == null) return;
        ArrayList<String> matches = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            voiceInput.setText(matches.get(0));
            voiceInput.setSelection(voiceInput.length());
            if (voiceStatus != null) voiceStatus.setText(R.string.voice_ready);
        }
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer == null) return;
        try {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        } catch (RuntimeException ignored) {
            // The vendor speech service may already be disconnected.
        }
        speechRecognizer = null;
    }

    private void cancelPendingAnalysis() {
        if (pendingAnalysis != null) {
            handler.removeCallbacks(pendingAnalysis);
            pendingAnalysis = null;
        }
    }

    // API 33+ gestures use the registered OnBackInvoked callback; this override
    // intentionally preserves hardware/system Back support on Android 8–12.
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void navigateBack() {
        cancelPendingAnalysis();
        destroySpeechRecognizer();
        if (screenHistory.isEmpty()) {
            finish();
            return;
        }
        ScreenState previous = screenHistory.pop();
        restoringPreviousScreen = true;
        renderState(previous);
        restoringPreviousScreen = false;
    }

    private void renderState(ScreenState state) {
        switch (state.screen) {
            case HOME -> renderHome();
            case ACTIVITY -> renderActivity();
            case SETTINGS -> renderSettings();
            case SETUP -> renderSetup();
            case ONBOARDING -> renderOnboarding((int) state.flaggedId);
            case PAYWALL -> renderPaywall();
            case PANIC -> renderPanic();
            case CHECKER -> renderChecker(state.voiceDescription);
            case CHECKER_RESULT -> renderCheckerResult(
                    state.voiceDescription == null ? "" : state.voiceDescription);
            case TIP -> renderTip((int) state.flaggedId);
            case REMINDER -> renderReminder((int) state.flaggedId);
            case FLAGGED -> renderFlaggedDetail(state.flaggedId);
            case VOICE -> renderVoice(state.voiceDescription);
            case RESULT -> renderResult(SampleAnalysis.of(state.sampleKind));
            case VOICE_RESULT -> renderVoiceResult(
                    state.voiceDescription == null ? "" : state.voiceDescription);
            case PROCESSING -> {
                SampleAnalysis sample = SampleAnalysis.of(state.sampleKind);
                renderProcessing(sample);
                pendingAnalysis = () -> {
                    if (currentScreen != null && currentScreen.screen == Screen.PROCESSING) {
                        pendingAnalysis = null;
                        renderResult(sample);
                    }
                };
                handler.postDelayed(pendingAnalysis, 1450);
            }
        }
    }

    private void openImagePicker() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        try {
            startActivityForResult(intent, REQUEST_IMAGE);
        } catch (Exception error) {
            Toast.makeText(this, R.string.image_picker_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null) {
            showImageSelectedDialog();
        }
    }

    private void showImageSelectedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_not_live_title)
                .setMessage(R.string.import_not_live_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.preview_result,
                        (dialog, which) -> simulateAnalysis(SampleAnalysis.Kind.BILL))
                .show();
    }

    private boolean isSharedImage(Intent intent) {
        return intent != null
                && Intent.ACTION_SEND.equals(intent.getAction())
                && intent.getType() != null
                && intent.getType().startsWith("image/");
    }

    private boolean isSharedText(Intent intent) {
        return intent != null
                && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())
                && sharedText(intent).length() > 0;
    }

    private String sharedText(Intent intent) {
        CharSequence shared = intent == null
                ? null : intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null) return "";
        String text = shared.toString().trim();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private static final java.util.Set<String> SUPPORTED_LANGUAGES =
            new java.util.HashSet<>(java.util.Arrays.asList("en", "hi", "ta", "te", "mr", "bn"));

    private static String savedLanguage(Context context) {
        try {
            String code = HistoryStore.prefs(context).getString(KEY_LANGUAGE, null);
            if (code != null && SUPPORTED_LANGUAGES.contains(code)) return code;
            if (code != null) {
                HistoryStore.prefs(context).edit().remove(KEY_LANGUAGE).apply();
            }
        } catch (ClassCastException invalidStoredValue) {
            HistoryStore.prefs(context).edit().remove(KEY_LANGUAGE).apply();
        }
        return null;
    }

    private String currentLanguage() {
        String saved = savedLanguage(this);
        if (saved != null) return saved;
        String deviceLanguage = getResources().getConfiguration()
                .getLocales().get(0).getLanguage();
        return SUPPORTED_LANGUAGES.contains(deviceLanguage) ? deviceLanguage : "en";
    }

    private void toggleLanguage() {
        setLanguage(currentLanguage().equals("hi") ? "en" : "hi");
    }

    private void setLanguage(String code) {
        if (currentLanguage().equals(code)) return;
        HistoryStore.prefs(this).edit().putString(KEY_LANGUAGE, code).apply();
        recreate();
    }
}
