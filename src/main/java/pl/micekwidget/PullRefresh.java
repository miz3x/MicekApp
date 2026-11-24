package pl.micekwidget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;

final class PullRefresh extends FrameLayout {
    final ScrollView scroll;
    private final ProgressBar indicator;
    private final Runnable refresh;
    private final int threshold, slop, hidden, resting;
    private final boolean reducedMotion;
    private float downX, downY;
    private boolean eligible, dragging, refreshing;

    PullRefresh(Context context, int panel, int accent, Runnable refresh) {
        super(context);
        this.refresh = refresh;
        reducedMotion = AppMotion.reduced(context);
        threshold = dp(68);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        hidden = -dp(48);
        resting = dp(13);
        scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(-1, -1));
        indicator = new ProgressBar(context);
        indicator.setIndeterminateTintList(ColorStateList.valueOf(accent));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(panel);
        indicator.setBackground(circle);
        indicator.setPadding(dp(8), dp(8), dp(8), dp(8));
        indicator.setElevation(dp(7));
        indicator.setTranslationY(hidden);
        indicator.setVisibility(INVISIBLE);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(indicator, new LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            eligible = !refreshing && !scroll.canScrollVertically(-1);
            dragging = false;
        } else if (eligible && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float horizontal = Math.abs(event.getX() - downX);
            float vertical = event.getY() - downY;
            if (horizontal > slop && horizontal > Math.abs(vertical)) eligible = false;
            if (eligible && vertical > slop && vertical > horizontal && !scroll.canScrollVertically(-1)) {
                dragging = true;
                pull(vertical);
                return true;
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            eligible = false;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return false;
        float distance = event.getY() - downY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pull(distance);
                break;
            case MotionEvent.ACTION_UP:
                dragging = false;
                eligible = false;
                if (distance >= threshold) {
                    show();
                    refresh.run();
                } else finish();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                eligible = false;
                finish();
                break;
            default: break;
        }
        return true;
    }

    private void pull(float distance) {
        indicator.animate().cancel();
        indicator.setVisibility(VISIBLE);
        indicator.setTranslationY(hidden + (resting - hidden) * Math.min(1f, Math.max(0f, distance / threshold)));
    }

    boolean isRefreshing() { return refreshing; }

    void show() {
        refreshing = true;
        indicator.setVisibility(VISIBLE);
        indicator.animate().cancel();
        indicator.animate().translationY(resting).setDuration(reducedMotion ? 0 : 180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    void finish() {
        refreshing = false;
        indicator.animate().cancel();
        indicator.animate().translationY(hidden).setDuration(reducedMotion ? 0 : 170)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                    if (!refreshing && !dragging) indicator.setVisibility(INVISIBLE);
                }).start();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
