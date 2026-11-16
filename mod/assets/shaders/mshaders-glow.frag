// What the bullet and effect layers added to the world image: the copy taken right after them (u_texture) minus the copy
// taken right before them (u_before). Shots, beams, explosions and their bloom, with whatever lies under them removed.
uniform sampler2D u_texture;
uniform sampler2D u_before;

varying vec2 v_texCoords;

void main(){
    vec3 added = texture2D(u_texture, v_texCoords).rgb - texture2D(u_before, v_texCoords).rgb;
    gl_FragColor = vec4(max(added, vec3(0.0)), 1.0);
}
