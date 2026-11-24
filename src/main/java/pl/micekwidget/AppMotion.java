package pl.micekwidget;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;

final class AppMotion {
    static boolean reduced(android.content.Context context) {
        return SchoolPlan.prefs(context).getBoolean("performanceMode", false)
                || !android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    static void enter(View view) {
        if (reduced(view.getContext())) return;
        view.setAlpha(0.72f);
        view.setTranslationY(view.getResources().getDisplayMetrics().density * 7);
        view.animate().alpha(1f).translationY(0).setDuration(180).start();
    }

    static void transition(Activity activity) {
        if (!reduced(activity)) return;
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
        } else activity.overridePendingTransition(0, 0);
    }

    static void localBack(Activity activity, View current, Runnable action, boolean local) {
        if (Build.VERSION.SDK_INT >= 34) Api34.update(activity, current, action, local);
    }

    static void remember(Activity activity, View current) {
        if (Build.VERSION.SDK_INT >= 34 && !reduced(activity)) Api34.remember(activity, current);
    }

    private static final class Api34 {
        private static final java.util.WeakHashMap<Activity, OnBackAnimationCallback> callbacks = new java.util.WeakHashMap<>();
        private static final java.util.WeakHashMap<Activity, android.graphics.Bitmap> previews = new java.util.WeakHashMap<>();

        static void remember(Activity activity, View current) {
            if (current.getWidth() < 1 || current.getHeight() < 1) return;
            float scale = Math.min(1f, 600f / Math.max(current.getWidth(), current.getHeight()));
            android.graphics.Bitmap picture = android.graphics.Bitmap.createBitmap(
                    Math.max(1, Math.round(current.getWidth() * scale)),
                    Math.max(1, Math.round(current.getHeight() * scale)), android.graphics.Bitmap.Config.RGB_565);
            android.graphics.Canvas canvas = new android.graphics.Canvas(picture);
            canvas.scale(scale, scale);
            current.draw(canvas);
            previews.put(activity, picture);
        }

        static void update(Activity activity, View current, Runnable action, boolean local) {
            OnBackAnimationCallback previous = callbacks.remove(activity);
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            if (previous != null) dispatcher.unregisterOnBackInvokedCallback(previous);
            if (!local) { previews.remove(activity); return; }
            boolean reduced = AppMotion.reduced(activity);
            OnBackAnimationCallback callback = new OnBackAnimationCallback() {
                private View parent;
                private android.graphics.drawable.Drawable previousBackground;

                @Override public void onBackStarted(BackEvent event) {
                    if (reduced || !(current.getParent() instanceof View)) return;
                    android.graphics.Bitmap picture = previews.get(activity);
                    if (picture == null) return;
                    parent = (View) current.getParent();
                    previousBackground = parent.getBackground();
                    android.graphics.drawable.BitmapDrawable preview =
                            new android.graphics.drawable.BitmapDrawable(activity.getResources(), picture);
                    preview.setGravity(android.view.Gravity.FILL);
                    parent.setBackground(preview);
                }
                @Override public void onBackProgressed(BackEvent event) {
                    if (reduced) return;
                    float progress = event.getProgress();
                    float sign = event.getSwipeEdge() == BackEvent.EDGE_LEFT ? 1 : -1;
                    current.setPivotX(sign > 0 ? current.getWidth() : 0);
                    current.setTranslationX(sign * progress * current.getWidth() * 0.13f);
                    current.setScaleX(1 - 0.08f * progress);
                    current.setScaleY(1 - 0.08f * progress);
                    current.setRotationY(sign * -5 * progress);
                }

                @Override public void onBackCancelled() {
                    if (!reduced) current.animate().translationX(0).scaleX(1).scaleY(1).rotationY(0).setDuration(150).start();
                    restoreBackground();
                }

                @Override public void onBackInvoked() {
                    current.animate().cancel();
                    current.setTranslationX(0); current.setScaleX(1); current.setScaleY(1); current.setRotationY(0);
                    restoreBackground();
                    previews.remove(activity);
                    action.run();
                }

                private void restoreBackground() {
                    if (parent != null) parent.setBackground(previousBackground);
                    parent = null;
                }
            };
            callbacks.put(activity, callback);
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        }
    }
}
