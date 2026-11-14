#version 150
// Crepuscular rays: light from very bright sources (sun, coils) is stretched radially
// from the screen-space sun position. Planets and modules occlude the rays.
// Inputs: Source (HDR image), SunUv (sun position in uv, may be off-screen), Threshold.
uniform sampler2D Source;
uniform vec2 SunUv;
uniform float Threshold;

in vec2 vUv;
out vec4 fragColor;

const int SAMPLES = 72;
const float DENSITY = 0.94;
const float DECAY = 0.972;

void main() {
    vec2 delta = (vUv - SunUv) * DENSITY / float(SAMPLES);
    // Per-pixel random offset on the first sample to break up regular banding.
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    vec2 uv = vUv - delta * jitter;
    float illumination = 1.0;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < SAMPLES; i++) {
        uv -= delta;
        if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
            vec3 c = min(texture(Source, uv).rgb, vec3(30.0));
            float level = max(c.r, max(c.g, c.b));
            sum += c * smoothstep(Threshold, Threshold * 2.5, level) * illumination;
        }
        illumination *= DECAY;
    }
    fragColor = vec4(sum / float(SAMPLES), 1.0);
}
