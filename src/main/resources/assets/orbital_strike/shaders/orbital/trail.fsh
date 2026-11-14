#version 150
// Emissive ribbon (needle trail, reentry plasma), rendered additively.
// Inputs: Color, Intensity, Time. vUv.x: 0 at head, 1 at tail. vUv.y: 0 to 1 across width.
// White core, colored edges, intensity fades toward the tail, subtle flicker.
uniform vec3 Color;
uniform float Intensity;
uniform float Time;

in vec2 vUv;
out vec4 fragColor;

void main() {
    float across = 1.0 - abs(vUv.y * 2.0 - 1.0);
    float along = 1.0 - vUv.x;
    float body = pow(across, 1.5) * along * along;
    float core = pow(across, 6.0) * along;
    float flicker = 0.88 + 0.12 * sin(vUv.x * 70.0 - Time * 30.0);
    vec3 col = (Color * body + mix(Color, vec3(1.0), 0.6) * core * 0.5) * Intensity * flicker;
    fragColor = vec4(col, 1.0);
}
