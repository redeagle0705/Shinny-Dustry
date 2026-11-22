package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;

/**
 * Units show what is happening to them (the game shows it only with a few particles):
 *   wet        a cool sheen over the body that slides as the unit turns, and a glint on the side facing the sun
 *   burning    the body glows from within, flickering like embers, and lights the ground around it on a dark map
 *   freezing   frost: the body turns pale, and its rim is white
 *   shocked, electrified  short bright arcs crawl over the body, now here, now there
 *   overdrive, overclock  a warm glow that breathes
 * Drawn over the unit's body (its sprite, the way the game lays it), each status as a tint of that same picture.
 */
public class Statuses{
    private static final Color tint = new Color();
    private static final Rect view = new Rect();

    public static void init(){
        Events.run(Trigger.draw, Statuses::draw);
    }

    private static void draw(){
        if(!Core.settings.getBool(ShaderMod.statusesOn, true) || Vars.state.isMenu()) return;
        float k = ShaderMod.percent(ShaderMod.statusesStrength, 100);
        if(k <= 0f) return;
        Core.camera.bounds(view);
        Groups.unit.each(u -> {
            if(!u.isValid() || u.inFogTo(Vars.player.team())) return;
            float r = u.hitSize * 1.5f;
            if(!view.overlaps(u.x - r, u.y - r, r * 2f, r * 2f)) return;
            boolean wet = u.hasEffect(StatusEffects.wet), burning = u.hasEffect(StatusEffects.burning) || u.hasEffect(StatusEffects.melting),
                frozen = u.hasEffect(StatusEffects.freezing),
                shocked = u.hasEffect(StatusEffects.shocked) || u.hasEffect(StatusEffects.electrified),
                driven = u.hasEffect(StatusEffects.overdrive) || u.hasEffect(StatusEffects.overclock);
            if(!wet && !burning && !frozen && !shocked && !driven) return;
            UnitType type = u.type;
            TextureRegion body = type.region;
            if(body == null || !body.found()) return;
            float z = u.elevation > 0.5f ? (type.lowAltitude ? Layer.flyingUnitLow : Layer.flyingUnit) : type.groundLayer + Mathf.clamp(type.hitSize / 4000f, 0, 0.01f);
            Draw.draw(z + 0.015f, () -> overlay(u, body, k, wet, burning, frozen, shocked, driven));
        });
    }

    private static void overlay(Unit u, TextureRegion body, float k, boolean wet, boolean burning, boolean frozen, boolean shocked, boolean driven){
        float t = Time.time, seed = u.id * 1.37f, angle = u.rotation - 90f;
        // (laid on as tints of the body: drawn with additive blending they did not show over the body in the scene
        // buffer)
        if(wet){
            // a cool sheen, brighter on the side facing the sun: how much the unit faces it
            float facing = Mathf.cosDeg(u.rotation - Mathf.atan2(ShaderMod.sunX(), ShaderMod.sunY()) * Mathf.radDeg);
            float a = (0.16f + 0.16f * Math.max(facing, 0f) + 0.05f * Mathf.sin(t * 0.05f + seed)) * k;
            Draw.mixcol(tint.set(0.62f, 0.82f, 1f, 1f), 1f);
            Draw.color(1f, 1f, 1f, Mathf.clamp(a));
            Draw.rect(body, u.x, u.y, angle);
        }
        if(burning){
            // embers: two flickers at their own paces
            float f = 0.5f + 0.3f * Mathf.sin(t * 0.31f + seed) + 0.2f * Mathf.sin(t * 0.77f + seed * 2.1f);
            Draw.mixcol(tint.set(1f, 0.5f, 0.15f, 1f), 1f);
            Draw.color(1f, 1f, 1f, Mathf.clamp((0.22f + 0.26f * f) * k));
            Draw.rect(body, u.x, u.y, angle);
            if(Vars.state.rules.lighting && Vars.renderer.lights != null){
                Vars.renderer.lights.add(u.x, u.y, u.hitSize * 2.2f, Pal.lightFlame, Mathf.clamp(0.35f + 0.25f * f));
            }
        }
        if(driven){
            float b = 0.5f + 0.5f * Mathf.sin(t * 0.08f + seed);
            Draw.mixcol(tint.set(1f, 0.75f, 0.4f, 1f), 1f);
            Draw.color(1f, 1f, 1f, Mathf.clamp((0.14f + 0.14f * b) * k));
            Draw.rect(body, u.x, u.y, angle);
        }
        Draw.mixcol();
        if(frozen){
            // frost: a pale skin over the body, and a white rim (the body a little larger, behind... laid over, faint)
            Draw.mixcol(Color.white, 1f);
            Draw.color(1f, 1f, 1f, Mathf.clamp(0.30f * k));
            Draw.rect(body, u.x, u.y, angle);
            Draw.mixcol(tint.set(0.85f, 0.95f, 1f, 1f), 1f);
            Draw.color(1f, 1f, 1f, Mathf.clamp(0.22f * k));
            float grow = 1f + 2.4f / Math.max(body.width * Draw.scl, 1f);
            Draw.rect(body, u.x, u.y, body.width * Draw.scl * grow, body.height * Draw.scl * grow, angle);
            Draw.mixcol();
        }
        if(shocked){
            // two or three short arcs, each living a few ticks, crawling over the body
            int span = (int)(t / 5f);
            Draw.color(tint.set(0.75f, 0.9f, 1f, Mathf.clamp(0.9f * k)));
            Lines.stroke(1.1f);
            for(int a = 0; a < 3; a++){
                long s = (long)u.id * 7919L + span * 104729L + a * 31L;
                if(Mathf.randomSeed(s) > 0.75f) continue;
                float sx = u.x + Mathf.randomSeedRange(s + 1, u.hitSize * 0.4f), sy = u.y + Mathf.randomSeedRange(s + 2, u.hitSize * 0.4f);
                float dir = Mathf.randomSeed(s + 3, 360f), len = u.hitSize * (0.3f + 0.3f * Mathf.randomSeed(s + 4));
                float px = sx, py = sy;
                for(int j = 1; j <= 4; j++){
                    float along = len * j / 4f, off = Mathf.randomSeedRange(s + 10 + j, u.hitSize * 0.12f);
                    float nx = sx + Angles.trnsx(dir, along) + Angles.trnsx(dir + 90f, off), ny = sy + Angles.trnsy(dir, along) + Angles.trnsy(dir + 90f, off);
                    Lines.line(px, py, nx, ny);
                    px = nx;
                    py = ny;
                }
            }
            if(Vars.state.rules.lighting && Vars.renderer.lights != null){
                Vars.renderer.lights.add(u.x, u.y, u.hitSize * 1.6f, Pal.lancerLaser, 0.3f);
            }
        }
        Draw.reset();
    }
}
