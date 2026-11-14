#version 150
// Emissive element (ring coils, needle tip). Inputs: Color, Intensity, CamPos.
// Output exceeds 1.0 when powered, driving bloom.
// Core shifts toward white when viewed head-on.
uniform vec3 Color;
uniform float Intensity;
uniform vec3 CamPos;

in vec3 vWorldPos;
in vec3 vNormal;
out vec4 fragColor;

void main() {
    float facing = abs(dot(normalize(vNormal), normalize(CamPos - vWorldPos)));
    vec3 col = mix(Color, vec3(1.0), 0.45 * facing * facing * clamp(Intensity * 0.25, 0.0, 1.0));
    fragColor = vec4(col * Intensity * (0.75 + 0.25 * facing), 1.0);
}
