package pl.micekwidget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.function.Function;
import java.util.List;

public final class NextLessonWidget extends PlanWidget {
    static ZonedDateTime renderWidget(Context context, AppWidgetManager manager, int id, ZonedDateTime now) {
        SharedPreferences prefs = settings(context, id);
        AppColors colors = widgetColors(prefs);
        android.os.Bundle options = manager.getAppWidgetOptions(id);
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        boolean tiny = useTiny(width, height);
        boolean wideRow = tiny && width >= 165;
        RemoteViews views = new RemoteViews(context.getPackageName(), tiny ? R.layout.widget_next_tiny : R.layout.widget_next_lesson);
        style(views, colors, prefs);
        int mode = themeMode(prefs);
        boolean glass = GlassSettings.read(prefs, "glass", mode).enabled;
        boolean blue = mode != AppColors.AMOLED && !glass;
        if (blue) views.setImageViewResource(R.id.widget_background, colors.light
                ? R.drawable.widget_focus_light : R.drawable.widget_focus_dark);
        String name = prefs.getString("name", "");
        String clazz = name.isEmpty() ? "" : name.split("\\s+", 2)[0];
        if (!tiny) views.setTextViewText(R.id.widget_title, clazz);
        views.setTextColor(R.id.next_subject, colors.text);
        int secondary = blue && !colors.light ? 0xFFD9E4FF : colors.muted;
        views.setTextColor(R.id.next_info, secondary);
        views.setTextColor(R.id.next_countdown, blue && !colors.light ? 0xFFF1F4FF : colors.accent);
        views.setTextColor(R.id.next_empty, colors.muted);
        if (!tiny) views.setTextColor(R.id.next_caption, secondary);
        if (tiny) {
            int sp = android.util.TypedValue.COMPLEX_UNIT_SP;
            boolean roomy = width >= 245 && height >= 54;
            views.setTextViewTextSize(R.id.next_subject, sp, roomy ? 21 : wideRow ? 16 : 11);
            views.setTextViewTextSize(R.id.next_info, sp, roomy ? 15 : wideRow ? 13 : 10);
            views.setTextViewTextSize(R.id.next_countdown, sp, roomy ? 17 : wideRow ? 14 : 10);
            float density = context.getResources().getDisplayMetrics().density;
            int horizontal = Math.round((wideRow ? 16 : 9) * density);
            int vertical = Math.round((roomy ? 8 : 3) * density);
            views.setViewPadding(R.id.next_lesson, horizontal, vertical, horizontal, vertical);
        }
        Next next = currentOrNext(now, date -> LivePlan.day(context, prefs, date));
        views.setViewVisibility(R.id.next_lesson, next == null ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.next_empty, next == null ? View.VISIBLE : View.GONE);
        String message = clazz.isEmpty() ? "Wybierz klasę"
                : prefs.getBoolean("error", false) && !prefs.contains("week") ? "Nie udało się pobrać planu"
                : prefs.contains("week") ? "Brak nadchodzących lekcji" : "Pobieranie planu…";
        if (prefs.contains("refreshRequested")) message = "Odświeżanie planu…";
        views.setTextViewText(R.id.next_empty, message);
        if (next != null) {
            views.setTextViewText(R.id.next_subject, next.lesson.title);
            String day = PlanDay.label(now.toLocalDate(), next.start.toLocalDate());
            if (tiny && !day.equals("Dziś") && !day.equals("Jutro")) {
                day = next.start.format(java.time.format.DateTimeFormatter.ofPattern("EEE", new java.util.Locale("pl", "PL")));
            }
            String info;
            String caption = next.active ? "Trwa · do " + next.lesson.end : day + " · " + next.lesson.start;
            if (tiny) {
                info = next.active ? caption : (day.equals("Dziś") ? "" : day + " ") + next.lesson.start;
                if (!next.lesson.room.isEmpty()) info += " · " + next.lesson.room;
            } else {
                views.setTextViewText(R.id.next_caption, caption);
                info = next.lesson.room.isEmpty() ? "" : "Sala " + next.lesson.room;
                if (!next.lesson.teacher.isEmpty()) info += (info.isEmpty() ? "" : " · ") + next.lesson.teacher;
            }
            views.setTextViewText(R.id.next_info, info);
            long countdown = Duration.between(now.toInstant(), next.boundary.toInstant()).toMillis();
            views.setChronometerCountDown(R.id.next_countdown, true);
            views.setChronometer(R.id.next_countdown, SystemClock.elapsedRealtime() + Math.max(0, countdown),
                    tiny && !wideRow ? "%s" : next.active ? "Do końca %s" : "Za %s", true);
        }
        bindActions(context, id, views, clazz.isEmpty() ? R.id.next_empty : 0);
        manager.updateAppWidget(id, views);
        return next == null ? null : next.boundary;
    }

    static boolean useTiny(int width, int height) {
        return width < 165 || height < 94;
    }

    static Next currentOrNext(ZonedDateTime now, Function<LocalDate, List<LivePlan.Lesson>> lessons) {
        LocalDate date = now.toLocalDate();
        ZonedDateTime midnight = date.atStartOfDay(now.getZone());
        List<LivePlan.Lesson> today = lessons.apply(date);
        for (LivePlan.Lesson lesson : today) {
            if (PlanDay.active(lesson, date, now)) {
                return new Next(lesson, midnight.plusMinutes(PlanDay.minute(lesson.start)),
                        midnight.plusMinutes(PlanDay.minute(lesson.end)), true);
            }
        }
        return find(now, day -> day.equals(date) ? today : lessons.apply(day));
    }

    static Next find(ZonedDateTime now, Function<LocalDate, List<LivePlan.Lesson>> lessons) {
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            if (date.getDayOfWeek().getValue() > 5) continue;
            Next earliest = null;
            for (LivePlan.Lesson lesson : lessons.apply(date)) {
                int minute = PlanDay.minute(lesson.start);
                if (minute < 0 || lesson.cancelled || lesson.optional) continue;
                ZonedDateTime start = date.atStartOfDay(now.getZone()).plusMinutes(minute);
                if (start.isAfter(now) && (earliest == null || start.isBefore(earliest.start))) {
                    earliest = new Next(lesson, start);
                }
            }
            if (earliest != null) return earliest;
        }
        return null;
    }

    static final class Next {
        final LivePlan.Lesson lesson;
        final ZonedDateTime start;
        final ZonedDateTime boundary;
        final boolean active;

        Next(LivePlan.Lesson lesson, ZonedDateTime start) {
            this(lesson, start, start, false);
        }

        Next(LivePlan.Lesson lesson, ZonedDateTime start, ZonedDateTime boundary, boolean active) {
            this.lesson = lesson;
            this.start = start;
            this.boundary = boundary;
            this.active = active;
        }
    }
}
