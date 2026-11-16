package nl.kiosk.browser;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import org.json.JSONObject;

/** Kleine HTTP-server zodat je de instellingen vanaf een computer kunt aanpassen (pincode vereist). */
final class WebServer {
    interface Host { void changed(); void reload(); }

    static final int PORT = 8080;
    private static ServerSocket server;
    private static Host host;
    private static int fails;
    private static long lockedUntil;

    static synchronized void start(Context ctx, Host h) {
        host = h;
        if (server != null) return;
        final Context app = ctx.getApplicationContext();
        try { server = new ServerSocket(PORT); } catch (IOException e) { server = null; return; }
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    new Thread(() -> handle(app, s)).start();
                } catch (IOException e) { break; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
        host = null;
    }

    static synchronized boolean running() { return server != null; }

    /** Lokaal IPv4-adres van de tablet, of null. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static synchronized boolean authorized(Context ctx, String pin) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return false;
        boolean ok = new Prefs(ctx).checkPin(pin);
        if (ok) fails = 0;
        else if (++fails >= 5) { lockedUntil = now + 60000; fails = 0; }
        return ok;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        return (c == -1 && b.size() == 0) ? null : b.toString("UTF-8");
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, int code, JSONObject o) throws IOException {
        respond(out, code, "application/json", o.toString().getBytes("UTF-8"));
    }

    private static void handle(Context ctx, Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            String[] p = req.split(" ");
            if (p.length < 2) return;
            int len = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
            }
            if (len > 65536) { respond(out, 413, "text/plain", "Te groot".getBytes()); return; }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            String path = p[1].split("\\?")[0];

            if (p[0].equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                try (InputStream a = ctx.getAssets().open("settings.html")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
                    respond(out, 200, "text/html", b.toByteArray());
                }
                return;
            }
            if (p[0].equals("POST") && path.startsWith("/api/")) {
                JSONObject j = len > 0 ? new JSONObject(new String(body, 0, off, "UTF-8")) : new JSONObject();
                if (!authorized(ctx, j.optString("pin"))) {
                    json(out, 401, new JSONObject().put("error", "Onjuiste pincode (of tijdelijk geblokkeerd, wacht een minuut)"));
                    return;
                }
                Prefs pr = new Prefs(ctx);
                switch (path) {
                    case "/api/get":
                        json(out, 200, new JSONObject().put("url", pr.url()).put("reload", pr.reloadMinutes())
                                .put("screensaver", pr.screensaver()).put("ssSec", pr.screensaverSeconds())
                                .put("saverType", pr.saverType()).put("yt", pr.youtube())
                                .put("motion", pr.motion()).put("lock", pr.lock()).put("boot", pr.autostart())
                                .put("zoom", pr.zoom()).put("orient", pr.orientation()).put("web", pr.webEnabled()));
                        return;
                    case "/api/save": {
                        String u = j.optString("url", pr.url()).trim();
                        if (u.isEmpty()) u = Prefs.DEFAULT_URL;
                        if (!u.contains("://")) u = "https://" + u;
                        pr.save(u, j.optBoolean("screensaver", true), Math.max(5, j.optInt("ssSec", 60)),
                                j.optBoolean("lock", true), j.optBoolean("boot", true),
                                Math.max(0, j.optInt("reload", 0)), j.optBoolean("motion", true));
                        pr.saveView(Math.min(300, Math.max(50, j.optInt("zoom", 100))),
                                Math.min(2, Math.max(0, j.optInt("orient", 0))));
                        pr.saveSaver(Math.min(1, Math.max(0, j.optInt("saverType", 0))), j.optString("yt", "").trim());
                        pr.saveWeb(j.optBoolean("web", true));
                        String np = j.optString("newPin", "");
                        if (!np.isEmpty()) {
                            if (!np.matches("\\d{4,}")) {
                                json(out, 400, new JSONObject().put("error", "Nieuwe pincode: minimaal 4 cijfers"));
                                return;
                            }
                            pr.setPin(np);
                        }
                        Host h = host;
                        if (h != null) h.changed();
                        json(out, 200, new JSONObject().put("ok", true));
                        return;
                    }
                    case "/api/reload": {
                        Host h = host;
                        if (h != null) h.reload();
                        json(out, 200, new JSONObject().put("ok", true));
                        return;
                    }
                }
            }
            respond(out, 404, "text/plain", "Niet gevonden".getBytes());
        } catch (Exception ignored) {
        }
    }
}
