package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.draw.*;

/**
 * The flame of a furnace (DrawFlame: the silicon smelter, the kiln, ...) as a fire. The game draws a pale disc with a
 * white one on it and a steady light - "like a light bulb"; a smelter burns coal to get silicon out of sand ("it would
 * be good to make it feel a little like fire"). Here: a glow of the block's flame colour drawn towards orange, three
 * tongues that waver and flicker, a hot core that is yellow, not white, now and then a spark, and a light that
 * flickers with it. As it cools (warmup falling) it goes deep red, then out - embers.
 *
 * Put in place of the game's DrawFlame in each block's drawer (it is one: drawer objects belong to the block type), with
 * everything of the game's copied over; with the setting off it draws as the game's own.
 */
public class FurnaceFire extends DrawFlame{
    private static final Color fire = new Color(1f, 0.5f, 0.12f, 1f), ember = new Color(0.75f, 0.12f, 0.03f, 1f), core = new Color(1f, 0.68f, 0.28f, 1f);
    private static final Color c1 = new Color(), c2 = new Color();
    private static TextureRegion soft;

    /** Sparks thrown up off the fire: a bright point flying out and falling back, yellow cooling to red. */
    static final Effect spark = new Effect(28f, e -> {
        float d = e.finpow() * (5f + Mathf.randomSeed(e.id, 4f));
        Draw.color(c1.set(1f, 0.85f, 0.5f, 1f).lerp(0.9f, 0.2f, 0.05f, 1f, e.fin()));
        Draw.alpha(e.fout());
        Fill.circle(e.x + Angles.trnsx(e.rotation, d), e.y + Angles.trnsy(e.rotation, d), 0.55f * e.fout() + 0.15f);
        Draw.reset();
    });

    static boolean on(){
        return Core.settings.getBool(ShaderMod.fireOn, true);
    }

    /** Puts a fire in place of every plain DrawFlame of the crafters. @return how many blocks have one now */
    static int install(){
        int n = 0;
        for(Block block : Vars.content.blocks()){
            if(!(block instanceof GenericCrafter crafter) || crafter.drawer == null) continue;
            DrawBlock before = crafter.drawer;
            crafter.drawer = swap(crafter.drawer);
            if(crafter.drawer != before || has(crafter.drawer)) n++;
        }
        return n;
    }

    private static boolean has(DrawBlock d){
        if(d instanceof FurnaceFire) return true;
        if(d instanceof DrawMulti m) for(DrawBlock part : m.drawers) if(has(part)) return true;
        return false;
    }

    private static DrawBlock swap(DrawBlock d){
        if(d != null && d.getClass() == DrawFlame.class) return copy((DrawFlame)d);
        if(d instanceof DrawMulti m && m.drawers != null){
            for(int i = 0; i < m.drawers.length; i++) m.drawers[i] = swap(m.drawers[i]);
        }
        return d;
    }

    private static FurnaceFire copy(DrawFlame from){
        FurnaceFire f = new FurnaceFire();
        f.flameColor = from.flameColor;
        f.top = from.top;
        f.lightRadius = from.lightRadius;
        f.lightAlpha = from.lightAlpha;
        f.lightSinScl = from.lightSinScl;
        f.lightSinMag = from.lightSinMag;
        f.flameRadius = from.flameRadius;
        f.flameRadiusIn = from.flameRadiusIn;
        f.flameRadiusScl = from.flameRadiusScl;
        f.flameRadiusMag = from.flameRadiusMag;
        f.flameRadiusInMag = from.flameRadiusInMag;
        f.flameX = from.flameX;
        f.flameY = from.flameY;
        return f;
    }

    /** A flicker, about 0.75 .. 1.15, of its own for each building: a few waves of different speeds. */
    private static float flicker(float t, float seed){
        return 0.95f + 0.09f * Mathf.sin(t * 0.23f + seed) + 0.07f * Mathf.sin(t * 0.61f + seed * 1.7f) + 0.05f * Mathf.sin(t * 1.37f + seed * 2.3f);
    }

    /** The fire's colour at that heat: embers deep red, burning orange, with a touch of the block's own flame colour. */
    private Color tone(Color out, float w){
        out.set(flameColor).lerp(fire, 0.7f);
        return out.lerp(ember, 1f - Mathf.clamp(w * 1.6f));
    }

    @Override
    public void draw(Building build){
        if(!on()){
            super.draw(build);
            return;
        }
        float w = build.warmup();
        if(w <= 0.001f || flameColor.a <= 0.001f) return;
        if(soft == null) soft = Core.atlas.find("circle-shadow");
        Draw.z(Layer.block + 0.01f);
        // the lid, lit from inside as the game has it
        Draw.alpha(w);
        if(top != null && top.found()) Draw.rect(top, build.x, build.y);
        float x = build.x + flameX, y = build.y + flameY;
        float t = Time.time, seed = build.id * 13.7f % 100f;
        float f = flicker(t, seed), r = Math.max(flameRadius, 1.5f);
        tone(c1, w);
        Draw.blend(Blending.additive);
        // the glow round it
        Draw.color(c1, 0.28f * w * f);
        Draw.rect(soft, x, y, r * 5.2f * f, r * 5.2f * f);
        // three tongues, each wavering about the middle at a pace of its own
        for(int k = 0; k < 3; k++){
            float ang = seed * 40f + k * 120f + t * (1.1f + k * 0.45f) + Mathf.sin(t * 0.07f + k * 2f + seed) * 40f;
            float off = r * (0.25f + 0.2f * Mathf.absin(t + k * 17f + seed, 6f + k * 2f, 1f));
            float s = r * (2.2f + 0.6f * Mathf.sin(t * (0.17f + k * 0.05f) + seed + k * 3f)) * f;
            Draw.color(c2.set(c1).lerp(core, 0.25f), 0.3f * w);
            Draw.rect(soft, x + Angles.trnsx(ang, off), y + Angles.trnsy(ang, off), s, s);
        }
        // the hot core: yellow-orange, never white
        Draw.color(c2.set(core).lerp(ember, 1f - Mathf.clamp(w * 1.4f)), 0.5f * w);
        float cs = r * 1.7f * (0.85f + 0.3f * (f - 0.95f) / 0.2f);
        Draw.rect(soft, x, y, cs, cs);
        Draw.blend();
        Draw.reset();
        // now and then a spark
        if(!Vars.state.isPaused() && w > 0.3f && Mathf.chanceDelta(0.03f * w)) spark.at(x + Mathf.range(r * 0.5f), y + Mathf.range(r * 0.5f), Mathf.random(360f));
    }

    @Override
    public void drawLight(Building build){
        if(!on()){
            super.drawLight(build);
            return;
        }
        float w = build.warmup();
        if(w <= 0.001f) return;
        float f = flicker(Time.time, build.id * 13.7f % 100f);
        tone(c2, w);
        Drawf.light(build.x + flameX, build.y + flameY, (lightRadius + Mathf.absin(lightSinScl, lightSinMag)) * (0.85f + 0.25f * (f - 0.75f)), c2, lightAlpha * w * f);
    }
}
