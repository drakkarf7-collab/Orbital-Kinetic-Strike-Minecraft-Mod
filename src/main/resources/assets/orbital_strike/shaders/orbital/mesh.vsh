#version 150
// 3D mesh vertices (planets, ring modules, needle, ribbons).
// Inputs: Position, Normal, UV0. Uniforms: ModelMat (object -> world), ViewProjMat (world -> screen).
// Outputs: world-space position and normal, object-space position and normal, uv.
in vec3 Position;
in vec3 Normal;
in vec2 UV0;

uniform mat4 ModelMat;
uniform mat4 ViewProjMat;

out vec3 vWorldPos;
out vec3 vNormal;
out vec3 vObjNormal;
out vec3 vObjPos;
out vec2 vUv;

void main() {
    vec4 world = ModelMat * vec4(Position, 1.0);
    vWorldPos = world.xyz;
    vNormal = normalize(mat3(ModelMat) * Normal);
    vObjNormal = Normal;
    vObjPos = Position;
    vUv = UV0;
    gl_Position = ViewProjMat * world;
}
