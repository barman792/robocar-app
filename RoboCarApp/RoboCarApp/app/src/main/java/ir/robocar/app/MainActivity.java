package ir.robocar.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * RoboCar app.
 *  - The whole control UI is bundled in the app (assets/index.html), so every feature is visible even when the
 *    car is off; the page itself shows "car not connected" with the steps to connect and keeps retrying.
 *  - Binds the process to the Wi-Fi network so the car's Wi-Fi is used even when mobile data is on.
 *  - Full-screen, screen always on, the connection is closed when the app leaves the screen (the car stops).
 *  - Tilt steering via the accelerometer -> window.__tilt(deg) in the page.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String UI_URL = "file:///android_asset/index.html";

    private WebView web;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // tilt steering
    private SensorManager sm;
    private boolean tiltOn = false, tiltInvert = false, zeroPending = false;
    private float tiltSmooth = 0f, tiltZero = 0f;
    private long lastTiltSend = 0;

    /** Methods callable from the page as RCApp.xxx() */
    private final class Bridge {
        @JavascriptInterface public void setTilt(final boolean on) {
            ui.post(new Runnable() { @Override public void run() { enableTilt(on); } });
        }
        @JavascriptInterface public void tiltInvert(final boolean inv) { tiltInvert = inv; }
        @JavascriptInterface public void tiltZero() { tiltZero = tiltSmooth; }
        @JavascriptInterface public void openWifi() {
            ui.post(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {}
                }
            });
        }
        @JavascriptInterface public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(Math.max(1, Math.min(ms, 80)));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D10);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "RCApp");
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Bind to the Wi-Fi network so traffic to the car goes there even if Android prefers mobile data.
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(final Network n) {
                    try { cm.bindProcessToNetwork(n); } catch (Exception ignored) {}
                }
            };
            cm.requestNetwork(req, callback);
        } catch (Exception ignored) {}
        web.loadUrl(UI_URL);                  // the page connects (and keeps retrying) by itself
    }

    @Override
    protected void onPause() {
        super.onPause();
        enableTilt(false);
        web.loadUrl("about:blank");           // closes the WebSocket: the car stops
        try { if (callback != null) cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        callback = null;
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        // Let the page close its sheet / card / demo first; leave the app only when it says "exit".
        web.evaluateJavascript("window.__back?window.__back():'exit'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v == null || v.contains("exit") || v.equals("null")) finish();
            }
        });
    }

    // ---------------- tilt steering ----------------
    private void enableTilt(boolean on) {
        if (on == tiltOn) return;
        tiltOn = on;
        if (on) {
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a != null) { zeroPending = true; sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME); }
            else tiltOn = false;
        } else {
            sm.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!tiltOn) return;
        float ax = e.values[0], ay = e.values[1];
        double th;
        // Angle of the "steering wheel" (clockwise = positive) from the direction of gravity in the screen plane.
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:  th = Math.toDegrees(Math.atan2(ay, Math.abs(ax))); break;
            case Surface.ROTATION_270: th = Math.toDegrees(Math.atan2(-ay, Math.abs(ax))); break;
            case Surface.ROTATION_180: th = Math.toDegrees(Math.atan2(ax, Math.abs(ay))); break;
            default:                   th = Math.toDegrees(Math.atan2(-ax, Math.abs(ay))); break;
        }
        if (zeroPending) { tiltSmooth = (float) th; tiltZero = tiltSmooth; zeroPending = false; }
        tiltSmooth += 0.25f * ((float) th - tiltSmooth);
        long now = System.currentTimeMillis();
        if (now - lastTiltSend < 33) return;
        lastTiltSend = now;
        float v = (tiltSmooth - tiltZero) * (tiltInvert ? -1f : 1f);
        web.evaluateJavascript(String.format(Locale.US, "window.__tilt&&window.__tilt(%.1f)", v), null);
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) {}
}
