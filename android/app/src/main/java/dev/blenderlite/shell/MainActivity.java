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
 * A shell and nothing else. Blender itself is not in this APK: the page it opens carries the
 * WebAssembly build, and this activity only provides a browser engine, a capability check and a way
 * out to the real Chrome when the engine here cannot do the job.
 *
 * That way out matters. Blender's web build renders through WebGPU, which Android's Chrome has and
 * Android WebView has only on some versions, so the honest design is: measure first, then either run
 * here or hand the same URL to Chrome rather than show a blank viewport and no reason.
 */
public class MainActivity extends Activity {
    /** The probe is bundled, so the check works with no network at all. */
    static final String PROBE = "file:///android_asset/probe.html";
    /** Where the real thing lives. Kept here so "Open in Chrome" sends exactly the same URL. */
    static final String BLENDER_WEB = "https://developer.puter.com/labs/blender-web/";

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
        Button chrome = new Button(this);
        chrome.setText(R.string.open_chrome);
        chrome.setOnClickListener(view -> openInChrome());
        Button again = new Button(this);
        again.setText(R.string.reload);
        again.setOnClickListener(view -> runProbe());
        buttons.addView(chrome);
        buttons.addView(again);
        root.addView(buttons);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                status.setText(summarise(url));
            }
        });
        root.addView(web);
        setContentView(root);

        if (state == null) runProbe();
    }

    private void runProbe() {
        status.setText(R.string.status_probe);
        web.loadUrl(PROBE);
    }

    /** What the probe page wrote into its own title, or why there is nothing to report yet. */
    private String summarise(String url) {
        String title = web.getTitle();
        if (title != null && title.startsWith("probe:")) return title;
        return url.startsWith(BLENDER_WEB) ? url : getString(R.string.status_probe);
    }

    private void openInChrome() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB))
                    .setPackage("com.android.chrome"));
        } catch (ActivityNotFoundException noChrome) {
            // Any browser that has WebGPU is better than a WebView that does not.
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BLENDER_WEB)));
        }
    }

    @Override protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
