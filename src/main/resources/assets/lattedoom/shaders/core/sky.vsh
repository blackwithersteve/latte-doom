#version 330

// Latte Doom's sky pass. Vertices arrive camera-relative in world axes (the world
// submit's pose carries translation only; the view rotation lives in ModelViewMat),
// so the raw Position IS the view ray for this fragment — exactly what a
// direction-sampled sky needs, interpolated per pixel.
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

out vec3 skyDir;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#ifdef SKY_FAR
    // sky PLANES never occlude: true geometry (exact silhouette, exact per-pixel
    // rays), depth forced to the FAR plane. This renderer runs REVERSED depth —
    // DepthStencilState.DEFAULT is GREATER_THAN_OR_EQUAL, so far is depth ZERO,
    // not one; writing ~1 here painted the sky in front of the world instead of
    // behind it. Sky WALLS keep true depth — they are the original's sky clipping.
    gl_Position.z = gl_Position.w * 1.0e-6;
#endif
    skyDir = Position;
    vertexColor = Color;
}
