#version 150
// Gas giant: photographic map (BandTex) enriched with procedural detail.
// Inputs: SunDir, CamPos, Time, and ring parameters (RingCenter, RingNormal, RingInner, RingOuter).
// Bands scrolling at latitude-dependent speeds, sheared boundary turbulence,
// rotating giant storm, white ovals, darker poles,
// analytically computed ring shadow.
uniform sampler2D BandTex;
uniform vec3 SunDir;
uniform vec3 CamPos;
uniform float Time;
uniform vec3 RingCenter;
uniform vec3 RingNormal;
uniform float RingInner;
uniform float RingOuter;

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


// Seamless equirectangular texture coordinates for a direction vector.
vec2 sphereUv(vec3 n) {
    float a = atan(n.z, n.x) / 6.28318530718;
    float uA = fract(a);
    float uB = fract(a + 0.5) - 0.5;
    float u = fwidth(uA) < fwidth(uB) ? uA : uB;
    float v = acos(clamp(n.y, -1.0, 1.0)) / 3.14159265359;
    return vec2(u, v);
}

const float PI = 3.14159265359;

// Wind speed by latitude: alternating zonal jets.
float jet(float lat) {
    return sin(lat * 13.0) * 0.6 + sin(lat * 29.0 + 0.7) * 0.4;
}

// Oval vortex centered at (lon0, lat0). Returns (mask, internal spiral value).
vec2 vortex(float lon, float lat, float lon0, float lat0, vec2 radius, float spin) {
    float dl = lon - lon0;
    dl = dl - 2.0 * PI * floor((dl + PI) / (2.0 * PI));
    vec2 q = vec2(dl * cos(lat0), lat - lat0) / radius;
    float d = length(q);
    if (d > 1.8) return vec2(0.0);
    float a = atan(q.y, q.x) + (1.8 - d) * spin + Time * 0.08 * sign(spin);
    float swirl = fbm(vec3(cos(a) * d * 2.6, sin(a) * d * 2.6, d * 3.0));
    float arms = 0.5 + 0.5 * sin(a * 2.0 + d * 9.0);
    return vec2(smoothstep(1.35, 0.75, d), mix(swirl, arms, 0.35));
}

void main() {
    vec3 p = normalize(vObjNormal);
    vec3 n = normalize(vNormal);
    vec3 v = normalize(CamPos - vWorldPos);

    float lat = asin(clamp(p.y, -1.0, 1.0));
    float lon = atan(p.z, p.x);
    float cl = cos(lat);

    // Each latitude rotates at its own speed: rotate the sample point around the axis.
    float flow = jet(lat);
    float lonA = lon + Time * 0.012 * flow;
    vec3 q = vec3(cos(lonA) * cl, p.y, sin(lonA) * cl);

    // Turbulence stretched along parallels across three scales.
    float large = fbm(q * vec3(3.0, 9.0, 3.0));
    float medium = fbm(q * vec3(9.0, 34.0, 9.0) + large * 2.5);
    float small = fbm(q * vec3(30.0, 110.0, 30.0) + medium * 2.0);
    // Shear is strongest where wind velocity changes rapidly.
    float shear = abs(cos(lat * 13.0)) * 0.6 + 0.4;
    float y = lat + (large - 0.5) * 0.050 + (medium - 0.5) * 0.030 * shear + (small - 0.5) * 0.008;

    float band = sin(y * 21.0) * 0.50 + sin(y * 47.0 + 1.3) * 0.28 + sin(y * 93.0 + 0.4) * 0.14
               + sin(y * 181.0 + 2.1) * 0.08;
    band = band * 0.5 + 0.5;

    // Color: photographic map of Jupiter (NASA/JPL, Cassini), rotating slowly as a whole.
    // Procedural detail only preserves crispness when the camera is very close.
    float spinLon = lon + Time * 0.004;
    vec3 col = texture(BandTex, sphereUv(vec3(cos(spinLon) * cl, p.y, sin(spinLon) * cl))).rgb;
    col = pow(col, vec3(1.15)) * 1.12;
    col *= 0.90 + 0.20 * small;
    col = mix(col, col * vec3(1.04, 0.98, 0.90), (medium - 0.5) * 0.6 + (band - 0.5) * 0.08);

    // Lighting: soft terminator, limb darkening.
    float ndl = dot(n, SunDir);
    float light = smoothstep(-0.06, 0.85, ndl);
    float ndv = max(dot(n, v), 0.0);
    col *= (0.008 + 0.92 * light) * (0.45 + 0.55 * pow(ndv, 0.6));

    // Ring shadow: ray toward the sun intersects the ring plane.
    if (RingOuter > 0.0) {
        float denom = dot(SunDir, RingNormal);
        if (abs(denom) > 1e-4) {
            float t = dot(RingCenter - vWorldPos, RingNormal) / denom;
            if (t > 0.0) {
                float d = length(vWorldPos + SunDir * t - RingCenter);
                float soft = (RingOuter - RingInner) * 0.5;
                float inRing = smoothstep(RingInner - soft, RingInner, d) * (1.0 - smoothstep(RingOuter, RingOuter + soft, d));
                col *= 1.0 - 0.7 * inRing;
            }
        }
    }

    // Thin golden limb haze on the lit side.
    col += vec3(1.0, 0.72, 0.42) * pow(1.0 - ndv, 4.0) * light * 0.22;

    fragColor = vec4(col, 1.0);
}
