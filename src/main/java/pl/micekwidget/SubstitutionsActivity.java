package pl.micekwidget;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SubstitutionsActivity extends NativePage {
    private static final String[] TYPES = {"Wszystkie", "Zastępstwa", "Odwołane", "Później / wcześniej",
            "Zmiany sal", "Wydarzenia", "Dyżury"};
    private final List<LocalDate> archive = new ArrayList<>();
    private LocalDate date;
    private JSONObject response;
    private int kind, scope;
    private String className, teacherName = "Wybierz nauczyciela", teacherId = "";
    private LinearLayout results;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.content.SharedPreferences prefs = SchoolPlan.prefs(this);
        className = prefs.getString("url", "").startsWith("teacher:") ? ""
                : prefs.getString("name", "").split("\\s+", 2)[0];
        scope = className.isEmpty() ? 1 : 0;
        date = PlanDay.display();
        render();
        fetch(true);
    }

    private void render() {
        open("Zastępstwa");
        add(body, label(date.format(DateTimeFormatter.ofPattern("EEEE, d.MM.yyyy", new Locale("pl", "PL"))),
                15, colors.muted, false), 2);
        LocalDate now = LocalDate.now(SchoolPlan.ZONE);
        int day = date.equals(now) ? 0 : date.equals(now.plusDays(1)) ? 1 : 2;
        chips(body, new String[]{"Dziś", "Jutro", "Wcześniejsze…"}, day, selected -> {
            if (selected == 2) chooseArchive();
            else changeDate(now.plusDays(selected));
        });
        chips(body, TYPES, kind, selected -> { kind = selected; render(); });
        chips(body, new String[]{"Moja klasa", "Wszystkie", "Nauczyciel"}, scope, selected -> {
            if (selected == 0 && className.isEmpty()) {
                chooseClass();
                return;
            }
            scope = selected;
            render();
        });
        if (scope == 0) add(body, button(className + "  ▾", false, this::chooseClass), 8);
        if (scope == 2) add(body, button(teacherName + "  ▾", false, this::chooseTeacher), 8);
        results = column();
        add(body, results, 14);
        if (response != null) showResults();
        else add(results, label("Pobieranie zastępstw…", 15, colors.muted, false), 9);
    }

    private void fetch(boolean withArchive) {
        String selected = date.toString();
        load(() -> {
            JSONArray dates = null;
            if (withArchive) {
                try { dates = LivePlan.read("substitutions/dates").getJSONArray("dates"); }
                catch (java.io.IOException | org.json.JSONException ignored) {}
            }
            return new JSONObject().put("archive", dates).put("day", LivePlan.read("substitutions?date=" + selected));
        }, data -> {
            JSONArray dates = data.optJSONArray("archive");
            if (dates != null) {
                archive.clear();
                SectionActivity.each(dates, item -> {
                    try { archive.add(LocalDate.parse(value(item, "date"))); }
                    catch (java.time.format.DateTimeParseException ignored) {}
                });
                Collections.sort(archive, Collections.reverseOrder());
            }
            response = data.optJSONObject("day");
            showResults();
        }, error -> failure(results, () -> fetch(withArchive)));
    }

    @Override void refreshPage() {
        fetch(true);
    }

    private void changeDate(LocalDate selected) {
        date = selected;
        response = null;
        render();
        fetch(archive.isEmpty());
    }

    private void chooseArchive() {
        List<LocalDate> earlier = new ArrayList<>();
        LocalDate today = LocalDate.now(SchoolPlan.ZONE);
        for (LocalDate available : archive) if (available.isBefore(today)) earlier.add(available);
        if (earlier.isEmpty()) { message("Brak wcześniejszych komunikatów."); return; }
        String[] labels = earlier.stream().map(LocalDate::toString).toArray(String[]::new);
        dialog().setTitle("Wcześniejsze komunikaty").setItems(labels, (d, index) -> changeDate(earlier.get(index))).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, colors.light ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert);
    }

    private void chooseClass() {
        network.execute(() -> {
            try {
                List<SchoolPlan.ClassPlan> items = SchoolPlan.classes("LO");
                items.addAll(SchoolPlan.classes("T"));
                String[] names = items.stream().map(item -> item.name.split("\\s+", 2)[0]).toArray(String[]::new);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) dialog().setTitle("Wybierz klasę").setItems(names,
                            (dialog, index) -> { className = names[index]; scope = 0; render(); }).show();
                });
            } catch (java.io.IOException error) {
                runOnUiThread(() -> { if (!isDestroyed()) message("Nie udało się pobrać klas."); });
            }
        });
    }

    private void chooseTeacher() {
        network.execute(() -> {
            try {
                List<SchoolPlan.ClassPlan> people = LivePlan.teachers();
                String[] names = people.stream().map(person -> person.name).toArray(String[]::new);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) dialog().setTitle("Wybierz nauczyciela").setItems(names,
                            (dialog, index) -> {
                                teacherName = names[index]; teacherId = people.get(index).url.substring(8);
                                scope = 2; render();
                            }).show();
                });
            } catch (java.io.IOException | org.json.JSONException error) {
                runOnUiThread(() -> { if (!isDestroyed()) message("Nie udało się pobrać nauczycieli."); });
            }
        });
    }

    private void showResults() {
        results.removeAllViews();
        if (response == null) return;
        List<JSONObject> matches = new ArrayList<>();
        SectionActivity.each(response.optJSONArray("events"), event -> {
            String type = value(event, "kind");
            if (type.equals("announcement")) return;
            if (!typeMatches(type) || !scopeMatches(event)) return;
            JSONObject subject = event.optJSONObject("subject");
            if (SchoolPlan.prefs(this).getBoolean("hideReligion", false)
                    && (LivePlan.isReligion(value(subject, "name")) || LivePlan.isReligion(value(event, "newSubject")))) return;
            matches.add(event);
        });

        if (matches.isEmpty()) {
            LinearLayout empty = card(results, 2);
            empty.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(16), dp(22), dp(16), dp(23));
            android.widget.TextView icon = label("▣", 24, colors.accent, true);
            icon.setGravity(android.view.Gravity.CENTER);
            icon.setBackground(shape(colors.selected, 13));
            empty.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(44)));
            String title = scope == 0 && kind == 0 ? "Brak zmian dla klasy " + className
                    : scope == 2 && teacherId.isEmpty() ? "Wybierz nauczyciela" : "Brak zmian dla tego wyboru";
            android.widget.TextView heading = label(title, 17, colors.text, true);
            heading.setGravity(android.view.Gravity.CENTER);
            add(empty, heading, 10);
            if (scope == 0 && kind == 0) {
                android.widget.TextView hint = label("Plan na ten dzień obowiązuje bez zmian.", 14, colors.muted, false);
                hint.setGravity(android.view.Gravity.CENTER);
                add(empty, hint, 8);
            }
        } else {
            add(results, label("Zmiany w planie · " + matches.size(), 18, colors.text, true), 0);
            for (JSONObject match : matches) eventCard(match);
        }

        add(results, button("Zobacz plan lekcji  ›", false, () -> {
            startActivity(new android.content.Intent(this, MainActivity.class).setAction(MainActivity.OPEN_DAY)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("date", date.toString()));
        }), 13);

        JSONObject document = response.optJSONObject("document");
        if (document != null) {
            String updated = value(document, "publishedAt");
            try {
                updated = java.time.Instant.parse(updated).atZone(SchoolPlan.ZONE)
                        .format(DateTimeFormatter.ofPattern("d.MM, HH:mm"));
            } catch (java.time.format.DateTimeParseException ignored) { updated = ""; }
            add(results, label("Na podstawie komunikatu szkoły" + (updated.isEmpty() ? "" : " · aktualizacja " + updated),
                    12, colors.muted, false), 16);
        }
    }

    private void eventCard(JSONObject event) {
        LivePlan.Lesson lesson = LivePlan.merge(null, new JSONArray().put(event)).get(0);
        String clazz = value(event.optJSONObject("class"), "name");
        String zone = value(event.optJSONObject("dutyZone"), "name");
        LinearLayout card = card(results, 8);
        add(card, label(lesson.status + (clazz.isEmpty() ? "" : " · " + clazz), 14,
                lesson.released ? colors.green : colors.warning, true), 0);
        if (!lesson.title.equals(lesson.status)) add(card, label(lesson.title, 17, colors.text, true), 5);
        List<String> details = new ArrayList<>();
        for (String detail : new String[]{lesson.period > 0 ? "Lekcja " + lesson.period : "", lesson.time(),
                value(event.optJSONObject("group"), "name"), lesson.teacher,
                lesson.room.isEmpty() ? zone : "Sala " + lesson.room}) if (!detail.isEmpty()) details.add(detail);
        if (!details.isEmpty()) add(card, label(String.join(" · ", details), 14, colors.muted, false), 5);
        if (!lesson.note.isEmpty()) add(card, label(lesson.note, 14, colors.text, false), 5);
    }

    private boolean scopeMatches(JSONObject event) {
        if (scope == 0) return className.equalsIgnoreCase(value(event.optJSONObject("class"), "name"));
        if (scope == 1) return true;
        return !teacherId.isEmpty() && (teacherId.equals(value(event.optJSONObject("absentTeacher"), "id"))
                || teacherId.equals(value(event.optJSONObject("substitute"), "id")));
    }

    private boolean typeMatches(String value) {
        switch (kind) {
            case 1: return value.equals("substitution") || value.equals("merged");
            case 2: return value.equals("cancelled") || value.equals("free_period");
            case 3: return value.equals("released") || value.equals("late_start") || value.equals("moved");
            case 4: return value.equals("room_change");
            case 5: return value.equals("event") || value.equals("trip");
            case 6: return value.equals("duty");
            default: return !value.equals("duty");
        }
    }

    private static String value(JSONObject object, String key) { return LivePlan.text(object, key); }
}
