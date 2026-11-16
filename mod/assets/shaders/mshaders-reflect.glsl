// Shared by the water and the ice shader (pulled in with an #include line that ShaderMod.frag() expands).
// Reflections of the previous frame, looked up by world position, so a moving camera does not shift them.
uniform sampler2D u_objects; // mirror picture of the previous frame, premultiplied (mshaders-objects.frag), half screen size
uniform sampler2D u_glow;    // light the bullet and effect layers added in that frame (mshaders-glow.frag), half screen size
uniform float u_glowing;     // 1 when u_glow is bound
uniform vec4 u_objcam;       // camera rectangle of those pictures in world units: x, y of the lower-left corner, width, height
uniform vec2 u_mirrorDir;    // direction towards the sun on the ground, length 1: the mirror images fall away from it

const int STREAK_STEPS = 12;
const float STREAK_STEP = 3.2;   // light streaks reach 38 units, almost 5 tiles

// the previous frame at a world position; nothing outside that frame
vec4 objectAt(vec2 world){
    vec2 q = (world - u_objcam.xy) / u_objcam.zw;
    float in_frame = step(0.0, q.x) * step(q.x, 1.0) * step(0.0, q.y) * step(q.y, 1.0);
    return texture2D(u_objects, q) * in_frame;
}

vec3 glowAt(vec2 world){
    vec2 q = (world - u_objcam.xy) / u_objcam.zw;
    float in_frame = step(0.0, q.x) * step(q.x, 1.0) * step(0.0, q.y) * step(q.y, 1.0);
    return texture2D(u_glow, q).rgb * in_frame;
}

// color:   the surface colour so far
// ripple:  how far the surface bends what it shows, in world units (large on water, almost nothing on ice)
// shimmer: how the surface breaks up light, around 1 on average (glitter on water, even on ice)
// mirror:  strength of the mirror image of solid things; light: strength of the streaks of light
vec3 reflected(vec3 color, vec2 world, vec2 ripple, float shimmer, float mirror, float light){
    // 1. Things that stand up (buildings, units). u_objects already is the mirror picture - flipped, cut to the height
    // of the thing and faded (mshaders-objects.frag) - so here it is only laid over the surface, bent by the ripples.
    // The ripple only ever pushes the lookup away from the sun, the way the image falls: pushed towards the sun it
    // would reach under the block itself, where the picture is empty, and leave a gap along the foot of the image.
    if(mirror > 0.001){
        float towards = dot(ripple, u_mirrorDir);
        vec4 o = objectAt(world + ripple - u_mirrorDir * (towards + abs(towards)));
        float k = 0.9 * mirror;
        color = color * (1.0 - clamp(o.a * k, 0.0, 1.0)) + o.rgb * vec3(0.82, 0.90, 1.0) * k;
    }

    // 2. Light (shots, beams, explosions and their bloom) is not mirrored as a shape. It is smeared southwards into
    // a streak, the way a lamp shows on a wet or rippled surface.
    if(light > 0.001 && u_glowing > 0.5){
        // every pixel starts its samples at a slightly different height and spreads them sideways a little:
        // evenly spaced samples would show as horizontal bands and a hard-edged column
        float j1 = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
        float j2 = fract(sin(dot(gl_FragCoord.xy, vec2(39.3468, 11.1351))) * 24634.6345);
        vec3 streak = vec3(0.0);
        for(int i = 0; i < STREAK_STEPS; i++){
            float d = (float(i) + j1) * STREAK_STEP;
            float w = 1.0 - d / (float(STREAK_STEPS) * STREAK_STEP);
            streak += glowAt(world + vec2(ripple.x * (0.6 + d * 0.12) + (j2 - 0.5) * d * 0.22, d + ripple.y)) * max(w, 0.0);
        }
        // 1 - exp(-x): a small flash still shows, a big beam does not burn the surface white
        color += (vec3(1.0) - exp(-streak * 0.9)) * shimmer * 0.6 * light;
    }
    return color;
}
