"""Writes the pack manifest: size and sha256 of every part, plus the serve order.

Lives in a file rather than a heredoc inside the workflow because an indented
heredoc terminator never terminates, which is exactly how the first pack job died.
"""
import hashlib
import json

NAMES = ["blender.wasm.zst", "assets.tar.zst", "wgsl-cache.json"]

files = {}
for n in NAMES:
    h = hashlib.sha256()
    size = 0
    with open(n, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
            size += len(chunk)
    files[n] = {"size": size, "sha256": h.hexdigest()}
    print(n, size, h.hexdigest())

with open("manifest.json", "w") as out:
    json.dump({"files": files,
               "serve": {"blender.wasm.zst": ["blender.wasm.zst"]}},
              out, indent=1)
