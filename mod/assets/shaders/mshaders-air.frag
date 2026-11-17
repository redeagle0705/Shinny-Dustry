// Dark maps: the light picture is a picture of the ground. Where a wall stops a light, the ground behind the wall is
// dark - and so was a unit flying over that ground, though nothing stands between it and the light: the shade of the
// wall lay on top of the unit. Here the units in the air get the light as it is with no block in its way.
uniform sampler2D u_texture;   // the light picture: rgb = light colour, a = amount of light; blocks stop the light
uniform sampler2D u_open;      // the same lights with nothing in their way
uniform sampler2D u_air;       // the units in the air, full size: a = how much of the pixel one covers

varying vec2 v_texCoords;

void main(){
    vec4 lit = texture2D(u_texture, v_texCoords);
    float air = texture2D(u_air, v_texCoords).a;
    if(air > 0.003){
        vec4 open = texture2D(u_open, v_texCoords);
        // never less light than the ground has: beams and the lights beyond the limit are only in the first picture
        if(open.a > lit.a) lit = mix(lit, open, clamp(air, 0.0, 1.0));
    }
    gl_FragColor = lit;
}
