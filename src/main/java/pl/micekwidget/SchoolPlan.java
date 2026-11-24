package pl.micekwidget;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

final class SchoolPlan {
    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");
    static final String BASE = "https://mickiewicz.net.pl/planlekcji/aktualny/";
    static final String[] DAYS = {"Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek"};

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("plan", Context.MODE_PRIVATE);
    }

    static List<ClassPlan> classes(String school) throws IOException {
        Document page = Jsoup.connect(BASE + school + "/lista.html").timeout(12000).get();
        List<ClassPlan> result = new ArrayList<>();
        for (Element link : page.select("a[href]")) {
            String url = link.absUrl("href");
            if (url.matches("https://(?:www\\.)?mickiewicz\\.net\\.pl/planlekcji/aktualny/"
                    + school + "/plany/o[0-9]+\\.html") && !link.text().isEmpty()) {
                result.add(new ClassPlan(link.text(), url));
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Nie znaleziono listy klas");
        }
        return result;
    }

    static JSONArray fetch(String url) throws IOException {
        Document page = Jsoup.connect(url).timeout(12000).get();
        JSONArray week = new JSONArray();
        for (int day = 0; day < DAYS.length; day++) {
            week.put(new JSONArray());
        }
        Element header = page.select("tr").stream()
                .filter(row -> row.text().contains("Poniedziałek") && row.text().contains("Piątek"))
                .findFirst().orElse(null);
        if (header == null) throw new IOException("Brak tabeli planu");
        boolean hasRows = false;
        for (Element row : header.parent().select("tr")) {
            Elements cells = row.children();
            if (cells.size() != 7 || !cells.get(1).text().matches(".*[0-9]{1,2}:[0-9]{2}.*")) continue;
            hasRows = true;
            for (int day = 0; day < DAYS.length; day++) {
                String lesson = cells.get(day + 2).text().trim();
                if (!lesson.isEmpty() && !lesson.equals("-")) {
                    week.optJSONArray(day).put(cells.get(1).text() + "  " + lesson);
                }
            }
        }
        if (!hasRows) throw new IOException("Tabela nie zawiera godzin");
        return week;
    }

    static String displayDay(JSONArray week, int day) {
        if (day >= DAYS.length) return "Dziś nie ma lekcji w planie";
        JSONArray lessons = week.optJSONArray(day);
        if (lessons == null || lessons.length() == 0) return "Brak lekcji w planie";
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < lessons.length(); i++) lines.add(lessons.optString(i));
        return String.join("\n", lines);
    }

    static final class ClassPlan {
        final String name;
        final String url;

        ClassPlan(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
