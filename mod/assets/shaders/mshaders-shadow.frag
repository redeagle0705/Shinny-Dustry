#define HIGHP

// Stands in for the built-in shaders/darkness.frag while BlockRenderer.drawShadows() runs.
// u_texture: the game's own shadow mask, one texel per tile, white = no shadow, darker = a block that casts one.
// The built-in shader reads the mask once, so the shadow sits right under the block. Here every shadow is stretched
// towards the lower left, the way unit shadows already fall, and as far as its block is tall (u_heights, Heights.java:
// u_length is the shadow of a block one tile tall; a conveyor casts hardly any, a large factory a long one).
//
// How: from each point a line is walked towards the light, tile by tile - every tile the line crosses, once, in order
// (so nothing is stepped over, however long the shadow). A tile with a block on it shades the point if the block is
// tall enough for its shadow to reach this far. That gives the exact outline of the block's shadow: the square pushed
// along the light direction. Edges are then softened by an amount the "shadow quality" setting chooses.
// (Before, the blurred mask was read at 16 evenly spaced points: long shadows broke up into repeated copies of the
// block and thin walls were stepped over.)
uniform sampler2D u_texture;
uniform sampler2D u_heights;  // one texel per tile, blue = block height, 1.0 = MAX_HEIGHT tiles (Occluders.java)
uniform float u_tall;         // 1 when u_heights is bound; otherwise every block counts as one tile tall

uniform vec2 u_tile;      // size of one tile in texture coordinates (1 / map size)
uniform float u_length;   // shadow length of a block one tile tall, in tiles
uniform float u_strength; // 1 = as dark as the built-in shadow under a block
uniform float u_soft;     // 0 = edges as sharp as the screen allows, 1 = soft and wide
uniform float u_ao;       // the soft dark rim all around a block (ambient occlusion): 1 = as dark as the game's own, 0 = none
uniform float u_pixel;    // size of one screen pixel in tiles
uniform float u_wet;      // how much of the shadow is laid where a mirror image lies on water or ice (1 = all of it)
uniform sampler2D u_mirror;   // the mirror picture of the previous frame (mshaders-objects.frag): alpha = how much image
uniform vec4 u_mirrorCam;     // camera rectangle that picture was made with, in world units: x, y, width, height
uniform float u_mirrored;     // 1 when u_mirror is bound

uniform sampler2D u_fill;     // light of the lamps this frame, over the whole screen (quarter size)
uniform float u_filled;       // 1 when u_fill is bound
uniform vec2 u_screen;        // screen size in pixels
uniform float u_fillK;        // how strongly lamp light fills in a sun shadow

varying vec4 v_color;
varying vec2 v_texCoords;

uniform vec2 u_sun;       // direction towards the sun on the ground, length 1 (the same for the shadows, the shading of normal-mapped blocks and the ground relief)
const float MAX_HEIGHT = 3.0;              // Heights.max
const int MAX_TILES = 48;                  // tiles walked at most (about 17 tiles of shadow along this direction)

// Blocks that are not drawn as full squares - round or cut corners, dents, a picture smaller than its tiles.
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

// 1 when a block whose shadow reaches at least t tiles stands on that tile (boulders are round and join nothing).
float casts(vec2 cell, float t){
    vec2 uv = (cell + 0.5) * u_tile;
    if((1.0 - texture2D(u_texture, uv).r) / 0.71 < 0.05) return 0.0;
    vec4 block = u_tall > 0.5 ? texture2D(u_heights, uv) : vec4(0.0, 0.0, 1.0 / MAX_HEIGHT, 1.0);
    if(block.a < 0.75) return 0.0;
    return step(t, block.b * MAX_HEIGHT * u_length);
}

// Two blocks that touch have no edge between them. "toward" points across the line, to the side of the tile the
// point is nearer to: if a block stands on the tile next door on that side too, nothing may get through there.
// (Without this, softening each tile on its own left a pale line along every seam of a row of blocks whenever the
// light came straight along the rows.)
float joined(vec2 cell, vec2 toward, float t){
    float j = 0.0;
    if(abs(toward.x) > 0.3) j = max(j, casts(cell + vec2(sign(toward.x), 0.0), t));
    if(abs(toward.y) > 0.3) j = max(j, casts(cell + vec2(0.0, sign(toward.y)), t));
    if(abs(toward.x) > 0.3 && abs(toward.y) > 0.3) j = max(j, casts(cell + sign(toward), t));
    return j;
}

void main(){
    vec2 p = v_texCoords / u_tile;                    // this point in tiles; tile n covers n .. n + 1
    float range = MAX_HEIGHT * u_length;              // the longest shadow there can be

    // walking the tiles along the line towards the sun, whichever way that is
    vec2 cell = floor(p);
    // the direction with neither part exactly zero, so the divisions below are safe
    vec2 light = vec2(abs(u_sun.x) < 0.0001 ? 0.0001 : u_sun.x, abs(u_sun.y) < 0.0001 ? 0.0001 : u_sun.y);
    vec2 way = sign(light);                           // which neighbour the walk goes to in x and in y
    vec2 crossing = abs(1.0 / light);                 // distance along the line between two tile borders, in x and in y
    vec2 next = (cell + max(way, 0.0) - p) / light;   // distance to the next border in x and in y
    float t = 0.0;                                    // distance at which the line entered the current tile
    vec2 across = vec2(-light.y, light.x);            // the direction square to the light
    float halfWidth = 0.5 * (abs(across.x) + abs(across.y));   // half the width of a tile's shadow

    float shade = 0.0;
    for(int i = 0; i < MAX_TILES; i++){
        vec2 uv = (cell + 0.5) * u_tile;
        // the game's mask stores 1 - 0.71 for a block that casts a shadow (BlockRenderer.blendShadowColor);
        // read at the texel centre, so the value is exact
        float dark = (1.0 - texture2D(u_texture, uv).r) / 0.71;
        if(dark > 0.05){
            vec4 block = u_tall > 0.5 ? texture2D(u_heights, uv) : vec4(0.0, 0.0, 1.0 / MAX_HEIGHT, 1.0);
            float reach = block.b * MAX_HEIGHT * u_length;
            vec4 shape = u_shaped > 0.5 ? texture2D(u_shapes, uv) * 255.0 : vec4(0.0);
            if(block.a < 0.75){
                // A boulder (alpha 128): round, and smaller than its tile. Its shadow is a circle pushed along the
                // light direction - everything closer than the circle's radius to the line from the boulder's centre
                // to the tip.
                vec2 q = p - (cell + 0.5);
                float along = clamp(-dot(q, light), 0.0, reach);
                float inside = 0.36 - length(q + light * along);
                float edge = clamp(max(u_pixel * 1.5, u_soft * 0.16 + 0.04), 0.001, 0.30);
                float rel = along / max(reach, 0.001);
                shade = max(shade, min(dark, 1.0) * smoothstep(0.0, edge, inside) * (1.0 - 0.45 * rel * rel));
            }else if(shape.a > 7.0){
                // A block that is not drawn as a full square: the shadow of its outline, not of the tiles it stands on.
                // (Every tile of the block gives the same answer.)
                float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
                vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));   // the block's lower left corner
                float edge = clamp(max(u_pixel * 1.5, u_soft * (0.16 + 0.10 * t) + 0.012 * t), 0.001, 0.33);
                float tip = mix(0.04, 0.30, u_soft), cover = 0.0;
                for(int k = 0; k < 2; k++){
                    float far = toOutline(shape.rg, size, p - corner + across * edge * (k == 0 ? 0.35 : -0.35), light);
                    if(far < reach) cover += 0.5 * (1.0 - smoothstep(1.0 - tip, 1.0, far / reach)) * (1.0 - 0.45 * far * far / (reach * reach));
                }
                shade = max(shade, min(dark, 1.0) * cover);
            }else if(t < reach){
                float rel = t / max(reach, 0.001);    // 0 at the block, 1 at the tip of its shadow
                // how far inside the side edge of this tile's shadow the point lies, in tiles
                float lateral = dot(p - (cell + 0.5), across);
                float margin = halfWidth - abs(lateral);
                // edges: never sharper than a screen pixel; softer with the setting, and the further from the block.
                // Not wider than a third of a tile: the softening eats into each tile's own shadow, and two tiles
                // side by side overlap by just over two thirds - wider, and a pale seam shows between them.
                float edge = clamp(max(u_pixel * 1.5, u_soft * (0.16 + 0.10 * t) + 0.012 * t), 0.001, 0.33);
                float tip = mix(0.04, 0.30, u_soft);
                float side = smoothstep(0.0, edge, margin);
                if(side < 0.999) side = max(side, joined(cell, across * (lateral < 0.0 ? -1.0 : 1.0), t));
                float cover = side * (1.0 - smoothstep(1.0 - tip, 1.0, rel));
                // a little lighter towards the tip
                shade = max(shade, min(dark, 1.0) * cover * (1.0 - 0.45 * rel * rel));
            }
        }
        // on to the next tile the line crosses
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

    // Ambient occlusion: the soft dark rim the built-in shadow has all around a block - the blurred mask, read where the
    // point is, exactly as the game reads it. It does not come from the sun: it has a strength of its own (u_ao) and
    // does not follow the sun's strength, the time of day or the lamps. (It was kept at 0.3 of the sun shadow's
    // strength, about a fifth of the game's: without it blocks looked cut out and laid on the ground - the maker:
    // "without the slight shadow around the blocks something looks odd".)
    float rim = (1.0 - texture2D(u_texture, v_texCoords).r) / 0.71;
    if(u_shaped > 0.5 && rim > 0.01){
        // Around a block that is not drawn as a full square the rim follows what is drawn (the rim of the square
        // showed as a darker square around every power node). The block is the one on this tile, or else on the
        // nearest tile beside it.
        vec2 f = fract(p), side = vec2(f.x > 0.5 ? 1.0 : -1.0, f.y > 0.5 ? 1.0 : -1.0);
        for(int i = 0; i < 4; i++){
            cell = floor(p) + (i == 1 ? vec2(side.x, 0.0) : i == 2 ? vec2(0.0, side.y) : i == 3 ? side : vec2(0.0));
            vec4 shape = texture2D(u_shapes, (cell + 0.5) * u_tile) * 255.0;
            if(shape.a > 7.0){
                float size = floor(shape.a / 15.0 + 0.5), where = floor(shape.b + 0.5);
                vec2 corner = cell - vec2(floor(where / 16.0), mod(where, 16.0));   // the block's lower left corner
                // the blurred outline is one half at a straight edge of the block, less where the block is not
                rim *= clamp(outlineAt(shape.rg, size, p - corner).b * 2.0, 0.0, 1.0);
                break;
            }
        }
    }
    // (the game's mask holds 0.71 under a block: the same darkness at u_ao = 1)
    float ao = clamp(rim, 0.0, 1.0) * 0.71 * u_ao;

    // Where the mirror image of a building lies on water or ice, the shadow is laid on more lightly, so the image can
    // be seen inside it. Only there: making every shadow on ice lighter left boulders and conveyors standing on the
    // ice as dark squares in a shadow that hardly showed on the ice around them.
    if(u_tall > 0.5 && u_mirrored > 0.5){
        float kind = texture2D(u_heights, (floor(p) + 0.5) * u_tile).a;
        vec2 at = (p * 8.0 - 4.0 - u_mirrorCam.xy) / u_mirrorCam.zw;
        if(kind > 0.15 && kind < 0.40 && at.x > 0.0 && at.y > 0.0 && at.x < 1.0 && at.y < 1.0){
            float wet = mix(1.0, u_wet, texture2D(u_mirror, at).a);
            shade *= wet;
            ao *= wet;
        }
    }
    // Where a lamp shines, the sun is not the only light: its shadow takes away a smaller share of the light there.
    // (A lamp behind a block does not count - its light was already cut off when u_fill was made.)
    if(u_filled > 0.5){
        vec3 lamp = texture2D(u_fill, gl_FragCoord.xy / u_screen).rgb;
        shade /= 1.0 + max(lamp.r, max(lamp.g, lamp.b)) * u_fillK;
    }
    gl_FragColor = vec4(0.0, 0.0, 0.0, max(shade * u_strength, ao));
}
