package pl.micekwidget;

import android.app.PendingIntent;
import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;

public class PlanWidget extends AppWidgetProvider {
    private static final String REFRESH = "pl.micekwidget.REFRESH";
    private static final String CLOCK = "pl.micekwidget.CLOCK";
    static final String OPEN_PLAN = "pl.micekwidget.OPEN_PLAN";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context);
        RefreshJob.schedule(context);
        RefreshJob.refresh(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
                                                        android.os.Bundle options) {
        render(context);
    }

    @Override public void onDisabled(Context context) {
        if (ids(context).length != 0) return;
        context.getSystemService(AlarmManager.class).cancel(clock(context));
    }

    @Override public void onDeleted(Context context, int[] ids) {
        for (int id : ids) context.deleteSharedPreferences(settingsName(id));
    }

    static String settingsName(int id) {
        return "widget_" + id;
    }

    static int[] ids(Context context) {
        int[] plan = ids(context, PlanWidget.class);
        int[] compact = ids(context, NextLessonWidget.class);
        int[] all = java.util.Arrays.copyOf(plan, plan.length + compact.length);
        System.arraycopy(compact, 0, all, plan.length, compact.length);
        return all;
    }

    private static int[] ids(Context context, Class<?> provider) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, provider));
    }

    static boolean owns(Context context, int id) {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
        return info != null && (new ComponentName(context, PlanWidget.class).equals(info.provider)
                || new ComponentName(context, NextLessonWidget.class).equals(info.provider));
    }

    static synchronized SharedPreferences settings(Context context, int id) {
        SharedPreferences settings = context.getSharedPreferences(settingsName(id), Context.MODE_PRIVATE);
        if (settings.contains("name")) return settings;
        SharedPreferences source = SchoolPlan.prefs(context);
        long newest = -1;
        for (int other : ids(context)) {
            if (other == id) continue;
            SharedPreferences existing = context.getSharedPreferences(settingsName(other), Context.MODE_PRIVATE);
            if (!existing.getString("url", "").startsWith(SchoolPlan.BASE)) continue;
            long saved = existing.getLong("widgetConfiguredAt", 0);
            if (saved <= newest) continue;
            source = existing;
            newest = saved;
        }
        String url = source.getString("url", "");
        SharedPreferences.Editor edit = settings.edit().putString("name", "");
        if (url.startsWith(SchoolPlan.BASE)) {
            edit.putString("url", url).putString("name", source.getString("name", ""));
            if (source.contains("liveClassId")) edit.putString("liveClassId", source.getString("liveClassId", ""));
            if (source.contains("week")) edit.putString("week", source.getString("week", ""));
            if (source.contains("changes")) edit.putString("changes", source.getString("changes", ""));
            edit.putLong("updated", source.getLong("updated", 0));
        }
        if (newest >= 0) {
            int theme = themeMode(source);
            edit.putInt("widgetTheme", theme);
            GlassSettings.read(source, "glass", theme).save(edit, "glass");
        }
        edit.apply();
        return settings;
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (CLOCK.equals(intent.getAction())) render(context);
        if (REFRESH.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (!owns(context, id)) return;
            SharedPreferences prefs = settings(context, id);
            if (!prefs.contains("url")) return;
            boolean queued = RefreshJob.refresh(context);
            prefs.edit().putLong("refreshRequested", System.currentTimeMillis())
                    .putBoolean("refreshRejected", !queued).apply();
            render(context);
        }
    }

    static void render(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgets = ids(context);
        if (widgets.length == 0) return;
        ZonedDateTime now = ZonedDateTime.now(SchoolPlan.ZONE);
        LocalDate date = PlanDay.display(now);
        ZonedDateTime next = now.withHour(PlanDay.ROLLOVER_HOUR).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        int[] planWidgets = ids(context, PlanWidget.class);
        for (int id : planWidgets) {
            manager.updateAppWidget(id, views(context, id, date, now));
            if (date.equals(now.toLocalDate())) {
                for (LivePlan.Lesson lesson : LivePlan.day(context, settings(context, id), date)) {
                    for (String time : new String[]{lesson.start, lesson.end}) {
                        int minute = PlanDay.minute(time);
                        if (minute < 0) continue;
                        ZonedDateTime boundary = date.atStartOfDay(SchoolPlan.ZONE).plusMinutes(minute);
                        if (boundary.isAfter(now) && boundary.isBefore(next)) next = boundary;
                    }
                }
            }
        }
        for (int id : ids(context, NextLessonWidget.class)) {
            ZonedDateTime boundary = NextLessonWidget.renderWidget(context, manager, id, now);
            if (boundary != null && boundary.isBefore(next)) next = boundary;
        }
        if (Build.VERSION.SDK_INT < 31 && planWidgets.length > 0) {
            manager.notifyAppWidgetViewDataChanged(planWidgets, R.id.widget_lessons);
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        long when = next.toInstant().toEpochMilli();
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try { alarms.setExact(AlarmManager.RTC, when, clock(context)); }
            catch (SecurityException unavailable) { alarms.set(AlarmManager.RTC, when, clock(context)); }
        } else alarms.set(AlarmManager.RTC, when, clock(context));
    }

    private static PendingIntent clock(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, PlanWidget.class).setAction(CLOCK),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private static RemoteViews views(Context context, int id, LocalDate today, ZonedDateTime now) {
        SharedPreferences prefs = settings(context, id);
        AppColors colors = widgetColors(prefs);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        style(views, colors, prefs);
        String name = prefs.getString("name", "");
        String dateLabel = PlanDay.label(LocalDate.now(SchoolPlan.ZONE), today);
        views.setTextViewText(R.id.widget_title, name.isEmpty() ? context.getString(R.string.app_name)
                : name.split("\\s+", 2)[0] + " · " + dateLabel);
        String empty = name.isEmpty() ? "Dotknij, aby wybrać klasę"
                : prefs.contains("week") ? "Brak lekcji w planie" : "Oczekiwanie na plan lekcji";
        if (!prefs.contains("week") && prefs.getBoolean("error", false)) empty = "Nie udało się pobrać planu";
        views.setTextViewText(R.id.widget_empty, empty);
        views.setTextColor(R.id.widget_empty, colors.muted);
        views.setEmptyView(R.id.widget_lessons, R.id.widget_empty);
        long updated = prefs.getLong("updated", 0);
        List<LivePlan.Lesson> lessons = LivePlan.day(context, prefs, today);
        views.setViewVisibility(R.id.widget_columns, lessons.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        for (int heading : new int[]{R.id.column_start, R.id.column_subject, R.id.column_room, R.id.column_end}) {
            views.setTextColor(heading, colors.muted);
        }
        long releases = lessons.stream().filter(lesson -> lesson.released).count();
        long otherChanges = lessons.stream().filter(lesson -> !lesson.status.isEmpty() && !lesson.released).count();
        String status = otherChanges > 0 ? "Zmiany: " + otherChanges : "";
        if (releases > 0) status += (status.isEmpty() ? "" : " · ") + "Zwolnienia: " + releases;
        views.setTextColor(R.id.widget_status, releases > 0 ? colors.green
                : otherChanges > 0 ? colors.warning : colors.muted);
        if (prefs.getBoolean("refreshRejected", false)) {
            status = "Nie udało się uruchomić odświeżania";
        } else if (prefs.contains("refreshRequested")) {
            status = System.currentTimeMillis() - prefs.getLong("refreshRequested", 0) < 60000
                    ? "Odświeżanie planu…" : "Oczekiwanie na połączenie…";
        } else if (prefs.getBoolean("error", false)) {
            status = (status.isEmpty() ? "" : status + " · ") + "Błąd pobierania planu";
        } else if (prefs.getBoolean("changesError", false)) {
            status += (status.isEmpty() ? "" : " · ") + "Brak aktualizacji zastępstw";
        } else if (updated != 0) {
            status += (status.isEmpty() ? "" : " · ") + "Plan z " + Instant.ofEpochMilli(updated)
                    .atZone(SchoolPlan.ZONE).format(DateTimeFormatter.ofPattern("d.MM HH:mm"));
        }
        views.setTextViewText(R.id.widget_status, status);
        bindActions(context, id, views, R.id.widget_empty);
        Intent plan = planIntent(context, id);
        views.setPendingIntentTemplate(R.id.widget_lessons, PendingIntent.getActivity(context, id + 1,
                new Intent(plan).setData(Uri.parse("micekwidget://open/" + id)), PendingIntent.FLAG_MUTABLE));
        if (Build.VERSION.SDK_INT >= 31) {
            CollectionApi31.setRows(context, views, lessons, colors, today, now);
            return views;
        }
        Intent adapter = new Intent(context, WidgetLessons.class)
                .setData(new Uri.Builder().scheme("micekwidget").authority("lessons").appendPath(Integer.toString(id))
                        .appendQueryParameter("class", prefs.getString("url", ""))
                        .appendQueryParameter("theme", Integer.toString(themeMode(prefs)))
                        .appendQueryParameter("date", today.toString()).build())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setRemoteAdapter(R.id.widget_lessons, adapter);
        return views;
    }

    private static final class CollectionApi31 {
        static void setRows(Context context, RemoteViews views, List<LivePlan.Lesson> lessons,
                            AppColors colors, LocalDate date, ZonedDateTime now) {
            RemoteViews.RemoteCollectionItems.Builder rows = new RemoteViews.RemoteCollectionItems.Builder()
                    .setViewTypeCount(1);
            int upcoming = PlanDay.upcomingIndex(lessons, date, now);
            for (int i = 0; i < lessons.size(); i++) {
                LivePlan.Lesson lesson = lessons.get(i);
                rows.addItem(i, WidgetLessons.row(context, lesson, colors, PlanDay.active(lesson, date, now), i == upcoming));
            }
            views.setRemoteAdapter(R.id.widget_lessons, rows.build());
        }
    }

    static AppColors widgetColors(SharedPreferences prefs) {
        return new AppColors(themeMode(prefs));
    }

    static int themeMode(SharedPreferences prefs) {
        return prefs.getInt("widgetTheme", prefs.getBoolean("widgetLight", false) ? AppColors.LIGHT : AppColors.DARK);
    }

    static void style(RemoteViews views, AppColors colors, SharedPreferences prefs) {
        int mode = themeMode(prefs);
        GlassSettings effect = GlassSettings.read(prefs, "glass", mode);
        boolean glass = mode != AppColors.AMOLED && effect.enabled;
        int background = mode == AppColors.AMOLED ? R.drawable.widget_bg_amoled
                : colors.light ? (glass ? R.drawable.widget_glass_light : R.drawable.widget_bg_light)
                : (glass ? R.drawable.widget_glass_dark : R.drawable.widget_bg);
        views.setImageViewResource(R.id.widget_background, background);
        views.setInt(R.id.widget_background, "setImageAlpha", glass ? Math.round(255 * effect.opacity / 100f) : 255);
        views.setImageViewResource(R.id.widget_glass_edge, colors.light ? R.drawable.glass_edge_light : R.drawable.glass_edge_dark);
        views.setViewVisibility(R.id.widget_glass_edge, glass && effect.edge
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (views.getLayoutId() != R.layout.widget_next_tiny) {
            views.setTextColor(R.id.widget_title, colors.text);
            views.setTextColor(R.id.widget_configure, colors.accent);
            views.setTextColor(R.id.widget_refresh, colors.accent);
        }
    }

    static void bindActions(Context context, int id, RemoteViews views, int empty) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent configuration = new Intent(context, WidgetConfiguration.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("fromWidget", true)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        boolean controls = views.getLayoutId() != R.layout.widget_next_tiny;
        if (controls) views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, id, configuration, flags));
        if (empty != 0) views.setOnClickPendingIntent(empty, PendingIntent.getActivity(context, id, configuration, flags));
        PendingIntent open = PendingIntent.getActivity(context, id, planIntent(context, id), flags);
        if (controls) views.setOnClickPendingIntent(R.id.widget_title, open);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        if (controls) views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id,
                new Intent(context, PlanWidget.class).setAction(REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), flags));
    }

    private static Intent planIntent(Context context, int id) {
        return new Intent(context, MainActivity.class).setAction(OPEN_PLAN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
    }
}
