#define HIGHP

// Molten slag (LiquidFx.java). The game's own look first (a warped picture with bright bands drifting through it,
// shaders/slag.frag), then: dark plates of cooling crust that drift slowly over it in patches, split by glowing seams;
// the melt breathes brighter and dimmer from place to place.

#define S2 vec3(100.0, 93.0, 49.0) / 100.0
#define S1 vec3(100.0, 60.0, 25.0) / 100.0
#define NSCALE 200.0 / 2.0

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

// a hash without sine (precise far from the map's origin)
vec2 hash2(vec2 p){
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// distance to the nearest border between cells (0 on it)
float cellBorder(vec2 x){
    vec2 n = floor(x), f = fract(x);
    vec2 mr = vec2(0.0);
    float md = 8.0;
    for(int j = -1; j <= 1; j++){
        for(int i = -1; i <= 1; i++){
            vec2 g = vec2(float(i), float(j));
            vec2 r = g + hash2(n + g) - f;
            float d = dot(r, r);
            if(d < md){
                md = d;
                mr = r;
            }
        }
    }
    md = 8.0;
    for(int j = -1; j <= 1; j++){
        for(int i = -1; i <= 1; i++){
            vec2 g = vec2(float(i), float(j));
            vec2 r = g + hash2(n + g) - f;
            if(dot(mr - r, mr - r) > 0.00001) md = min(md, dot(0.5 * (mr + r), normalize(r - mr)));
        }
    }
    return md;
}

void main(){
    vec2 coords = v_texCoords * u_resolution + u_campos;

    // the game's own
    float btime = u_time / 5000.0;
    vec4 orig = texture2D(u_texture, v_texCoords);
    float n0 = (texture2D(u_noise, coords / NSCALE + vec2(btime) * vec2(-0.9, 0.8)).r + texture2D(u_noise, coords / NSCALE + vec2(btime * 1.1) * vec2(0.8, -1.0)).r) / 2.0;
    vec2 c = v_texCoords + (vec2(
        texture2D(u_noise, coords / 170.0 + vec2(btime) * vec2(-0.9, 0.8)).r,
        texture2D(u_noise, coords / 170.0 + vec2(btime * 1.1) * vec2(0.8, -1.0)).r
    ) - vec2(0.5)) * 8.0 / u_resolution;
    vec4 color = texture2D(u_texture, c);
    if(color.a < 0.95) color = orig;
    if(n0 > 0.6) color.rgb = S2;
    else if(n0 > 0.54) color.rgb = S1;
    vec3 game = color.rgb;

    float t = u_time / 60.0;
    vec3 col = game;
    // the melt breathes
    float breathe = noise(coords / 260.0 + vec2(t * 0.004, -t * 0.003)) * 0.6 + 0.4 * (0.5 + 0.5 * sin(t * 1.3 + noise(coords / 110.0) * 9.0));
    col *= 0.92 + 0.16 * breathe;
    // crust: plates drifting with the flow, only in patches, cooler (darker) where the crustPatch is thick
    vec2 drift = vec2(t * 0.35, -t * 0.22);
    float crustPatch = smoothstep(0.42, 0.62, noise((coords + drift * 0.5) / 420.0 + 0.31));
    if(crustPatch > 0.0){
        vec2 q = (coords + drift) / 22.0 + (vec2(noise(coords / 140.0), noise(coords / 140.0 + 0.5)) - 0.5) * 1.2;
        float b = cellBorder(q);
        float seam = 1.0 - smoothstep(0.02, 0.12, b);
        vec3 crust = mix(vec3(0.30, 0.13, 0.07), vec3(0.18, 0.08, 0.05), crustPatch) * (0.85 + 0.3 * noise(coords / 18.0));
        vec3 glow = vec3(1.0, 0.62, 0.25) * (1.05 + 0.25 * breathe);
        col = mix(col, mix(crust, glow, seam), crustPatch * 0.9);
    }
    gl_FragColor = vec4(mix(game, col, u_strength), color.a);
}
