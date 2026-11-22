#define HIGHP

// Stands in for the game's shaders/shield.frag (Shields.java). The shields of this frame - force projectors, the force
// fields of units, shield arcs - have been drawn into u_texture as flat shapes in their colour; this lays them over the
// screen. As the game's: the shape wobbles a little, a line runs round its edge, stripes drift across it. Added here:
//   bubble   see-through in the middle, thicker and brighter towards the edge, the way a soap bubble or a glass dome
//            looks - and a glint on the edge that faces the sun
//   ripples  where a shot was stopped, a ring runs out across the shield from that point
//   honeycomb  the game's stripes give way to a faint net of hexagons, which lights up where a ring passes
//   refraction what lies behind the shield is bent near its edge, as behind the rim of a glass dome
uniform sampler2D u_texture;
uniform vec2 u_texsize;     // the camera's size in world units
uniform vec2 u_invsize;     // 1 / that
uniform float u_time;
uniform float u_dp;
uniform vec2 u_offset;      // world position of the lower left corner of the view

uniform float u_bubble;     // 0 = flat as the game's, 1 = as set here
uniform float u_ripple;     // 0 = no rings, 1 = as set here
uniform vec2 u_sun;         // direction towards the sun on the ground, length 1
uniform vec4 u_hits[16];    // where a shot was stopped: x, y in world units, age in ticks, 1 = in use
uniform float u_hex;        // 0 = the game's stripes, 1 = the net of hexagons instead
uniform sampler2D u_backdrop;   // the world right before the shields (half size), for the refraction
uniform float u_refract;    // 0 = none
uniform float u_sheen;      // 0 = the game's stripes; above 0: waves of light now and then instead, this strong
uniform vec4 u_shields[8];  // the shields in view: x, y, radius in world units, a number of their own 1 .. 2 (0 = not in use)
uniform vec4 u_starts[8];   // shields that have just come on: x, y in world units, age in ticks, full radius (0 = not in use)

varying vec2 v_texCoords;

const float ALPHA = 0.18;   // the game's
const float STEP = 2.0;     // the game's edge line: how far out from the shape, in world units
const float RING_SPEED = 1.5;   // world units a tick
const float RING_LIFE = 34.0;   // ticks

// 1 where that place is shield
float on(vec2 T){
    return step(0.5, texture2D(u_texture, T).a);
}

// The middle of the cell of the net of hexagons (side "size") that the point p lies in, in world units.
vec2 hexCentre(vec2 p, float size){
    vec2 r = vec2(1.0, 1.7320508);
    vec2 h = r * 0.5;
    vec2 q = p / size;
    vec2 a = mod(q, r) - h, b = mod(q - h, r) - h;
    vec2 g = dot(a, a) < dot(b, b) ? a : b;
    return (q - g) * size;
}

// How near a line of a net of hexagons (side "size" world units) the point p is: 0 on a line, 0.5 in the middle of a cell.
float hexLine(vec2 p, float size){
    vec2 r = vec2(1.0, 1.7320508);
    vec2 h = r * 0.5;
    vec2 q = p / size;
    vec2 a = mod(q, r) - h, b = mod(q - h, r) - h;
    vec2 g = dot(a, a) < dot(b, b) ? a : b;
    g = abs(g);
    return 0.5 - max(dot(g, normalize(vec2(1.0, 1.7320508))), g.x);
}

void main(){
    vec2 T = v_texCoords.xy;
    vec2 coords = T * u_texsize + u_offset;     // world position

    // the rings of the shots that were stopped: how much ring is here, and which way it pushes
    float ring = 0.0, flash = 0.0;
    vec2 push = vec2(0.0);
    if(u_ripple > 0.001){
        for(int i = 0; i < 16; i++){
            vec4 h = u_hits[i];
            if(h.w < 0.5) continue;
            vec2 to = coords - h.xy;
            float dist = length(to), age = h.z;
            float r = age * RING_SPEED;
            float life = 1.0 - age / RING_LIFE;
            if(life <= 0.0 || dist > r + 8.0) continue;
            // a soft ring some four units wide, weaker as it spreads and as it fades
            float x = (dist - r) / 3.2;
            float a = exp(-x * x) * life / (1.0 + r / 60.0);
            ring += a;
            push += to / max(dist, 0.001) * a * (x < 0.0 ? -1.0 : 1.0);
            // and right where it struck, a short glow
            flash += exp(-dist * dist / 18.0) * max(1.0 - age / 10.0, 0.0);
        }
        ring = min(ring, 1.5) * u_ripple;
        flash = min(flash, 1.0) * u_ripple;
        push *= u_ripple;
    }

    // the game's wobble, and the ring bending the picture a little where it passes
    T += (vec2(sin(coords.y / 3.0 + u_time / 20.0), sin(coords.x / 3.0 + u_time / 20.0)) + push * 0.9) * u_invsize;

    vec4 color = texture2D(u_texture, T);
    vec2 v = u_invsize;
    vec4 maxed = max(max(max(texture2D(u_texture, T + vec2(0, STEP) * v), texture2D(u_texture, T + vec2(0, -STEP) * v)), texture2D(u_texture, T + vec2(STEP, 0) * v)), texture2D(u_texture, T + vec2(-STEP, 0) * v));

    if(color.a < 0.9 && maxed.a > 0.9){
        // the edge line, as the game's
        gl_FragColor = vec4(maxed.rgb, 1.0);
        return;
    }
    if(color.a <= 0.0){
        gl_FragColor = color;
        return;
    }
    float hexOn = clamp(u_hex, 0.0, 1.0);

    // Waves of light (in place of the game's stripes, which come one after another, straight, at one pace): now and
    // then - at no steady beat - a winding band of light runs across a shield, each shield in its own time and its
    // own direction, with a softer glow trailing behind it.
    float sheen = 0.0;
    if(u_sheen > 0.001){
        // The shield this point belongs to: of those whose shape can reach it (the corners of a shield's polygon are
        // its radius from its middle; a little more for the wobble), the one it lies deepest in, as a share of the
        // radius. (By the nearest middle within 1.15 radii, the wave of a small shield ran on into a large one beside
        // it - the maker: "it trespasses on the other shield".)
        float best = 1e9;
        vec4 mine = vec4(0.0);
        for(int k = 0; k < 8; k++){
            vec4 s = u_shields[k];
            if(s.w < 0.5) continue;
            float d = length(coords - s.xy);
            if(d < s.z + 2.0 && d / s.z < best){
                best = d / s.z;
                mine = s;
            }
        }
        if(mine.w > 0.5){
            float seed = mine.w - 1.0, r = mine.z;
            float speed = 2.6;                          // world units a tick
            float period = 2.0 * r / speed + 70.0;      // one wave at most in this long
            float clock = u_time + seed * 997.0;
            float n = floor(clock / period);
            float h1 = fract(sin(n * 12.9898 + seed * 78.233) * 43758.5453);
            float h2 = fract(sin(n * 39.3468 + seed * 11.135) * 24634.6345);
            float h3 = fract(sin(n * 73.1567 + seed * 52.371) * 15731.7431);
            // about two waves of three come; each starts at a random moment of its period
            if(h1 < 0.68){
                float local = clock - n * period - h2 * 60.0;
                float ang = h3 * 6.2832;
                vec2 dir = vec2(cos(ang), sin(ang)), side = vec2(-dir.y, dir.x);
                vec2 rel = coords - mine.xy;
                float across = dot(rel, side);
                // the front: winding, and the windings drift along it
                float along = dot(rel, dir) + sin(across / 11.0 + h2 * 6.2832 + u_time * 0.035) * 4.0 + sin(across / 29.0 + h1 * 9.0) * 7.0;
                float front = -r * 1.1 + local * speed;
                float x = along - front;
                float band = exp(-x * x / 70.0) + (x < 0.0 ? exp(x / 26.0) * 0.40 : 0.0);
                // in and out softly over the wave's way
                float way = local * speed / (2.2 * r);
                sheen = band * smoothstep(0.0, 0.12, way) * (1.0 - smoothstep(0.85, 1.0, way));
            }
        }
        sheen *= u_sheen;
    }

    // A shield that has just come on: its cells come on one by one, from the middle out (and a little at random),
    // each with a flash. A cell not on yet is not drawn - only the outline is there.
    float lit = 0.0;
    vec2 cellAt = hexCentre(coords, 5.2);
    for(int k = 0; k < 8; k++){
        vec4 s = u_starts[k];
        if(s.w < 0.5) continue;
        float d = length(cellAt - s.xy) / s.w;
        if(d > 1.2) continue;
        float h = fract(sin(dot(cellAt, vec2(12.9898, 78.233))) * 43758.5453);
        float comes = (0.65 * d + 0.35 * h) * 30.0;     // ticks
        if(s.z < comes){
            gl_FragColor = vec4(0.0);
            return;
        }
        lit = max(lit, 1.0 - (s.z - comes) / 9.0);
    }

    // The stripes of the game.
    bool stripe = mod(coords.x / u_dp + coords.y / u_dp + sin(coords.x / u_dp / 5.0) * 3.0 + sin(coords.y / u_dp / 5.0) * 3.0 + u_time / 4.0, 10.0) < 2.0;

    float alpha = ALPHA;
    vec3 rgb = color.rgb;
    float rimNow = 0.0;     // how near the edge (bubble), for the hexagons and the refraction
    vec2 rimWay = vec2(0.0);
    if(u_bubble > 0.001){
        // How near the edge this point is: looked for along eight ways at six distances, each ring of eight turned a
        // little against the last so the answer changes in many small steps, not six large ones. 1 = at the edge,
        // 0 = further in than 18 world units. The ways that lead out, weighted by how soon, point to the edge.
        float near = 0.0, total = 0.0;
        vec2 outward = vec2(0.0);
        // far ring first: a point it finds wholly inside is deep in the shield, and nothing else need be looked at
        bool deep = true;
        for(int j = 0; j < 8; j++){
            float a = float(j) * 0.7854;
            if(on(T + vec2(cos(a), sin(a)) * 18.0 * v) < 0.5){
                deep = false;
                break;
            }
        }
        if(!deep){
            for(int k = 0; k < 6; k++){
                float r = 1.5 + float(k) * float(k) * 0.55 + float(k) * 0.6;   // 1.5 .. 18.2
                float w = 1.0 - float(k) / 6.5;
                float turn = float(k) * 0.37;
                for(int j = 0; j < 8; j++){
                    float a = float(j) * 0.7854 + turn;
                    vec2 d = vec2(cos(a), sin(a));
                    float outside = 1.0 - on(T + d * r * v);
                    near += outside * w;
                    outward += d * outside * w / r;
                }
                total += 8.0 * w;
            }
            near = clamp(near / total * 2.2, 0.0, 1.0);
        }
        near *= u_bubble;
        float rim = near * near * (3.0 - 2.0 * near);

        // see-through in the middle, thick at the rim
        alpha = mix(ALPHA, 0.10, min(u_bubble, 1.0)) + 0.40 * rim;
        rgb *= 1.0 + 0.55 * rim;
        // the glint: on the part of the rim that faces the sun
        float facing = length(outward) > 0.0001 ? max(dot(normalize(outward), u_sun), 0.0) : 0.0;
        rgb += vec3(1.0) * pow(facing, 3.0) * rim * 0.45;
        alpha += pow(facing, 3.0) * rim * 0.20;
        rimNow = rim;
        rimWay = length(outward) > 0.0001 ? normalize(outward) : vec2(0.0);
    }
    // the game's stripes, fading out as the hexagons or the waves of light come in
    if(stripe){
        rgb *= mix(1.65, 1.0, max(hexOn, clamp(u_sheen, 0.0, 1.0)));
    }
    if(sheen > 0.0){
        rgb = mix(rgb, rgb * 1.9 + 0.18, clamp(sheen, 0.0, 1.0));
        alpha += sheen * 0.22;
    }
    if(u_hex > 0.001){
        // a net of hexagons, nine world units across, its lines a pixel or so wide: faint everywhere, bright where a
        // ring passes or a shot struck, a little brighter towards the rim
        float edge = hexLine(coords + push * 0.9, 5.2);
        float line = 1.0 - smoothstep(0.02, 0.07, edge);
        float show = (0.30 + 0.35 * rimNow + 1.6 * ring + 1.2 * flash + 0.9 * sheen) * u_hex;
        rgb = mix(rgb, rgb * 1.5 + 0.12, clamp(line * show, 0.0, 1.0));
        alpha += line * show * 0.16;
    }

    // a cell that has just come on
    if(lit > 0.0){
        rgb = mix(rgb, vec3(1.0), lit * 0.6);
        alpha += lit * 0.35;
    }

    // the rings: brighter, a little more solid, and lighter in colour
    rgb = mix(rgb, vec3(1.0), clamp(ring * 0.55 + flash * 0.5, 0.0, 0.85)) * (1.0 + ring * 0.6);
    alpha += ring * 0.55 + flash * 0.35;

    alpha = clamp(alpha, 0.0, 1.0);
    // (a colour above 1 is cut to 1 when it is written, before it is blended - so it must be cut here too, before it
    // is mixed with the world behind below: mixed first, the bright stripes and the glint came out as glaring lines)
    rgb = clamp(rgb, 0.0, 1.0);

    // Refraction: near the rim, what lies behind is seen shifted outwards, as through the curved edge of a glass dome
    // (and a passing ring bends it a little more). The shifted picture is laid under the shield only as far as it is
    // shifted: deep inside nothing is laid, and the world stays as sharp as it is.
    if(u_refract > 0.001){
        vec2 shift = (rimWay * rimNow * 5.0 + push * 1.6) * u_refract;   // world units
        float amount = clamp(length(shift) / 1.5, 0.0, 1.0);
        if(amount > 0.001){
            vec3 behind = texture2D(u_backdrop, v_texCoords - shift * u_invsize).rgb;
            // what the blend must give: the shifted world, with the shield over it
            float a = 1.0 - (1.0 - amount) * (1.0 - alpha);
            rgb = (behind * amount * (1.0 - alpha) + rgb * alpha) / max(a, 0.001);
            alpha = a;
        }
    }

    gl_FragColor = vec4(rgb, alpha);
}
