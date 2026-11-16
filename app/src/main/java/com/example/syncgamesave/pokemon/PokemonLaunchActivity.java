package com.example.syncgamesave.pokemon;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Walks through overlay, notification and screen-capture permissions, then starts the overlay service. */
public class PokemonLaunchActivity extends AppCompatActivity {
    private final ActivityResultLauncher<Intent> overlaySettings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (hasOverlayPermission()) requestNotification();
                else fail("未授予悬浮窗权限，宝可梦工具无法启动");
            });
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> requestProjection());
    private final ActivityResultLauncher<Intent> projectionRequest = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent service = new Intent(this, PokemonOverlayService.class)
                            .putExtra(PokemonOverlayService.EXTRA_RESULT_CODE, result.getResultCode())
                            .putExtra(PokemonOverlayService.EXTRA_DATA, result.getData());
                    ContextCompat.startForegroundService(this, service);
                    finish();
                } else fail("未授予录屏权限，宝可梦工具无法启动");
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            finish();
            return;
        }
        if (PokemonOverlayService.isRunning()) {
            fail("宝可梦工具已在运行，长按悬浮球可退出");
        } else if (!hasOverlayPermission()) {
            new MaterialAlertDialogBuilder(this).setTitle("需要悬浮窗权限")
                    .setMessage("宝可梦工具需要在游戏上方显示悬浮球和识别结果。请在接下来的设置页中允许“显示在其他应用的上层”。")
                    .setNegativeButton("取消", (dialog, which) -> finish())
                    .setPositiveButton("去设置", (dialog, which) -> openOverlaySettings())
                    .setOnCancelListener(dialog -> finish())
                    .show();
        } else requestNotification();
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        try {
            overlaySettings.launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            fail("系统没有悬浮窗权限设置页，请在系统设置中手动允许本应用显示在其他应用上层");
        }
    }

    /** The notification is only shown when allowed, but the service runs either way, so the result is ignored. */
    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        else requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projectionRequest.launch(manager.createScreenCaptureIntent());
        } catch (RuntimeException e) {
            fail("无法请求录屏权限：" + e.getMessage());
        }
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
