#version 150
// Plasma around the needle on an additively rendered surface of revolution.
// vUv.x: 0 at nose, 1 at tail. vUv.y: 0 to 1 around the axis.
// Inputs: HotColor (near nose), CoolColor (toward tail), Intensity, Time, CamPos,
// Mode (0 = turbulent sheath, 1 = shock cone made of scrolling rings).
uniform vec3 HotColor;
uniform vec3 CoolColor;
uniform float Intensity;
uniform float Time;
uniform vec3 CamPos;
uniform float Mode;

in vec3 vWorldPos;
in vec3 vNormal;
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

void main() {
    float facing = abs(dot(normalize(vNormal), normalize(CamPos - vWorldPos)));
    // A gas shell appears denser at grazing viewing angles.
    float edge = pow(1.0 - facing, 1.6);
    float along = vUv.x;
    float angle = vUv.y * 6.28318530718;
    vec2 around = vec2(cos(angle), sin(angle));

    if (Mode > 0.5) {
        // Shock cone: thin rings traveling away from the nose, fading along the length.
        float rings = pow(max(sin(along * 34.0 - Time * 26.0), 0.0), 10.0);
        float fade = (1.0 - along) * smoothstep(0.0, 0.06, along);
        float body = (0.10 + 0.9 * rings) * (0.30 + 0.70 * edge) * fade;
        fragColor = vec4(mix(CoolColor, HotColor, rings) * body * Intensity, 1.0);
        return;
    }

    // Sheath: turbulence scrolling rearward, streaked along the flow direction.
    float turb = fbm(vec3(along * 16.0 - Time * 11.0, around * 2.4));
    float streaks = fbm(vec3(along * 3.5 - Time * 17.0, around * 9.0));
    float flicker = 0.85 + 0.15 * sin(Time * 47.0 + along * 20.0);
    float fade = pow(1.0 - along, 1.7);
    float body = (0.22 + 0.78 * edge) * (0.30 + 1.0 * turb) * (0.45 + 0.9 * streaks) * fade * flicker;
    float heat = clamp(fade * fade * 1.5 + (turb - 0.5) * 0.5, 0.0, 1.0);
    fragColor = vec4(mix(CoolColor, HotColor, heat) * body * Intensity, 1.0);
}
