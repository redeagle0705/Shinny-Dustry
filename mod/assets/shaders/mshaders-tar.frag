#define HIGHP

// Tar (LiquidFx.java). The game's own look first (dark, with thin pale lines drifting, shaders/tar.frag), then: a slow
// oily sheen that slides as the camera moves, with a faint rainbow film at its edges, and bubbles that swell up here
// and there and pop.

#define NSCALE 180.0 / 2.0

uniform sampler2D u_texture;
uniform sampler2D u_noise;
uniform vec2 u_campos;
uniform vec2 u_resolution;
uniform float u_time;
uniform float u_strength;     // 0 = the game's own look

varying vec2 v_texCoords;

float noise(vec2 p){
    return texture2D(u_noise, p).r;
}

float hash1(vec2 p){
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main(){
    vec2 c = v_texCoords;
    vec2 coords = c * u_resolution + u_campos;

    // the game's own
    float btime = u_time / 8000.0;
    float n0 = (texture2D(u_noise, coords / NSCALE + vec2(btime) * vec2(-0.9, 0.8)).r + texture2D(u_noise, coords / NSCALE + vec2(btime * 1.1) * vec2(-0.8, -1.0)).r) / 2.0;
    vec4 color = texture2D(u_texture, c);
    if(!(n0 > 0.54 && n0 < 0.58)) color.rgb *= vec3(0.6, 0.6, 0.7);
    vec3 game = color.rgb;

    float t = u_time / 60.0;
    vec3 col = game;
    vec2 eye = u_campos + u_resolution * 0.5;
    // sheen: long soft bands, tied to the tar only in part so they slide when the camera moves
    vec2 slide = coords - eye * 0.4;
    float along = dot(slide, vec2(0.7071, 0.7071)), across = dot(slide, vec2(-0.7071, 0.7071));
    float s = noise(vec2(along / 900.0 + t * 0.002, across / 240.0)) * 0.65 + noise(vec2(along / 380.0, across / 110.0 + t * 0.003) + 0.4) * 0.35;
    float sheen = smoothstep(0.50, 0.75, s);
    col += sheen * 0.11 * vec3(0.85, 0.9, 1.0);
    // a thin rainbow film where the sheen fades
    float film = smoothstep(0.40, 0.52, s) * (1.0 - smoothstep(0.55, 0.70, s));
    float phase = noise(coords / 240.0) * 1.5 + along / 300.0;
    col += (0.5 + 0.5 * cos(6.2832 * (phase + vec3(0.0, 0.33, 0.67)))) * film * 0.03;

    // bubbles: in a few cells of 14 units one swells up, then pops
    vec2 cp = coords / 14.0, id = floor(cp), f = fract(cp);
    float beat = t / (3.0 + 3.0 * hash1(id + 3.7)) + hash1(id);
    float rnd = floor(beat), ph = fract(beat);
    if(hash1(id + rnd * 1.37) < 0.22){
        // near the middle of its cell, so that the bubble and the ring it leaves stay inside it (only the pixels of its
        // own cell draw it: placed near the edge, the ring was cut off straight)
        vec2 at = vec2(hash1(id + rnd * 0.71 + 1.1), hash1(id - rnd * 0.53 + 2.3)) * 0.1 + 0.45;
        vec2 to = (f - at) * 14.0;
        float d = length(to);
        float grow = smoothstep(0.0, 0.85, ph);
        float r = 0.6 + 2.6 * grow;
        if(ph < 0.88){
            // the dome: lighter on the side towards the light (upper right), dark rim
            float inside = 1.0 - smoothstep(r - 0.5, r, d);
            float lit = clamp(dot(to / max(r, 0.01), vec2(0.6, 0.6)), -1.0, 1.0);
            col = mix(col, col * 1.25 + 0.03, inside * (0.4 + 0.4 * lit) * grow);
            col += vec3(0.9) * (1.0 - smoothstep(0.0, 0.5, length(to - vec2(0.35, 0.35) * r))) * 0.18 * grow;
            col *= 1.0 - (smoothstep(r - 0.6, r, d) - smoothstep(r, r + 0.5, d)) * 0.35 * grow;
        }else{
            // popped: a ring that runs out and fades
            float k = (ph - 0.88) / 0.12;
            float ring = 1.0 - smoothstep(0.0, 0.5, abs(d - (r + k * 2.5)));
            col += ring * (1.0 - k) * 0.08;
        }
    }
    gl_FragColor = vec4(mix(game, col, u_strength), color.a);
}
