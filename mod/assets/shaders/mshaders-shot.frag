#define HIGHP

// The shadow of a shot (ShotShadows.java), on what it falls on.
//
// A shadow lies the nearer to its shot the higher the thing it falls on: on the ground it is far out, on top of a
// wall under the shot it is almost under it. So a shot's shadow is drawn once for every height that is there - once
// for the ground, once for each height of block near it - each time where it would lie on something that tall, and
// this shader keeps of each copy only the part that is on tiles of that very height. Passing a wall, the shadow
// climbs onto it at the wall's edge and steps down again behind it.
//
// u_texture: the game's sprites (only the shape, the alpha, is used). u_heights: one texel per tile, blue = how tall
// the block there is (Occluders.java). The red of the sprite's mix colour: the height this copy is meant for, as
// that picture has it; what is lower than LOW counts as the ground on both sides.
uniform sampler2D u_texture;
uniform sampler2D u_heights;
uniform vec2 u_world;   // map size in world units

varying vec4 v_color;
varying vec4 v_mix;
varying vec2 v_texCoords;
varying vec2 v_world;

const float LOW = 42.5 / 255.0;   // half a tile of Heights.max = 3: conveyors, routers, boulders, open doors

void main(){
    float cover = texture2D(u_texture, v_texCoords).a * v_color.a;
    if(cover < 0.004) discard;
    vec2 cell = floor((v_world + 4.0) / 8.0);
    float tall = texture2D(u_heights, (cell + 0.5) * 8.0 / u_world).b;
    if(tall < LOW) tall = 0.0;
    if(abs(tall - v_mix.r) > 0.002) discard;
    gl_FragColor = vec4(v_color.rgb, cover);
}
