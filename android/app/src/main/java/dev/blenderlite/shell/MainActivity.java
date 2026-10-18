package dev.blenderlite.shell;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * A shell and nothing else, but a shell that owns its payload. Blender is not compiled into this
 * APK - 145 MB of wasm does not belong in one - yet nothing outside this app is required at runtime
 * either: the payload is fetched once from this project's own release, verified by sha256, kept in
 * the app's files directory, and served to the WebView from 127.0.0.1 with the COOP and COEP headers
 * the wasm threads need. After the first fetch the app runs with no network at all.
 *
 * The status line always says what actually happened: probe result, fetch progress in megabytes,
 * the serving origin, and then what the loaded page reports about itself - isolation, WebGPU,
 * canvas count - at once and again after eight seconds, so a blank viewport can be told apart from
 * a build that never booted.
 */
public class MainActivity extends Activity {
    static final String PROBE = "file:///android_asset/probe.html";
    static final String BLENDER_WEB = "https://developer.puter.com/labs/blender-web/";

    static final String READBACK =
            "(function(){return 'page: isolated=' + self.crossOriginIsolated"
                    + " + ' gpu=' + (!!navigator.gpu)"
                    + " + ' canvases=' + document.getElementsByTagName('canvas').length"
                    + " + ' title=' + document.title.slice(0, 48);})()";

    private TextView status;
    private WebView web;
    private PackServer server;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText(R.string.status_probe);
        status.setPadding(24, 16, 24, 16);
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button(getString(R.string.launch_here), view -> launchHere()));
        buttons.addView(button(getString(R.string.reload), view -> runProbe()));
        buttons.addView(button(getString(R.string.open_chrome), view -> openInChrome()));
        root.addView(buttons);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith(PackServer.ORIGIN)) readBack(view, 0);
                else status.setText(summarise(url));
            }
        });
        root.addView(web);
        setContentView(root);

        if (state == null) runProbe();
    }

    private Button button(String label, android.view.View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(onClick);
        return b;
    }

    private void runProbe() {
        status.setText(R.string.status_probe);
        web.loadUrl(PROBE);
    }

    /** Fetch what is missing, start serving it from loopback, open it in this WebView. */
    private void launchHere() {
        say(getString(R.string.status_pack));
        File packDir = new File(getFilesDir(), "pack");
        new Thread(() -> {
            try {
                PackFetch.ensure(packDir, this::say);
                if (server == null) {
                    server = new PackServer(this, packDir);
                    Thread t = new Thread(server, "pack-server-main");
                    t.setDaemon(true);
                    t.start();
                }
                say(getString(R.string.status_serving));
                runOnUiThread(() -> web.loadUrl(PackServer.ORIGIN + "/"));
            } catch (Exception e) {
                say("pack failed: " + e);
            }
        }, "pack-fetch").start();
    }

    private void say(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    private void readBack(WebView view, long delayMs) {
        view.postDelayed(() -> view.evaluateJavascript(READBACK, value -> {
            if (value != null && value.length() > 1 && value.startsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            status.setText(value);
        }), delayMs);
        if (delayMs == 0) readBack(view, 8000);
    }

    private String summarise(String url) {
        String title = web.getTitle();
        if (title != null && title.startsWith("probe:")) return title;
        return url.startsWith(BLENDER_WEB) ? getString(R.string.status_loading)
                : getString(R.string.status_probe);
    }

    private void openInChrome() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB))
                    .setPackage("com.android.chrome"));
        } catch (ActivityNotFoundException noChrome) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB)));
        }
    }

    @Override protected void onDestroy() {
        if (server != null) server.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
