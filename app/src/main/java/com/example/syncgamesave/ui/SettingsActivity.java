package com.example.syncgamesave.ui;

import android.os.Bundle;
import android.content.Intent;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.syncgamesave.pokemon.PokemonSettings;
import com.google.android.material.textfield.TextInputEditText;

/** App settings. Add new sections here as more options are introduced. */
public class SettingsActivity extends AppCompatActivity {
    private TextInputEditText retries;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.screen(this, "设置");
        Ui.text(body, "宝可梦工具", 18);
        Ui.text(body, "识别失败后，会换用不同的图像处理方式重新识别。填 0 表示失败后不重试，范围 0 到 "
                + PokemonSettings.MAX_RETRIES + "。", 14);
        retries = Ui.field(body, "失败重试次数（0-" + PokemonSettings.MAX_RETRIES + "）",
                String.valueOf(PokemonSettings.retries(this)), false);
        retries.setInputType(InputType.TYPE_CLASS_NUMBER);
        retries.setFilters(new InputFilter[] {new InputFilter.LengthFilter(3)});
        Ui.button(body, "保存", this::save);
        Ui.button(body, "管理宝可梦别名", () -> startActivity(new Intent(this, PokemonAliasActivity.class)));
    }

    private void save() {
        String text = Ui.value(retries);
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            retries.setError("请输入 0 到 " + PokemonSettings.MAX_RETRIES + " 的整数");
            return;
        }
        if (value < 0 || value > PokemonSettings.MAX_RETRIES) {
            retries.setError("请输入 0 到 " + PokemonSettings.MAX_RETRIES + " 的整数");
            return;
        }
        PokemonSettings.setRetries(this, value);
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
