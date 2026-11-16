#define HIGHP

// Dark maps: gives lit ground the colour of the light that falls on it. The game's own lighting only takes the darkness
// away where a light shines - a red lamp and a white lamp light a place the same - so the colour of a lamp shows only
// in the thin rim where its light runs out.
// Drawn over the world right before the game's lighting, with a blend mode that multiplies: result = world * 2 * output.
// So an output of 0.5 changes nothing, less darkens a colour channel, more (up to twice) brightens it.
// Only the hue of the light is used, not its brightness: the colour is divided by its own brightness first, so a pale
// lamp and a strong lamp of the same hue tint alike, and the ground is not made brighter or darker overall.
uniform sampler2D u_texture;   // the light picture of this frame: rgb = light colour, a = amount of light
uniform float u_color;         // 1 = lit ground takes the full hue of the light, 2 = exaggerated

varying vec2 v_texCoords;

void main(){
    vec4 l = texture2D(u_texture, v_texCoords);
    float bright = dot(l.rgb, vec3(0.299, 0.587, 0.114));
    vec3 hue = l.rgb / max(bright, 0.35);
    vec3 factor = clamp(1.0 + (hue - 1.0) * clamp(l.a, 0.0, 1.0) * u_color, 0.0, 2.0);
    gl_FragColor = vec4(factor * 0.5, 1.0);
}
