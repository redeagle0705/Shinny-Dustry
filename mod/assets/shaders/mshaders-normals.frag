#define HIGHP

// Used while the normal maps of the buildings in view are drawn into a picture of their own (Surfaces.draw).
// u_texture: the maps, as Surfaces.encode keeps them:
//   a direction: red, green = its sideways part (0..1 for -1..1; the part pointing up follows from them), blue 0.6 .. 1;
//   a height:    red = the height, green 0, blue 0 .. 0.3.
// Blue also says how glossy the pixel is - within its range, the lower OWN of it: 0 = matt .. OWN = polished metal.
// The very top of the range means "no gloss of its own": then the pixel is as glossy as its block.
// A sprite that is drawn turned has to have its directions turned too; the angle comes in the sprite's colour:
// red = cosine, green = sine, each squeezed into 0..1. Blue says how the sprite is mirrored. Alpha says how glossy the
// block is (Gloss.java), for the pixels that have no gloss of their own.
// Where the map is transparent nothing is written, so a layer only replaces what lies under its own shape.
// The texture holds colour times alpha and may be read between pixels (smooth edges on a turned sprite): dividing by
// alpha gives back the values of the opaque pixels alone.
uniform sampler2D u_texture;

varying vec4 v_color;
varying vec2 v_texCoords;

const float HEIGHT_BLUE = 0.3;   // Surfaces.heightBlue
const float NORMAL_BLUE = 0.6;   // Surfaces.normalBlue
const float OWN = 0.88;          // Surfaces.ownShare

// The gloss a map's blue stands for, "part" being how far up its range the blue is (0..1).
float glossOf(float part){
    return part > 0.5 * (OWN + 1.0) ? v_color.a : clamp(part / OWN, 0.0, 1.0);
}

void main(){
    vec4 m = texture2D(u_texture, v_texCoords);
    if(m.a < 0.02) discard;
    vec3 v = m.rgb / m.a;
    // A height map (Surfaces.fromHeight): red = height, blue below the gap. It is written on as it is: a height has no
    // direction that would have to be turned or mirrored. The shading pass works the direction out from the heights
    // around each pixel (mshaders-shape.frag).
    if(v.b < 0.5 * (HEIGHT_BLUE + NORMAL_BLUE)){
        float gloss = glossOf(v.b / HEIGHT_BLUE);
        gl_FragColor = vec4(v.r * m.a, 0.0, HEIGHT_BLUE * gloss * m.a, m.a);
        return;
    }
    vec2 n = v.rg * 2.0 - 1.0;
    // a mirrored sprite (a conveyor that bends or is joined from the other side): blue = 0, 1/4 (left-right), 1/2
    // (up-down) or 3/4 (both). Mirrored first, then turned - the order the sprite itself is drawn in.
    float mirror = floor(v_color.b * 4.0 + 0.5);
    if(mod(mirror, 2.0) > 0.5) n.x = -n.x;
    if(mirror > 1.5) n.y = -n.y;
    vec2 turn = v_color.rg * 2.0 - 1.0;
    n = vec2(n.x * turn.x - n.y * turn.y, n.x * turn.y + n.y * turn.x);
    float gloss = glossOf((v.b - NORMAL_BLUE) / (1.0 - NORMAL_BLUE));
    // written as colour times cover, and laid over what is there by that cover: along the edge of a turned layer the
    // direction then passes smoothly from the layer below to this one instead of in steps
    gl_FragColor = vec4((n * 0.5 + 0.5) * m.a, (NORMAL_BLUE + (1.0 - NORMAL_BLUE) * gloss) * m.a, m.a);
}
