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

    // contact shade: the soft dark rim the built-in shadow has all around a block, kept faintly so blocks still sit on
    // the ground on their lit sides (the blurred mask, read where the point is)
    float rim = (1.0 - texture2D(u_texture, v_texCoords).r) / 0.71;
    shade = max(shade, clamp(rim, 0.0, 1.0) * 0.30);

    // Where the mirror image of a building lies on water or ice, the shadow is laid on more lightly, so the image can
    // be seen inside it. Only there: making every shadow on ice lighter left boulders and conveyors standing on the
    // ice as dark squares in a shadow that hardly showed on the ice around them.
    if(u_tall > 0.5 && u_mirrored > 0.5){
        float kind = texture2D(u_heights, (floor(p) + 0.5) * u_tile).a;
        vec2 at = (p * 8.0 - 4.0 - u_mirrorCam.xy) / u_mirrorCam.zw;
        if(kind > 0.15 && kind < 0.40 && at.x > 0.0 && at.y > 0.0 && at.x < 1.0 && at.y < 1.0){
            shade *= mix(1.0, u_wet, texture2D(u_mirror, at).a);
        }
    }
    // Where a lamp shines, the sun is not the only light: its shadow takes away a smaller share of the light there.
    // (A lamp behind a block does not count - its light was already cut off when u_fill was made.)
    if(u_filled > 0.5){
        vec3 lamp = texture2D(u_fill, gl_FragCoord.xy / u_screen).rgb;
        shade /= 1.0 + max(lamp.r, max(lamp.g, lamp.b)) * u_fillK;
    }
    gl_FragColor = vec4(0.0, 0.0, 0.0, shade * u_strength);
}
