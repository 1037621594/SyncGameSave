package com.example.syncgamesave.pokemon;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
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
        void onSearch(String query);
        void onSearchModeChanged(boolean enabled);
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

    /** Same order as {@link PokemonEntry#getStats()}. */
    private static final String[] STAT_NAMES = {"HP", "物攻", "物防", "特攻", "特防", "速度"};

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

    public void showLoading(String heading) {
        title.setText(heading);
        content.removeAllViews();
    }

    public void showMessage(String heading, String detail) {
        title.setText(heading);
        content.removeAllViews();
        listener.onSearchModeChanged(false);
        TextView text = label(detail);
        text.setPadding(0, dp(8), 0, 0);
        content.addView(text);
    }

    public void showManualSearch(String heading, String detail, String query) {
        title.setText(heading);
        content.removeAllViews();
        listener.onSearchModeChanged(true);
        TextView text = label(detail);
        text.setPadding(0, dp(8), 0, dp(8));
        content.addView(text);

        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFB0B3B8);
        input.setHint("输入宝可梦名称");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(query);
        row.addView(input, new LayoutParams(0, dp(48), 1));

        Button search = new Button(getContext());
        search.setText("搜索");
        search.setOnClickListener(view -> listener.onSearch(input.getText().toString().trim()));
        row.addView(search, new LayoutParams(-2, dp(48)));
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                listener.onSearch(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        content.addView(row, new LayoutParams(-1, -2));
        input.requestFocus();
        input.post(() -> {
            InputMethodManager inputMethod =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethod != null) inputMethod.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public void showReport(List<MatchCandidate> candidates, int selected, MatchupReport report) {
        listener.onSearchModeChanged(false);
        MatchCandidate current = candidates.get(selected);
        PokemonEntry pokemon = report.getPokemon();
        title.setText(String.format(Locale.ROOT, "#%03d %s", pokemon.getId(), pokemon.getName())
                + (current.isExact() ? "" : "（模糊匹配）"));
        content.removeAllViews();
        if (candidates.size() > 1) addCandidates(candidates, selected);
        addTypesAndStats(report);
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

    /** Types on the left, base stats in the free space to their right (two columns of three). */
    private void addTypesAndStats(MatchupReport report) {
        LinearLayout row = new LinearLayout(getContext());
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        left.addView(sectionLabel("属性"));
        FlowLayout flow = new FlowLayout(getContext(), dp(6));
        for (int type : report.getTypes()) {
            String name = report.getTypeNames().get(type);
            flow.addView(chip(name, typeColor(name)));
        }
        left.addView(flow, new LayoutParams(-1, -2));
        row.addView(left, new LayoutParams(0, -2, 1));

        // Older data files have no stats, in which case only the types are shown.
        int[] values = report.getPokemon().getStats();
        if (values.length == STAT_NAMES.length) {
            LinearLayout grid = new LinearLayout(getContext());
            grid.setPadding(dp(8), dp(12), 0, 0);
            int perColumn = STAT_NAMES.length / 2;
            for (int column = 0; column < 2; column++) {
                LinearLayout cells = new LinearLayout(getContext());
                cells.setOrientation(VERTICAL);
                for (int i = column * perColumn; i < (column + 1) * perColumn; i++) {
                    TextView cell = label(STAT_NAMES[i] + ":" + values[i]);
                    cell.setTextSize(12);
                    cell.setTextColor(0xFFD0D3D8);
                    cell.setSingleLine(true);
                    cells.addView(cell, new LayoutParams(-2, -2));
                }
                LayoutParams cellsParams = new LayoutParams(-2, -2);
                if (column > 0) cellsParams.setMargins(dp(10), 0, 0, 0);
                grid.addView(cells, cellsParams);
            }
            row.addView(grid, new LayoutParams(-2, -2));
        }
        content.addView(row, new LayoutParams(-1, -2));
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
