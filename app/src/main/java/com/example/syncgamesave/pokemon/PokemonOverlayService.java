package com.example.syncgamesave.pokemon;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import com.example.syncgamesave.R;
import com.example.syncgamesave.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/** Hosts the floating ball, the region selection layer and the result card above other apps. */
public class PokemonOverlayService extends Service {
    public static final String ACTION_STOP = "com.example.syncgamesave.pokemon.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";

    private static final String CHANNEL_ID = "pokemon_tool";
    private static final int NOTIFICATION_ID = 4101;
    private static final long LONG_PRESS_MS = 900;
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private ScreenCapture capture;
    private PokemonOcr ocr;
    private PokemonData data;
    private PokemonMatcher matcher;
    private TextView ball;
    private WindowManager.LayoutParams ballParams;
    private ResultCardView card;
    private View selection;
    private List<MatchCandidate> candidates = new ArrayList<>();
    private boolean busy;
    private boolean destroyed;

    public static boolean isRunning() { return running; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "宝可梦工具",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // The system requires startForeground shortly after startForegroundService, even for a repeated start.
        startInForeground();
        if (running) return START_NOT_STICKY;
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))
                throw new IllegalStateException("未授予悬浮窗权限");
            data = PokemonRepository.load(this);
            matcher = new PokemonMatcher(data);
            startProjection(intent);
            ocr = new PokemonOcr();
            createBall();
            running = true;
            Toast.makeText(this, "宝可梦工具已启动：点击悬浮球识别，长按悬浮球退出", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String message = e.getMessage();
            Toast.makeText(this, "宝可梦工具启动失败：" + (message == null ? e.getClass().getSimpleName() : message),
                    Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!running) return;
        if (capture != null) capture.resize();
        if (selection != null) cancelSelection();
        removeCard();
        clampBall();
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        removeSelection();
        removeCard();
        if (ball != null) {
            try { windowManager.removeView(ball); } catch (RuntimeException ignored) { }
            ball = null;
        }
        if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback);
        if (capture != null) capture.release();
        if (projection != null) projection.stop();
        if (ocr != null) ocr.close();
        super.onDestroy();
    }

    private void startInForeground() {
        Intent stop = new Intent(this, PokemonOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("宝可梦工具运行中")
                .setContentText("点击悬浮球识别宝可梦")
                .setOngoing(true)
                .addAction(0, "退出", stopIntent)
                .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressWarnings("deprecation")
    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent grant = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode != Activity.RESULT_OK || grant == null) throw new IllegalStateException("未获得录屏授权");
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, grant);
        if (projection == null) throw new IllegalStateException("无法获取录屏授权");
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() { main.post(PokemonOverlayService.this::stopSelf); }
        };
        projection.registerCallback(projectionCallback, main);
        capture = new ScreenCapture(this, projection);
        capture.start();
    }

    // ---- floating ball ----

    private void createBall() {
        TextView view = new TextView(this);
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
        private final int slop = ViewConfiguration.get(PokemonOverlayService.this).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;
        private boolean longPressed;
        private final Runnable longPress = () -> {
            longPressed = true;
            stopSelf();
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
                    main.postDelayed(longPress, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        main.removeCallbacks(longPress);
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

    private void onBallClick() {
        if (busy || capture == null) return;
        busy = true;
        removeCard();
        setBallVisible(false);
        capture.capture(new ScreenCapture.Callback() {
            @Override public void onFrame(Bitmap frame) {
                if (destroyed) return;
                showSelection(frame);
            }

            @Override public void onError(String message) {
                if (destroyed) return;
                setBallVisible(true);
                busy = false;
                Toast.makeText(PokemonOverlayService.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSelection(Bitmap frame) {
        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) cancelSelection();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        root.addView(new RegionSelectView(this, frame, fractions -> finishSelection(frame, fractions)),
                new FrameLayout.LayoutParams(-1, -1));
        TextView cancel = new TextView(this);
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
            Toast.makeText(this, "无法显示选区层：" + e.getMessage(), Toast.LENGTH_LONG).show();
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
        int left = clamp(Math.round(fractions.left * frame.getWidth()), 0, frame.getWidth() - 1);
        int top = clamp(Math.round(fractions.top * frame.getHeight()), 0, frame.getHeight() - 1);
        int right = clamp(Math.round(fractions.right * frame.getWidth()), left + 1, frame.getWidth());
        int bottom = clamp(Math.round(fractions.bottom * frame.getHeight()), top + 1, frame.getHeight());
        Bitmap crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top);
        showCard();
        card.showLoading();
        ocr.recognize(crop, new PokemonOcr.Callback() {
            @Override public void onResult(List<String> lines) {
                if (destroyed) return;
                busy = false;
                showRecognized(lines);
            }

            @Override public void onError(Exception error) {
                if (destroyed) return;
                busy = false;
                showCard();
                card.showMessage("识别失败", error.getMessage() == null ? "文字识别出错，请重试" : error.getMessage());
            }
        });
    }

    // ---- result card ----

    private void showRecognized(List<String> lines) {
        showCard();
        candidates = matcher.match(lines);
        if (candidates.isEmpty()) {
            String seen = lines.isEmpty() ? "区域内没有识别到文字，请圈得更贴近宝可梦名字后重试。"
                    : "识别到的文字：" + String.join(" / ", lines) + "\n名字库中没有匹配项，请重新圈选。";
            card.showMessage("未识别到宝可梦", seen);
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
        card = new ResultCardView(this, Math.round(size.y * 0.55f), new ResultCardView.Listener() {
            @Override public void onClose() { removeCard(); }
            @Override public void onSelect(int candidateIndex) { showCandidate(candidateIndex); }
        });
        WindowManager.LayoutParams params = overlayParams(Math.min(size.x - 2 * dp(12), dp(380)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(24);
        windowManager.addView(card, params);
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
    }

    // ---- helpers ----

    @SuppressWarnings("deprecation")
    private static WindowManager.LayoutParams overlayParams(int width, int height, int flags) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, type, flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    private int dp(int value) { return Ui.dp(this, value); }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
