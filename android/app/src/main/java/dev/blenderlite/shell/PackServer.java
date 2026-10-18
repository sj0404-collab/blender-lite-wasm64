package dev.blenderlite.shell;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Serves the Blender web build from this device, on this device's own loopback, so that the page
 * runs from our origin with the headers its wasm threads require.
 *
 * Two roots, one origin. The wrapper (html, js, css) is tiny and ships inside the APK as an asset.
 * The payload (blender.wasm.zst, about 145 MB, plus assets.tar.zst) is far too large for an APK, so
 * it is fetched once into the app's own files directory and streamed from there. The wrapper asks
 * for "manifest.json" and then for the names it lists, exactly as it would from its original host,
 * so none of its code is patched: only the origin changes.
 *
 * Every response carries Cross-Origin-Opener-Policy: same-origin and Cross-Origin-Embedder-Policy:
 * require-corp. That is what makes crossOriginIsolated true and SharedArrayBuffer real; the
 * original host sends the same two headers, verified with curl before this was written.
 *
 * blender.wasm.zst is stored in parts, each under GitHub's 100 MB per-file limit; a request for the
 * whole name streams the parts in order under one Content-Length, so the page never sees the seam.
 */
public class PackServer implements Runnable {
    public static final int PORT = 8788;
    public static final String ORIGIN = "http://127.0.0.1:" + PORT;

    private final Context context;
    private final File packDir;
    private volatile boolean running;

    public PackServer(Context context, File packDir) {
        this.context = context;
        this.packDir = packDir;
    }

    @Override public void run() {
        running = true;
        try (ServerSocket server = new ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))) {
            while (running) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> serve(socket), "pack-server");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Exception e) {
            running = false;
        }
    }

    public void stop() {
        running = false;
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int prev = 0;
            while (true) {
                int b = in.read();
                if (b < 0) return;
                head.append((char) b);
                if (head.length() > 8192) return;
                if ((prev << 8 | b) == 0x0a0d && head.length() >= 4
                        && head.charAt(head.length() - 3) == '\r'
                        && head.charAt(head.length() - 4) == '\n') break;
                prev = b;
            }
            String line = head.toString().split("\r\n", 2)[0];
            String[] req = line.split(" ");
            if (req.length < 2) return;
            String path = req[1].split("\\?", 2)[0];
            if (path.equals("/")) path = "/index.html";

            if (path.startsWith("/assets/")) {
                try (InputStream a = context.getAssets().open("wrapper" + path)) {
                    respond(s, path, a, -1);
                }
                return;
            }
            if (path.equals("/index.html")) {
                try (InputStream a = context.getAssets().open("wrapper/index.html")) {
                    respond(s, path, a, -1);
                }
                return;
            }

            String name = path.substring(1);
            String[] parts = PackFetch.serveParts(packDir, name);
            if (parts == null) {
                String body = "not in pack: " + name;
                OutputStream o = s.getOutputStream();
                o.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length()
                        + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
                o.flush();
                return;
            }
            long total = 0;
            for (String p : parts) total += new File(packDir, p).length();
            OutputStream o = s.getOutputStream();
            o.write(headers(path, total).getBytes(StandardCharsets.UTF_8));
            for (String p : parts) {
                try (InputStream f = new FileInputStream(new File(packDir, p))) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = f.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
            o.flush();
        } catch (Exception ignored) {
            // A browser that goes away mid-download is normal, not an error to surface.
        }
    }

    private void respond(Socket s, String path, InputStream body, long unused) throws Exception {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = body.read(buf)) > 0) all.write(buf, 0, n);
        OutputStream o = s.getOutputStream();
        o.write(headers(path, all.size()).getBytes(StandardCharsets.UTF_8));
        all.writeTo(o);
        o.flush();
    }

    private String headers(String path, long length) {
        String type = "application/octet-stream";
        if (path.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (path.endsWith(".js")) type = "text/javascript; charset=utf-8";
        else if (path.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (path.endsWith(".json")) type = "application/json; charset=utf-8";
        else if (path.endsWith(".svg")) type = "image/svg+xml";
        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cross-Origin-Opener-Policy: same-origin\r\n"
                + "Cross-Origin-Embedder-Policy: require-corp\r\n"
                + "Cross-Origin-Resource-Policy: same-origin\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
    }
}
