// Progress the wrapper does not show: which file is coming from the mirror, how many
// megabytes of it are on the device, and which phase the page is in afterwards.
// The service worker meters every payload byte and reports here; once the payload is
// cached, the line says so, and a minute later it says plainly that the remaining wait
// is the page's own unpacking and compiling - device work, not network.
(function () {
  var box = document.createElement('div');
  box.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:99999;pointer-events:none;' +
    'background:rgba(16,18,26,.94);color:#e8e8ee;font:12px/1.5 monospace;padding:8px 12px;' +
    'border-top:1px solid #2a2e3d;white-space:pre-wrap';
  document.documentElement.appendChild(box);
  var rows = {}, phase = 'starting', lastByte = 0, cached = {};
  function mb(n) { return (n / 1048576).toFixed(1); }
  function draw() {
    var out = phase;
    for (var k in rows) out += '\n' + k + '  ' + rows[k];
    var names = Object.keys(cached);
    if (names.length >= 3) out += '\npayload on device - offline ready';
    box.textContent = out;
  }
  function tick() {
    if (lastByte && Date.now() - lastByte > 60000 && phase.indexOf('unpack') < 0) {
      phase = 'payload received; Blender is unpacking/compiling in the page (no network now)';
      draw();
    }
  }
  setInterval(tick, 10000);
  addEventListener('message', function (e) {
    var d = e.data || {};
    if (d.kind === 'bytes') {
      rows[d.name] = mb(d.done) + '/' + mb(d.total) + ' MB' + (d.total ? ' ' + Math.round(100 * d.done / d.total) + '%' : '');
      if (!d.final) lastByte = Date.now();
    } else if (d.kind === 'cached') {
      cached[d.name] = 1;
      rows[d.name] = mb(d.size) + ' MB cached';
      phase = 'payload complete; unpacking starts';
      lastByte = Date.now();
    } else if (d.kind === 'cache-hit') {
      cached[d.name] = 1;
      rows[d.name] = 'from device cache';
      phase = 'payload from cache; unpacking starts';
      lastByte = Date.now();
    }
    draw();
  });
  draw();
})();
