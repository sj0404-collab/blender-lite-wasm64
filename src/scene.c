/*
 * blender-lite: a 3D viewport compiled to wasm64. Software rasterizer - no GL, no x86 emulation,
 * no Linux guest. The point is that it runs in a browser and does not crash, and that a test can
 * prove it drew something.
 */
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define W 320
#define H 240

static uint8_t pixels[W * H * 4];
static float depth[W * H];

typedef struct { float x, y, z; } V3;
typedef struct { float x, y, z; } P3;

int viewport_width(void) { return W; }
int viewport_height(void) { return H; }
uint8_t *viewport_pixels(void) { return pixels; }

/* 8 when this module was built with 64-bit memory, 4 otherwise. sizeof cannot lie. */
int pointer_bits(void) { return (int)(sizeof(void *) * 8); }

/* Allocate, touch and release: how much this build can actually hold at once. */
size_t probe_alloc(size_t bytes) {
    if (bytes == 0) return 0;
    uint8_t *block = (uint8_t *)malloc(bytes);
    if (!block) return 0;
    memset(block, 0x5a, bytes);
    size_t touched = block[bytes - 1];
    free(block);
    return touched == 0x5a ? bytes : 0;
}

static V3 rotate(V3 v, float yaw, float pitch) {
    float cy = cosf(yaw), sy = sinf(yaw), cp = cosf(pitch), sp = sinf(pitch);
    float x = v.x * cy + v.z * sy;
    float z = -v.x * sy + v.z * cy;
    float y = v.y * cp - z * sp;
    V3 out = {x, y, v.y * sp + z * cp};
    return out;
}

static P3 project(V3 v) {
    float d = v.z + 4.0f;   /* the camera sits at z = -4 and looks at the origin */
    if (d < 0.1f) d = 0.1f;
    P3 p = {(v.x * 2.2f / d) * (W * 0.5f) + W * 0.5f, (v.y * 2.2f / d) * (H * 0.5f) + H * 0.5f, d};
    return p;
}

static void triangle(P3 a, P3 b, P3 c, int r, int g, int bl) {
    float area = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
    if (area > -1e-4f && area < 1e-4f) return;
    int x0 = (int)fminf(a.x, fminf(b.x, c.x)), x1 = (int)fmaxf(a.x, fmaxf(b.x, c.x));
    int y0 = (int)fminf(a.y, fminf(b.y, c.y)), y1 = (int)fmaxf(a.y, fmaxf(b.y, c.y));
    if (x1 < 0 || y1 < 0 || x0 >= W || y0 >= H) return;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 >= W) x1 = W - 1;
    if (y1 >= H) y1 = H - 1;
    for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
            float px = x + 0.5f, py = y + 0.5f;
            float w0 = ((b.y - c.y) * (px - c.x) + (c.x - b.x) * (py - c.y)) / area;
            float w1 = ((c.y - a.y) * (px - c.x) + (a.x - c.x) * (py - c.y)) / area;
            float w2 = 1.0f - w0 - w1;
            if (w0 < 0.0f || w1 < 0.0f || w2 < 0.0f) continue;
            int i = y * W + x;
            float z = w0 * a.z + w1 * b.z + w2 * c.z;
            if (z >= depth[i]) continue;
            depth[i] = z;
            pixels[i * 4 + 0] = (uint8_t)r;
            pixels[i * 4 + 1] = (uint8_t)g;
            pixels[i * 4 + 2] = (uint8_t)bl;
            pixels[i * 4 + 3] = 255;
        }
    }
}

static const V3 CUBE[8] = {
    {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
    {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
};
static const int FACES[6][4] = {
    {0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {4, 5, 1, 0}, {3, 2, 6, 7},
};

/*
 * Draw one frame at a fixed pose, so a test renders the same picture twice. Returns how many pixels
 * the cube covers: zero means nothing was drawn, which is the failure this project cares about.
 */
int render(int step) {
    float yaw = step * 0.03f, pitch = 0.4f + 0.2f * sinf(step * 0.01f);
    for (int i = 0; i < W * H; i++) {
        depth[i] = 1e9f;
        pixels[i * 4 + 0] = 16; pixels[i * 4 + 1] = 18; pixels[i * 4 + 2] = 24; pixels[i * 4 + 3] = 255;
    }
    V3 v[8]; P3 p[8];
    for (int i = 0; i < 8; i++) { v[i] = rotate(CUBE[i], yaw, pitch); p[i] = project(v[i]); }
    V3 light = {0.4f, 0.8f, -0.6f};
    float ln = sqrtf(light.x * light.x + light.y * light.y + light.z * light.z);
    for (int f = 0; f < 6; f++) {
        int i0 = FACES[f][0], i1 = FACES[f][1], i2 = FACES[f][2], i3 = FACES[f][3];
        V3 u = {v[i1].x - v[i0].x, v[i1].y - v[i0].y, v[i1].z - v[i0].z};
        V3 w = {v[i2].x - v[i0].x, v[i2].y - v[i0].y, v[i2].z - v[i0].z};
        V3 n = {u.y * w.z - u.z * w.y, u.z * w.x - u.x * w.z, u.x * w.y - u.y * w.x};
        float nl = sqrtf(n.x * n.x + n.y * n.y + n.z * n.z);
        if (nl < 1e-6f) continue;
        float lit = (n.x * light.x + n.y * light.y + n.z * light.z) / (nl * ln);
        if (lit <= 0.0f) continue;   /* back face, or a face the light does not reach */
        int shade = (int)(60.0f + 195.0f * lit);
        int g = (int)(shade * 0.72f), b = (int)(shade * 0.45f);
        triangle(p[i0], p[i1], p[i2], shade, g, b);
        triangle(p[i0], p[i2], p[i3], shade, g, b);
    }
    int covered = 0;
    for (int i = 0; i < W * H; i++) if (depth[i] < 1e8f) covered++;
    return covered;
}
