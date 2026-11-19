package com.example.syncgamesave.pokemon;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import com.example.syncgamesave.translate.TranslateOverlayController;

/**
 * Takes screenshots on demand and hosts the floating windows of the game tools (Pokemon recognition and
 * screenshot translation), so no per-launch screen recording permission, foreground service or overlay
 * permission is needed. It reads no screen content. Only one tool runs at a time.
 */
public class PokemonAccessibilityService extends AccessibilityService {
    private static volatile PokemonAccessibilityService instance;

    private OverlayTool controller;
    private AccessibilityCapture capture;

    /** True while the user has this accessibility service turned on. */
    public static boolean isConnected() { return instance != null; }

    /** True while either game tool is showing its floating ball, whichever service hosts it. */
    public static boolean isToolActive() {
        return PokemonOverlayController.isActive() || TranslateOverlayController.isActive();
    }

    /** Starts the floating tool inside the running service. Returns an error message, or null on success. */
    public static String startTool(String mode) {
        PokemonAccessibilityService service = instance;
        if (service == null) return "无障碍服务尚未开启";
        return service.startOverlay(mode);
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

    private String startOverlay(String mode) {
        if (controller != null) return null;
        if (Build.VERSION.SDK_INT < 30) return "当前系统版本不支持无障碍截屏";
        capture = new AccessibilityCapture(this);
        boolean translate = OverlayTool.MODE_TRANSLATE.equals(mode);
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        controller = translate ? new TranslateOverlayController(this, type, capture, this::stopOverlay)
                : new PokemonOverlayController(this, type, capture, this::stopOverlay);
        try {
            controller.start();
            return null;
        } catch (Exception e) {
            stopOverlay();
            String message = e.getMessage();
            return (translate ? "截图翻译" : "宝可梦工具") + "启动失败："
                    + (message == null ? e.getClass().getSimpleName() : message);
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
