package com.example.syncgamesave.pokemon;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Takes screenshots on demand and hosts the floating windows, so no per-launch screen recording
 * permission, foreground service or overlay permission is needed. It reads no screen content.
 */
public class PokemonAccessibilityService extends AccessibilityService {
    private static volatile PokemonAccessibilityService instance;

    private PokemonOverlayController controller;
    private AccessibilityCapture capture;

    /** True while the user has this accessibility service turned on. */
    public static boolean isConnected() { return instance != null; }

    /** Starts the floating tool inside the running service. Returns an error message, or null on success. */
    public static String startTool() {
        PokemonAccessibilityService service = instance;
        if (service == null) return "无障碍服务尚未开启";
        return service.startOverlay();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controller != null) controller.onConfigurationChanged();
    }

    @Override public boolean onUnbind(Intent intent) {
        stopOverlay();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopOverlay();
        instance = null;
        super.onDestroy();
    }

    private String startOverlay() {
        if (controller != null) return null;
        if (Build.VERSION.SDK_INT < 30) return "当前系统版本不支持无障碍截屏";
        capture = new AccessibilityCapture(this);
        controller = new PokemonOverlayController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                capture, this::stopOverlay);
        try {
            controller.start();
            return null;
        } catch (Exception e) {
            stopOverlay();
            String message = e.getMessage();
            return "宝可梦工具启动失败：" + (message == null ? e.getClass().getSimpleName() : message);
        }
    }

    private void stopOverlay() {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        if (capture != null) {
            capture.release();
            capture = null;
        }
    }
}
