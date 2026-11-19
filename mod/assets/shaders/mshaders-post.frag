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
uniform float u_spare;         // 0 = every colour gets the saturation and contrast above, 1 = colours that are vivid already are spared (set on Erekir only)

varying vec2 v_texCoords;

const vec2 baked = vec2(0.7071, 0.7071);  // block sprites are painted as if lit from the upper right

float luma(vec3 c){
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float lumaAt(vec2 uv){
    return luma(texture2D(u_texture, uv).rgb);
}

void main(){
    vec2 uv = v_texCoords;
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
            float band = pow(0.5 + 0.5 * sin((dot(world, baked) / 170.0 - u_time * 0.22) * 6.2832), 28.0);
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

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
