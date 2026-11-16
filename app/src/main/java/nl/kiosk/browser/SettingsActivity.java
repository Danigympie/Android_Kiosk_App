package nl.kiosk.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    static boolean urlChanged;
    private Prefs prefs;
    private EditText url, ssSec, reload, newPin;
    private CheckBox ss, lock, boot, motion;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        l.setPadding(p, p, p, p);
        sv.addView(l);
        setContentView(sv);

        url = field(l, "Webadres (URL)", prefs.url(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        reload = field(l, "Pagina automatisch herladen (minuten, 0 = nooit)", "" + prefs.reloadMinutes(), InputType.TYPE_CLASS_NUMBER);
        ss = check(l, "Screensaver met klok inschakelen", prefs.screensaver());
        ssSec = field(l, "Screensaver na (seconden zonder aanraking)", "" + prefs.screensaverSeconds(), InputType.TYPE_CLASS_NUMBER);
        motion = check(l, "Screensaver uit bij beweging (camera)", prefs.motion());
        lock = check(l, "Kioskmodus (scherm vastzetten, geen navigatie)", prefs.lock());
        boot = check(l, "Automatisch starten na herstart van het apparaat", prefs.autostart());
        newPin = field(l, "Nieuwe pincode (leeg laten = ongewijzigd)", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        Button save = new Button(this);
        save.setText("Opslaan en terug");
        save.setOnClickListener(v -> save());
        l.addView(save);

        Button exit = new Button(this);
        exit.setText("Kiosk-app afsluiten");
        exit.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra("exit", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        l.addView(exit);
    }

    private EditText field(LinearLayout l, String label, String val, int type) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, 24, 0, 0);
        l.addView(t);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(val);
        l.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout l, String label, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(v);
        c.setPadding(0, 24, 0, 0);
        l.addView(c);
        return c;
    }

    private int num(EditText e, int def) {
        try { return Math.max(0, Integer.parseInt(e.getText().toString().trim())); } catch (Exception x) { return def; }
    }

    private void save() {
        String u = url.getText().toString().trim();
        if (u.isEmpty()) u = Prefs.DEFAULT_URL;
        if (!u.contains("://")) u = "https://" + u;
        if (!u.equals(prefs.url())) urlChanged = true;
        String pin = newPin.getText().toString();
        if (!pin.isEmpty() && pin.length() < 4) {
            Toast.makeText(this, "Pincode moet minimaal 4 cijfers zijn", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.save(u, ss.isChecked(), Math.max(5, num(ssSec, 60)), lock.isChecked(), boot.isChecked(), num(reload, 0), motion.isChecked());
        if (!pin.isEmpty()) prefs.setPin(pin);
        finish();
    }
}
