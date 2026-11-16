#define HIGHP

// How many light sources the arrays below have room for. Scene.java writes its own #define MAX_LIGHTS in front of this
// file (256 if the graphics card takes that many, else 64); the lights actually used each frame are u_count of them.
#ifndef MAX_LIGHTS
#define MAX_LIGHTS 64
#endif

// Light from the recorded light sources, stopped by blocks. Drawn into a quarter-size buffer.
// u_texture: what stops light, one texel per tile - normally the picture of solid blocks (Occluders.java),
// as a fallback the game's block shadow mask.
// For every light in reach, the line from this point to the light is walked tile by tile; tiles with a block on them
// take the light away. Two stretches of that line do not count: the block this point is on (so the side of a wall that
// faces the light is lit, up to OWN units deep) and the block the light sits in (so a lamp shines out of its own tile).
uniform sampler2D u_texture;
uniform float u_masked;     // 1 when u_texture really is the mask; 0 = no mask this frame, nothing is blocked
uniform float u_own;        // 1: u_texture is the mod's own picture of solid blocks (white = block); 0: the game's shadow mask

uniform vec2 u_campos;      // world position of the lower-left screen corner
uniform vec2 u_resolution;  // camera size in world units
uniform vec2 u_world;       // map size in world units

// 0: light colour to add on top of the picture (maps without the lighting rule)
// 1: the direction the light comes from (for the glint)
// 2: the game's own light buffer for dark maps: rgb = light colour, a = how much light, as LightRenderer.draw() makes it
// 3: how near the light is, 1 = inside a light, falling to 0 at WIDE times its radius (for deepening the dark far from light)
uniform int u_mode;
uniform int u_count;
uniform vec4 u_lights[MAX_LIGHTS];  // x, y, radius, opacity
uniform vec4 u_colors[MAX_LIGHTS];  // rgb = colour; a = what the light sits in (see reach), or 1000 + how high it hangs (flying units)

varying vec2 v_texCoords;

const int MAX_TILES = 128;  // tiles walked at most towards one light (about 90 tiles of distance); beyond that nothing blocks
const float OWN = 1.5;      // tiles
const float WIDE = 2.8;     // mode 3: a light is "near" up to this many radii away

const float TALLEST = 3.0;  // tiles: the tallest a block can be (Heights.max)

// Units in the way of light (UnitShades.java): a picture of the view, half the size of the screen. Where something of
// a unit is: r = (1 - its bottom / CEILING) * cover, g = (its top / CEILING) * cover, b = cover, a = (its width / WIDEST) * cover.
uniform sampler2D u_unitMask;
uniform float u_unitsOn;    // 1 when the picture is of this frame and not empty
uniform float u_unitTop;    // the top of the tallest thing in it, world units
uniform float u_unitDepth;  // how deep the shadow of a unit is: 1 = it takes all of the light away, less = part of it (the "depth" setting)
const float CEILING = 96.0;     // world units (UnitShades.ceiling)
const int UNIT_STEPS = 32;      // steps along the line to one light, at most
const float UNIT_SOFT = 1.5;    // world units over which the bottom and the top of a unit fade
const float WIDEST = 32.0;      // world units (UnitShades.widest): alpha of the picture = width of the thing / this
const float LIGHT_SIZE = 5.0;   // world units: how large a light is taken to be - it smears the shadow of thin things
const float UNIT_LAMP = 6.0;    // world units: how high a light on the ground is taken to be when units are in its way
const float LIFTED = 4.0;       // world units: a thing whose underside is higher than this is carried above the ground
const float OVER = 2.5;         // for such a thing a light on the ground counts as this many times as high as its top
const float BODY = 0.9;         // cover above this is a body (legs are drawn with less, UnitShades.legCover)

// How tall the block on that tile is, in tiles (Occluders.java keeps it in blue); one tile where only the game's mask is there.
float tallOn(vec2 cell){
    return u_own > 0.5 ? texture2D(u_texture, (cell + 0.5) * 8.0 / u_world).b * TALLEST : 1.0;
}

// 1 when a block that stops light stands on that tile (whole tile numbers; read at the texel centre, so the value is exact).
// The game's shadow mask stores 1 - 0.71 for a block (BlockRenderer.blendShadowColor).
float blockOn(vec2 cell){
    float v = texture2D(u_texture, (cell + 0.5) * 8.0 / u_world).r;
    return step(0.5, u_own > 0.5 ? v : clamp((1.0 - v) / 0.71, 0.0, 1.0));
}

// Two blocks that touch have no edge between them. "toward" points across the line, to the side of the tile the
// point is nearer to: if a block stands on the tile next door on that side too, no light may get through there.
// (Without this, softening each tile on its own let light leak along every seam of a row of blocks.)
float joined(vec2 cell, vec2 toward){
    float j = 0.0;
    if(abs(toward.x) > 0.3) j = max(j, blockOn(cell + vec2(sign(toward.x), 0.0)));
    if(abs(toward.y) > 0.3) j = max(j, blockOn(cell + vec2(0.0, sign(toward.y))));
    if(abs(toward.x) > 0.3 && abs(toward.y) > 0.3) j = max(j, blockOn(cell + sign(toward)));
    return j;
}

// Low blocks - what units walk over: conveyors, sorters, routers, the distributor - are not in the way of a lamp the way
// a wall is, and used to be no obstacle at all. A light on the ground is now taken to hang LAMP_UP tiles up: the line
// from a point on the ground to it climbs, and a low block is in the way only where that line is still below the
// block's top - a short shadow behind the block, longer the further from the lamp and the taller the block (so the
// distributor's is a little longer than a router's, and a conveyor's is the shortest).
const float LAMP_UP = 2.0;

// 1 when whatever stands on that tile is at least "need" tiles tall.
float tallEnough(vec2 cell, float need){
    return step(need, tallOn(cell));
}

// The same as joined(), for low blocks: no light gets through between two that touch and are both tall enough.
float joinedLow(vec2 cell, vec2 toward, float need){
    float j = 0.0;
    if(abs(toward.x) > 0.3) j = max(j, tallEnough(cell + vec2(sign(toward.x), 0.0), need));
    if(abs(toward.y) > 0.3) j = max(j, tallEnough(cell + vec2(0.0, sign(toward.y)), need));
    if(abs(toward.x) > 0.3 && abs(toward.y) > 0.3) j = max(j, tallEnough(cell + sign(toward), need));
    return j;
}

// How much of a light at "to" reaches the point "from" (world units): 1 = all of it, 0 = none.
// The line between them is walked tile by tile - every tile it crosses, once, in order - so no block is stepped over
// however far away the light is. (Before, the line was read at 20 evenly spaced points: far from a lamp those points
// lay several tiles apart and fell on a block or beside it by chance, so the shade behind a block broke up into blobs.)
// A block shades the point by how far inside the block's outline, seen from the light, the line runs; that edge is
// softer the further the point is behind the block.
// "lamp" says what the light sits in (Scene.pickLights): its last two digits before the point are the distance from the
// light, in tiles, inside which blocks belong to the light's own building (0 = not known, a tile and a half is
// assumed); 100 or more means the light is a core, whose light also passes through whatever touches the core.
//
// 1000 or more: the light hangs in the air, lamp - 1000 world units up (the glow of a flying unit). It shines down
// over the blocks: the top of a block is lit whatever stands around it, and a block is in the way of a point on the
// ground only while the line from the point up to the light is still lower than the block - up to
// (height of the block) * (distance to the light) / (height of the light) from the point. So blocks throw short
// shadows away from the unit, longer the further from it, instead of cutting its light off like a wall cuts a lamp's.
float reach(vec2 from, vec2 to, float lamp){
    if(u_masked < 0.5) return 1.0;
    vec2 p = (from + 4.0) / 8.0, q = (to + 4.0) / 8.0;   // in tiles; tile n covers n .. n + 1
    float len = distance(p, q);
    if(len < 0.001) return 1.0;
    float air = lamp >= 1000.0 ? (lamp - 1000.0) / 8.0 : 0.0;   // tiles above the ground
    if(air > 0.0){
        lamp = 0.0;
        if(blockOn(floor(p)) > 0.5) return 1.0;
    }
    // per tile of block height, how far from the point a block can still be in the way
    float perTall = air > 0.0 ? len / air : 0.0;
    vec2 dir = (q - p) / len;
    // the direction with neither part exactly zero, so the divisions below are safe
    vec2 safe = vec2(abs(dir.x) < 0.0001 ? 0.0001 : dir.x, abs(dir.y) < 0.0001 ? 0.0001 : dir.y);
    vec2 way = sign(safe);
    vec2 crossing = abs(1.0 / safe);                 // distance along the line between two tile borders, in x and in y
    vec2 cell = floor(p);
    vec2 next = (cell + max(way, 0.0) - p) / safe;   // distance to the next border in x and in y
    vec2 across = vec2(-dir.y, dir.x);
    float halfWidth = 0.5 * (abs(across.x) + abs(across.y));
    // A lamp shines out of the block it sits in - all of it, however large (a core's own corners used to stop the
    // core's light and left black tiles at its corners).
    float ownReach = mod(lamp, 100.0);
    if(ownReach < 0.01) ownReach = OWN + 0.7;
    // A core lights through the blocks that touch it as well (containers set against it are part of it): the run of
    // blocks the walk ends in, when it ends at a core, lets the light through if it is no longer than this.
    float attached = lamp >= 100.0 ? 9.0 : 0.0;
    bool lampInside = air <= 0.0 && blockOn(floor(q)) > 0.5;
    // still inside the block this point is on: its side that faces the light is lit, up to OWN tiles deep
    bool starting = true;
    // "through": light left after the runs of blocks already walked past; "run": after the run the walk is in now
    float t = 0.0, through = 1.0, run = 1.0, runStart = 0.0;
    bool inRun = false;
    // low blocks in the way of a light on the ground (see LAMP_UP): "base" is how high the point itself is - on the
    // ground, or on top of the block it lies on, which then is not in its own way, nor is any block no taller
    bool lows = air <= 0.0 && u_own > 0.5;
    float base = lows ? tallOn(cell) : 0.0, low = 1.0;
    float climb = max(LAMP_UP - base, 0.05) / len;   // how much the line to the light rises per tile
    for(int i = 0; i < MAX_TILES; i++){
        vec4 here = texture2D(u_texture, (cell + 0.5) * 8.0 / u_world);
        if((u_own > 0.5 ? here.r : clamp((1.0 - here.r) / 0.71, 0.0, 1.0)) > 0.5){
            if(!inRun){
                inRun = true;
                run = 1.0;
                runStart = t;
            }
            bool spared = (starting && t < OWN) || (lampInside && len - t < ownReach);
            if(!spared){
                float lateral = dot(p - (cell + 0.5), across);
                float margin = halfWidth - abs(lateral);
                float side = smoothstep(0.0, clamp(0.10 + 0.04 * t, 0.10, 0.45), margin);
                if(side < 0.999) side = max(side, joined(cell, across * (lateral < 0.0 ? -1.0 : 1.0)));
                if(air > 0.0){
                    // a light in the air: this block is in the way only this near to the point (the end of its shadow, softened)
                    float far = tallOn(cell) * perTall;
                    side *= 1.0 - smoothstep(far * 0.6, far, t);
                }
                run *= 1.0 - side;
            }
        }else{
            starting = false;
            if(inRun){
                // out of a run of blocks: what it took away counts
                inRun = false;
                through *= run;
                if(through < 0.01) return 0.0;
            }
            // a low block: in the way up to "far" tiles from the point, where the line to the light passes its top
            float far = lows ? (here.b * TALLEST - base) / climb : 0.0;
            if(t < far){
                float lateral = dot(p - (cell + 0.5), across);
                float side = smoothstep(0.0, clamp(0.10 + 0.04 * t, 0.10, 0.45), halfWidth - abs(lateral));
                if(side < 0.999) side = max(side, joinedLow(cell, across * (lateral < 0.0 ? -1.0 : 1.0), base + climb * t));
                low *= 1.0 - side * (1.0 - smoothstep(far * 0.6, far, t));
                if(low < 0.01) return 0.0;
            }
        }
        if(next.x < next.y){
            t = next.x;
            next.x += crossing.x;
            cell.x += way.x;
        }else{
            t = next.y;
            next.y += crossing.y;
            cell.y += way.y;
        }
        if(t >= len) break;
        // a light in the air: further from the point than this, the line to it runs above every block
        if(air > 0.0 && t > TALLEST * perTall) break;
    }
    // The walk ended inside a run of blocks. If that is a core with what touches it, the light passes; otherwise the
    // run counts like any other.
    if(inRun && !(lampInside && len - runStart < attached)) through *= run;
    return through * low;
}

// How much of a light gets past the units between it and a point: 1 = all of it.
// The line from the point to the light is a ray that climbs: "low" at the point (the ground, or the top of the unit the
// point is on), "high" at the light. At each step: is the ray, at the height it has there, inside what the picture of
// the units says stands there (from its bottom to its top)? So a body throws a shadow as long as it is tall against
// the height of the light, the shadow of an arching leg lies apart from the leg, and light passes under what is
// carried or flies. Only the stretch of the line on which the ray is still below the tallest unit is walked.
// "air": 1 for a light that hangs in the air (the glow of a flying unit), whose height is taken as it is.
// "clear": the radius of the unit the light itself sits on - that unit is not in the way of its own light.
// "jitter": 0..1, different for every pixel, shifts the steps so that their spacing shows as grain (which the blur
// after this pass smooths) rather than as bands.
float unitsOpen(vec2 from, vec2 to, float len, float low, float high, float air, float clear, float jitter){
    if(len < 1.0 || high <= low) return 1.0;
    float last = min((u_unitTop + UNIT_SOFT - low) / (high - low), 1.0 - clear / len);
    if(last <= 0.0) return 1.0;
    float steps = clamp(last * len / 2.5, 3.0, float(UNIT_STEPS));
    float open = 1.0;
    for(int i = 0; i < UNIT_STEPS; i++){
        float k = float(i) + jitter;
        if(k >= steps) break;
        float t = last * k / steps;
        vec2 uv = (from + (to - from) * t - u_campos) / u_resolution;
        if(uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) continue;
        vec4 m = texture2D(u_unitMask, uv);
        if(m.b > 0.02){
            float bottom = (1.0 - m.r / m.b) * CEILING, top = m.g / m.b * CEILING;
            // What is carried above the ground - the body and the upper legs of a walker, a unit that flies - is
            // higher than a light on the ground is taken to be, and would throw no shadow at all. For those the light
            // counts as hanging OVER times as high as their top: the shadow then falls on the ground some way behind
            // them, apart from them, the further the further they are from the light - the way the shadow of
            // something in the air lies beside it. (Not for lights that really hang in the air: those keep their height.)
            // Bodies only (cover above BODY): the legs of a walker, treated so, drew thin dark lines all around it.
            float lamp = air < 0.5 && bottom > LIFTED && m.b > BODY ? max(high, top * OVER) : high;
            float ray = low + (lamp - low) * t;
            float hit = smoothstep(bottom - UNIT_SOFT, bottom, ray) * (1.0 - smoothstep(top, top + UNIT_SOFT, ray));
            // A light has a size, so the shadow of a thing is smeared the wider the further the point is behind it
            // compared with how far the thing is from the light: by (size of the light) * t / (1 - t). A thing
            // narrower than that smear takes only its share of the light away - the leg of a walker shades the ground
            // right behind it, and a few tiles on its shadow has all but faded.
            float smear = LIGHT_SIZE * t / max(1.0 - t, 0.02);
            hit *= clamp(m.a / m.b * WIDEST / max(smear, 0.001), 0.0, 1.0);
            open = min(open, 1.0 - m.b * hit);
        }
    }
    return open;
}

void main(){
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec3 sum = vec3(0.0);
    vec2 aim = vec2(0.0);
    float most = 0.0, total = 0.0;

    // units in the way of light: where this point is on top of one, the ray starts from its top
    float standing = 0.0, jitter = 0.0;
    if(u_unitsOn > 0.5){
        vec4 here = texture2D(u_unitMask, v_texCoords);
        if(here.b > 0.2) standing = (here.g / here.b * CEILING + 2.0 * UNIT_SOFT) * smoothstep(0.2, 0.6, here.b);
        jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    }

    for(int i = 0; i < MAX_LIGHTS; i++){
        if(i >= u_count) break;
        vec4 l = u_lights[i];
        vec2 d = l.xy - world;
        float along = length(d);        // distance on the ground
        float dist = along, strength = l.w, sideways = 1.0;
        // A light that hangs "high" world units up (the glow of a flying unit): what counts is how much further away
        // it is than from the spot right under it. Under the unit that changes little with every step - a wide, even
        // pool instead of a hot spot - and the pool reaches further out; spread over more ground, it is fainter.
        // u_colors a: 1000 + height + 100 * (radius of the unit the light hangs on, whole units) - Scene.pickLights
        float high = u_colors[i].a >= 1000.0 ? mod(u_colors[i].a - 1000.0, 100.0) : 0.0;
        if(high > 0.0){
            float slant = sqrt(along * along + high * high);
            dist = slant - high;
            strength *= l.z / (l.z + high);
            sideways = along / slant;   // how much of the light comes from the side rather than from above
        }
        float rad = u_mode == 3 ? l.z * WIDE : l.z;
        if(dist >= rad) continue;

        // below zero: the light sits on a unit of that radius (Scene.pickLights) - nothing to do with blocks
        float code = u_colors[i].a;
        float open = reach(world, l.xy, code >= 1000.0 ? 1000.0 + high : max(code, 0.0));
        // the unit the light sits on or hangs under is not in the way of its own light: its radius
        float own = code >= 1000.0 ? floor((code - 1000.0) / 100.0) : (code < 0.0 ? -code : 0.0);
        // A light that does not hang in the air is taken to sit low, UNIT_LAMP up: a unit on the ground then shades all
        // the way behind it, and the light of a lamp a walker stands over gets out under its body, between its legs.
        if(u_unitsOn > 0.5 && open > 0.01) open *= mix(1.0, unitsOpen(world, l.xy, along, standing, high > 0.0 ? high : UNIT_LAMP, high > 0.0 ? 1.0 : 0.0, own, jitter), u_unitDepth);
        vec3 c = u_colors[i].rgb;
        if(u_mode == 3){
            // full inside the lit circle, fading out to WIDE radii; weak lights count less; behind a block it is
            // "a little light" rather than none, so shade next to a lamp does not turn pitch black
            float f = (1.0 - smoothstep(0.7, WIDE, dist / l.z)) * smoothstep(0.0, 0.5, l.w) * mix(0.45, 1.0, open);
            most = max(most, f);
        }else if(u_mode == 2){
            // same fall-off as the game's light sprite "circle-shadow" (measured: 1 until 0.2 of the radius, 0.73 at half, 0 at the rim)
            float f = (1.0 - smoothstep(0.2, 1.0, dist / l.z)) * strength * open;
            most = max(most, f);
            sum += c * f;
            total += f;
        }else{
            float f = 1.0 - dist / l.z;
            f = f * f * strength * open;
            sum += c * f;
            aim += d / max(along, 0.001) * sideways * f * dot(c, vec3(0.299, 0.587, 0.114));
        }
    }

    if(u_mode == 0){
        gl_FragColor = vec4(sum, 1.0);
    }else if(u_mode == 1){
        gl_FragColor = vec4(0.5 + 0.5 * clamp(aim, -1.0, 1.0), 0.0, 1.0);
    }else if(u_mode == 3){
        gl_FragColor = vec4(vec3(most), 1.0);
    }else{
        gl_FragColor = vec4(sum / max(total, 0.0001), most);
    }
}
