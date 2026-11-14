#version 150
// Earth. Inputs: DayTex (RGB = day albedo, A = ocean mask), NightTex (city lights),
// CloudTex (R = cloud cover), SunDir (world, normalized), CamPos, Time, ModelMat.
// Shaded relief (normal perturbed by a procedural height field), detailed clouds with ground
// shadows, sun and sky specular reflection on oceans, night-side city lights,
// blue atmospheric scattering toward the limb and orange along the terminator.
uniform sampler2D DayTex;
uniform sampler2D NightTex;
uniform sampler2D CloudTex;
uniform vec3 SunDir;
uniform vec3 CamPos;
uniform float Time;
uniform mat4 ModelMat;

in vec3 vWorldPos;
in vec3 vNormal;
in vec3 vObjNormal;
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

// Texture coordinates for a point on the sphere (equirectangular projection).
// u is computed two ways shifted by half a turn; keeping the smaller derivative avoids
// the seam line where u wraps from 1 back to 0.
vec2 sphereUv(vec3 n) {
    float a = atan(n.z, n.x) / 6.28318530718;
    float uA = fract(a);
    float uB = fract(a + 0.5) - 0.5;
    float u = fwidth(uA) < fwidth(uB) ? uA : uB;
    float v = acos(clamp(n.y, -1.0, 1.0)) / 3.14159265359;
    return vec2(u, v);
}

// Procedural relief: mountain ridges modulated by broad regions.
float terrain(vec3 p) {
    float ridged = 1.0 - abs(2.0 * fbm(p * 26.0) - 1.0);
    float regions = fbm(p * 5.0 + 9.0);
    return ridged * ridged * smoothstep(0.35, 0.75, regions) + 0.25 * fbm(p * 60.0);
}

float cloudAt(vec3 objN, vec2 drift) {
    vec2 uv = sphereUv(objN) + drift;
    float c = texture(CloudTex, uv).r;
    // Fine noise breaks up cloud mass edges.
    float wisps = fbm(objN * 34.0 + vec3(drift.x * 40.0, 0.0, 0.0));
    float puffs = fbm(objN * 90.0 + 5.0);
    c = smoothstep(0.10, 0.75, c * (0.55 + 0.9 * wisps) + 0.18 * (puffs - 0.5) * c);
    // Projection pinches at the poles: fade out clouds there (polar caps replace them).
    return c * smoothstep(0.03, 0.15, uv.y) * (1.0 - smoothstep(0.85, 0.97, uv.y));
}

void main() {
    vec3 objN = normalize(vObjNormal);
    vec3 n = normalize(vNormal);
    vec3 v = normalize(CamPos - vWorldPos);
    vec2 uv = sphereUv(objN);
    mat3 rot = mat3(ModelMat);
    // Sun direction transformed into object space (ModelMat is rotation times uniform scale).
    vec3 sunObj = normalize(transpose(rot) * SunDir);

    vec4 surface = texture(DayTex, uv);
    float ocean = surface.a;
    float land = 1.0 - ocean;
    // Polar ice: detected from whiteness in the texture and latitude. No bump relief under ice.
    float ice = smoothstep(0.78, 0.86, min(surface.r, min(surface.g, surface.b))) * smoothstep(0.62, 0.74, abs(objN.y));
    float rock = land * (1.0 - ice);

    // Relief-perturbed normal, on land only.
    vec3 t1 = normalize(cross(objN, vec3(0.0, 1.0, 0.0) + objN.zxy * 0.001));
    vec3 t2 = cross(objN, t1);
    float e = 0.004;
    float h0 = terrain(objN);
    float h1 = terrain(normalize(objN + t1 * e));
    float h2 = terrain(normalize(objN + t2 * e));
    vec3 bumpObj = normalize(objN - (t1 * (h1 - h0) + t2 * (h2 - h0)) / e * 0.006 * rock);
    vec3 nb = normalize(rot * bumpObj);

    float ndl = dot(n, SunDir);
    float day = smoothstep(-0.10, 0.20, ndl);
    float sunlight = max(dot(nb, SunDir), 0.0) * smoothstep(-0.05, 0.10, ndl);

    // Clouds and their shadow: same cloud layer sampled slightly offset toward the sun.
    vec2 drift = vec2(Time * 0.0035, 0.0);
    float cloud = cloudAt(objN, drift);
    float shadow = cloudAt(normalize(objN + sunObj * 0.014), drift);
    float cloudTowardSun = cloudAt(normalize(objN + sunObj * 0.006), drift);

    // Ground: texture color modulated by relief.
    vec3 albedo = surface.rgb;
    // Satellite texture is fairly dark: lift gamma slightly with fine surface grain.
    albedo = pow(albedo, vec3(0.88)) * 1.30;
    albedo *= 0.95 + 0.10 * fbm(objN * 140.0) * (1.0 - ice);
    albedo = mix(albedo, vec3(0.96, 0.98, 1.0) * (0.94 + 0.06 * fbm(objN * 30.0)), ice);
    vec3 col = albedo * (0.012 + 1.20 * sunlight) * (1.0 - 0.55 * shadow * day);

    // Ocean: sun glint (tight highlight + broad sheen) and sky reflection near the limb.
    vec3 hv = normalize(SunDir + v);
    float ndh = max(dot(n, hv), 0.0);
    float glint = pow(ndh, 700.0) * 1.1 + pow(ndh, 60.0) * 0.05;
    float fres = pow(1.0 - max(dot(n, v), 0.0), 4.0);
    col += vec3(1.0, 0.93, 0.80) * glint * max(ocean, ice * 0.5) * (1.0 - cloud) * (1.0 - 0.8 * shadow) * day;
    // Ice reflects sunlight as a broad sheen.
    col += vec3(0.90, 0.95, 1.0) * pow(ndh, 24.0) * ice * 0.35 * (1.0 - cloud) * day;
    col += vec3(0.30, 0.50, 0.85) * fres * ocean * day * 0.35 * max(ndl, 0.0);

    // Clouds: sunlit, darker where thick toward the sun, pink-orange at the terminator.
    vec3 cloudTint = mix(vec3(1.0, 0.50, 0.30), vec3(1.0), smoothstep(0.0, 0.30, ndl));
    float cloudLight = (0.02 + 1.30 * max(ndl, 0.0)) * (1.0 - 0.40 * cloudTowardSun);
    col = mix(col, cloudTint * cloudLight, cloud * 0.96);

    // City lights: strictly on the night side, fine shimmer, occluded by clouds.
    float night = 1.0 - smoothstep(-0.16, -0.02, ndl);
    vec3 lights = texture(NightTex, uv).rgb * (0.6 + 0.8 * noise3(objN * 400.0));
    col += lights * night * (1.0 - 0.8 * cloud) * 4.5;
    // Soft glow halo around major metropolitan areas.
    col += textureLod(NightTex, uv, 3.0).rgb * night * (1.0 - 0.8 * cloud) * 1.6;

    // Atmospheric scattering seen from above: blue veil thickening toward the limb,
    // orange along the terminator.
    float rim = pow(1.0 - max(dot(n, v), 0.0), 2.5);
    float dusk = exp(-pow(ndl / 0.16, 2.0));
    vec3 haze = mix(vec3(0.22, 0.48, 1.0), vec3(1.0, 0.42, 0.14), dusk * 0.85);
    col = mix(col, haze * (0.04 + 0.85 * smoothstep(-0.12, 0.35, ndl)), clamp(rim * 0.55 + 0.03, 0.0, 1.0) * day);

    fragColor = vec4(col, 1.0);
}
