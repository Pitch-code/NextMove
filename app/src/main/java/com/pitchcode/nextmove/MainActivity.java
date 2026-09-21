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

import com.pitchcode.nextmove.data.FlaggedStore;
import com.pitchcode.nextmove.data.HistoryStore;
import com.pitchcode.nextmove.data.PlanState;
import com.pitchcode.nextmove.data.SampleAnalysis;
import com.pitchcode.nextmove.notifications.NotificationHelper;
import com.pitchcode.nextmove.data.ScamTips;
import com.pitchcode.nextmove.safety.LinkNumberCheck;
import com.pitchcode.nextmove.safety.VoiceRiskAssessment;
import com.pitchcode.nextmove.scan.MessageScanService;
import com.pitchcode.nextmove.ui.Design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    public static final String EXTRA_FLAGGED_ID = "com.pitchcode.nextmove.FLAGGED_ID";

    private static final int REQUEST_IMAGE = 200;
    private static final int REQUEST_NOTIFICATIONS = 201;
    private static final int REQUEST_MICROPHONE = 202;
    private static final int REQUEST_SETUP_NOTIFICATIONS = 203;
    private static final int REQUEST_SETUP_MIC = 204;
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_NOTIFICATION_REQUESTED = "notification_requested";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_DARK = "theme_dark";
    private static final String KEY_HIGH_CONTRAST = "high_contrast";
    private static final String KEY_TEXT_SCALE = "text_scale";
    private static final String CYBERCRIME_URL = "https://cybercrime.gov.in/";

    private enum Screen {
        HOME, PROCESSING, RESULT, ACTIVITY, SETTINGS, VOICE, VOICE_RESULT, SETUP, FLAGGED, PAYWALL,
        PANIC, CHECKER, CHECKER_RESULT, TIP, ONBOARDING
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
        NotificationHelper.createChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::navigateBack);
        }
        buildShell();
        if (!isOnboardingComplete()) {
            renderOnboarding(0);
        } else if (!isSetupComplete()) {
            renderSetup();
        } else if (PlanState.isLocked(this)) {
            renderPaywall();
        } else if (isSharedText(getIntent())) {
            renderVoice(sharedText(getIntent()));
        } else if (hasFlaggedExtra(getIntent())) {
            renderFlaggedDetail(flaggedExtra(getIntent()));
        } else {
            renderHome();
            if (isSharedImage(getIntent())) {
                handler.postDelayed(this::showImageSelectedDialog, 350);
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroySpeechRecognizer();
        super.onDestroy();
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
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Design.rounded(Design.SAFFRON, 15, this));
        row.addView(mark, new LinearLayout.LayoutParams(Design.dp(this, 48), Design.dp(this, 48)));

        LinearLayout names = Design.column(this);
        names.setPadding(Design.dp(this, 12), 0, 0, 0);
        names.addView(Design.text(this, getString(R.string.app_name), 18, Design.INK, true));
        names.addView(Design.text(this, getString(R.string.tagline), 11, Design.MUTED, false));
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView language = Design.chip(this, getString(R.string.language_button), false);
        language.setContentDescription(getString(R.string.language_label));
        language.setOnClickListener(view -> toggleLanguage());
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
        body.addView(buildScanStatusCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildNotificationSetupCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(buildSafetyToolsCard(), Design.match());
        if (!PlanState.isPremium(this)) {
            body.addView(Design.space(this, 14));
            body.addView(buildAdPlaceholder(), Design.match());
        }
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
        addSampleChip(row, SampleAnalysis.Kind.BILL);
        addSampleChip(row, SampleAnalysis.Kind.VISIT);
        addSampleChip(row, SampleAnalysis.Kind.RETURN);
        addSampleChip(row, SampleAnalysis.Kind.SCAM);
        scroll.addView(row);
        return scroll;
    }

    private void addSampleChip(LinearLayout row, SampleAnalysis.Kind kind) {
        SampleAnalysis sample = SampleAnalysis.of(kind);
        TextView chip = Design.chip(this, getString(sample.chip), kind == SampleAnalysis.Kind.BILL);
        chip.setId(switch (kind) {
            case BILL -> R.id.sample_bill;
            case VISIT -> R.id.sample_visit;
            case RETURN -> R.id.sample_return;
            case SCAM -> R.id.sample_scam;
        });
        chip.setOnClickListener(view -> simulateAnalysis(kind));
        LinearLayout.LayoutParams params = Design.match();
        params.setMarginEnd(Design.dp(this, 9));
        row.addView(chip, params);
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
        chip.setOnClickListener(view -> {
            if (active) renderSettings();
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
        TextView ask = Design.button(this, "👥  " + getString(R.string.ask_trusted_button),
                Color.TRANSPARENT, Design.INK);
        ask.setBackground(Design.outlined(Color.TRANSPARENT, Design.INK, 17, this));
        ask.setOnClickListener(view -> shareToTrustedPerson(getString(R.string.panic_share_text)));
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
        body.addView(Design.text(this, getString(result.highRisk
                ? R.string.checker_high_title : R.string.checker_low_title), 28, Design.INK, true));
        body.addView(Design.space(this, 10));
        body.addView(Design.text(this, getString(result.highRisk
                ? R.string.checker_high_body : R.string.checker_low_body), 14, Design.MUTED, false));
        body.addView(Design.space(this, 16));

        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 18), Design.dp(this, 16),
                Design.dp(this, 18), Design.dp(this, 16));
        card.setBackground(Design.rounded(
                result.highRisk ? Design.DANGER_SOFT : Design.CREAM, 19, this));
        card.addView(Design.text(this,
                getString(R.string.checker_signals_found, result.score),
                15, result.highRisk ? Design.DANGER : Design.INK, true));
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

        TextView ask = Design.button(this, "👥  " + getString(R.string.ask_trusted_button),
                Design.INK, Design.ON_INK);
        ask.setOnClickListener(view -> shareToTrustedPerson(
                getString(R.string.checker_share_prefix) + " " + input));
        body.addView(ask, Design.match());
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
            TextView upgrade = Design.button(this, getString(R.string.paywall_upgrade_button),
                    Design.SAFFRON, Design.INK);
            upgrade.setId(R.id.plan_upgrade);
            upgrade.setOnClickListener(view -> simulateUpgrade());
            body.addView(upgrade, Design.match());
            body.addView(Design.space(this, 10));
            body.addView(Design.text(this, getString(R.string.paywall_note), 11,
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

        body.addView(Design.space(this, 18));
        body.addView(buildDemoControls(), Design.match());

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

    private View buildAdPlaceholder() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 16), Design.dp(this, 18),
                Design.dp(this, 16), Design.dp(this, 18));
        card.setBackground(Design.outlined(Design.AD_BG, Design.SOFT, 16, this));
        card.setGravity(Gravity.CENTER);
        TextView tag = Design.text(this, getString(R.string.ad_placeholder_label), 10,
                Design.MUTED, true);
        tag.setLetterSpacing(0.12f);
        tag.setGravity(Gravity.CENTER);
        card.addView(tag, Design.match());
        card.addView(Design.space(this, 4));
        TextView body = Design.text(this, getString(R.string.ad_placeholder_body), 12,
                Design.MUTED, false);
        body.setGravity(Gravity.CENTER);
        card.addView(body, Design.match());
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
        if (!isSetupComplete()) {
            renderSetup();
        } else if (PlanState.isLocked(this)) {
            renderPaywall();
        } else {
            renderHome();
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
        body.addView(settingsShareSafelyCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsPrivacyCard(), Design.match());
        body.addView(Design.space(this, 14));
        body.addView(settingsAccuracyCard(), Design.match());
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

    private View settingsLanguageCard() {
        LinearLayout card = Design.column(this);
        card.setPadding(Design.dp(this, 17), Design.dp(this, 17),
                Design.dp(this, 17), Design.dp(this, 17));
        Design.card(card, Design.CARD, 21, this);
        card.addView(Design.label(this, getString(R.string.language_label)));
        card.addView(Design.space(this, 12));
        LinearLayout row = Design.row(this);
        boolean hindi = currentLanguage().equals("hi");
        TextView english = Design.chip(this, getString(R.string.english), !hindi);
        english.setOnClickListener(view -> setLanguage("en"));
        TextView hindiButton = Design.chip(this, getString(R.string.hindi), hindi);
        hindiButton.setOnClickListener(view -> setLanguage("hi"));
        row.addView(english, Design.weight());
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Design.dp(this, 10), 1));
        row.addView(hindiButton, Design.weight());
        card.addView(row, Design.match());
        return card;
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
        TextView runSetup = Design.chip(this, getString(R.string.run_setup_again), false);
        runSetup.setOnClickListener(view -> renderSetup());
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
            setupStepMicrophone();
        } else if (requestCode == REQUEST_SETUP_MIC) {
            setupStepListener();
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

    private static String savedLanguage(Context context) {
        try {
            String code = HistoryStore.prefs(context).getString(KEY_LANGUAGE, null);
            if ("en".equals(code) || "hi".equals(code)) return code;
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
        return "hi".equals(deviceLanguage) ? "hi" : "en";
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
