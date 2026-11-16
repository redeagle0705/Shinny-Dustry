#define HIGHP

// Dry ground with a little life of its own, the way the ice has (mshaders-ice.frag). One shader for every kind of ground:
//   1 rock    cracks in patches, broad lighter and darker areas, a few mineral glints
//   2 sand    wind ripples in drifts, dune shading, pale sand blowing downwind, grains that glint
//   3 snow    bluish hollows, sparkle that twinkles and shifts with the view
//   4 salt    damp patches, cracks in the crust, crystal sparkle
//   5 grass   gusts of wind running over it, blades leaning with them
//   6 hot     the glowing parts of the tile pulse, glowing cracks, the air over it shimmers
//   7 metal   a sheen that slides when the camera moves, fine scratches
// Drawn like the liquids: the tiles of all these kinds are first drawn into the effect buffer (u_texture, alpha 0
// everywhere else), then that buffer goes to the screen through this shader - once. Which kind a pixel is comes from
// u_kinds, a picture of the map with one texel per tile (FloorFx.java).
// (Before, every kind had a layer and a compiled shader of its own: up to seven times per frame the buffer was
// cleared, drawn into and laid over the whole screen. On a phone that - not the arithmetic below - was the cost.)
uniform sampler2D u_texture;
uniform sampler2D u_noise;   // sprites/noise.png, 128x128, repeating, linear filtered; one blob is about 1/25 of it
uniform sampler2D u_kinds;   // one texel per tile, nearest: red = kind * 32 / 255
uniform vec2 u_tiles;        // size of the map in tiles; 0 = no such picture, everything is drawn as it is

uniform vec2 u_campos;       // world position of the lower-left screen corner
uniform vec2 u_resolution;   // camera size in world units (1 tile = 8)
uniform float u_time;        // game ticks (60 per second)

// strength of each kind, 1 = default
uniform float u_a1;
uniform float u_a2;
uniform float u_a3;
uniform float u_a4;
uniform float u_a5;
uniform float u_a6;
uniform float u_a7;
uniform float u_px;          // world units per screen pixel (large when zoomed far out)
uniform float u_relief;      // strength of the relief shading, 0 = off, 1 = default
uniform vec2 u_sunDir;       // direction towards the sun on the ground, length 1

varying vec2 v_texCoords;

const vec2 wind = vec2(0.94, 0.34);   // the wind blows towards the right and a little up

float noise(vec2 p){
    return texture2D(u_noise, p).r;
}

vec2 hash2(vec2 p){
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Distance to the nearest border between cells of scattered points (the plane cut into irregular polygons).
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

// tiny bright points; "shift" is how far they slide when the camera moves, "drift" makes them twinkle
float glints(vec2 world, vec2 eye, float shift, float size, float drift, float lo, float hi){
    vec2 p = world - eye * shift;
    float f = noise(p / (size * 1.5) + vec2(drift, 0.0)) * noise(p / size - vec2(0.0, drift * 0.8));
    return smoothstep(lo, hi, f);
}

void main(){
    // This shader runs over the whole screen. Where the layer has nothing, skip all the work.
    vec4 base = texture2D(u_texture, v_texCoords);
    if(base.a < 0.004){
        gl_FragColor = vec4(0.0);
        return;
    }
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec2 eye = u_campos + u_resolution * 0.5;      // where the camera looks
    float t = u_time / 60.0;
    // Lines and points thinner than a screen pixel flicker when the camera moves: fade them out when zoomed far out.
    float fine = 1.0 - smoothstep(0.55, 1.3, u_px);

    // which kind of ground the tile under this pixel is (tile n covers n * 8 - 4 .. n * 8 + 4); 0 = none of them:
    // the edge of one of these floors lying over a tile of another floor, which is drawn as it is
    float kind = u_tiles.x > 0.5 ? floor(texture2D(u_kinds, (world + 4.0) / (8.0 * u_tiles)).r * 7.96875 + 0.5) : 0.0;
    bool hot = kind > 5.5 && kind < 6.5;

    if(hot){
        // heat shimmer: the picture itself wobbles a little (never pulling in the empty area outside the layer)
        vec2 wobble = vec2(noise(world / 60.0 + vec2(0.0, t * 0.11)), noise(world / 60.0 + vec2(0.5, -t * 0.09))) - 0.5;
        vec4 moved = texture2D(u_texture, v_texCoords + wobble * 1.1 * u_a6 * fine / u_resolution);
        if(moved.a > 0.99) base = moved;
    }

    // the buffer holds colour already multiplied by its alpha (tiles were blended onto a transparent buffer)
    vec3 color = base.rgb / max(base.a, 0.001);

    // Relief: the tile picture read as a height map - what is painted lighter (pebbles, sand ridges, lumps of snow,
    // blades of grass) stands higher. Light comes from the sun (u_sunDir, by default the upper right, as in the block
    // sprites): where the ground
    // falls towards the light it is lit, where it rises towards the light it is in shade.
    // (Not on hot rock: its light parts are glowing cracks, which are lower, not higher.)
    if(!hot && u_relief > 0.001){
        // one sprite pixel (a quarter of a world unit), but never less than a screen pixel
        vec2 reach = u_sunDir * max(0.25, u_px) / u_resolution;
        vec4 lit = texture2D(u_texture, v_texCoords + reach), shaded = texture2D(u_texture, v_texCoords - reach);
        // not across the edge of the layer, where the buffer is empty
        if(lit.a > 0.99 && shaded.a > 0.99){
            float rise = dot(lit.rgb - shaded.rgb, vec3(0.299, 0.587, 0.114));
            // smaller than a screen pixel it would only be noise: fade out when zoomed far out
            color *= 1.0 + clamp(-rise * 2.2, -0.22, 0.28) * u_relief * (1.0 - smoothstep(0.45, 1.0, u_px));
        }
    }

    if(kind < 0.5){
        // none of the kinds: as it is
    }else if(kind < 1.5){
        float a = u_a1;
        // broad lighter and darker areas, so the repeating tile is less obvious
        float tone = noise(world / 700.0 + 0.37) * 0.6 + noise(world / 260.0 + 0.11) * 0.4;
        color *= 1.0 + (tone - 0.5) * 0.18 * a;
        // what follows is too fine to be seen from far out (fine = 0): then it is not worked out at all
        if(fine > 0.0){
            // cracks: hairlines along the borders of large irregular cells, dark with a pale lip, only in patches
            float patches = smoothstep(0.40, 0.60, noise(world / 480.0 + 0.83));
            if(patches > 0.0){
                float c1 = cellBorder(world / 30.0 + (noise(world / 150.0) - 0.5) * 0.7);
                float crack = (1.0 - smoothstep(0.0, 0.035, c1)) * patches * fine;
                float lip = (1.0 - smoothstep(0.035, 0.10, c1)) * patches * fine - crack;
                color *= 1.0 - crack * 0.32 * a;
                color += lip * 0.045 * a;
            }
            color += glints(world, eye, 0.10, 7.0, 0.0, 0.46, 0.52) * 0.16 * a * fine;
        }
    }else if(kind < 2.5){
        float a = u_a2;
        float along = dot(world, wind), across = dot(world, vec2(-wind.y, wind.x));
        // dunes: broad light and dark
        color *= 1.0 + (noise(world / 620.0 + 0.2) - 0.5) * 0.13 * a;
        // wind ripples: fine wavy lines across the wind, one every 3.2 units, only where sand has drifted
        if(fine > 0.0){
            float bend = noise(vec2(along / 260.0, across / 420.0)) * 9.0 + noise(world / 90.0) * 2.0;
            float drifts = smoothstep(0.42, 0.62, noise(world / 520.0 + 0.5));
            color *= 1.0 + sin((along + bend) * 1.9635) * 0.04 * drifts * a * fine;
        }
        // blown sand: pale streaks that travel downwind
        float blow = noise(vec2(along / 300.0 - t * 0.045, across / 60.0)) * noise(vec2(along / 170.0 - t * 0.07, across / 38.0 + 0.4));
        color += smoothstep(0.30, 0.50, blow) * 0.07 * a * vec3(1.0, 0.97, 0.90);
        if(fine > 0.0) color += glints(world, eye, 0.10, 7.0, t * 0.004, 0.44, 0.50) * 0.22 * a * fine;
    }else if(kind < 3.5){
        float a = u_a3;
        // hollows: bluish shade in broad patches
        float drift = noise(world / 560.0 + 0.3) * 0.6 + noise(world / 210.0 + 0.7) * 0.4;
        color = mix(color, color * vec3(0.88, 0.93, 1.02), smoothstep(0.40, 0.70, drift) * 0.55 * a);
        if(fine > 0.0) color += glints(world, eye, 0.14, 8.0, t * 0.011, 0.38, 0.45) * 0.45 * a * fine;
    }else if(kind < 4.5){
        float a = u_a4;
        // The salt tile already shows the polygons of a salt flat, and it is nearly white: anything added washes it out.
        // So: damp, slightly greyer patches, and thin dark cracks where the crust has split, much larger than the polygons.
        float tone = noise(world / 520.0 + 0.61) * 0.6 + noise(world / 190.0 + 0.2) * 0.4;
        color *= 1.0 - smoothstep(0.45, 0.75, tone) * 0.08 * a;
        if(fine > 0.0){
            float patches = smoothstep(0.45, 0.62, noise(world / 430.0 + 0.3));
            if(patches > 0.0){
                float c1 = cellBorder(world / 44.0 + (noise(world / 170.0) - 0.5) * 0.6);
                color *= 1.0 - (1.0 - smoothstep(0.0, 0.025, c1)) * patches * fine * 0.18 * a;
            }
            color += glints(world, eye, 0.14, 8.0, t * 0.009, 0.40, 0.47) * 0.30 * a * fine;
        }
    }else if(kind < 5.5){
        float a = u_a5;
        float along = dot(world, wind), across = dot(world, vec2(-wind.y, wind.x));
        // gusts: broad bands of lighter, yellower grass that travel downwind
        float gust = noise(vec2(along / 520.0 - t * 0.035, across / 300.0)) * 0.6 + noise(vec2(along / 230.0 - t * 0.06, across / 150.0) + 0.3) * 0.4;
        float wave = smoothstep(0.42, 0.68, gust);
        color *= 1.0 + (wave - 0.35) * 0.17 * a;
        color += wave * vec3(0.030, 0.034, 0.0) * a;
        // blades: fine streaks along the wind that lean further where a gust passes
        if(fine > 0.0){
            float blades = noise(vec2(along / 30.0 - wave * 0.10, across / 9.0));
            color *= 1.0 + (blades - 0.5) * 0.06 * a * fine;
        }
    }else if(kind < 6.5){
        float a = u_a6;
        // the glowing parts of the tile (more red than blue) are the hot ones
        float heat = smoothstep(0.10, 0.32, color.r - color.b);
        // slow pulse, different from place to place
        float pulse = noise(world / 240.0 + vec2(t * 0.030, -t * 0.021)) * 0.65
            + 0.35 * (0.5 + 0.5 * sin(t * 1.7 + noise(world / 90.0) * 12.0));
        color += heat * (pulse - 0.30) * vec3(1.0, 0.45, 0.10) * 0.60 * a;
        // cracks in the crust, in patches, glowing from below: a bright hairline with a dull red halo, pulsing with the rest
        if(fine > 0.0){
            float patches = smoothstep(0.38, 0.58, noise(world / 420.0 + 0.47));
            if(patches > 0.0){
                float c1 = cellBorder(world / 34.0 + (noise(world / 140.0) - 0.5) * 0.7);
                float crack = (1.0 - smoothstep(0.0, 0.045, c1)) * patches * fine;
                float halo = (1.0 - smoothstep(0.0, 0.16, c1)) * patches * fine;
                color += (crack * (0.35 + 0.65 * pulse) * vec3(1.0, 0.55, 0.15) * 0.55 + halo * pulse * vec3(0.50, 0.12, 0.0) * 0.25) * a;
            }
        }
        // the rock around it catches a little of the glow
        color += (1.0 - heat) * smoothstep(0.45, 0.80, pulse) * vec3(0.070, 0.020, 0.0) * a;
    }else{
        float a = u_a7;
        // sheen: long soft bands on the diagonal. Tied to the ground only in part, so it slides when the camera moves.
        vec2 slide = world - eye * 0.35;
        float along = dot(slide, vec2(0.7071, 0.7071)), across = dot(slide, vec2(-0.7071, 0.7071));
        float s = noise(vec2(along / 1100.0, across / 260.0)) * 0.65 + noise(vec2(along / 500.0, across / 120.0) + 0.4) * 0.35;
        float sheen = smoothstep(0.48, 0.72, s);
        color += sheen * 0.10 * a * vec3(0.92, 0.96, 1.0);
        color *= 1.0 - (1.0 - sheen) * 0.04 * a;
        // fine scratches along the same diagonal
        if(fine > 0.0) color *= 1.0 + (noise(vec2(along / 160.0, across / 14.0)) - 0.5) * 0.07 * a * fine;
    }

    gl_FragColor = vec4(color, base.a);
}
