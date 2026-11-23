// The game's sprite shader (SpriteBatch), with pixels dropped near the cursor while a block is being placed (Cutaway.java):
//   u_style 1 = dithered: clear in the middle, then a 4x4 ordered pattern that thins out towards the rim
//   u_style 2 = cut away: a clean round hole with a sharp (smoothed, not dithered) edge
//   u_style 3 = fade: the units fade out smoothly towards the middle
// (The cut-away first had a dithered rim and looked like the dithering up close: "make it a clean circle, or a fade".)
varying lowp vec4 v_color;
varying lowp vec4 v_mix_color;
varying highp vec2 v_texCoords;
uniform highp sampler2D u_texture;

uniform vec2 u_cursor;      // screen pixels
uniform float u_radius;     // screen pixels, 0 = nothing dropped
uniform float u_style;

// the 4x4 Bayer threshold of this pixel, 0 .. 1
float bayer(vec2 p){
    vec2 q = mod(floor(p), 4.0);
    float i = q.x + q.y * 4.0;
    // the 16 thresholds in order: 0 8 2 10 / 12 4 14 6 / 3 11 1 9 / 15 7 13 5
    float t = 0.0;
    if(i < 0.5) t = 0.0; else if(i < 1.5) t = 8.0; else if(i < 2.5) t = 2.0; else if(i < 3.5) t = 10.0;
    else if(i < 4.5) t = 12.0; else if(i < 5.5) t = 4.0; else if(i < 6.5) t = 14.0; else if(i < 7.5) t = 6.0;
    else if(i < 8.5) t = 3.0; else if(i < 9.5) t = 11.0; else if(i < 10.5) t = 1.0; else if(i < 11.5) t = 9.0;
    else if(i < 12.5) t = 15.0; else if(i < 13.5) t = 7.0; else if(i < 14.5) t = 13.0; else t = 5.0;
    return (t + 0.5) / 16.0;
}

void main(){
    float keep = 1.0;
    if(u_radius > 0.5){
        float d = length(gl_FragCoord.xy - u_cursor) / u_radius;
        if(d < 1.0 && u_style > 2.5){
            keep = smoothstep(0.35, 1.0, d);
            if(keep < 0.004) discard;
        }else if(d < 1.0 && u_style > 1.5){
            // a pixel and a half of soft edge, so the circle is round and not stepped
            keep = smoothstep(1.0 - 1.5 / u_radius, 1.0, d);
            if(keep < 0.004) discard;
        }else if(d < 1.0){
            float drop;
            {
                // clean in the middle (no dither left there: "the middle without remnants"), dithered from the
                // middle out to the rim
                drop = 1.0 - smoothstep(0.45, 1.0, d);
            }
            if(bayer(gl_FragCoord.xy) < drop) discard;
        }
    }
    vec4 c = texture2D(u_texture, v_texCoords);
    gl_FragColor = v_color * mix(c, vec4(v_mix_color.rgb, c.a), v_mix_color.a);
    gl_FragColor.a *= keep;
}
