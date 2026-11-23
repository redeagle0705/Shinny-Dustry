#define HIGHP

// Cryofluid (LiquidFx.java). The game's own look first (pale bands drifting over blue, shaders/cryofluid.frag), then:
// cold mist drifting low over it in wisps, a slow cold glow, frost along the shore, and tiny ice glints.

#define S1 vec3(53.0, 83.0, 93.0) / 100.0
#define S2 vec3(68.0, 90.0, 97.0) / 100.0
#define NSCALE 100.0 / 2.0

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

// 1 where the layer (the cryofluid) is there
float here(vec2 world){
    return step(0.95, texture2D(u_texture, (world - u_campos) / u_resolution).a);
}

void main(){
    vec2 c = v_texCoords;
    vec2 coords = c * u_resolution + u_campos;

    // the game's own
    float btime = u_time / 5000.0;
    float wave = abs(sin(coords.x * 1.1 + coords.y) + 0.1 * sin(2.5 * coords.x) + 0.15 * sin(3.0 * coords.y)) / 30.0;
    float n0 = wave + (texture2D(u_noise, coords / NSCALE + vec2(btime) * vec2(-0.2, 0.8)).r + texture2D(u_noise, coords / NSCALE + vec2(btime * 1.1) * vec2(0.8, -1.0)).r) / 2.0;
    vec4 color = texture2D(u_texture, c);
    if(n0 > 0.54 && n0 < 0.57) color.rgb = S2;
    else if(n0 > 0.49 && n0 < 0.62) color.rgb = S1;
    vec3 game = color.rgb;

    float t = u_time / 60.0;
    vec3 col = game;
    // a slow cold glow
    col += vec3(0.03, 0.08, 0.10) * (0.5 + 0.5 * sin(t * 0.8 + noise(coords / 150.0) * 8.0));
    // mist: wisps drifting low over it
    float m = noise(coords / 240.0 + vec2(t * 0.010, t * 0.004)) * noise(coords / 110.0 - vec2(t * 0.016, -t * 0.006));
    col = mix(col, vec3(0.90, 0.97, 1.0), smoothstep(0.22, 0.48, m) * 0.28);
    // frost along the shore: how much of the ring around this place is not cryofluid
    if(color.a > 0.95){
        float out1 = 0.0;
        for(int k = 0; k < 8; k++){
            float a = float(k) * 0.785398;
            vec2 d = vec2(cos(a), sin(a));
            out1 += 1.0 - here(coords + d * 3.0);
            out1 += (1.0 - here(coords + d * 7.0)) * 0.5;
        }
        // feathery: broken up by fine noise, brighter in specks
        float frost = clamp(out1 / 4.0, 0.0, 1.0);
        frost = smoothstep(0.15, 0.6, frost * (0.6 + 0.8 * noise(coords / 9.0)));
        col = mix(col, vec3(0.96, 0.99, 1.0), frost * 0.85);
        col -= frost * (1.0 - frost) * 0.15 * vec3(0.3, 0.15, 0.0);
    }
    // ice glints: here and there a tiny point flickers (at first whole cells lit up, as white squares)
    vec2 gp = coords / 4.0, g = floor(gp);
    float h = hash1(g);
    if(h > 0.97){
        vec2 at = vec2(hash1(g + 5.3), hash1(g + 9.1)) * 0.6 + 0.2;
        float tw = 0.5 + 0.5 * sin(t * (2.0 + 3.0 * h) + h * 40.0);
        float dot0 = 1.0 - smoothstep(0.0, 0.12, length(fract(gp) - at));
        col += dot0 * pow(tw, 6.0) * 0.6;
    }
    gl_FragColor = vec4(mix(game, col, u_strength), color.a);
}
