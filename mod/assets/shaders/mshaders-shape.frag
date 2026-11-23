#define HIGHP

// How many light sources the arrays below have room for. Scene.java writes its own #define MAX_LIGHTS in front of this
// file (256 if the graphics card takes that many, else 64); the lights actually used each frame are u_count of them.
#ifndef MAX_LIGHTS
#define MAX_LIGHTS 64
#endif

// Shading for blocks that have a normal map (Surfaces.java). Their sprite carries no painted shading (it is replaced
// by a plain one, sprites-override/), so the shading is worked out from the direction each pixel's face points.
//
// Every light counts on its own, and the sun is one of them:
//   - light from all around (no direction): lights every face the same;
//   - the sun: comes from u_sunDir, 45 degrees above the horizon, the same everywhere;
//   - each light source near enough (lamps, shots, units, ...): comes from where that source is, weaker with distance.
// A face is as bright as the lights it faces, added up, compared with what a flat face would get from the same lights.
// So two lamps on opposite sides light both sides of a block, instead of cancelling out as they did when only "the
// direction most light comes from" was used. On a dark map the daylight (all-around light and sun) counts for little
// and the lamps do nearly all of it.
//
// Drawn in the MIDDLE of the frame, right after the buildings and before turret heads, units, shots and effects, with a
// blend mode that multiplies: result = what is on screen * 2 * output. So everything drawn later - a unit hovering over
// the block - is not touched.
uniform sampler2D u_texture;   // this frame's surface picture, drawn sprite by sprite (Surfaces.draw): rgb = direction, or red = height with blue 0; a = 0 where there is none
uniform sampler2D u_aim;       // quarter-size light direction (mshaders-light.frag, mode 1), for the glint only

uniform vec2 u_campos;         // world position of the lower-left screen corner
uniform vec2 u_resolution;     // camera size in world units
uniform float u_shape;         // strength, 1 = default
uniform float u_day;           // how much daylight there is: 1 on a bright map, what the darkness leaves on a dark one
uniform vec2 u_sunDir;         // direction towards the sun on the ground, length 1
uniform float u_aimed;         // 1 when u_aim holds this frame's light directions
uniform float u_glint;         // metal glint strength, 0 = off
uniform float u_band;          // strength of the drifting sun band
uniform float u_bandWave;      // 2 = sun through gaps in clouds, 1 = a winding band at random, 0 = straight bands one after another
uniform float u_time;          // seconds
uniform float u_metal;         // how much the top of the gloss scale is shaded as metal: 0 = polished only, 1 = metal

uniform float u_own;           // 1 while units are shaded and a unit is not to be lit by the glow it carries itself
uniform float u_coreOwn;       // 1 while buildings are shaded and a core is not to be lit by its own light
uniform int u_count;           // light sources of this frame
uniform vec4 u_lights[MAX_LIGHTS];     // x, y, radius, opacity
uniform vec4 u_colors[MAX_LIGHTS];     // their colours

// The glow of the ground (molten slag, magma rock, ...): too many lights to go through one by one, so GlowField.java
// works out for every tile what a block there gets of it. One texel per tile, read between texels.
uniform float u_glowOn;        // 1 when the two pictures below hold this frame's glow
uniform sampler2D u_glow;      // rgb = colour of the glow times a, a = square root of (weight of the strongest glowing tile in reach / 1.3)
uniform sampler2D u_glowDir;   // how strongly the glowing ground pulls to the right, left, up and down (r, g, b, a): sideways per unit of height, 0..LEAN squeezed into 0..1
uniform float u_glowGain;      // how many times stronger than worked out the glow counts (the "glowing ground" setting)
uniform vec4 u_glowRect;       // world position of the corner of the first texel; 1 / (size of the whole picture in world units)

uniform float u_px;            // world units per screen pixel

varying vec2 v_texCoords;

const float SLOPE = 8.0;       // how steep a height map's steps come out: lean = (difference in height, 0..1, per sprite pixel) * this
const float RELIEF_FLOOR = 0.6; // the darkest a face from a height map gets, as a share of a flat face
const float LEAN = 4.0;        // the largest sideways-per-height u_glowDir can hold (GlowField.lean)

// The blue of the picture: how glossy the pixel is, and which kind it is (Surfaces.encode, mshaders-normals.frag).
// A height has blue 0 .. HEIGHT_BLUE, a direction NORMAL_BLUE .. 1; the gap between them tells the two apart.
const float HEIGHT_BLUE = 0.3;
const float NORMAL_BLUE = 0.6;
const float BETWEEN = 0.45;

// Where on the gloss scale metal begins; at 1 (white in a gloss map) a face is metal all the way. Below this a face is
// "polished": a matt body with a shine laid over it - which, however strong, reads as glazed pottery and not as metal
// (the maker, of faces painted white: "ceramic with a coat of polish"). No block gets this high by what it is built
// from (Gloss.java: titanium 0.85), so it takes a gloss map to say "this is metal".
const float METAL_FROM = 0.85;
// Metal is darker than it is painted wherever it mirrors nothing bright, and bright in its own colour where it does.
// This pass can only multiply what is painted, and a light colour runs out of room soon (gold went white over whole
// faces): so the body of a metal face is taken down a little, and the top of the range is squeezed - the bright faces
// keep their colour, and only the small mirror patches go all the way to white.
const float METAL_BODY = 0.86;
const float METAL_TOP = 1.3;

// How bright the world is that a face of metal mirrors into the eye, a flat face = 1. Metal has no colour of its own
// that every light brings out the same: it shows what stands around it, and so its faces differ far more than those
// of a matt body, and change at once from one face to the next. The eye looks straight down, so the mirrored view
// swings twice as far as the face leans: a flat face shows the sky overhead, one at 45 degrees the horizon, a steeper
// one the ground. Towards the sun the world is bright right down to the horizon, away from it dark.
float mirrored(vec3 n, vec2 sunDir){
    vec2 r = 2.0 * n.z * n.xy;              // sideways: 0 on a flat face, 1 at 45 degrees
    float e = 2.0 * n.z * n.z - 1.0;        // upwards: 1 = straight up, 0 = the horizon, below 0 = the ground
    float s = dot(r, sunDir);
    float ground = 1.0 - smoothstep(-0.8, 0.1, e);
    return max(1.0 + 0.75 * s - 0.15 * (1.0 - max(e, 0.0)) * (1.0 - abs(s)) - 0.35 * ground, 0.1);
}

// The height at another place of the picture, for a pixel that came from a height map; "own" where there is no height
// to read - nothing drawn, a block with a normal map, an item lying on the belt. So the outline of a block does not
// turn into a cliff, and a conveyor runs on into the next one at whatever height that one has there.
float heightAt(vec2 uv, float own){
    vec4 s = texture2D(u_texture, uv);
    if(s.a < 0.5 || s.b > BETWEEN * s.a) return own;
    return s.r / s.a;
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

void main(){
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec4 m = texture2D(u_texture, v_texCoords);
    // Along the outline of a sprite that is drawn enlarged and smoothed (the head of a turret seen close up) its colour
    // fades into what is behind it over the width of one sprite pixel, and so does the cover of its map. The shading
    // fades with it (see the end): cut off at half cover, as at first, the outer half of that fade stayed unshaded - a
    // pale line along every dark face.
    if(m.a < 0.02) discard;
    // the picture holds direction times cover (layers are laid over each other by their cover)
    vec3 n;
    bool relief = m.b <= BETWEEN * m.a;
    // How glossy this pixel is: 0 = matt, 0.5 = half polished, 1 = polished metal (the pixel's own gloss where a gloss
    // map says so, else what the block is built from - Gloss.java).
    float gloss = clamp(relief ? m.b / m.a / HEIGHT_BLUE : (m.b / m.a - NORMAL_BLUE) / (1.0 - NORMAL_BLUE), 0.0, 1.0);
    if(relief){
        // From a height map (red = height, blue = 0): the face leans away from where the ground around it is higher.
        // The eight places around this one are read, half a sprite pixel away (an eighth of a world unit; at least one
        // screen pixel) - across the edge of the tile too, so an outline is not cut off where two tiles meet. (A whole
        // sprite pixel away, as at first, every step in height became a soft band three pixels wide: the arrows of a
        // belt, themselves three pixels wide, turned into smudges.) The
        // places straight left/right/up/down count double, the diagonal ones once: along a diagonal line of pixels,
        // which is a staircase, the lean then points across the line all the way instead of flipping between
        // "sideways" and "upwards" from one step to the next.
        float own = m.r / m.a;
        float far = max(0.125, u_px);
        vec2 d = vec2(far) / u_resolution;
        float l = heightAt(v_texCoords + vec2(-d.x, 0.0), own), r = heightAt(v_texCoords + vec2(d.x, 0.0), own);
        float dn = heightAt(v_texCoords + vec2(0.0, -d.y), own), up = heightAt(v_texCoords + vec2(0.0, d.y), own);
        float ld = heightAt(v_texCoords - d, own), ru = heightAt(v_texCoords + d, own);
        float lu = heightAt(v_texCoords + vec2(-d.x, d.y), own), rd = heightAt(v_texCoords + vec2(d.x, -d.y), own);
        // difference between the two sides, averaged over the three rows. A step between two sprite pixels shows in
        // full at any distance up to a whole sprite pixel; zoomed out, where the places are further apart than that,
        // it counts for less.
        float gx = ((ru + 2.0 * r + rd) - (lu + 2.0 * l + ld)) / 4.0, gy = ((lu + 2.0 * up + ru) - (ld + 2.0 * dn + rd)) / 4.0;
        float per = SLOPE * 0.5 * min(1.0, 0.25 / far);
        n = normalize(vec3(-gx * per, -gy * per, 1.0));
    }else{
        // the sideways part is in red and green; the part pointing up is whatever is left of a length of one
        vec2 xy = m.rg / m.a * 2.0 - 1.0;
        n = normalize(vec3(xy, sqrt(max(1.0 - dot(xy, xy), 0.0))));
    }

    // "got" = light this face receives, "level" = light a flat face would receive from the same lights - kept per
    // colour channel, because each light has a colour of its own: a face turned to a red lamp gets more red, the face
    // turned to the blue lamp on the other side more blue. Only the hue of a light is used (its colour divided by its
    // strongest channel); how bright it is already is in its weight. A flat face still comes out unchanged in every
    // channel, so this adds no tint of its own to what the other passes do - it only moves colour between faces.
    float around = 0.55 * u_day + 0.06, sun = 0.45 * u_day;
    vec3 level = vec3(around + sun);
    vec3 got = vec3(around + sun * max(dot(n, vec3(u_sunDir * 0.7071, 0.7071)), 0.0) / 0.7071);
    // The same for a face of metal ("hard" = what it gets), kept beside the other and mixed in by how much of a metal
    // the pixel is.
    float metal = clamp(smoothstep(METAL_FROM, 1.0, gloss) * u_metal, 0.0, 2.0);
    vec3 hard = vec3((around + sun) * mirrored(n, u_sunDir));
    // Gloss, as numbers the lighting can use:
    //   keen, much   the soft sheen every block had before there was gloss: how tight it is (1/4 on matt, 1 at half,
    //                4 on polished metal) and how much of it there is (0, 1, 2);
    //   mirror       how much the face works as a mirror (0, about 1/3, 1) - what makes a polished face look
    //                polished: the sun and each lamp show in it as a bright patch (gathered in "spark", in the lamps'
    //                colours), and a streak of sky lies across it;
    //   tight        how small those patches are: wide and soft at low gloss, small and hard on polished metal.
    float keen = pow(4.0, gloss * 2.0 - 1.0), much = gloss * 2.0, mirror = gloss * sqrt(gloss), tight = mix(6.0, 48.0, gloss);
    vec3 spark = vec3(0.0);
    for(int i = 0; i < MAX_LIGHTS; i++){
        if(i >= u_count) break;
        vec4 l = u_lights[i];
        vec2 d = l.xy - world;
        float dist = length(d), strength = l.w;
        // A unit's own glow sits in its middle, on the ground under it as far as this pass knows - so it lit the unit
        // from inside: every face turned to the middle bright, every face turned outwards dark, whatever the sun does.
        // A light that sits on a unit (Scene.pickLights marks those: u_colors a below 0 = minus the unit's radius, or
        // 1000 and up with the radius in the hundreds) leaves out what lies within that unit's reach - the unit itself.
        if(u_own > 0.5){
            float code = u_colors[i].a;
            float body = code >= 1000.0 ? floor((code - 1000.0) / 100.0) : max(-code, 0.0);
            if(body > 0.5) strength *= smoothstep(body * 1.7 + 2.0, body * 2.3 + 6.0, dist);
            if(strength <= 0.0) continue;
        }
        // The same for a core: its light sits in its middle, and lit it from inside - every face turned to the
        // middle bright, every face turned outwards dark. A light that sits in a core (Scene.lampCode: 100 and up,
        // below 1000; the last two digits = half the core's diagonal in tiles + 0.3) leaves out the square the core
        // stands on. Everything around the core it lights as before.
        if(u_coreOwn > 0.5 && u_colors[i].a >= 100.0 && u_colors[i].a < 1000.0){
            float reach = (mod(u_colors[i].a, 100.0) - 0.3) / 0.7072 * 4.0;   // half the side of the core, world units
            vec2 away = abs(world - l.xy);
            strength *= smoothstep(reach - 1.0, reach + 0.5, max(away.x, away.y));
            if(strength <= 0.0) continue;
        }
        // the glow of a flying unit hangs "high" world units up (u_colors a = 1000 + height): a wider, evener, fainter
        // pool, as in mshaders-light.frag, and it falls on the block more from above
        // u_colors a: 1000 + height + 100 * (radius of the unit the light hangs on, whole units) - Scene.pickLights
        float high = u_colors[i].a >= 1000.0 ? mod(u_colors[i].a - 1000.0, 100.0) : 0.0;
        if(high > 0.0){
            dist = sqrt(dist * dist + high * high) - high;
            strength *= l.z / (l.z + high);
        }
        if(dist >= l.z) continue;
        float f = 1.0 - dist / l.z;
        float weight = f * f * strength * 1.3;                   // the same fall-off as the light itself (mshaders-light.frag)
        // where the light comes from: the source hangs a little above the ground, so right next to it the light
        // falls almost straight down, far from it almost sideways
        vec3 from = normalize(vec3(d, 10.0 + 0.08 * l.z + high));
        vec3 hue = u_colors[i].rgb / max(max(u_colors[i].r, max(u_colors[i].g, u_colors[i].b)), 0.05);
        float facing = max(dot(n, from), 0.0) / from.z;
        got += hue * (weight * min(facing, 2.2));
        level += hue * weight;
        // metal: a face turned to the lamp throws far more of it at the eye, one turned a little away far less
        if(metal > 0.003){
            facing = min(facing, 1.65);
            hard += hue * (weight * facing * facing);
        }
        // the eye looks straight down: a face mirrors the lamp into it where it points halfway between the two
        if(mirror > 0.003) spark += hue * (weight * pow(max(dot(n, normalize(from + vec3(0.0, 0.0, 1.0))), 0.0), tight));
    }
    // The glow of the ground counts as one more light: as strong as the strongest glowing tile in reach, coming from
    // the sides the glowing ground lies on - a face gets the more of it the more glowing ground it is turned to.
    if(u_glowOn > 0.5){
        vec2 at = (world - u_glowRect.xy) * u_glowRect.zw;
        vec4 g = texture2D(u_glow, at);
        if(g.a > 0.004){
            float weight = g.a * g.a * 1.3 * u_glowGain;
            // what a single light gives a face is  n.z + n.xy * (way to the light / its height);  here the second part
            // comes from the pull of the side the face is turned to
            vec4 pull = texture2D(u_glowDir, at) * LEAN;
            float side = (n.x > 0.0 ? n.x * pull.r : -n.x * pull.g) + (n.y > 0.0 ? n.y * pull.b : -n.y * pull.a);
            vec3 hue = g.rgb / max(max(g.r, max(g.g, g.b)), 0.004);
            got += hue * (weight * min(max(n.z + side, 0.0), 2.2));
            hard += hue * (weight * min(max(n.z + side, 0.0), 2.2));
            level += hue * weight;
        }
    }
    // A polished face throws little light back in all directions and much in one: its light and dark sides lie
    // further apart than those of a matt face.
    vec3 factor = 1.0 + (got / level - 1.0) * u_shape * mix(0.85, 1.35, gloss);
    if(metal > 0.003) factor = max(mix(factor, (1.0 + (hard / level - 1.0) * u_shape) * METAL_BODY, metal), vec3(0.0));
    // Relief from a height map is small - an arrow one step above its belt. Next to a lamp on a dark map its far side
    // would get almost nothing and come out black, a dark smear beside every arrow. Something that low is still lit
    // by what the belt around it throws back, so it never drops below this share of a flat face's light.
    if(relief) factor = max(factor, vec3(RELIEF_FLOOR));

    // Metal glint for these blocks (the same as in mshaders-post.frag, but with the face direction known instead of
    // guessed from the colours): a faint sheen on the faces turned to the sun, the bright band that drifts across the
    // map, and faces turned towards a light catching it. Laid on here so that it stays under the units too.
    // How glossy the pixel is decides how that looks: on a matt face (gloss 0) there is none of it; at 0.5 it is the
    // soft sheen blocks always had, with a first hint of the mirror; on polished metal the sheen is tighter and twice
    // as strong, and the mirror shows in full (see "mirror" above).
    if(u_glint > 0.001 && much > 0.0){
        float turned = dot(n.xy, u_sunDir);
        float lit = pow(clamp(turned * 1.6, 0.0, 1.0), keen);
        float band = sunBand(world, vec2(0.7071, 0.7071), u_time);
        float shine = lit * (0.05 + 0.30 * band * u_band);
        if(u_aimed > 0.5){
            vec2 a = texture2D(u_aim, v_texCoords).rg * 2.0 - 1.0;
            float len = length(a);
            if(len > 0.03) shine += pow(max(dot(n.xy, a / len), 0.0), keen) * min(len * 2.5, 1.0) * 0.32;
        }
        factor *= 1.0 + shine * much * u_glint * 1.15;
        // The mirror. The eye looks straight down, so a face shows the sun where it points halfway between the sun and
        // the eye. A flat face does not (the sun stands at 45 degrees), and what little of it a flat face would show is
        // taken off, so that flat ground-level faces stay as they are.
        vec3 halfway = normalize(vec3(u_sunDir * 0.7071, 1.7071));
        float sunny = max(pow(max(dot(n, halfway), 0.0), tight) - pow(halfway.z, tight), 0.0);
        // The sky in it: a soft streak across the faces, at right angles to the sun. It is tied to the ground only in
        // part, so it slides over the blocks when the camera moves, as a reflection does; and a face that leans shows
        // another part of the sky, so the streak breaks at every edge - which is how one sees that a face is a mirror.
        vec2 eye = u_campos + u_resolution * 0.5;
        float along = dot(world - eye * 0.35 + n.xy * 9.0, u_sunDir) / 21.0;
        float wave = 0.5 + 0.5 * sin(along * 6.2832);
        float sky = pow(wave, mix(3.0, 12.0, gloss)) + 0.45 * pow(0.5 + 0.5 * sin((along + 0.3) * 6.2832), 60.0) * max(gloss * 2.0 - 1.0, 0.0);
        factor += (sunny * 0.6 + sky * 0.3) * mirror * u_day * u_glint;
        factor += spark * mirror * u_glint * 1.2;
        if(metal > 0.003){
            // On metal the sky does not lie on the face as a faint streak: the face IS what it mirrors. A broad bright
            // bar with one hard edge and one that fades, the face between the bars darker than it is painted - sheet
            // metal as it is drawn. It slides and breaks at the edges as the streak does.
            float w = fract(along * 0.62 + 0.15);
            float bar = smoothstep(0.0, 0.07, w) * (1.0 - smoothstep(0.07, 0.62, w));
            factor *= 1.0 + (bar * 0.55 - 0.16) * min(metal, 1.5) * (0.25 + 0.75 * u_day) * min(u_glint, 1.5);
        }
    }
    if(metal > 0.003) factor = mix(factor, min(factor, METAL_TOP + (factor - METAL_TOP) * 0.4), min(metal, 1.0));
    // as much of it as the map covers this pixel
    factor = mix(vec3(1.0), factor, clamp(m.a, 0.0, 1.0));
    gl_FragColor = vec4(clamp(factor, 0.0, 2.0) * 0.5, 1.0);
}
