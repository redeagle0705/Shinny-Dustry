package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * Heat from hits: a block that takes a lot of fire heats up - a dull red glow in its own shape, then orange as it gets
 * hotter - and cools off over a few seconds once the fire stops. One shot leaves hardly anything; a stream of them
 * makes the block glow. (From the game's BuildDamageEvent: each shot that hits a block, with its damage.)
 */
public class HitHeat{
    /** Heat of each block that was hit lately, by its position (Point2.pack): 0 .. 1. */
    private static final IntFloatMap heat = new IntFloatMap();
    private static final IntSeq hot = new IntSeq();
    private static final Color c = new Color();
    private static final Rect view = new Rect();
    private static float lastTime;

    public static void init(){
        Events.on(BuildDamageEvent.class, e -> {
            if(e.build == null || e.source == null || !on()) return;
            var b = e.build;
            float damage = e.source.damage * e.source.type.buildingDamageMultiplier;
            if(damage <= 0f) return;
            // by the damage, only a little less for a block with much health (by its health alone, a large wall under
            // steady fire hardly warmed): a stream of small shots heats a wall in about a second, a shot now and then
            // leaves only a faint warmth
            float add = damage / (40f + b.maxHealth * 0.05f) * ShaderMod.percent(ShaderMod.hitHeatStrength, 100);
            int key = b.pos();
            float was = heat.get(key, 0f);
            if(was <= 0f) hot.add(key);
            heat.put(key, Math.min(was + add, 1f));
        });
        Events.on(WorldLoadEvent.class, e -> {
            heat.clear();
            hot.clear();
        });
        Events.run(Trigger.draw, HitHeat::draw);
    }

    /** How hot hits have made this building, 0 .. 1. */
    static float heatAt(int pos){
        return heat.get(pos, 0f);
    }

    static boolean on(){
        return Core.settings.getBool(ShaderMod.hitHeatOn, true);
    }

    private static void draw(){
        if(hot.isEmpty()) return;
        // cooling: by game time (stands still while paused), most of the heat gone in about six seconds
        float now = Time.time, dt = Math.max(now - lastTime, 0f);
        lastTime = now;
        float keep = (float)Math.exp(-dt / 200f);
        Core.camera.bounds(view);
        boolean lights = Vars.renderer.lights != null;
        for(int i = hot.size - 1; i >= 0; i--){
            int key = hot.get(i);
            float h = heat.get(key, 0f) * keep;
            var build = Vars.world.build(key);
            if(h < 0.02f || build == null || !build.isValid() || !on()){
                heat.remove(key, 0f);
                hot.removeIndex(i);
                continue;
            }
            heat.put(key, h);
            float size = build.block.size * Vars.tilesize;
            if(!view.overlaps(build.x - size, build.y - size, size * 2f, size * 2f)) continue;
            // the air over it shimmers too, as over anything hot (HeatHaze; the maker: "it would be good if it got the
            // shimmer too")
            if(h > 0.15f) HeatHaze.extra(build.x, build.y, size * 0.95f + 6f, (h - 0.15f) * 0.3f);
            // dull red, then orange, then a hot yellow at the most
            if(h < 0.5f) c.set(0.55f, 0.08f, 0.02f, 1f).lerp(1f, 0.35f, 0.05f, 1f, h / 0.5f);
            else c.set(1f, 0.35f, 0.05f, 1f).lerp(1f, 0.75f, 0.3f, 1f, (h - 0.5f) / 0.5f);
            // only the hotter part shows: a little heat is not seen
            float a = Mathf.clamp((h - 0.05f) / 0.95f);
            a = (float)Math.pow(a, 1.5) * 0.9f;
            if(a <= 0.005f) continue;
            // over the block (over a turret's head too), in its own shape: first the colour of hot metal laid over it,
            // then a glow added (added alone, red on a blue wall came out pink)
            Draw.z(build.block instanceof BaseTurret ? Layer.turretHeat + 0.1f : Layer.blockOver + 0.4f);
            c.a = Math.min(a * 1.2f, 0.95f);
            Draw.color(c);
            Draw.rect(build.block.fullIcon, build.x, build.y);
            Draw.blend(Blending.additive);
            c.a = a * 0.5f;
            Draw.color(c);
            Draw.rect(build.block.fullIcon, build.x, build.y);
            Draw.blend();
            c.a = a;
            if(lights && a > 0.05f) Vars.renderer.lights.add(build.x, build.y, size * 1.6f, c, a * 0.5f);
        }
        Draw.color();
    }
}
