#version 150
// Fullscreen triangle. Outputs: vUv in [0, 1], vNdc in [-1, 1].
in vec3 Position;

out vec2 vUv;
out vec2 vNdc;

void main() {
    vNdc = Position.xy;
    vUv = Position.xy * 0.5 + 0.5;
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
