#version 150
// HDR motion blur (speed sensation).
// Inputs: Source (HDR image), Center (vanishing point in uv), Strength (radial blur, 0 to 1),
// Direction (directional streak blur in uv; zero = none, used for panning).
// Radial: each pixel stretches toward the vanishing point, stronger near the edges.
// Directional: the entire image stretches along Direction.
uniform sampler2D Source;
uniform vec2 Center;
uniform float Strength;
uniform vec2 Direction;

in vec2 vUv;
out vec4 fragColor;

const int SAMPLES = 24;

void main() {
    vec2 toCenter = Center - vUv;
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float f = float(i) / float(SAMPLES - 1);
        float w = 1.0 - 0.6 * abs(f * 2.0 - 1.0) * step(0.0001, dot(Direction, Direction)) - 0.6 * f * (1.0 - step(0.0001, dot(Direction, Direction)));
        vec2 uv = vUv + toCenter * f * Strength * 0.22 + Direction * (f - 0.5);
        sum += texture(Source, clamp(uv, 0.0, 1.0)).rgb * w;
        total += w;
    }
    fragColor = vec4(sum / total, 1.0);
}
