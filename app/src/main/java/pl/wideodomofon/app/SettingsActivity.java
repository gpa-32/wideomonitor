package pl.wideodomofon.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ustawienia w menu aplikacji - pelna konfiguracja bez panelu www. */
public class SettingsActivity extends Activity {
    private final Map<Fields.F, View> inputs = new LinkedHashMap<>();

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject cfg = Cfg.all();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(24));

        TextView head = new TextView(this);
        head.setText("Ustawienia");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        head.setTextColor(0xFFFFFFFF);
        form.addView(head);

        for (Fields.Group g : Fields.all()) {
            TextView gt = new TextView(this);
            gt.setText(g.title);
            gt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            gt.setTextColor(0xFF7FC8F8);
            gt.setPadding(0, dp(26), 0, dp(4));
            form.addView(gt);

            for (Fields.F f : g.fields) {
                View input;
                if ("bool".equals(f.type)) {
                    Switch sw = new Switch(this);
                    sw.setText(f.label);
                    sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    sw.setTextColor(0xFFE6EDF2);
                    sw.setChecked(cfg.optBoolean(f.key, false));
                    sw.setPadding(0, dp(12), 0, dp(4));
                    input = sw;
                } else {
                    TextView lb = new TextView(this);
                    lb.setText(f.label);
                    lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    lb.setTextColor(0xFFE6EDF2);
                    lb.setPadding(0, dp(12), 0, 0);
                    form.addView(lb);
                    if ("select".equals(f.type)) {
                        Spinner sp = new Spinner(this);
                        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                                android.R.layout.simple_spinner_item, f.labels);
                        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        sp.setAdapter(ad);
                        String cur = cfg.optString(f.key, "");
                        for (int i = 0; i < f.values.length; i++) {
                            if (f.values[i].equals(cur)) sp.setSelection(i);
                        }
                        input = sp;
                    } else {
                        EditText et = new EditText(this);
                        et.setSingleLine(true);
                        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                        if ("number".equals(f.type)) {
                            et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                        } else if ("password".equals(f.type)) {
                            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                        } else {
                            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        }
                        et.setText(String.valueOf(cfg.opt(f.key) == null ? "" : cfg.opt(f.key)));
                        input = et;
                    }
                }
                form.addView(input, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                inputs.put(f, input);
                if (f.hint != null && !f.hint.isEmpty()) {
                    TextView h = new TextView(this);
                    h.setText(f.hint);
                    h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    h.setTextColor(0xFF93A4B1);
                    form.addView(h);
                }
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(form);

        Button save = new Button(this);
        save.setText("Zapisz ustawienia");
        save.setOnClickListener(v -> save());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10171C);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(16), dp(6), dp(16), dp(12));
        root.addView(save, blp);
        setContentView(root);
    }

    private void save() {
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<Fields.F, View> e : inputs.entrySet()) {
                Fields.F f = e.getKey();
                View v = e.getValue();
                if (v instanceof Switch) {
                    out.put(f.key, ((Switch) v).isChecked());
                } else if (v instanceof Spinner) {
                    int i = ((Spinner) v).getSelectedItemPosition();
                    if (i >= 0 && i < f.values.length) out.put(f.key, f.values[i]);
                } else if (v instanceof EditText) {
                    out.put(f.key, ((EditText) v).getText().toString().trim());
                }
            }
            Cfg.update(out);
            IntercomService s = IntercomService.instance;
            if (s != null) s.applyConfig();
            Toast.makeText(this, "Zapisano ustawienia", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Throwable t) {
            L.e("Zapis ustawien z menu", t);
            Toast.makeText(this, "Nie udało się zapisać: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
