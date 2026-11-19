package com.example.syncgamesave.pokemon;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import com.example.syncgamesave.R;
import com.example.syncgamesave.translate.TranslateOverlayController;

/**
 * Fallback host that captures the screen with MediaProjection. It is used below Android 11 or when the
 * accessibility service is not enabled. The projection keeps recording while the tool runs.
 */
public class PokemonOverlayService extends Service {
    public static final String ACTION_STOP = "com.example.syncgamesave.pokemon.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";

    private static final String CHANNEL_ID = "pokemon_tool";
    private static final int NOTIFICATION_ID = 4101;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private ScreenCapture capture;
    private OverlayTool controller;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "游戏工具",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean translate = OverlayTool.MODE_TRANSLATE.equals(intent.getStringExtra(OverlayTool.EXTRA_MODE));
        // The system requires startForeground shortly after startForegroundService, even for a repeated start.
        startInForeground(translate);
        if (controller != null) return START_NOT_STICKY;
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))
                throw new IllegalStateException("未授予悬浮窗权限");
            startProjection(intent);
            controller = translate ? new TranslateOverlayController(this, overlayType(), capture, this::stopSelf)
                    : new PokemonOverlayController(this, overlayType(), capture, this::stopSelf);
            controller.start();
        } catch (Exception e) {
            String message = e.getMessage();
            Toast.makeText(this, (translate ? "截图翻译" : "宝可梦工具") + "启动失败："
                    + (message == null ? e.getClass().getSimpleName() : message), Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (capture != null) capture.resize();
        if (controller != null) controller.onConfigurationChanged();
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (controller != null) controller.stop();
        if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback);
        if (capture != null) capture.release();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    private void startInForeground(boolean translate) {
        Intent stop = new Intent(this, PokemonOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(translate ? "截图翻译运行中" : "宝可梦工具运行中")
                .setContentText(translate ? "点击悬浮球翻译屏幕文字" : "点击悬浮球识别宝可梦")
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

    @SuppressWarnings("deprecation")
    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }
}
