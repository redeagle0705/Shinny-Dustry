#define HIGHP

// Used while the normal maps of the buildings in view are drawn into a picture of their own (Surfaces.draw).
// u_texture: the normal maps. A sprite that is drawn turned has to have its directions turned too; the angle comes
// in the sprite's colour: red = cosine, green = sine, each squeezed into 0..1. Blue says how the sprite is mirrored.
// Where the map is transparent nothing is written, so a layer only replaces what lies under its own shape.
// The texture holds colour times alpha and may be read between pixels (smooth edges on a turned sprite): dividing by
// alpha gives back the direction of the opaque pixels alone.
uniform sampler2D u_texture;

varying vec4 v_color;
varying vec2 v_texCoords;

void main(){
    vec4 m = texture2D(u_texture, v_texCoords);
    if(m.a < 0.02) discard;
    vec3 v = m.rgb / m.a;
    // A height map (Surfaces.fromHeight): red = height, blue = 0 - a normal map's blue is never below half. It is
    // written on as it is: a height has no direction that would have to be turned or mirrored. The shading pass
    // works the direction out from the heights around each pixel (mshaders-shape.frag).
    if(v.b < 0.25){
        gl_FragColor = vec4(v.r * m.a, 0.0, 0.0, m.a);
        return;
    }
    vec3 n = v * 2.0 - 1.0;
    // a mirrored sprite (a conveyor that bends or is joined from the other side): blue = 0, 1/4 (left-right), 1/2
    // (up-down) or 3/4 (both). Mirrored first, then turned - the order the sprite itself is drawn in.
    float mirror = floor(v_color.b * 4.0 + 0.5);
    if(mod(mirror, 2.0) > 0.5) n.x = -n.x;
    if(mirror > 1.5) n.y = -n.y;
    vec2 turn = v_color.rg * 2.0 - 1.0;
    n.xy = vec2(n.x * turn.x - n.y * turn.y, n.x * turn.y + n.y * turn.x);
    // written as colour times cover, and laid over what is there by that cover: along the edge of a turned layer the
    // direction then passes smoothly from the layer below to this one instead of in steps
    gl_FragColor = vec4((normalize(n) * 0.5 + 0.5) * m.a, m.a);
}
