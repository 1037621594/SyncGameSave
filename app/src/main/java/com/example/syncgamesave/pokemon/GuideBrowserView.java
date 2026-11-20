package com.example.syncgamesave.pokemon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Floating mini browser (system WebView) with a search bar, used to look up game guides while playing. */
public class GuideBrowserView extends LinearLayout {
    public interface Listener {
        void onClose();
        /** The window must be focusable while the search field is being edited, and not focusable otherwise. */
        void onInputModeChanged(boolean enabled);
    }

    private static final String SEARCH_URL = "https://www.baidu.com/s?wd=";

    private final Listener listener;
    private final float density;
    private final EditText input;
    private final TextView back;
    private final WebView web;
    private boolean inputMode;
    private boolean loaded;

    @SuppressLint("SetJavaScriptEnabled")
    public GuideBrowserView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int padding = dp(8);
        setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202124);
        background.setCornerRadius(dp(14));
        setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        back = action("‹", 24, "后退");
        back.setAlpha(0.4f);
        back.setOnClickListener(view -> {
            if (web.canGoBack()) web.goBack();
        });
        header.addView(back, new LayoutParams(-2, -2));

        input = new EditText(context);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFB0B3B8);
        input.setHint("搜索攻略（百度）");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !inputMode) focusInput();
            return false;
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                search(input.getText().toString());
                return true;
            }
            return false;
        });
        header.addView(input, new LayoutParams(0, dp(44), 1));

        TextView search = action("搜索", 15, "搜索");
        search.setTextColor(0xFF8AB4F8);
        search.setOnClickListener(view -> search(input.getText().toString()));
        header.addView(search, new LayoutParams(-2, -2));

        TextView close = action("✕", 18, "关闭");
        close.setOnClickListener(view -> listener.onClose());
        header.addView(close, new LayoutParams(-2, -2));
        addView(header, new LayoutParams(-1, -2));

        web = new WebView(context);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isWebScheme(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isWebScheme(Uri.parse(url));
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                back.setAlpha(view.canGoBack() ? 1f : 0.4f);
            }
        });
        // Touching the page means the user is done typing: hide the keyboard and give key focus back to the game.
        web.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) leaveInputMode();
            return false;
        });
        LayoutParams webParams = new LayoutParams(-1, 0, 1);
        webParams.topMargin = dp(6);
        addView(web, webParams);
    }

    /** Whether a page was loaded before, so reopening the browser can keep showing it. */
    public boolean hasPage() { return loaded; }

    public void prefill(String text) {
        input.setText(text);
        input.setSelection(input.getText().length());
    }

    /** Loads a URL as is, or searches Baidu for any other text. */
    public void search(String text) {
        String query = text == null ? "" : text.trim();
        if (query.isEmpty()) return;
        input.setText(query);
        leaveInputMode();
        String lower = query.toLowerCase(Locale.ROOT);
        boolean url = (lower.startsWith("http://") || lower.startsWith("https://")) && query.indexOf(' ') < 0;
        web.loadUrl(url ? query : SEARCH_URL + Uri.encode(query));
        loaded = true;
    }

    public void focusInput() {
        if (!inputMode) {
            inputMode = true;
            listener.onInputModeChanged(true);
        }
        input.requestFocus();
        // The window only becomes focusable after the layout update is applied, so show the keyboard slightly later.
        input.postDelayed(() -> {
            InputMethodManager inputMethod =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethod != null) inputMethod.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    public void onShow() { web.onResume(); }

    /** Call before the window is removed; the page and its history are kept for the next onShow. */
    public void onHide() {
        leaveInputMode();
        web.onPause();
    }

    /** The view must already be removed from the window manager. */
    public void destroy() {
        web.stopLoading();
        removeAllViews();
        web.destroy();
    }

    private void leaveInputMode() {
        if (!inputMode) return;
        InputMethodManager inputMethod =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethod != null) inputMethod.hideSoftInputFromWindow(input.getWindowToken(), 0);
        inputMode = false;
        listener.onInputModeChanged(false);
    }

    /** Only http(s) pages are opened; links that try to launch other apps (intent://, custom schemes) are dropped. */
    private static boolean isWebScheme(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private TextView action(String text, int size, String description) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(0xFFCCCCCC);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        view.setContentDescription(description);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }
}
