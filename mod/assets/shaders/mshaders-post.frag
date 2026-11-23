#define HIGHP

// Last step: the finished world image goes to the screen through this shader (the UI is drawn afterwards, untouched).
// Order: metal glint on blocks, light from light sources, colour pass. Each part is skipped when its strength is 0.
uniform sampler2D u_texture;   // the world
uniform sampler2D u_lightmap;  // quarter-size light (mshaders-light.frag, mode 0)
uniform sampler2D u_aim;       // quarter-size light direction (mode 1)
uniform sampler2D u_mask;      // the game's block shadow mask, one texel per tile

uniform vec2 u_campos;         // world position of the lower-left screen corner
uniform vec2 u_resolution;     // camera size in world units
uniform vec2 u_world;          // map size in world units
uniform float u_time;          // seconds

uniform float u_light;         // light strength, 0 = off
uniform float u_glint;         // glint strength, 0 = off
uniform float u_aimed;         // 1 when u_aim holds this frame's light directions
uniform float u_band;          // strength of the drifting sun band, 0 = no band
uniform float u_bandWave;      // 2 = sun through gaps in clouds, 1 = a winding band at random, 0 = straight bands one after another

uniform sampler2D u_surface;   // the normals drawn for this frame (Surfaces.draw); alpha = 1 where a normal-mapped building is
uniform float u_shape;         // 1 when u_surface is bound

// Blocks that are not drawn as full squares (as in mshaders-shadow.frag). u_shapes, one texel per tile, unfiltered:
// red, green = the block's slot in u_outlines, blue = which tile of its block this is (x * 16 + y), alpha = size * 15 / 255,
// 0 = a plain square or no block. u_outlines, 16 texels per tile: green = how much the block covers there.
uniform sampler2D u_shapes;
uniform sampler2D u_outlines;
uniform float u_shaped;        // 1 when both are bound

// How glossy the block on each tile is (Gloss.java, Occluders.java): one texel per tile, unfiltered, red 0..1 -
// 0 = matt, 0.5 = half polished, 1 = polished metal.
uniform sampler2D u_gloss;
uniform float u_glossy;        // 1 when it is bound
uniform float u_day;           // how much daylight there is: 1 on a bright map, what the darkness leaves on a dark one
uniform vec2 u_sunDir;         // direction towards the sun on the ground, length 1

uniform float u_vignette;      // 0 = none, 1 = corners fully dark
uniform float u_saturation;    // 1 = unchanged
uniform float u_contrast;      // 1 = unchanged
uniform float u_warmth;        // 0 = unchanged, + warmer, - cooler
uniform vec4 u_sky;            // the light of the time of day (Daytime.java): the picture is multiplied by rgb, a = how much
uniform vec2 u_night;          // night: x = how much (0 = day), y = how bright the moonlit world is (1 = as by day)
uniform vec4 u_waves[16];      // waves of menders: x, y in world units, how far along (0 .. 1), range (0 = not in use)
uniform vec4 u_waveColors[16];
uniform vec4 u_shapes4[24];    // the places of the menders of up to four groups whose waves run: 12 points a slot, two to a vec4
uniform vec4 u_waveEnds[16];   // the other end of each wave's line (x, y): a group of menders sends one long wave from the line through it
uniform float u_pulse;         // strength of those waves, 0 = none
uniform vec4 u_hazes[8];       // overdrive projectors at work: x, y, range, how hard they work (0 = not in use)
uniform vec4 u_hazeColors[8];
uniform float u_haze;          // strength of their shimmer and glow, 0 = none
uniform vec4 u_blasts[16];     // explosions of late: x, y in world units, age in ticks, how far the wave runs (0 = not in use)
uniform float u_rain;          // how much rain there is, 0 = none
uniform vec4 u_heats[24];      // hot things in view: x, y in world units, how far the haze reaches, how hot (0 .. 1); reach 0 = not in use
uniform float u_heat;          // strength of the heat haze, 0 = none
uniform sampler2D u_kinds;     // the kind of dry ground of each tile (FloorFx): upper 3 bits of red, 0 = none of them
uniform vec2 u_tiles;          // the map in tiles; 0 = no such picture
uniform float u_shock;         // strength of the shock waves, 0 = none
uniform float u_flash;         // strength of the flashes of explosions, 0 = none
uniform float u_spare;         // 0 = every colour gets the saturation and contrast above, 1 = colours that are vivid already are spared (set on Erekir only)

varying vec2 v_texCoords;

const vec2 baked = vec2(0.7071, 0.7071);  // block sprites are painted as if lit from the upper right

float luma(vec3 c){
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float lumaAt(vec2 uv){
    return luma(texture2D(u_texture, uv).rgb);
}


// Smooth noise for the clouds (value noise on a grid of one unit, eased between).
float cloudHash(vec2 p){
    // (no sine: with sine, far from the map's origin the graphics card's precision ran out and the noise broke into
    // straight-edged blocks - seen as straight cuts through rain puddles)
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float cloudNoise(vec2 p){
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(cloudHash(i), cloudHash(i + vec2(1.0, 0.0)), f.x), mix(cloudHash(i + vec2(0.0, 1.0)), cloudHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// The bright sunlight that drifts across the map ("dir": the way the wind takes it).
//   u_bandWave 0: as at first, a straight band every 170 world units, one after another at one pace
//   u_bandWave 1: the band winds and comes at random ("not straight, wavy, at random intervals", a player asked)
//   u_bandWave 2: no band - sunlight through gaps in clouds that drift over the map: soft patches of sun, a few
//                 hundred world units across, that grow, shrink and pass ("not lines: as if it went through the places
//                 without cloud, and was gone again", the maker)
float sunBand(vec2 world, vec2 dir, float t){
    float along = dot(world, dir);
    if(u_bandWave < 0.5) return pow(0.5 + 0.5 * sin((along / 170.0 - t * 0.22) * 6.2832), 28.0);
    if(u_bandWave > 1.5){
        // the clouds move with the wind as the band did; three layers, each turned against the last so the grid of
        // the noise does not show, the finer ones drifting a little on their own so the gaps change shape
        vec2 p = (world - dir * t * 37.0) / 300.0;
        vec2 q = vec2(p.x * 0.8 - p.y * 0.6, p.x * 0.6 + p.y * 0.8);
        vec2 r = vec2(q.x * 0.8 - q.y * 0.6, q.x * 0.6 + q.y * 0.8);
        float c = cloudNoise(p) * 0.50 + cloudNoise(q * 2.1 + vec2(5.2 - t * 0.04, 1.3)) * 0.32 + cloudNoise(r * 4.7 + vec2(9.1, -2.7 + t * 0.06)) * 0.18;
        // a gap where the cloud is thinnest: about a fifth of the ground at a time, with soft edges
        return smoothstep(0.58, 0.72, c);
    }
    float across = dot(world, vec2(-dir.y, dir.x));
    along += sin(across / 45.0 + t * 0.35) * 14.0 + sin(across / 110.0 - t * 0.12) * 24.0;
    float p = along / 170.0 - t * 0.22;
    float n = floor(p);     // which band (its brightest is at a quarter of the way through)
    float h1 = fract(sin(n * 12.9898 + 4.1) * 43758.5453), h2 = fract(sin(n * 78.233 + 1.7) * 24634.6345);
    if(h1 > 0.5) return 0.0;
    return (0.6 + 0.4 * h2) * pow(0.5 + 0.5 * sin(p * 6.2832), 20.0 + 20.0 * h2);
}

// The shock waves of explosions (Blasts.java): a ring runs out from each, fast at first and slowing, and where it
// passes the picture is pushed outwards just ahead of it and drawn in just behind it - the air bent by the blast. It
// weakens as it spreads and is gone by the time it reaches its full size. Returns where to read the picture instead,
// and in "lit" how bright the front of the wave is.
vec2 blastWarp(vec2 uv, vec2 world, out float lit){
    lit = 0.0;
    vec2 push = vec2(0.0);
    for(int i = 0; i < 16; i++){
        vec4 b = u_blasts[i];
        if(b.w < 0.5) continue;
        float life = 20.0 + b.w * 0.25;
        float k = b.z / life;
        if(k >= 1.0) continue;
        vec2 to = world - b.xy;
        float d = length(to);
        float r = b.w * (1.0 - (1.0 - k) * (1.0 - k) * (1.0 - k));   // eases out
        float width = 2.5 + b.w * 0.10;
        float x = (d - r) / width;
        if(abs(x) > 3.0) continue;
        float fade = (1.0 - k) * (1.0 - k);
        // a bump that pushes out ahead of the front and pulls in behind it
        float bump = -x * exp(-x * x) * 1.6;
        float amount = (1.5 + b.w * 0.05) * fade;
        push += to / max(d, 0.001) * bump * amount;
        lit += max(-x, 0.0) * exp(-x * x) * fade;
    }
    lit = min(lit, 1.0) * u_shock;
    return uv - push * u_shock / u_resolution;
}

// Heat haze: the air over hot things (smelters, generators, reactors, turrets that have just fired; HeatHaze.java)
// shimmers - the picture is read from a little aside, by fine waves that run up through it fast. The haze reaches a
// little further up than down (the warm air rises).
vec2 heatWarp(vec2 uv, vec2 world){
    vec2 push = vec2(0.0);
    float t = u_time;
    for(int i = 0; i < 24; i++){
        vec4 h = u_heats[i];
        if(h.z < 0.5) continue;
        // most of it in the air above the hot thing, less over the thing itself (at first it bent the block like
        // jelly)
        vec2 to = world - h.xy - vec2(0.0, h.z * 0.45);
        to.y *= 0.8;
        float d = length(to) / (h.z * 1.1);
        if(d >= 1.0) continue;
        float inside = (1.0 - smoothstep(0.15, 1.0, d)) * h.w;
        vec2 p = world / 2.4;
        push += vec2(sin(p.y * 1.7 - t * 8.0 + sin(p.x * 0.9 + t * 2.3)),
                     0.5 * cos(p.x * 1.5 - t * 6.3 + sin(p.y * 1.1 - t * 3.1))) * inside;
    }
    return uv + push * 0.2 * u_heat / u_resolution;
}

// How strongly the flashes of explosions light this place (the same life and reach as in Blasts.flashes).
float blastFlash(vec2 world){
    float f = 0.0;
    for(int i = 0; i < 16; i++){
        vec4 b = u_blasts[i];
        if(b.w < 0.5) continue;
        float life = 8.0 + b.w * 0.12;
        if(b.z >= life) continue;
        float k = b.z / life, left = (1.0 - k) * (1.0 - k);
        float radius = b.w * 1.8 + 24.0;
        float d = length(world - b.xy) / radius;
        f += left * (1.0 - smoothstep(0.0, 1.0, d)) * (1.0 - smoothstep(0.0, 1.0, d));
    }
    return min(f, 1.5);
}

// The air over an overdrive projector's range shimmers a little, as over heat: where to read the picture instead,
// and in "warm" how much of the projector's colour is laid on (a faint glow that breathes, stronger towards the edge).
vec2 overdriveHaze(vec2 uv, vec2 world, out vec3 warm){
    warm = vec3(0.0);
    vec2 push = vec2(0.0);
    float t = u_time;
    for(int i = 0; i < 8; i++){
        vec4 h = u_hazes[i];
        if(h.z < 0.5) continue;
        float d = length(world - h.xy) / h.z;
        if(d > 1.0) continue;
        float inside = smoothstep(1.0, 0.85, d) * h.w;
        // slow, soft waves rising through the air
        vec2 p = world / 14.0;
        push += vec2(sin(p.y * 1.3 + t * 2.1 + sin(p.x * 0.7 + t)), cos(p.x * 1.1 - t * 1.7 + sin(p.y * 0.9 - t * 0.6))) * inside;
        float breathe = 0.5 + 0.5 * sin(t * 3.1415 * 0.6);
        warm += u_hazeColors[i].rgb * inside * (0.025 + 0.035 * smoothstep(0.55, 1.0, d) + 0.02 * breathe);
    }
    warm *= u_haze;
    return uv + push * 0.55 * u_haze / u_resolution;
}

// How far "world" is from the nearest point of a group's shape (slot 1 .. 4, count points).
float shapeDistance(vec2 world, float slot, float count){
    float d = 1e9;
    int base = int(slot - 1.0) * 6;
    for(int j = 0; j < 24; j++){
        if(j < base || j >= base + 6) continue;
        vec4 p = u_shapes4[j];
        float n = float(j - base) * 2.0;
        if(n < count) d = min(d, length(world - p.xy));
        if(n + 1.0 < count) d = min(d, length(world - p.zw));
    }
    return d;
}

// The waves of menders: a soft ring of the mender's light that runs out to the edge of its range. Blocks catch more of
// it than the ground (they stand up into it).
vec3 pulseLight(vec2 world, float onBlock){
    vec3 add = vec3(0.0);
    for(int i = 0; i < 16; i++){
        vec4 w = u_waves[i];
        if(w.w < 0.5) continue;
        float k = w.z;
        float r = w.w * (1.0 - (1.0 - k) * (1.0 - k));
        // distance to the wave's line (a point for a single block)
        vec2 ab = u_waveEnds[i].xy - w.xy;
        float h = clamp(dot(world - w.xy, ab) / max(dot(ab, ab), 0.001), 0.0, 1.0);
        float d = u_waveEnds[i].z > 0.5 ? shapeDistance(world, u_waveEnds[i].z, u_waveEnds[i].w) : length(world - w.xy - ab * h);
        float width = 3.0 + w.w * 0.06;
        float x = (d - r) / width;
        if(abs(x) > 3.0 || d > w.w + width) continue;
        // a bright front and a softer glow behind it, fading as it goes
        float front = exp(-x * x) + (x < 0.0 ? exp(x * 0.8) * 0.35 : 0.0);
        add += u_waveColors[i].rgb * u_waveColors[i].a * front * (1.0 - k) * (0.35 + 0.65 * onBlock);
    }
    // (several at one place do not add up past one wave's worth)
    return min(add, vec3(1.0)) * u_pulse;
}

// Faint waves (an overdrive's: strength below 1) bend the picture a little as they pass, like a soft shock wave.
vec2 waveWarp(vec2 uv, vec2 world){
    vec2 push = vec2(0.0);
    for(int i = 0; i < 16; i++){
        vec4 w = u_waves[i];
        float a = u_waveColors[i].a;
        if(w.w < 0.5 || a >= 0.99) continue;
        float k = w.z, r = w.w * (1.0 - (1.0 - k) * (1.0 - k));
        vec2 ab = u_waveEnds[i].xy - w.xy;
        vec2 to = world - w.xy - ab * clamp(dot(world - w.xy, ab) / max(dot(ab, ab), 0.001), 0.0, 1.0);
        float d = length(to), x = (d - r) / (3.0 + w.w * 0.05);
        if(abs(x) > 3.0) continue;
        push += to / max(d, 0.001) * (-x * exp(-x * x)) * (1.0 - k) * 1.2;
    }
    return uv - push * u_pulse / u_resolution;
}

// The kind of dry ground of the tile at that place (FloorFx kinds), 0 = none of them.
float kindAt(vec2 world){
    return floor(floor(texture2D(u_kinds, (world + 4.0) / (8.0 * u_tiles)).r * 255.0 + 0.5) / 32.0 + 0.01);
}

// 1 where a tile takes puddles (dry ground other than snow and hot rock), 0 where not.
float puddleGround(float kind){
    return kind > 0.5 && kind < 5.5 && (kind < 2.5 || kind > 3.5) || kind > 6.5 ? 1.0 : 0.0;
}

// Rain on the ground: wet darkening, puddles in the hollows (where a slow noise is high), and in them rings where drops
// fall. "onBlock": how much of this place is a block (blocks get only the wet sheen, no puddles).
vec3 rainOn(vec3 color, vec2 world, float onBlock){
    float r = u_rain;
    // Only dry ground gets wet and gathers puddles - not water, deep water, tar or other liquids, which are wet
    // already ("puddles don't suit places with water already"). The kinds of dry ground: rock, sand, salt, grass,
    // metal floors; snow gets wet but no puddles; hot rock neither.
    float kind = 0.0;
    if(u_tiles.x > 0.5) kind = kindAt(world);
    bool dry = kind > 0.5 && (kind < 5.5 || kind > 6.5);
    if(onBlock < 0.5 && !dry) return color;
    // How far into ground that takes puddles: the four tiles around, blended - so a puddle thins out towards the
    // water's edge instead of being cut off along the tiles ("this looks unnatural")
    float room = 0.0;
    if(u_tiles.x > 0.5){
        vec2 t = (world + 4.0) / 8.0 - 0.5, base = floor(t), f = fract(t);
        float a = 0.0, b = puddleGround(kindAt((base + vec2(1.0, 0.0)) * 8.0)),
            c = puddleGround(kindAt((base + vec2(0.0, 1.0)) * 8.0)), d = puddleGround(kindAt((base + vec2(1.0, 1.0)) * 8.0));
        a = puddleGround(kindAt(base * 8.0));
        f = f * f * (3.0 - 2.0 * f);
        room = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    }
    bool puddles = room > 0.01;
    // wet: a little darker and more saturated
    float g = luma(color);
    color = mix(color, mix(vec3(g), color, 1.12) * 0.86, r * (1.0 - 0.5 * onBlock));
    if(onBlock > 0.5){
        // blocks: a faint sheen on the sides turned to the sun
        return color + r * 0.03;
    }
    if(!puddles) return color;
    // (three layers turned against each other: one layer alone showed the square grid of the noise at the edges)
    vec2 p = world / 40.0;
    vec2 q = vec2(p.x * 0.8 - p.y * 0.6, p.x * 0.6 + p.y * 0.8);
    vec2 w = vec2(q.x * 0.8 - q.y * 0.6, q.x * 0.6 + q.y * 0.8);
    float n = cloudNoise(p) * 0.5 + cloudNoise(q * 2.3 + 3.1) * 0.32 + cloudNoise(w * 5.1 + 7.7) * 0.18;
    // (where the ground takes fewer puddles, the noise must be higher: the puddle shrinks away from the edge)
    float puddle = smoothstep(0.60 - 0.06 * r + (1.0 - room) * 0.3, 0.68 - 0.06 * r + (1.0 - room) * 0.3, n) * r;
    if(puddle <= 0.001) return color;
    // a puddle: darker, cooler, with the sky in it
    vec3 sky = vec3(0.55, 0.62, 0.72);
    // (the sky in it as bright as the ground around: added evenly, it lit up the shadows that fall on a puddle and
    // their edges showed as straight cuts through it)
    color = mix(color, color * 0.5 + sky * 0.2 * clamp(luma(color) * 2.2, 0.0, 1.0), puddle * 0.7);
    // the rim of a puddle catches the light
    float rim = smoothstep(0.0, 0.25, puddle) * (1.0 - smoothstep(0.25, 0.7, puddle));
    color += vec3(0.8, 0.85, 0.9) * rim * 0.05;
    // rings where drops fall: one drop now and then in each cell of 9 world units
    vec2 cellP = world / 9.0, id = floor(cellP), f = fract(cellP);
    float ring = 0.0;
    for(int j = -1; j <= 0; j++){
        for(int i = -1; i <= 0; i++){
            vec2 c = id + vec2(float(i), float(j));
            float h = cloudHash(c), h2 = cloudHash(c + 17.3);
            float phase = fract(u_time * (0.9 + 0.6 * h2) + h);
            vec2 at = vec2(float(i), float(j)) + vec2(h, h2) * 0.8 + 0.1;
            float d = length(f - at) * 9.0;
            float rad = phase * 3.5;
            float x = (d - rad) / 0.45;
            ring += exp(-x * x) * (1.0 - phase) * step(cloudHash(c + floor(u_time * (0.9 + 0.6 * h2) + h)), 0.55 + 0.4 * r);
        }
    }
    color += vec3(0.75, 0.82, 0.9) * min(ring, 1.0) * 0.22 * puddle;
    return color;
}

void main(){
    vec2 uv = v_texCoords;
    if(u_pulse > 0.001) uv = waveWarp(uv, uv * u_resolution + u_campos);
    vec3 hazeWarm = vec3(0.0);
    if(u_haze > 0.001) uv = overdriveHaze(uv, uv * u_resolution + u_campos, hazeWarm);
    if(u_heat > 0.001) uv = heatWarp(uv, uv * u_resolution + u_campos);
    float shockLit = 0.0;
    if(u_shock > 0.001) uv = blastWarp(uv, uv * u_resolution + u_campos, shockLit);
    vec3 color = texture2D(u_texture, uv).rgb;
    vec2 world = uv * u_resolution + u_campos;

    // Blocks with a normal map (Surfaces.java) get their shading and their glint in the middle of the frame
    // (mshaders-shape.frag), under the units. Here they are only left out, so the glint below is not laid on twice.
    bool shaped = false;
    if(u_shape > 0.001){
        shaped = texture2D(u_surface, uv).a > 0.5;
    }

    if(u_glint > 0.001 && !shaped){
        float block = smoothstep(0.55, 0.90, (1.0 - texture2D(u_mask, (world + 4.0) / u_world).r) / 0.71);
        if(block > 0.01 && u_shaped > 0.5){
            // Where such a block leaves its tiles empty the ground shows, and the ground gets no glint.
            // (It did: a paler square around every power node.)
            vec2 at = (world + 4.0) / 8.0, cell = floor(at);   // in tiles; tile n covers n .. n + 1
            vec4 shape = texture2D(u_shapes, (cell + 0.5) * 8.0 / u_world) * 255.0;
            if(shape.a > 7.0){
                float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
                vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));
                block *= smoothstep(0.35, 0.65, texture2D(u_outlines, (shape.rg + clamp(at - corner, 0.04, size - 0.04)) / 64.0).g);
            }
        }
        if(block > 0.01){
            // Block sprites use three tones per material: light = faces turned to the upper right (and raised tops),
            // mid = flat, dark = faces turned away. Brightness minus the brightness around it therefore says which way
            // a face leans, whatever the material's own brightness is. Sprites have 4 texels per world unit.
            vec2 px = 0.25 / u_resolution;
            float r = 6.0, k = 4.24;
            float around = (
                lumaAt(uv + vec2(r, 0.0) * px) + lumaAt(uv + vec2(-r, 0.0) * px) +
                lumaAt(uv + vec2(0.0, r) * px) + lumaAt(uv + vec2(0.0, -r) * px) +
                lumaAt(uv + vec2(k, k) * px) + lumaAt(uv + vec2(-k, k) * px) +
                lumaAt(uv + vec2(k, -k) * px) + lumaAt(uv + vec2(-k, -k) * px)) / 8.0;
            float lean = (luma(color) - around) / 0.09;
            // sprite tones are flat, so these two follow the painted edges exactly
            float lit = smoothstep(0.20, 0.45, lean);     // light-tone face: turned to the upper right, or a raised top
            float away = smoothstep(0.20, 0.45, -lean);   // dark-tone face: turned to the lower left

            // sunlight from the upper right: a faint sheen on the lit faces, and a bright band that drifts across the map
            float band = sunBand(world, baked, u_time);
            float shine = lit * (0.05 + 0.30 * band * u_band);

            // moving lights: faces turned towards the light catch it (lit faces for a light to the upper right,
            // dark faces for one to the lower left)
            if(u_aimed > 0.5){
                vec2 a = texture2D(u_aim, uv).rg * 2.0 - 1.0;
                float len = length(a);
                if(len > 0.03){
                    float toward = dot(baked, a / len);
                    shine += (lit * max(toward, 0.0) + away * max(-toward, 0.0)) * min(len * 2.5, 1.0) * 0.32;
                }
            }
            // metal: the highlight keeps most of the material's own colour
            // by what the block is made of: none of it on a matt block, twice as much on polished metal
            float much = u_glossy > 0.5 ? texture2D(u_gloss, (floor((world + 4.0) / 8.0) + 0.5) * 8.0 / u_world).r * 2.0 : 1.0;
            shine *= block * u_glint * much;
            // Polished metal mirrors the sky: the streak that slides over the blocks with a normal map when the camera
            // moves (mshaders-shape.frag) lies over these too - fainter, because here the faces are only guessed.
            float gloss = much * 0.5, mirror = gloss * sqrt(gloss);
            if(mirror > 0.003 && u_day > 0.001){
                vec2 eye = u_campos + u_resolution * 0.5;
                float along = dot(world - eye * 0.35 + baked * (lit - away) * 6.0, u_sunDir) / 21.0;
                float sky = pow(0.5 + 0.5 * sin(along * 6.2832), mix(3.0, 12.0, gloss));
                color += color * (sky * 0.13 * mirror * u_day * block * u_glint);
            }
            color += color * shine + vec3(1.0, 0.98, 0.92) * shine * 0.15;
        }
    }

    if(u_light > 0.001){
        // Bright maps: a lamp in daylight. The light cannot burn the picture out: its amount levels off (1 - exp), and
        // what is already bright gains little while darker ground and shade gain more. Mostly it lights up what is
        // there (colour * light), plus a little glow of its own.
        vec3 l = vec3(1.0) - exp(-texture2D(u_lightmap, uv).rgb * 1.4);
        float room = 1.0 - luma(color);
        color += (color * 0.9 + 0.06) * l * u_light * (0.12 + 0.88 * room * room);
    }

    // The flash of an explosion, on a bright map too: there the light above is kept faint (a lamp in daylight), and
    // a flash would hardly show. Where the flash falls - the light map says where blocks keep it off - the picture
    // brightens, warm. (On a dark map the light above shows it already; this adds less there, as daylight is less.)
    if(u_flash > 0.001){
        float f = blastFlash(uv * u_resolution + u_campos) * u_flash;
        if(f > 0.001){
            float open = u_light > 0.001 ? clamp(luma(texture2D(u_lightmap, uv).rgb) * 2.5, 0.0, 1.0) : 1.0;
            f *= open * u_day;
            // (at most this much: a full flash burnt the middle out to white)
            color += (color * 0.6 + vec3(0.07, 0.055, 0.035)) * min(f, 1.0);
        }
    }

    float gray = luma(color);
    // More saturation and contrast are meant for the muted colours of most maps. Where the picture is vivid already -
    // all of Erekir, orange rock under the red haze the game lays over that planet (EnvRenderers, Env.scorching) -
    // they turn it into glowing red (a player: "Erekir is too red"; measured on its first map: the game alone
    // 149,71,51 on average, with this pass 156,61,36). With u_spare a colour is spared by how vivid it is: no more
    // saturation than it has, and contrast of its brightness alone (contrast on each of red, green, blue apart pushes
    // them further from each other = more colour).
    float top = max(color.r, max(color.g, color.b)), low = min(color.r, min(color.g, color.b));
    float spare = u_spare * smoothstep(0.35, 0.75, top > 0.001 ? (top - low) / top : 0.0);
    color = mix(vec3(gray), color, mix(u_saturation, min(u_saturation, 1.0), spare));
    if(spare > 0.001){
        float g = luma(color);
        color = mix((color - 0.5) * u_contrast + 0.5, color * (max((g - 0.5) * u_contrast + 0.5, 0.0) / max(g, 0.001)), spare);
    }else{
        color = (color - 0.5) * u_contrast + 0.5;
    }
    color *= vec3(1.0 + 0.10 * u_warmth, 1.0 + 0.02 * u_warmth, 1.0 - 0.10 * u_warmth);
    if(u_sky.a > 0.001) color *= max(mix(vec3(1.0), u_sky.rgb, u_sky.a), 0.0);
    if(u_night.x > 0.001){
        // By moonlight: little colour, cool, darker - but where a lamp or a lit building lights the ground, the
        // world keeps its own warm colour and brightness (the light map: the lights of the frame, blocks keeping it off)
        // the light that falls here, in its own colour (it is added to the moonlit world: a pool of light that fades
        // out, not a hole of daylight - at first it gave back the whole day inside a sharp circle)
        vec3 lamp = vec3(0.0);
        if(u_light > 0.001) lamp = vec3(1.0) - exp(-texture2D(u_lightmap, uv).rgb * 1.4);
        float g = luma(color);
        vec3 moon = mix(vec3(g), color, 0.40) * vec3(0.62, 0.74, 1.0) * u_night.y;
        // the brightest things (glowing parts, fire, molten slag) keep some of their colour: they shine on their own
        // (bright and strongly coloured: not pale sand or snow, which only reflect)
        float hi = max(color.r, max(color.g, color.b)), lo = min(color.r, min(color.g, color.b));
        float glow = smoothstep(0.75, 0.95, hi) * smoothstep(0.50, 0.75, hi - lo);
        moon = mix(moon, color * 0.85, glow * 0.6);
        // (a little less colour in what the light falls on: cold light on grass came out neon green)
        vec3 night = moon + mix(vec3(g), color, 0.7) * lamp * 1.0;
        color = mix(color, min(night, max(color, moon)), clamp(u_night.x, 0.0, 1.0));
    }

    // oval vignette: 0 in the middle, 1 in the corners
    float d = length(uv - 0.5) * 1.4142;
    color *= 1.0 - smoothstep(0.45, 1.0, d) * u_vignette;

    // the waves of menders, and the glow of overdrives
    if(u_pulse > 0.001){
        vec2 wpos = uv * u_resolution + u_campos;
        float onBlock = smoothstep(0.55, 0.90, (1.0 - texture2D(u_mask, (wpos + 4.0) / u_world).r) / 0.71);
        // (lit like a lamp: what is bright already gains little - at first the front burnt pale sand white)
        vec3 p = min(pulseLight(wpos, onBlock), vec3(1.2));
        float room = 1.0 - luma(color);
        color += p * (color * 0.55 + 0.06) * (0.25 + 0.75 * room);
    }
    color += hazeWarm * (color + 0.15);

    // the front of a shock wave, a little brighter
    color += shockLit * 0.06;
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
