package com.example.syncgamesave.translate;

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
import com.example.syncgamesave.pokemon.FrameSource;
import com.example.syncgamesave.pokemon.OverlayTool;
import com.example.syncgamesave.pokemon.RegionSelectView;
import com.example.syncgamesave.pokemon.ScreenCapture;
import com.example.syncgamesave.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating ball for screenshot translation. A tap translates the whole screen, or the saved region, and draws
 * the Chinese text over the original. The host supplies the window type and the screenshot source.
 */
public class TranslateOverlayController implements OverlayTool {
    private static final String PREFS = "translate_tool";
    private static final String KEY_LANGUAGE = "source_language";
    private static final String KEY_LEFT = "region_left";
    private static final String KEY_TOP = "region_top";
    private static final String KEY_RIGHT = "region_right";
    private static final String KEY_BOTTOM = "region_bottom";
    private static final String KEY_SCREEN_W = "screen_width";
    private static final String KEY_SCREEN_H = "screen_height";
    private static final String BALL_IDLE = "译";
    private static final String BALL_BUSY = "…";
    private static volatile boolean active;

    private final Context context;
    private final WindowManager windowManager;
    private final int windowType;
    private final FrameSource source;
    private final Runnable onExit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TranslateOcr ocr;
    private OfflineTranslator translator;
    private SourceLanguage language = SourceLanguage.AUTO;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private LinearLayout menu;
    private View selection;
    private View translation;
    /** Saved region as fractions of the screen; null means translate the whole screen. */
    private RectF savedRegion;
    private Point savedScreen;
    private boolean busy;
    private boolean started;
    private boolean destroyed;

    public TranslateOverlayController(Context context, int windowType, FrameSource source, Runnable onExit) {
        this.context = context;
        this.windowType = windowType;
        this.source = source;
        this.onExit = onExit;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean isActive() { return active; }

    @Override public void start() {
        ocr = new TranslateOcr();
        translator = new OfflineTranslator();
        loadSettings();
        createBall();
        started = true;
        active = true;
        Toast.makeText(context, "截图翻译已启动：点击悬浮球翻译，长按悬浮球打开菜单", Toast.LENGTH_LONG).show();
    }

    @Override public void stop() {
        destroyed = true;
        if (started) active = false;
        main.removeCallbacksAndMessages(null);
        removeSelection();
        removeMenu();
        removeTranslation();
        if (ball != null) {
            try { windowManager.removeView(ball); } catch (RuntimeException ignored) { }
            ball = null;
        }
        if (ocr != null) {
            ocr.close();
            ocr = null;
        }
        if (translator != null) {
            translator.close();
            translator = null;
        }
    }

    @Override public void onConfigurationChanged() {
        if (!started || destroyed) return;
        if (selection != null) cancelSelection();
        removeMenu();
        // The translation is laid out for the old screen size.
        removeTranslation();
        clampBall();
    }

    // ---- floating ball ----

    private void createBall() {
        TextView view = new TextView(context);
        view.setText(BALL_IDLE);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xCC2E7D32);
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

    private void setBallBusy(boolean working) {
        if (ball != null) ball.setText(working ? BALL_BUSY : BALL_IDLE);
    }

    /** Windows added later sit on top, and the translation is not touchable, so the ball is moved above it. */
    private void raiseBall() {
        if (ball == null) return;
        try {
            windowManager.removeView(ball);
            windowManager.addView(ball, ballParams);
        } catch (RuntimeException ignored) { }
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

    // ---- capture, selection and translation ----

    /** A tap translates the whole screen, or the saved region; the user draws a region only on request. */
    private void onBallClick() {
        if (busy || destroyed) return;
        removeMenu();
        boolean select = false;
        if (savedRegion != null && !hasValidRegion()) {
            Toast.makeText(context, "屏幕尺寸已变化，请重新圈选区域", Toast.LENGTH_SHORT).show();
            select = true;
        }
        startCapture(select);
    }

    private void startCapture(boolean select) {
        busy = true;
        removeTranslation();
        setBallVisible(false);
        source.capture(new FrameSource.Callback() {
            @Override public void onFrame(Bitmap frame) {
                if (destroyed) return;
                if (select) showSelection(frame);
                else {
                    setBallVisible(true);
                    translateFrame(frame, savedRegion);
                }
            }

            @Override public void onError(String message) {
                if (destroyed) return;
                finishTask(message);
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
        root.addView(new RegionSelectView(context, frame, "拖动手指圈出要翻译的区域",
                fractions -> finishSelection(frame, fractions)), new FrameLayout.LayoutParams(-1, -1));
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
            finishTask("无法显示选区层：" + e.getMessage());
            return;
        }
        selection = root;
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private void cancelSelection() {
        removeSelection();
        finishTask(null);
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
        translateFrame(frame, fractions);
    }

    /** Recognizes the text in the region (or the whole frame when it is null) and translates it. */
    private void translateFrame(Bitmap frame, RectF fractions) {
        int left = 0;
        int top = 0;
        Bitmap crop = frame;
        if (fractions != null) {
            left = clamp(Math.round(fractions.left * frame.getWidth()), 0, frame.getWidth() - 1);
            top = clamp(Math.round(fractions.top * frame.getHeight()), 0, frame.getHeight() - 1);
            int right = clamp(Math.round(fractions.right * frame.getWidth()), left + 1, frame.getWidth());
            int bottom = clamp(Math.round(fractions.bottom * frame.getHeight()), top + 1, frame.getHeight());
            crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top);
        }
        int frameWidth = frame.getWidth();
        int frameHeight = frame.getHeight();
        setBallBusy(true);
        ocr.recognize(crop, left, top, new TranslateOcr.Callback() {
            @Override public void onResult(List<ScreenText> texts) {
                if (destroyed) return;
                if (texts.isEmpty()) {
                    finishTask("没有识别到文字");
                    return;
                }
                translate(texts, frameWidth, frameHeight);
            }

            @Override public void onError(Exception error) {
                if (destroyed) return;
                finishTask("文字识别失败：" + describe(error));
            }
        });
    }

    private void translate(List<ScreenText> texts, int frameWidth, int frameHeight) {
        translator.translate(texts, language, new OfflineTranslator.Callback() {
            @Override public void onDownloading(String languageLabel) {
                if (destroyed) return;
                Toast.makeText(context, "正在下载" + languageLabel + "翻译模型，首次使用需要联网，请稍候",
                        Toast.LENGTH_LONG).show();
            }

            @Override public void onDone(List<ScreenText> done) {
                if (destroyed) return;
                List<ScreenText> shown = new ArrayList<>();
                for (ScreenText item : done)
                    if (item.getTranslated() != null && !item.getTranslated().trim().isEmpty()) shown.add(item);
                if (shown.isEmpty()) {
                    finishTask("没有找到需要翻译的日文或英文");
                    return;
                }
                showTranslation(frameWidth, frameHeight, shown);
                finishTask(null);
            }

            @Override public void onError(Exception error) {
                if (destroyed) return;
                finishTask("翻译失败：" + describe(error) + "\n首次使用需要联网下载翻译模型");
            }
        });
    }

    private void finishTask(String message) {
        busy = false;
        setBallBusy(false);
        setBallVisible(true);
        if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static String describe(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    // ---- translation layer ----

    private void showTranslation(int frameWidth, int frameHeight, List<ScreenText> texts) {
        removeTranslation();
        Point size = ScreenCapture.realSize(windowManager);
        TranslateOverlayView view = new TranslateOverlayView(context, frameWidth, frameHeight, texts);
        WindowManager.LayoutParams params = overlayParams(size.x, size.y,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= 28)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            windowManager.addView(view, params);
        } catch (RuntimeException e) {
            Toast.makeText(context, "无法显示译文：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        translation = view;
        raiseBall();
    }

    private void removeTranslation() {
        if (translation == null) return;
        try { windowManager.removeView(translation); } catch (RuntimeException ignored) { }
        translation = null;
    }

    // ---- long-press menu and settings ----

    private void showMenu() {
        if (busy || menu != null || ball == null) return;
        Point size = ScreenCapture.realSize(windowManager);
        int width = dp(160);
        int height = dp(48) * 5;
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202124);
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        view.addView(menuItem("选区域翻译", () -> {
            removeMenu();
            if (!busy && !destroyed) startCapture(true);
        }));
        view.addView(menuItem("全屏翻译", () -> {
            removeMenu();
            clearRegion();
            Toast.makeText(context, "已切换为全屏翻译", Toast.LENGTH_SHORT).show();
        }));
        view.addView(menuItem("源语言：" + language.getLabel(), () -> {
            removeMenu();
            language = language.next();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LANGUAGE, language.name()).apply();
            Toast.makeText(context, "源语言：" + language.getLabel(), Toast.LENGTH_SHORT).show();
        }));
        view.addView(menuItem("清除译文", () -> {
            removeMenu();
            removeTranslation();
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

    private boolean hasValidRegion() {
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

    private void clearRegion() {
        savedRegion = null;
        savedScreen = null;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_LEFT).remove(KEY_TOP).remove(KEY_RIGHT).remove(KEY_BOTTOM)
                .remove(KEY_SCREEN_W).remove(KEY_SCREEN_H).apply();
    }

    private void loadSettings() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            language = SourceLanguage.valueOf(prefs.getString(KEY_LANGUAGE, SourceLanguage.AUTO.name()));
        } catch (IllegalArgumentException e) {
            language = SourceLanguage.AUTO;
        }
        if (!prefs.contains(KEY_SCREEN_W)) return;
        RectF region = new RectF(prefs.getFloat(KEY_LEFT, 0), prefs.getFloat(KEY_TOP, 0),
                prefs.getFloat(KEY_RIGHT, 0), prefs.getFloat(KEY_BOTTOM, 0));
        boolean valid = region.left >= 0 && region.top >= 0 && region.right <= 1 && region.bottom <= 1
                && region.left < region.right && region.top < region.bottom;
        if (!valid) return;
        savedRegion = region;
        savedScreen = new Point(prefs.getInt(KEY_SCREEN_W, 0), prefs.getInt(KEY_SCREEN_H, 0));
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
