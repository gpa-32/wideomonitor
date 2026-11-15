package pl.wideodomofon.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Dziennik zdarzen i diagnostyka kamery w menu aplikacji. */
public class LogActivity extends Activity {
    private TextView text;
    private ScrollView scroll;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTextColor(0xFFDDDDDD);
        text.setTextIsSelectable(true);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll = new ScrollView(this);
        scroll.addView(text);

        Button refresh = new Button(this);
        refresh.setText("Odśwież dziennik");
        refresh.setOnClickListener(v -> showLog());
        Button diag = new Button(this);
        diag.setText("Diagnostyka kamery");
        diag.setOnClickListener(v -> runDiag());
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(8));
        bar.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(diag, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1014);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bar);
        setContentView(root);
        showLog();
    }

    private void showLog() {
        text.setText(L.dump());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void runDiag() {
        text.setText("Sprawdzam połączenie z kamerą… (do 30 s)");
        Thread t = new Thread(() -> {
            String out;
            try {
                out = Diag.camera();
            } catch (Throwable e) {
                out = "Błąd diagnostyki: " + e;
            }
            final String o = out;
            runOnUiThread(() -> {
                if (!isFinishing()) text.setText(o);
            });
        }, "diag");
        t.setDaemon(true);
        t.start();
    }
}
