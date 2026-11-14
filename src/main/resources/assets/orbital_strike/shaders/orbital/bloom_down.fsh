#version 150
// Bloom step 2: half-resolution downsample (5 bilinear taps).
// Inputs: Source, TexelSize (1 / Source resolution).
uniform sampler2D Source;
uniform vec2 TexelSize;

in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 sum = texture(Source, vUv).rgb * 4.0;
    sum += texture(Source, vUv + TexelSize * vec2(-1.0, -1.0)).rgb;
    sum += texture(Source, vUv + TexelSize * vec2(1.0, -1.0)).rgb;
    sum += texture(Source, vUv + TexelSize * vec2(-1.0, 1.0)).rgb;
    sum += texture(Source, vUv + TexelSize * vec2(1.0, 1.0)).rgb;
    fragColor = vec4(sum / 8.0, 1.0);
}
