#version 150
// Bloom step 3: 3x3 tent-filter upsample, accumulated onto the upper level (additive blend).
// Inputs: Source (smaller mip level), TexelSize (1 / Source resolution).
uniform sampler2D Source;
uniform vec2 TexelSize;

in vec2 vUv;
out vec4 fragColor;

void main() {
    vec2 d = TexelSize;
    vec3 sum = texture(Source, vUv).rgb * 4.0;
    sum += (texture(Source, vUv + vec2(-d.x, 0.0)).rgb + texture(Source, vUv + vec2(d.x, 0.0)).rgb
          + texture(Source, vUv + vec2(0.0, -d.y)).rgb + texture(Source, vUv + vec2(0.0, d.y)).rgb) * 2.0;
    sum += texture(Source, vUv + vec2(-d.x, -d.y)).rgb + texture(Source, vUv + vec2(d.x, -d.y)).rgb
         + texture(Source, vUv + vec2(-d.x, d.y)).rgb + texture(Source, vUv + vec2(d.x, d.y)).rgb;
    fragColor = vec4(sum / 16.0, 1.0);
}
