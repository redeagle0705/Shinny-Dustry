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
uniform vec4 u_waves[16];      // waves of menders: x, y in world units, how far along (0 .. 1), range (0 = not in use)
uniform vec4 u_waveColors[16];
uniform vec4 u_shapes4[24];    // the places of the menders of up to four groups whose waves run: 12 points a slot, two to a vec4
uniform vec4 u_waveEnds[16];   // the other end of each wave's line (x, y): a group of menders sends one long wave from the line through it
uniform float u_pulse;         // strength of those waves, 0 = none
uniform vec4 u_hazes[8];       // overdrive projectors at work: x, y, range, how hard they work (0 = not in use)
uniform vec4 u_hazeColors[8];
uniform float u_haze;          // strength of their shimmer and glow, 0 = none
uniform vec4 u_blasts[16];     // explosions of late: x, y in world units, age in ticks, how far the wave runs (0 = not in use)
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
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
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

void main(){
    vec2 uv = v_texCoords;
    if(u_pulse > 0.001) uv = waveWarp(uv, uv * u_resolution + u_campos);
    vec3 hazeWarm = vec3(0.0);
    if(u_haze > 0.001) uv = overdriveHaze(uv, uv * u_resolution + u_campos, hazeWarm);
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
