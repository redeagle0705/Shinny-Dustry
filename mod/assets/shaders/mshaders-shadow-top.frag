#define HIGHP

// Shadows ON blocks: a tall block shades the lower blocks next to it - a wall's shadow runs over the conveyor beside
// it instead of disappearing under it. (The game draws all block shadows before the blocks, so on its own every
// shadow lies under every block. mshaders-shadow.frag does the shadows on the ground.)
//
// Drawn over the whole screen right after the buildings, before turret heads and units. Only points that lie on a
// block are touched. The walk is the same as in mshaders-shadow.frag - every tile the line towards the light crosses,
// in order - with one difference: the point is not on the ground but on top of its own block, so another block only
// shades it by as much as it is TALLER. A block never shades itself or one of the same height.
uniform sampler2D u_texture;  // the game's shadow mask, one texel per tile, white = no shadow
uniform sampler2D u_heights;  // one texel per tile, blue = block height, 1.0 = MAX_HEIGHT tiles (Occluders.java)

uniform vec2 u_campos;        // world position of the lower-left screen corner
uniform vec2 u_resolution;    // camera size in world units
uniform vec2 u_tile;          // size of one tile in texture coordinates (1 / map size)
uniform float u_length;       // shadow length of a block one tile tall, in tiles
uniform float u_strength;     // 1 = as dark as the built-in shadow under a block
uniform float u_soft;         // 0 = edges as sharp as the screen allows, 1 = soft and wide
uniform float u_pixel;        // size of one screen pixel in tiles

uniform sampler2D u_fill;     // light of the lamps this frame, over the whole screen (quarter size)
uniform float u_filled;       // 1 when u_fill is bound
uniform vec2 u_screen;        // screen size in pixels
uniform float u_fillK;        // how strongly lamp light fills in a sun shadow

varying vec2 v_texCoords;

uniform vec2 u_sun;       // direction towards the sun on the ground, length 1 (the same for the shadows, the shading of normal-mapped blocks and the ground relief)
const float MAX_HEIGHT = 3.0;              // Heights.max
const int MAX_TILES = 48;

// (The same as in mshaders-shadow.frag.) Blocks that are not drawn as full squares - round or cut corners, dents, a picture smaller than its tiles.
// u_shapes (Occluders.java): one texel per tile, unfiltered. red, green = where the block's outline is in u_outlines
// (its slot, in tiles), blue = which tile of its block this is (x * 16 + y, from the lower left), alpha = size * 15 / 255.
// Alpha 0: a plain square, or no block.
// u_outlines (Silhouettes.java): the outlines, 16 texels per tile. green = how much the block covers there, blue = the
// same blurred, red = how far from there towards the sun the block begins (1.0 = never, else 255 / FAR tiles).
uniform sampler2D u_shapes;
uniform sampler2D u_outlines;
uniform float u_shaped;       // 1 when both are bound
const float CELLS = 64.0;     // u_outlines is this many tiles wide (Silhouettes.cells)
const float FAR = 24.0;       // Silhouettes.unit

// What u_outlines says at the point q of a block (in tiles from the block's lower left corner).
vec4 outlineAt(vec2 slot, float size, vec2 q){
    return texture2D(u_outlines, (slot + clamp(q, 0.04, size - 0.04)) / CELLS);
}

// How far from the point q, going along "light", the block begins, in tiles; 100 = it never does.
// Read where the line enters the block's tiles: from there on, the answer was worked out beforehand (red).
float toOutline(vec2 slot, float size, vec2 q, vec2 light){
    vec2 t1 = -q / light, t2 = (size - q) / light;
    vec2 lo = min(t1, t2), hi = max(t1, t2);
    float enter = max(max(lo.x, lo.y), 0.0);
    if(enter > min(hi.x, hi.y)) return 100.0;
    float far = outlineAt(slot, size, q + light * enter).r;
    return far > 0.98 ? 100.0 : enter + far * 255.0 / FAR;
}

// 1 when a block stands on that tile whose shadow reaches at least t tiles at the height "own".
float casts(vec2 cell, float t, float own){
    vec2 uv = (cell + 0.5) * u_tile;
    if((1.0 - texture2D(u_texture, uv).r) / 0.71 < 0.05) return 0.0;
    return step(t, (texture2D(u_heights, uv).b * MAX_HEIGHT - own - 0.03) * u_length);
}

// Two blocks that touch have no edge between them (see mshaders-shadow.frag).
float joined(vec2 cell, vec2 toward, float t, float own){
    float j = 0.0;
    if(abs(toward.x) > 0.3) j = max(j, casts(cell + vec2(sign(toward.x), 0.0), t, own));
    if(abs(toward.y) > 0.3) j = max(j, casts(cell + vec2(0.0, sign(toward.y)), t, own));
    if(abs(toward.x) > 0.3 && abs(toward.y) > 0.3) j = max(j, casts(cell + sign(toward), t, own));
    return j;
}

void main(){
    vec2 p = (v_texCoords * u_resolution + u_campos + 4.0) / 8.0;   // this point in tiles; tile n covers n .. n + 1
    vec2 cell = floor(p);
    float own = texture2D(u_heights, (cell + 0.5) * u_tile).b * MAX_HEIGHT;   // height of the block this point is on
    if(own < 0.01) discard;                                          // bare ground: done by the ground shadow
    // A boulder is round and smaller than its tile: only the boulder itself is shaded, not a square around it.
    float kind = texture2D(u_heights, (cell + 0.5) * u_tile).a;
    if(kind > 0.40 && kind < 0.60 && length(p - (cell + 0.5)) > 0.42) discard;
    // The same for a block that is not drawn as a full square: where it leaves its tiles empty the ground shows, and
    // the ground has its shadow already.
    if(u_shaped > 0.5){
        vec4 shape = texture2D(u_shapes, (cell + 0.5) * u_tile) * 255.0;
        if(shape.a > 7.0){
            float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
            vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));   // the block's lower left corner
            if(outlineAt(shape.rg, size, p - corner).g < 0.5) discard;
        }
    }

    float range = (MAX_HEIGHT - own) * u_length;                     // the longest shadow that can reach up here
    vec2 light = vec2(abs(u_sun.x) < 0.0001 ? 0.0001 : u_sun.x, abs(u_sun.y) < 0.0001 ? 0.0001 : u_sun.y);
    vec2 way = sign(light);
    vec2 crossing = abs(1.0 / light);
    vec2 next = (cell + max(way, 0.0) - p) / light;   // distance to the next border in x and in y
    float t = 0.0;
    vec2 across = vec2(-light.y, light.x);
    float halfWidth = 0.5 * (abs(across.x) + abs(across.y));

    float shade = 0.0;
    for(int i = 0; i < MAX_TILES; i++){
        vec2 uv = (cell + 0.5) * u_tile;
        float dark = (1.0 - texture2D(u_texture, uv).r) / 0.71;
        if(dark > 0.05){
            // Only the part of that block that stands above this point's own block casts a shadow up here.
            // It has to be clearly taller (0.03 tiles): two readings of the same height can differ in the last digit,
            // and without the margin every block one tile tall shaded itself.
            float reach = (texture2D(u_heights, uv).b * MAX_HEIGHT - own - 0.03) * u_length;
            vec4 shape = u_shaped > 0.5 ? texture2D(u_shapes, uv) * 255.0 : vec4(0.0);
            if(shape.a > 7.0){
                // a taller block that is not drawn as a full square: the shadow of its outline (see mshaders-shadow.frag)
                if(reach > 0.0){
                    float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
                    vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));   // the block's lower left corner
                    float edge = clamp(max(u_pixel * 1.5, u_soft * (0.16 + 0.10 * t) + 0.012 * t), 0.001, 0.33);
                    float tip = mix(0.04, 0.30, u_soft), cover = 0.0;
                    for(int k = 0; k < 2; k++){
                        float far = toOutline(shape.rg, size, p - corner + across * edge * (k == 0 ? 0.35 : -0.35), light);
                        if(far < reach) cover += 0.5 * (1.0 - smoothstep(1.0 - tip, 1.0, far / reach)) * (1.0 - 0.45 * far * far / (reach * reach));
                    }
                    shade = max(shade, min(dark, 1.0) * cover);
                }
            }else if(i > 0 && t < reach){
                float rel = t / max(reach, 0.001);
                float lateral = dot(p - (cell + 0.5), across);
                float margin = halfWidth - abs(lateral);
                float edge = clamp(max(u_pixel * 1.5, u_soft * (0.16 + 0.10 * t) + 0.012 * t), 0.001, 0.33);
                float tip = mix(0.04, 0.30, u_soft);
                float side = smoothstep(0.0, edge, margin);
                if(side < 0.999) side = max(side, joined(cell, across * (lateral < 0.0 ? -1.0 : 1.0), t, own));
                float cover = side * (1.0 - smoothstep(1.0 - tip, 1.0, rel));
                shade = max(shade, min(dark, 1.0) * cover * (1.0 - 0.45 * rel * rel));
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
        if(t > range) break;
    }
    if(shade < 0.004) discard;
    // Where a lamp shines, the sun is not the only light: its shadow takes away a smaller share of the light there.
    // (A lamp behind a block does not count - its light was already cut off when u_fill was made.)
    if(u_filled > 0.5){
        vec3 lamp = texture2D(u_fill, gl_FragCoord.xy / u_screen).rgb;
        shade /= 1.0 + max(lamp.r, max(lamp.g, lamp.b)) * u_fillK;
    }
    gl_FragColor = vec4(0.0, 0.0, 0.0, shade * u_strength);
}
