#define HIGHP

// Replacement for the built-in shaders/water.frag (same inputs, drawn by CacheLayer.ShaderLayer).
// u_texture: the effect buffer holding only the liquid floor tiles of this layer (alpha 0 elsewhere).
// u_noise:   sprites/noise.png, 128x128, repeating, linear filtered; one blob is about 1/25 of the texture.
uniform sampler2D u_texture;
uniform sampler2D u_noise;

uniform vec2 u_campos;      // world position of the lower-left screen corner
uniform vec2 u_resolution;  // camera size in world units (1 tile = 8)
uniform float u_time;       // game ticks (60 per second)

uniform float u_wave;       // refraction strength, 1 = default
uniform float u_sparkle;    // glints and caustic lines, 1 = default
uniform float u_foam;       // shore foam, 1 = default
uniform sampler2D u_wake;   // wakes of units (Wakes.java): red = how much churned water
uniform float u_hasWake;    // 0 = none; otherwise how strongly they show
uniform vec2 u_wakeSize;    // the map in world units

uniform float u_reflect;     // reflection strength, 0 = off

#include "mshaders-reflect.glsl"

varying vec2 v_texCoords;


float noise(vec2 p){
    return texture2D(u_noise, p).r;
}

// 1 inside the liquid, 0 outside; seams between liquid tiles can be slightly transparent and must still count as inside
float inside(vec2 uv){
    return smoothstep(0.2, 0.8, texture2D(u_texture, uv).a);
}

// how much of a ring of 8 points around uv lies outside the liquid (0 = open water, towards 1 = shore)
float outside(vec2 uv, vec2 v, float r){
    float d = r * 0.7071;
    float a =
        inside(uv + vec2(r, 0.0) * v) +
        inside(uv + vec2(-r, 0.0) * v) +
        inside(uv + vec2(0.0, r) * v) +
        inside(uv + vec2(0.0, -r) * v) +
        inside(uv + vec2(d, d) * v) +
        inside(uv + vec2(-d, d) * v) +
        inside(uv + vec2(d, -d) * v) +
        inside(uv + vec2(-d, -d) * v);
    return 1.0 - a / 8.0;
}

void main(){
    // This shader runs over the whole screen. Where the layer has nothing (land, other floors), skip all the work.
    if(texture2D(u_texture, v_texCoords).a < 0.004){
        gl_FragColor = vec4(0.0);
        return;
    }
    vec2 c = v_texCoords;
    vec2 v = 1.0 / u_resolution;            // one world unit in texture coordinates
    vec2 world = c * u_resolution + u_campos;
    float t = u_time / 60.0;                // seconds

    // two noise layers drifting in different directions: slow swells a few tiles wide
    float h1 = noise(world / 760.0 + vec2(t * 0.0041, t * 0.0023));
    float h2 = noise(world / 430.0 + vec2(-t * 0.0052, t * 0.0061));
    float h = h1 * 0.6 + h2 * 0.4;

    // refraction: shift the lookup by a noise-driven offset (in world units)
    vec2 dir = vec2(
        noise(world / 330.0 + vec2(t * 0.011, 0.31)) - 0.5,
        noise(world / 330.0 + vec2(0.67, -t * 0.010)) - 0.5);
    vec2 offset = dir * 11.0 * u_wave * v;

    vec4 base = texture2D(u_texture, c);
    vec4 moved = texture2D(u_texture, c + offset);
    // never pull in the transparent area outside the liquid
    vec4 sampled = mix(base, moved, step(0.99, moved.a));

    // same blue tint as the built-in shader, then light and dark swells
    vec3 color = sampled.rgb * vec3(0.9, 0.9, 1.0);
    color *= 0.80 + h * 0.42;

    // caustic lines: thin bright bands where two drifting noise layers have the same value
    vec2 cp = (world + dir * 9.0) / 300.0;
    float ca = abs(noise(cp + vec2(t * 0.0075, t * 0.0021)) - noise(cp + vec2(0.43 - t * 0.0057, 0.19 + t * 0.0049)));
    float caustic = 1.0 - smoothstep(0.0, 0.030, ca);
    color += caustic * 0.13 * u_sparkle * vec3(0.75, 0.92, 1.0);

    // glints: small bright spots that drift and twinkle, mostly on the bright side of a swell
    float g = noise(world / 46.0 + vec2(t * 0.050, -t * 0.031)) * noise(world / 31.0 - vec2(t * 0.037, t * 0.023));
    float glint = smoothstep(0.36, 0.44, g) * smoothstep(0.42, 0.56, h);
    color += glint * 0.45 * u_sparkle;

    // Reflections (mshaders-reflect.glsl): buildings and units mirrored, light smeared into streaks that the waves
    // break into glitter - brighter on the caustic lines and the glint spots.
    if(u_reflect > 0.001){
        color = reflected(color, world, dir * 5.0 * u_wave, 0.30 + 2.8 * g + 1.5 * caustic, u_reflect, u_reflect);
    }

    // shore foam: the buffer is transparent outside the liquid, so low alpha nearby means a shore.
    // (the first 2-3 units next to land are hidden under the land tiles' own edge sprites, hence the wide rings)
    float shore = outside(c, v, 4.0) * 0.6 + outside(c, v, 8.0) * 0.4;
    float wobble = (noise(world / 120.0 + vec2(t * 0.02, -t * 0.015)) - 0.5) * 0.30;
    float band = smoothstep(0.10, 0.42, shore + wobble);
    // a thin line further out that moves towards and away from the land
    float reach = 0.10 + 0.06 * sin(t * 1.3 + h1 * 9.0);
    float lap = (1.0 - smoothstep(0.0, 0.035, abs(shore + wobble * 0.5 - reach))) * step(0.02, shore);
    float foam = clamp(band * 0.60 + lap * 0.45, 0.0, 1.0);
    color = mix(color, vec3(0.88, 0.95, 1.0), clamp(foam * u_foam, 0.0, 1.0));

    // wakes behind units in the water: foam broken up into flecks, and the water around it a little lighter
    if(u_hasWake > 0.001){
        float wk = texture2D(u_wake, (world + 4.0) / u_wakeSize).r * u_hasWake;
        if(wk > 0.003){
            float fleck = noise(world / 7.0 + vec2(t * 0.09, -t * 0.07)) * 0.6 + noise(world / 3.0 - vec2(t * 0.13, t * 0.05)) * 0.4;
            float wfoam = smoothstep(0.2, 0.55, wk * (0.35 + 1.0 * fleck));
            color += min(wk, 1.0) * 0.06;
            color = mix(color, vec3(0.90, 0.96, 1.0), clamp(wfoam, 0.0, 0.75));
        }
    }

    gl_FragColor = vec4(color, min(base.a * 100.0, 1.0));
}
