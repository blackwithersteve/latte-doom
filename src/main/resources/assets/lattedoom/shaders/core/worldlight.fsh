#version 330

// The software renderer's light chain, per pixel: sector light banded to 16
// lightnums, fake contrast on axis-aligned walls, and a colormap index from view
// distance with the DISTMAP falloff, floored to the 32-step ramp. The int()
// truncations are the authentic banding and must not be smoothed.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec4 lightCode;
in vec2 texCoord;
in vec3 viewPos;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord);
    if (tex.a < 0.1) {
        discard;
    }
    // per-pixel euclidean distance, camera-relative blocks -> DOOM units
    float z = max(length(viewPos), 0.03125) * 28.0;
#ifdef DEPTH_DEBUG
    // diagnostic: paint the distance the light chain actually sees. A sane
    // frame is a smooth near-to-far ramp; polygon-shaped patches or a
    // texture-like pattern mean the shader is reading the wrong varying.
    float d = clamp(z / 1600.0, 0.0, 1.0);
    fragColor = vec4(d, d, d, 1.0);
    return;
#endif
    // fake contrast rides the G channel, computed per quad on the CPU from the
    // wall's true axis (the derivative version flickered at grazing angles)
    int contrast = int(round(lightCode.g * 2.0)) - 1;
    // ROUND the light byte, never truncate: DOOM maps set light in multiples of
    // 16, interpolation returns the value +/- one ulp, and truncating 143.9999
    // to 143 dropped whole surfaces one band per TRIANGLE (weights differ per
    // triangle and with the view) — the dark flickering triangle sheets.
    // Fullbright was immune only because 255 sits inside band 15 either way.
    int lightnum = clamp((int(round(lightCode.r * 255.0)) >> 4) + contrast, 0, 15);
    int index = clamp((15 - lightnum) * 4 - int(1280.0 / z), 0, 31);
    float bright = 1.0 - float(index) / 32.0;
    // gamma exponent/4 rides the BLUE channel (alpha stays opaque — a sub-opaque
    // vertex alpha made the geometry collector restructure the mesh)
    float gamma = max(lightCode.b * 4.0, 0.05);
    fragColor = vec4(pow(tex.rgb * bright, vec3(gamma)), tex.a) * ColorModulator;
}
