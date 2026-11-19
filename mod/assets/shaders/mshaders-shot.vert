// The sprite batch's own vertex shader (shaders/default.vert of the game), handing on two things more: where on the
// map the point is, and the mix colour of the sprite - which ShotShadows uses to say what the shadow is meant to lie on.
uniform mat4 u_projTrans;

attribute vec4 a_position;
attribute vec2 a_texCoord0;
attribute vec4 a_color;
attribute vec4 a_mix_color;

varying vec4 v_color;
varying vec4 v_mix;
varying vec2 v_texCoords;
varying vec2 v_world;

void main(){
    gl_Position = u_projTrans * a_position;
    v_texCoords = a_texCoord0;
    v_color = a_color;
    v_mix = a_mix_color;
    v_world = a_position.xy;
}
