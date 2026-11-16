#define HIGHP

// How many light sources the arrays below have room for. Scene.java writes its own #define MAX_LIGHTS in front of this
// file (256 if the graphics card takes that many, else 64); the lights actually used each frame are u_count of them.
#ifndef MAX_LIGHTS
#define MAX_LIGHTS 64
#endif

// Shading for blocks that have a normal map (Surfaces.java). Their sprite carries no painted shading (it is replaced
// by a plain one, sprites-override/), so the shading is worked out from the direction each pixel's face points.
//
// Every light counts on its own, and the sun is one of them:
//   - light from all around (no direction): lights every face the same;
//   - the sun: comes from u_sunDir, 45 degrees above the horizon, the same everywhere;
//   - each light source near enough (lamps, shots, units, ...): comes from where that source is, weaker with distance.
// A face is as bright as the lights it faces, added up, compared with what a flat face would get from the same lights.
// So two lamps on opposite sides light both sides of a block, instead of cancelling out as they did when only "the
// direction most light comes from" was used. On a dark map the daylight (all-around light and sun) counts for little
// and the lamps do nearly all of it.
//
// Drawn in the MIDDLE of the frame, right after the buildings and before turret heads, units, shots and effects, with a
// blend mode that multiplies: result = what is on screen * 2 * output. So everything drawn later - a unit hovering over
// the block - is not touched.
uniform sampler2D u_texture;   // this frame's surface picture, drawn sprite by sprite (Surfaces.draw): rgb = direction, or red = height with blue 0; a = 0 where there is none
uniform sampler2D u_aim;       // quarter-size light direction (mshaders-light.frag, mode 1), for the glint only

uniform vec2 u_campos;         // world position of the lower-left screen corner
uniform vec2 u_resolution;     // camera size in world units
uniform float u_shape;         // strength, 1 = default
uniform float u_day;           // how much daylight there is: 1 on a bright map, what the darkness leaves on a dark one
uniform vec2 u_sunDir;         // direction towards the sun on the ground, length 1
uniform float u_aimed;         // 1 when u_aim holds this frame's light directions
uniform float u_glint;         // metal glint strength, 0 = off
uniform float u_band;          // strength of the drifting sun band
uniform float u_time;          // seconds

uniform int u_count;           // light sources of this frame
uniform vec4 u_lights[MAX_LIGHTS];     // x, y, radius, opacity
uniform vec4 u_colors[MAX_LIGHTS];     // their colours

// The glow of the ground (molten slag, magma rock, ...): too many lights to go through one by one, so GlowField.java
// works out for every tile what a block there gets of it. One texel per tile, read between texels.
uniform float u_glowOn;        // 1 when the two pictures below hold this frame's glow
uniform sampler2D u_glow;      // rgb = colour of the glow times a, a = square root of (weight of the strongest glowing tile in reach / 1.3)
uniform sampler2D u_glowDir;   // how strongly the glowing ground pulls to the right, left, up and down (r, g, b, a): sideways per unit of height, 0..LEAN squeezed into 0..1
uniform float u_glowGain;      // how many times stronger than worked out the glow counts (the "glowing ground" setting)
uniform vec4 u_glowRect;       // world position of the corner of the first texel; 1 / (size of the whole picture in world units)

uniform float u_px;            // world units per screen pixel

varying vec2 v_texCoords;

const float SLOPE = 8.0;       // how steep a height map's steps come out: lean = (difference in height, 0..1, per sprite pixel) * this
const float RELIEF_FLOOR = 0.6; // the darkest a face from a height map gets, as a share of a flat face
const float LEAN = 4.0;        // the largest sideways-per-height u_glowDir can hold (GlowField.lean)

// The height at another place of the picture, for a pixel that came from a height map; "own" where there is no height
// to read - nothing drawn, a block with a normal map, an item lying on the belt. So the outline of a block does not
// turn into a cliff, and a conveyor runs on into the next one at whatever height that one has there.
float heightAt(vec2 uv, float own){
    vec4 s = texture2D(u_texture, uv);
    if(s.a < 0.5 || s.b > 0.25 * s.a) return own;
    return s.r / s.a;
}

void main(){
    vec2 world = v_texCoords * u_resolution + u_campos;
    vec4 m = texture2D(u_texture, v_texCoords);
    // Along the outline of a sprite that is drawn enlarged and smoothed (the head of a turret seen close up) its colour
    // fades into what is behind it over the width of one sprite pixel, and so does the cover of its map. The shading
    // fades with it (see the end): cut off at half cover, as at first, the outer half of that fade stayed unshaded - a
    // pale line along every dark face.
    if(m.a < 0.02) discard;
    // the picture holds direction times cover (layers are laid over each other by their cover)
    vec3 n;
    bool relief = m.b <= 0.25 * m.a;
    if(relief){
        // From a height map (red = height, blue = 0): the face leans away from where the ground around it is higher.
        // The eight places around this one are read, half a sprite pixel away (an eighth of a world unit; at least one
        // screen pixel) - across the edge of the tile too, so an outline is not cut off where two tiles meet. (A whole
        // sprite pixel away, as at first, every step in height became a soft band three pixels wide: the arrows of a
        // belt, themselves three pixels wide, turned into smudges.) The
        // places straight left/right/up/down count double, the diagonal ones once: along a diagonal line of pixels,
        // which is a staircase, the lean then points across the line all the way instead of flipping between
        // "sideways" and "upwards" from one step to the next.
        float own = m.r / m.a;
        float far = max(0.125, u_px);
        vec2 d = vec2(far) / u_resolution;
        float l = heightAt(v_texCoords + vec2(-d.x, 0.0), own), r = heightAt(v_texCoords + vec2(d.x, 0.0), own);
        float dn = heightAt(v_texCoords + vec2(0.0, -d.y), own), up = heightAt(v_texCoords + vec2(0.0, d.y), own);
        float ld = heightAt(v_texCoords - d, own), ru = heightAt(v_texCoords + d, own);
        float lu = heightAt(v_texCoords + vec2(-d.x, d.y), own), rd = heightAt(v_texCoords + vec2(d.x, -d.y), own);
        // difference between the two sides, averaged over the three rows. A step between two sprite pixels shows in
        // full at any distance up to a whole sprite pixel; zoomed out, where the places are further apart than that,
        // it counts for less.
        float gx = ((ru + 2.0 * r + rd) - (lu + 2.0 * l + ld)) / 4.0, gy = ((lu + 2.0 * up + ru) - (ld + 2.0 * dn + rd)) / 4.0;
        float per = SLOPE * 0.5 * min(1.0, 0.25 / far);
        n = normalize(vec3(-gx * per, -gy * per, 1.0));
    }else{
        n = normalize(m.rgb / m.a * 2.0 - 1.0);
    }

    // "got" = light this face receives, "level" = light a flat face would receive from the same lights - kept per
    // colour channel, because each light has a colour of its own: a face turned to a red lamp gets more red, the face
    // turned to the blue lamp on the other side more blue. Only the hue of a light is used (its colour divided by its
    // strongest channel); how bright it is already is in its weight. A flat face still comes out unchanged in every
    // channel, so this adds no tint of its own to what the other passes do - it only moves colour between faces.
    float around = 0.55 * u_day + 0.06, sun = 0.45 * u_day;
    vec3 level = vec3(around + sun);
    vec3 got = vec3(around + sun * max(dot(n, vec3(u_sunDir * 0.7071, 0.7071)), 0.0) / 0.7071);
    for(int i = 0; i < MAX_LIGHTS; i++){
        if(i >= u_count) break;
        vec4 l = u_lights[i];
        vec2 d = l.xy - world;
        float dist = length(d), strength = l.w;
        // the glow of a flying unit hangs "high" world units up (u_colors a = 1000 + height): a wider, evener, fainter
        // pool, as in mshaders-light.frag, and it falls on the block more from above
        // u_colors a: 1000 + height + 100 * (radius of the unit the light hangs on, whole units) - Scene.pickLights
        float high = u_colors[i].a >= 1000.0 ? mod(u_colors[i].a - 1000.0, 100.0) : 0.0;
        if(high > 0.0){
            dist = sqrt(dist * dist + high * high) - high;
            strength *= l.z / (l.z + high);
        }
        if(dist >= l.z) continue;
        float f = 1.0 - dist / l.z;
        float weight = f * f * strength * 1.3;                   // the same fall-off as the light itself (mshaders-light.frag)
        // where the light comes from: the source hangs a little above the ground, so right next to it the light
        // falls almost straight down, far from it almost sideways
        vec3 from = normalize(vec3(d, 10.0 + 0.08 * l.z + high));
        vec3 hue = u_colors[i].rgb / max(max(u_colors[i].r, max(u_colors[i].g, u_colors[i].b)), 0.05);
        got += hue * (weight * min(max(dot(n, from), 0.0) / from.z, 2.2));
        level += hue * weight;
    }
    // The glow of the ground counts as one more light: as strong as the strongest glowing tile in reach, coming from
    // the sides the glowing ground lies on - a face gets the more of it the more glowing ground it is turned to.
    if(u_glowOn > 0.5){
        vec2 at = (world - u_glowRect.xy) * u_glowRect.zw;
        vec4 g = texture2D(u_glow, at);
        if(g.a > 0.004){
            float weight = g.a * g.a * 1.3 * u_glowGain;
            // what a single light gives a face is  n.z + n.xy * (way to the light / its height);  here the second part
            // comes from the pull of the side the face is turned to
            vec4 pull = texture2D(u_glowDir, at) * LEAN;
            float side = (n.x > 0.0 ? n.x * pull.r : -n.x * pull.g) + (n.y > 0.0 ? n.y * pull.b : -n.y * pull.a);
            vec3 hue = g.rgb / max(max(g.r, max(g.g, g.b)), 0.004);
            got += hue * (weight * min(max(n.z + side, 0.0), 2.2));
            level += hue * weight;
        }
    }
    vec3 factor = 1.0 + (got / level - 1.0) * u_shape;
    // Relief from a height map is small - an arrow one step above its belt. Next to a lamp on a dark map its far side
    // would get almost nothing and come out black, a dark smear beside every arrow. Something that low is still lit
    // by what the belt around it throws back, so it never drops below this share of a flat face's light.
    if(relief) factor = max(factor, vec3(RELIEF_FLOOR));

    // Metal glint for these blocks (the same as in mshaders-post.frag, but with the face direction known instead of
    // guessed from the colours): a faint sheen on the faces turned to the sun, the bright band that drifts across the
    // map, and faces turned towards a light catching it. Laid on here so that it stays under the units too.
    if(u_glint > 0.001){
        float turned = dot(n.xy, u_sunDir);
        float lit = clamp(turned * 1.6, 0.0, 1.0), away = clamp(-turned * 1.6, 0.0, 1.0);
        float band = pow(0.5 + 0.5 * sin((dot(world, vec2(0.7071, 0.7071)) / 170.0 - u_time * 0.22) * 6.2832), 28.0);
        float shine = lit * (0.05 + 0.30 * band * u_band);
        if(u_aimed > 0.5){
            vec2 a = texture2D(u_aim, v_texCoords).rg * 2.0 - 1.0;
            float len = length(a);
            if(len > 0.03) shine += max(dot(n.xy, a / len), 0.0) * min(len * 2.5, 1.0) * 0.32;
        }
        factor *= 1.0 + shine * u_glint * 1.15;
    }
    // as much of it as the map covers this pixel
    factor = mix(vec3(1.0), factor, clamp(m.a, 0.0, 1.0));
    gl_FragColor = vec4(clamp(factor, 0.0, 2.0) * 0.5, 1.0);
}
