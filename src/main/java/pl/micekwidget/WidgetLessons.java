package pl.micekwidget;

import android.content.Intent;
import android.appwidget.AppWidgetManager;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

public final class WidgetLessons extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        return new RemoteViewsFactory() {
            private volatile List<LivePlan.Lesson> lessons = Collections.emptyList();
            private LocalDate date;
            private AppColors colors;

            @Override public void onCreate() {}

            @Override public void onDataSetChanged() {
                String requested = intent.getData() == null ? null : intent.getData().getQueryParameter("date");
                date = requested == null ? PlanDay.display() : LocalDate.parse(requested);
                colors = PlanWidget.widgetColors(PlanWidget.settings(WidgetLessons.this, id));
                lessons = PlanWidget.owns(WidgetLessons.this, id)
                        ? LivePlan.day(WidgetLessons.this, PlanWidget.settings(WidgetLessons.this, id), date)
                        : Collections.emptyList();
            }

            @Override public void onDestroy() {
                lessons = Collections.emptyList();
            }

            @Override public int getCount() {
                return lessons.size();
            }

            @Override public RemoteViews getViewAt(int position) {
                List<LivePlan.Lesson> current = lessons;
                if (position < 0 || position >= current.size()) return null;
                LivePlan.Lesson lesson = current.get(position);
                return row(WidgetLessons.this, lesson, colors, PlanDay.active(lesson, date, ZonedDateTime.now(SchoolPlan.ZONE)));
            }

            @Override public RemoteViews getLoadingView() { return null; }
            @Override public int getViewTypeCount() { return 1; }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return false; }
        };
    }

    static RemoteViews row(android.content.Context context, LivePlan.Lesson lesson, AppColors colors, boolean active) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_lesson);
        row.setViewVisibility(R.id.lesson_highlight, active ? View.VISIBLE : View.GONE);
        row.setImageViewResource(R.id.lesson_highlight, colors.light ? R.drawable.widget_active_light : R.drawable.widget_active_dark);
        row.setOnClickFillInIntent(R.id.lesson_row, new Intent());
        boolean changed = !lesson.status.isEmpty();
        int accent = lesson.released ? colors.green : changed ? colors.warning : colors.text;
        row.setTextColor(R.id.lesson_start, colors.muted);
        row.setTextColor(R.id.lesson_end, colors.text);
        row.setTextColor(R.id.lesson_detail, colors.muted);
        row.setTextColor(R.id.lesson_room, colors.accent);
        row.setTextViewText(R.id.lesson_start, lesson.start);
        row.setTextViewText(R.id.lesson_name, lesson.cancelled ? lesson.status : lesson.title);
        row.setTextColor(R.id.lesson_name, accent);
        String detail = lesson.cancelled ? lesson.group : changed ? lesson.status : lesson.teacher;
        if (active) detail = "Teraz" + (detail.isEmpty() ? "" : " · " + detail);
        if (changed && !lesson.cancelled && !lesson.teacher.isEmpty()) detail += " · " + lesson.teacher;
        if (!lesson.cancelled && !lesson.group.isEmpty()) {
            detail += (detail.isEmpty() ? "" : " · ") + lesson.group;
        }
        row.setTextViewText(R.id.lesson_detail, detail);
        row.setViewVisibility(R.id.lesson_detail, detail.isEmpty() ? View.GONE : View.VISIBLE);
        row.setTextViewText(R.id.lesson_room, lesson.cancelled ? "" : lesson.room);
        row.setTextViewText(R.id.lesson_end, lesson.end);
        return row;
    }
}
