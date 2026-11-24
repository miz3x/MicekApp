package pl.micekwidget;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

abstract class NativePage extends Activity {
    final ExecutorService network = Executors.newSingleThreadExecutor();
    AppColors colors;
    LinearLayout root, body;
    ScrollView scroll;
    PullRefresh pull;
    private int generation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        colors = new AppColors(this);
        AppMotion.transition(this);
        root = column();
        root.setBackgroundColor(colors.background);
        getWindow().setStatusBarColor(colors.background);
        getWindow().setNavigationBarColor(colors.background);
        if (colors.light) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
    }

    void open(String title) {
        boolean refreshing = pull != null && pull.isRefreshing();
        root.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(14), dp(8));
        Button back = button("‹", false, this::onBackPressed);
        back.setContentDescription("Wróć");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = label(title, 25, colors.text, true);
        heading.setPadding(dp(10), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        pull = new PullRefresh(this, colors.panel, colors.accent, this::refreshPage);
        scroll = pull.scroll;
        body = column();
        body.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(body);
        root.addView(pull, new LinearLayout.LayoutParams(-1, 0, 1));
        if (refreshing) pull.show();
        AppMotion.enter(body);
    }

    abstract void refreshPage();

    void finishLocalRefresh() {
        PullRefresh active = pull;
        active.postDelayed(active::finish, 300);
    }

    <T> void load(Work<T> work, java.util.function.Consumer<T> success, java.util.function.Consumer<Exception> error) {
        int token = ++generation;
        network.execute(() -> {
            try {
                T result = work.run();
                runOnUiThread(() -> {
                    if (!live(token)) return;
                    try { success.accept(result); }
                    finally { if (pull != null) pull.finish(); }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!live(token)) return;
                    try { error.accept(failure); }
                    finally { if (pull != null) pull.finish(); }
                });
            }
        });
    }

    private boolean live(int token) {
        return token == generation && !isDestroyed() && !isFinishing();
    }

    interface Work<T> { T run() throws Exception; }

    void failure(LinearLayout target, Runnable retry) {
        target.removeAllViews();
        add(target, label("Nie udało się pobrać danych.", 15, colors.warning, true), 10);
        add(target, button("Spróbuj ponownie", false, retry), 8);
    }

    void message(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    android.app.Dialog modal(String heading, LinearLayout fields) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout frame = column();
        frame.setBackground(shape(colors.panel, 23));
        frame.setPadding(dp(18), dp(17), dp(18), dp(19));
        add(frame, label(heading, 21, colors.text, true), 0);
        add(frame, fields, 10);
        scroll.addView(frame);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    void showModal(android.app.Dialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
            if (AppMotion.reduced(this)) dialog.getWindow().setWindowAnimations(0);
            else AppMotion.enter(dialog.getWindow().getDecorView());
        }
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout card(LinearLayout parent, int gap) {
        LinearLayout layout = column();
        layout.setPadding(dp(14), dp(11), dp(14), dp(12));
        layout.setBackground(shape(colors.panel, 17));
        add(parent, layout, gap);
        return layout;
    }

    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), colors.border);
        if (color == colors.panel && AppColors.appGlass(this)) AppColors.glassPanel(this, drawable, colors.light, dp(1));
        else drawable.setColor(color);
        return drawable;
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    Button button(String text, boolean selected, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(selected ? colors.accent : colors.text);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinimumHeight(dp(44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x333F7BFF),
                shape(selected ? colors.selected : colors.panel, 14), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    EditText input(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(colors.text);
        field.setHintTextColor(colors.muted);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(shape(colors.panel, 13));
        return field;
    }

    void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(gap);
        parent.addView(child, size);
    }

    void chips(LinearLayout parent, String[] labels, int active, java.util.function.IntConsumer select) {
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(44));
            if (i > 0) size.setMarginStart(dp(6));
            row.addView(button(labels[i], active == i, () -> select.accept(index)), size);
        }
        strip.addView(row);
        add(parent, strip, 8);
    }

    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        AppMotion.localBack(this, root, this::onBackPressed, false);
        generation++;
        network.shutdownNow();
        super.onDestroy();
    }
}
