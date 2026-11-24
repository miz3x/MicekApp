package pl.micekwidget;

import android.content.SharedPreferences;

final class GlassSettings {
    final boolean enabled;
    final int opacity;
    final boolean edge;

    private GlassSettings(boolean enabled, int opacity, boolean edge) {
        this.enabled = enabled;
        this.opacity = opacity;
        this.edge = edge;
    }

    static GlassSettings read(SharedPreferences prefs, String key, int mode) {
        String legacy = AppColors.glassSuffix(mode);
        if (mode == AppColors.AMOLED && !prefs.getBoolean(key + "Dark", false)
                && prefs.getBoolean(key + "Light", false)) legacy = "Light";
        return new GlassSettings(
                prefs.getBoolean(key, prefs.getBoolean(key + legacy, false)),
                prefs.getInt(key + "Opacity", prefs.getInt(key + "Opacity" + legacy, 100)),
                prefs.getBoolean(key + "Edge", prefs.getBoolean(key + "Edge" + legacy, false)));
    }

    void save(SharedPreferences.Editor editor, String key) {
        editor.putBoolean(key, enabled).putInt(key + "Opacity", opacity).putBoolean(key + "Edge", edge);
    }
}
