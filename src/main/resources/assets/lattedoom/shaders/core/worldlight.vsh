#version 330

// The lit-world pass. Vertices arrive camera-relative in world axes: the pose
// carries translation only, and the view rotation lives in ModelViewMat. The
// Color channel is an encoding rather than a colour:
// R = sector light with the boost applied, G = fake-contrast band (0/.5/1 maps
// to -1/0/+1), B = fullbright flag, A = gamma exponent / 4.
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
    // pass the POSITION and take the distance per pixel: a per-vertex distance
    // interpolates wrongly across big triangles, and the 32-band quantization
    // turned those per-triangle disagreements into flickering dark triangles
    viewPos = vp.xyz;
}
