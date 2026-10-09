#version 330
#extension GL_ARB_separate_shader_objects : require

// ULTRAKILL's picture over Minecraft's frame (FrameOverlay.java), drawn two or three times:
// - with OIL_PASS, while there is any, the Firestarter's gasoline: Sampler0 is what ULTRAKILL multiplies a surface's colour
//   by there (white where there is none; alpha where there is some), multiplied into Minecraft's frame the same way as the
//   blood, and kept behind mobs the same way;
// - with STAIN_PASS, the blood stains: Sampler0 is the stain plane (how much blood, in red), the decals ULTRAKILL draws on
//   its hidden blocks (Layering.DrawStains in the ULTRAKILL plugin). They're multiplied into Minecraft's frame (the
//   pipeline's blend), so the blood darkens the block it lies on with that block's own lighting. Sampler1 (red) is how much
//   of it each pixel takes (stain_keep.fsh, StainMask.compose): none where something (a mob) stands in front of the block, all
//   on the block itself, and on grass or a flower in front of it as much as the plant stands close to it (round 29d).
//   Sampler2, the depth with everything solid, is only read by the mask debug view.
// - then the picture over that, with premultiplied alpha: solid things cover, glows (colour with alpha 0) add light.

// as in position_tex_color.fsh (a copy of dynamicimports.glsl)
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// what a full stain multiplies the block's colour by: lit stone becomes dark red, netherrack darker red
const vec3 BLOOD = vec3(0.45, 0.04, 0.035);
const float BLOOD_OPACITY = 0.9;
const float NONE = 0.5 / 255.0;

void main() {
#ifdef MASK_DEBUG
    // development (FrameOverlay.maskDebug): magenta where something stands in front of the terrain, else the terrain's
    // depth in red and everything's depth in green, stretched (near is 1, far 0; a block 3 away is about 0.02)
    ivec2 at = ivec2(gl_FragCoord.xy);
    float terrain = texelFetch(Sampler1, at, 0).r;
    float scene = texelFetch(Sampler2, at, 0).r;
    fragColor = scene != terrain ? vec4(1.0, 0.0, 1.0, 1.0) : vec4(sqrt(terrain * 20.0), sqrt(scene * 20.0), 0.0, 1.0);
    return;
#endif
#if defined(OIL_PASS)
    vec4 oil = texture(Sampler0, texCoord0);
    float keep = texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r;
    if (oil.a < NONE || keep < NONE) {
        discard;
    }

    fragColor = vec4(mix(vec3(1.0), oil.rgb, oil.a * keep), 1.0);
#elif defined(STAIN_PASS)
    float blood = texture(Sampler0, texCoord0).r;
    // the keep texture is the frame's own size, and this draws over the whole frame: the same pixel
    float keep = texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r;
    if (blood < NONE || keep < NONE) {
        discard;
    }

    fragColor = vec4(mix(vec3(1.0), BLOOD, blood * BLOOD_OPACITY * keep), 1.0);
#else
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    // nothing there (most of the screen)
    if (color.a < NONE && color.r + color.g + color.b < NONE) {
        discard;
    }

    fragColor = color * ColorModulator;
#endif
}
