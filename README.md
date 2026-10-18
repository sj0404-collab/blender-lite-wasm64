# blender-lite-wasm64

A 3D viewport compiled to **wasm64 (memory64)** that runs in a browser. No x86 emulation, no Linux
guest, no Wine: the program itself is compiled to WebAssembly, which is how DOSBox-wasm, ScummVM-wasm
and ffmpeg.wasm run in a browser, and how a web build of Blender would run.

This exists to answer one question with evidence rather than opinion: **how much of a GPU-facing
desktop application ports from Android to a browser tab**, and what stops where. It started as a step
of the DroidDecker Mali work (`sj0404-collab/DroidDecker`, PR #1), where the hybrid DXVK path needs a
Vulkan driver that a browser does not have.

## What is here

- `src/scene.c` - a software rasterizer: a lit, rotating cube with a z-buffer and backface culling,
  rendering into an RGBA buffer the page puts on a canvas. `render(step)` returns how many pixels the
  cube covers, so a test can assert that something was actually drawn instead of trusting that the
  module loaded.
- `pointer_bits()` reports 8 when the module was built with 64-bit memory and 4 otherwise - `sizeof`
  cannot lie, which makes the memory64 claim checkable rather than asserted.
- `probe_alloc(bytes)` allocates, touches and frees a block, so the ceiling of this build is measured
  and not assumed. A >4 GiB probe is deliberately **not** run in CI: runners have ~7 GB of RAM, and a
  5 GiB allocation there measures the runner's swap, not wasm64.

## What is deliberately not here

- **No x86/x64 CPU emulation.** Emulating x64 under wasm runs at a few percent of native speed and
  still needs a Windows or Linux userland that does not exist in a browser tab. Real Blender-in-browser
  work compiles Blender with Emscripten instead.
- **No DXVK, no Vulkan.** Browsers expose WebGL2 and WebGPU only, and WebGPU has no push constants
  (DXVK requires at least 256 bytes), no tessellation or geometry shaders and no transform feedback,
  so the DXVK path has no target to translate to. That is a finding, not a TODO.

## Next commit

`.github/workflows/build.yml`: install the Emscripten SDK, build `src/scene.c` with `-sMEMORY64=1`,
load the page in headless Chrome (SwiftShader, so no GPU is needed on the runner), assert that the
module reported 8-bit pointers, that `render()` covered pixels and that no wasm trap occurred, then
upload the **screenshot** as a workflow artifact.
