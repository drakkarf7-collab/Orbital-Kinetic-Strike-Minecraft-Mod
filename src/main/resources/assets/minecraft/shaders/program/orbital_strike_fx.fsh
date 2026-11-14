#version 150

// Orbital Kinetic Strike post-processing shader (written for this mod).
// Desat/Vignette: pre-impact silence. Flash/Aberration: impact.
// Heat: ring distortion following the shockwave.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Desat;
uniform float Vignette;
uniform float Flash;
uniform float Aberration;
uniform float Heat;
uniform float HeatX;
uniform float HeatY;
uniform float HeatRadius;
uniform float Time;
uniform float Stylize;
uniform float Dusk;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    if (Heat > 0.001) {
        vec2 aspect = vec2(InSize.x / InSize.y, 1.0);
        vec2 d = (uv - vec2(HeatX, HeatY)) * aspect;
        float dist = length(d);
        float ring = exp(-pow((dist - HeatRadius) * 14.0, 2.0));
        vec2 dir = dist > 0.0001 ? d / dist : vec2(0.0);
        uv += dir / aspect * ring * 0.018 * Heat;
        uv += vec2(sin(uv.y * 90.0 + Time * 7.0), cos(uv.x * 80.0 + Time * 6.0)) * 0.0012 * Heat;
    }
    vec3 col;
    if (Aberration > 0.001) {
        vec2 off = (uv - 0.5) * 0.014 * Aberration;
        col = vec3(texture(DiffuseSampler, uv + off).r, texture(DiffuseSampler, uv).g, texture(DiffuseSampler, uv - off).b);
    } else {
        col = texture(DiffuseSampler, uv).rgb;
    }
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    // Silence: the world loses color, darkens slightly, and gains contrast.
    vec3 grey = vec3(luma) * vec3(0.96, 0.98, 1.04);
    grey = clamp((grey - 0.5) * 1.25 + 0.46, 0.0, 1.0);
    col = mix(col, grey, clamp(Desat, 0.0, 1.0));
    float vig = smoothstep(0.80, 0.18, length(texCoord - 0.5) * 1.30);
    col *= mix(1.0, vig, clamp(Vignette, 0.0, 1.0));
    if (Stylize > 0.001) {
        // 3-stage stylized impact (Blue, Black & White): ice-white/sapphire overexposure + glitch, blue/black halftone + lightning bolt, then horizontal tears.
        float tStep = floor(Time * 18.0);
        float blockY = floor(texCoord.y * 42.0);
        float blockX = floor(texCoord.x * 22.0);
        float rowHash = fract(sin(blockY * 91.7 + tStep * 13.1) * 43758.5453);
        float cellHash = fract(sin(dot(vec2(blockX, blockY), vec2(127.1, 311.7)) + tStep * 19.3) * 43758.5453);
        float tearStrength = step(0.78, rowHash) * (smoothstep(0.74, 0.98, Stylize) + smoothstep(0.30, 0.04, Stylize));
        vec2 guv = vec2(clamp(texCoord.x + (rowHash - 0.89) * 0.36 * tearStrength, 0.0, 1.0), texCoord.y);

        vec3 src = texture(DiffuseSampler, guv).rgb;
        float l = dot(src, vec3(0.299, 0.587, 0.114));
        vec2 px = 1.0 / max(InSize, vec2(1.0));
        float ll = dot(texture(DiffuseSampler, guv - vec2(px.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float lr = dot(texture(DiffuseSampler, guv + vec2(px.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float ld = dot(texture(DiffuseSampler, guv - vec2(0.0, px.y)).rgb, vec3(0.299, 0.587, 0.114));
        float lu = dot(texture(DiffuseSampler, guv + vec2(0.0, px.y)).rgb, vec3(0.299, 0.587, 0.114));
        float edge = clamp((abs(lr - ll) + abs(lu - ld)) * 6.8, 0.0, 1.0);

        vec3 voidBlack = vec3(0.01, 0.015, 0.035);
        vec3 coldWhite = vec3(0.96, 0.99, 1.0);
        vec3 sapphire = vec3(0.06, 0.44, 1.0);
        vec3 cobalt = vec3(0.04, 0.20, 0.82);
        vec3 iceCyan = vec3(0.36, 0.90, 1.0);
        float scan = 0.95 + 0.05 * sin(gl_FragCoord.y * 1.5);

        // Stage 1 (Stylize > 0.76): cold-white overexposure, black/cobalt ink silhouettes, and cyan/white glitch.
        vec3 stage1 = mix(cobalt, coldWhite, smoothstep(0.26, 0.56, l));
        stage1 = mix(voidBlack, stage1, smoothstep(0.12, 0.28, l));
        stage1 = mix(stage1, coldWhite, edge * 0.78);
        float gMask1 = step(0.91, cellHash);
        vec3 gCol1 = mix(iceCyan, coldWhite, step(0.955, cellHash));
        stage1 = mix(stage1, gCol1, gMask1 * 0.90);

        // Stage 2 (0.24 .. 0.80): void black + electric sapphire blue + 45-degree halftone screen + diagonal bolt.
        vec2 pRot = mat2(0.7071, -0.7071, 0.7071, 0.7071) * (gl_FragCoord.xy / 5.2);
        float htDist = length(fract(pRot) - 0.5);
        float htRadius = 0.55 * clamp(l * 1.30, 0.0, 1.0);
        float htMask = step(htDist, htRadius);
        vec3 stage2 = mix(voidBlack, sapphire, htMask);
        stage2 = mix(stage2, coldWhite, smoothstep(0.80, 0.95, l));
        stage2 = mix(stage2, mix(iceCyan, coldWhite, smoothstep(0.42, 0.88, l)), clamp(edge * 0.96, 0.0, 1.0));

        float boltWindow = smoothstep(0.40, 0.52, Stylize) * (1.0 - smoothstep(0.74, 0.84, Stylize));
        float jag = sin(texCoord.y * 38.0 + tStep * 0.5) * 0.022 + sin(texCoord.y * 117.0) * 0.009;
        float diag = (texCoord.x - 0.14) * 0.88 - (0.90 - texCoord.y) * 1.05 + jag;
        float boltSpan = smoothstep(0.12, 0.25, texCoord.y) * (1.0 - smoothstep(0.72, 0.94, texCoord.y));
        float boltCore = smoothstep(0.012, 0.002, abs(diag)) * boltSpan * boltWindow;
        float boltHalo = smoothstep(0.046, 0.006, abs(diag)) * boltSpan * boltWindow;
        stage2 = mix(stage2, iceCyan, boltHalo * 0.92);
        stage2 = mix(stage2, coldWhite, boltCore);

        // Stage 3 (< 0.26): sapphire/cyan/white horizontal tears and transition back to the darkened world.
        vec3 tearBase = mix(col * vec3(0.20, 0.28, 0.46), sapphire, clamp(edge * 0.85 + smoothstep(0.62, 0.94, l), 0.0, 1.0));
        float gMask3 = step(0.88, cellHash) * step(0.35, tearStrength + 0.35);
        vec3 gCol3 = mix(iceCyan, mix(sapphire, coldWhite, step(0.95, cellHash)), step(0.92, cellHash));
        vec3 stage3 = mix(tearBase, gCol3, gMask3 * 0.84);

        float w1 = smoothstep(0.74, 0.86, Stylize);
        float w3 = 1.0 - smoothstep(0.18, 0.30, Stylize);
        vec3 duo = mix(stage2, stage1, w1);
        duo = clamp(mix(duo, stage3, w3) * scan, 0.0, 1.0);
        float opacity = smoothstep(0.02, 0.22, Stylize);
        col = mix(col, duo, opacity);
    }
    if (Dusk > 0.001) {
        // Post-impact: darkened sky, cyan/white and incandescent glows stay bright.
        float glowLuma = clamp((col.b - col.r * 0.6) * 2.2 + smoothstep(0.72, 0.98, luma), 0.0, 1.0);
        vec3 dark = col * vec3(0.38, 0.44, 0.60);
        col = mix(col, mix(dark, col * 1.12, glowLuma), clamp(Dusk, 0.0, 1.0));
    }
    col = mix(col, vec3(1.0), clamp(Flash, 0.0, 1.0));
    fragColor = vec4(col, 1.0);
}
