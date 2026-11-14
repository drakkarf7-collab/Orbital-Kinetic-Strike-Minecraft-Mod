#version 150
// Per-pixel space sky: deep midnight-blue backdrop, crisp Milky Way,
// four flicker-free star layers, and point Sun with diffraction spikes.
uniform vec3 CamRight;
uniform vec3 CamUp;
uniform vec3 CamForward;
uniform float TanHalfFov;
uniform float Aspect;
uniform vec3 SunDir;
uniform float StarAlpha;
uniform float Time;
uniform sampler2D MilkyTex;

in vec2 vNdc;
out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

// Seamless equirectangular texture coordinates without derivative discontinuities.
vec2 sphereUv(vec3 n) {
    float a = atan(n.z, n.x) / 6.28318530718;
    float uA = fract(a);
    float uB = fract(a + 0.5) - 0.5;
    float u = fwidth(uA) < fwidth(uB) ? uA : uB;
    float v = acos(clamp(n.y, -1.0, 1.0)) / 3.14159265359;
    return vec2(u, v);
}

// Anti-aliased star layer with a per-pixel minimum radius to prevent shimmer in motion.
vec3 starLayer(vec3 dir, float scale, float density, float sizeMul, float dirPx) {
    vec3 p = dir * scale;
    vec3 cell = floor(p);
    float h = hash13(cell);
    if (h > density) return vec3(0.0);
    vec3 offset = vec3(hash13(cell + 7.1), hash13(cell + 13.7), hash13(cell + 23.3)) * 0.56 + 0.22;
    float d = length(fract(p) - offset);
    float mag = hash13(cell + 5.5);
    float brightness = 0.40 + 4.8 * pow(mag, 7.0);
    float size = (0.044 + 0.062 * mag) * sizeMul;
    float renderSize = max(size, dirPx * scale * 1.35);
    float aa = clamp((size * size) / (renderSize * renderSize) * 1.15, 0.16, 1.0);
    float core = smoothstep(renderSize, 0.0, d);
    float twinkle = 0.98 + 0.02 * sin(Time * 0.35 + mag * 35.0 + h * 19.0);
    float temp = hash13(cell + 41.0);
    vec3 tint = temp < 0.45 ? mix(vec3(0.84, 0.92, 1.0), vec3(0.97, 0.99, 1.0), temp / 0.45)
                            : mix(vec3(0.97, 0.99, 1.0), vec3(0.56, 0.84, 1.0), (temp - 0.45) / 0.55);
    return tint * core * core * twinkle * brightness * aa;
}

void main() {
    vec3 dir = normalize(CamForward + vNdc.x * Aspect * TanHalfFov * CamRight + vNdc.y * TanHalfFov * CamUp);
    float dirPx = max(length(fwidth(dir)), 0.0012);

    // Very dark deep midnight-blue for crisp contrast behind the ring and planets.
    vec3 col = vec3(0.002, 0.004, 0.013);

    // Cool-tinted Milky Way (blue/cyan) with boosted contrast to stay crisp without brown haze.
    vec3 skyDir = vec3(dir.x, dir.y * 0.829 - dir.z * 0.559, dir.y * 0.559 + dir.z * 0.829);
    vec2 uv = sphereUv(skyDir);
    vec3 photoSoft = texture(MilkyTex, uv, 1.25).rgb;
    vec3 photoNebula = texture(MilkyTex, uv, 2.35).rgb;
    vec3 photo = mix(photoSoft, photoNebula, 0.28);
    photo = pow(max(photo, vec3(0.0)), vec3(1.85)) * 0.42;
    photo *= vec3(0.56, 0.80, 1.22);

    vec3 stars = starLayer(dir, 58.0, 0.060, 1.35, dirPx)
               + starLayer(dir, 130.0, 0.095, 1.10, dirPx)
               + starLayer(dir, 250.0, 0.090, 0.90, dirPx) * 0.55
               + starLayer(dir, 460.0, 0.095, 0.85, dirPx) * 0.24;

    col += (photo + stars * 0.42) * StarAlpha;

    // Ice-white point Sun with fine 8-point diffraction spikes.
    float s = max(dot(dir, SunDir), 0.0);
    if (s > 0.985) {
        vec3 su = abs(SunDir.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 sr = normalize(cross(su, SunDir));
        vec3 sup = cross(SunDir, sr);
        float px = dot(dir, sr);
        float py = dot(dir, sup);
        float p1 = abs(px), p2 = abs(py);
        float d1 = abs(px + py) * 0.7071, d2 = abs(px - py) * 0.7071;
        float angMask = smoothstep(0.994, 0.9999, s);
        float spike = (exp(-p1 * 760.0) * smoothstep(0.052, 0.0, p2)
                     + exp(-p2 * 760.0) * smoothstep(0.052, 0.0, p1)) * 1.15
                    + (exp(-d1 * 900.0) * smoothstep(0.034, 0.0, d2)
                     + exp(-d2 * 900.0) * smoothstep(0.034, 0.0, d1)) * 0.55;
        float core = smoothstep(0.99984, 0.99996, s) * 14.0 + pow(s, 3600.0) * 1.8;
        col += vec3(0.94, 0.98, 1.0) * (core + spike * angMask * 1.4);
    }

    fragColor = vec4(col, 1.0);
}
