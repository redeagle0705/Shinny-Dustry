package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.entities.bullet.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;

import java.lang.reflect.*;

/**
 * Sits in Renderer.lights and writes down every light the game reports through Drawf.light(...).
 *
 * Maps WITHOUT the lighting rule: the game's own LightRenderer drops all lights (LightRenderer.add starts with
 * "if(!enabled()) return"), and BlockRenderer only asks buildings for their lights when enabled() is true. So while
 * {@link #capture} is set, enabled() answers true and the lights are recorded here; Scene adds their light on top.
 *
 * Maps WITH the lighting rule (dark maps): while {@link #takeover} is set, the game's light picture is drawn by
 * Scene.drawGameLighting() instead of LightRenderer.draw(): round lights go through the shader that lets blocks stop them,
 * everything else (beams, sprite-shaped lights, custom ones) is drawn the game's way. With takeover off, everything is
 * passed on untouched and the game lights the map as it always did.
 */
public class LightTap extends LightRenderer{
    /** floats per recorded light: x, y, radius, r, g, b, opacity, height above the ground (0 = on the ground) */
    public static final int stride = 8;

    public final FloatSeq data = new FloatSeq();
    /** floats per light of the ground: x, y, radius, r, g, b, opacity */
    public static final int groundStride = 7;
    /**
     * The glow of the ground itself: molten slag, magma rock, hot rock, cryofluid and the like report one light for
     * every tile in view. They are kept out of {@link #data}: a lake of slag is hundreds of lights of equal strength,
     * which took every place under the light limit - a few spots of the shore glowed, the rest did not, and weaker
     * lights (shots) got no place at all. Scene paints these as plain round glows instead, any number of them
     * (paintGround), and tells the normal-mapped blocks where the glow comes from (GlowField).
     */
    public final FloatSeq ground = new FloatSeq();
    /** Lights that are not plain circles, kept for Scene.drawGameLighting() while takeover is on. */
    public final Seq<Runnable> extras = new Seq<>();
    public boolean capture, takeover;
    public Scene scene;

    /** Renderer.lights is a public final field, so it has to be set through reflection. @return the tap, or null if that failed */
    public static LightTap install(Scene scene){
        try{
            LightTap tap = new LightTap();
            tap.scene = scene;
            Field field = Renderer.class.getDeclaredField("lights");
            field.setAccessible(true);
            field.set(Vars.renderer, tap);
            if(Vars.renderer.lights != tap) return null;
            try{
                int matched = matchShots();
                if(matched > 0) arc.util.Log.info("[mshaders] light colour matched to the shot for @ kind(s) of shots", matched);
            }catch(Throwable t){
                arc.util.Log.err("[mshaders] light colours of shots left as they are", t);
            }
            return tap;
        }catch(Throwable t){
            arc.util.Log.err("[mshaders] could not listen for lights", t);
            return null;
        }
    }

    /** @return whether the map itself has lighting on (the game's own rule, not ours) */
    public boolean gameLighting(){
        return super.enabled();
    }

    @Override
    public boolean enabled(){
        return capture || super.enabled();
    }

    private void record(float x, float y, float radius, Color color, float opacity, float height){
        if(!capture || radius <= 0f || opacity <= 0f) return;
        data.add(x, y, radius);
        data.add(color.r, color.g, color.b, opacity);
        data.add(height);
    }

    @Override
    public void add(Runnable run){
        if(!gameLighting()) return;
        if(takeover) extras.add(run);
        else super.add(run);
    }

    /**
     * How much stronger the lights of things that move are made: shots, beams, explosions and other effects. 1 = as
     * the game reports them. The game gives a shot a small, weak light (about two tiles across, opacity 0.3), which is
     * hardly seen next to a lamp. Set by Scene every frame from the "moving lights" setting; 1 while the mod's own
     * lighting is switched off, so the game is then left exactly as it is.
     * A light k times stronger is given k times the opacity (up to 1, the most the game's own drawing takes) and
     * sqrt(k) times the radius.
     */
    public float moving = 1f;

    /**
     * How high the light of a unit in full flight hangs above the ground, in world units (8 = one tile); 0 = unit
     * lights are on the ground like every other light, as the game has them. Set by Scene every frame from the "light
     * of flying units: height" setting. The game itself knows no height - a flying unit's glow is reported at the
     * unit's place on the map like a lamp standing there - so a wall next to a unit passing overhead threw a long
     * shadow, as if the unit were crawling past it. With a height the light shader lets it shine over blocks
     * (mshaders-light.frag) and normal-mapped blocks are lit more from above (mshaders-shape.frag).
     */
    public float airHeight = 0f;

    /** Where the units are in this frame, each with how far up it is: 0 = on the ground .. 1 = in full flight. */
    private final IntFloatMap unitsAt = new IntFloatMap();
    /** The same places, each with the radius of the unit there. */
    private final IntFloatMap unitRadii = new IntFloatMap();
    private long unitsFrame = -1;

    private static int place(float x, float y){
        return Float.floatToIntBits(x) * 31 + Float.floatToIntBits(y);
    }

    private void findUnits(){
        long frame = Core.graphics.getFrameId();
        if(frame == unitsFrame) return;
        unitsFrame = frame;
        unitsAt.clear();
        unitRadii.clear();
        for(Unit unit : Groups.unit){
            unitsAt.put(place(unit.x, unit.y), upOf(unit));
            unitRadii.put(place(unit.x, unit.y), unit.hitSize * 0.5f);
        }
    }

    /**
     * How far up a unit is: 0 = on the ground .. 1 = in full flight. A unit on legs carries its body - and the glow
     * under it - off the ground: a little below the body (UnitShades.lift), as a share of the height a unit in full
     * flight hangs at.
     */
    private static float upOf(Unit unit){
        return unit instanceof Legsc legged && legged.legs().length > 0 ? Math.max(UnitShades.lift(unit.type, unit.hitSize) - 3f, 1f) / Scene.airLevel
            : unit.isFlying() ? Mathf.clamp(unit.elevation) : 0f;
    }

    /** The shots of units that are off the ground, by where each is in this frame. */
    private final IntMap<Bullet> shotsAt = new IntMap<>();
    private long shotsFrame = -1;
    /** A light lower than this is on the ground (world units): the light pass treats the two differently, and close to the ground the difference shows as a jump either way. */
    private static final float lowest = 2f;

    /** The shot of a unit off the ground that is exactly at that place; null if there is none. */
    private Bullet shotAt(float x, float y){
        long frame = Core.graphics.getFrameId();
        if(frame != shotsFrame){
            shotsFrame = frame;
            shotsAt.clear();
            for(Bullet b : Groups.bullet){
                if(b.owner instanceof Unit unit && upOf(unit) > 0f) shotsAt.put(place(b.x, b.y), b);
            }
        }
        return shotsAt.isEmpty() ? null : shotsAt.get(place(x, y));
    }

    /**
     * How high a shot leaves its unit, in world units: where the unit's own glow hangs.
     * The game has no heights: the light of a shot is reported at the shot's place on the map, like a lamp standing
     * there. So a unit in flight had its glow up in the air (airHeight) and its shots crawling along the ground under
     * it - cut off by every wall the unit itself shone over.
     */
    private float muzzle(Bullet b){
        return b.owner instanceof Unit unit ? upOf(unit) * airHeight : 0f;
    }

    /**
     * Does that shot stay at the height it was fired at? One that cannot hit anything on the ground does, and so does
     * one whose unit is, right now, aiming the weapon it came from at something that flies.
     */
    private static boolean staysUp(Bullet b){
        if(!b.type.collidesGround) return true;
        if(!b.type.collidesAir || !(b.owner instanceof Unit unit)) return false;
        for(mindustry.entities.units.WeaponMount mount : unit.mounts){
            if(mount.weapon.bullet == b.type && mount.target instanceof Unit aimed && aimed.isFlying()) return true;
        }
        return false;
    }

    /**
     * How high a shot is right now, in world units. Every other shot comes down on its way: it is on the ground where
     * it was aimed at (how far along the line from where it was fired to there it has come), at the latest when its
     * time is up (a bomb does not travel, it falls). Linear - nothing here is a real trajectory.
     */
    private float shotHeight(Bullet b){
        float top = muzzle(b);
        if(top <= 0f || staysUp(b)) return top;
        float done = b.fin();
        if(!(b.aimX == -1f && b.aimY == -1f)){
            float ax = b.aimX - b.originX, ay = b.aimY - b.originY, len2 = ax * ax + ay * ay;
            if(len2 > 64f) done = Math.max(done, ((b.x - b.originX) * ax + (b.y - b.originY) * ay) / len2);
        }
        return top * (1f - Mathf.clamp(done));
    }

    /** The radius of the unit a light reported at that place sits on; 0 where no unit is. */
    public float unitRadiusAt(float x, float y){
        findUnits();
        return unitRadii.get(place(x, y), 0f);
    }

    /**
     * How high the light reported at that place hangs: airHeight times how far up the unit there is, or as high as the
     * shot there is on its way down from its unit; 0 where neither is.
     */
    private float heightOf(float x, float y){
        if(airHeight <= 0f) return 0f;
        findUnits();
        int place = place(x, y);
        if(unitsAt.containsKey(place)) return unitsAt.get(place, 0f) * airHeight;
        Bullet shot = shotAt(x, y);
        if(shot == null) return 0f;
        float height = shotHeight(shot);
        return height < lowest ? 0f : height;
    }

    /**
     * Is that light one of a moving thing? The game does not say who reports a light, so it is told by where the light
     * is: a light exactly on the middle of a building is that building's own (lamps, cores, generators), one exactly
     * on a unit is the unit's own glow - everything else is a shot or an effect.
     */
    private boolean moves(float x, float y){
        Building build = Vars.world.buildWorld(x, y);
        if(build != null && Math.abs(build.x - x) < 0.01f && Math.abs(build.y - y) < 0.01f) return false;
        findUnits();
        return !unitsAt.containsKey(place(x, y));
    }

    /**
     * Is that light the glow of the ground itself - of the floor, of what lies on it, or of a rock standing there? The
     * game does not say who reports a light; such a light sits exactly on the middle of a tile without a building and
     * has the radius that tile's floor, overlay or block glows with (Block.drawEnvironmentLight).
     */
    private static boolean ofGround(float x, float y, float radius){
        Tile tile = Vars.world.tileWorld(x, y);
        if(tile == null || tile.build != null || tile.worldx() != x || tile.worldy() != y) return false;
        return glows(tile.floor(), radius) || glows(tile.overlay(), radius) || glows(tile.block(), radius);
    }

    private static boolean glows(Block block, float radius){
        return block != null && block.emitLight && block.lightRadius == radius;
    }

    /**
     * The colour used for the light of a shot in place of the one the game reports, found by the very Color object the
     * shot's type hands over (matchShots gives each such type an object of its own).
     */
    private static final java.util.IdentityHashMap<Color, Color> shotColors = new java.util.IdentityHashMap<>();

    /**
     * Gives the light of a shot the colour of the shot.
     *
     * The game picks the colour of a shot's light apart from the colour of the shot, and where the content says nothing
     * it is a fixed one: orange for every continuous laser (ContinuousLaserBulletType), white for every continuous
     * flame, a pale yellow for every plain bullet (BulletType.lightColor = Pal.powerLight). So the green laser of a
     * vela threw orange light. In the game that hardly shows - its lighting only takes darkness away - but here the
     * colour of a light is seen: on the ground, and on the faces of normal-mapped blocks.
     *
     * For every kind of shot whose light colour is one of those fixed ones and whose own colour is clearly another
     * (more than 25 degrees of hue away, or the light is white and the shot is not): the type gets a Color object of
     * its own with the same value - so the game itself draws exactly what it drew - and while the mod is listening,
     * a light reported with that object is given the shot's colour instead (ownColor).
     * @return how many kinds of shots were matched
     */
    static int matchShots(){
        int matched = 0;
        for(BulletType type : Vars.content.bullets()){
            Color given = type.lightColor;
            // any other colour was chosen by whoever made the shot
            if(given != Pal.powerLight && given != Color.orange && given != Color.white) continue;
            Color own = type instanceof ContinuousLaserBulletType l && l.colors.length > 0 ? l.colors[Math.max(l.colors.length - 2, 0)]
                : type instanceof ContinuousFlameBulletType f && f.colors.length > 0 ? f.colors[Math.max(f.colors.length - 2, 0)]
                : type instanceof LightningBulletType ? type.lightningColor
                : type instanceof BasicBulletType b ? b.backColor : null;
            if(own == null || !differs(given, own)) continue;
            Color key = given.cpy();
            type.lightColor = key;
            shotColors.put(key, new Color(Mathf.clamp(own.r), Mathf.clamp(own.g), Mathf.clamp(own.b), 1f));
            matched++;
        }
        return matched;
    }

    /** Is the colour of that shot clearly another than that of its light? */
    private static boolean differs(Color light, Color shot){
        float shotHue = hue(shot), lightHue = hue(light);
        if(shotHue < 0f) return false;   // a white or grey shot: any light will do
        if(lightHue < 0f) return true;
        float apart = Math.abs(shotHue - lightHue);
        return Math.min(apart, 360f - apart) > 25f;
    }

    /** Hue in degrees, or -1 for a colour with (almost) none. */
    private static float hue(Color c){
        float max = Math.max(c.r, Math.max(c.g, c.b)), min = Math.min(c.r, Math.min(c.g, c.b)), d = max - min;
        if(max <= 0f || d / max < 0.25f) return -1f;
        float h = max == c.r ? (c.g - c.b) / d : max == c.g ? (c.b - c.r) / d + 2f : (c.r - c.g) / d + 4f;
        return (h * 60f + 360f) % 360f;
    }

    /** Colours lasers are drawn in, as objects that stay as they are (a light keeps the object it was reported with until it is drawn). */
    private final IntMap<Color> laserColors = new IntMap<>();

    /** The colour the sprite batch is drawing with right now, in 32 steps per channel; "else" if that is next to black. */
    private Color drawnColor(Color otherwise){
        Color now = Draw.getColor();
        if(Math.max(now.r, Math.max(now.g, now.b)) < 0.15f) return otherwise;
        int key = (int)(Mathf.clamp(now.r) * 31f + 0.5f) << 10 | (int)(Mathf.clamp(now.g) * 31f + 0.5f) << 5 | (int)(Mathf.clamp(now.b) * 31f + 0.5f);
        Color kept = laserColors.get(key);
        if(kept == null) laserColors.put(key, kept = new Color((key >> 10) / 31f, (key >> 5 & 31) / 31f, (key & 31) / 31f, 1f));
        return kept;
    }

    /** The colour a light reported with that Color object is given while the mod is listening: the shot's own (matchShots), else the same. */
    private Color ownColor(Color reported){
        if(!capture || shotColors.isEmpty()) return reported;
        Color own = shotColors.get(reported);
        return own != null ? own : reported;
    }

    /**
     * How much stronger the purple glow of an exploding thorium reactor is made (asked for: "about ten times").
     * The explosion (Fx.reactorExplosion) is some eighty puffs of smoke that drift outwards for eight seconds, each
     * with a small purple light of its own (Pal.reactorPurple, handed over as that very object - nothing else in the
     * game lights with it). Like every light made stronger here: k times the opacity (up to 1) and sqrt(k) times the
     * radius.
     * The number is the setting "ReactorFUNvalue" (ShaderMod.reactorFun), ten until somebody changes it - and nobody
     * can before a reactor has blown up in front of them: the first time these lights are seen, the setting appears.
     */
    private float reactorBoost = 10f;
    /**
     * The colour that glow is given. The game's is a pale lilac (Pal.reactorPurple, bf92f9); made strong it lit the
     * ground more white and pink than purple. Each part squared over the largest - the same deepening the glow of
     * lava got (Scene.deep) - gives about 9356f9: the blue stays, red and green fall back.
     */
    private static final Color reactorColor = deepened(Pal.reactorPurple);

    private static Color deepened(Color c){
        float top = Math.max(Math.max(c.r, c.g), Math.max(c.b, 0.001f));
        return new Color(c.r * c.r / top, c.g * c.g / top, c.b * c.b / top, 1f);
    }
    /**
     * Of those puffs only every fourth is taken as a light. Made this strong, all eighty would take every place under
     * the light limit for eight seconds, and the lamps of the base around the reactor would go out; at three times the
     * radius the lights of the puffs overlap so far that a quarter of them floods the same ground.
     */
    private static final int reactorEvery = 4;
    private long reactorFrame = -1;
    private int reactorSeen;

    @Override
    public void add(float x, float y, float radius, Color color, float opacity){
        if(capture && color == Pal.reactorPurple){
            long frame = Core.graphics.getFrameId();
            if(frame != reactorFrame){
                reactorFrame = frame;
                reactorSeen = 0;
                reactorBoost = Math.max(Core.settings.getInt(ShaderMod.reactorFun, 10), 1);
                if(!Core.settings.getBool(ShaderMod.reactorFound, false)) ShaderMod.foundReactor();
            }
            // the same puffs every frame: the effect goes through them in the same order
            if(reactorSeen++ % reactorEvery != 0){
                if(gameLighting() && !takeover) super.add(x, y, radius, color, opacity);
                return;
            }
            radius *= Mathf.sqrt(reactorBoost);
            opacity = Math.min(opacity * reactorBoost, 1f);
            color = reactorColor;
        }
        color = ownColor(color);
        if(capture && ofGround(x, y, radius)){
            // kept apart from the light sources: a lake of slag is hundreds of these (see ground)
            if(radius > 0f && opacity > 0f){
                ground.add(x, y, radius, color.r);
                ground.add(color.g, color.b, opacity);
            }
            if(gameLighting() && !takeover) super.add(x, y, radius, color, opacity);
            return;
        }
        if(moving != 1f && moves(x, y)){
            radius *= Mathf.sqrt(moving);
            opacity = Math.min(opacity * moving, 1f);
        }
        record(x, y, radius, color, opacity, heightOf(x, y));
        if(gameLighting() && !takeover) super.add(x, y, radius, color, opacity);
    }

    @Override
    public void add(float x, float y, TextureRegion region, float rotation, Color color, float opacity){
        // sprite-shaped light. Without the lighting rule it is recorded as a circle about as large as the sprite
        // (4 texture pixels = 1 world unit); with it, the game's own drawing below is used (it ends up in add(Runnable)).
        if(!gameLighting()) record(x, y, Math.max(region.width * Draw.xscl, region.height * Draw.yscl) / 8f, color, opacity, 0f);
        else super.add(x, y, region, rotation, color, opacity);
    }

    @Override
    public void line(float x, float y, float x2, float y2, float stroke, Color tint, float alpha){
        tint = ownColor(tint);
        // The light of a laser sprite (Drawf.laser: sap lasers, repair and mining beams, tractor beams, the lines between
        // power nodes) is always the same: 30 wide, orange, 0.3 (Drawf.light(x, y, x2, y2)) - whatever colour the laser is
        // drawn in. That colour is still set while the light is reported: the light takes it.
        if(capture && tint == Color.orange && stroke == 30f && alpha == 0.3f) tint = drawnColor(tint);
        // beam light. Without the lighting rule it is recorded as a few circles along the beam; with it, the game's own
        // drawing below is used (it ends up in add(Runnable)).
        if(moving != 1f){
            // Beams of weapons, drills and mining units are made stronger like the shots. The glowing lines between
            // power nodes are beams to the game too, but they belong to the base: a line that both starts and ends
            // on a building with power is left as it is.
            Building from = Vars.world.buildWorld(x, y), to = Vars.world.buildWorld(x2, y2);
            if(!(from != null && to != null && from != to && from.block.hasPower && to.block.hasPower)){
                stroke *= Mathf.sqrt(moving);
                alpha = Math.min(alpha * moving, 1f);
            }
        }
        if(!gameLighting()){
            // the beam of a shot fired by a unit off the ground starts at that unit's height and slants down to the
            // ground at its far end (or stays up: staysUp)
            Bullet shot = airHeight > 0f ? shotAt(x, y) : null;
            float from = shot == null ? 0f : muzzle(shot), to = shot != null && staysUp(shot) ? from : 0f;
            int n = Mathf.clamp((int)(Mathf.dst(x, y, x2, y2) / Math.max(stroke, 8f)) + 1, 1, 6);
            for(int i = 0; i < n; i++){
                float f = n == 1 ? 0.5f : i / (float)(n - 1), height = Mathf.lerp(from, to, f);
                record(Mathf.lerp(x, x2, f), Mathf.lerp(y, y2, f), stroke * 1.5f, tint, alpha, height < lowest ? 0f : height);
            }
        }else{
            super.line(x, y, x2, y2, stroke, tint, alpha);
        }
    }

    /** Called by Renderer.draw() at Layer.light, only on maps with the lighting rule. */
    @Override
    public void draw(){
        if(takeover && scene != null){
            try{
                scene.drawGameLighting(extras);
            }finally{
                extras.clear();
            }
        }else{
            extras.clear();
            super.draw();
        }
    }
}
