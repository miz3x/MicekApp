package pl.micekwidget;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

final class SpottedTime {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("pl", "PL"));

    static String display(String value, ZonedDateTime now, boolean exactTime) {
        if (value.isEmpty()) return "";
        ZonedDateTime when;
        try { when = Instant.parse(value).atZone(now.getZone()); }
        catch (java.time.format.DateTimeParseException invalid) { return ""; }
        long days = ChronoUnit.DAYS.between(when.toLocalDate(), now.toLocalDate());
        long minutes = Math.max(0, Duration.between(when, now).toMinutes());
        long hours = minutes / 60;
        String time = when.format(HOUR);
        if (exactTime) {
            String date = days == 0 ? "Dzisiaj" : days == 1 ? "Wczoraj" : when.format(DATE);
            return date + ", " + time;
        }
        if (days == 0) {
            if (minutes < 1) return "Przed chwilą";
            if (hours == 0) return minutes + (minutes == 1 ? " minutę" : few(minutes) ? " minuty" : " minut") + " temu";
            if (hours <= 6) return hours + (hours == 1 ? " godzinę" : few(hours) ? " godziny" : " godzin") + " temu";
            return "Dzisiaj, " + time;
        }
        if (days == 1) return "Wczoraj, " + time;
        if (days >= 2 && days < 7) return days + " dni temu";
        return when.format(DATE);
    }

    private static boolean few(long value) {
        return value % 10 >= 2 && value % 10 <= 4 && (value % 100 < 12 || value % 100 > 14);
    }
}
