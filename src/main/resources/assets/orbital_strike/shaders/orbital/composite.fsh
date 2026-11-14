#version 150
// Final screen composite. Inputs: Scene (HDR image, optionally supersampled),
// Bloom (glow), Streak (smaller bloom mip for anamorphic flares), Rays (god rays),
// BloomIntensity, StreakIntensity, RaysIntensity, Aberration, Exposure, Vignette,
// Grain, Time, Aspect, SkyBlue (0 to 1: blue sky seen from inside the atmosphere),
// Cloud (0 to 1: density of the traversed cloud layer), CloudScroll (scroll offset).
// Scene + bloom + streaks + rays, ascent sky and clouds, chromatic aberration,
// ACES tone mapping (Narkowicz approximation), vignette, film grain.
uniform sampler2D Scene;
uniform sampler2D Bloom;
uniform sampler2D Streak;
uniform sampler2D Rays;
uniform float BloomIntensity;
uniform float StreakIntensity;
uniform float RaysIntensity;
uniform float Aberration;
uniform float Exposure;
uniform float Vignette;
uniform float Grain;
uniform float Time;
uniform float Aspect;
uniform float SkyBlue;
uniform float Cloud;
uniform float CloudScroll;

in vec2 vUv;
out vec4 fragColor;

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i), n100 = hash13(i + vec3(1, 0, 0));
    float n010 = hash13(i + vec3(0, 1, 0)), n110 = hash13(i + vec3(1, 1, 0));
    float n001 = hash13(i + vec3(0, 0, 1)), n101 = hash13(i + vec3(1, 0, 1));
    float n011 = hash13(i + vec3(0, 1, 1)), n111 = hash13(i + vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float sum = 0.0, amp = 0.5;
    for (int i = 0; i < 5; i++) {
        sum += amp * noise3(p);
        p = p * 2.03 + 11.7;
        amp *= 0.5;
    }
    return sum / 0.96875;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    // Chromatic aberration: red and blue separate slightly toward the screen edges.
    vec2 fromCenter = vUv - 0.5;
    vec2 shift = fromCenter * dot(fromCenter, fromCenter) * Aberration;
    vec3 hdr = vec3(texture(Scene, vUv + shift).r, texture(Scene, vUv).g, texture(Scene, vUv - shift).b);

    hdr += texture(Bloom, vUv).rgb * BloomIntensity;
    if (RaysIntensity > 0.0) hdr += texture(Rays, vUv).rgb * RaysIntensity;

    // Anamorphic flares: stretch bloom horizontally and tint blue.
    if (StreakIntensity > 0.0) {
        vec3 streak = vec3(0.0);
        float total = 0.0;
        for (int i = -24; i <= 24; i++) {
            float w = exp(-float(i * i) / 190.0);
            streak += texture(Streak, vUv + vec2(float(i) * 0.0055, 0.0)).rgb * w;
            total += w;
        }
        hdr += streak / total * vec3(0.45, 0.70, 1.0) * StreakIntensity;
    }

    // Ascent: blue sky while inside the atmosphere, brighter near the horizon (bottom of frame).
    if (SkyBlue > 0.001) {
        vec3 sky = mix(vec3(0.62, 0.78, 1.0), vec3(0.16, 0.36, 0.86), smoothstep(0.0, 1.0, vUv.y));
        hdr = mix(hdr, sky, SkyBlue);
    }
    // Traversed cloud layer: two scales of cloud wisps scrolling rapidly downward.
    if (Cloud > 0.001) {
        vec2 p = vec2(fromCenter.x * Aspect, vUv.y);
        float bigPuffs = fbm(vec3(p * 2.2 + vec2(0.0, CloudScroll), CloudScroll * 0.21));
        float wisps = fbm(vec3(p * vec2(7.0, 3.0) + vec2(0.0, CloudScroll * 2.6), 4.0));
        float density = bigPuffs * 0.68 + wisps * 0.32;
        float cover = smoothstep(1.0 - Cloud * 1.15, 1.25 - Cloud * 1.15, density + Cloud * 0.35);
        vec3 cloudCol = mix(vec3(0.60, 0.68, 0.82), vec3(1.08, 1.06, 1.02), smoothstep(0.25, 0.85, wisps + bigPuffs * 0.4));
        hdr = mix(hdr, cloudCol, cover);
    }

    vec3 col = aces(hdr * Exposure);
    // Scene colors are already in display space: compensate for the contrast added by ACES.
    col = pow(col, vec3(0.82));

    float vig = smoothstep(1.05, 0.35, length(fromCenter) * 1.25);
    col *= mix(1.0, vig, Vignette);

    float noise = hash12(gl_FragCoord.xy + fract(Time) * 317.0) - 0.5;
    col += noise * Grain;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
