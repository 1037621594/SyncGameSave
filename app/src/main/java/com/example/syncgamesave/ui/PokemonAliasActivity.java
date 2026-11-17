package com.example.syncgamesave.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.example.syncgamesave.pokemon.PokemonAliasStore;
import com.example.syncgamesave.pokemon.PokemonData;
import com.example.syncgamesave.pokemon.PokemonEntry;
import com.example.syncgamesave.pokemon.PokemonMatcher;
import com.example.syncgamesave.pokemon.PokemonRepository;
import com.example.syncgamesave.ui.Ui;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Editor for local alternate Pokemon names. */
public class PokemonAliasActivity extends AppCompatActivity {
    private PokemonData data;
    private LinkedHashMap<String, Integer> aliases;
    private LinearLayout body;
    private LinearLayout rows;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        body = Ui.screen(this, "宝可梦别名");
        Ui.text(body, "别名会用于 OCR 识别和手动搜索，命中后显示对应的官方条目。", 14);
        Ui.button(body, "新增别名", () -> editAlias(null, null));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        try {
            data = PokemonRepository.load(this);
            aliases = PokemonAliasStore.load(this, data);
            renderAliases();
        } catch (IOException e) {
            Ui.text(body, e.getMessage() == null ? "宝可梦数据加载失败" : e.getMessage(), 14);
        }
    }

    private void renderAliases() {
        rows.removeAllViews();
        if (aliases.isEmpty()) {
            Ui.text(rows, "还没有自定义别名。", 14);
            return;
        }
        for (Map.Entry<String, Integer> alias : aliases.entrySet()) {
            PokemonEntry target = PokemonAliasStore.findById(data.getPokemon(), alias.getValue());
            if (target == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            TextView label = new TextView(this);
            label.setText(alias.getKey() + "  →  " + target.getName());
            label.setTextSize(15);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton edit = new MaterialButton(this);
            edit.setText("修改");
            edit.setOnClickListener(view -> editAlias(alias.getKey(), alias.getValue()));
            row.addView(edit, new LinearLayout.LayoutParams(-2, -2));
            MaterialButton delete = new MaterialButton(this);
            delete.setText("删除");
            delete.setOnClickListener(view -> confirmDelete(alias.getKey()));
            row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void editAlias(String oldAlias, Integer oldId) {
        if (data == null) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 20);
        form.setPadding(padding, Ui.dp(this, 8), padding, 0);
        TextInputEditText aliasInput = Ui.field(form, "别名", oldAlias == null ? "" : oldAlias, false);
        aliasInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        TextInputLayout targetLayout = new TextInputLayout(this);
        targetLayout.setHint("对应的官方名称");
        targetLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        AutoCompleteTextView targetInput = new AutoCompleteTextView(this);
        targetInput.setSingleLine(true);
        targetInput.setThreshold(1);
        targetInput.setAdapter(new FuzzyNameAdapter(this, data.getPokemon()));
        // The official name can only be chosen from the dropdown; typing merely narrows the list.
        PokemonEntry[] chosen = new PokemonEntry[1];
        if (oldId != null) {
            chosen[0] = PokemonAliasStore.findById(data.getPokemon(), oldId);
            if (chosen[0] != null) targetInput.setText(chosen[0].getName(), false);
        }
        targetInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (chosen[0] != null && !chosen[0].getName().contentEquals(s)) chosen[0] = null;
                targetLayout.setError(null);
            }
        });
        targetInput.setOnItemClickListener((parent, view, position, id) ->
                chosen[0] = PokemonAliasStore.findByName(data.getPokemon(), String.valueOf(parent.getItemAtPosition(position))));
        targetLayout.addView(targetInput, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(-1, -2);
        targetParams.bottomMargin = Ui.dp(this, 12);
        form.addView(targetLayout, targetParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(oldAlias == null ? "新增别名" : "修改别名")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String alias = Ui.value(aliasInput);
            String normalized = PokemonMatcher.normalize(alias);
            PokemonEntry target = chosen[0];
            if (normalized.isEmpty()) {
                aliasInput.setError("请输入至少一个汉字");
                return;
            }
            if (target == null) {
                targetLayout.setError("请从下拉列表中选择官方名称");
                return;
            }
            targetLayout.setError(null);
            Integer existing = aliases.get(normalized);
            if (existing != null && !normalized.equals(oldAlias)) {
                aliasInput.setError("这个别名已存在");
                return;
            }
            String officialCollision = null;
            for (PokemonEntry pokemon : data.getPokemon()) {
                if (PokemonMatcher.normalize(pokemon.getName()).equals(normalized)
                        && pokemon.getId() != target.getId()) {
                    officialCollision = pokemon.getName();
                    break;
                }
            }
            if (officialCollision != null) {
                aliasInput.setError("与官方名称“" + officialCollision + "”冲突");
                return;
            }
            if (oldAlias != null) aliases.remove(oldAlias);
            aliases.put(normalized, target.getId());
            PokemonAliasStore.save(this, aliases);
            renderAliases();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Dropdown of official names whose filter accepts partial and slightly misspelled input. */
    private static class FuzzyNameAdapter extends ArrayAdapter<String> {
        private final List<PokemonEntry> all;

        FuzzyNameAdapter(Context context, List<PokemonEntry> all) {
            super(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
            this.all = all;
        }

        @Override public Filter getFilter() {
            return new Filter() {
                @Override protected FilterResults performFiltering(CharSequence constraint) {
                    List<String> names = new ArrayList<>();
                    for (PokemonEntry entry : PokemonMatcher.searchOfficial(all, String.valueOf(constraint))) {
                        names.add(entry.getName());
                    }
                    FilterResults results = new FilterResults();
                    results.values = names;
                    results.count = names.size();
                    return results;
                }

                @SuppressWarnings("unchecked")
                @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                    clear();
                    addAll((List<String>) results.values);
                    notifyDataSetChanged();
                }

                @Override public CharSequence convertResultToString(Object resultValue) {
                    return String.valueOf(resultValue);
                }
            };
        }
    }

    private void confirmDelete(String alias) {
        new MaterialAlertDialogBuilder(this).setTitle("删除别名？")
                .setMessage("将删除“" + alias + "”，不会影响官方名称。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    aliases.remove(alias);
                    PokemonAliasStore.save(this, aliases);
                    renderAliases();
                    Toast.makeText(this, "别名已删除", Toast.LENGTH_SHORT).show();
                }).show();
    }
}
