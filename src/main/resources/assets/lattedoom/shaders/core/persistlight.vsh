#version 330

// The persistent-buffer lit pass. Same conventions as worldlight.vsh, except
// the Color channel carries the sector index (R = low byte, G = high byte) and
// the per-quad fake-contrast band in B. Light itself lives in a per-sector
// texture the fragment fetches, so geometry never rebakes for a light change.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;

out vec4 lightCode;
out vec2 texCoord;
out vec3 viewPos;

void main() {
    vec4 vp = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * vp;
    lightCode = Color;
    texCoord = UV0;
    viewPos = vp.xyz;
}
