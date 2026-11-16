#define HIGHP

// Makes the mirror picture that the water and ice shaders of the NEXT frame lay over their surface: for every point of
// the view, what shows there of the things standing nearby. (Units are drawn into the same picture afterwards, as
// sprites - Scene.drawUnitMirrors.)
//
// Only things that stand up from the ground are mirrored, and only as far as they are tall:
//   - a tile is "standing" when a solid building is on it (u_solid, green: walls, machines, turrets - not conveyors,
//     sorters, power lasers or items, which lie flat and would have no mirror image, and not the map's own walls);
//   - its image falls on the side away from the sun - the same way its shadow falls - and is as long as the
//     building is tall (u_solid, blue: the height table of Heights.java), fading out towards its end;
//   - what the image shows is the building's SIDE: the pixels along the edge of the building, each drawn out into a
//     streak the length of the image. (It used to show the building flipped over, so the picture of its top - which
//     is all a sprite seen from above has - appeared in the water. A wall standing at the water's edge shows its
//     side there, not its roof.)
// to the lower left, the two read as two different shadows of one building.
//
// How: from each point a line is walked towards the sun, tile by tile (every tile it crosses, in order). The first
// building met within its own height is the one that shows here; the point then shows the first pixel of that
// building on the line - the pixel at its edge.
//
// Output (premultiplied): rgb = colour * cover, a = cover.
uniform sampler2D u_texture;   // the scene as it stands (full size), so that the single pixel at a building's edge can be read
uniform sampler2D u_solid;     // one texel per tile: red = solid block, green = that block is a building, blue = its height (Occluders.java)

uniform vec2 u_campos;         // world position of the lower-left screen corner
uniform vec2 u_resolution;     // camera size in world units
uniform vec2 u_tiles;          // map size in tiles
uniform vec2 u_sunDir;         // direction towards the sun on the ground, length 1
uniform float u_px;            // world units per screen pixel

varying vec2 v_texCoords;

const float MAX_HEIGHT = 3.0;   // Heights.max
const int MAX_TILES = 8;        // tiles walked at most: the tallest height, along a diagonal

// x: 1 when a solid block is on that tile, y: 1 when it is a building, z: how tall the block is, in tiles
// (whole tile numbers; the texel centre is read, so filtering does not matter)
vec3 standing(vec2 tile){
    vec2 uv = (tile + 0.5) / u_tiles;
    if(uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return vec3(0.0);
    vec3 v = texture2D(u_solid, uv).rgb;
    return vec3(step(0.5, v.r), step(0.5, v.g), v.b * MAX_HEIGHT);
}

void main(){
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec2 p = (world + 4.0) / 8.0;         // in tiles; tile n covers n .. n + 1
    vec2 cell = floor(p);
    gl_FragColor = vec4(0.0);
    if(standing(cell).x > 0.5) return;    // under a block: nothing shows here anyway

    // the walk towards the sun (the direction with neither part exactly zero, so the divisions are safe)
    vec2 dir = vec2(abs(u_sunDir.x) < 0.0001 ? 0.0001 : u_sunDir.x, abs(u_sunDir.y) < 0.0001 ? 0.0001 : u_sunDir.y);
    vec2 way = sign(dir);
    vec2 crossing = abs(1.0 / dir);
    vec2 next = (cell + max(way, 0.0) - p) / dir;
    float t = 0.0;

    // "front": distance to the edge of the first building met
    float front = -1.0, tall = 0.0;
    for(int i = 0; i < MAX_TILES; i++){
        if(next.x < next.y){
            t = next.x;
            next.x += crossing.x;
            cell.x += way.x;
        }else{
            t = next.y;
            next.y += crossing.y;
            cell.y += way.y;
        }
        vec3 s = standing(cell);
        if(s.y > 0.5){
            front = t;
            tall = s.z;
            break;
        }else if(s.x > 0.5 || t > MAX_HEIGHT){
            return;                       // a map wall in between, or no building near enough
        }
    }
    if(front < 0.0 || front >= tall) return;   // the image is as long as the building is tall

    // The pixel at the building's edge: just inside it on the line - half a sprite pixel in (sprites have 4 pixels
    // per world unit), but at least most of a screen pixel, so that it is the building that is read and not the
    // ground in front of it.
    vec2 uv = v_texCoords + u_sunDir * (front * 8.0 + max(0.15, 0.75 * u_px)) / u_resolution;
    if(uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0) return;
    float fade = 1.0 - front / tall;
    // stays strong for most of its length, then falls off quickly at the end
    float cover = sqrt(fade);
    gl_FragColor = vec4(texture2D(u_texture, uv).rgb * cover, cover);
}
