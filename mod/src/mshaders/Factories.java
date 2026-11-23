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
import mindustry.game.Teams.*;
import mindustry.graphics.*;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.units.Reconstructor.*;
import mindustry.world.blocks.units.UnitBlock.*;
import mindustry.world.blocks.units.UnitFactory.*;

/**
 * Unit factories and reconstructors at work. The game draws the unit taking shape, with a line sweeping from side to
 * side across it; here, where that line is, a welding spot burns - white in the middle, in the factory's colour around
 * it - that slides up and down the line and throws short sparks; the factory glows from inside and lights its
 * surroundings on a dark map; and when a unit is finished, a flash.
 */
public class Factories{
    private static final Seq<UnitBuild> builds = new Seq<>();
    private static final IntFloatMap lastProgress = new IntFloatMap();
    /** Flashes of units just finished: x, y, the time, size. */
    private static final int flashesKept = 8;
    private static final float[] flashX = new float[flashesKept], flashY = new float[flashesKept], flashAt = new float[flashesKept], flashR = new float[flashesKept];
    private static int nextFlash;
    private static long lastLook = -100;
    private static final Rect view = new Rect();
    private static final Color c = new Color();

    public static void init(){
        Events.run(Trigger.draw, Factories::draw);
        Events.on(WorldLoadEvent.class, e -> {
            builds.clear();
            lastProgress.clear();
        });
    }

    private static boolean working(UnitBuild b){
        if(b instanceof UnitFactoryBuild f) return f.currentPlan != -1 && b.speedScl > 0.05f;
        if(b instanceof ReconstructorBuild r) return r.constructing() && b.speedScl > 0.05f;
        return false;
    }

    private static void draw(){
        if(!Core.settings.getBool(ShaderMod.factoriesOn, true) || Vars.state.isMenu()) return;
        Core.camera.bounds(view);
        long frame = Core.graphics.getFrameId();
        if(frame - lastLook >= 10){
            lastLook = frame;
            builds.clear();
            float cx = view.x + view.width / 2f, cy = view.y + view.height / 2f, reach = Mathf.dst(view.width, view.height) / 2f + 40f;
            for(TeamData data : Vars.state.teams.present){
                Vars.indexer.eachBlock(data.team, cx, cy, reach, b -> b instanceof UnitFactoryBuild || b instanceof ReconstructorBuild, b -> builds.add((UnitBuild)b));
            }
            if(lastProgress.size > 2000) lastProgress.clear();
        }
        // a unit finished: the progress starts again from nothing
        for(UnitBuild b : builds){
            if(!b.isValid()) continue;
            float was = lastProgress.get(b.id, -1f);
            lastProgress.put(b.id, b.progress);
            if(was > 1f && b.progress < was * 0.5f){
                flashX[nextFlash] = b.x;
                flashY[nextFlash] = b.y;
                flashAt[nextFlash] = Time.time;
                flashR[nextFlash] = b.block.size * Vars.tilesize;
                nextFlash = (nextFlash + 1) % flashesKept;
            }
        }
        if(builds.isEmpty()) return;
        Draw.draw(Layer.blockOver + 0.01f, Factories::welds);
    }

    private static void welds(){
        boolean lit = Vars.state.rules.lighting && Vars.renderer.lights != null;
        float t = Time.time;
        TextureRegion soft = Core.atlas.find("circle-shadow");
        for(UnitBuild b : builds){
            if(!b.isValid() || !working(b)) continue;
            float size = b.block.size * Vars.tilesize - 4f;
            if(!view.overlaps(b.x - size, b.y - size, size * 2f, size * 2f)) continue;
            float s = b.speedScl;
            // where the game's sweeping line is (Drawf.construct), and the spot sliding up and down it
            float lx = b.x + Mathf.sin(b.time, 20f, size / 2f);
            float ly = b.y + Mathf.sin(b.time * 0.37f + b.id, 9f, size * 0.32f);
            // the glow inside the factory
            c.set(Pal.accent).a(0.16f * s);
            Draw.color(c);
            if(soft.found()) Draw.rect(soft, b.x, b.y, size * 1.3f, size * 1.3f);
            // the whole sweeping line burns: a soft glow along it, and a bright thin core
            c.set(Pal.accent).a(0.5f * s);
            Draw.color(c);
            if(soft.found()) Draw.rect(soft, lx, b.y, 7f, size * 1.05f);
            c.set(Color.white).a(0.55f * s);
            Draw.color(c);
            Lines.stroke(1f);
            Lines.line(lx, b.y - size / 2f, lx, b.y + size / 2f);
            // the spot
            c.set(Pal.accent).a(0.9f * s);
            Draw.color(c);
            if(soft.found()) Draw.rect(soft, lx, ly, 13f, 13f);
            c.set(Color.white).a(s);
            Draw.color(c);
            Fill.circle(lx, ly, 1.7f);
            // sparks: thrown from the spot, falling and fading, each living 14 ticks; a new one every 2 ticks
            Lines.stroke(0.9f);
            for(int i = 0; i < 7; i++){
                float born = (float)Math.floor(t / 2f) * 2f - i * 2f, age = t - born;
                long seed = b.id * 7919L + (long)born * 31L;
                float dir = 30f + Mathf.randomSeed(seed, 120f) + (Mathf.randomSeed(seed + 5) < 0.5f ? 0f : 180f), speed = 0.5f + Mathf.randomSeed(seed + 1, 0.7f);
                // (from where the spot was when it was thrown)
                float bx0 = b.x + Mathf.sin(b.time - age * s, 20f, size / 2f), by0 = b.y + Mathf.sin((b.time - age * s) * 0.37f + b.id, 9f, size * 0.32f);
                float vx = Mathf.cosDeg(dir) * speed, vy = Mathf.sinDeg(dir) * speed;
                float px = bx0 + vx * age, py = by0 + vy * age - 0.03f * age * age;
                float f = 1f - age / 14f;
                c.set(Color.white).lerp(Pal.accent, 1f - f).a(s * f);
                Draw.color(c);
                Lines.line(px, py, px - vx * 2.2f, py - (vy - 0.06f * age) * 2.2f);
            }
            if(lit){
                Vars.renderer.lights.add(lx, ly, 26f, Pal.accent, 0.5f * s);
                Vars.renderer.lights.add(b.x, b.y, size * 1.2f, Pal.accent, 0.2f * s);
            }
        }
        // flashes of units just finished
        for(int i = 0; i < flashesKept; i++){
            float age = Time.time - flashAt[i];
            if(flashR[i] <= 0f || age < 0f || age > 14f) continue;
            float f = 1f - age / 14f;
            c.set(Color.white).lerp(Pal.accent, 1f - f).a(0.45f * f * f);
            Draw.color(c);
            Fill.circle(flashX[i], flashY[i], flashR[i] * (0.5f + 0.4f * (1f - f)));
            if(lit) Vars.renderer.lights.add(flashX[i], flashY[i], flashR[i] * 2.5f, Pal.accent, f);
        }
        Draw.reset();
    }
}
