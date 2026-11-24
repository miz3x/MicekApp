package pl.micekwidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;

public class MainActivity extends Activity {
    static final String OPEN_DAY = "pl.micekwidget.OPEN_DAY";
    private int BG, PANEL, EDGE, INK, MUTED, ACCENT, SELECTED, ALERT, GREEN;
    private boolean light;
    private boolean appGlass;
    private static final String[] SHORT_DAYS = {"Pon", "Wt", "Śr", "Czw", "Pt"};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout content;
    private SharedPreferences prefs;
    private boolean picking;
    private boolean home = true;
    private boolean settings;
    private boolean firstStart = true;
    private AppUpdates updates;
    private int welcomeStep;
    private boolean welcomePicker;
    private SchoolPlan.ClassPlan welcomeClass;
    private Button welcomeConfirm;
    private boolean previewPicking;
    private SchoolPlan.ClassPlan previewClass;
    private org.json.JSONArray previewWeek;
    private org.json.JSONObject previewChanges;
    private long previewUpdated;
    private boolean previewFailed, previewChangesFailed;
    private int previewRequest;
    private LocalDate planWeek;
    private SharedPreferences widgetPlan;
    private final android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updateHome = new Runnable() {
        @Override public void run() {
            if (welcomeStep == 0 && home && !picking && !settings) renderHome();
            clock.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int widgetTheme;
    private boolean widgetGlass;
    private int widgetGlassOpacity = 100;
    private boolean widgetGlassEdge;
    private SchoolPlan.ClassPlan widgetClass;
    private Button widgetSave;
    private PullRefresh pull;
    private Runnable pickerRefresh;
    private boolean refreshingPlan, refreshingNotices, refreshingPicker;
    private LocalDate noticesDate, loadingNoticesDate;
    private org.json.JSONArray notices;
    private int noticeRequest;
    private String school = "LO";
    private int day;
    private int request;
    private List<SchoolPlan.ClassPlan> classes;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if (p == visiblePlan() && "refreshFinished".equals(key)) {
            refreshingPlan = false;
            finishRefresh();
        }
        if (!picking) renderScreen();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppMotion.transition(this);
        if (isWidgetConfiguration()) {
            widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            setResult(RESULT_CANCELED, widgetResult());
            if (!PlanWidget.owns(this, widgetId)) {
                finish();
                return;
            }
            prefs = PlanWidget.settings(this, widgetId);
            widgetTheme = state == null ? PlanWidget.themeMode(prefs)
                    : state.getInt("widgetTheme", PlanWidget.themeMode(prefs));
            GlassSettings effect = GlassSettings.read(prefs, "glass", widgetTheme);
            widgetGlass = state == null ? effect.enabled : state.getBoolean("widgetGlass", effect.enabled);
            widgetGlassOpacity = state == null ? effect.opacity : state.getInt("widgetGlassOpacity", effect.opacity);
            widgetGlassEdge = state == null ? effect.edge : state.getBoolean("widgetGlassEdge", effect.edge);
            String name = state == null ? prefs.getString("name", "") : state.getString("widgetClassName", "");
            String url = state == null ? prefs.getString("url", "") : state.getString("widgetClassUrl", "");
            if (!name.isEmpty() && !url.isEmpty()) widgetClass = new SchoolPlan.ClassPlan(name, url);
        } else {
            prefs = SchoolPlan.prefs(this);
            if (!getIntent().getBooleanExtra("standalonePlan", false)) updates = new AppUpdates(this, executor);
            if (updates != null && !PlanWidget.OPEN_PLAN.equals(getIntent().getAction())
                    && !OPEN_DAY.equals(getIntent().getAction())) {
                welcomeStep = prefs.getInt("welcomeStep", installedFresh() ? 1 : 0);
                if (!prefs.contains("welcomeStep")) prefs.edit().putInt("welcomeStep", welcomeStep).apply();
            }
            for (int id : PlanWidget.ids(this)) PlanWidget.settings(this, id);
        }
        String selection = widgetClass == null ? prefs.getString("url", "") : widgetClass.url;
        if (selection.startsWith(SchoolPlan.BASE + "T/")) school = "T";
        if (selection.startsWith("teacher:")) school = "teacher";
        day = today();
        planWeek = monday(LocalDate.now(SchoolPlan.ZONE));
        if (state != null) {
            day = state.getInt("day", day);
            home = state.getBoolean("home", true);
            settings = state.getBoolean("settings", false);
            planWeek = LocalDate.ofEpochDay(state.getLong("planWeek", planWeek.toEpochDay()));
            welcomePicker = welcomeStep == 1 && state.getBoolean("welcomePicker", false);
            if (welcomePicker && state.containsKey("welcomeClassUrl")) {
                welcomeClass = new SchoolPlan.ClassPlan(state.getString("welcomeClassName", ""),
                        state.getString("welcomeClassUrl", ""));
                if (welcomeClass.url.startsWith(SchoolPlan.BASE + "T/")) school = "T";
                else if (welcomeClass.url.startsWith("teacher:")) school = "teacher";
            }
        }
        if (welcomeStep == 3) settings = true;
        if (!isWidgetConfiguration()) openWidgetPlan(getIntent());
        if (state != null && !home && state.containsKey("previewUrl")) {
            previewClass = new SchoolPlan.ClassPlan(state.getString("previewName", ""), state.getString("previewUrl", ""));
        }
        root = column();
        applyColors();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
    }

    @Override public void onStart() {
        super.onStart();
        if (isFinishing()) return;
        if (isWidgetConfiguration()) {
            if (!picking) showPicker(school);
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener(listener);
        if (widgetPlan != null) widgetPlan.registerOnSharedPreferenceChangeListener(listener);
        boolean starting = firstStart;
        firstStart = false;
        if (!picking) {
            if (welcomePicker) showPicker(school);
            else renderScreen();
        }
        if (starting && previewClass != null) loadPreview();
        if (starting && getIntent().getBooleanExtra("standalonePlan", false)) animateContent();
        if (starting) {
            SchoolNotifications.channels(this);
            RefreshJob.schedule(this);
            if (welcomeStep == 0) checkOnLaunch();
        }
        clock.postDelayed(updateHome, 60000 - System.currentTimeMillis() % 60000);
        boolean classPlan = !prefs.getString("url", "").startsWith("teacher:");
        if (prefs.contains("url") && ((classPlan && !prefs.contains("changes"))
                || System.currentTimeMillis() - prefs.getLong("updated", 0) >= 15 * 60 * 1000L)) {
            RefreshJob.refresh(this);
        }
    }

    private boolean installedFresh() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return freshInstall(info.firstInstallTime, info.lastUpdateTime,
                    prefs.contains("url") || prefs.contains("notificationsAsked"));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    static boolean freshInstall(long firstInstalled, long lastUpdated, boolean previouslyOpened) {
        return firstInstalled > 0 && firstInstalled == lastUpdated && !previouslyOpened;
    }

    private void checkOnLaunch() {
        if (updates == null) return;
        updates.check(false);
        if (!prefs.getBoolean("notificationsAsked", false)) requestNotifications();
    }

    @Override public void onStop() {
        clock.removeCallbacks(updateHome);
        if (prefs != null && !isWidgetConfiguration()) prefs.unregisterOnSharedPreferenceChangeListener(listener);
        if (widgetPlan != null) widgetPlan.unregisterOnSharedPreferenceChangeListener(listener);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (updates != null) updates.resumed();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("day", day);
        state.putBoolean("home", home);
        state.putBoolean("settings", settings);
        if (previewClass != null) {
            state.putString("previewName", previewClass.name);
            state.putString("previewUrl", previewClass.url);
        }
        state.putInt("widgetTheme", widgetTheme);
        state.putBoolean("widgetGlass", widgetGlass);
        state.putInt("widgetGlassOpacity", widgetGlassOpacity);
        state.putBoolean("widgetGlassEdge", widgetGlassEdge);
        state.putBoolean("welcomePicker", welcomePicker);
        if (welcomeClass != null) {
            state.putString("welcomeClassName", welcomeClass.name);
            state.putString("welcomeClassUrl", welcomeClass.url);
        }
        if (widgetClass != null) {
            state.putString("widgetClassName", widgetClass.name);
            state.putString("widgetClassUrl", widgetClass.url);
        }
        state.putLong("planWeek", planWeek == null ? LocalDate.now(SchoolPlan.ZONE).toEpochDay() : planWeek.toEpochDay());
    }

    @Override public void onBackPressed() {
        if (isWidgetConfiguration()) finishConfiguration();
        else if (picking) renderScreen();
        else if (settings) goHome();
        else if (!home) closePlan();
        else super.onBackPressed();
    }

    @Override public void onDestroy() {
        AppMotion.localBack(this, root, this::onBackPressed, false);
        executor.shutdownNow();
        super.onDestroy();
    }

    private static LocalDate monday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1);
    }

    private void showDate(LocalDate date) {
        day = date.getDayOfWeek().getValue() - 1;
        planWeek = monday(date);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!isWidgetConfiguration()) {
            openWidgetPlan(intent);
            if (widgetPlan != null) widgetPlan.registerOnSharedPreferenceChangeListener(listener);
            renderScreen();
        }
    }

    private void openWidgetPlan(Intent intent) {
        if (OPEN_DAY.equals(intent.getAction())) {
            String date = intent.getStringExtra("date");
            if (date == null) return;
            try {
                clearPreview();
                clearWidgetPlan();
                home = false;
                settings = false;
                picking = false;
                LocalDate selected = LocalDate.parse(date);
                showDate(selected.getDayOfWeek().getValue() <= 5 ? selected : PlanDay.display());
            } catch (java.time.format.DateTimeParseException ignored) {}
            return;
        }
        if (!PlanWidget.OPEN_PLAN.equals(intent.getAction())) return;
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (!PlanWidget.owns(this, id)) return;
        clearPreview();
        clearWidgetPlan();
        widgetPlan = PlanWidget.settings(this, id);
        home = false;
        settings = false;
        picking = false;
        showDate(PlanDay.display());
    }

    private void clearWidgetPlan() {
        if (widgetPlan == null) return;
        widgetPlan.unregisterOnSharedPreferenceChangeListener(listener);
        widgetPlan = null;
        getIntent().setAction(Intent.ACTION_MAIN);
    }

    private void goHome() {
        boolean finishedWelcome = welcomeStep != 0;
        if (finishedWelcome) {
            welcomeStep = 0;
            welcomePicker = false;
            welcomeClass = null;
            prefs.edit().putInt("welcomeStep", 0).apply();
        }
        clearPreview();
        clearWidgetPlan();
        settings = false;
        home = true;
        renderHome();
        animateContent();
        if (finishedWelcome) checkOnLaunch();
    }

    private void renderScreen() {
        if (picking) {
            refreshingPicker = false;
            pickerRefresh = null;
            finishRefresh();
        }
        if (welcomeStep == 1 || welcomeStep == 2) renderWelcome();
        else if (settings) renderSettings();
        else if (home) renderHome();
        else renderPlan();
    }

    private void renderWelcome() {
        picking = false;
        welcomePicker = false;
        root.removeAllViews();
        AppMotion.localBack(this, root, this::onBackPressed, false);
        content = scrollContent();
        addTop(content, label("MicekApp", 16, ACCENT, true), 30);
        if (welcomeStep == 1) {
            addTop(content, label("Witaj w MicekApp!", 30, INK, true), 12);
            message(content, "Twój plan lekcji", "Wybierz swoją klasę lub nauczyciela, aby zobaczyć plan zajęć.");
            Button next = button("Wybierz klasę lub nauczyciela", SELECTED, ACCENT);
            next.setOnClickListener(view -> {
                AppMotion.remember(this, root);
                welcomePicker = true;
                showPicker(school.equals("teacher") || school.equals("T") ? school : "LO");
            });
            addTop(content, next, 15);
        } else {
            addTop(content, label("Dostosuj aplikację do siebie", 28, INK, true), 12);
            String name = prefs.getString("name", "");
            boolean teacher = prefs.getString("url", "").startsWith("teacher:");
            message(content, teacher ? "Plan nauczyciela · " + name : "Klasa " + shortName(name),
                    "W ustawieniach dopasujesz wygląd, powiadomienia i widżety.");
            Button settingsButton = button("Przejdź do ustawień", SELECTED, ACCENT);
            settingsButton.setOnClickListener(view -> {
                welcomeStep = 3;
                openSettings();
                prefs.edit().putInt("welcomeStep", 3).apply();
            });
            addTop(content, settingsButton, 15);
        }
        animateContent();
    }

    private SharedPreferences visiblePlan() {
        return widgetPlan == null ? prefs : widgetPlan;
    }

    private boolean visibleTeacher() {
        return (previewClass == null ? visiblePlan().getString("url", "") : previewClass.url).startsWith("teacher:");
    }

    private boolean visibleRoom() {
        return previewClass != null && previewClass.url.startsWith("room:");
    }

    private void clearPreview() {
        previewRequest++;
        previewClass = null;
        previewWeek = null;
        previewChanges = null;
        previewUpdated = 0;
        previewFailed = previewChangesFailed = false;
    }

    private void showPreview(SchoolPlan.ClassPlan entry) {
        clearPreview();
        if (!entry.url.equals(visiblePlan().getString("url", ""))) previewClass = entry;
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        renderScreen();
        if (previewClass != null) loadPreview();
    }

    private void loadPreview() {
        SchoolPlan.ClassPlan entry = previewClass;
        if (entry == null) return;
        int token = ++previewRequest;
        LocalDate weekStart = planWeek;
        if (pull != null && !pull.isRefreshing()) pull.show();
        executor.execute(() -> {
            org.json.JSONArray week = null;
            org.json.JSONObject changes = null;
            boolean teacher = entry.url.startsWith("teacher:");
            boolean room = entry.url.startsWith("room:");
            try {
                String path = teacher ? "teacherId=" + entry.url.substring(8)
                        : room ? "roomId=" + entry.url.substring(5) : "classId=" + LivePlan.classId(entry.name);
                week = LivePlan.schedule(LivePlan.read("schedule?" + path));
                if (!teacher && !room) changes = LivePlan.changes(path.substring(8), weekStart);
            } catch (IOException | JSONException ignored) {
                if (week == null && entry.url.startsWith(SchoolPlan.BASE)) {
                    try { week = SchoolPlan.fetch(entry.url); } catch (IOException ignoredFallback) {}
                }
            }
            org.json.JSONArray result = week;
            org.json.JSONObject modifications = changes;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || previewRequest != token) return;
                previewFailed = result == null;
                previewChangesFailed = !teacher && !room && modifications == null;
                if (result != null) {
                    previewWeek = result;
                    previewUpdated = System.currentTimeMillis();
                }
                if (modifications != null) previewChanges = modifications;
                if (pull != null) pull.finish();
                if (!picking && !home && !settings) renderPlan();
            });
        });
    }

    private void openPlan() {
        startActivity(new Intent(this, MainActivity.class).setAction(OPEN_DAY).putExtra("standalonePlan", true)
                .putExtra("date", PlanDay.display().toString()));
        AppMotion.transition(this);
    }

    private void closePlan() {
        if (getIntent().getBooleanExtra("standalonePlan", false)) {
            finish();
            AppMotion.transition(this);
        } else goHome();
    }

    private void applyColors() {
        AppColors colors = new AppColors(this);
        light = colors.light;
        appGlass = AppColors.appGlass(this);
        BG = colors.background; PANEL = colors.panel; EDGE = colors.border; INK = colors.text;
        MUTED = colors.muted; ACCENT = colors.accent; SELECTED = colors.selected;
        ALERT = colors.warning; GREEN = colors.green;
        root.setBackgroundColor(BG);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }

    private void animateContent() {
        if (content == null) return;
        AppMotion.enter(content);
    }

    private void renderHome() {
        picking = false;
        AppMotion.localBack(this, root, this::onBackPressed, false);
        View previous = root.getChildCount() == 1 ? root.getChildAt(0) : null;
        int scrollY = previous instanceof PullRefresh ? ((PullRefresh) previous).scroll.getScrollY() : 0;
        root.removeAllViews();
        content = scrollContent();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(SchoolPlan.ZONE);
        LocalDate date = PlanDay.display(now);
        boolean today = date.equals(now.toLocalDate());
        String dateLabel = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", new Locale("pl", "PL")));
        String name = prefs.getString("name", "");
        boolean teacher = prefs.getString("url", "").startsWith("teacher:");
        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(label("MicekApp", 22, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button preferences = button("⚙", PANEL, ACCENT);
        preferences.setTextSize(20);
        preferences.setContentDescription("Ustawienia");
        toolbar.addView(preferences, new LinearLayout.LayoutParams(dp(50), dp(48)));
        preferences.setOnClickListener(view -> openSettings());
        addTop(content, toolbar, 8);
        addTop(content, label(dateLabel + (name.isEmpty() ? "" : " · " + (teacher ? name : shortName(name))),
                14, MUTED, false), 16);

        List<LivePlan.Lesson> lessons = LivePlan.day(this, prefs, date);
        java.util.Set<String> periods = new java.util.HashSet<>();
        LivePlan.Lesson current = null;
        LivePlan.Lesson next = null;
        LocalDate actual = now.toLocalDate();
        for (LivePlan.Lesson lesson : today ? lessons : LivePlan.day(this, prefs, actual)) {
            if (PlanDay.active(lesson, actual, now)) { current = lesson; break; }
        }
        for (LivePlan.Lesson lesson : lessons) {
            if (lesson.cancelled || lesson.optional) continue;
            periods.add(lesson.period > 0 ? Integer.toString(lesson.period) : lesson.time());
            if (next == null && (!today || PlanDay.minute(lesson.start) > PlanDay.minute(now))) next = lesson;
        }
        int count = periods.size();
        String unit = count == 1 ? "lekcję" : count % 10 >= 2 && count % 10 <= 4
                && (count % 100 < 12 || count % 100 > 14) ? "lekcje" : "lekcji";
        String dayLabel = PlanDay.label(now.toLocalDate(), date);
        String prefix = today || dayLabel.equals("Jutro") ? dayLabel + " masz " : dayLabel + " · ";
        String greeting = name.isEmpty() ? "Twój plan lekcji"
                : !prefs.contains("week") ? "Plan lekcji" : prefix + count + " " + unit;
        addTop(content, label(greeting, 30, INK, true), 6);
        if (name.isEmpty()) {
            menuButton("Ustaw plan", this::openSettings);
        } else {
            LinearLayout focus = column();
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF364E8F, 0xFF27365F});
            gradient.setCornerRadius(dp(22));
            focus.setBackground(gradient);
            focus.setPadding(dp(18), dp(15), dp(18), dp(16));
            long changes = lessons.stream().filter(lesson -> !lesson.status.isEmpty()).count();
            if (changes > 0) menuButton("Zmiany w planie · " + changes, this::openPlan);
            if (!prefs.contains("week")) {
                focus.addView(label(prefs.getBoolean("error", false) ? "Nie udało się pobrać planu" : "Pobieranie planu…",
                        20, 0xFFF1F4FF, true));
            } else if (current != null) {
                focusLesson(focus, "Teraz · " + current.time(), current, teacher);
            } else if (next != null) {
                focusLesson(focus, (today ? "Następna lekcja" : "Pierwsza lekcja") + " · " + next.start, next, teacher);
            } else {
                focus.addView(label(today && count > 0 ? "Koniec lekcji na dziś" : "Brak lekcji w planie", 22, 0xFFF1F4FF, true));
            }
            addTop(content, focus, 14);
            if (today && current == null && next == null && count > 0) {
                LocalDate following = PlanDay.display(now.withHour(PlanDay.ROLLOVER_HOUR));
                for (LivePlan.Lesson lesson : LivePlan.day(this, prefs, following)) {
                    if (lesson.cancelled || lesson.optional || lesson.start.isEmpty()) continue;
                    addTop(focus, label("Kolejne zajęcia · " + following.format(
                            DateTimeFormatter.ofPattern("EEE d.MM", new Locale("pl", "PL"))) + " o " + lesson.start,
                            14, 0xFFF1F4FF, false), 6);
                    break;
                }
            }
        }
        addTop(content, label("Menu główne", 21, INK, true), 22);
        menuRow("▤", "Plan lekcji", this::openPlan, "↻", "Zastępstwa", () -> section("zastepstwa"));
        menuRow("♫", "Radiowęzeł", () -> section("radio"), "◈", "Spotted", () -> section("spotted"));
        menuRow("%", "Progi ocen", () -> section("progi"), "◷", "Dyżury", () -> section("dyzury"));
        renderAnnouncements(date);
        ScrollView scroll = (ScrollView) content.getParent();
        if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void renderAnnouncements(LocalDate date) {
        if (!date.equals(noticesDate) && !date.equals(loadingNoticesDate)) loadAnnouncements(date);
        if (!date.equals(noticesDate) || notices == null || notices.length() == 0) return;
        addTop(content, label(PlanDay.announcementsLabel(LocalDate.now(SchoolPlan.ZONE), date), 21, INK, true), 22);
        for (int i = 0; i < notices.length(); i++) {
            org.json.JSONObject notice = notices.optJSONObject(i);
            String title = LivePlan.text(notice, "title");
            String text = LivePlan.text(notice, "text");
            if (text.equals(title)) text = "";
            if (title.isEmpty() && text.isEmpty()) continue;
            message(content, title.isEmpty() ? text : title, title.isEmpty() ? "" : text);
        }
    }

    private void loadAnnouncements(LocalDate date) {
        int token = ++noticeRequest;
        loadingNoticesDate = date;
        executor.execute(() -> {
            org.json.JSONArray found = null;
            try {
                org.json.JSONArray events = LivePlan.read("substitutions?date=" + date).optJSONArray("events");
                found = new org.json.JSONArray();
                if (events != null) for (int i = 0; i < events.length(); i++) {
                    org.json.JSONObject event = events.optJSONObject(i);
                    if ("announcement".equals(LivePlan.text(event, "kind"))) found.put(event);
                }
            } catch (IOException | JSONException ignored) {
            }
            org.json.JSONArray downloaded = found;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || token != noticeRequest) return;
                if (!date.equals(noticesDate) || downloaded != null) notices = downloaded;
                noticesDate = date;
                loadingNoticesDate = null;
                refreshingNotices = false;
                finishRefresh();
                if (welcomeStep == 0 && home && !settings && !picking) renderHome();
            });
        });
    }

    private void refreshPage() {
        if (picking && pickerRefresh != null) {
            refreshingPicker = true;
            pickerRefresh.run();
        } else if (!home && !settings && previewClass != null) {
            loadPreview();
        } else if (settings || welcomeStep != 0) {
            PullRefresh current = pull;
            current.postDelayed(current::finish, 300);
        } else {
            refreshingPlan = visiblePlan().contains("url") && RefreshJob.refresh(this);
            if (home) {
                refreshingNotices = true;
                loadAnnouncements(PlanDay.display());
            }
            if (refreshingPlan) clock.postDelayed(refreshTimeout, 35000);
            if (!refreshingPlan && !refreshingNotices) {
                PullRefresh current = pull;
                current.postDelayed(current::finish, 300);
            }
        }
    }

    private final Runnable refreshTimeout = () -> {
        refreshingPlan = false;
        finishRefresh();
    };

    private void finishRefresh() {
        if (!refreshingPlan) clock.removeCallbacks(refreshTimeout);
        if (pull != null && !refreshingPlan && !refreshingNotices && !refreshingPicker && pull.isRefreshing()) {
            pull.finish();
        }
    }

    private void focusLesson(LinearLayout card, String caption, LivePlan.Lesson lesson, boolean teacherPlan) {
        card.addView(label(caption, 13, 0xFFD9E0FF, true));
        addTop(card, label(lesson.title, 22, 0xFFF1F4FF, true), 5);
        String person = teacherPlan ? lesson.className : lesson.teacher;
        String detail = lesson.room.isEmpty() ? "" : "Sala " + lesson.room;
        if (!person.isEmpty()) detail += (detail.isEmpty() ? "" : " · ") + person;
        if (!detail.isEmpty()) addTop(card, label(detail, 14, 0xFFD9E0FF, false), 5);
    }

    private void menuRow(String firstIcon, String first, Runnable firstAction,
                         String secondIcon, String second, Runnable secondAction) {
        LinearLayout tiles = row();
        addTop(content, tiles, 9);
        addTile(tiles, firstIcon, first, firstAction);
        addTile(tiles, secondIcon, second, secondAction);
    }

    private void addTile(LinearLayout tiles, String icon, String title, Runnable action) {
        LinearLayout tile = column();
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), surface(PANEL, 16), null));
        tile.setPadding(dp(14), dp(10), dp(12), dp(12));
        tile.addView(label(icon, 23, ACCENT, true));
        addTop(tile, label(title, 15, INK, true), 4);
        tile.setContentDescription(title);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        if (tiles.getChildCount() > 0) size.setMarginStart(dp(9));
        tiles.addView(tile, size);
    }

    private void openSettings() {
        AppMotion.remember(this, root);
        renderSettings();
        AppMotion.enter(root);
    }

    private void renderSettings() {
        clearWidgetPlan();
        settings = true;
        picking = false;
        AppMotion.localBack(this, root, this::onBackPressed, true);
        root.removeAllViews();
        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(14), dp(8));
        Button back = button("‹", PANEL, INK);
        back.setContentDescription("Wróć");
        back.setOnClickListener(view -> goHome());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label("Ustawienia", 25, INK, true);
        title.setPadding(dp(10), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar);
        content = scrollContent();
        int current = AppColors.appMode(this);
        GlassSettings appEffect = GlassSettings.read(prefs, "appGlass", current);
        addTop(content, label("Motyw aplikacji", 16, INK, true), 15);
        themeChoices(content, () -> AppColors.appMode(this), choice -> {
            SharedPreferences.Editor edit = prefs.edit().putInt("appTheme", choice);
            appEffect.save(edit, "appGlass");
            edit.apply();
            applyColors();
            renderSettings();
        });
        android.widget.Switch glass = glassSwitch();
        updateGlass(glass, current, AppColors.appGlass(this));
        glass.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("appGlass", enabled).putInt("appGlassOpacity", appEffect.opacity)
                    .putBoolean("appGlassEdge", appEffect.edge).apply();
            applyColors();
            renderSettings();
        });
        addTop(content, glass, 4);
        if (AppColors.appGlass(this)) {
            LinearLayout options = column();
            addTop(content, options, 2);
            glassControls(options, appEffect.opacity, appEffect.edge,
                    value -> {
                        prefs.edit().putInt("appGlassOpacity", value).apply();
                        renderSettings();
                    }, edge -> {
                        prefs.edit().putBoolean("appGlassEdge", edge).apply();
                        renderSettings();
                    });
        }
        addTop(content, label("Personalizacja planu lekcji", 16, INK, true), 18);
        String name = prefs.getString("name", "");
        if (!name.isEmpty()) addTop(content, label("Wybrany plan · " + name, 15, MUTED, false), 12);
        menuButton("Zmień wybór klasy", () -> showPicker(school.equals("room") ? "LO" : school));
        android.widget.Switch religion = new android.widget.Switch(this);
        religion.setText("Ukryj religię");
        religion.setTextColor(INK);
        religion.setTextSize(16);
        religion.setMinHeight(dp(56));
        religion.setPadding(dp(14), 0, dp(14), 0);
        religion.setBackground(surface(PANEL, 16));
        religion.setChecked(prefs.getBoolean("hideReligion", false));
        addTop(content, religion, 12);
        addTop(content, label("Dotyczy planu, ekranu głównego i widżetów.", 13, MUTED, false), 6);
        religion.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("hideReligion", enabled).apply();
            PlanWidget.render(this);
        });
        addTop(content, label("Język obcy", 16, INK, true), 16);
        addSegments(content, new String[]{"Brak", "Niemiecki", "Rosyjski"},
                () -> prefs.getInt("languageChoice", 0), choice -> {
                    prefs.edit().putInt("languageChoice", choice).apply();
                    PlanWidget.render(this);
                });
        android.widget.Switch performance = new android.widget.Switch(this);
        performance.setText("Tryb wydajności");
        performance.setTextColor(INK);
        performance.setTextSize(16);
        performance.setMinHeight(dp(56));
        performance.setPadding(dp(14), 0, dp(14), 0);
        performance.setBackground(surface(PANEL, 16));
        performance.setChecked(prefs.getBoolean("performanceMode", false));
        addTop(content, performance, 12);
        addTop(content, label("Ogranicza animacje i zużycie pamięci przez okładki.", 13, MUTED, false), 6);
        performance.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean("performanceMode", enabled).apply();
            AppMotion.localBack(this, root, this::onBackPressed, true);
        });
        addTop(content, label("Odświeżanie danych", 16, INK, true), 18);
        addSegments(content, new String[]{"15 min", "30 min", "45 min", "60 min"},
                () -> prefs.getInt("refreshMinutes", 15) / 15 - 1, choice -> {
                    prefs.edit().putInt("refreshMinutes", (choice + 1) * 15).apply();
                    RefreshJob.schedule(this);
                });
        addTop(content, label("Powiadomienia", 16, INK, true), 18);
        if (!SchoolNotifications.allowed(this)) menuButton("Włącz powiadomienia", this::requestNotifications);
        notificationSwitch("Nowe posty na Spotted", SchoolNotifications.POSTS);
        notificationSwitch("Komentarze do obserwowanych postów", SchoolNotifications.COMMENTS);
        notificationSwitch("Zmiany w moim planie", SchoolNotifications.PLAN);
        menuButton("Dodaj widżet", this::pinWidget);
        if (updates != null) menuButton("Sprawdź aktualizacje", () -> updates.check(true));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            addTop(content, label("Aktualna wersja · " + version, 13, MUTED, false), 12);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        if (welcomeStep == 3) {
            LinearLayout footer = column();
            footer.setPadding(dp(20), dp(8), dp(20), dp(12));
            Button done = button("Przejdź do ekranu głównego", SELECTED, ACCENT);
            done.setOnClickListener(view -> goHome());
            footer.addView(done);
            root.addView(footer);
        }
    }

    private void notificationSwitch(String name, String channel) {
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setText(name);
        toggle.setTextColor(INK);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(SchoolNotifications.enabled(this, channel));
        addTop(content, toggle, 4);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean("notify_" + channel, checked).apply();
            if (checked && !SchoolNotifications.allowed(this)) requestNotifications();
            else if (checked) RefreshJob.refresh(this);
        });
    }

    private void requestNotifications() {
        boolean asked = prefs.getBoolean("notificationsAsked", false);
        prefs.edit().putBoolean("notificationsAsked", true).apply();
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                && (!asked || shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 51);
        } else if (asked && !SchoolNotifications.allowed(this)) {
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 51 && SchoolNotifications.allowed(this)) RefreshJob.refresh(this);
    }

    private void menuButton(String name, Runnable action) {
        Button item = button(name + "  ›", PANEL, INK);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        item.setPadding(dp(15), 0, dp(12), 0);
        addTop(content, item, 8);
        item.setOnClickListener(view -> action.run());
    }

    private void section(String path) {
        if (path.equals("zastepstwa")) {
            startActivity(new Intent(this, SubstitutionsActivity.class));
        } else {
            startActivity(new Intent(this, SectionActivity.class).putExtra("section", path));
        }
        AppMotion.transition(this);
    }

    private void renderPlan() {
        picking = false;
        previewPicking = false;
        AppMotion.localBack(this, root, this::onBackPressed, !getIntent().getBooleanExtra("standalonePlan", false));
        root.removeAllViews();
        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(6), dp(14), dp(8));
        Button back = button("‹", PANEL, INK);
        back.setContentDescription("Wróć");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(view -> closePlan());
        TextView title = label("Plan lekcji", 25, INK, true);
        title.setPadding(dp(10), 0, 0, 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar);
        LinearLayout header = column();
        header.setPadding(dp(20), 0, dp(20), dp(14));
        root.addView(header);

        String name = previewClass == null ? visiblePlan().getString("name", "") : previewClass.name;
        boolean teacher = visibleTeacher();
        LinearLayout actions = row();
        addTop(header, actions, 6);
        Button choose = button(name.isEmpty() ? "Wybierz plan"
                : (visibleRoom() ? "Sala " + name : teacher ? name : shortName(name)) + "  ▾", SELECTED, ACCENT);
        actions.addView(choose, new LinearLayout.LayoutParams(0, dp(48), 1));
        choose.setOnClickListener(view -> {
            AppMotion.remember(this, root);
            previewPicking = true;
            String url = previewClass == null ? visiblePlan().getString("url", "") : previewClass.url;
            showPicker(url.startsWith("room:") ? "room" : url.startsWith("teacher:") ? "teacher"
                    : url.startsWith(SchoolPlan.BASE + "T/") ? "T" : "LO");
        });
        Button refresh = button("↻", PANEL, ACCENT);
        refresh.setTextSize(23);
        refresh.setContentDescription("Odśwież plan");
        LinearLayout.LayoutParams refreshSize = new LinearLayout.LayoutParams(dp(52), dp(48));
        refreshSize.setMarginStart(dp(8));
        actions.addView(refresh, refreshSize);
        refresh.setEnabled(!name.isEmpty());
        refresh.setOnClickListener(view -> {
            if (previewClass != null) { loadPreview(); return; }
            boolean queued = RefreshJob.refresh(this);
            android.widget.Toast.makeText(this, queued ? "Odświeżanie planu…" : "Nie udało się uruchomić odświeżania",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        if (!name.isEmpty()) {
            LinearLayout tabs = row();
            addTop(header, tabs, 12);
            for (int index = 0; index < SHORT_DAYS.length; index++) {
                int selectedDay = index;
                Button tab = button(SHORT_DAYS[index], day == index ? SELECTED : PANEL, day == index ? ACCENT : MUTED);
                tab.setContentDescription(SchoolPlan.DAYS[index] + (today() == index ? ", dziś" : ""));
                tab.setSelected(day == index);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1);
                if (index > 0) size.setMarginStart(dp(5));
                tabs.addView(tab, size);
                tab.setOnClickListener(view -> { day = selectedDay; renderPlan(); });
            }
        }

        content = scrollContent();
        if (previewClass != null) menuButton("Wróć do zapisanego planu", () -> { clearPreview(); renderPlan(); });
        if (name.isEmpty()) {
            message(content, "Wybierz plan", "Wybierz klasę, nauczyciela lub salę, aby zobaczyć lekcje.");
        } else {
            renderLessons();
        }

        Button widget = button("Dodaj widżet", PANEL, ACCENT);
        addTop(content, widget, 18);
        widget.setOnClickListener(view -> pinWidget());
    }

    private void renderLessons() {
        SharedPreferences prefs = visiblePlan();
        String heading = day < SHORT_DAYS.length ? SchoolPlan.DAYS[day] : "Dzisiaj";
        if (day == today() && day < SHORT_DAYS.length) heading += " · dziś";
        content.addView(label(heading, 22, INK, true));
        long updated = previewClass == null ? prefs.getLong("updated", 0) : previewUpdated;
        if (updated != 0) {
            String time = Instant.ofEpochMilli(updated).atZone(SchoolPlan.ZONE)
                    .format(DateTimeFormatter.ofPattern("d.MM, HH:mm"));
            addTop(content, label("Ostatnie pobranie planu · " + time, 13, MUTED, false), 4);
        }
        boolean failed = previewClass == null ? prefs.getBoolean("error", false) : previewFailed;
        if (failed) addTop(content, label("Nie udało się odświeżyć planu.", 14, ALERT, false), 8);
        LocalDate date = planWeek.plusDays(day);
        List<LivePlan.Lesson> lessons = visibleRoom() ? LivePlan.merge(previewWeek == null ? null : previewWeek.optJSONArray(day), null)
                : previewClass == null ? LivePlan.day(this, prefs, date)
                : LivePlan.day(this, previewWeek, previewChanges, date, !visibleTeacher());
        long changes = lessons.stream().filter(lesson -> !lesson.status.isEmpty()).count();
        if (previewClass == null ? prefs.getBoolean("changesError", false) : previewChangesFailed) {
            addTop(content, label("Zastępstwa · brak aktualizacji", 14, ALERT, true), 8);
        }
        if (changes > 0) addTop(content, label("Zmiany w planie · " + changes, 14, ALERT, true), 8);
        if (lessons.isEmpty()) {
            if (previewClass == null ? prefs.contains("week") : previewWeek != null) message(content, "Brak lekcji w planie", "");
            else if (!failed) message(content, "Pobieranie planu…", "");
            return;
        }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(SchoolPlan.ZONE);
        for (LivePlan.Lesson lesson : lessons) addTop(content, lessonCard(lesson, PlanDay.active(lesson, date, now)), 8);
    }

    private View lessonCard(LivePlan.Lesson lesson, boolean active) {
        int accent = lesson.released ? GREEN : lesson.status.isEmpty() ? ACCENT : ALERT;
        LinearLayout card = column();
        card.setBackground(surface(active ? SELECTED : lesson.released ? (light ? 0xFFE1F4E8 : 0xFF172B27)
                : lesson.status.isEmpty() ? PANEL : (light ? 0xFFFFF0DD : 0xFF2B2530), 15));
        card.setPadding(dp(14), dp(11), dp(14), dp(12));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        String time = lesson.period > 0 ? lesson.period + ".  ·  " + lesson.time() : lesson.time();
        if (active) time = "Teraz  ·  " + time;
        top.addView(label(time, 13, accent, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!visibleRoom() && !lesson.room.isEmpty() && !lesson.cancelled) {
            TextView room = label("Sala " + lesson.room, 13, INK, true);
            room.setBackground(surface(BG, 8));
            room.setPadding(dp(9), dp(4), dp(9), dp(4));
            top.addView(room);
        }
        card.addView(top);
        if (!lesson.status.isEmpty()) addTop(card, label(lesson.status, 14, accent, true), 5);
        TextView title = label(lesson.title, 17, lesson.cancelled ? MUTED : INK, true);
        if (lesson.cancelled) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        addTop(card, title, 4);
        boolean teacherPlan = visibleTeacher();
        String detail = teacherPlan || visibleRoom() ? lesson.className : lesson.cancelled ? "" : lesson.teacher;
        if (visibleRoom() && !lesson.teacher.isEmpty()) detail += (detail.isEmpty() ? "" : "  ·  ") + lesson.teacher;
        if (!lesson.group.isEmpty()) detail += (detail.isEmpty() ? "" : "  ·  ") + lesson.group;
        if (lesson.optional) detail += (detail.isEmpty() ? "" : "  ·  ") + "nieobowiązkowa";
        if (!detail.isEmpty()) addTop(card, label(detail, 13, MUTED, false), 4);
        if (!lesson.previous.isEmpty()) addTop(card, label("Zamiast: " + lesson.previous, 13, MUTED, false), 3);
        if (!lesson.note.isEmpty()) addTop(card, label(lesson.note, 13, MUTED, false), 3);
        return card;
    }

    private void showPicker(String kind) {
        picking = true;
        AppMotion.localBack(this, root, this::onBackPressed, true);
        school = kind;
        classes = null;
        int requested = ++request;
        root.removeAllViews();
        LinearLayout header = column();
        header.setPadding(dp(20), dp(16), dp(20), dp(12));
        root.addView(header);
        if (!isWidgetConfiguration()) {
            Button back = button(welcomePicker ? "‹  Witaj" : previewPicking ? "‹  Plan lekcji" : "‹  Ustawienia", PANEL, ACCENT);
            header.addView(back);
            back.setOnClickListener(view -> renderScreen());
        }
        String title = isWidgetConfiguration() ? "Ustawienia widżetu" : kind.equals("room") ? "Wybierz salę"
                : kind.equals("teacher") ? "Nauczyciele" : "Wybierz klasę";
        addTop(header, label(title, 27, INK, true), isWidgetConfiguration() ? 0 : 14);
        if (isWidgetConfiguration()) addWidgetThemes(header);
        LinearLayout tabs = row();
        addTop(header, tabs, 12);
        String[] options = isWidgetConfiguration() ? new String[]{"LO", "T"}
                : previewPicking ? new String[]{"LO", "T", "teacher", "room"} : new String[]{"LO", "T", "teacher"};
        for (String option : options) {
            boolean selected = option.equals(kind);
            Button tab = button(option.equals("LO") ? "Liceum" : option.equals("T") ? "Technikum"
                            : option.equals("room") ? "Sale" : options.length == 4 ? "Nauczyciel" : "Nauczyciele",
                    selected ? SELECTED : PANEL, selected ? ACCENT : MUTED);
            if (options.length == 4) { tab.setPadding(dp(2), 0, dp(2), 0); tab.setTextSize(12); }
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (!option.equals("LO")) size.setMarginStart(dp(6));
            tabs.addView(tab, size);
            tab.setSelected(selected);
            tab.setOnClickListener(view -> { if (!selected) showPicker(option); });
        }

        EditText search = new EditText(this);
        search.setSingleLine();
        search.setTextSize(16);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setHint(kind.equals("room") ? "Szukaj sali" : kind.equals("teacher") ? "Szukaj nauczyciela" : "Szukaj klasy lub profilu");
        search.setBackground(surface(PANEL, 13));
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        addTop(header, search, 10);
        header.setFocusableInTouchMode(true);
        header.requestFocus();

        LinearLayout results = scrollContent();
        results.addView(label(kind.equals("room") ? "Pobieranie sal…"
                : kind.equals("teacher") ? "Pobieranie listy nauczycieli…" : "Pobieranie listy klas…", 15, MUTED, false));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (classes != null) renderClasses(results, s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        if (isWidgetConfiguration()) addWidgetFooter();
        else if (welcomePicker) {
            LinearLayout footer = column();
            footer.setPadding(dp(20), dp(8), dp(20), dp(12));
            welcomeConfirm = button("Zatwierdź wybór planu", SELECTED, ACCENT);
            welcomeConfirm.setEnabled(welcomeClass != null);
            welcomeConfirm.setAlpha(welcomeClass == null ? 0.5f : 1);
            welcomeConfirm.setOnClickListener(view -> { if (welcomeClass != null) select(welcomeClass); });
            footer.addView(welcomeConfirm);
            root.addView(footer);
        }
        pickerRefresh = () -> loadClasses(kind, requested, results, search);
        pickerRefresh.run();
    }

    private void loadClasses(String kind, int requested, LinearLayout results, EditText search) {
        executor.execute(() -> {
            List<SchoolPlan.ClassPlan> loaded = null;
            try {
                loaded = kind.equals("room") ? LivePlan.rooms()
                        : kind.equals("teacher") ? LivePlan.teachers() : SchoolPlan.classes(kind);
            } catch (IOException | JSONException ignored) {
            }
            List<SchoolPlan.ClassPlan> downloaded = loaded;
            runOnUiThread(() -> {
                if (isDestroyed() || !picking || request != requested) return;
                refreshingPicker = false;
                finishRefresh();
                classes = downloaded;
                if (classes != null) {
                    renderClasses(results, search.getText().toString());
                } else {
                    results.removeAllViews();
                    results.addView(label(kind.equals("room") ? "Nie udało się pobrać sal."
                            : kind.equals("teacher") ? "Nie udało się pobrać nauczycieli."
                            : "Nie udało się pobrać listy klas.", 16, ALERT, false));
                    Button retry = button("Spróbuj ponownie", PANEL, ACCENT);
                    addTop(results, retry, 10);
                    retry.setOnClickListener(view -> loadClasses(kind, requested, results, search));
                }
            });
        });
    }

    private void addWidgetThemes(LinearLayout header) {
        android.widget.Switch glass = glassSwitch();
        LinearLayout controls = column();
        Runnable update = () -> {
            setWidgetGlass(glass, controls);
            widgetGlassControls(controls);
        };
        themeChoices(header, () -> widgetTheme, mode -> {
            widgetTheme = mode;
            update.run();
        });
        update.run();
        addTop(header, glass, 4);
        addTop(header, controls, 0);
    }

    private void themeChoices(LinearLayout parent, java.util.function.IntSupplier selected,
                              java.util.function.IntConsumer select) {
        addSegments(parent, new String[]{"AMOLED", "Ciemny", "Jasny"}, selected, select);
    }

    private void addSegments(LinearLayout parent, String[] labels, java.util.function.IntSupplier selected,
                             java.util.function.IntConsumer select) {
        LinearLayout themes = row();
        themes.setBackground(surface(PANEL, 15));
        themes.setPadding(dp(4), dp(4), dp(4), dp(4));
        Button[] items = new Button[labels.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = button(labels[i], PANEL, MUTED);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (i > 0) size.setMarginStart(dp(5));
            themes.addView(items[i], size);
        }
        Runnable update = () -> {
            for (int i = 0; i < items.length; i++) themeSegment(items[i], selected.getAsInt() == i);
        };
        for (int i = 0; i < items.length; i++) {
            int mode = i;
            items[i].setOnClickListener(view -> { select.accept(mode); update.run(); });
        }
        update.run();
        addTop(parent, themes, 10);
    }

    private android.widget.Switch glassSwitch() {
        android.widget.Switch glass = new android.widget.Switch(this);
        glass.setText("Liquid Glass");
        glass.setTextColor(INK);
        glass.setMinHeight(dp(44));
        return glass;
    }

    private void updateGlass(android.widget.Switch glass, int mode, boolean checked) {
        boolean available = mode != AppColors.AMOLED;
        glass.setEnabled(available);
        glass.setAlpha(available ? 1f : 0.45f);
        glass.setChecked(available && checked);
    }

    private void setWidgetGlass(android.widget.Switch glass, LinearLayout controls) {
        glass.setOnCheckedChangeListener(null);
        updateGlass(glass, widgetTheme, widgetGlass);
        glass.setOnCheckedChangeListener((view, checked) -> {
            widgetGlass = checked;
            widgetGlassControls(controls);
        });
    }

    private void widgetGlassControls(LinearLayout controls) {
        controls.removeAllViews();
        boolean enabled = widgetTheme != AppColors.AMOLED && widgetGlass;
        controls.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) {
            glassControls(controls, widgetGlassOpacity, widgetGlassEdge,
                    value -> widgetGlassOpacity = value, edge -> widgetGlassEdge = edge);
        }
    }

    private void glassControls(LinearLayout parent, int opacity, boolean edge, java.util.function.IntConsumer changeOpacity,
                               java.util.function.Consumer<Boolean> changeEdge) {
        TextView caption = label("Krycie tła · " + opacity + "%", 13, MUTED, false);
        addTop(parent, caption, 0);
        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setContentDescription("Krycie tła Liquid Glass");
        slider.setMin(65);
        slider.setMax(100);
        slider.setProgress(opacity);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(EDGE));
        slider.setSplitTrack(false);
        slider.setPadding(dp(18), 0, dp(18), 0);
        slider.setMinHeight(dp(44));
        GradientDrawable thumb = surface(ACCENT, 10);
        thumb.setSize(dp(20), dp(20));
        slider.setThumb(thumb);
        slider.setBackground(surface(PANEL, 14));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seek, int value, boolean user) {
                caption.setText("Krycie tła · " + value + "%");
                if (user && !seek.isPressed()) changeOpacity.accept(value);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seek) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seek) { changeOpacity.accept(seek.getProgress()); }
        });
        addTop(parent, slider, 1);
        android.widget.Switch outline = new android.widget.Switch(this);
        outline.setText("Wyraźniejsza krawędź");
        outline.setTextColor(INK);
        outline.setChecked(edge);
        outline.setOnCheckedChangeListener((view, checked) -> changeEdge.accept(checked));
        addTop(parent, outline, 0);
    }

    private void themeSegment(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? ACCENT : MUTED);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x333F7BFF),
                surface(selected ? SELECTED : PANEL, 11), null));
    }

    private void addWidgetFooter() {
        LinearLayout footer = column();
        footer.setPadding(dp(20), dp(8), dp(20), dp(10));
        footer.setBackgroundColor(BG);
        widgetSave = button("Zapisz zmiany", SELECTED, ACCENT);
        widgetSave.setEnabled(widgetClass != null);
        widgetSave.setAlpha(widgetClass == null ? 0.5f : 1f);
        widgetSave.setOnClickListener(view -> { if (widgetClass != null) select(widgetClass); });
        addTop(footer, widgetSave, 0);
        Button cancel = button("Anuluj", PANEL, INK);
        cancel.setOnClickListener(view -> finishConfiguration());
        addTop(footer, cancel, 6);
        root.addView(footer);
    }

    private void renderClasses(LinearLayout results, String query) {
        results.removeAllViews();
        String filter = query.trim().toLowerCase(Locale.ROOT);
        for (SchoolPlan.ClassPlan entry : classes) {
            if (!entry.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
            String[] parts = entry.name.split("\\s+", 2);
            String text = school.equals("teacher") ? entry.name : parts.length == 2 ? parts[0] + "  ·  " + parts[1] : parts[0];
            boolean selected = isWidgetConfiguration() ? widgetClass != null && entry.url.equals(widgetClass.url)
                    : welcomePicker ? welcomeClass != null && entry.url.equals(welcomeClass.url)
                    : entry.url.equals(previewPicking
                            ? (previewClass == null ? visiblePlan().getString("url", "") : previewClass.url)
                            : prefs.getString("url", ""));
            Button item = button(text, selected ? SELECTED : PANEL, selected ? ACCENT : INK);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setSelected(selected);
            addTop(results, item, results.getChildCount() == 0 ? 0 : 7);
            item.setOnClickListener(view -> {
                if (isWidgetConfiguration()) {
                    widgetClass = entry;
                    widgetSave.setEnabled(true);
                    widgetSave.setAlpha(1f);
                    renderClasses(results, query);
                } else if (welcomePicker) {
                    welcomeClass = entry;
                    welcomeConfirm.setEnabled(true);
                    welcomeConfirm.setAlpha(1f);
                    renderClasses(results, query);
                } else if (previewPicking) showPreview(entry);
                else select(entry);
            });
        }
        if (results.getChildCount() == 0) results.addView(label(school.equals("room") ? "Brak pasujących sal." : school.equals("teacher")
                ? "Brak pasujących nauczycieli." : "Brak pasujących klas.", 16, MUTED, false));
    }

    private void select(SchoolPlan.ClassPlan entry) {
        if (isWidgetConfiguration() && !PlanWidget.owns(this, widgetId)) {
            finishConfiguration();
            return;
        }
        SharedPreferences.Editor edit = prefs.edit().putString("name", entry.name).putString("url", entry.url);
        if (welcomePicker) {
            welcomePicker = false;
            welcomeStep = 2;
            edit.putInt("welcomeStep", 2);
        }
        if (isWidgetConfiguration()) edit.putInt("widgetTheme", widgetTheme)
                .putBoolean("glass", widgetGlass).putInt("glassOpacity", widgetGlassOpacity)
                .putBoolean("glassEdge", widgetGlassEdge).putLong("widgetConfiguredAt", System.currentTimeMillis());
        if (!entry.url.equals(prefs.getString("url", ""))) {
            edit.remove("week").remove("updated").remove("error").remove("liveClassId")
                    .remove("changes").remove("changesError").remove("teacherAlerts");
        }
        edit.apply();
        if (isWidgetConfiguration()) {
            saveWidgetConfiguration();
            return;
        }
        android.view.inputmethod.InputMethodManager keyboard =
                getSystemService(android.view.inputmethod.InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        showDate(PlanDay.display());
        renderScreen();
        RefreshJob.refresh(this);
    }

    protected boolean isWidgetConfiguration() {
        return false;
    }

    private void saveWidgetConfiguration() {
        PlanWidget.render(this);
        RefreshJob.schedule(this);
        RefreshJob.refresh(this);
        setResult(RESULT_OK, widgetResult());
        finishConfiguration();
    }

    private void finishConfiguration() {
        if (getIntent().getBooleanExtra("fromWidget", false)) finishAndRemoveTask();
        else finish();
    }

    private Intent widgetResult() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }

    private void pinWidget() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout panel = column();
        panel.setBackground(surface(PANEL, 23));
        panel.setPadding(dp(18), dp(16), dp(18), dp(18));
        addTop(panel, label("Wybierz widżet", 21, INK, true), 0);
        Button plan = button("Plan dnia", SELECTED, ACCENT);
        plan.setOnClickListener(view -> { dialog.dismiss(); pinWidget(PlanWidget.class); });
        addTop(panel, plan, 14);
        Button next = button("Najbliższa lekcja", SELECTED, ACCENT);
        next.setOnClickListener(view -> { dialog.dismiss(); pinWidget(NextLessonWidget.class); });
        addTop(panel, next, 8);
        Button cancel = button("Anuluj", PANEL, INK);
        cancel.setOnClickListener(view -> dialog.dismiss());
        addTop(panel, cancel, 10);
        dialog.setContentView(panel);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min(dp(430), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            AppMotion.enter(panel);
        }
    }

    void confirmDialog(String title, String badge, String detail, String confirm, String cancel, Runnable action) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout panel = column();
        panel.setBackground(surface(PANEL, 23));
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        if (!badge.isEmpty()) {
            TextView version = label(badge, 13, ACCENT, true);
            version.setBackground(surface(SELECTED, 11));
            version.setPadding(dp(10), dp(6), dp(10), dp(6));
            panel.addView(version, new LinearLayout.LayoutParams(-2, -2));
        }
        addTop(panel, label(title, 22, INK, true), badge.isEmpty() ? 0 : 10);
        addTop(panel, label(detail, 15, MUTED, false), 8);
        LinearLayout buttons = row();
        Button later = button(cancel, PANEL, INK);
        later.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(later, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button accept = button(confirm, SELECTED, ACCENT);
        accept.setOnClickListener(view -> { dialog.dismiss(); action.run(); });
        LinearLayout.LayoutParams primary = new LinearLayout.LayoutParams(0, dp(48), 2);
        primary.setMarginStart(dp(8));
        buttons.addView(accept, primary);
        addTop(panel, buttons, 18);
        dialog.setContentView(panel);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(Math.min(dp(430), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            if (AppMotion.reduced(this)) dialog.getWindow().setWindowAnimations(0);
            AppMotion.enter(panel);
        }
    }

    private void pinWidget(Class<?> provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager.isRequestPinAppWidgetSupported() &&
                manager.requestPinAppWidget(new ComponentName(this, provider), null, null)) return;
        new android.app.AlertDialog.Builder(this).setMessage(
                getString(R.string.widget_manual_add, provider == PlanWidget.class
                        ? getString(R.string.app_name) : "Najbliższa lekcja"))
                .setPositiveButton("OK", null).show();
    }

    private int today() {
        return LocalDate.now(SchoolPlan.ZONE).getDayOfWeek().getValue() - 1;
    }

    private String shortName(String name) {
        return name.split("\\s+", 2)[0];
    }

    private void message(LinearLayout parent, String title, String description) {
        LinearLayout card = column();
        card.setBackground(surface(PANEL, 15));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(label(title, 17, INK, true));
        if (!description.isEmpty()) addTop(card, label(description, 14, MUTED, false), 5);
        addTop(parent, card, 12);
    }

    private LinearLayout scrollContent() {
        pull = new PullRefresh(this, PANEL, ACCENT, this::refreshPage);
        ScrollView scroll = pull.scroll;
        root.addView(pull, new LinearLayout.LayoutParams(-1, 0, 1));
        if (refreshingPlan || refreshingNotices || refreshingPicker) pull.show();
        LinearLayout list = column();
        list.setPadding(dp(20), dp(6), dp(20), dp(24));
        scroll.addView(list);
        return list;
    }

    private LinearLayout column() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private LinearLayout row() {
        return new LinearLayout(this);
    }

    private void addTop(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(gap);
        parent.addView(child, size);
    }

    private GradientDrawable surface(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), EDGE);
        if (appGlass && fill == PANEL) AppColors.glassPanel(this, shape, light, dp(1));
        else shape.setColor(fill);
        return shape;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text, int fill, int ink) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(ink);
        view.setAllCaps(false);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), surface(fill, 13), null));
        view.setStateListAnimator(null);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
