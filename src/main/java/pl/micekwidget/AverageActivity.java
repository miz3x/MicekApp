package pl.micekwidget;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.NumberFormat;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AverageActivity extends NativePage {
    private static final String[] CATEGORIES = {"Sprawdzian", "Kartkówka", "Odpowiedź ustna", "Zadanie domowe",
            "Sprawdzian praktyczny", "Olimpiada lub konkurs", "Praca praktyczna na lekcji", "Prezentacja",
            "Projekt", "Referat", "Aktywność", "Test kompetencji / egzamin próbny", "Inna"};
    private static final double[] WEIGHTS = {1, .8, .8, .6, 1, 1, .7, .6, .6, .5, .5, 0, 1};
    private static final String[] MARKS = {"1", "2", "3", "4", "5", "6", "1+", "2+", "3+", "4+", "5+"};
    private JSONArray subjects;
    private SharedPreferences storage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        storage = getSharedPreferences("gradeBook", MODE_PRIVATE);
        try { subjects = new JSONArray(storage.getString("subjects", "[]")); }
        catch (JSONException malformed) { subjects = new JSONArray(); }
        render();
    }

    @Override void refreshPage() { finishLocalRefresh(); }

    private void render() {
        open("Kalkulator średniej");
        LinearLayout summary = card(body, 7);
        summary.setBackground(shape(colors.light ? 0xFFDFE8FF : 0xFF293D72, 21));
        int title = colors.light ? colors.accent : 0xFFE3EBFF;
        add(summary, label("Średnia ważona", 14, title, true), 1);
        JSONArray all = new JSONArray();
        SectionActivity.each(subjects, subject -> SectionActivity.each(subject.optJSONArray("grades"), all::put));
        double average = average(all);
        add(summary, label(Double.isNaN(average) ? "—" : format(average), 34, title, true), 4);
        add(summary, label(all.length() == 1 ? "1 ocena" : "Liczba ocen · " + all.length(), 14, title, false), 4);

        LinearLayout create = card(body, 13);
        add(create, label("Dodaj przedmiot", 18, colors.text, true), 0);
        String[] suggestions = {"Matematyka", "Język polski", "Język angielski", "Fizyka"};
        chips(create, suggestions, -1, index -> addSubject(suggestions[index]));
        EditText subjectName = input("Nazwa przedmiotu");
        subjectName.setSingleLine();
        add(create, subjectName, 9);
        add(create, button("Dodaj przedmiot", true, () -> addSubject(subjectName.getText().toString())), 8);

        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject != null) renderSubject(subject, i);
        }
    }

    private void addSubject(String entered) {
        String name = entered.trim();
        if (name.isEmpty()) { message("Wpisz nazwę przedmiotu."); return; }
        for (int i = 0; i < subjects.length(); i++) {
            if (name.equalsIgnoreCase(LivePlan.text(subjects.optJSONObject(i), "name"))) {
                message("Ten przedmiot jest już na liście."); return;
            }
        }
        try {
            subjects.put(new JSONObject().put("name", name).put("grades", new JSONArray()));
            save();
        } catch (JSONException ignored) {}
    }

    private void renderSubject(JSONObject subject, int index) {
        LinearLayout subjectCard = card(body, 12);
        String name = LivePlan.text(subject, "name");
        JSONArray grades = subject.optJSONArray("grades");
        if (grades == null) return;
        add(subjectCard, label(name, 19, colors.text, true), 0);
        double result = average(grades);
        add(subjectCard, label(Double.isNaN(result) ? "Brak średniej" : "Średnia ważona · " + format(result),
                14, colors.muted, false), 4);
        for (int i = 0; i < grades.length(); i++) {
            int position = i;
            JSONObject mark = grades.optJSONObject(i);
            if (mark == null) continue;
            add(subjectCard, button(markName(mark.optDouble("value")) + " · " + LivePlan.text(mark, "category")
                    + " · ×" + format(mark.optDouble("weight")), false, () -> grade(name, grades, position)), 7);
        }
        add(subjectCard, button("＋  Dodaj ocenę", true, () -> grade(name, grades, -1)), 9);
        add(subjectCard, button("Edytuj przedmiot", false, () -> editSubject(subject, index)), 6);
    }

    private void editSubject(JSONObject subject, int index) {
        LinearLayout fields = column();
        EditText name = input("Nazwa przedmiotu");
        name.setSingleLine();
        name.setText(LivePlan.text(subject, "name"));
        add(fields, name, 6);
        Dialog dialog = modal("Edytuj przedmiot", fields);
        add(fields, button("Zapisz nazwę", true, () -> {
            String updated = name.getText().toString().trim();
            if (updated.isEmpty()) { name.setError("Wpisz nazwę"); return; }
            for (int i = 0; i < subjects.length(); i++) {
                if (i != index && updated.equalsIgnoreCase(LivePlan.text(subjects.optJSONObject(i), "name"))) {
                    name.setError("Ten przedmiot jest już na liście"); return;
                }
            }
            try { subject.put("name", updated); dialog.dismiss(); save(); }
            catch (JSONException ignored) {}
        }), 10);
        add(fields, button("Usuń przedmiot", false, () -> {
            new android.app.AlertDialog.Builder(this, colors.light ? android.R.style.Theme_Material_Light_Dialog_Alert
                    : android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Usunąć przedmiot razem z ocenami?").setNegativeButton("Anuluj", null)
                    .setPositiveButton("Usuń", (confirm, action) -> {
                        subjects.remove(index); dialog.dismiss(); save();
                    }).show();
        }), 7);
        add(fields, button("Anuluj", false, dialog::dismiss), 7);
        showModal(dialog);
    }

    private void grade(String subject, JSONArray grades, int position) {
        JSONObject existing = grades.optJSONObject(position);
        LinearLayout fields = column();
        add(fields, label(subject, 14, colors.muted, false), 0);
        add(fields, label("Ocena", 15, colors.text, true), 12);
        double[] mark = {existing == null ? 5 : existing.optDouble("value", 5)};
        int[] category = {0};
        if (existing != null) {
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (CATEGORIES[i].equals(LivePlan.text(existing, "category"))) category[0] = i;
            }
        }
        EditText weight = input("Waga");
        weight.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weight.setText(formField(existing == null ? 1 : existing.optDouble("weight", 1)));
        TextView preview = label("", 15, colors.accent, true);
        Runnable update = () -> {
            double parsed = weight(weight);
            JSONArray temporary = new JSONArray();
            for (int i = 0; i < grades.length(); i++) if (i != position) temporary.put(grades.optJSONObject(i));
            if (parsed >= 0) try { temporary.put(new JSONObject().put("value", mark[0]).put("weight", parsed)); }
            catch (JSONException ignored) {}
            double projected = average(temporary);
            preview.setText("Nowa średnia przedmiotu · " + (Double.isNaN(projected) ? "—" : format(projected)));
        };

        android.widget.Button[] choices = new android.widget.Button[MARKS.length];
        for (int start : new int[]{0, 6}) {
            LinearLayout row = new LinearLayout(this);
            for (int i = start; i < Math.min(start + 6, MARKS.length); i++) {
                double value = i < 6 ? i + 1 : i - 4.5;
                android.widget.Button choice = button(MARKS[i], value == mark[0], () -> {});
                choice.setPadding(0, 0, 0, 0);
                choices[i] = choice;
                choice.setOnClickListener(view -> {
                    mark[0] = value;
                    for (int j = 0; j < choices.length; j++) {
                        boolean active = (j < 6 ? j + 1 : j - 4.5) == value;
                        choices[j].setBackground(shape(active ? colors.selected : colors.panel, 11));
                        choices[j].setTextColor(active ? colors.accent : colors.text);
                        choices[j].setSelected(active);
                    }
                    update.run();
                });
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(42), 1);
                if (i > start) size.setMarginStart(dp(4));
                row.addView(choice, size);
            }
            add(fields, row, 6);
        }

        add(fields, label("Kategoria", 15, colors.text, true), 12);
        android.widget.Button type = button(CATEGORIES[category[0]] + "  ▾", false, () -> {});
        type.setOnClickListener(view -> new android.app.AlertDialog.Builder(this,
                colors.light ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kategoria").setSingleChoiceItems(CATEGORIES, category[0], (chooser, id) -> {
                    category[0] = id;
                    type.setText(CATEGORIES[id] + "  ▾");
                    weight.setText(formField(WEIGHTS[id]));
                    chooser.dismiss();
                }).show());
        add(fields, type, 4);
        add(fields, label("Waga", 15, colors.text, true), 10);
        add(fields, weight, 5);
        add(fields, preview, 12);
        weight.addTextChangedListener(SectionActivity.onText(update));
        update.run();

        Dialog dialog = modal(position < 0 ? "Dodaj ocenę" : "Edytuj ocenę", fields);
        add(fields, button(position < 0 ? "Dodaj ocenę" : "Zapisz ocenę", true, () -> {
            double parsed = weight(weight);
            if (parsed < 0) { weight.setError("Podaj wagę od 0 do 100"); return; }
            try {
                JSONObject entry = new JSONObject().put("value", mark[0]).put("weight", parsed)
                        .put("category", CATEGORIES[category[0]]);
                if (position < 0) grades.put(entry);
                else grades.put(position, entry);
                dialog.dismiss();
                save();
            } catch (JSONException ignored) {}
        }), 12);
        if (position >= 0) add(fields, button("Usuń ocenę", false, () -> {
            grades.remove(position); dialog.dismiss(); save();
        }), 6);
        add(fields, button("Anuluj", false, dialog::dismiss), 6);
        showModal(dialog);
    }

    static double average(JSONArray grades) {
        double weighted = 0, weights = 0;
        for (int i = 0; i < grades.length(); i++) {
            JSONObject grade = grades.optJSONObject(i);
            if (grade == null) continue;
            double value = grade.optDouble("value"), weight = grade.optDouble("weight");
            if (!Double.isFinite(value) || !Double.isFinite(weight) || weight <= 0) continue;
            weighted += value * weight;
            weights += weight;
        }
        return weights == 0 ? Double.NaN : weighted / weights;
    }

    private static double weight(EditText field) {
        try {
            double value = Double.parseDouble(field.getText().toString().replace(',', '.'));
            return Double.isFinite(value) && value >= 0 && value <= 100 ? value : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static String markName(double value) {
        return value == Math.floor(value) ? Integer.toString((int) value) : (int) value + "+";
    }

    private static String formField(double value) { return format(value); }

    private static String format(double value) {
        NumberFormat number = NumberFormat.getNumberInstance(new Locale("pl", "PL"));
        number.setMaximumFractionDigits(2);
        return number.format(value);
    }

    private void save() {
        storage.edit().putString("subjects", subjects.toString()).apply();
        render();
    }
}
