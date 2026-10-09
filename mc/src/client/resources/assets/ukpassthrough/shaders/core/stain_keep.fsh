#version 330
#extension GL_ARB_separate_shader_objects : require

// How much of ULTRAKILL's blood (and gasoline) each pixel of Minecraft's frame takes (StainMask.compose, round 29d), into the red
// channel of a texture the stain pass reads (ultrakill_picture.fsh). The stain plane is ULTRAKILL's decals on its hidden blocks:
// on the ground, never on Minecraft's grass and flowers (ULTRAKILL doesn't have them). From three depth copies:
// - Sampler0: the solid terrain only (the ground);
// - Sampler1: with the cut-out terrain too (grass, flowers, crops, leaves, glass: textures with holes);
// - Sampler2: with everything (mobs, block entities, the item V1 holds).
// Where something stands in front of the terrain (2 differs from 1): none. On the ground itself (1 is 0): all. On a plant in front of
// the ground: as much as it stands close to the ground behind it, so a blade standing in a pool of blood is bloody and one far in
// front of a pool isn't (the user's "make it so the grass and flowers is able to get blood stains on it").
// 26.3's depth runs from 1 (near) to 0 (far) with its near plane 0.05 blocks out: a distance is about 0.05 / depth.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float NEAR = 0.05;
// a plant this far (blocks, along the view) in front of the ground behind it still takes all of the blood there, and none from twice as far
const float FULL = 1.5;
const float NONE = 3.0;

void main() {
    ivec2 at = ivec2(gl_FragCoord.xy);
    float ground = texelFetch(Sampler0, at, 0).r;
    float cutout = texelFetch(Sampler1, at, 0).r;
    float scene = texelFetch(Sampler2, at, 0).r;
    float keep;
    if (scene != cutout) {
        keep = 0.0;
    } else if (cutout == ground) {
        keep = 1.0;
    } else if (ground <= 0.0) {
        // a plant against the sky
        keep = 0.0;
    } else {
        float gap = NEAR / ground - NEAR / cutout;
        keep = 1.0 - smoothstep(FULL, NONE, gap);
    }

    fragColor = vec4(keep, 0.0, 0.0, 1.0);
}
