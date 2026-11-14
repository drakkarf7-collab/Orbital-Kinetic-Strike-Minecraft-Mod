#version 150
// Atmosphere halo: shell slightly larger than the celestial body, rendered additively.
// Inputs: SunDir, CamPos, DayColor (Rayleigh scattering, lit side), DuskColor (terminator),
// Strength, Mie (forward scattering: rim glows when the sun is behind the body).
uniform vec3 SunDir;
uniform vec3 CamPos;
uniform vec3 DayColor;
uniform vec3 DuskColor;
uniform float Strength;
uniform float Mie;

in vec3 vWorldPos;
in vec3 vNormal;
out vec4 fragColor;

void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(CamPos - vWorldPos);
    float ndv = abs(dot(n, v));
    // Rises toward the edge, then falls off at the outer shell boundary for a soft rim.
    float rim = pow(1.0 - ndv, 3.0) * smoothstep(0.0, 0.18, ndv);
    float ndl = dot(n, SunDir);
    float lit = smoothstep(-0.25, 0.35, ndl);
    float dusk = exp(-pow(ndl / 0.22, 2.0));
    vec3 col = mix(DayColor, DuskColor, dusk * 0.8) * rim * lit * Strength;

    // Mie scattering: backlit rim remains visible even along the night-side limb.
    float forward = pow(max(dot(-v, SunDir), 0.0), 10.0);
    float thin = pow(1.0 - ndv, 5.0) * smoothstep(0.0, 0.10, ndv);
    col += mix(DuskColor, vec3(1.0, 0.92, 0.80), forward) * thin * forward * smoothstep(-0.45, 0.1, ndl) * Mie * 2.2;

    fragColor = vec4(col, 1.0);
}
