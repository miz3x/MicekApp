package pl.micekwidget;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;

public final class SectionActivity extends NativePage {
    private static final String[] GRADES = {"Celujący", "Bardzo dobry", "Dobry", "Dostateczny", "Dopuszczający", "Niedostateczny"};
    private static final int[][] THRESHOLDS = {{100,90,70,50,30,0}, {100,90,75,60,50,0}, {100,90,85,80,75,0}};
    private CommunityApi api;
    private String section, title, query = "", post;
    private int page = 1, sort, dutyDay, gradeType;
    private int vote;
    private boolean sending;
    private boolean suggesting;
    private CoverImages covers;
    private boolean radioStopped;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        section = getIntent().getStringExtra("section");
        if (section == null) { finish(); return; }
        switch (section) {
            case "radio": title = "Radiowęzeł"; break;
            case "spotted": title = "Spotted"; break;
            case "progi": title = "Progi ocen"; break;
            case "dyzury": title = "Dyżury"; break;
            default: finish(); return;
        }
        api = new CommunityApi(this);
        if (section.equals("radio")) covers = new CoverImages(AppMotion.reduced(this));
        dutyDay = PlanDay.display().getDayOfWeek().getValue() - 1;
        if (LocalDate.now(SchoolPlan.ZONE).toString().equals(SchoolPlan.prefs(this).getString("voteDate", ""))) {
            vote = SchoolPlan.prefs(this).getInt("voteSong", 0);
        }
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        if (!"radio".equals(section) || !radioStopped) return;
        covers = new CoverImages(AppMotion.reduced(this));
        radioStopped = false;
        if (!suggesting && !sending) render();
    }

    @Override protected void onStop() {
        if (covers != null) {
            covers.close();
            covers = null;
            radioStopped = true;
        }
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (sending) return;
        if (suggesting) { suggesting = false; render(); }
        else if (post != null) { post = null; render(); }
        else finish();
    }

    private void render() {
        if (sending) return;
        open(title);
        AppMotion.localBack(this, root, this::onBackPressed, post != null);
        if (covers != null) covers.clear();
        switch (section) {
            case "radio": radio(); break;
            case "spotted": if (post == null) spotted(); else post(); break;
            case "progi": grades(); break;
            case "dyzury": duties(); break;
            default: finish();
        }
    }

    @Override void refreshPage() {
        if (sending || suggesting || section.equals("progi")) finishLocalRefresh();
        else render();
    }

    private void search(String hint) {
        EditText filter = input(hint);
        filter.setSingleLine();
        filter.setText(query);
        add(body, filter, 8);
        add(body, button("Szukaj", false, () -> {
            query = filter.getText().toString().trim();
            page = 1;
            render();
        }), 6);
    }

    private void pagination(LinearLayout target, boolean more) {
        if (page > 1) add(target, button("‹  Poprzednia strona", false, () -> { page--; render(); }), 9);
        if (more) add(target, button("Następna strona  ›", false, () -> { page++; render(); }), 7);
    }

    private void spotted() {
        add(body, button("＋  Nowy post", true, () -> compose(true)), 6);
        chips(body, new String[]{"Najnowsze", "Popularne", "Archiwum"}, sort, selected -> {
            sort = selected; page = 1; render();
        });
        search("Szukaj w postach");
        LinearLayout list = column();
        add(body, list, 9);
        add(list, label("Pobieranie postów…", 14, colors.muted, false), 6);
        String route = "spotted/posts?page=" + page + "&limit=10&sort=" + (sort == 1 ? "popular" : "new")
                + "&tab=" + (sort == 2 ? "archived" : "published") + "&search=" + Uri.encode(query);
        load(() -> api.object(route), data -> {
            list.removeAllViews();
            each(data.optJSONArray("data"), item -> {
                LinearLayout card = card(list, 8);
                postText(card, item, 5);
                String id = safeId(item);
                if (!id.isEmpty()) {
                    add(card, button("♡ " + item.optInt("likesCount") + "     Komentarze " + item.optInt("commentsCount") + "  ›",
                            item.optBoolean("liked"), () -> {
                                AppMotion.remember(this, root);
                                post = id;
                                render();
                            }), 7);
                }
            });
            if (list.getChildCount() == 0) add(list, label("Brak postów.", 15, colors.muted, false), 12);
            JSONObject meta = data.optJSONObject("meta");
            pagination(list, meta != null && page < meta.optInt("totalPages"));
        }, error -> failure(list, this::render));
    }

    private void post() {
        String route = "spotted/posts/" + post;
        LinearLayout list = column();
        add(body, list, 7);
        add(list, label("Pobieranie wpisu…", 14, colors.muted, false), 6);
        load(() -> api.object(route), data -> {
            list.removeAllViews();
            JSONObject item = data.optJSONObject("post");
            if (item == null) { failure(list, this::render); return; }
            LinearLayout entry = card(list, 6);
            postText(entry, item, Integer.MAX_VALUE);
            if (!"archived".equals(value(item, "status")) && item.isNull("archivedAt")) {
                add(entry, button((item.optBoolean("liked") ? "♥ " : "♡ ") + item.optInt("likesCount") + " · Polubienia",
                        item.optBoolean("liked"), () -> send(() -> { api.post(route + "/like", new JSONObject()); return null; }, "")), 9);
                add(entry, button("Dodaj komentarz", true, () -> compose(false)), 7);
            }
            add(list, label("Komentarze", 19, colors.text, true), 15);
            each(data.optJSONArray("comments"), entryData -> {
                LinearLayout comment = card(list, 7);
                add(comment, label(value(entryData, "body"), 15, colors.text, false), 0);
                addTimestamp(comment, entryData, true);
            });
        }, error -> failure(list, this::render));
    }

    private void postText(LinearLayout card, JSONObject item, int limit) {
        String title = value(item, "title");
        if (!title.isEmpty()) add(card, label(title, 17, colors.text, true), 0);
        android.widget.TextView content = label(value(item, "body"), 15, colors.text, false);
        content.setMaxLines(limit);
        content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(card, content, title.isEmpty() ? 0 : 5);
        addTimestamp(card, item, false);
    }

    private void addTimestamp(LinearLayout card, JSONObject item, boolean exact) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(SchoolPlan.ZONE);
        String published = value(item, "createdAt");
        String date = SpottedTime.display(published, now, exact);
        if (!date.isEmpty()) {
            android.widget.TextView timestamp = label(date, 12, colors.muted, false);
            timestamp.setContentDescription(SpottedTime.display(published, now, true));
            add(card, timestamp, 7);
        }
    }

    private void compose(boolean newPost) {
        LinearLayout fields = column();
        EditText title = input("Tytuł (opcjonalnie)");
        title.setSingleLine();
        if (newPost) add(fields, title, 9);
        EditText content = input(newPost ? "Treść posta" : "Komentarz");
        content.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.setMinLines(newPost ? 4 : 3);
        content.setGravity(android.view.Gravity.TOP);
        add(fields, content, 10);
        android.app.Dialog dialog = modal(newPost ? "Nowy post" : "Dodaj komentarz", fields);
        android.widget.Button submit = button(newPost ? "Wyślij post" : "Wyślij komentarz", true, () -> {});
        add(fields, submit, 13);
        add(fields, button("Anuluj", false, dialog::dismiss), 7);
        submit.setOnClickListener(view -> {
            String text = content.getText().toString().trim();
            String subject = title.getText().toString().trim();
            if (text.isEmpty()) { content.setError("Wpisz treść"); return; }
            String path = newPost ? "spotted/posts" : "spotted/posts/" + post + "/comments";
            beginSend(() -> {
                api.object(newPost ? "spotted/posts?limit=1" : "spotted/posts/" + post);
                JSONObject data = new JSONObject().put("body", text).put("_hp", "");
                if (newPost) data.put("title", subject);
                api.post(path, data);
                return null;
            }, () -> { dialog.dismiss(); message("Wysłano do moderacji"); render(); }, submit);
        });
        showModal(dialog);
    }

    private void radio() {
        add(body, button("＋  Zaproponuj utwór", true, this::suggestSong), 6);
        LinearLayout schedule = card(body, 10);
        add(schedule, label("Muzyka na przerwach", 18, colors.text, true), 0);
        add(body, label("Głosowanie na jutro", 19, colors.text, true), 14);
        search("Tytuł lub artysta");
        LinearLayout list = column();
        add(body, list, 8);
        add(list, label("Pobieranie utworów…", 14, colors.muted, false), 4);
        String route = "radio/votes?page=" + page + "&limit=20&q=" + Uri.encode(query);
        load(() -> {
            JSONArray agenda = null;
            try { agenda = api.array("radio/schedule"); } catch (java.io.IOException | org.json.JSONException ignored) {}
            return new JSONObject().put("agenda", agenda).put("votes", api.object(route));
        }, data -> {
            JSONArray agenda = data.optJSONArray("agenda");
            if (agenda == null || agenda.length() == 0) add(schedule, label(agenda == null ? "Nie udało się pobrać playlisty."
                    : "Brak zaplanowanej muzyki na dziś.", 14, colors.muted, false), 7);
            each(agenda, slot -> {
                add(schedule, label("Przerwa " + slot.optInt("number") + " · " + time(slot, "startTime"), 14, colors.accent, true), 9);
                each(slot.optJSONArray("songs"), track -> add(schedule,
                        label(value(track, "title") + " · " + value(track, "artist"), 14, colors.text, false), 4));
            });
            list.removeAllViews();
            JSONObject votes = data.optJSONObject("votes");
            each(votes.optJSONArray("songs"), item -> {
                LinearLayout track = card(list, 7);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.ImageView cover = new android.widget.ImageView(this);
                cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                cover.setBackground(shape(colors.selected, 11));
                cover.setClipToOutline(true);
                row.addView(cover, new LinearLayout.LayoutParams(dp(50), dp(50)));
                if (covers != null) covers.show(value(item, "coverUrl"), cover);
                LinearLayout details = column();
                details.setPadding(dp(10), 0, dp(6), 0);
                add(details, label(value(item, "title"), 15, colors.text, true), 0);
                add(details, label(value(item, "artist"), 13, colors.muted, false), 3);
                row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                int id = item.optInt("id");
                if (id > 0) row.addView(voteButton(id, item.optInt("votes")), new LinearLayout.LayoutParams(dp(56), dp(46)));
                add(track, row, 0);
            });
            if (list.getChildCount() == 0) add(list, label("Brak utworów.", 14, colors.muted, false), 9);
            pagination(list, votes.optBoolean("hasMore"));
        }, error -> failure(list, this::render));
    }

    private android.widget.Button voteButton(int id, int votes) {
        boolean selected = vote == id;
        android.widget.Button action = button((selected ? "♥ " : "♡ ") + votes, selected, () -> {
            send(() -> {
                if (selected) api.delete("radio/votes/" + id);
                else api.post("radio/votes/" + id, null);
                return null;
            }, "", () -> {
                vote = selected ? 0 : id;
                SchoolPlan.prefs(this).edit().putInt("voteSong", vote)
                        .putString("voteDate", LocalDate.now(SchoolPlan.ZONE).toString()).apply();
            });
        });
        action.setEnabled(vote == 0 || selected);
        action.setContentDescription((selected ? "Cofnij głos" : "Oddaj głos") + " · " + votes);
        return action;
    }

    private void suggestSong() {
        AppMotion.remember(this, root);
        suggesting = true;
        open("Zaproponuj utwór");
        AppMotion.localBack(this, root, this::onBackPressed, true);
        if (covers != null) covers.clear();
        LinearLayout form = card(body, 12);
        add(form, label("Link do utworu w Spotify", 18, colors.text, true), 3);
        add(form, label("W Spotify wybierz Udostępnij → Kopiuj link, a potem wklej go poniżej.",
                14, colors.muted, false), 7);
        EditText link = input("https://open.spotify.com/track/…");
        link.setSingleLine();
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        add(form, link, 15);
        add(form, button("Wyślij propozycję", true, () -> {
            String raw = link.getText().toString().trim();
            Uri url = Uri.parse(raw);
            String path = url.getPath();
            if (!"https".equals(url.getScheme()) || !"open.spotify.com".equalsIgnoreCase(url.getHost())
                    || path == null || !path.matches("/(?:intl-[a-z-]+/)?track/[A-Za-z0-9]+")) {
                link.setError("Wklej link utworu z open.spotify.com"); return;
            }
            send(() -> { api.post("radio/suggestions", new JSONObject().put("spotifyUrl", raw)); return null; },
                    "Propozycja została wysłana", () -> suggesting = false);
        }), 12);
    }

    private void duties() {
        chips(body, SchoolPlan.DAYS, dutyDay, day -> { dutyDay = day; render(); });
        EditText filter = input("Filtruj według nauczyciela lub strefy");
        filter.setSingleLine();
        add(body, filter, 10);
        LinearLayout list = column();
        add(body, list, 8);
        add(list, label("Pobieranie dyżurów…", 14, colors.muted, false), 4);
        int day = dutyDay + 1;
        load(() -> api.object("duties?dayOfWeek=" + day), data -> {
            Runnable update = () -> dutyRows(list, data, filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT));
            update.run();
            filter.addTextChangedListener(onText(update));
        }, error -> failure(list, this::render));
    }

    private void dutyRows(LinearLayout list, JSONObject data, String filter) {
        list.removeAllViews();
        JSONObject dictionaries = data.optJSONObject("includes");
        if (dictionaries == null) return;
        each(data.optJSONArray("entries"), entry -> {
            JSONObject teacher = item(dictionaries, "teachers", entry, "teacherId");
            JSONObject zone = item(dictionaries, "zones", entry, "zoneId");
            JSONObject period = item(dictionaries, "periods", entry, "periodId");
            String name = (value(teacher, "firstName") + " " + value(teacher, "lastName")).trim();
            String place = value(zone, "name");
            if (!(name + " " + place + " " + value(zone, "code")).toLowerCase(java.util.Locale.ROOT).contains(filter)) return;
            LinearLayout card = card(list, 7);
            add(card, label(time(period, "startTime") + "–" + time(period, "endTime") + " · " + place,
                    14, colors.accent, true), 0);
            add(card, label(name, 16, colors.text, true), 4);
        });
        if (list.getChildCount() == 0) add(list, label("Brak dyżurów dla tego wyboru.", 15, colors.muted, false), 9);
    }

    private void grades() {
        EditText maximum = numeric("Maksymalna liczba punktów");
        EditText scored = numeric("Zdobyte punkty");
        String[] types = {"Ogólne", "Zawodowe teoretyczne", "Zawodowe praktyczne"};
        android.content.Context themed = new android.view.ContextThemeWrapper(this, colors.light
                ? android.R.style.Theme_Material_Light : android.R.style.Theme_Material);
        android.widget.Spinner spinner = new android.widget.Spinner(themed);
        android.widget.ArrayAdapter<String> typesAdapter = new android.widget.ArrayAdapter<>(themed,
                android.R.layout.simple_spinner_item, types);
        typesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(typesAdapter);
        add(body, spinner, 7);
        add(body, maximum, 8);
        add(body, scored, 8);
        LinearLayout rows = column();
        add(body, rows, 10);
        Runnable update = () -> {
            rows.removeAllViews();
            double max = number(maximum), obtained = number(scored);
            int[] thresholds = THRESHOLDS[gradeType];
            if (max > 0 && obtained >= 0 && obtained <= max) {
                int grade = 0;
                while (grade < 5 && obtained < threshold(max, thresholds[grade])) grade++;
                add(card(rows, 6), label("Ocena " + (6 - grade) + " · " + GRADES[grade], 18, colors.accent, true), 0);
            }
            for (int i = 0; i < thresholds.length; i++) {
                String condition = max > 0 ? " · od " + java.text.NumberFormat.getInstance(new java.util.Locale("pl", "PL"))
                        .format(threshold(max, thresholds[i])) + " pkt" : "";
                add(card(rows, 7), label((6 - i) + "  " + GRADES[i] + " · " + thresholds[i] + "%" + condition,
                        14, colors.text, true), 0);
            }
        };
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                gradeType = position; update.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        maximum.addTextChangedListener(onText(update));
        scored.addTextChangedListener(onText(update));
        update.run();
    }

    static double threshold(double max, int percentage) {
        return percentage == 100 ? max : Math.ceil(max * percentage / 50.0) / 2;
    }

    private EditText numeric(String hint) {
        EditText input = input(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return input;
    }

    private static double number(EditText field) {
        try { return Double.parseDouble(field.getText().toString().replace(',', '.')); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private void send(Work<Void> work, String message) { send(work, message, () -> {}); }

    private void send(Work<Void> work, String text, Runnable completed) {
        beginSend(work, () -> {
            completed.run();
            if (!text.isEmpty()) message(text);
            render();
        }, null);
    }

    private void beginSend(Work<Void> work, Runnable done, android.widget.Button submit) {
        if (sending) return;
        sending = true;
        if (submit != null) submit.setEnabled(false);
        load(work, result -> {
            sending = false;
            done.run();
        }, error -> {
            sending = false;
            if (submit != null) submit.setEnabled(true);
            message(error.getMessage() == null ? "Nie udało się wysłać danych." : error.getMessage());
        });
    }

    static TextWatcher onText(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable value) {}
        };
    }

    static JSONObject item(JSONObject dictionaries, String map, JSONObject entry, String id) {
        JSONObject values = dictionaries.optJSONObject(map);
        return values == null ? null : values.optJSONObject(value(entry, id));
    }

    static void each(JSONArray rows, java.util.function.Consumer<JSONObject> action) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item != null) action.accept(item);
        }
    }

    static String value(JSONObject data, String key) { return LivePlan.text(data, key); }

    static String time(JSONObject item, String key) {
        String value = value(item, key);
        return value.substring(0, Math.min(5, value.length()));
    }

    private static String safeId(JSONObject data) {
        String id = value(data, "id");
        return id.matches("[0-9a-fA-F-]{36}") ? id : "";
    }
}
