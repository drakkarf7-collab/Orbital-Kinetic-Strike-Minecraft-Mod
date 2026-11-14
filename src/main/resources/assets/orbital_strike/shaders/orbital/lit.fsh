#version 150
// Lit matte metal (ring module bodies, needle).
// Inputs: SunDir, CamPos, Albedo, Glow (charge of neighboring emissive parts), GlowColor,
// PlanetCenter / PlanetRadius / PlanetColor (PlanetRadius = 0: no nearby planet).
// Art direction: large dark crisp surfaces, fine seams, and glowing trim lines along
// selected seams that brighten with charge. Cold specular reflection (GGX lobe),
// cast planet shadow, and planet bounce light.
uniform vec3 SunDir;
uniform vec3 CamPos;
uniform vec3 Albedo;
uniform float Glow;
uniform vec3 GlowColor;
uniform vec3 PlanetCenter;
uniform float PlanetRadius;
uniform vec3 PlanetColor;
// 1 = paneled metal (ring, needle); 0 = bumpy rock (debris).
uniform float Panels;

in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vObjPos;
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
    return mix(mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x), mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x), mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    return noise3(p) * 0.55 + noise3(p * 2.1 + 7.0) * 0.30 + noise3(p * 4.3 + 13.0) * 0.15;
}

void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(CamPos - vWorldPos);
    float rockMix = 1.0 - Panels;
    if (rockMix > 0.5) {
        // Rock: irregular relief derived from object-space noise.
        vec3 q = vObjPos * 2.3;
        float e = 0.08;
        float h0 = fbm(q);
        vec3 grad = vec3(fbm(q + vec3(e, 0.0, 0.0)) - h0, fbm(q + vec3(0.0, e, 0.0)) - h0, fbm(q + vec3(0.0, 0.0, e)) - h0) / e;
        n = normalize(n - grad * 0.45);
    }

    // Large panels: wide object-space grid. Only the seams are shaded.
    vec3 grid = vObjPos * 0.62 + 0.25;
    vec3 cell = floor(grid);
    vec3 edge = abs(fract(grid) - 0.5);
    float toSeam = 0.5 - max(edge.x, max(edge.y, edge.z));
    float seam = 1.0 - smoothstep(0.010, 0.022, toSeam);
    float tone = hash13(cell);
    float rough = mix(0.30, 0.55, hash13(cell + 17.0));
    seam *= Panels;
    vec3 albedo = Albedo * (0.85 + 0.30 * tone) * (1.0 - 0.75 * seam);
    albedo *= mix(1.0, 0.6 + 0.8 * fbm(vObjPos * 6.0), rockMix);

    // Emissive trim: one seam in three carries a light line, dim at rest and bright under charge.
    float accent = (1.0 - smoothstep(0.004, 0.012, toSeam)) * step(0.66, hash13(cell + 5.0)) * Panels;

    // Planet shadow: distance from the ray toward the sun to the planet center.
    float sunVisible = 1.0;
    vec3 planetLight = vec3(0.0);
    if (PlanetRadius > 0.0) {
        vec3 oc = PlanetCenter - vWorldPos;
        float along = dot(oc, SunDir);
        if (along > 0.0) {
            float miss = length(oc - SunDir * along);
            sunVisible = smoothstep(PlanetRadius * 0.985, PlanetRadius * 1.03, miss);
        }
        vec3 toPlanet = normalize(oc);
        float facing = max(dot(n, toPlanet), 0.0);
        float planetLit = clamp(dot(-toPlanet, SunDir) * 0.5 + 0.6, 0.0, 1.0);
        float nearness = clamp(PlanetRadius / length(oc), 0.0, 1.0);
        planetLight = PlanetColor * facing * planetLit * nearness * 0.55;
    }

    float ndl = max(dot(n, SunDir), 0.0) * sunVisible;
    float ndv = max(dot(n, v), 1e-3);
    vec3 h = normalize(SunDir + v);
    float ndh = max(dot(n, h), 0.0);
    float a = rough * rough;
    float d = ndh * ndh * (a * a - 1.0) + 1.0;
    float ggx = a * a / (3.14159265 * d * d);
    float fresnel = 0.55 + 0.45 * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    vec3 spec = vec3(0.82, 0.90, 1.0) * min(ggx, 40.0) * fresnel * ndl * 0.10 * (1.0 - seam);

    float rim = pow(1.0 - ndv, 5.0);
    vec3 col = albedo * (0.02 + 1.35 * ndl + planetLight);
    col += spec;
    col += (vec3(0.06, 0.09, 0.15) + planetLight * 0.5) * rim * 0.5;
    col += GlowColor * Glow * 0.03 * albedo;
    col += GlowColor * accent * (0.12 + 2.6 * Glow);
    fragColor = vec4(col, 1.0);
}
