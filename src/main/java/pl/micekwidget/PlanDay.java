package pl.micekwidget;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

final class PlanDay {
    static final int ROLLOVER_HOUR = 18;
    static LocalDate display(ZonedDateTime now) {
        LocalDate date = now.toLocalDate();
        if (now.getHour() >= ROLLOVER_HOUR) date = date.plusDays(1);
        while (date.getDayOfWeek().getValue() > 5) date = date.plusDays(1);
        return date;
    }

    static LocalDate display() {
        return display(ZonedDateTime.now(SchoolPlan.ZONE));
    }

    static String label(LocalDate today, LocalDate date) {
        if (date.equals(today)) return "Dziś";
        if (date.equals(today.plusDays(1))) return "Jutro";
        return SchoolPlan.DAYS[date.getDayOfWeek().getValue() - 1];
    }

    static String announcementsLabel(LocalDate today, LocalDate date) {
        if (date.equals(today)) return "Ogłoszenia dzisiejsze";
        if (date.equals(today.plusDays(1))) return "Ogłoszenia jutrzejsze";
        return "Ogłoszenia na " + date.format(java.time.format.DateTimeFormatter.ofPattern("d.MM"));
    }

    static boolean active(LivePlan.Lesson lesson, LocalDate date, ZonedDateTime now) {
        return !lesson.cancelled && date.equals(now.toLocalDate()) && minute(lesson.start) <= minute(now)
                && minute(now) < minute(lesson.end) && minute(lesson.start) >= 0;
    }

    static int minute(ZonedDateTime now) {
        return now.getHour() * 60 + now.getMinute();
    }

    static int minute(String time) {
        String[] parts = time.split(":");
        if (parts.length != 2) return -1;
        try {
            return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])).toSecondOfDay() / 60;
        } catch (RuntimeException invalid) {
            return -1;
        }
    }
}
