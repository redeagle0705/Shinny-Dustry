#define HIGHP

// Used right after mshaders-normals.frag, into the same picture: whatever lies ON a block that has a normal or height
// map - the items riding on a conveyor - is drawn once more here, in the shape of its own sprite.
//
// An item is written as a HEIGHT (red = height, blue = 0, like a height map - see mshaders-normals.frag): a small
// lump with a flat top that stands a little above the belt and its arrows. The shading pass then gives it what it
// gives every other step in height: the side turned to a light is bright, the side turned away dark.
//   - Without this pass the belt's relief was shaded onto the items: the arrows showed through them as lines.
//   - The first version of this pass wrote a flat face with no height. The belt's lines were gone, but so was all
//     lighting: next to a lamp the belt was lit from one side and the items on it were the only things that were not.
// u_texture: the game's own sprites (only their shape, the alpha, is used).
// Output: height * cover, 0, 0, cover - laid over the belt by cover, so the edge of an item is a short ramp.
uniform sampler2D u_texture;

varying vec2 v_texCoords;

const float ITEM_HEIGHT = 0.5;   // belt 0.22, arrows 0.38, rails 0.66 (the conveyor height maps)

void main(){
    float cover = texture2D(u_texture, v_texCoords).a;
    if(cover < 0.02) discard;
    gl_FragColor = vec4(ITEM_HEIGHT * cover, 0.0, 0.0, cover);
}
