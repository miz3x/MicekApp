package pl.micekwidget;

import android.content.SharedPreferences;
import android.content.Context;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

final class LivePlan {
    private static final String API = "https://micekapp.pl/api/";

    static JSONObject read(String path) throws IOException, JSONException {
        String body = Jsoup.connect(API + path).ignoreContentType(true).followRedirects(false).timeout(6500).maxBodySize(1500000)
                .header("Accept", "application/json").execute().body();
        return new JSONObject(body);
    }

    static String classId(String name) throws IOException, JSONException {
        String shortName = name.split("\\s+", 2)[0];
        int pages = 1;
        for (int page = 1; page <= pages; page++) {
            JSONObject response = read("classes?page=" + page);
            JSONObject meta = response.optJSONObject("meta");
            if (meta != null) pages = Math.min(meta.optInt("totalPages", 1), 20);
            JSONArray classes = response.getJSONArray("data");
            for (int i = 0; i < classes.length(); i++) {
                JSONObject item = classes.getJSONObject(i);
                if (shortName.equalsIgnoreCase(item.getString("name"))) {
                    String id = item.getString("id");
                    if (id.matches("[0-9a-fA-F-]{36}")) return id;
                }
            }
        }
        throw new IOException("Nie znaleziono klasy w MicekApp");
    }

    static List<SchoolPlan.ClassPlan> teachers() throws IOException, JSONException {
        return catalog(false);
    }

    static List<SchoolPlan.ClassPlan> rooms() throws IOException, JSONException {
        return catalog(true);
    }

    private static List<SchoolPlan.ClassPlan> catalog(boolean rooms) throws IOException, JSONException {
        List<SchoolPlan.ClassPlan> result = new ArrayList<>();
        int pages = 1;
        for (int page = 1; page <= pages; page++) {
            JSONObject response = read("catalog/" + (rooms ? "rooms" : "teachers") + "?limit=100&page=" + page);
            JSONObject meta = response.optJSONObject("meta");
            if (meta != null) pages = Math.min(meta.optInt("totalPages", 1), 20);
            JSONArray items = response.getJSONArray("data");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String name = rooms ? text(item, "name") : (text(item, "firstName") + " " + text(item, "lastName")).trim();
                String id = text(item, "id");
                if (!name.isEmpty() && id.matches("[0-9a-fA-F-]{36}")) {
                    result.add(new SchoolPlan.ClassPlan(name, (rooms ? "room:" : "teacher:") + id));
                }
            }
        }
        result.sort(Comparator.comparing(entry -> entry.name, java.text.Collator.getInstance(new java.util.Locale("pl", "PL"))));
        return result;
    }

    static JSONArray schedule(JSONObject source) throws JSONException {
        JSONObject includes = source.getJSONObject("includes");
        JSONArray entries = source.getJSONArray("entries");
        JSONArray week = new JSONArray();
        for (int d = 0; d < 5; d++) week.put(new JSONArray());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject row = entries.getJSONObject(i);
            JSONArray day = week.optJSONArray(row.optInt("dayOfWeek") - 1);
            JSONObject period = lookup(includes, row, "periods", "periodId");
            if (day == null || period == null) continue;
            JSONObject subject = lookup(includes, row, "subjects", "subjectId");
            String title = text(subject, "name");
            if (title.isEmpty()) title = text(subject, "shortName");
            day.put(new JSONObject()
                    .put("id", text(row, "id"))
                    .put("period", period.optInt("number"))
                    .put("start", clock(text(period, "startTime")))
                    .put("end", clock(text(period, "endTime")))
                    .put("title", title)
                    .put("short", text(subject, "shortName"))
                    .put("teacher", text(lookup(includes, row, "teachers", "teacherId"), "shortName"))
                    .put("room", text(lookup(includes, row, "rooms", "roomId"), "name"))
                    .put("className", text(lookup(includes, row, "classes", "classId"), "name"))
                    .put("group", text(lookup(includes, row, "groups", "groupId"), "name"))
                    .put("optional", row.optBoolean("isOptional")));
        }
        return week;
    }

    static JSONObject changes(String id, LocalDate monday) throws IOException, JSONException {
        JSONObject result = new JSONObject();
        JSONArray dates = read("substitutions/dates").getJSONArray("dates");
        for (int i = 0; i < dates.length(); i++) {
            String date = dates.getJSONObject(i).getString("date");
            if (date.compareTo(monday.toString()) < 0 || date.compareTo(monday.plusDays(7).toString()) > 0) continue;
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) continue;
            JSONArray events = read("substitutions?date=" + date + "&classId=" + id).getJSONArray("events");
            for (int j = 0; j < events.length(); j++) {
                JSONObject event = events.getJSONObject(j);
                String kind = text(event, "kind");
                if (!id.equals(text(event.optJSONObject("class"), "id"))
                        || kind.equals("duty") || kind.equals("announcement") || event.optInt("period") <= 0) continue;
                addChange(result, date, event);
                JSONObject moved = event.optJSONObject("moved");
                JSONObject from = moved == null ? null : moved.optJSONObject("from");
                if (from == null || from.optInt("period") <= 0) continue;
                String fromDate = text(from, "date");
                if (fromDate.isEmpty()) fromDate = date;
                if (fromDate.equals(date) && from.optInt("period") == event.optInt("period")) continue;
                JSONObject origin = new JSONObject(event.toString()).put("kind", "moved_out")
                        .put("period", from.optInt("period")).put("scheduleEntryId", "")
                        .put("time", JSONObject.NULL);
                addChange(result, fromDate, origin);
            }
        }
        return result;
    }

    private static void addChange(JSONObject result, String date, JSONObject event) throws JSONException {
        JSONArray day = result.optJSONArray(date);
        if (day == null) {
            day = new JSONArray();
            result.put(date, day);
        }
        day.put(event);
    }

    static List<Lesson> day(Context context, SharedPreferences prefs, LocalDate date) {
        try {
            JSONArray week = new JSONArray(prefs.getString("week", "[]"));
            JSONObject changes = new JSONObject(prefs.getString("changes", "{}"));
            return day(context, week, changes, date, !prefs.getString("url", "").startsWith("teacher:"));
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }
    }

    static List<Lesson> day(Context context, JSONArray week, JSONObject changes, LocalDate date, boolean classPlan) {
        List<Lesson> lessons = merge(week == null ? null : week.optJSONArray(date.getDayOfWeek().getValue() - 1),
                changes == null ? null : changes.optJSONArray(date.toString()));
        SharedPreferences settings = SchoolPlan.prefs(context);
        if (settings.getBoolean("hideReligion", false)) {
            lessons.removeIf(lesson -> isReligion(lesson.title) || isReligion(lesson.shortTitle) || isReligion(lesson.previous));
        }
        if (classPlan) {
            int language = settings.getInt("languageChoice", 0);
            if (language != 0) lessons.removeIf(lesson -> !languageAllowed(lesson, language));
        }
        return lessons;
    }

    static boolean languageAllowed(Lesson lesson, int choice) {
        if (choice == 0) return true;
        String subject = lesson.previous.isEmpty() ? lesson.title + " " + lesson.shortTitle : lesson.previous;
        String normalized = subject.toLowerCase(java.util.Locale.ROOT);
        if (choice == 1) return !normalized.contains("niemieck");
        if (choice == 2) return !normalized.contains("rosyjsk");
        return true;
    }

    static boolean isReligion(String title) {
        return title.trim().toLowerCase(java.util.Locale.ROOT).matches("religia(?:[\\s.,:/-].*)?");
    }

    static List<Lesson> merge(JSONArray plan, JSONArray changes) {
        List<Lesson> result = new ArrayList<>();
        if (plan != null) {
            for (int i = 0; i < plan.length(); i++) {
                JSONObject entry = plan.optJSONObject(i);
                result.add(entry == null ? new Lesson(plan.optString(i)) : new Lesson(entry));
            }
        }
        if (changes != null) {
            for (int i = 0; i < changes.length(); i++) {
                JSONObject event = changes.optJSONObject(i);
                if (event == null) continue;
                Lesson match = null;
                int candidates = 0;
                String id = text(event, "scheduleEntryId");
                String group = text(event.optJSONObject("group"), "name");
                for (Lesson lesson : result) {
                    if (lesson.period != event.optInt("period")) continue;
                    if (!id.isEmpty() && id.equals(lesson.id)) {
                        match = lesson;
                        candidates = 1;
                        break;
                    }
                    if (!lesson.status.isEmpty()) continue;
                    if (!group.isEmpty() && !group.equals(lesson.group)) continue;
                    match = lesson;
                    candidates++;
                }
                if (candidates != 1) {
                    match = new Lesson(event);
                    match.title = text(event.optJSONObject("subject"), "name");
                    match.shortTitle = text(event.optJSONObject("subject"), "shortName");
                    match.teacher = text(event.optJSONObject("absentTeacher"), "name");
                    match.room = text(event.optJSONObject("room"), "name");
                    JSONObject time = event.optJSONObject("time");
                    match.start = clock(text(time, "start"));
                    match.end = clock(text(time, "end"));
                    for (Lesson lesson : result) {
                        if (lesson.period == match.period && match.start.isEmpty()) {
                            match.start = lesson.start;
                            match.end = lesson.end;
                        }
                    }
                    result.add(match);
                }
                match.apply(event);
            }
        }
        result.sort(Comparator.comparing(lesson -> lesson.start.length() == 4 ? "0" + lesson.start : lesson.start));
        return result;
    }

    private static JSONObject lookup(JSONObject includes, JSONObject row, String map, String key) {
        JSONObject values = includes.optJSONObject(map);
        return values == null ? null : values.optJSONObject(text(row, key));
    }

    static String text(JSONObject object, String key) {
        return object == null || object.isNull(key) ? "" : object.optString(key, "");
    }

    private static String clock(String value) {
        return value.length() > 5 ? value.substring(0, 5) : value;
    }

    static final class Lesson {
        final String id;
        final int period;
        String start, end, title, shortTitle, teacher, room, group, className;
        String status = "", note = "", previous = "";
        boolean optional, cancelled, released;

        Lesson(JSONObject row) {
            id = text(row, "id");
            period = row.optInt("period");
            start = text(row, "start");
            end = text(row, "end");
            title = text(row, "title");
            shortTitle = text(row, "short");
            teacher = text(row, "teacher");
            room = text(row, "room");
            group = text(row, "group");
            className = text(row, "className");
            optional = row.optBoolean("optional");
        }

        Lesson(String legacy) {
            id = "";
            period = 0;
            String[] parts = legacy.split("  ", 2);
            String[] range = parts.length == 2 ? parts[0].trim().split("\\s*-\\s*", 2) : new String[0];
            start = range.length > 0 ? range[0] : "";
            end = range.length > 1 ? range[1] : "";
            title = parts[parts.length - 1];
            shortTitle = teacher = room = group = className = "";
        }

        void apply(JSONObject event) {
            String kind = text(event, "kind");
            String replacement = text(event, "newSubject");
            String substitute = text(event.optJSONObject("substitute"), "name");
            String newRoom = text(event.optJSONObject("newRoom"), "name");
            String changedGroup = text(event.optJSONObject("group"), "name");
            switch (kind) {
                case "substitution": status = "Zastępstwo"; break;
                case "merged": status = "Złączenie grup"; break;
                case "room_change": status = "Zmiana sali"; break;
                case "released": status = "Zwolnieni do domu"; released = true; cancelled = true; break;
                case "late_start": status = "Przychodzicie później"; cancelled = true; break;
                case "cancelled": status = "Odwołana lekcja"; cancelled = true; break;
                case "free_period": status = "Lekcja się nie odbędzie"; cancelled = true; break;
                case "moved_out": status = "Przeniesiona lekcja"; cancelled = true; break;
                case "moved":
                    status = "Przeniesiona lekcja";
                    JSONObject moved = event.optJSONObject("moved");
                    cancelled = moved == null || moved.optJSONObject("from") == null;
                    break;
                case "trip": status = "Wycieczka"; break;
                case "event": status = "Wydarzenie"; break;
                case "announcement": status = "Ogłoszenie"; break;
                case "duty": status = "Zmiana dyżuru"; break;
                default: status = "Zmiana w planie";
            }
            if (!changedGroup.isEmpty()) group = changedGroup;
            if (!replacement.isEmpty() && !replacement.equalsIgnoreCase(title)) {
                previous = title;
                title = shortTitle = replacement;
            }
            if (!substitute.isEmpty()) teacher = substitute;
            if (!newRoom.isEmpty()) room = newRoom;
            String detail = text(event, "note");
            if (detail.isEmpty()) detail = text(event, "title");
            if (detail.isEmpty()) detail = text(event, "text");
            if (!detail.equalsIgnoreCase(title) && !detail.equalsIgnoreCase(status)) note = detail;
            if (title.isEmpty()) title = status;
        }

        String time() {
            return start.isEmpty() ? "" : start + (end.isEmpty() ? "" : "–" + end);
        }

        String compactTitle() {
            return shortTitle.isEmpty() ? title : shortTitle;
        }
    }
}
