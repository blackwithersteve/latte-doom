#version 330

// The software light chain against static geometry: the sector index rides the
// vertex colour and the live light rides Sampler2, an Nx2 texture whose row 0 is
// light with the boost folded in and whose row 1 pixel 0 is the gamma exponent
// byte. Four rules are load-bearing here: round() every byte read, or ulp
// truncation splits triangles; measure distance per pixel and euclidean; keep
// alpha opaque; and never multiply ColorModulator with encoded data.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

in vec4 lightCode;
in vec2 texCoord;
in vec3 viewPos;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord);
    if (tex.a < 0.1) {
        discard;
    }
    float z = max(length(viewPos), 0.03125) * 28.0;
    int sector = int(round(lightCode.r * 255.0)) + 256 * int(round(lightCode.g * 255.0));
    float lightVal = texelFetch(Sampler2, ivec2(sector, 0), 0).r;
    int contrast = int(round(lightCode.b * 2.0)) - 1;
    int lightnum = clamp((int(round(lightVal * 255.0)) >> 4) + contrast, 0, 15);
    int index = clamp((15 - lightnum) * 4 - int(1280.0 / z), 0, 31);
    float bright = 1.0 - float(index) / 32.0;
    float gamma = max(texelFetch(Sampler2, ivec2(0, 1), 0).r * 4.0, 0.05);
    fragColor = vec4(pow(tex.rgb * bright, vec3(gamma)), tex.a);
}
