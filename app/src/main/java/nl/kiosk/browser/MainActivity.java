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
    private WebView saverWeb;
    private View customView;
    private boolean saverOn;
    private long cornerStart;
    private int cornerTaps;
    private MotionDetector motion;
    private float downX, downY;
    private boolean downAtTop, pulled;
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
        if (prefs.webEnabled()) {
            WebServer.start(this, new WebServer.Host() {
                @Override public void changed() { runOnUiThread(() -> applySettings()); }
                @Override public void reload() { runOnUiThread(() -> web.reload()); }
            });
        }
    }

    @Override protected void onDestroy() {
        WebServer.stop();
        super.onDestroy();
    }

    /** Instellingen zijn op afstand gewijzigd: alles opnieuw toepassen. */
    private void applySettings() {
        prefs = new Prefs(this);
        web.loadUrl(prefs.url());
        applyOrientation();
        applyLock();
        resetIdle();
        scheduleReload();
        updateMotion();
    }

    private void applyLock() {
        try {
            boolean pinned = ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE))
                    .getLockTaskModeState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE;
            if (prefs.lock() && !pinned) startLockTask();      // alleen als nog niet vastgezet (geen herhaalde melding)
            else if (!prefs.lock() && pinned) stopLockTask();
        } catch (Exception ignored) {}
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
            @Override public void onPageFinished(WebView v, String u) { applyZoom(); }
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
        saverWeb = new WebView(this);
        saverWeb.setBackgroundColor(Color.BLACK);
        saverWeb.getSettings().setJavaScriptEnabled(true);
        saverWeb.getSettings().setMediaPlaybackRequiresUserGesture(false);
        saverWeb.getSettings().setDomStorageEnabled(true);
        saverWeb.setOnTouchListener((v, ev) -> true); // aanrakingen gaan naar de activity
        saver.addView(saverWeb, new LinearLayout.LayoutParams(-1, -1));
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
    }

    private void startSaver() {
        if (!prefs.screensaver()) return;
        saverOn = true;
        String[] yt = prefs.saverType() == 1 ? youtubeSpec(prefs.youtube()) : null;
        if (yt != null) {
            boolean isList = yt[0].equals("list");
            String html = "<html><head><style>html,body{margin:0;background:#000;height:100%}"
                    + "iframe{position:fixed;top:0;left:0;width:100%;height:100%;border:0}</style></head><body>"
                    + "<div id='p'></div><script src='https://www.youtube.com/iframe_api'></script><script>"
                    + "function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',"
                    + (isList ? "" : "videoId:'" + yt[1] + "',")
                    + "playerVars:{autoplay:1,mute:1,controls:0,playsinline:1,rel:0,modestbranding:1,"
                    + "disablekb:1,iv_load_policy:3" + (isList ? ",listType:'playlist',list:'" + yt[1] + "',loop:1" : "")
                    + "},events:{onReady:function(e){e.target.mute();e.target.playVideo()},"
                    + "onStateChange:function(e){if(e.data===0&&" + (!isList)
                    + "){e.target.seekTo(0);e.target.playVideo()}}}})}</script></body></html>";
            saverWeb.loadDataWithBaseURL("https://kiosk.nl.browser/", html, "text/html", "utf-8", null);
        } else {
            saverWeb.loadUrl("file:///android_asset/flipclock.html");
        }
        saverWeb.onResume();
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

    private void stopSaverContent() {
        saverWeb.loadUrl("about:blank"); // stopt video/klok
        saverWeb.onPause();
    }

    /** Zet een YouTube-link, video-ID of afspeellijst om naar {soort, id} ("video" of "list"), of null. */
    private static String[] youtubeSpec(String in) {
        if (in == null || in.trim().isEmpty()) return null;
        in = in.trim();
        java.util.regex.Matcher list = java.util.regex.Pattern.compile("list=([A-Za-z0-9_-]+)").matcher(in);
        if (list.find()) return new String[]{"list", list.group(1)};
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:v=|youtu\\.be/|embed/|shorts/|live/)([A-Za-z0-9_-]{11})").matcher(in);
        String id = m.find() ? m.group(1) : (in.matches("[A-Za-z0-9_-]{11}") ? in : null);
        return id == null ? null : new String[]{"video", id};
    }

    private void applyZoom() {
        int z = prefs.zoom();
        if (z != 100) web.evaluateJavascript("document.documentElement.style.zoom='" + (z / 100f) + "'", null);
    }

    private void applyOrientation() {
        int[] modes = {android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT};
        setRequestedOrientation(modes[prefs.orientation()]);
    }

    /** Beweging gezien door de camera: screensaver uit en timer opnieuw starten. */
    private void onMotion() {
        saverOn = false;
        saver.setVisibility(View.GONE);
        stopSaverContent();
        resetIdle();
    }

    private void updateMotion() {
        boolean want = prefs.motion() && prefs.screensaver();
        if (!want) { if (motion != null) motion.stop(); return; }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
            return;
        }
        if (motion == null) motion = new MotionDetector(this, this::onMotion);
        motion.start();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) updateMotion();
    }

    /** Naar beneden vegen vanaf de bovenkant van de pagina = herladen. */
    private void handleSwipe(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); pulled = false;
                downAtTop = customView == null && !web.canScrollVertically(-1);
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY, dx = e.getX() - downX;
                if (downAtTop && !pulled && dy > 300 && dy > 2 * Math.abs(dx)) {
                    pulled = true;
                    Toast.makeText(this, "Pagina wordt herladen...", Toast.LENGTH_SHORT).show();
                    web.reload();
                }
                break;
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        handleSwipe(e);
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
            stopSaverContent();
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
        applyOrientation();
        applyLock();
        if (SettingsActivity.urlChanged) { SettingsActivity.urlChanged = false; web.loadUrl(prefs.url()); }
        resetIdle();
        scheduleReload();
        updateMotion();
        web.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        web.onPause();
        if (motion != null) motion.stop();
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
