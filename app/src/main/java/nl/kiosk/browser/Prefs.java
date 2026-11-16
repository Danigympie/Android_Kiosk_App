package nl.kiosk.browser;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;

final class Prefs {
    static final String DEFAULT_URL = "https://www.google.nl";
    private final SharedPreferences p;

    Prefs(Context c) { p = c.getSharedPreferences("kiosk", Context.MODE_PRIVATE); }

    String url() { return p.getString("url", DEFAULT_URL); }
    boolean screensaver() { return p.getBoolean("ss", true); }
    int screensaverSeconds() { return p.getInt("ssSec", 60); }
    boolean lock() { return p.getBoolean("lock", true); }
    boolean autostart() { return p.getBoolean("boot", true); }
    int reloadMinutes() { return p.getInt("reload", 0); }

    boolean motion() { return p.getBoolean("motion", true); }

    void save(String url, boolean ss, int ssSec, boolean lock, boolean boot, int reload, boolean motion) {
        p.edit().putString("url", url).putBoolean("ss", ss).putInt("ssSec", ssSec)
                .putBoolean("lock", lock).putBoolean("boot", boot).putInt("reload", reload)
                .putBoolean("motion", motion).apply();
    }

    static String hash(String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("kiosk:" + pin).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    boolean checkPin(String pin) { return hash(pin).equals(p.getString("pin", hash("1234"))); }
    void setPin(String pin) { p.edit().putString("pin", hash(pin)).apply(); }
}
