package nl.kiosk.browser;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import java.nio.ByteBuffer;
import java.util.Collections;

/** Detecteert beweging via de voorcamera door opeenvolgende beeldjes te vergelijken. */
final class MotionDetector {
    interface Listener { void onMotion(); }

    private static final int GRID = 24;          // 24x24 meetpunten
    private static final int PIXEL_DIFF = 25;    // verschil in helderheid per punt (0-255)
    private static final float CHANGED_FRACTION = 0.04f; // aandeel punten dat moet veranderen

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private int[] prev;
    private int frame;
    private long lastNotify;
    private boolean running;

    MotionDetector(Context ctx, Listener l) { this.ctx = ctx.getApplicationContext(); this.listener = l; }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("motion");
        thread.start();
        bg = new Handler(thread.getLooper());
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String s : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(s).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { id = s; break; }
                if (id == null) id = s; // val terug op de eerste camera
            }
            if (id == null) { running = false; return; }
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            for (Size sz : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (sz.getWidth() >= 320 && (best == null || sz.getWidth() * sz.getHeight() < best.getWidth() * best.getHeight()))
                    best = sz;
            }
            if (best == null) best = map.getOutputSizes(ImageFormat.YUV_420_888)[0];
            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    synchronized (MotionDetector.this) {
                        if (!running) { c.close(); return; }
                        camera = c;
                    }
                    try {
                        CaptureRequest.Builder rb = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(reader.getSurface());
                        c.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try { s.setRepeatingRequest(rb.build(), null, bg); } catch (Exception ignored) {}
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {}
                                }, bg);
                    } catch (CameraAccessException ignored) {}
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); }
                @Override public void onError(CameraDevice c, int e) { c.close(); }
            }, bg);
        } catch (Exception e) {
            running = false;
        }
    }

    synchronized void stop() {
        running = false;
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; camera = null; reader = null; prev = null;
        if (thread != null) { thread.quitSafely(); thread = null; }
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            if (++frame % 4 != 0) return; // ongeveer 7 vergelijkingen per seconde
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), rs = p.getRowStride();
            int[] cur = new int[GRID * GRID];
            for (int gy = 0; gy < GRID; gy++) {
                for (int gx = 0; gx < GRID; gx++) {
                    int x = (gx * w) / GRID, y = (gy * h) / GRID;
                    cur[gy * GRID + gx] = buf.get(y * rs + x) & 0xFF;
                }
            }
            if (prev != null) {
                int changed = 0;
                for (int i = 0; i < cur.length; i++) if (Math.abs(cur[i] - prev[i]) > PIXEL_DIFF) changed++;
                if (changed >= cur.length * CHANGED_FRACTION) {
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 1000) { lastNotify = now; main.post(listener::onMotion); }
                }
            }
            prev = cur;
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }
}
