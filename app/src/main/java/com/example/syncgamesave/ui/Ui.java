package com.example.syncgamesave.ui;

import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Ui {
    private Ui() { }
    public static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout screen(AppCompatActivity activity, String title) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar toolbar = new MaterialToolbar(activity);
        toolbar.setTitle(title);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(activity, 56)));
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 16);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        activity.setContentView(root);
        activity.setSupportActionBar(toolbar);
        if (activity.getSupportActionBar() != null) activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(view -> activity.finish());
        return body;
    }

    public static TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(parent.getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(parent.getContext(), 12);
        parent.addView(text, params);
        return text;
    }

    public static TextInputEditText field(LinearLayout parent, String label, String value, boolean password) {
        TextInputLayout wrapper = new TextInputLayout(parent.getContext());
        wrapper.setHint(label);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(parent.getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setText(value);
        input.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        wrapper.addView(input, new ViewGroup.LayoutParams(-1, -2));
        if (password) wrapper.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(parent.getContext(), 12);
        parent.addView(wrapper, params);
        return input;
    }

    public static Button button(LinearLayout parent, String label, Runnable action) {
        MaterialButton button = new MaterialButton(parent.getContext());
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    public static String value(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    public static String time(long timestamp) {
        return timestamp <= 0 ? "未知／尚未同步" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timestamp));
    }
}


