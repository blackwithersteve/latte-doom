#version 330

// Sprite fragment: texture times the doomShade grey, with cutout alpha. The
// shade byte already carries the whole software light chain (banding, distance,
// gamma and the fullbright short-circuit) from LatteMesh.doomShade on the CPU.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord);
    if (tex.a < 0.1) {
        discard;
    }
    fragColor = vec4(tex.rgb * vertexColor.rgb, tex.a) * ColorModulator;
}
