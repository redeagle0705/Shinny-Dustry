#define HIGHP

// Ice floor (and red ice). Drawn like the liquids: the ice tiles of the frame are first drawn into the effect buffer
// (u_texture, alpha 0 everywhere else), then that buffer goes to the screen through this shader.
// Ice does not move, so nothing here runs on a clock except a slow twinkle. What makes it read as ice is that it
// answers to the view: the sheen and the frost glints slide a little when the camera moves, and it mirrors what
// stands on it.
uniform sampler2D u_texture;
uniform sampler2D u_noise;   // sprites/noise.png, 128x128, repeating, linear filtered

uniform vec2 u_campos;       // world position of the lower-left screen corner
uniform vec2 u_resolution;   // camera size in world units (1 tile = 8)
uniform float u_time;        // game ticks (60 per second)

uniform float u_ice;         // strength of the ice look, 1 = default
uniform float u_reflect;     // reflection strength, 0 = off

// The ores lying on the ice (FloorFx.bindOres - the same pictures as the ground shader's). An ore is drawn with its
// floor, into this layer: here it got the ice's look and none of the relief the ground gives an ore elsewhere, and on
// ice it lay flat ("on ice the height map is used less - and the ores got that too, they look different").
uniform sampler2D u_kinds;   // one texel per tile: where the tile's ore sprite is (mshaders-ground.frag)
uniform vec2 u_tiles;        // map size in tiles; 0 = no picture, no ores
uniform sampler2D u_ores;    // the sprite sheet with the ore sprites
uniform vec2 u_sheet;        // its size in pixels; 0 = none
uniform float u_oreSize;     // how many pixels across an ore sprite is
uniform float u_px;          // world units per screen pixel
uniform float u_relief;      // strength of the ground's relief (the ore's is the same)
uniform vec2 u_sunDir;       // direction towards the sun on the ground

const float ORE_LIFT = 0.2;  // as in mshaders-ground.frag

#include "mshaders-reflect.glsl"

varying vec2 v_texCoords;

float noise(vec2 p){
    return texture2D(u_noise, p).r;
}

vec2 hash2(vec2 p){
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Distance to the nearest border between cells of scattered points (the plane cut into irregular polygons).
// Lines drawn where this is small are straight and meet at angles, like cracks in a sheet of ice.
float cellBorder(vec2 x){
    vec2 n = floor(x), f = fract(x);
    float nearest = 8.0, second = 8.0;
    for(int j = -1; j <= 1; j++){
        for(int i = -1; i <= 1; i++){
            vec2 g = vec2(float(i), float(j));
            vec2 r = g + hash2(n + g) - f;
            float d = dot(r, r);
            if(d < nearest){
                second = nearest;
                nearest = d;
            }else if(d < second){
                second = d;
            }
        }
    }
    return sqrt(second) - sqrt(nearest);
}

// (as in mshaders-ground.frag)
vec4 tileBytes(vec2 world){
    return floor(texture2D(u_kinds, (world + 4.0) / (8.0 * u_tiles)) * 255.0 + 0.5);
}

bool hasOre(vec4 bytes){
    return mod(bytes.r, 32.0) + bytes.g + bytes.b + bytes.a > 0.5;
}

float oreOn(vec2 world, vec4 bytes){
    if(!hasOre(bytes)) return 0.0;
    vec2 corner = vec2(mod(bytes.r, 32.0) * 256.0 + bytes.g, bytes.b * 256.0 + bytes.a);
    vec2 f = fract((world + 4.0) / 8.0);
    return smoothstep(0.5, 0.9, texture2D(u_ores, (corner + vec2(f.x, 1.0 - f.y) * u_oreSize) / u_sheet).a);
}

void main(){
    // This shader runs over the whole screen. Where the layer has nothing (land, other floors), skip all the work.
    if(texture2D(u_texture, v_texCoords).a < 0.004){
        gl_FragColor = vec4(0.0);
        return;
    }
    vec4 base = texture2D(u_texture, v_texCoords);
    // the buffer holds colour already multiplied by its alpha (tiles were blended onto a transparent buffer)
    vec3 color = base.rgb / max(base.a, 0.001);
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec2 eye = u_campos + u_resolution * 0.5;      // where the camera looks
    float t = u_time / 60.0;

    // An ore on this tile: it stands up from the ice as from any ground - the relief of its own picture, and a step at
    // its rim (lit on the sunward side, shaded on the other) - and keeps its own colour, not the ice's.
    bool oreTile = u_tiles.x > 0.5 && u_sheet.x > 0.5 && hasOre(tileBytes(world));
    float ore = 0.0;
    if(oreTile){
        ore = oreOn(world, tileBytes(world));
        float near = 1.0 - smoothstep(0.45, 1.0, u_px);
        if(u_relief > 0.001 && near > 0.0){
            vec2 pace = u_sunDir * max(0.25, u_px);
            vec2 reach = pace / u_resolution;
            vec4 lit = texture2D(u_texture, v_texCoords + reach), shaded = texture2D(u_texture, v_texCoords - reach);
            if(lit.a > 0.99 && shaded.a > 0.99){
                float rise = dot(lit.rgb / lit.a - shaded.rgb / shaded.a, vec3(0.299, 0.587, 0.114));
                vec2 sunward = world + pace, away = world - pace;
                float up = oreOn(sunward, tileBytes(sunward)), down = oreOn(away, tileBytes(away));
                float rim = up - down;
                rise = rise * (1.0 - abs(rim)) + (ore > 0.5 ? rim : max(rim, 0.0)) * ORE_LIFT;
                // (only the ore and the ice right at its rim: the ice itself stays flat)
                float keep = max(ore, max(up, down));
                color *= 1.0 + clamp(-rise * 2.2, -0.22, 0.28) * u_relief * keep * near;
            }
        }
    }
    vec3 plain = color;

    // depth: thicker, bluer ice in broad patches
    float thick = noise(world / 640.0 + 0.21) * 0.6 + noise(world / 290.0 + 0.67) * 0.4;
    color = mix(color, color * vec3(0.78, 0.88, 1.03), smoothstep(0.35, 0.65, thick) * 0.55 * u_ice);

    // cracks under the surface: hairlines along the borders of large irregular cells, dark with a pale edge,
    // and only in patches - a sheet of ice is not cracked everywhere
    float c1 = cellBorder(world / 38.0 + (noise(world / 170.0) - 0.5) * 0.6);
    float patches = smoothstep(0.38, 0.58, noise(world / 520.0 + 0.83));
    float crack = (1.0 - smoothstep(0.0, 0.030, c1)) * patches;
    float edge = ((1.0 - smoothstep(0.030, 0.085, c1)) * patches) - crack;
    color *= 1.0 - crack * 0.20 * u_ice;
    color += edge * 0.07 * u_ice;

    // sheen: long soft bands along the painted streaks of the ice tile (lower left to upper right). The pattern is
    // tied to the ground only in part, so it slides when the camera moves, the way a highlight on a polished floor does.
    vec2 slide = world - eye * 0.30;
    float along = dot(slide, vec2(0.7071, 0.7071)), across = dot(slide, vec2(-0.7071, 0.7071));
    float s = noise(vec2(along / 900.0, across / 190.0)) * 0.65 + noise(vec2(along / 420.0, across / 85.0) + 0.4) * 0.35;
    float sheen = smoothstep(0.50, 0.72, s);
    color += sheen * 0.13 * u_ice * vec3(0.90, 0.96, 1.0);

    // frost: tiny glints that twinkle slowly and also shift with the view
    vec2 fr = world - eye * 0.12;
    float f = noise(fr / 14.0 + vec2(t * 0.010, 0.0)) * noise(fr / 9.0 - vec2(0.0, t * 0.008));
    color += smoothstep(0.40, 0.47, f) * 0.38 * u_ice;

    // reflections: ice is flat, so the mirror image is steady and barely bent; light shows as a clean streak
    if(u_reflect > 0.001){
        vec2 bend = vec2(noise(world / 150.0) - 0.5, noise(world / 150.0 + 0.5) - 0.5) * 1.2;
        color = reflected(color, world, bend, 0.9 + 0.5 * sheen, u_reflect * 0.55 * u_ice, u_reflect * 1.1 * u_ice);
    }
    // the ore lies on the ice: no sheen, frost, cracks or mirror image on it
    if(ore > 0.0) color = mix(color, plain, ore);

    gl_FragColor = vec4(color, base.a);
}
