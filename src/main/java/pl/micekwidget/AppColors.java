package pl.micekwidget;

import android.content.Context;

final class AppColors {
    static final int AMOLED = 0;
    static final int DARK = 1;
    static final int LIGHT = 2;
    final boolean light;
    final int background, panel, border, text, muted, accent, selected, warning, green;

    AppColors(Context context) {
        this(appMode(context));
    }

    static int appMode(Context context) {
        android.content.SharedPreferences prefs = SchoolPlan.prefs(context);
        return prefs.getInt("appTheme", prefs.getBoolean("lightTheme", false) ? LIGHT : DARK);
    }

    static boolean appGlass(Context context) {
        int mode = appMode(context);
        return mode != AMOLED && GlassSettings.read(SchoolPlan.prefs(context), "appGlass", mode).enabled;
    }

    static String glassSuffix(int mode) { return mode == LIGHT ? "Light" : "Dark"; }

    static void glassPanel(Context context, android.graphics.drawable.GradientDrawable shape, boolean light, int stroke) {
        android.content.SharedPreferences prefs = SchoolPlan.prefs(context);
        GlassSettings glass = GlassSettings.read(prefs, "appGlass", appMode(context));
        int opacity = glass.opacity;
        int[] gradient = light ? new int[]{0xF5FFFFFF, 0xD5E4EDFF} : new int[]{0xC52C3954, 0xC31A2336};
        for (int i = 0; i < gradient.length; i++) {
            gradient[i] = (gradient[i] & 0xFFFFFF) | (((gradient[i] >>> 24) * opacity / 100) << 24);
        }
        shape.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        shape.setColors(gradient);
        if (glass.edge) {
            shape.setStroke(stroke * 2, light ? 0xFFFFFFFF : 0xACABCBFF);
        }
    }

    AppColors(boolean light) {
        this(light ? LIGHT : DARK);
    }

    AppColors(int mode) {
        light = mode == LIGHT;
        background = light ? 0xFFF3F5FC : mode == AMOLED ? 0xFF000000 : 0xFF10131D;
        panel = light ? 0xFFFFFFFF : mode == AMOLED ? 0xFF0D0D10 : 0xFF1B2232;
        border = light ? 0xFFD2D9ED : mode == AMOLED ? 0xFF25252D : 0xFF303B52;
        text = light ? 0xFF19233C : 0xFFF1F4FF;
        muted = light ? 0xFF52617A : 0xFFABB7CC;
        accent = light ? 0xFF344C9B : 0xFFB8BFFF;
        selected = light ? 0xFFDCE5FF : 0xFF303862;
        warning = light ? 0xFF854500 : 0xFFFFD49C;
        green = light ? 0xFF17613C : 0xFF9DE5BC;
    }
}
