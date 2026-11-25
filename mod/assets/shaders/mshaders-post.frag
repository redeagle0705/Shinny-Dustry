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
uniform float u_gameLit;       // 1: the game drew its own lighting into the picture (a dark map)
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
uniform float u_rain;          // how much it rains on the blocks now (Wetness.raining): drops splash on wet blocks
uniform float u_unitWet;       // how wet the units are (Wetness.wet): they are out in the rain
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
uniform vec4 u_heats[24];      // hot things in view: x, y in world units, how far the haze reaches, how hot (0 .. 1); reach 0 = not in use
uniform float u_heat;          // strength of the heat haze, 0 = none
uniform float u_shock;         // strength of the shock waves, 0 = none
uniform float u_flash;         // strength of the flashes of explosions, 0 = none
uniform float u_spare;         // 0 = every colour gets the saturation and contrast above, 1 = colours that are vivid already are spared (set on Erekir only)
uniform float u_cloud;         // how much the clouds seen from high up show (Clouds.java): 0 at a working zoom, up to 1 zoomed all the way out
uniform float u_cloudCover;    // how much of the sky they cover, 0 .. 1 (more in rain or snow)
uniform vec2 u_cloudShift;     // how far they have drifted, world units
uniform float u_dust;          // how thick the dust of a sandstorm hangs in the air (Skies.dust), 0 .. 1
uniform vec2 u_dustShift;      // how far that dust has blown with the wind, world units
uniform float u_spore;         // how thick the spores of a spore storm hang in the air, thinned as the camera comes in (Skies)
uniform float u_sporeGlow;     // how many of them glow: as thick as they hang, at any zoom
uniform vec2 u_sporeShift;     // how far they have drifted, world units
uniform float u_fog;           // how thick the fog of a fog weather is, 0 .. 1 (Skies.fogAir)
uniform vec2 u_fogShift;       // how far it has crept along, world units

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
    // straight-edged blocks)
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
    float weight = 0.0;
    for(int i = 0; i < 24; i++){
        vec4 h = u_heats[i];
        // (the places in use come first: past the first empty one there are no more)
        if(h.z < 0.5) break;
        // most of it in the air above the hot thing, less over the thing itself (at first it bent the block like
        // jelly)
        vec2 to = world - h.xy - vec2(0.0, h.z * 0.45);
        to.y *= 0.8;
        float d = length(to) / (h.z * 1.1);
        if(d >= 1.0) continue;
        weight += (1.0 - smoothstep(0.15, 1.0, d)) * h.w;
    }
    if(weight <= 0.0) return uv;
    // the waves are the same for every hot place: worked out once, by how much heat there is here
    float t = u_time;
    vec2 p = world / 2.4;
    vec2 push = vec2(sin(p.y * 1.7 - t * 8.0 + sin(p.x * 0.9 + t * 2.3)),
                     0.5 * cos(p.x * 1.5 - t * 6.3 + sin(p.y * 1.1 - t * 3.1))) * weight;
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

// Drops of rain splashing on a wet block: here and there a bright speck that comes and is gone within a moment,
// each speck at a place of its own, more of them the harder it rains. "world" in world units (a sprite pixel is a quarter).
float rainDrops(vec2 world, float time, float rain){
    vec2 cell = floor(world / 1.5);
    // (no sine: far from the map's origin its precision runs out - cloudHash)
    float h1 = cloudHash(cell);
    float h2 = cloudHash(cell + 17.3);
    if(h1 > rain * 0.22) return 0.0;
    float phase = fract(time * (0.55 + 0.5 * h2) + h2 * 7.31);
    // the speck: in the middle of its cell, round, at once bright and soon gone
    vec2 inCell = world / 1.5 - cell - 0.5;
    float speck = 1.0 - smoothstep(0.18, 0.42, length(inCell));
    return speck * smoothstep(0.0, 0.03, phase) * (1.0 - smoothstep(0.03, 0.16, phase));
}

// Rain on a sloping face, as it really goes: a drop sits a while, then slides a little way down in a jerk and stops
// again; its path wanders from side to side; a thin wet trail stays behind it and fades; here and there a bead just
// hangs. (Straight streaks all sliding one way at one pace looked wrong - "doesn't it go a bit, get stuck, twist?")
// "down" = the way down the face (length 1), world units. Above 0 a bright bead or trail, below 0 its darker rim.
float wander(float x, float seed){
    float i = floor(x), f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(cloudHash(vec2(i, seed)), cloudHash(vec2(i + 1.0, seed)), f) - 0.5;
}

float bead(vec2 at){
    float d = length(at);
    float body = 1.0 - smoothstep(0.22, 0.40, d);
    float rim = smoothstep(0.30, 0.42, d) * (1.0 - smoothstep(0.42, 0.56, d));
    return body - rim * 0.45;
}

// A bead that has run off its slope onto a flat part: it no longer stands up as a drop (no bright body, no rim),
// it spreads into the film of water as a faint flat patch. (Drawn as the bead made larger, it looked like one big drop.)
float soakPatch(vec2 at){
    return (1.0 - smoothstep(0.15, 0.55, length(at))) * 0.30;
}

// One lane of running water at this point. Returns its wet trail here; "local" = where this point lies from the lane's
// bead (in the bead's own measure: far away = no bead near), "center" = the bead's middle, world units. The caller
// decides whether the bead shows: it is drawn whole while its middle is on its slope (cut off at the edge of the
// sloping face it looked chopped off), and spreads out and fades for a little way past the foot of the slope.
float runoffLane(vec2 world, vec2 down, float time, out vec2 local, out vec2 center){
    vec2 side = vec2(-down.y, down.x);
    const float W = 1.6;                          // how far apart the lanes are, world units
    float across = dot(world, side) / W, along = dot(world, down);
    float lane = floor(across);
    float h = cloudHash(vec2(lane, 3.1)), h2 = cloudHash(vec2(lane, 9.7)), h3 = cloudHash(vec2(lane, 5.3));
    float kind = cloudHash(vec2(lane, 13.9));
    local = vec2(99.0);
    center = world;
    if(h >= 0.6) return 0.0;
    // the path winds: a slow sway and a smaller quick one
    float path = 0.5 + wander(along / 2.4, lane) * 0.42 + wander(along / 0.9, lane + 50.0) * 0.14;
    float d = (fract(across) - path) * W;
    // Each lane has a drop of its own kind ("can the drops differ - one that runs down fast?"):
    //   a runner (some 15%) slides down without stopping, drawn out long, a long trail behind it;
    //   a heavy one (some 20%) is bigger, waits longer and then slides further;
    //   the rest stop and go, each its own size.
    // A drop that slides is drawn out into a tear, round in front and thin behind; at rest it is round.
    float size, slid, moving, trailLen, trailK;
    if(kind < 0.15){
        size = 0.8 + 0.25 * h3;
        slid = time * (2.4 + 2.0 * h3) + h2 * 30.0;
        moving = 1.0;
        trailLen = 6.0 + 5.0 * h3;
        trailK = 0.5;
    }else{
        bool heavy = kind < 0.35;
        size = heavy ? 1.1 + 0.15 * h3 : 0.7 + 0.35 * h3;
        // stop and go: each stretch of time it waits, then slides (each time a different wait and pace)
        float u = time * (heavy ? 0.14 + 0.12 * h3 : 0.22 + 0.30 * h3) + h2 * 5.0;
        float k = floor(u), f = fract(u);
        float wait = (heavy ? 0.55 : 0.35) + (heavy ? 0.3 : 0.5) * cloudHash(vec2(lane, k + 0.5));
        float go = smoothstep(wait, 1.0, f);
        slid = (k + go) * (heavy ? 3.0 + 2.0 * h : 1.4 + 1.6 * h);
        // how fast it is going now, 0 .. 1 (the slope of that smoothstep)
        float x = clamp((f - wait) / (1.0 - wait), 0.0, 1.0);
        moving = 4.0 * x * (1.0 - x);
        trailLen = heavy ? 4.0 + 4.0 * h3 : 2.5 + 4.0 * h3;
        trailK = heavy ? 0.45 : 0.35;
    }
    float stretch = 10.0 + 8.0 * h2;              // one drop to each stretch of the lane
    float da = mod(along - slid + stretch * 0.5, stretch) - stretch * 0.5;
    // drawn out along the way down while it moves; the part behind its middle the more so (a tear)
    float tear = 1.0 + 0.9 * moving;
    float along2 = da * 0.85 / (size * (da < 0.0 ? tear * (1.0 + 0.5 * moving) : 1.0 + 0.15 * moving));
    local = vec2(along2, d / (size * (1.0 - 0.15 * moving)));
    center = world - down * da - side * d;
    // the wet trail up the path it came down, fading
    if(da < -0.2 * size && da > -trailLen) return (1.0 - smoothstep(0.05, 0.16, abs(d))) * (1.0 + da / trailLen) * trailK;
    return 0.0;
}

// Beads that just sit, on sloping and flat faces alike.
float restingBeads(vec2 world){
    vec2 cell = floor(world / 2.6);
    if(cloudHash(cell + 71.0) >= 0.10) return 0.0;
    vec2 at = (vec2(cloudHash(cell + 3.3), cloudHash(cell + 8.8)) * 0.6 + 0.2 + cell) * 2.6;
    return bead((world - at) * 1.15) * 0.8;
}

// Where water stands on a flat face: blotches that spread the deeper it is (0 = none .. 1 = the whole face).
float puddleAt(vec2 world, float depth){
    vec2 p = world / 3.2, i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float big = mix(mix(cloudHash(i), cloudHash(i + vec2(1.0, 0.0)), f.x), mix(cloudHash(i + vec2(0.0, 1.0)), cloudHash(i + vec2(1.0, 1.0)), f.x), f.y);
    p = world / 1.1; i = floor(p); f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float small = mix(mix(cloudHash(i + 31.0), cloudHash(i + vec2(32.0, 31.0)), f.x), mix(cloudHash(i + vec2(31.0, 32.0)), cloudHash(i + vec2(32.0, 32.0)), f.x), f.y);
    float n = big * 0.7 + small * 0.3;
    float th = 1.05 - depth * 0.95;
    return smoothstep(th, th + 0.08, n);
}

// How much the face at that place (screen texture coordinates) is of the tone that slopes the given way: 1 = towards
// the upper right (a light tone), -1 = the other way (a dark tone). As the glint below guesses it.
float faceTone(vec2 at, float way){
    vec2 px = 0.25 / u_resolution;
    float r = 6.0, k = 4.24;
    float around = (
        lumaAt(at + vec2(r, 0.0) * px) + lumaAt(at + vec2(-r, 0.0) * px) +
        lumaAt(at + vec2(0.0, r) * px) + lumaAt(at + vec2(0.0, -r) * px) +
        lumaAt(at + vec2(k, k) * px) + lumaAt(at + vec2(-k, k) * px) +
        lumaAt(at + vec2(k, -k) * px) + lumaAt(at + vec2(-k, -k) * px)) / 8.0;
    return smoothstep(0.20, 0.45, (lumaAt(at) - around) / 0.09 * way);
}

// 0 on the dark outline the game draws around units and turrets (Pal.darkOutline, 2d2f39 - in shade a darker shade of
// it), 1 elsewhere: no drops on an outline, which is a line drawn around the thing, not a surface of it.
float notOutline(vec3 c){
    const vec3 line = vec3(0.176, 0.184, 0.224);
    float k = clamp(luma(c) / 0.186, 0.3, 1.15);
    return smoothstep(0.025, 0.06, length(c - line * k));
}
// Is a unit drawn at that place (screen texture coordinates)? (Scene.coverUnit: red 1, green above 0.85 in u_surface)
float unitAt(vec2 at){
    vec4 s = texture2D(u_surface, at);
    return s.a > 0.5 && s.r > 0.97 && s.g > 0.85 && s.b > 0.45 ? 1.0 : 0.0;
}

// At the edge of the map's own walls: which way the face leans at that place (world units) - outwards in a band along
// each side of its tile that faces open ground (alpha 64 + 1 up, 2 right, 4 down, 8 left; Wetness), flat elsewhere.
vec2 wallTilt(vec2 p){
    vec4 k = texture2D(u_gloss, (floor((p + 4.0) / 8.0) + 0.5) * 8.0 / u_world);
    float a = k.a * 255.0;
    if(a < 63.5 || a > 100.0) return vec2(0.0);
    float n = floor(a - 64.0 + 0.5);
    vec2 f = fract((p + 4.0) / 8.0);
    const float band = 0.32;
    vec2 t = vec2(0.0);
    if(mod(n, 2.0) > 0.5) t.y += 1.0 - smoothstep(0.0, band, 1.0 - f.y);
    if(mod(floor(n / 2.0), 2.0) > 0.5) t.x += 1.0 - smoothstep(0.0, band, 1.0 - f.x);
    if(mod(floor(n / 4.0), 2.0) > 0.5) t.y -= 1.0 - smoothstep(0.0, band, f.y);
    if(mod(floor(n / 8.0), 2.0) > 0.5) t.x -= 1.0 - smoothstep(0.0, band, f.x);
    return t;
}
// Clouds seen from high up (Clouds.java): soft heaps some hundreds of world units across, made of value noise over
// five scales (sine-free, so they hold together far from the map's origin). 0 .. about 1.
float skyNoise(vec2 p){
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(cloudHash(i), cloudHash(i + vec2(1.0, 0.0)), f.x), mix(cloudHash(i + vec2(0.0, 1.0)), cloudHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float cloudDensity(vec2 p){
    vec2 q = p / 520.0;
    float n = 0.0, a = 0.5;
    for(int i = 0; i < 5; i++){
        n += a * skyNoise(q);
        q = q * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return n;
}
// Is there a block at that place (world units)? As "solid" below.
float blockAt(vec2 world){
    return smoothstep(0.42, 0.58, (1.0 - texture2D(u_mask, (world + 4.0) / u_world).r) / 0.71);
}

void main(){    vec2 uv = v_texCoords;
    if(u_pulse > 0.001) uv = waveWarp(uv, uv * u_resolution + u_campos);
    vec3 hazeWarm = vec3(0.0);
    if(u_haze > 0.001) uv = overdriveHaze(uv, uv * u_resolution + u_campos, hazeWarm);
    if(u_heat > 0.001) uv = heatWarp(uv, uv * u_resolution + u_campos);
    float shockLit = 0.0;
    if(u_shock > 0.001) uv = blastWarp(uv, uv * u_resolution + u_campos, shockLit);
    vec3 color = texture2D(u_texture, uv).rgb;
    vec2 world = uv * u_resolution + u_campos;
    // How bright drops of rain and running water show: by day the sun; at night they still catch the moon (not less
    // than this) and shine where a lamp lights them - by night they were all but gone ("at night the drops hardly show")
    // (a drop is a little mirror: it shows the moon as it shows the sun, as brightly - the night grading of the last
    // step darkens it enough as it is)
    float dropLight = 1.0;
    if(u_light > 0.001) dropLight += luma(texture2D(u_lightmap, uv).rgb) * 1.2 * u_light;

    // Blocks with a normal map (Surfaces.java) get their shading and their glint in the middle of the frame
    // (mshaders-shape.frag), under the units. Here they are only left out, so the glint below is not laid on twice.
    bool shaped = false;
    if(u_shape > 0.001){
        vec4 surface = texture2D(u_surface, uv);
        shaped = surface.a > 0.5;
        // A unit in the rain: wet too. Units are laid over that picture pure white (Scene.unitCover; no normal comes
        // out white). They move, so only what does not slide over them: a little darker, a sheen of sky that slides
        // as the camera moves, and drops splashing - no running water, no puddles.
        // (Scene.coverUnit: red 1 and green above 0.85, which no normal is; green = 0.86 + 0.14 * how large the unit
        // is; blue = 0.5 + half of how still it has kept)
        if(shaped && u_unitWet > 0.01 && u_glint > 0.001 && surface.r > 0.97 && surface.g > 0.85 && surface.b > 0.45){
            float w = u_unitWet;
            float still = clamp((surface.b - 0.5) * 2.0, 0.0, 1.0);
            // the drops on a small unit are smaller, as are their lanes and trails ("as small as the unit"): all of
            // it worked out in a world made larger by that much
            float size = clamp((surface.g - 0.86) / 0.14, 0.25, 1.0);
            vec2 uw = world / size;
            // A unit that has kept still a second: rain runs down it as down a block (on one that moves, water laid
            // out on the ground would slide over it). Which way a face slopes is guessed from the tones of the sprite.
            if(still > 0.01 && u_rain > 0.01){
                float run = restingBeads(uw) * 0.8;
                for(int s = 0; s < 2; s++){
                    float way = s == 0 ? 1.0 : -1.0;
                    vec2 down = baked * way;
                    vec2 local, center;
                    float trail = runoffLane(uw, down, u_time, local, center);
                    center *= size;
                    run += trail * faceTone(uv, way);
                    if(dot(local, local) < 1.8){
                        // Whether its middle is on a face sloping this way, read over the bead as a whole (its middle
                        // and four places around it): a unit's sprite has many small faces, and read at one point the
                        // bead popped in and out as its middle crossed from one to the next ("it appears with a pop and
                        // is gone with a pop")
                        float r = 0.35 * size;
                        float on = (faceTone((center - u_campos) / u_resolution, way)
                            + faceTone((center + vec2(r, 0.0) - u_campos) / u_resolution, way)
                            + faceTone((center - vec2(r, 0.0) - u_campos) / u_resolution, way)
                            + faceTone((center + vec2(0.0, r) - u_campos) / u_resolution, way)
                            + faceTone((center - vec2(0.0, r) - u_campos) / u_resolution, way)) / 5.0;
                        on = smoothstep(0.0, 0.7, on) * unitAt((center - u_campos) / u_resolution) * notOutline(texture2D(u_texture, (center - u_campos) / u_resolution).rgb);
                        run += mix(bead(local), soakPatch(local), 1.0 - on) * (0.25 + 0.75 * on);
                    }
                }
                color += vec3(0.80, 0.86, 0.95) * run * 0.32 * w * u_rain * dropLight * still * notOutline(color);
            }
            color *= 1.0 - 0.10 * w;
            vec2 eye = u_campos + u_resolution * 0.5;
            float sky = pow(0.5 + 0.5 * sin(dot(world - eye * 0.35, u_sunDir) / 21.0 * 6.2832), 6.0);
            color += color * sky * 0.20 * w * u_day * u_glint;
            if(u_rain > 0.01) color += vec3(0.80, 0.86, 0.95) * rainDrops(uw, u_time, u_rain) * 0.30 * w * dropLight * notOutline(color);
        }
    }

    if(u_glint > 0.001 && !shaped){
        float onMask = (1.0 - texture2D(u_mask, (world + 4.0) / u_world).r) / 0.71;
        float block = smoothstep(0.55, 0.90, onMask);
        // (the whole top of a block, out to the edges of its tiles: where a film of rain lies - the glint above keeps off the edges)
        float solid = smoothstep(0.42, 0.58, onMask);
        if(solid > 0.01 && u_shaped > 0.5){
            // Where such a block leaves its tiles empty the ground shows, and the ground gets no glint.
            // (It did: a paler square around every power node.)
            vec2 at = (world + 4.0) / 8.0, cell = floor(at);   // in tiles; tile n covers n .. n + 1
            vec4 shape = texture2D(u_shapes, (cell + 0.5) * 8.0 / u_world) * 255.0;
            if(shape.a > 7.0){
                float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
                vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));
                float inside = smoothstep(0.35, 0.65, texture2D(u_outlines, (shape.rg + clamp(at - corner, 0.04, size - 0.04)) / 64.0).g);
                block *= inside;
                solid *= inside;
            }
        }
        if(solid > 0.01){
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
            // (red: the gloss of what the block is made of; green: how wet with rain it is, Wetness.java)
            vec4 kind = u_glossy > 0.5 ? texture2D(u_gloss, (floor((world + 4.0) / 8.0) + 0.5) * 8.0 / u_world) : vec4(0.5, 0.0, 0.0, 1.0);
            float much = u_glossy > 0.5 ? kind.r * 2.0 : 1.0;
            // A block wet with rain: a film of water lies on it, and water shines whatever is under it - as a half
            // polished block at the least, more so where the block is glossy itself. A little darker, as a wet thing is.
            float wet = kind.g * solid;
            much = mix(much, max(much * 1.25, 1.5), wet);
            color *= 1.0 - 0.12 * wet;
            // the drops that splash on it while it rains
            if(wet > 0.02 && u_rain > 0.01) color += vec3(0.80, 0.86, 0.95) * rainDrops(world, u_time, u_rain) * 0.30 * wet * dropLight * notOutline(color);
            // Where the face slopes (a light or a dark tone) the rain runs down it - towards the upper right on a light
            // face, the other way on a dark one; where it is flat the water stands (blue: how deep, Wetness.pool), in
            // puddles that spread and mirror more clearly the deeper they are.
            // At the edge of the map's own walls (alpha 64 .. 79: which of its sides face open ground, Wetness) the
            // slope is not guessed from the tones: a band along the open sides leans outwards - the bevel such walls
            // are drawn with - and the rest is flat ("the inside of these joined walls is all flat"), so the rain runs
            // off the edge and stands on the flat.
            float aByte = kind.a * 255.0;
            bool wallEdge = aByte > 63.5 && aByte < 100.0;
            vec2 bevel = wallEdge ? wallTilt(world) : vec2(0.0);
            float slope = wallEdge ? min(length(bevel), 1.0) : max(lit, away);
            // (alpha 128 .. 254: a turret or mass driver, whose head turns - running water and puddles laid out on the
            // ground flickered over it as it did; they show only once it has kept still a while: not .. quite still)
            float steady = aByte > 127.5 && aByte < 254.5 ? clamp((aByte - 128.0) / 126.0, 0.0, 1.0) : 1.0;
            if(wet > 0.02 && u_rain > 0.01 && steady > 0.01){
                float run = restingBeads(world) * 0.8;
                if(wallEdge){
                    if(slope > 0.05){
                        vec2 down = bevel / length(bevel);
                        vec2 local, center;
                        float trail = runoffLane(world, down, u_time, local, center);
                        run += trail * slope;
                        if(dot(local, local) < 1.8){
                            // whole while its middle is on the bevel; it runs off the edge onto the ground beyond
                            float on = smoothstep(0.1, 0.4, dot(wallTilt(center), down)) * blockAt(center);
                            float spread = 1.0 - on;
                            run += mix(bead(local), soakPatch(local / (1.0 + spread * 0.8)), spread) * on;
                        }
                    }
                }else{
                    // the two ways a face in a sprite can slope: to the upper right (light tone), the other way (dark tone)
                    for(int s = 0; s < 2; s++){
                        float way = s == 0 ? 1.0 : -1.0;
                        vec2 down = baked * way;
                        vec2 local, center;
                        float trail = runoffLane(world, down, u_time, local, center);
                        // the trail only on a face sloping this way
                        run += trail * (s == 0 ? lit : away);
                        if(dot(local, local) < 1.8){
                            // the bead: whole while its middle is on such a face; just past the foot of the slope (that
                            // face a little way up from it) it spreads into the film of water and fades
                            vec2 cuv = (center - u_campos) / u_resolution;
                            float on = faceTone(cuv, way) * blockAt(center) * notOutline(texture2D(u_texture, cuv).rgb);
                            float arrived = 0.0;
                            if(on < 0.9){
                                float up1 = faceTone((center - down * 1.0 - u_campos) / u_resolution, way);
                                float up2 = faceTone((center - down * 2.0 - u_campos) / u_resolution, way);
                                arrived = max(up1 * 0.75, up2 * 0.4) * blockAt(center);
                            }
                            float spread = 1.0 - on;
                            run += mix(bead(local), soakPatch(local / (1.0 + spread * 0.8)), spread) * max(on, arrived * (1.0 - 0.5 * spread));
                        }
                    }
                }
                color += vec3(0.80, 0.86, 0.95) * run * 0.32 * wet * u_rain * dropLight * steady * notOutline(color);
            }
            float puddle = kind.b * solid > 0.02 && steady > 0.01 ? puddleAt(world, kind.b) * (1.0 - slope) * solid * steady : 0.0;
            much = mix(much, 2.0, puddle);
            color *= 1.0 - 0.10 * puddle;
            shine *= block * u_glint * much;
            // Polished metal mirrors the sky: the streak that slides over the blocks with a normal map when the camera
            // moves (mshaders-shape.frag) lies over these too - fainter, because here the faces are only guessed.
            float gloss = much * 0.5, mirror = gloss * sqrt(gloss);
            if(mirror > 0.003 && u_day > 0.001){
                vec2 eye = u_campos + u_resolution * 0.5;
                float along = dot(world - eye * 0.35 + baked * (lit - away) * 6.0, u_sunDir) / 21.0;
                float sky = pow(0.5 + 0.5 * sin(along * 6.2832), mix(3.0, 12.0, gloss));
                color += color * (sky * 0.13 * mirror * u_day * block * u_glint);
                // a puddle is a mirror lying flat: the sky shows in it clearly, more so the deeper it is
                if(puddle > 0.01) color += (color * 0.5 + vec3(0.10, 0.12, 0.15)) * sky * puddle * kind.b * 0.55 * u_day;
            }
            // and the lamps shine in it
            if(puddle > 0.01 && u_aimed > 0.5){
                vec2 a = texture2D(u_aim, uv).rg * 2.0 - 1.0;
                color += vec3(1.0, 0.97, 0.9) * min(length(a) * 2.5, 1.0) * puddle * kind.b * 0.22;
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

    // The dust of a sandstorm hanging in the air: a haze in streaks and swirls, blown along with the wind, between the
    // eye and everything - the colours lose some of their strength and contrast, and the sand colour lies over them,
    // thicker in some places than others (a yellow tint alone looked like a filter, "that's just yellow dust").
    if(u_dust > 0.001){
        vec2 dp = world - u_dustShift;
        float n = skyNoise(dp / 150.0) * 0.65 + skyNoise(dp / 48.0 + 3.1) * 0.35;
        float veil = u_dust * (0.12 + 0.26 * n);
        color = mix(color, vec3(luma(color)), 0.22 * u_dust);
        vec3 dustColour = vec3(0.80, 0.68, 0.50) * (0.35 + 0.65 * u_day);
        color = mix(color, dustColour, veil);
    }
    // A spore storm: a slow violet murk in heaps and swirls, and here and there a spore glowing as it drifts - soft
    // violet points that bob and pulse, faint by day, bright by night.
    // Where a lamp shines into the storm the spores in the air catch its light: a soft violet halo around every
    // light, and more of the drifting spores glow there, brighter (spore storm option 4). The light: our light map
    // on a bright map; on a dark map the game has drawn the lamps into the picture itself, so what is bright in the
    // dark is taken as lamplight.
    vec3 sporeLamp = vec3(0.0);
    if(u_sporeGlow > 0.001 || u_fog > 0.001){
        if(u_light > 0.001) sporeLamp = (vec3(1.0) - exp(-texture2D(u_lightmap, uv).rgb * 1.4)) * min(u_light, 1.5);
        // (only where the game lit the map: on a bright map at the mod's own night, or with the lights off, sunlit
        // ground passed for lamplight everywhere; and only what stands out above the ground the darkness leaves lit -
        // on a mildly dark map pale floor passed for it)
        else if(u_gameLit > 0.5) sporeLamp = color * smoothstep(u_day * 0.6 + 0.04, u_day * 0.6 + 0.40, luma(color)) * (1.0 - u_day) * 1.5;
    }
    if(u_spore > 0.001 || u_sporeGlow > 0.001){
        vec2 sp = world - u_sporeShift;
        float n = skyNoise(sp / 110.0) * 0.6 + skyNoise(sp / 37.0 + 5.3) * 0.4;
        float thick = smoothstep(0.3, 0.8, n);
        if(u_spore > 0.001){
            float veil = u_spore * (0.10 + 0.30 * thick);
            color = mix(color, vec3(0.48, 0.38, 0.72) * (0.3 + 0.7 * u_day), veil);
        }
        float lampLit = luma(sporeLamp);
        if(lampLit > 0.002){
            // the lamp's own colour, drawn towards the violet of the spores; stronger the darker it is around, and
            // where the murk hangs thicker (it stays with the storm when zoomed in: halos, not haze)
            vec3 halo = mix(sporeLamp, vec3(0.62, 0.45, 1.0) * lampLit, 0.75);
            color += halo * u_sporeGlow * (0.35 + 0.65 * thick) * (0.30 + 0.70 * (1.0 - u_day)) * 0.22;
        }
    }
    if(u_sporeGlow > 0.001){
        float lampLit = min(luma(sporeLamp) * 3.0, 1.0);
        vec2 gp = world - u_sporeShift * 1.4;
        vec2 cell = floor(gp / 14.0);
        float h = cloudHash(cell + 91.0);
        if(h < 0.35 * u_sporeGlow * (1.0 + 0.8 * lampLit)){
            vec2 at = (cell + 0.5 + (vec2(cloudHash(cell + 2.7), cloudHash(cell + 6.1)) - 0.5) * 0.6) * 14.0;
            at += vec2(sin(u_time * 0.7 + h * 40.0), cos(u_time * 0.5 + h * 31.0)) * 2.5;
            float d = length(gp - at);
            float pulse = 0.6 + 0.4 * sin(u_time * (1.5 + h * 3.0) + h * 60.0);
            float glow = exp(-d * d / 1.2) * pulse;
            color += vec3(0.75, 0.50, 1.0) * glow * (0.25 + 0.75 * (1.0 - u_day) + 0.5 * lampLit) * 0.9;
        }
    }
    // Fog: a soft pale murk in slow heaps over everything. Where a light falls it thins - a pocket of sight round each
    // lamp, the lamp itself glowing in it - and where none does it thickens, so out in the dark the ground is all but
    // gone ("around places with no light the fog gets thicker and you can't see properly"); by day the sun lights it
    // all, and it is a milky haze, thicker away from the lamps only a little.
    if(u_fog > 0.001){
        vec2 fp = world - u_fogShift;
        float n = skyNoise(fp / 150.0) * 0.6 + skyNoise(fp / 48.0 + 7.1) * 0.4;
        float heap = smoothstep(0.25, 0.85, n);
        float lit = clamp(luma(sporeLamp) * 2.5, 0.0, 1.0);
        float unlit = (1.0 - lit) * (0.35 + 0.65 * (1.0 - u_day));
        // Distance (it was "a little thin" - a real fog: "close by fairly clear, a little further off and it is gone"):
        // seen from the middle of the screen, a few tiles round it stay clear, then the fog closes in fast - at 100 %
        // half gone some 14 tiles out, all but gone at 22. Its edge is ragged with the heaps, and a thicker fog
        // closes in nearer. (Zoomed out, the view is a clear patch in a white sea.)
        vec2 eye = u_campos + u_resolution * 0.5;
        float fd = length(world - eye);
        float fk = max(u_fog, 0.05);
        float r0 = 36.0 / pow(fk, 0.7), len = 75.0 / fk * (0.7 + 0.6 * n);
        float far = 1.0 - exp(-pow(max(fd - r0, 0.0) / len, 1.6));
        // (near: the old soft murk, lighter - the distance does the hiding now)
        float near = u_fog * (0.08 + 0.18 * heap + 0.30 * unlit);
        // (a lamp clears it as far as it is the light there: at night all the way, by day little - at noon every lamp
        // cut a clear disc out of the haze; far off a lamp is a glow in it, not a hole)
        float night = 0.3 + 0.7 * (1.0 - u_day);
        near *= 1.0 - 0.75 * lit * night;
        far *= 1.0 - 0.45 * lit * night;
        float veil = 1.0 - (1.0 - clamp(near, 0.0, 0.9)) * (1.0 - far);
        vec3 fogCol = mix(vec3(0.20, 0.22, 0.27), vec3(0.80, 0.83, 0.86), u_day);
        // (a touch lighter where it is deepest, as a wall of fog is)
        fogCol = mix(fogCol, fogCol * 1.06 + 0.02, far * heap);
        color = mix(color, fogCol, clamp(veil, 0.0, 0.97));
        // the lamps glow in it: their light, a little paler, spread through the fog
        if(lit > 0.002) color += mix(sporeLamp, vec3(luma(sporeLamp)), 0.35) * min(u_fog, 1.0) * (0.45 + 0.55 * heap) * 0.30 * night;
    }
    // Clouds, zoomed all the way out: their shadows on the ground first - the cloud that shades a point lies towards
    // the sun from it, high up - then the clouds themselves over everything, lit on the side towards the sun.
    if(u_cloud > 0.001){
        vec2 cp = world + u_cloudShift;
        float lo = 0.60 - u_cloudCover * 0.32, hi = lo + 0.28;
        float shade = smoothstep(lo, hi, cloudDensity(cp + u_sunDir * 110.0));
        color *= 1.0 - shade * 0.20 * u_cloud * u_day;
        float d = cloudDensity(cp);
        float a = smoothstep(lo, hi, d);
        if(a > 0.001){
            // where the cloud thins out towards the sun it is lit; where it thickens that way, in its own shade
            float lit = clamp((d - cloudDensity(cp + u_sunDir * 30.0)) * 5.0 + 0.6, 0.0, 1.0);
            vec3 day = mix(vec3(0.66, 0.70, 0.78), vec3(0.98, 0.98, 0.99), lit);
            vec3 night = mix(vec3(0.10, 0.12, 0.18), vec3(0.22, 0.26, 0.36), lit);
            // heavier with rain: greyer and thicker
            vec3 cloud = mix(night, day, u_day) * (1.0 - 0.25 * u_cloudCover * (1.0 - lit));
            color = mix(color, cloud, a * u_cloud * (0.45 + 0.2 * u_cloudCover));
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
        // (twice what it was: on the ground the front came to some 10 of 255 - "the overdrive's wave seems gone")
        color += p * (color * 1.1 + 0.12) * (0.3 + 0.7 * room);
    }
    color += hazeWarm * (color + 0.15);

    // the front of a shock wave, a little brighter
    color += shockLit * 0.06;
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
