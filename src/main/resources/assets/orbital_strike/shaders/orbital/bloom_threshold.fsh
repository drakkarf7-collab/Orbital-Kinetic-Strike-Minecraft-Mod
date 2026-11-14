#version 150
// Bloom step 1: half-resolution downsample keeping only light above threshold.
// Inputs: Source (HDR image), TexelSize (1 / Source resolution), Threshold.
uniform sampler2D Source;
uniform vec2 TexelSize;
uniform float Threshold;

in vec2 vUv;
out vec4 fragColor;

vec3 bright(vec2 uv) {
    // Clamp extreme values (sun) to avoid frame-to-frame flickering.
    vec3 c = min(texture(Source, uv).rgb, vec3(24.0));
    float level = max(c.r, max(c.g, c.b));
    float knee = Threshold * 0.5;
    float soft = clamp(level - Threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float keep = max(soft, level - Threshold) / max(level, 1e-4);
    return c * keep;
}

void main() {
    vec3 sum = bright(vUv + TexelSize * vec2(-1.0, -1.0)) + bright(vUv + TexelSize * vec2(1.0, -1.0))
             + bright(vUv + TexelSize * vec2(-1.0, 1.0)) + bright(vUv + TexelSize * vec2(1.0, 1.0));
    fragColor = vec4(sum * 0.25, 1.0);
}
