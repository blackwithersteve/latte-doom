#version 330

// DOOM's sky, sampled by view direction per pixel — the hardware-port model. The
// horizontal mapping is the original's: a 256-wide sky repeats four times per full
// turn (wider Boom-era skies repeat proportionally less, 1024 columns per turn
// either way). Vertically the texture's horizon row sits at eye level: vanilla put
// row 100 of 128 there, which generalizes to (height - 28) for tall skies; the top
// row caps the zenith the way GL ports smear it.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec3 skyDir;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec3 d = normalize(skyDir);
    vec2 ts = vec2(textureSize(Sampler0, 0));
    float u = fract(atan(d.x, -d.z) * 0.15915494 * (1024.0 / ts.x));
    float horizon = (ts.y - 28.0) / ts.y;
    float t = d.y / max(length(d.xz), 1.0e-4);
    float v = clamp(horizon - t * horizon, 0.001, 0.999);
    fragColor = texture(Sampler0, vec2(u, v)) * vertexColor * ColorModulator;
}
