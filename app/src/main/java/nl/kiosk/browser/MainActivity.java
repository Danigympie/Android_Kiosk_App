package nl.kiosk.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.Toast;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private Prefs prefs;
    private FrameLayout root;
    private WebView web;
    private LinearLayout saver;
    private View customView;
    private boolean saverOn;
    private long cornerStart;
    private int cornerTaps;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable showSaver = this::startSaver;
    private final Runnable reload = new Runnable() {
        @Override public void run() {
            if (web != null && !saverOn) web.reload();
            scheduleReload();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        buildWeb();
        buildSaver();
        web.loadUrl(prefs.url());
    }

    private void buildWeb() {
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setBuiltInZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadData("<html><body style='background:#111;color:#fff;font-family:sans-serif;"
                            + "text-align:center;padding-top:20%'><h1>Pagina niet bereikbaar</h1>"
                            + "<p>Nieuwe poging over 10 seconden...</p></body></html>", "text/html", "UTF-8");
                    h.postDelayed(() -> web.loadUrl(prefs.url()), 10000);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                customView = v;
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() {
                if (customView != null) root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildSaver() {
        saver = new LinearLayout(this);
        saver.setOrientation(LinearLayout.VERTICAL);
        saver.setGravity(Gravity.CENTER);
        saver.setBackgroundColor(Color.BLACK);
        saver.setVisibility(View.GONE);
        saver.setClickable(true);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(110);
        time.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        time.setGravity(Gravity.CENTER);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(0xFFAAAAAA);
        date.setTextSize(28);
        date.setGravity(Gravity.CENTER);
        saver.addView(time, new LinearLayout.LayoutParams(-2, -2));
        saver.addView(date, new LinearLayout.LayoutParams(-2, -2));
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
    }

    private void startSaver() {
        if (!prefs.screensaver()) return;
        saverOn = true;
        saver.setVisibility(View.VISIBLE);
        saver.bringToFront();
    }

    private void resetIdle() {
        h.removeCallbacks(showSaver);
        if (prefs.screensaver()) h.postDelayed(showSaver, prefs.screensaverSeconds() * 1000L);
    }

    private void scheduleReload() {
        h.removeCallbacks(reload);
        if (prefs.reloadMinutes() > 0) h.postDelayed(reload, prefs.reloadMinutes() * 60000L);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            // Verborgen toegang: 5x tikken in de linkerbovenhoek binnen 4 seconden
            if (e.getX() < 150 && e.getY() < 150 && !saverOn) {
                long now = System.currentTimeMillis();
                if (now - cornerStart > 4000) { cornerStart = now; cornerTaps = 0; }
                if (++cornerTaps >= 5) { cornerTaps = 0; askPin(this::openSettings); }
            }
            boolean wasOn = saverOn;
            saverOn = false;
            saver.setVisibility(View.GONE);
            resetIdle();
            if (wasOn) return true; // eerste tik sluit alleen de screensaver
        }
        return super.dispatchTouchEvent(e);
    }

    private void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    private void askPin(Runnable ok) {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this).setTitle("Voer pincode in").setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    if (prefs.checkPin(in.getText().toString())) ok.run();
                    else Toast.makeText(this, "Onjuiste pincode", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuleren", null).show();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (customView != null) web.getWebChromeClient().onHideCustomView();
            return true; // terugknop uitgeschakeld
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onResume() {
        super.onResume();
        prefs = new Prefs(this);
        hideSystemUi();
        try { if (prefs.lock()) startLockTask(); else stopLockTask(); } catch (Exception ignored) {}
        if (SettingsActivity.urlChanged) { SettingsActivity.urlChanged = false; web.loadUrl(prefs.url()); }
        resetIdle();
        scheduleReload();
        web.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
        h.removeCallbacks(showSaver);
        h.removeCallbacks(reload);
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Vanuit instellingen, na correcte pincode: kiosk volledig afsluiten. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("exit", false)) {
            try { stopLockTask(); } catch (Exception ignored) {}
            finishAndRemoveTask();
        }
    }
}
