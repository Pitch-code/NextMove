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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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

import com.pitchcode.nextmove.data.HistoryStore;
import com.pitchcode.nextmove.data.SampleAnalysis;
import com.pitchcode.nextmove.notifications.NotificationHelper;
import com.pitchcode.nextmove.safety.VoiceRiskAssessment;
import com.pitchcode.nextmove.ui.Design;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMAGE = 200;
    private static final int REQUEST_NOTIFICATIONS = 201;
    private static final int REQUEST_MICROPHONE = 202;
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_NOTIFICATION_INTRO = "notification_intro_shown";
    private static final String CYBERCRIME_URL = "https://cybercrime.gov.in/";

    private enum Screen { HOME, PROCESSING, RESULT, ACTIVITY, SETTINGS, VOICE, VOICE_RESULT }

    private static final class ScreenState {
        final Screen screen;
        final SampleAnalysis.Kind sampleKind;
        final String voiceDescription;

        ScreenState(Screen screen, SampleAnalysis.Kind sampleKind, String voiceDescription) {
            this.screen = screen;
            this.sampleKind = sampleKind;
            this.voiceDescription = voiceDescription;
        }

        static ScreenState of(Screen screen) {
            return new ScreenState(screen, null, null);
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
    private TextView voiceStatus;
    private boolean startListeningAfterPermission;

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
        NotificationHelper.createChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::navigateBack);
        }
        buildShell();
        renderHome();
        if (isSharedImage(getIntent())) {
            handler.postDelayed(this::showImageSelectedDialog, 350);
        } else {
            handler.postDelayed(this::offerNotificationSetupOnce, 700);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroySpeechRecognizer();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isSharedImage(intent)) showImageSelectedDialog();
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
        return first.screen == second.screen && first.sampleKind == second.sampleKind;
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
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_home);
        LinearLayout body = pageBody();
        scroll.addView(body);
        body.addView(brandHeader());
        body.addView(Design.space(this, 30));
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
        body.addView(buildSafetyToolsCard(), Design.match());
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
        card.addView(Design.text(this, getString(R.string.share_title), 25, Color.WHITE, true));
        card.addView(Design.space(this, 7));
        card.addView(Design.text(this, getString(R.string.share_body), 14,
                Color.rgb(204, 215, 210), false));
        card.addView(Design.space(this, 18));
        TextView choose = Design.button(this, getString(R.string.choose_image),
                Design.SAFFRON, Design.INK);
        choose.setContentDescription(getString(R.string.choose_image));
        choose.setOnClickListener(view -> openImagePicker());
        card.addView(choose, Design.match());
        card.addView(Design.space(this, 10));
        TextView voice = Design.button(this, "🎙  " + getString(R.string.voice_button),
                Color.TRANSPARENT, Color.WHITE);
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
        card.addView(Design.space(this, 13));
        TextView open = Design.chip(this, "🎙  " + getString(R.string.open_voice_check), true);
        open.setOnClickListener(view -> renderVoice());
        card.addView(open, new LinearLayout.LayoutParams(
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
        action.setBackground(Design.rounded(sample.danger ? Design.DANGER_SOFT : Color.rgb(255, 241, 207), 22, this));
        action.addView(Design.label(this, getString(R.string.next_move_label)));
        action.addView(Design.space(this, 8));
        action.addView(Design.text(this, getString(sample.action), 16, Design.INK, true));
        action.addView(Design.space(this, 16));
        if (sample.danger) {
            TextView helpline = Design.button(this, getString(R.string.call_1930),
                    Design.INK, Color.WHITE);
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
                    Design.INK, Color.WHITE);
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
        quote.setBackground(Design.rounded(Color.rgb(255, 247, 225), 12, this));
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
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930")));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.dial_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void openCybercrimePortal() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CYBERCRIME_URL)));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.open_link_error, Toast.LENGTH_SHORT).show();
        }
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

    private void renderVoice() {
        ScrollView scroll = scrollPage();
        scroll.setId(R.id.screen_voice);
        LinearLayout body = pageBody();
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
        body.addView(Design.space(this, 20));

        voiceInput = new EditText(this);
        voiceInput.setTextSize(15);
        voiceInput.setTextColor(Design.INK);
        voiceInput.setHintTextColor(Design.MUTED);
        voiceInput.setHint(R.string.voice_hint);
        voiceInput.setGravity(Gravity.TOP | Gravity.START);
        voiceInput.setMinHeight(Design.dp(this, 150));
        voiceInput.setPadding(Design.dp(this, 16), Design.dp(this, 15),
                Design.dp(this, 16), Design.dp(this, 15));
        voiceInput.setBackground(Design.outlined(Design.CARD, Design.SOFT, 20, this));
        body.addView(voiceInput, Design.match());
        body.addView(Design.space(this, 10));

        voiceStatus = Design.text(this, getString(R.string.voice_ready), 12,
                Design.MUTED, false);
        body.addView(voiceStatus, Design.match());
        body.addView(Design.space(this, 14));

        TextView speak = Design.button(this, "🎙  " + getString(R.string.start_listening),
                Design.SAFFRON, Design.INK);
        speak.setOnClickListener(view -> requestMicrophone(true));
        body.addView(speak, Design.match());
        body.addView(Design.space(this, 10));

        TextView check = Design.button(this, getString(R.string.check_situation),
                Design.INK, Color.WHITE);
        check.setOnClickListener(view -> analyzeVoiceDescription());
        body.addView(check, Design.match());
        body.addView(Design.space(this, 18));
        body.addView(infoCard(R.string.voice_privacy_title, R.string.voice_privacy_body,
                Design.MINT), Design.match());
        body.addView(Design.space(this, 12));
        body.addView(infoCard(R.string.preliminary_check, R.string.voice_live_boundary,
                Color.rgb(255, 241, 207)), Design.match());

        showScreen(scroll, 0, ScreenState.of(Screen.VOICE));
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
        renderVoiceResult(description);
    }

    private void renderVoiceResult(String description) {
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
                assessment.highRisk ? Design.DANGER_SOFT : Color.rgb(255, 241, 207),
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
                Design.INK, Color.WHITE);
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
                Color.rgb(255, 241, 207)), Design.match());

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

    private void renderSettings() {
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
        body.addView(settingsLanguageCard(), Design.match());
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
                () -> requestMicrophone(false)));
        card.addView(Design.space(this, 12));
        card.addView(permissionRow(
                "□",
                R.string.calendar_title,
                R.string.calendar_reason,
                R.string.not_required,
                null));
        card.addView(Design.space(this, 12));
        card.addView(permissionRow(
                "✉",
                R.string.messages_title,
                R.string.messages_reason,
                R.string.not_required,
                null));
        card.addView(Design.space(this, 14));
        card.addView(Design.divider(this));
        card.addView(Design.space(this, 12));
        card.addView(Design.text(this, getString(R.string.permission_center_note), 12,
                Design.MUTED, false));
        return card;
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
        card.setBackground(Design.rounded(Color.rgb(255, 241, 207), 21, this));
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

    private void offerNotificationSetupOnce() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && NotificationHelper.areEnabled(this)) return;
        boolean shown = HistoryStore.prefs(this).getBoolean(KEY_NOTIFICATION_INTRO, false);
        if (shown) return;
        HistoryStore.prefs(this).edit().putBoolean(KEY_NOTIFICATION_INTRO, true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_intro_title)
                .setMessage(R.string.notification_intro_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.enable_notifications,
                        (dialog, which) -> requestNotificationAccess())
                .show();
    }

    private void requestNotificationAccess() {
        if (NotificationHelper.areEnabled(this)) {
            NotificationHelper.showReady(this);
            Toast.makeText(this, R.string.notification_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } else {
            NotificationHelper.showReady(this);
            Toast.makeText(this, R.string.notification_older_android, Toast.LENGTH_LONG).show();
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
            case VOICE -> renderVoice();
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
