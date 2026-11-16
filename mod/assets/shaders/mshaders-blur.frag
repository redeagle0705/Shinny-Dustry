// Softens the quarter-size light picture: average of a 3x3 block. The light shader walks towards each light from a
// slightly different starting point per pixel (to avoid rings), which leaves grain along the edges of the shade;
// this turns the grain into a soft edge.
uniform sampler2D u_texture;
uniform vec2 u_texel;   // size of one texel of u_texture

varying vec2 v_texCoords;

void main(){
    vec4 sum = vec4(0.0);
    for(int x = -1; x <= 1; x++){
        for(int y = -1; y <= 1; y++){
            sum += texture2D(u_texture, v_texCoords + vec2(float(x), float(y)) * u_texel);
        }
    }
    gl_FragColor = sum / 9.0;
}
