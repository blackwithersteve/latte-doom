#version 330

// The sprite pass: DOOM's colormap model and nothing else. Here the Color
// channel is a real colour, the CPU-computed doomShade grey with gamma already
// included, so the fragment is a plain multiply. No lightmap and no entity
// diffuse: vanilla fullbright means the art exactly as authored, and the vanilla
// entity shader's extra terms dim sprites below that.
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

out vec4 vertexColor;
out vec2 texCoord;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord = UV0;
}
