package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Floating card that shows the recognized Pokemon, its types and its type matchups. */
public class ResultCardView extends LinearLayout {
    public interface Listener {
        void onClose();
        void onSelect(int candidateIndex);
    }

    private static class LimitedScrollView extends ScrollView {
        private final int maxHeight;

        LimitedScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    private final Listener listener;
    private final float density;
    private final TextView title;
    private final LinearLayout content;

    public ResultCardView(Context context, int maxContentHeight, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int padding = dp(12);
        setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202124);
        background.setCornerRadius(dp(14));
        setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LayoutParams(0, -2, 1));
        TextView close = new TextView(context);
        close.setText("✕");
        close.setTextColor(0xFFCCCCCC);
        close.setTextSize(18);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setContentDescription("关闭");
        close.setOnClickListener(view -> listener.onClose());
        header.addView(close, new LayoutParams(-2, -2));
        addView(header, new LayoutParams(-1, -2));

        LimitedScrollView scroll = new LimitedScrollView(context, maxContentHeight);
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -2));
    }

    public void showLoading() {
        title.setText("识别中……");
        content.removeAllViews();
    }

    public void showMessage(String heading, String detail) {
        title.setText(heading);
        content.removeAllViews();
        TextView text = label(detail);
        text.setPadding(0, dp(8), 0, 0);
        content.addView(text);
    }

    public void showReport(List<MatchCandidate> candidates, int selected, MatchupReport report) {
        MatchCandidate current = candidates.get(selected);
        PokemonEntry pokemon = report.getPokemon();
        title.setText(String.format(Locale.ROOT, "#%03d %s", pokemon.getId(), pokemon.getName())
                + (current.isExact() ? "" : "（模糊匹配）"));
        content.removeAllViews();
        if (candidates.size() > 1) addCandidates(candidates, selected);
        addRow("属性", report.getTypes(), report, "");
        addRow("本系招式克制", report.getStrongAgainst(), report, "");
        addRow("受到 ×4 伤害", report.getWeak4(), report, "×4");
        addRow("受到 ×2 伤害", report.getWeak2(), report, "×2");
        addRow("受到 ×½ 伤害", report.getResist2(), report, "×½");
        addRow("受到 ×¼ 伤害", report.getResist4(), report, "×¼");
        addRow("免疫（×0）", report.getImmune(), report, "");
    }

    private void addCandidates(List<MatchCandidate> candidates, int selected) {
        content.addView(sectionLabel("可能是（点击切换）"));
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        for (int i = 0; i < candidates.size(); i++) {
            int index = i;
            TextView chip = chip(candidates.get(i).getPokemon().getName(), i == selected ? 0xFF3F7CE0 : 0xFF4A4D52);
            chip.setOnClickListener(view -> listener.onSelect(index));
            LayoutParams params = new LayoutParams(-2, -2);
            params.setMargins(0, dp(3), dp(6), dp(3));
            row.addView(chip, params);
        }
        scroll.addView(row);
        content.addView(scroll, new LayoutParams(-1, -2));
    }

    private void addRow(String heading, List<Integer> types, MatchupReport report, String suffix) {
        if (types.isEmpty()) return;
        content.addView(sectionLabel(heading));
        FlowLayout flow = new FlowLayout(getContext(), dp(6));
        for (int type : types) {
            String name = report.getTypeNames().get(type);
            flow.addView(chip(suffix.isEmpty() ? name : name + " " + suffix, typeColor(name)));
        }
        content.addView(flow, new LayoutParams(-1, -2));
    }

    private TextView sectionLabel(String text) {
        TextView view = label(text);
        view.setTextSize(13);
        view.setTextColor(0xFFB0B3B8);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        return view;
    }

    private TextView chip(String text, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        view.setBackground(background);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private static int typeColor(String name) {
        switch (name) {
            case "一般": return 0xFF9A9A6A;
            case "火": return 0xFFF08030;
            case "水": return 0xFF6890F0;
            case "电": return 0xFFD9A800;
            case "草": return 0xFF5FB43C;
            case "冰": return 0xFF4FB5B5;
            case "格斗": return 0xFFC03028;
            case "毒": return 0xFFA040A0;
            case "地面": return 0xFFC09A3C;
            case "飞行": return 0xFF8A74E0;
            case "超能力": return 0xFFF85888;
            case "虫": return 0xFF94A41C;
            case "岩石": return 0xFFA48E2C;
            case "幽灵": return 0xFF705898;
            case "龙": return 0xFF7038F8;
            case "恶": return 0xFF705848;
            case "钢": return 0xFF8E8EA8;
            case "妖精": return 0xFFE57A94;
            default: return 0xFF777777;
        }
    }
}
