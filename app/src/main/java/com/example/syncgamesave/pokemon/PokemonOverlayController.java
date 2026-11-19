package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.example.syncgamesave.ui.Ui;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating ball, region selection layer, long-press menu and result card. The host (a foreground service
 * or the accessibility service) supplies the window type and the screenshot source.
 */
public class PokemonOverlayController implements OverlayTool {
    private static final String PREFS = "pokemon_tool";
    private static final String KEY_LEFT = "region_left";
    private static final String KEY_TOP = "region_top";
    private static final String KEY_RIGHT = "region_right";
    private static final String KEY_BOTTOM = "region_bottom";
    private static final String KEY_SCREEN_W = "screen_width";
    private static final String KEY_SCREEN_H = "screen_height";
    private static volatile boolean active;

    private final Context context;
    private final WindowManager windowManager;
    private final int windowType;
    private final FrameSource source;
    private final Runnable onExit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PokemonRecognizer recognizer;
    private PokemonMatcher matcher;
    private PokemonData data;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private ResultCardView card;
    private WindowManager.LayoutParams cardParams;
    private LinearLayout menu;
    /** Last selected region as fractions of the screen, valid only for the screen size it was selected on. */
    private RectF savedRegion;
    private Point savedScreen;
    private View selection;
    private List<MatchCandidate> candidates = new ArrayList<>();
    private boolean busy;
    private boolean started;
    private boolean destroyed;

    public PokemonOverlayController(Context context, int windowType, FrameSource source, Runnable onExit) {
        this.context = context;
        this.windowType = windowType;
        this.source = source;
        this.onExit = onExit;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean isActive() { return active; }

    @Override public void start() throws IOException {
        data = PokemonRepository.load(context);
        matcher = new PokemonMatcher(data, PokemonAliasStore.load(context, data));
        recognizer = new PokemonRecognizer(matcher);
        loadRegion();
        createBall();
        started = true;
        active = true;
        Toast.makeText(context, "宝可梦工具已启动：点击悬浮球识别，长按悬浮球打开菜单", Toast.LENGTH_LONG).show();
    }

    /** Safe to call on a controller whose start() failed part way. */
    @Override public void stop() {
        destroyed = true;
        if (started) active = false;
        main.removeCallbacksAndMessages(null);
        removeSelection();
        removeMenu();
        removeCard();
        if (ball != null) {
            try { windowManager.removeView(ball); } catch (RuntimeException ignored) { }
            ball = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    @Override public void onConfigurationChanged() {
        if (!started || destroyed) return;
        if (selection != null) cancelSelection();
        removeMenu();
        removeCard();
        clampBall();
    }

    // ---- floating ball ----

    private void createBall() {
        TextView view = new TextView(context);
        view.setText("宝");
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xCC3F7CE0);
        background.setStroke(dp(2), 0xCCFFFFFF);
        view.setBackground(background);
        Point size = ScreenCapture.realSize(windowManager);
        ballParams = overlayParams(dp(48), dp(48),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        ballParams.x = dp(8);
        ballParams.y = size.y / 3;
        view.setOnTouchListener(new BallTouchListener());
        windowManager.addView(view, ballParams);
        ball = view;
    }

    private void clampBall() {
        if (ball == null) return;
        Point size = ScreenCapture.realSize(windowManager);
        ballParams.x = Math.max(0, Math.min(ballParams.x, size.x - dp(48)));
        ballParams.y = Math.max(0, Math.min(ballParams.y, size.y - dp(48)));
        windowManager.updateViewLayout(ball, ballParams);
    }

    private void setBallVisible(boolean visible) {
        if (ball != null) ball.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private class BallTouchListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;
        private boolean longPressed;
        private final Runnable longPress = () -> {
            longPressed = true;
            showMenu();
        };

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = ballParams.x;
                    startY = ballParams.y;
                    dragging = false;
                    longPressed = false;
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        main.removeCallbacks(longPress);
                        // Moving after a long press turns it into a drag, so the menu is no longer wanted.
                        if (longPressed) removeMenu();
                    }
                    if (dragging) {
                        ballParams.x = startX + Math.round(dx);
                        ballParams.y = startY + Math.round(dy);
                        windowManager.updateViewLayout(view, ballParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (!dragging && !longPressed) onBallClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---- capture and selection ----

    /** A tap reuses the saved region; the user only draws a region the first time or after choosing to change it. */
    private void onBallClick() {
        if (busy || destroyed) return;
        removeMenu();
        boolean select = !hasSavedRegion();
        if (select && savedRegion != null)
            Toast.makeText(context, "屏幕尺寸已变化，请重新圈选区域", Toast.LENGTH_SHORT).show();
        startCapture(select);
    }

    private void startCapture(boolean select) {
        busy = true;
        removeCard();
        setBallVisible(false);
        source.capture(new FrameSource.Callback() {
            @Override public void onFrame(Bitmap frame) {
                if (destroyed) return;
                if (select) showSelection(frame);
                else {
                    setBallVisible(true);
                    recognizeRegion(frame, savedRegion);
                }
            }

            @Override public void onError(String message) {
                if (destroyed) return;
                setBallVisible(true);
                busy = false;
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSelection(Bitmap frame) {
        FrameLayout root = new FrameLayout(context) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) cancelSelection();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.addView(new RegionSelectView(context, frame, fractions -> finishSelection(frame, fractions)),
                new FrameLayout.LayoutParams(-1, -1));
        TextView cancel = new TextView(context);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC202124);
        background.setCornerRadius(dp(20));
        cancel.setBackground(background);
        cancel.setOnClickListener(view -> cancelSelection());
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cancelParams.setMargins(0, dp(32), dp(16), 0);
        root.addView(cancel, cancelParams);

        Point size = ScreenCapture.realSize(windowManager);
        WindowManager.LayoutParams params = overlayParams(size.x, size.y,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 28)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            windowManager.addView(root, params);
        } catch (RuntimeException e) {
            setBallVisible(true);
            busy = false;
            Toast.makeText(context, "无法显示选区层：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        selection = root;
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private void cancelSelection() {
        removeSelection();
        setBallVisible(true);
        busy = false;
    }

    private void removeSelection() {
        if (selection == null) return;
        try { windowManager.removeView(selection); } catch (RuntimeException ignored) { }
        selection = null;
    }

    private void finishSelection(Bitmap frame, RectF fractions) {
        removeSelection();
        setBallVisible(true);
        saveRegion(fractions);
        recognizeRegion(frame, fractions);
    }

    private void recognizeRegion(Bitmap frame, RectF fractions) {
        int left = clamp(Math.round(fractions.left * frame.getWidth()), 0, frame.getWidth() - 1);
        int top = clamp(Math.round(fractions.top * frame.getHeight()), 0, frame.getHeight() - 1);
        int right = clamp(Math.round(fractions.right * frame.getWidth()), left + 1, frame.getWidth());
        int bottom = clamp(Math.round(fractions.bottom * frame.getHeight()), top + 1, frame.getHeight());
        Bitmap crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top);
        matcher.setAliases(PokemonAliasStore.load(context, data));
        showCard();
        card.showLoading("识别中……");
        recognizer.recognize(crop, PokemonSettings.retries(context), new PokemonRecognizer.Callback() {
            @Override public void onAttempt(int attempt, int total) {
                if (destroyed || card == null) return;
                card.showLoading(attempt == 1 ? "识别中……" : "识别中……（第 " + attempt + "/" + total + " 次尝试）");
            }

            @Override public void onResult(List<String> lines, List<MatchCandidate> found, int attempts) {
                if (destroyed) return;
                busy = false;
                showRecognized(lines, found, attempts);
            }

            @Override public void onError(Exception error) {
                if (destroyed) return;
                busy = false;
                showCard();
                card.showManualSearch("识别失败", (error.getMessage() == null ? "文字识别出错" : error.getMessage())
                        + "\n可以直接输入名字搜索。", "");
            }
        });
    }

    // ---- long-press menu and saved region ----

    private void showMenu() {
        if (busy || menu != null || ball == null) return;
        Point size = ScreenCapture.realSize(windowManager);
        int width = dp(120);
        int height = dp(96);
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202124);
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        view.addView(menuItem("修改区域", () -> {
            removeMenu();
            if (!busy && !destroyed) startCapture(true);
        }));
        view.addView(menuItem("退出", () -> {
            removeMenu();
            onExit.run();
        }));
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) removeMenu();
            return false;
        });
        WindowManager.LayoutParams params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        int right = ballParams.x + dp(48) + dp(8);
        params.x = right + width <= size.x ? right : Math.max(0, ballParams.x - dp(8) - width);
        params.y = clamp(ballParams.y, 0, Math.max(0, size.y - height));
        windowManager.addView(view, params);
        menu = view;
    }

    private TextView menuItem(String text, Runnable action) {
        TextView item = new TextView(context);
        item.setText(text);
        item.setTextColor(Color.WHITE);
        item.setTextSize(16);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setOnClickListener(view -> action.run());
        return item;
    }

    private void removeMenu() {
        if (menu == null) return;
        try { windowManager.removeView(menu); } catch (RuntimeException ignored) { }
        menu = null;
    }

    private boolean hasSavedRegion() {
        return savedRegion != null && savedScreen != null
                && savedScreen.equals(ScreenCapture.realSize(windowManager));
    }

    private void saveRegion(RectF fractions) {
        savedRegion = new RectF(fractions);
        savedScreen = ScreenCapture.realSize(windowManager);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_LEFT, fractions.left).putFloat(KEY_TOP, fractions.top)
                .putFloat(KEY_RIGHT, fractions.right).putFloat(KEY_BOTTOM, fractions.bottom)
                .putInt(KEY_SCREEN_W, savedScreen.x).putInt(KEY_SCREEN_H, savedScreen.y)
                .apply();
    }

    private void loadRegion() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_SCREEN_W)) return;
        RectF region = new RectF(prefs.getFloat(KEY_LEFT, 0), prefs.getFloat(KEY_TOP, 0),
                prefs.getFloat(KEY_RIGHT, 0), prefs.getFloat(KEY_BOTTOM, 0));
        boolean valid = region.left >= 0 && region.top >= 0 && region.right <= 1 && region.bottom <= 1
                && region.left < region.right && region.top < region.bottom;
        if (!valid) return;
        savedRegion = region;
        savedScreen = new Point(prefs.getInt(KEY_SCREEN_W, 0), prefs.getInt(KEY_SCREEN_H, 0));
    }

    // ---- result card ----

    private void showRecognized(List<String> lines, List<MatchCandidate> found, int attempts) {
        showCard();
        candidates = found;
        if (candidates.isEmpty()) {
            // Show only Chinese text, the same way names are matched, so symbols and HP do not clutter the message.
            List<String> seen = new ArrayList<>();
            for (String line : lines) {
                String cleaned = PokemonMatcher.normalize(line);
                if (!cleaned.isEmpty()) seen.add(cleaned);
            }
            String tried = attempts > 1 ? "（已尝试 " + attempts + " 次）" : "";
            String message = seen.isEmpty() ? "区域内没有识别到汉字。可以直接输入名字搜索，或重新圈选。" + tried
                    : "识别到的文字：" + String.join(" / ", seen) + "\n名字库中没有匹配项。可以直接输入名字搜索，或重新圈选。" + tried;
            card.showManualSearch("未识别到宝可梦", message, "");
        } else showCandidate(0);
    }

    private void showCandidate(int index) {
        if (card == null || index < 0 || index >= candidates.size()) return;
        MatchupReport report = TypeMatchups.analyze(data, candidates.get(index).getPokemon());
        card.showReport(candidates, index, report);
    }

    private void showCard() {
        if (card != null) return;
        Point size = ScreenCapture.realSize(windowManager);
        card = new ResultCardView(context, Math.round(size.y * 0.55f), new ResultCardView.Listener() {
            @Override public void onClose() { removeCard(); }
            @Override public void onSelect(int candidateIndex) { showCandidate(candidateIndex); }
            @Override public void onSearch(String query) { searchManually(query); }
            @Override public void onSearchModeChanged(boolean enabled) { setCardSearchMode(enabled); }
        });
        cardParams = overlayParams(Math.min(size.x - 2 * dp(12), dp(380)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        cardParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardParams.y = dp(24);
        windowManager.addView(card, cardParams);
        // Windows added later sit on top, so re-add the ball to keep it reachable above the card.
        if (ball != null) {
            windowManager.removeView(ball);
            windowManager.addView(ball, ballParams);
        }
    }

    private void removeCard() {
        if (card == null) return;
        try { windowManager.removeView(card); } catch (RuntimeException ignored) { }
        card = null;
        cardParams = null;
    }

    private void searchManually(String query) {
        if (card == null) return;
        if (query == null || query.trim().isEmpty()) {
            card.showManualSearch("未找到宝可梦", "请输入宝可梦名称后搜索。", "");
            return;
        }
        matcher.setAliases(PokemonAliasStore.load(context, data));
        candidates = matcher.search(query.trim());
        if (candidates.isEmpty()) {
            card.showManualSearch("未找到宝可梦", "没有找到匹配项，请检查名称后重试。", query.trim());
        } else showCandidate(0);
    }

    private void setCardSearchMode(boolean enabled) {
        if (card == null || cardParams == null) return;
        if (enabled) cardParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else cardParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { windowManager.updateViewLayout(card, cardParams); } catch (RuntimeException ignored) { }
        if (!enabled) {
            android.view.inputmethod.InputMethodManager inputMethod =
                    (android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethod != null) inputMethod.hideSoftInputFromWindow(card.getWindowToken(), 0);
        }
    }

    // ---- helpers ----

    private WindowManager.LayoutParams overlayParams(int width, int height, int flags) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, windowType, flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private int dp(int value) { return Ui.dp(context, value); }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
