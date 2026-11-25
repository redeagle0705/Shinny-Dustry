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
uniform sampler2D u_kinds;   // one texel per tile, nearest: the upper 3 bits of red = the kind; the rest = where the tile's ore sprite is (below)
uniform vec2 u_tiles;        // size of the map in tiles; 0 = no such picture, everything is drawn as it is
uniform sampler2D u_ores;    // the game's sprite sheet with the ore sprites on it
uniform vec2 u_sheet;        // its size in pixels; 0 = none, the ores are shaded as part of the ground
uniform float u_oreSize;     // how many pixels across an ore sprite is (it covers one tile)

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
uniform float u_rain;        // how much rain there is, 0 = none (Skies.java)
uniform float u_pool;        // how much water stands in the puddles (Skies.puddles): they fill while it rains and stay a while after
uniform float u_snow;        // how much snow lies on the ground, 0 = none
uniform float u_spore;       // how many spores of a spore storm have settled on the ground (Skies.spores), 0 = none
uniform vec2 u_sand;         // x: how much sand has drifted onto the ground, y: how hard the sandstorm blows now
uniform float u_sandPile;    // how much sand piles up against the blocks, 0 .. 2 (setting; 1 = as made)
uniform vec2 u_wind;         // where the sandstorm blows towards, length 1
uniform sampler2D u_tracks;  // tracks of ground units (Tracks.java): red = how deep
uniform float u_hasTracks;   // 0 = none; otherwise how strongly they show
uniform sampler2D u_melt;    // where beams melted the snow (SnowCover): two pixels a tile, red = how far melted
uniform float u_hasMelt;     // 0 = no such picture
uniform vec4 u_warm[16];     // hot buildings that have melted their snow: x, y, reach, how far melted (SnowCover.java); reach 0 = not in use
uniform sampler2D u_solid;   // one texel per tile, linear filtered: red = a solid block stands there (Occluders.java)
uniform float u_hasSolid;    // 0 = no such picture
uniform sampler2D u_pileField; // the shape of the sand piles over the view, worked out once a frame (Skies.makeField)
uniform float u_hasField;    // 0 = not made: worked out here, per pixel
uniform vec4 u_fieldRect;    // the world rectangle it covers: x, y, width, height

varying vec2 v_texCoords;

const vec2 wind = vec2(0.94, 0.34);   // the wind blows towards the right and a little up
const float SOFT_RELIEF = 0.45;       // how much of the relief sand and grass keep
const float ORE_LIFT = 0.2;           // how far an ore stands above the ground it lies on, in the units of the relief (brightness)

float noise(vec2 p){
    return texture2D(u_noise, p).r;
}

// a hash without sine (precise far from the map's origin)
float hash1(vec2 p){
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float kindAt(vec2 world){
    return floor(floor(texture2D(u_kinds, (world + 4.0) / (8.0 * u_tiles)).r * 255.0 + 0.5) / 32.0 + 0.01);
}

// 1 where a tile takes puddles (dry ground other than snow and hot rock)
float puddleGround(float k){
    return (k > 0.5 && k < 2.5) || (k > 3.5 && k < 5.5) || k > 6.5 ? 1.0 : 0.0;
}

// Rain on this ground (drawn here, in the ground's own layer, so it lies under the blocks, units and shots - in the
// last step of the frame it lay over everything): wet darkening; puddles in the hollows, thinning out towards ground
// that takes none (water, snow, hot rock); rings where drops fall in them.
vec3 rainOn(vec3 color, vec2 world, float kind){
    float r = u_rain, p = u_pool;
    float g = dot(color, vec3(0.299, 0.587, 0.114));
    // (wet while it rains, and still damp while puddles stand)
    color = mix(color, mix(vec3(g), color, 1.12) * 0.86, max(r, p * 0.7));
    if(kind > 2.5 && kind < 3.5) return color;   // snow: only wet
    vec2 t = (world + 4.0) / 8.0 - 0.5, base = floor(t), f = fract(t);
    float a = puddleGround(kindAt(base * 8.0)), b = puddleGround(kindAt((base + vec2(1.0, 0.0)) * 8.0)),
        c = puddleGround(kindAt((base + vec2(0.0, 1.0)) * 8.0)), d = puddleGround(kindAt((base + vec2(1.0, 1.0)) * 8.0));
    f = f * f * (3.0 - 2.0 * f);
    float room = mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
    if(room <= 0.01) return color;
    float n = noise(world / 1100.0) * 0.55 + noise(world / 420.0 + 0.37) * 0.3 + noise(world / 170.0 + 0.71) * 0.15;
    // The puddles follow how much water stands (p), not the rain of this moment: they spread the longer it rains, and
    // stay when it stops, shrinking as they dry. (They were as large as the rain was hard, and gone when it stopped.)
    float puddle = smoothstep(0.60 - 0.08 * p + (1.0 - room) * 0.3, 0.67 - 0.08 * p + (1.0 - room) * 0.3, n) * min(p * 1.6, 1.0);
    // Drops on the ground between the puddles: a small dark wet spot that comes and soaks away, a new place each time.
    // (With rings only in the puddles, which lie still, the rain seemed to fall always in the same places.)
    {
        vec2 sp = world / 6.0, sid = floor(sp), sf = fract(sp);
        float beat = u_time / 60.0 * 1.3 + hash1(sid + 41.0);
        float drop = floor(beat), ph = fract(beat);
        vec2 at = vec2(hash1(sid + drop * 1.31 + 7.7), hash1(sid - drop * 0.73 + 2.9)) * 0.7 + 0.15;
        float d = length(sf - at) * 6.0;
        float spot = (1.0 - smoothstep(0.35, 0.75 + ph * 0.5, d)) * (1.0 - ph) * step(hash1(sid + drop * 0.37 + 1.3), 0.25 + 0.45 * r);
        color *= 1.0 - spot * 0.16 * r * room * (1.0 - puddle);
    }
    if(puddle <= 0.001) return color;
    vec3 sky = vec3(0.55, 0.62, 0.72);
    color = mix(color, color * 0.5 + sky * 0.2 * clamp(g * 2.2, 0.0, 1.0), puddle * (0.5 + 0.3 * p));
    // The deeper the water stands, the clearer it mirrors: a streak of sky that slides over it as the camera moves (it
    // is tied to the ground only in part, as a reflection is), with the sun in it.
    {
        vec2 eye = u_campos + u_resolution * 0.5;
        // (broad and soft, a little wavy where the water ripples: a sharp, evenly spaced line read as stripes of light
        // laid over the grass, not as sky in water)
        float along = (dot(world - eye * 0.35, u_sunDir) + (noise(world / 90.0) - 0.5) * 18.0) / 70.0;
        float wave = 0.5 + 0.5 * sin(along * 6.2832);
        vec3 skyLight = vec3(0.62, 0.70, 0.82) * (0.75 + 0.35 * wave);
        // what the water shows is the sky, more of it the deeper it is; the ground under it shows less
        color = mix(color, skyLight, (0.10 + 0.30 * pow(wave, 3.0)) * puddle * p);
        // the sun, a soft glow in the middle of the band
        color += vec3(1.0, 0.96, 0.88) * pow(wave, 12.0) * 0.12 * puddle * p;
    }
    float rim = smoothstep(0.0, 0.25, puddle) * (1.0 - smoothstep(0.25, 0.7, puddle));
    color += vec3(0.8, 0.85, 0.9) * rim * 0.05;
    // rings where drops fall: one now and then in each cell of 9 world units
    vec2 cellP = world / 9.0, id = floor(cellP), fr = fract(cellP);
    float ring = 0.0;
    float time = u_time / 60.0;
    for(int j = -1; j <= 0; j++){
        for(int i = -1; i <= 0; i++){
            vec2 cc = id + vec2(float(i), float(j));
            float h = hash1(cc), h2 = hash1(cc + 17.3);
            float beat = time * (0.9 + 0.6 * h2) + h;
            float phase = fract(beat), drop = floor(beat);
            // each drop lands somewhere else in its cell (with one fixed place per cell the rings came back to the
            // same spots over and over: "the rain seems to fall always in the same places")
            vec2 at = vec2(float(i), float(j)) + vec2(hash1(cc + drop * 1.71 + 3.1), hash1(cc - drop * 2.37 + 9.4)) * 0.8 + 0.1;
            float dd = length(fr - at) * 9.0;
            float x = (dd - phase * 3.5) / 0.45;
            ring += exp(-x * x) * (1.0 - phase) * step(hash1(cc + drop * 0.61 + 5.7), 0.35 + 0.4 * r);
        }
    }
    // (rings only while it rains)
    color += vec3(0.75, 0.82, 0.9) * min(ring, 1.0) * 0.22 * puddle * min(r * 3.0, 1.0);
    return color;
}

// how much of this place is ground of the given test, blended over the four tiles around it (so it thins out towards
// ground that takes none, instead of stopping at a tile's square edge)
float snowGround(float k){
    return k > 0.5 && (k < 5.5 || k > 6.5) ? 1.0 : 0.0;    // every dry ground but hot rock
}
float roomFor(vec2 world, bool snowTest){
    vec2 t = (world + 4.0) / 8.0 - 0.5, base = floor(t), f = fract(t);
    float k1 = kindAt(base * 8.0), k2 = kindAt((base + vec2(1.0, 0.0)) * 8.0),
        k3 = kindAt((base + vec2(0.0, 1.0)) * 8.0), k4 = kindAt((base + vec2(1.0, 1.0)) * 8.0);
    float a = snowTest ? snowGround(k1) : puddleGround(k1), b = snowTest ? snowGround(k2) : puddleGround(k2),
        c = snowTest ? snowGround(k3) : puddleGround(k3), d = snowTest ? snowGround(k4) : puddleGround(k4);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float glints(vec2 world, vec2 eye, float shift, float size, float drift, float lo, float hi);

// Snow lying on the ground: first a dusting on what stands higher (the lighter parts of the tile), then broad drifts
// that grow together until all is white. Not on water and other liquids, nor on hot rock (it melts there). On snow
// floor: fresh, whiter, more sparkle.
float tracksAt(vec2 world){
    if(u_hasTracks <= 0.001) return 0.0;
    return clamp(texture2D(u_tracks, (world + 4.0) / (8.0 * u_tiles)).r * u_hasTracks, 0.0, 1.0);
}

// How far the snow here has thawed around hot buildings (a ring around each, its edge broken up a little), 0 .. 1.
float thawAt(vec2 world){
    float thaw = 0.0;
    for(int i = 0; i < 16; i++){
        vec4 w = u_warm[i];
        if(w.z < 0.5) break;
        float d = length(world - w.xy) / w.z + (noise(world / 14.0) - 0.5) * 0.35 + (noise(world / 5.0) - 0.5) * 0.15;
        thaw = max(thaw, w.w * (1.0 - smoothstep(0.35, 1.0, d)));
    }
    if(u_hasMelt > 0.5){
        float m = texture2D(u_melt, (world + 4.0) / (8.0 * u_tiles)).r;
        thaw = max(thaw, smoothstep(0.15, 0.6, m + (noise(world / 9.0) - 0.5) * 0.25));
    }
    return thaw;
}

vec3 snowOn(vec3 color, vec2 world, float kind, vec2 eye, float fine){
    // ("can't the snow on the ground melt too?") - around hot buildings it thaws, the ground left a little darker, wet
    float thaw = thawAt(world);
    // (pushed aside where units walk: the ground shows through their tracks)
    float c = u_snow * (1.0 - thaw) * (1.0 - 0.85 * tracksAt(world));
    color *= 1.0 - thaw * 0.10 * min(u_snow * 2.0, 1.0);
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 fresh = vec3(0.90, 0.93, 0.98);
    float t = u_time / 60.0;
    if(kind > 2.5 && kind < 3.5){
        color = mix(color, fresh * (0.80 + 0.25 * lum), c * 0.35);
        color += glints(world, eye, 0.14, 8.0, t * 0.011, 0.36, 0.43) * 0.35 * c * fine;
        return color;
    }
    float room = roomFor(world, true);
    if(room <= 0.01) return color;
    float n = noise(world / 900.0) * 0.5 + noise(world / 330.0 + 0.37) * 0.3 + noise(world / 110.0 + 0.71) * 0.2;
    // what stands higher catches it first
    float field = n + (lum - 0.45) * 0.45;
    float th = 0.88 - c * 0.70 + (1.0 - room) * 0.6;
    float s = smoothstep(th - 0.07, th + 0.07, field);
    // a thin dusting everywhere it can lie
    float dust = min(c * 0.5, 0.25) * room;
    // snow keeps a little of the ground's light and dark, so the relief still shows through
    vec3 snowCol = fresh * (0.80 + 0.12 * clamp(lum * 1.6, 0.0, 1.0));
    color = mix(color, snowCol, max(s * 0.92, dust));
    // shade on the side away from the light at the edge of a drift
    float rim = s * (1.0 - s) * 4.0;
    color *= 1.0 - rim * 0.06;
    color += glints(world, eye, 0.14, 8.0, t * 0.011, 0.38, 0.45) * 0.30 * s * fine;
    return color;
}

// Spores settled by a spore storm: not a dusting as snow, but clumps, as moss grows - violet, darker in the middle of a
// clump, with a speck here and there; more and larger clumps the more have settled. Where snow could lie (roomFor).
vec3 sporesOn(vec3 color, vec2 world){
    float room = roomFor(world, true);
    if(room <= 0.01) return color;
    // burnt off around hot buildings and where beams have swept, as the snow melts there (the smoke rises from
    // SnowCover; at first heat did nothing to them: "the meltdown and the heat of turrets don't seem to touch them")
    room *= 1.0 - thawAt(world);
    if(room <= 0.01) return color;
    // (in large clumps: at first they came in small spots all over - "they should gather in bigger clumps")
    float n = noise(world / 160.0) * 0.65 + noise(world / 48.0 + 0.53) * 0.35;
    // (at most about a third of the ground: clumps, not a carpet - at first it all but covered the grass)
    float th = 0.92 - u_spore * 0.38 + (1.0 - room) * 0.5;
    float clump = smoothstep(th - 0.05, th + 0.08, n);
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 moss = mix(vec3(0.30, 0.20, 0.45), vec3(0.55, 0.42, 0.78), smoothstep(th, th + 0.25, n)) * (0.75 + 0.5 * lum);
    color = mix(color, moss, clump * 0.72);
    // specks: single spores lying apart from the clumps
    vec2 cell = floor(world / 2.0);
    float h = hash1(cell + 47.0);
    if(h < u_spore * 0.06 * room) color = mix(color, vec3(0.62, 0.48, 0.88), 0.55 * (1.0 - smoothstep(0.25, 0.5, length(world / 2.0 - cell - 0.5))));
    return color;
}
// Sandstorm: sand drifts piled up along the wind on the ground (they build up during the storm and are blown away
// slowly after), fast pale streaks of blowing sand running downwind with their shade under them, and dusty light.
vec3 sandOn(vec3 color, vec2 world, float kind, float fine){
    float c = u_sand.x, b = u_sand.y;
    float t = u_time / 60.0;
    vec2 w = u_wind;
    float along = dot(world, w), across = dot(world, vec2(-w.y, w.x));
    float lum = dot(color, vec3(0.299, 0.587, 0.114));
    vec3 dune = vec3(0.86, 0.72, 0.56);
    if(c > 0.001){
        float room = roomFor(world, true);
        if(room > 0.01){
            float field = noise(vec2(along / 800.0, across / 260.0)) * 0.6 + noise(vec2(along / 300.0, across / 95.0) + 0.3) * 0.4;
            float th = 1.0 - c * 0.38 + (1.0 - room) * 0.5;
            float s = smoothstep(th - 0.10, th + 0.06, field);
            vec3 sandCol = dune * (0.80 + 0.35 * clamp(lum * 1.6, 0.0, 1.0));
            // ripples across the wind on the drifts
            sandCol *= 1.0 + sin(along * 1.9635 + noise(world / 90.0) * 6.0) * 0.05 * fine;
            // Sand piles up against the blocks on the side the wind comes from: look downwind for a block - the nearer,
            // the deeper the sand - and a little to each side, so it wraps round the corners and tails off there.
            float pile = 0.0;
            if(u_hasSolid > 0.5 && u_hasField > 0.5){
                // the shape, worked out over the view once this frame (Skies.makeField: the same as below)
                pile = texture2D(u_pileField, (world - u_fieldRect.xy) / u_fieldRect.zw).r * u_sandPile;
                float streaks = noise(vec2(along / 70.0 - t * 0.25 * b, across / 6.0));
                pile = smoothstep(0.30, 0.48, pile * (0.85 + 0.3 * noise(world / 30.0)) + (streaks - 0.5) * 0.2 * (0.5 + b)) * room;
                pile *= smoothstep(0.0, 0.35, c);
            }else if(u_hasSolid > 0.5){
                // The shape (the maker's drawing, wind blowing down: an upside-down U round each block): a bank on the
                // face the wind blows onto, and from its corners two tails that run down both sides with the wind and
                // on past the block - none in the sheltered strip right behind it.
                vec2 side = vec2(-w.y, w.x);
                float cap = 0.0, tails = 0.0, lee = 0.0;
                // the bank: a block just downwind
                for(int k = 0; k < 5; k++){
                    float ang = (float(k) - 2.0) * 0.45;
                    vec2 dv = w * cos(ang) + side * sin(ang);
                    for(int m = 0; m < 2; m++){
                        float r = 3.0 + float(m) * 5.0;
                        cap = max(cap, texture2D(u_solid, (world + dv * r + 4.0) / (8.0 * u_tiles)).r * (1.0 - r / 15.0));
                    }
                }
                // the tails: a block beside, from a little ahead of here to well upwind
                for(int m = 0; m < 2; m++){
                    float r = 4.0 + float(m) * 4.5;
                    for(int k = 0; k < 5; k++){
                        float a = -4.0 + float(k) * 7.0;
                        float fall = (1.0 - r / 15.0) * (1.0 - max(a, 0.0) / 34.0);
                        tails = max(tails, fall * max(texture2D(u_solid, (world + side * r - w * a + 4.0) / (8.0 * u_tiles)).r,
                                                      texture2D(u_solid, (world - side * r - w * a + 4.0) / (8.0 * u_tiles)).r));
                    }
                }
                // the sheltered strip: a block straight upwind
                for(int k = 0; k < 4; k++){
                    float a = 3.0 + float(k) * 7.0;
                    lee = max(lee, texture2D(u_solid, (world - w * a + 4.0) / (8.0 * u_tiles)).r);
                }
                pile = max(cap, min(tails * 1.3, 1.0) * (1.0 - lee)) * u_sandPile;
                // its edge broken up by the noise, and streaked along the wind where the sand runs round the block
                float streaks = noise(vec2(along / 70.0 - t * 0.25 * b, across / 6.0));
                pile = smoothstep(0.30, 0.48, pile * (0.85 + 0.3 * noise(world / 30.0)) + (streaks - 0.5) * 0.2 * (0.5 + b)) * room;
                pile *= smoothstep(0.0, 0.35, c);
            }
            // the pile: lit sand, the ripples on it
            vec3 pileCol = dune * (0.95 + 0.12 * clamp(lum * 1.6, 0.0, 1.0)) * (1.0 + sin(along * 1.9635 + noise(world / 60.0) * 6.0) * 0.04 * fine);
            color = mix(color, sandCol, s * 0.6);
            color = mix(color, pileCol, pile * 0.92);
        }
    }
    if(b > 0.001){
        // dusty light
        color = mix(color, color * vec3(1.02, 0.93, 0.80), 0.22 * b);
        // streaks of blowing sand, fast downwind; a soft shade a little beside each
        float g1 = noise(vec2(along / 420.0 - t * 0.28, across / 70.0)) * noise(vec2(along / 240.0 - t * 0.40, across / 44.0 + 0.4));
        float g2 = noise(vec2((along + 18.0) / 420.0 - t * 0.28, (across - 10.0) / 70.0)) * noise(vec2((along + 18.0) / 240.0 - t * 0.40, (across - 10.0) / 44.0 + 0.4));
        float streak = smoothstep(0.22, 0.42, g1), shade = smoothstep(0.22, 0.42, g2);
        color *= 1.0 - shade * 0.10 * b;
        color = mix(color, vec3(0.97, 0.80, 0.64), streak * 0.22 * b);
    }
    return color;
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

// The four bytes of the texel of the tile that place is on.
vec4 tileBytes(vec2 world){
    return floor(texture2D(u_kinds, (world + 4.0) / (8.0 * u_tiles)) * 255.0 + 0.5);
}

// Whether the tile with those bytes has an ore on it.
bool hasOre(vec4 bytes){
    return mod(bytes.r, 32.0) + bytes.g + bytes.b + bytes.a > 0.5;
}

// How much of that place is ore: the alpha of the tile's ore sprite there (its drop shadow, alpha 0.3, is not ore).
float oreOn(vec2 world, vec4 bytes){
    if(!hasOre(bytes)) return 0.0;
    vec2 corner = vec2(mod(bytes.r, 32.0) * 256.0 + bytes.g, bytes.b * 256.0 + bytes.a);
    vec2 f = fract((world + 4.0) / 8.0);
    // (the sheet has its first row on top)
    return smoothstep(0.5, 0.9, texture2D(u_ores, (corner + vec2(f.x, 1.0 - f.y) * u_oreSize) / u_sheet).a);
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
    vec4 bytes = u_tiles.x > 0.5 ? tileBytes(world) : vec4(0.0);
    float kind = floor(bytes.r / 32.0 + 0.01);
    bool hot = kind > 5.5 && kind < 6.5;
    // An ore is drawn with its floor, into this layer. How much of this pixel is ore:
    bool oreTile = u_sheet.x > 0.5 && hasOre(bytes);
    float ore = oreTile ? oreOn(world, bytes) : 0.0;

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
    // smaller than a screen pixel it would only be noise: it fades out when zoomed far out
    float near = 1.0 - smoothstep(0.45, 1.0, u_px);
    if(!hot && u_relief > 0.001 && near > 0.0){
        // one sprite pixel (a quarter of a world unit), but never less than a screen pixel
        vec2 pace = u_sunDir * max(0.25, u_px);
        vec2 reach = pace / u_resolution;
        vec4 lit = texture2D(u_texture, v_texCoords + reach), shaded = texture2D(u_texture, v_texCoords - reach);
        // not across the edge of the layer, where the buffer is empty
        if(lit.a > 0.99 && shaded.a > 0.99){
            float rise = dot(lit.rgb - shaded.rgb, vec3(0.299, 0.587, 0.114));
            // Sand and grass get less of it. Their tiles are covered in small marks - streaks of sand, blades of grass
            // - and with every one of them standing up at full strength the ground read as a field of pebbles
            // (a friend of the maker's: "less shadow on grass and sand"). They are soft ground: a hint is enough.
            // (Shading the sand as dunes instead was tried and taken out again: among ground that is flat everywhere
            // else, the sand alone seemed to rise.)
            float keep = (kind > 1.5 && kind < 2.5) || (kind > 4.5 && kind < 5.5) ? SOFT_RELIEF : 1.0;
            // An ore lies on the ground: it stands above it whatever the two colours are. (Read as heights like the
            // rest, an ore was pebbles on dark rock - and holes on sand and grass, which are lighter than it.) At
            // the rim of an ore the step is the ore's height; the ground beside it is not lit by it, only shaded.
            // On the ore itself the relief is the same on every ground.
            if(oreTile){
                vec2 sunward = world + pace, away = world - pace;
                float up = oreOn(sunward, tileBytes(sunward)), down = oreOn(away, tileBytes(away));
                float rim = up - down;
                rise = rise * (1.0 - abs(rim)) + (ore > 0.5 ? rim : max(rim, 0.0)) * ORE_LIFT;
                keep = mix(keep, 1.0, max(ore, max(up, down)));
            }
            color *= 1.0 + clamp(-rise * 2.2, -0.22, 0.28) * u_relief * keep * near;
        }
    }

    // (what the ground of each kind does is not done to the ore lying on it: the ore goes back to this)
    vec3 plain = color;

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

    if(ore > 0.0 && !hot) color = mix(color, plain, ore);

    if(u_tiles.x > 0.5){
        // ores stay readable under snow and drifted sand (they are what the player is looking for)
        vec3 bare = color;
        if(u_sand.x > 0.001 || u_sand.y > 0.001) color = sandOn(color, world, kind, fine);
        if(u_snow > 0.001) color = snowOn(color, world, kind, eye, fine);
        if(u_spore > 0.001) color = sporesOn(color, world);
        if(ore > 0.0) color = mix(color, bare, ore * 0.75);
        if(u_rain > 0.001 || u_pool > 0.001) color = rainOn(color, world, kind);
        // tracks pressed into soft ground (sand, snow, and what the weather lays down): a darker groove, cooler in snow
        float tr = tracksAt(world);
        if(tr > 0.003){
            float snowy = max(kind > 2.5 && kind < 3.5 ? 1.0 : 0.0, min(u_snow * 2.0, 1.0));
            float soft = max(max(kind > 1.5 && kind < 2.5 ? 1.0 : 0.0, snowy), min(u_sand.x * 1.5, 1.0) * 0.8);
            vec3 pressed = mix(color * 0.68, color * vec3(0.70, 0.78, 0.92), snowy);
            color = mix(color, pressed, clamp(tr * 1.4 * soft * (1.0 - ore), 0.0, 1.0));
        }
    }

    gl_FragColor = vec4(color, base.a);
}
