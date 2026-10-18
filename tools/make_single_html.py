"""Assembles one self-contained HTML with every binary inside.

The wrapper needs no SharedArrayBuffer, no pthreads and no workers - checked by
grep before this was written - so it needs no COOP/COEP headers, no service
worker and no server: one file that runs from file:// offline and from any
http(s) origin online. The payload travels as base64 in inert script tags; a
fetch patch hands the decoded bytes to the wrapper under the names it asks
for, so not one byte of the wrapper is modified.
"""
import base64
import hashlib
import json
import os
import re
import sys

out = sys.argv[1] if len(sys.argv) > 1 else "blender-lite-single.html"
pay = sys.argv[2] if len(sys.argv) > 2 else "payload"
docs = sys.argv[3] if len(sys.argv) > 3 else "docs"

html = open(os.path.join(docs, "index.html")).read()
css_name = re.search(r'assets/([\w.-]+\.css)', html).group(1)
js_name = re.search(r'assets/([\w.-]+\.js)', html).group(1)
css = open(os.path.join(docs, "assets", css_name)).read()
js = open(os.path.join(docs, "assets", js_name)).read()
js = js.replace("</script", "<\\/script")

manifest = json.load(open(os.path.join(pay, "manifest.json")))
sizes = {"blender.wasm.zst": 145498307, "assets.tar.zst": 66672640}
sums = {n: manifest["files"][n]["sha256"] for n in manifest["files"]}

bins = []
for n in ["blender.wasm.zst", "assets.tar.zst", "wgsl-cache.json"]:
    raw = open(os.path.join(pay, n), "rb").read()
    assert hashlib.sha256(raw).hexdigest() == sums[n], n
    b64 = base64.b64encode(raw).decode()
    bins.append('<script type="text/plain" id="bin-%s">%s</script>' % (n, b64))
    print(n, len(raw), "->", len(b64))

boot = """
<script>
(function () {
  var SUMS = %s;
  var DECOMPRESSED = %s;
  var box = document.createElement('div');
  box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:99999;pointer-events:none;background:rgba(16,18,26,.94);color:#e8e8ee;font:12px/1.5 monospace;padding:8px 12px;border-top:1px solid #2a2e3d;white-space:pre-wrap';
  document.documentElement.appendChild(box);
  var lines = ['single file: all binaries embedded, online and offline'];
  var NL = String.fromCharCode(10);
  var full = document.createElement('div');
  full.style.display = 'none';
  document.documentElement.appendChild(full);
  var expanded = false;
  function draw() {
    full.textContent = lines.join(NL);
    box.textContent = expanded ? lines.join(NL) : lines[lines.length - 1] + '  [tap: ' + lines.length + ' lines]';
  }
  box.style.pointerEvents = 'auto';
  box.style.maxHeight = '38vh';
  box.style.overflow = 'auto';
  box.addEventListener('click', function () { expanded = !expanded; draw(); });
  function say(t) { lines.push(t); draw(); }
  draw();
  var blobs = {};
  async function decode(name) {
    var node = document.getElementById('bin-' + name);
    var b64 = node.textContent;
    node.textContent = '';
    say('decoding ' + name + ' (' + (b64.length * 3 / 4 / 1048576).toFixed(1) + ' MB)');
    var bin = atob(b64);
    var u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i += 65536) {
      var s = bin.slice(i, i + 65536);
      u8.set(Uint8Array.from(s, function (c) { return c.charCodeAt(0); }), i);
      if ((i & 4194303) === 0) await new Promise(function (r) { setTimeout(r, 0); });
    }
    return crypto.subtle.digest('SHA-256', u8).then(function (d) {
      var hex = Array.prototype.map.call(new Uint8Array(d), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
      if (hex !== SUMS[name]) throw new Error('sha256 mismatch for ' + name);
      say('verified ' + name + ' ' + (u8.length / 1048576).toFixed(1) + ' MB');
      blobs[name] = new Blob([u8], { type: 'application/octet-stream' });
      return blobs[name];
    });
  }
  var native = window.fetch;
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var name = url.split('/').pop().split('?')[0];
    if (SUMS[name] || name === 'wgsl-cache.json') {
      var p = blobs[name] ? Promise.resolve(blobs[name]) : decode(name);
      return p.then(function (b) {
        return new Response(b, { status: 200, headers: { 'Content-Length': String(b.size), 'Content-Type': name.endsWith('.json') ? 'application/json' : 'application/octet-stream' } });
      });
    }
    if (name === 'manifest.json') {
      return Promise.resolve(new Response(JSON.stringify(DECOMPRESSED), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    return native.apply(window, arguments);
  };
  addEventListener('error', function (e) { say('page error: ' + (e.message || e.type)); });
  addEventListener('unhandledrejection', function (e) { say('rejection: ' + ((e.reason && e.reason.message) || e.reason)); });
  var WI = WebAssembly.instantiate;
  var t0 = 0;
  var hb = -1;
  WebAssembly.instantiate = function () {
    var src = arguments[0];
    var mb = (src && src.byteLength) ? (src.byteLength / 1048576).toFixed(1) : '?';
    say('wasm instantiate start (' + mb + ' MB) - compiling is device work, minutes are normal');
    t0 = Date.now();
    return WI.apply(WebAssembly, arguments).then(function (r) {
      say('wasm instantiated in ' + ((Date.now() - t0) / 1000).toFixed(1) + ' s');
      return r;
    }, function (err) {
      say('wasm instantiate FAILED: ' + ((err && err.message) || err));
      throw err;
    });
  };
  var lastTick = Date.now();
  setInterval(function () {
    var now = Date.now();
    if (now - lastTick > 8000) say('main thread was busy ~' + Math.round((now - lastTick) / 1000) + ' s (sync decompress/index inside the wrapper)');
    lastTick = now;
    if (!t0) return;
    var line = 'waiting ' + Math.round((now - t0) / 1000) + ' s since wasm instantiate start';
    if (hb < 0) { hb = lines.length; lines.push(line); } else { lines[hb] = line; }
    draw();
  }, 5000);
  if (navigator.gpu) {
    var RA = navigator.gpu.requestAdapter.bind(navigator.gpu);
    navigator.gpu.requestAdapter = function () {
      say('gpu requestAdapter start');
      var t = Date.now();
      return RA().then(function (a) {
        say('gpu adapter ok in ' + ((Date.now() - t) / 1000).toFixed(1) + ' s');
        if (a) {
          var RD = a.requestDevice.bind(a);
          a.requestDevice = function () {
            say('gpu requestDevice start');
            var t2 = Date.now();
            return RD().then(function (d) {
              say('gpu device ok in ' + ((Date.now() - t2) / 1000).toFixed(1) + ' s');
              return d;
            }, function (e) { say('gpu device FAILED: ' + ((e && e.message) || e)); throw e; });
          };
        }
        return a;
      }, function (e) { say('gpu adapter FAILED: ' + ((e && e.message) || e)); throw e; });
    };
  }
  say('embedded payload: ' + Object.keys(SUMS).join(', '));
})();
</script>
""" % (json.dumps(sums), json.dumps(sizes))

html = re.sub(r'<script async src="https://plausible[^"]*"></script>', '', html)
html = re.sub(r'<script>\s*window\.plausible.*?</script>', '', html, flags=re.S)
html = html.replace('</head>', '<style>\n' + css + '\n</style>\n</head>')
html = re.sub(r'<link rel="stylesheet"[^>]*>', '', html)
html = html.replace('<link rel="manifest" href="manifest.webmanifest">', '')
html = re.sub(r'<meta name="theme-color"[^>]*>', '', html)
html = re.sub(r'<script src="progress\.js"[^>]*></script>', '', html)
html = re.sub(r'<script>\s*// First visit:.*?</script>', '', html, flags=re.S)
html = html.replace('<script type="module" src="./assets/' + js_name + '"></script>',
                    boot + '\n'.join(bins) + '\n<script type="module">\n' + js + '\n</script>')
html = re.sub(r'<script[^>]*src="\./assets/[^"]*"[^>]*></script>',
              boot + '\n'.join(bins) + '\n<script type="module">\n' + js + '\n</script>', html)
open(out, 'w').write(html)
print('wrote', out, os.path.getsize(out))
