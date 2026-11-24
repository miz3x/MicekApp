package pl.micekwidget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class RefreshJob extends JobService {
    private static final int PERIODIC = 21;
    private static final int IMMEDIATE = 22;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<JobParameters> active = new HashSet<>();

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        long interval = SchoolPlan.prefs(context).getInt("refreshMinutes", 15) * 60000L;
        JobInfo pending = scheduler.getPendingJob(PERIODIC);
        if (pending == null || pending.getIntervalMillis() != interval) {
            scheduler.schedule(builder(context, PERIODIC).setPersisted(true)
                    .setPeriodic(interval).build());
        }
    }

    static boolean refresh(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(IMMEDIATE) != null) return true;
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
                scheduler.schedule(builder(context, IMMEDIATE).setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS) {
            return true;
        }
        return scheduler.schedule(builder(context, IMMEDIATE).build()) == JobScheduler.RESULT_SUCCESS;
    }

    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    @Override public boolean onStartJob(JobParameters params) {
        active.add(params);
        List<SharedPreferences> selections = new ArrayList<>();
        SharedPreferences app = SchoolPlan.prefs(this);
        selections.add(app);
        for (int id : PlanWidget.ids(this)) selections.add(PlanWidget.settings(this, id));
        List<Target> targets = new ArrayList<>();
        for (SharedPreferences prefs : selections) {
            Target target = new Target(prefs);
            if (!target.url.isEmpty()) targets.add(target);
        }
        Handler handler = new Handler(getMainLooper());
        executor.execute(() -> {
            Map<String, Snapshot> fetched = new HashMap<>();
            LocalDate today = LocalDate.now(SchoolPlan.ZONE);
            LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
            for (Target target : targets) {
                if (Thread.currentThread().isInterrupted()) break;
                Snapshot snapshot = fetched.computeIfAbsent(target.url, key -> fetch(target, monday,
                        SchoolNotifications.allowed(this) && SchoolNotifications.enabled(this, SchoolNotifications.PLAN)));
                handler.post(() -> {
                    if (!active.contains(params)) return;
                    if (target.prefs == app && snapshot.changes != null && target.url.equals(app.getString("url", ""))) {
                        SchoolNotifications.plan(this, target.name, app.getString(target.teacher ? "teacherAlerts" : "changes", ""),
                                snapshot.changes, target.teacher);
                    }
                    target.save(snapshot);
                });
            }
            SchoolNotifications.poll(this);
            handler.post(() -> {
                if (!active.remove(params)) return;
                PlanWidget.render(this);
                jobFinished(params, false);
            });
        });
        return true;
    }

    private static Snapshot fetch(Target target, LocalDate monday, boolean planNotifications) {
        Snapshot result = new Snapshot();
        if (target.teacher) {
            try {
                result.week = LivePlan.schedule(LivePlan.read("schedule?teacherId=" + target.url.substring(8)));
            } catch (IOException | JSONException ignored) {
            }
            if (planNotifications) {
                try { result.changes = LivePlan.changes(target.url.substring(8), monday, true); }
                catch (IOException | JSONException ignored) {}
            }
            return result;
        }
        result.classId = target.classId;
        try {
            if (result.classId.isEmpty()) result.classId = LivePlan.classId(target.name);
        } catch (IOException | JSONException ignored) {
        }
        if (!result.classId.isEmpty()) {
            try {
                result.week = LivePlan.schedule(LivePlan.read("schedule?classId=" + result.classId));
            } catch (IOException | JSONException ignored) {
            }
            try {
                result.changes = LivePlan.changes(result.classId, monday);
            } catch (IOException | JSONException ignored) {
            }
        }
        if (result.week == null && target.url.startsWith(SchoolPlan.BASE)) {
            try {
                result.fallback = SchoolPlan.fetch(target.url);
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    @Override public boolean onStopJob(JobParameters params) {
        active.remove(params);
        return true;
    }

    @Override public void onDestroy() {
        active.clear();
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class Snapshot {
        String classId = "";
        JSONArray week;
        JSONArray fallback;
        JSONObject changes;
    }

    private static final class Target {
        final SharedPreferences prefs;
        final String url;
        final String name;
        final String classId;
        final boolean teacher;

        Target(SharedPreferences prefs) {
            this.prefs = prefs;
            url = prefs.getString("url", "");
            name = prefs.getString("name", "");
            classId = prefs.getString("liveClassId", "");
            teacher = url.startsWith("teacher:");
        }

        void save(Snapshot snapshot) {
            if (!url.equals(prefs.getString("url", ""))) return;
            SharedPreferences.Editor edit = prefs.edit().remove("refreshRequested").remove("refreshRejected")
                    .putLong("refreshFinished", System.currentTimeMillis());
            if (!teacher) {
                if (!snapshot.classId.isEmpty()) edit.putString("liveClassId", snapshot.classId);
                if (snapshot.changes != null) {
                    edit.putString("changes", snapshot.changes.toString()).remove("changesError");
                } else {
                    edit.putBoolean("changesError", true);
                }
            } else if (snapshot.changes != null) edit.putString("teacherAlerts", snapshot.changes.toString());
            JSONArray week = snapshot.week;
            if (week == null && !prefs.contains("week")) week = snapshot.fallback;
            if (week != null) {
                edit.putString("week", week.toString()).putLong("updated", System.currentTimeMillis())
                        .remove("error");
            } else {
                edit.putBoolean("error", true);
            }
            edit.apply();
        }
    }
}
