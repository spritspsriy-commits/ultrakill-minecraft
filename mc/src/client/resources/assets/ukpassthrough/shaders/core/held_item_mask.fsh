#version 330
#extension GL_ARB_separate_shader_objects : require

// The item V1 holds, as Minecraft drew it (StainMask.heldItemDrawn): InSampler is the depth it was drawn against, cleared
// to 0 (far) first, so anything above 0 is the item. There its depth goes into the stain mask's copy with the mobs; every
// other pixel is left as it is.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

void main() {
    float depth = texture(InSampler, texCoord).r;
    if (depth <= 0.0) {
        discard;
    }

    gl_FragDepth = depth;
}
