package pl.micekwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

final class AppUpdates {
    private static final String REPO = "https://github.com/miz3x/MicekApp/releases/download/";
    private static final String LATEST = "https://api.github.com/repos/miz3x/MicekApp/releases/latest";
    private static final long MAX_APK = 50L * 1024 * 1024;
    private final Activity activity;
    private final Executor executor;
    private Release awaitingPermission;
    private boolean busy;

    AppUpdates(Activity activity, Executor executor) {
        this.activity = activity;
        this.executor = executor;
    }

    void check(boolean manual) {
        if (busy) return;
        busy = true;
        executor.execute(() -> {
            Release available = null;
            String error = null;
            try {
                org.jsoup.Connection.Response response = Jsoup.connect(LATEST).ignoreContentType(true)
                        .ignoreHttpErrors(true).followRedirects(false).timeout(9000).maxBodySize(500000)
                        .header("Accept", "application/vnd.github+json").execute();
                if (response.statusCode() == 200) {
                    available = select(new JSONObject(response.body()), installed().versionName);
                } else if (response.statusCode() != 404) throw new IOException("GitHub HTTP " + response.statusCode());
            } catch (Exception ignored) {
                error = "Nie udało się sprawdzić aktualizacji.";
            }
            Release release = available;
            String failure = error;
            activity.runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (release != null) {
                    dialog().setTitle("Dostępna aktualizacja " + release.version)
                            .setMessage("Pobrać i zainstalować nową wersję MicekApp z GitHuba?")
                            .setNegativeButton("Później", null)
                            .setPositiveButton("Pobierz", (dialog, which) -> prepare(release)).show();
                } else if (manual) show(failure == null ? "Brak nowszego APK w GitHub Releases." : failure);
            });
        });
    }

    private void prepare(Release release) {
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            download(release);
        } else {
            dialog().setTitle("Zezwól na instalację aktualizacji")
                    .setMessage("Android wymaga zgody na instalowanie aktualizacji przez MicekApp.")
                    .setNegativeButton("Anuluj", null).setPositiveButton("Ustawienia", (dialog, which) -> {
                        try {
                            awaitingPermission = release;
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (android.content.ActivityNotFoundException unavailable) {
                            awaitingPermission = null;
                            show("Nie można otworzyć ustawień instalacji.");
                        }
                    }).show();
        }
    }

    void resumed() {
        Release release = awaitingPermission;
        if (release == null) return;
        awaitingPermission = null;
        if (activity.getPackageManager().canRequestPackageInstalls()) download(release);
    }

    private void download(Release release) {
        if (busy) return;
        busy = true;
        show("Pobieranie aktualizacji…");
        executor.execute(() -> {
            File apk = null;
            boolean verified = false;
            try {
                apk = File.createTempFile("micek-update-", ".apk", activity.getCacheDir());
                transfer(release, apk);
                verify(apk, release);
                java.nio.file.Files.move(apk.toPath(), UpdateApkProvider.file(activity).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                verified = true;
            } catch (Exception ignored) {
                if (apk != null) apk.delete();
            }
            boolean ready = verified;
            activity.runOnUiThread(() -> {
                busy = false;
                if (!alive()) return;
                if (!ready) { show("Nie udało się pobrać lub zweryfikować aktualizacji."); return; }
                try {
                    activity.startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE, UpdateApkProvider.uri(activity))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                    show("Android nie mógł otworzyć instalatora.");
                }
            });
        });
    }

    private void verify(File apk, Release release) throws Exception {
        PackageManager manager = activity.getPackageManager();
        PackageInfo existing = installed();
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), signatureFlag());
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName)
                || versionCode(candidate) <= versionCode(existing)
                || candidate.versionName == null || compare(candidate.versionName, existing.versionName) <= 0
                || compare(release.version, candidate.versionName) != 0
                || !Arrays.equals(signatures(existing), signatures(candidate))) {
            throw new IOException("Nieprawidłowy pakiet, wersja lub podpis");
        }
        Signature[] signature = signatures(candidate);
        if (signature == null || signature.length == 0) throw new IOException("Brak podpisu");
    }

    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), signatureFlag());
    }

    private static int signatureFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static Signature[] signatures(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static Release select(JSONObject data, String current) {
        String tag = data.optString("tag_name", "");
        if (data.optBoolean("draft") || data.optBoolean("prerelease") || compare(tag, current) <= 0) return null;
        JSONArray assets = data.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            long size = asset.optLong("size", 0);
            if (name.matches("(?i)MicekApp[-_A-Za-z0-9.]*\\.apk") && url.equals(REPO + tag + "/" + name)
                    && size > 0 && size <= MAX_APK) {
                return new Release(tag, url, size, LivePlan.text(asset, "digest"));
            }
        }
        return null;
    }

    static int compare(String next, String installed) {
        String format = "[vV]?[0-9]{1,6}(?:\\.[0-9]{1,6}){1,3}";
        if (installed == null || !next.matches(format) || !installed.matches(format)) return 0;
        String[] a = next.replaceFirst("^[vV]", "").split("\\.");
        String[] b = installed.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? Integer.parseInt(a[i]) : 0;
            int right = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private static void transfer(Release release, File target) throws Exception {
        URL url = new URL(release.url);
        HttpURLConnection connection = null;
        try {
            for (int hop = 0; hop < 5; hop++) {
                String host = url.getHost().toLowerCase(Locale.ROOT);
                if (!"https".equals(url.getProtocol()) || (url.getPort() != -1 && url.getPort() != 443)
                        || !Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(host)) {
                    throw new IOException("Niedozwolony adres");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(12000);
                int status = connection.getResponseCode();
                if (status == 200) break;
                String redirected = connection.getHeaderField("Location");
                connection.disconnect();
                connection = null;
                if (status < 300 || status >= 400 || redirected == null) throw new IOException("Pobranie nieudanе");
                url = new URL(url, redirected);
            }
            if (connection == null) throw new IOException("Za dużo przekierowań");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > release.size) throw new IOException("Nieprawidłowy rozmiar APK");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (size != release.size) throw new IOException("Niepełny APK");
            if (!release.digest.isEmpty()) {
                StringBuilder hex = new StringBuilder("sha256:");
                for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!hex.toString().equalsIgnoreCase(release.digest)) throw new IOException("Błędna suma kontrolna APK");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(activity, new AppColors(activity).light
                ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert);
    }

    private boolean alive() { return !activity.isFinishing() && !activity.isDestroyed(); }
    private void show(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

    static final class Release {
        final String version, url, digest;
        final long size;
        Release(String version, String url, long size, String digest) {
            this.version = version;
            this.url = url;
            this.size = size;
            this.digest = digest;
        }
    }
}
