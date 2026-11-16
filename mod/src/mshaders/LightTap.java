package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;

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
            return Vars.renderer.lights == tap ? tap : null;
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
    private long unitsFrame = -1;

    private static int place(float x, float y){
        return Float.floatToIntBits(x) * 31 + Float.floatToIntBits(y);
    }

    private void findUnits(){
        long frame = Core.graphics.getFrameId();
        if(frame == unitsFrame) return;
        unitsFrame = frame;
        unitsAt.clear();
        for(Unit unit : Groups.unit) unitsAt.put(place(unit.x, unit.y), unit.isFlying() ? Mathf.clamp(unit.elevation) : 0f);
    }

    /** How high the light reported at that place hangs: airHeight times how far up the unit there is; 0 where no flying unit is. */
    private float heightOf(float x, float y){
        if(airHeight <= 0f) return 0f;
        findUnits();
        return unitsAt.get(place(x, y), 0f) * airHeight;
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

    @Override
    public void add(float x, float y, float radius, Color color, float opacity){
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
            int n = Mathf.clamp((int)(Mathf.dst(x, y, x2, y2) / Math.max(stroke, 8f)) + 1, 1, 6);
            for(int i = 0; i < n; i++){
                float f = n == 1 ? 0.5f : i / (float)(n - 1);
                record(Mathf.lerp(x, x2, f), Mathf.lerp(y, y2, f), stroke * 1.5f, tint, alpha, 0f);
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
