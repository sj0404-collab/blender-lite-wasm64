package dev.blenderlite.shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Fetches the Blender payload once, into the app's own files directory, from this project's own
 * GitHub release - a mirror the CI job builds by downloading the upstream payload and publishing
 * its sha256 sums. The original host is unreachable from some networks, which is the whole reason
 * this exists; the mirror also makes the download verifiable instead of taken on trust.
 *
 * After this returns, everything the page needs is on the device and PackServer serves it offline.
 */
public class PackFetch {
    static final String RELEASE =
            "https://api.github.com/repos/sj0404-collab/blender-lite-wasm64/releases/tags/blender-pack-v1";

    public interface Reporter { void say(String message); }

    /** Names to stream, in order, for a public name; null when the pack does not carry it. */
    public static String[] serveParts(File packDir, String name) {
        File manifest = new File(packDir, "manifest.json");
        try {
            JSONObject m = new JSONObject(new String(readAll(manifest), "UTF-8"));
            JSONObject serve = m.optJSONObject("serve");
            if (serve != null && serve.has(name)) {
                JSONArray a = serve.getJSONArray(name);
                String[] parts = new String[a.length()];
                for (int i = 0; i < a.length(); i++) parts[i] = a.getString(i);
                return parts;
            }
            if (m.optJSONObject("files") != null && m.getJSONObject("files").has(name)) {
                return new String[]{name};
            }
        } catch (Exception ignored) {
            // A missing or broken manifest means "not in pack", which the caller turns into a 404.
        }
        return null;
    }

    /** Downloads whatever is missing or mismatched; reports progress in megabytes. */
    public static void ensure(File packDir, Reporter reporter) throws Exception {
        packDir.mkdirs();
        JSONObject release = new JSONObject(new String(get(RELEASE), "UTF-8"));
        JSONObject urls = new JSONObject();
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            urls.put(a.getString("name"), a.getString("browser_download_url"));
        }
        if (!urls.has("manifest.json")) throw new IllegalStateException("mirror release has no manifest");
        File manifest = new File(packDir, "manifest.json");
        write(manifest, get(urls.getString("manifest.json")));
        JSONObject files = new JSONObject(new String(readAll(manifest), "UTF-8")).getJSONObject("files");
        for (String name : files.keys()) {
            JSONObject meta = files.getJSONObject(name);
            long size = meta.getLong("size");
            File dest = new File(packDir, name);
            if (dest.length() == size) continue;
            reporter.say("fetching " + name + " (" + (size >> 20) + " MB)");
            File tmp = new File(packDir, name + ".part");
            HttpURLConnection c = (HttpURLConnection) new URL(urls.getString(name)).openConnection();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                long nextSay = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    done += n;
                    if (done >= nextSay) {
                        reporter.say("fetching " + name + ": " + (done >> 20) + "/" + (size >> 20) + " MB");
                        nextSay = done + (16L << 20);
                    }
                }
            }
            String sum = hex(digest.digest());
            if (!sum.equalsIgnoreCase(meta.getString("sha256"))) {
                tmp.delete();
                throw new IllegalStateException("sha256 mismatch for " + name + ": " + sum);
            }
            if (!tmp.renameTo(dest)) throw new IllegalStateException("cannot install " + name);
            reporter.say("verified " + name);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "blender-lite-shell");
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] body) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body);
        }
    }
}
