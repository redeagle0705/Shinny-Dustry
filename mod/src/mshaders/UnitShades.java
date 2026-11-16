package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.type.*;

/**
 * What of the units stands in the way of light, as a picture of the view.
 *
 * Blocks stop light tile by tile (Occluders). Units are not on the tile grid and do not stand like walls: a body is a
 * few units tall, a foot lower still, a leg is a thin thing that arches through the air, a flying unit hangs above
 * everything. So each of them is drawn into a picture - half the size of the screen - not as "something is here" but
 * as "something is here from this height to that height":
 *   red   = 1 - bottom / ceiling      green = top / ceiling      blue = how much of the pixel it covers
 *   alpha = how wide the thing is / widest   (a thin thing far from the ground throws a faint shadow: a light is not a point)
 * all times the cover, and where several things overlap the largest of each counts (lowest bottom, highest top).
 *
 * mshaders-light.frag walks the line from a point on the ground to a light and asks at every step: how high is the
 * line here (0 at the point, the height of the light at the light), and is that inside what stands there? That gives
 * what a lamp next to a unit really does: a shadow as long as the unit is tall, longer the lower the lamp; the shadow
 * of an arching leg lying apart from the leg, joined to the foot's; light passing under a body that is carried on legs
 * and under everything that flies.
 *
 *   - a unit on the ground: its body from the ground up, a round shape;
 *   - a unit on legs: its body lifted off the ground; each foot a small round shape on the ground; each leg two
 *     slanted bars, body to joint (rising) and joint to foot (falling to the ground);
 *   - a flying unit: a round shape above the height its own glow hangs at (LightTap.airHeight), so it is in the way
 *     only of lights that are higher still.
 * The heights are made up from what the game knows (size of the unit, length of its legs) - the game itself has no
 * heights.
 */
public class UnitShades{
    /** The greatest height the picture can hold, in world units (8 = one tile); CEILING in mshaders-light.frag. */
    public static final float ceiling = 96f;
    /** The greatest width of a thing the picture can hold, in world units; WIDEST in mshaders-light.frag. */
    public static final float widest = 32f;
    /**
     * How much of a pixel a leg covers, against 1 for a body or a foot. Everything in the picture is multiplied by its
     * cover, so a leg just takes a little less light away - and the light pass knows a carried body (cover above 0.9)
     * from a leg: only bodies get the shadow that lies apart from them (BODY in mshaders-light.frag).
     */
    private static final float legCover = 0.8f;
    /** How far above the height its glow hangs at a flying unit begins, and how thick it is. */
    private static final float flyGap = 3f, flyThick = 6f;

    private FrameBuffer mask;
    private TextureRegion disc;
    private long frame = -10;
    private int drawn;
    private float top;

    public void init(){
        mask = new FrameBuffer();
        mask.getTexture().setFilter(TextureFilter.linear, TextureFilter.linear);
        // a round shape, full almost to its rim; colour and cover hold the same value (the heights are multiplied in by the draw colour)
        int size = 64;
        Pixmap round = new Pixmap(size, size);
        for(int x = 0; x < size; x++){
            for(int y = 0; y < size; y++){
                float far = Mathf.clamp((Mathf.dst(x + 0.5f, y + 0.5f, size / 2f, size / 2f) / (size / 2f) - 0.8f) / 0.2f);
                float v = 1f - far * far * (3f - 2f * far);
                round.set(x, y, Tmp.c1.set(v, v, v, v).rgba());
            }
        }
        Texture texture = new Texture(round);
        round.dispose();
        texture.setFilter(TextureFilter.linear, TextureFilter.linear);
        disc = new TextureRegion(texture);
    }

    /** @return whether the picture is of this frame and has anything in it */
    public boolean ready(){
        return mask != null && frame == Core.graphics.getFrameId() && drawn > 0;
    }

    /** How many shapes were drawn into this frame's picture. */
    public int drawn(){
        return frame == Core.graphics.getFrameId() ? drawn : 0;
    }

    /** The top of the tallest thing in the picture, in world units. */
    public float top(){
        return top;
    }

    public Texture texture(){
        return mask.getTexture();
    }

    /**
     * Draws the units in view into the picture. To be called from inside the frame, while the sprite batch is sorting
     * (a Draw.draw runnable); the camera is the projection.
     * @param flyHeight how high a unit in full flight hangs, in world units
     */
    public void draw(int width, int height, float flyHeight){
        if(mask == null) return;
        Draw.flush();
        mask.resize(Math.max(width, 2), Math.max(height, 2));
        Draw.sort(false);
        Draw.color();
        mask.begin(Color.clear);
        // the largest of everything drawn onto a pixel: lowest bottom (kept as 1 - bottom), highest top, most cover
        Gl.blendEquationSeparate(Gl.max, Gl.max);
        drawn = 0;
        top = 0f;
        // units a little outside the view still shade what is in it; further out they are not in the picture at all
        Rect view = Core.camera.bounds(Tmp.r1).grow(48f);

        // the round shapes first (one texture), then the legs (plain quads)
        for(Unit unit : Groups.unit){
            if(unit instanceof BlockUnitc || !view.contains(unit.x, unit.y)) continue;
            UnitType type = unit.type;
            float hit = unit.hitSize;
            if(unit instanceof Legsc legged && legged.legs().length > 0){
                float lift = lift(type, hit);
                round(unit.x, unit.y, hit * 0.45f, lift, lift + Mathf.clamp(hit * 0.5f, 4f, 14f));
                float foot = type.footRegion.found() ? type.footRegion.width * type.footRegion.scl() * 0.4f : thick(type);
                for(Leg leg : legged.legs()) round(leg.base.x, leg.base.y, Math.max(foot, 1.5f), 0f, 3f);
            }else if(unit.isFlying()){
                float hang = Mathf.clamp(unit.elevation) * flyHeight + flyGap;
                round(unit.x, unit.y, hit * 0.45f, hang, hang + flyThick);
            }else{
                round(unit.x, unit.y, hit * 0.45f, 0f, Mathf.clamp(hit * 0.7f, 4f, 14f));
            }
        }
        for(Unit unit : Groups.unit){
            if(!(unit instanceof Legsc legged) || legged.legs().length == 0 || !view.contains(unit.x, unit.y)) continue;
            UnitType type = unit.type;
            float lift = lift(type, unit.hitSize), knee = lift + type.legLength * 0.12f, half = thick(type);
            Leg[] legs = legged.legs();
            for(int i = 0; i < legs.length; i++){
                Vec2 from = legged.legOffset(Tmp.v2, i).add(unit.x, unit.y);
                float fx = from.x, fy = from.y;
                bar(fx, fy, lift, legs[i].joint.x, legs[i].joint.y, knee, half);
                bar(legs[i].joint.x, legs[i].joint.y, knee, legs[i].base.x, legs[i].base.y, 0f, half);
            }
        }
        Draw.color();
        mask.end();
        Gl.blendEquationSeparate(Gl.funcAdd, Gl.funcAdd);
        Draw.sort(true);
        frame = Core.graphics.getFrameId();
    }

    /** How far off the ground the body of a unit on legs is carried. */
    static float lift(UnitType type, float hit){
        return Mathf.clamp(type.legLength * 0.15f, 2f, Math.max(hit * 0.6f, 2f));
    }

    /** Half the thickness of a leg of that unit. */
    private static float thick(UnitType type){
        return Math.max(type.legRegion.found() ? type.legRegion.height * type.legRegion.scl() * 0.5f : 1.5f, 1.2f);
    }

    private void round(float x, float y, float radius, float bottom, float high){
        Draw.color(1f - Mathf.clamp(bottom / ceiling), Mathf.clamp(high / ceiling), 1f, Mathf.clamp(radius * 2f / widest));
        Draw.rect(disc, x, y, radius * 2f, radius * 2f);
        top = Math.max(top, Math.min(high, ceiling));
        drawn++;
    }

    /**
     * A bar from one point to another, at one height at its start and another at its end. It is as wide as the leg is
     * drawn, but flat: at most 5 units from its underside to its top. (As thick as wide, the legs of a toxopid hung
     * down to the height a lamp's light travels at, all around its hips - and a lamp it stood over went dark.)
     */
    private void bar(float x1, float y1, float z1, float x2, float y2, float z2, float half){
        float dx = x2 - x1, dy = y2 - y1, len = Mathf.len(dx, dy);
        if(len < 0.01f) return;
        float nx = -dy / len * half, ny = dx / len * half, flat = Math.min(half, 2.5f);
        // legs cover a little less than bodies do (legCover): that is how the light pass tells the two apart
        float c1 = Color.toFloatBits((1f - Mathf.clamp((z1 - flat) / ceiling)) * legCover, Mathf.clamp((z1 + flat) / ceiling) * legCover, legCover, Mathf.clamp(half * 2f / widest) * legCover);
        float c2 = Color.toFloatBits((1f - Mathf.clamp((z2 - flat) / ceiling)) * legCover, Mathf.clamp((z2 + flat) / ceiling) * legCover, legCover, Mathf.clamp(half * 2f / widest) * legCover);
        Fill.quad(x1 + nx, y1 + ny, c1, x1 - nx, y1 - ny, c1, x2 - nx, y2 - ny, c2, x2 + nx, y2 + ny, c2);
        top = Math.max(top, Math.min(Math.max(z1, z2) + flat, ceiling));
        drawn++;
    }
}
