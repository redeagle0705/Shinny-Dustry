// Dark maps: lays extra darkness over places far from any light. Drawn right after the game's lighting.
uniform sampler2D u_texture;   // how near a light is, 1 = lit, 0 = far from all lights (mshaders-light.frag, mode 3)
uniform sampler2D u_lit;       // the light picture of this frame: alpha = amount of light, includes beams and other lights
uniform float u_deep;          // 0 = nothing, 1 = pitch black far from light

varying vec2 v_texCoords;

void main(){
    float near = texture2D(u_texture, v_texCoords).r;
    float lit = smoothstep(0.0, 0.5, texture2D(u_lit, v_texCoords).a);
    gl_FragColor = vec4(0.0, 0.0, 0.0, u_deep * (1.0 - max(near, lit)));
}
