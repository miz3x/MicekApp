package pl.micekwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SchoolNotifications {
    static final String POSTS = "spotted_posts";
    static final String COMMENTS = "spotted_comments";
    static final String PLAN = "plan_changes";
    private static final Object LOCK = new Object();

    static SharedPreferences state(Context context) {
        return context.getSharedPreferences("notificationState", Context.MODE_PRIVATE);
    }

    static boolean allowed(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean enabled(Context context, String channel) {
        return SchoolPlan.prefs(context).getBoolean("notify_" + channel, true);
    }

    static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannels(java.util.Arrays.asList(
                new NotificationChannel(POSTS, "Nowe posty na Spotted", NotificationManager.IMPORTANCE_DEFAULT),
                new NotificationChannel(COMMENTS, "Komentarze do obserwowanych postów", NotificationManager.IMPORTANCE_DEFAULT),
                new NotificationChannel(PLAN, "Zmiany w planie lekcji", NotificationManager.IMPORTANCE_DEFAULT)));
    }

    static boolean follows(Context context, String post) {
        return state(context).getStringSet("followed", Collections.emptySet()).contains(post);
    }

    static void follow(Context context, String post, JSONArray comments, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences prefs = state(context);
            Set<String> followed = new HashSet<>(prefs.getStringSet("followed", Collections.emptySet()));
            SharedPreferences.Editor editor = prefs.edit();
            if (enabled) {
                followed.add(post);
                editor.putStringSet("comments_" + post, ids(comments));
            } else {
                followed.remove(post);
                editor.remove("comments_" + post);
            }
            editor.putStringSet("followed", followed).apply();
        }
    }

    static void poll(Context context) {
        if (!allowed(context)) return;
        channels(context);
        if (enabled(context, POSTS)) {
            try { posts(context); } catch (IOException | JSONException ignored) {}
        }
        if (!enabled(context, COMMENTS)) return;
        Set<String> followed = new HashSet<>(state(context).getStringSet("followed", Collections.emptySet()));
        for (String post : followed) {
            if (Thread.currentThread().isInterrupted()) return;
            if (!post.matches("[0-9a-fA-F-]{36}")) continue;
            try {
                JSONObject detail = LivePlan.read("spotted/posts/" + post);
                JSONArray rows = detail.optJSONArray("comments");
                if (rows == null) continue;
                Set<String> latest = ids(rows);
                synchronized (LOCK) {
                    if (!follows(context, post)) continue;
                    SharedPreferences prefs = state(context);
                    String key = "comments_" + post;
                    Set<String> seen = prefs.getStringSet(key, Collections.emptySet());
                    String title = excerpt(detail.optJSONObject("post"));
                    if (prefs.contains(key)) {
                        for (String id : latest) if (!seen.contains(id)) {
                            notify(context, COMMENTS, "comment:" + post + ":" + id, "Nowy komentarz na Spotted",
                                    title, new Intent(context, SectionActivity.class).putExtra("section", "spotted").putExtra("post", post));
                        }
                    }
                    prefs.edit().putStringSet(key, latest).apply();
                }
            } catch (IOException | JSONException ignored) {}
        }
    }

    private static void posts(Context context) throws IOException, JSONException {
        SharedPreferences prefs = state(context);
        boolean initialized = prefs.getBoolean("postsInitialized", false);
        String last = prefs.getString("postWatermark", "");
        String newest = last;
        Set<String> latest = new HashSet<>();
        List<JSONObject> fresh = new ArrayList<>();
        for (int page = 1; page <= (initialized ? 5 : 1); page++) {
            JSONObject feed = LivePlan.read("spotted/posts?limit=20&sort=new&tab=published&page=" + page);
            JSONArray rows = feed.getJSONArray("data");
            boolean reachedPrevious = false;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String created = LivePlan.text(row, "createdAt");
                String id = LivePlan.text(row, "id");
                if (created.isEmpty() || id.isEmpty()) continue;
                if (created.compareTo(newest) > 0) { newest = created; latest.clear(); }
                if (created.compareTo(last) < 0) reachedPrevious = true;
                if (created.equals(newest)) latest.add(id);
                if (initialized && (created.compareTo(last) > 0 || (created.equals(last)
                        && !prefs.getStringSet("postWatermarkIds", Collections.emptySet()).contains(id)))) fresh.add(row);
            }
            if (rows.length() < 20 || reachedPrevious) break;
        }
        synchronized (LOCK) {
            if (!last.equals(prefs.getString("postWatermark", "")) || initialized != prefs.getBoolean("postsInitialized", false)) return;
            for (JSONObject row : fresh) {
                notify(context, POSTS, "post:" + LivePlan.text(row, "id"), "Nowy post na Spotted", excerpt(row),
                        new Intent(context, SectionActivity.class).putExtra("section", "spotted"));
            }
            SharedPreferences.Editor edit = prefs.edit().putBoolean("postsInitialized", true);
            if (!latest.isEmpty()) edit.putString("postWatermark", newest).putStringSet("postWatermarkIds", latest);
            edit.apply();
        }
    }

    private static Set<String> ids(JSONArray rows) {
        Set<String> result = new HashSet<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            String id = LivePlan.text(rows.optJSONObject(i), "id");
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    static void plan(Context context, String cls, String previous, JSONObject current, boolean teacherPlan) {
        if (previous.isEmpty() || !allowed(context) || !enabled(context, PLAN)) return;
        Map<String, JSONObject> old;
        try { old = events(new JSONObject(previous)); }
        catch (JSONException ignored) { return; }
        channels(context);
        Set<String> previousDetails = new HashSet<>();
        for (Map.Entry<String, JSONObject> entry : old.entrySet()) {
            previousDetails.add(entry.getKey().substring(0, 10) + details(entry.getValue()));
        }
        for (Map.Entry<String, JSONObject> entry : events(current).entrySet()) {
            JSONObject event = entry.getValue();
            JSONObject before = old.get(entry.getKey());
            if (before != null && details(before).equals(details(event))) continue;
            String date = entry.getKey().substring(0, 10);
            if (!previousDetails.add(date + details(event))) continue;
            LocalDate day;
            try { day = LocalDate.parse(date); }
            catch (java.time.format.DateTimeParseException ignored) { continue; }
            if (day.isBefore(LocalDate.now(SchoolPlan.ZONE))) continue;
            List<LivePlan.Lesson> lessons = LivePlan.merge(null, new JSONArray().put(event));
            LivePlan.Lesson lesson = lessons.get(0);
            SharedPreferences settings = SchoolPlan.prefs(context);
            if (!teacherPlan && (!LivePlan.languageAllowed(lesson, settings.getInt("languageChoice", 0))
                    || settings.getBoolean("hideReligion", false)
                    && (LivePlan.isReligion(lesson.title) || LivePlan.isReligion(lesson.previous)))) continue;
            String dayLabel = day.equals(LocalDate.now(SchoolPlan.ZONE)) ? "Dziś"
                    : day.equals(LocalDate.now(SchoolPlan.ZONE).plusDays(1)) ? "Jutro"
                    : day.format(DateTimeFormatter.ofPattern("d.MM"));
            String time = lesson.start.isEmpty() ? "lekcja " + lesson.period : lesson.start;
            String text = dayLabel + ", " + time;
            if (!lesson.title.equals(lesson.status)) text += " · " + lesson.title;
            if (!lesson.teacher.isEmpty() && !lesson.cancelled) text += " · " + lesson.teacher;
            if (!lesson.room.isEmpty() && !lesson.cancelled) text += " · sala " + lesson.room;
            String group = teacherPlan ? LivePlan.text(event.optJSONObject("class"), "name") : cls.split("\\s+", 2)[0];
            notify(context, PLAN, "change:" + entry.getKey(), lesson.status + (group.isEmpty() ? "" : " · " + group), text,
                    new Intent(context, MainActivity.class).setAction(MainActivity.OPEN_DAY).putExtra("date", date)
                            .putExtra("standalonePlan", true));
        }
    }

    static Map<String, JSONObject> events(JSONObject days) {
        Map<String, JSONObject> result = new HashMap<>();
        java.util.Iterator<String> dates = days.keys();
        while (dates.hasNext()) {
            String date = dates.next();
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) continue;
            JSONArray rows = days.optJSONArray(date);
            if (rows == null) continue;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject event = rows.optJSONObject(i);
                String id = LivePlan.text(event, "id");
                if (!id.isEmpty() && !"moved_out".equals(LivePlan.text(event, "kind"))) result.put(date + "/" + id, event);
            }
        }
        return result;
    }

    private static String details(JSONObject event) {
        StringBuilder fingerprint = new StringBuilder();
        for (String key : new String[]{"kind", "period", "time", "subject", "newSubject", "substitute",
                "room", "newRoom", "group", "note", "title", "text", "moved"}) {
            fingerprint.append('|').append(LivePlan.text(event, key));
        }
        return fingerprint.toString();
    }

    private static String excerpt(JSONObject post) {
        String title = LivePlan.text(post, "title").trim();
        String body = (title.isEmpty() ? LivePlan.text(post, "body") : title).replaceAll("\\s+", " ").trim();
        return body.length() > 110 ? body.substring(0, 109) + "…" : body.isEmpty() ? "Spotted" : body;
    }

    private static void notify(Context context, String channel, String id, String title, String text, Intent target) {
        if (!allowed(context) || !enabled(context, channel)) return;
        target.setData(android.net.Uri.parse("micekapp://notification/" + android.net.Uri.encode(id)));
        Intent home = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent tap = PendingIntent.getActivities(context, id.hashCode(), new Intent[]{home, target},
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification message = new Notification.Builder(context, channel).setSmallIcon(R.drawable.notification_school)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build();
        context.getSystemService(NotificationManager.class).notify(id, 1, message);
    }
}
