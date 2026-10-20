package ir.robocar.app;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Thin shell: binds the app to the car's Wi-Fi (works even with mobile data on) and shows the car's own control page. */
public class MainActivity extends Activity {
    private static final String CAR_URL = "http://192.168.4.1/";
    private static final String ERROR_HTML =
        "<html dir='rtl'><body style='background:#0b1020;color:#e8ecff;font-family:sans-serif;text-align:center;padding-top:20vh'>"
        + "<h2>ماشین پیدا نشد</h2>"
        + "<p>گوشی را به Wi-Fi با نام <b>RoboCar</b> وصل کنید و ماشین را روشن کنید.</p>"
        + "<button style='font-size:18px;padding:12px 28px;border-radius:12px;border:0;background:#2de2c4' "
        + "onclick=\"location.href='" + CAR_URL + "'\">تلاش دوباره</button></body></html>";

    private WebView web;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean started = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1020);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadDataWithBaseURL(CAR_URL, ERROR_HTML, "text/html", "utf-8", null);
            }
        });
        setContentView(web);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void load() {
        if (started) return;
        started = true;
        web.loadUrl(CAR_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        started = false;
        // Ask for the Wi-Fi network and bind to it so traffic to 192.168.4.1 goes there even if Android prefers mobile data.
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(final Network n) {
                    cm.bindProcessToNetwork(n);
                    ui.post(new Runnable() { @Override public void run() { load(); } });
                }
            };
            cm.requestNetwork(req, callback);
        } catch (Exception e) {
            load();
        }
        ui.postDelayed(new Runnable() { @Override public void run() { load(); } }, 2500);  // fallback
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("try{window.dispatchEvent(new Event('blur'))}catch(e){}", null);
        web.loadUrl("about:blank");           // closes the WebSocket: the car stops
        try { if (callback != null) cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        callback = null;
    }
}
