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

/**
 * A shell and nothing else. Blender is not in this APK: the page it opens carries the WebAssembly
 * build, and this activity only provides a browser engine, a capability check and, when that check
 * passes, the launch itself - here, in this WebView, not in some other browser.
 *
 * The web build is served by its host with Cross-Origin-Opener-Policy: same-origin and
 * Cross-Origin-Embedder-Policy: require-corp, verified with curl before this was written. Loading
 * that URL directly is therefore enough for crossOriginIsolated to become true and for the wasm
 * threads to start; no local proxy is needed and none is pretended.
 *
 * After the page finishes, the shell reads back what the page itself sees - isolation, WebGPU,
 * canvas count - and prints it in the status line, twice: at once and again after eight seconds, so
 * a blank viewport can be told apart from a build that never booted.
 */
public class MainActivity extends Activity {
    /** The probe is bundled, so the check works with no network at all. */
    static final String PROBE = "file:///android_asset/probe.html";
    /** Where the real thing lives, with the COOP/COEP headers its threads need. */
    static final String BLENDER_WEB = "https://developer.puter.com/labs/blender-web/";

    /** What the loaded page reports about itself, in its own words. */
    static final String READBACK =
            "(function(){return 'page: isolated=' + self.crossOriginIsolated"
                    + " + ' gpu=' + (!!navigator.gpu)"
                    + " + ' canvases=' + document.getElementsByTagName('canvas').length"
                    + " + ' title=' + document.title.slice(0, 48);})()";

    private TextView status;
    private WebView web;

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
                if (url.startsWith(BLENDER_WEB)) readBack(view, 0);
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

    /** The point of the app: the web build, inside this app. */
    private void launchHere() {
        status.setText(R.string.status_loading);
        web.loadUrl(BLENDER_WEB);
    }

    /** Ask the page what it sees, now and again a little later. */
    private void readBack(WebView view, long delayMs) {
        view.postDelayed(() -> view.evaluateJavascript(READBACK, value -> {
            if (value != null && value.length() > 1 && value.startsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            status.setText(value);
        }), delayMs);
        if (delayMs == 0) readBack(view, 8000);
    }

    /** What the probe page wrote into its own title, or why there is nothing to report yet. */
    private String summarise(String url) {
        String title = web.getTitle();
        if (title != null && title.startsWith("probe:")) return title;
        return url.startsWith(BLENDER_WEB) ? getString(R.string.status_loading)
                : getString(R.string.status_probe);
    }

    /** Only a fallback, for engines whose probe said no: never the default path. */
    private void openInChrome() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB))
                    .setPackage("com.android.chrome"));
        } catch (ActivityNotFoundException noChrome) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB)));
        }
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
