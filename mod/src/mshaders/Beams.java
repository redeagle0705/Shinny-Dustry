package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;

/**
 * Lasers and beams: continuous lasers and flames (meltdown, the flame turrets and units of Erekir), the lancer's laser,
 * point lasers. Where one is stopped by a block it burns it: glowing spots, white-hot, then orange, red, dark, that
 * cool off over a few seconds and leave soot - a beam sweeping along a wall leaves a glowing track. (Not where a beam
 * merely ends over open ground: the maker - "the ground isn't 'hit', no need to mark it".) And the air shimmers along
 * the beam (HeatHaze).
 */
public class Beams{
    private static final int kept = 220;
    private static final float[] emberX = new float[kept], emberY = new float[kept], emberAt = new float[kept], emberR = new float[kept];
    /** The building each burn is on (its position, Point2.pack): the burn goes with it when it is gone. */
    private static final int[] emberOn = new int[kept];
    private static int next;
    private static final float life = 300f;
    private static final Color c = new Color();
    private static final Rect view = new Rect(), camera = new Rect();

    public static void init(){
        Events.run(Trigger.draw, Beams::draw);
        Events.on(WorldLoadEvent.class, e -> {
            for(int i = 0; i < kept; i++) emberR[i] = 0f;
        });
    }

    static boolean on(){
        return Core.settings.getBool(ShaderMod.beamsOn, true);
    }

    /**
     * Burns where a beam from the bullet's place to (x2, y2) meets the buildings of other teams: on each one it enters
     * (a piercing laser goes through several - at first only the end of the beam was looked at, and a laser that went
     * on through enemy walls burnt nothing on them), and on the one that stops it, just past the end.
     */
    private static void burns(Bullet b, float x2, float y2, float r, int each){
        float d = Mathf.dst(b.x, b.y, x2, y2);
        if(d < 0.01f) return;
        float dx = (x2 - b.x) / d, dy = (y2 - b.y) / d;
        Building last = null;
        int found = 0;
        for(float s = 0f; s <= d + 3f && found < 6; s += 2f){
            float x = b.x + dx * s, y = b.y + dy * s;
            var build = Vars.world.buildWorld(x, y);
            if(build != null && build != last && build.team != b.team){
                for(int i = 0; i < each; i++) ember(build, x + dx * 1.5f, y + dy * 1.5f, r);
                found++;
            }
            last = build;
        }
    }

    private static void ember(Building build, float x, float y, float r){
        emberOn[next] = build.pos();
        emberX[next] = x + Mathf.range(r * 0.3f);
        emberY[next] = y + Mathf.range(r * 0.3f);
        emberAt[next] = Time.time;
        emberR[next] = r * (0.7f + Mathf.random(0.5f));
        next = (next + 1) % kept;
    }

    private static void draw(){
        if(!on() || Vars.state.isMenu()) return;
        Core.camera.bounds(view);
        camera.set(view);
        view.grow(400f);
        boolean paused = Vars.state.isPaused();
        for(Bullet b : Groups.bullet){
            if(!view.contains(b.x, b.y)) continue;
            BulletType type = b.type;
            float x2, y2, strength, width;
            if(type instanceof ContinuousLaserBulletType laser){
                float fout = Mathf.clamp(b.time > b.lifetime - laser.fadeTime ? 1f - (b.time - (b.lifetime - laser.fadeTime)) / laser.fadeTime : 1f);
                float len = Damage.findLength(b, laser.length * fout, laser.laserAbsorb, laser.pierceCap);
                x2 = b.x + Angles.trnsx(b.rotation(), len);
                y2 = b.y + Angles.trnsy(b.rotation(), len);
                strength = fout;
                width = laser.width;
            }else if(type instanceof ContinuousFlameBulletType flame){
                float len = Damage.findLength(b, flame.currentLength(b), flame.laserAbsorb, flame.pierceCap);
                x2 = b.x + Angles.trnsx(b.rotation(), len);
                y2 = b.y + Angles.trnsy(b.rotation(), len);
                strength = Mathf.clamp(b.fslope() * 2f);
                width = flame.width;
            }else if(type instanceof LaserBulletType laser){
                x2 = b.x + Angles.trnsx(b.rotation(), b.fdata);
                y2 = b.y + Angles.trnsy(b.rotation(), b.fdata);
                strength = b.fout();
                width = laser.width * 0.6f;
                // its burn: once, as it fires
                if(b.time <= Time.delta + 0.01f && !paused){
                    // (small: at first as wide as the whole beam, much larger than the thin line it leaves)
                    burns(b, x2, y2, Math.max(laser.width * 0.22f, 2.5f), 2);
                }
            }else if(type instanceof PointLaserBulletType){
                x2 = b.aimX;
                y2 = b.aimY;
                strength = b.fslope();
                width = 6f;
            }else{
                continue;
            }
            if(strength <= 0.01f) continue;
            // the burns of a continuous beam, now and then
            if(!(type instanceof LaserBulletType) && !paused && Mathf.chanceDelta(0.45f * strength)) burns(b, x2, y2, Math.max(width * 0.8f, 5f), 1);
            // the air along it shimmers (only where that shows: points off screen took the places of those on it)
            float len = Mathf.dst(b.x, b.y, x2, y2), reach = 9f + width * 0.8f;
            int n = Math.min((int)(len / 18f) + 1, 10);
            for(int i = 0; i <= n; i++){
                float t = n == 0 ? 1f : i / (float)n;
                float px = Mathf.lerp(b.x, x2, t), py = Mathf.lerp(b.y, y2, t);
                if(camera.overlaps(px - reach, py - reach, reach * 2f, reach * 2f)) HeatHaze.extra(px, py, reach, 0.85f * strength);
            }
            // the snow on the ground melts along it (a lancer's shot at once, a continuous beam as it stays)
            boolean once = type instanceof LaserBulletType;
            if(!paused && (!once || b.time <= Time.delta + 0.01f)){
                float r = Math.max(width * 0.7f, 4f) + 3f, step = r * 0.8f;
                int m = Math.min((int)(len / step) + 1, 60);
                for(int i = 0; i <= m; i++){
                    float t = m == 0 ? 1f : i / (float)m;
                    SnowCover.stamp(Mathf.lerp(b.x, x2, t), Mathf.lerp(b.y, y2, t), r, (once ? 0.9f : 0.12f) * strength);
                }
                SnowCover.stamp(x2, y2, r * 2f, (once ? 0.8f : 0.15f) * strength);
            }
        }
        drawEmbers();
    }

    private static void drawEmbers(){
        TextureRegion soft = Core.atlas.find("circle-shadow");
        if(!soft.found()) return;
        Core.camera.bounds(view);
        boolean lights = Vars.renderer.lights != null;
        // gone with the building it was on
        for(int i = 0; i < kept; i++){
            if(emberR[i] <= 0f) continue;
            var build = Vars.world.build(emberOn[i]);
            if(build == null || !build.isValid()) emberR[i] = 0f;
        }
        // on the blocks: soot first, under the glow
        Draw.z(Layer.blockOver + 0.1f);
        for(int i = 0; i < kept; i++){
            float r = emberR[i];
            if(r <= 0f) continue;
            float age = Time.time - emberAt[i];
            if(age > life * 3f){
                emberR[i] = 0f;
                continue;
            }
            float x = emberX[i], y = emberY[i];
            if(!view.overlaps(x - r * 2f, y - r * 2f, r * 4f, r * 4f)) continue;
            // the soot stays a while after the glow has gone
            float k = age / (life * 3f);
            c.set(0.06f, 0.045f, 0.035f, 0.30f * Mathf.clamp(age / 20f) * (1f - k * k));
            Draw.color(c);
            Draw.rect(soft, x, y, r * 1.6f, r * 1.6f);
        }
        Draw.z(Layer.blockOver + 0.2f);
        Draw.blend(Blending.additive);
        for(int i = 0; i < kept; i++){
            float r = emberR[i];
            if(r <= 0f) continue;
            float age = Time.time - emberAt[i];
            if(age > life) continue;
            float x = emberX[i], y = emberY[i];
            if(!view.overlaps(x - r * 2f, y - r * 2f, r * 4f, r * 4f)) continue;
            // white-hot, then yellow, orange, a dull red, gone
            float k = age / life;
            if(k < 0.08f) c.set(1f, 0.85f, 0.55f, 1f).lerp(1f, 0.75f, 0.3f, 1f, k / 0.08f);
            else if(k < 0.35f) c.set(1f, 0.75f, 0.3f, 1f).lerp(0.9f, 0.3f, 0.08f, 1f, (k - 0.08f) / 0.27f);
            else c.set(0.9f, 0.3f, 0.08f, 1f).lerp(0.35f, 0.05f, 0.02f, 1f, (k - 0.35f) / 0.65f);
            // (smaller and less glaring than at first: a white disc as wide as two tiles, on the lancer's hits)
            float a = (1f - k) * (1f - k) * 0.6f;
            c.a = a;
            Draw.color(c);
            float size = r * (1.2f - 0.4f * k);
            Draw.rect(soft, x, y, size * 1.05f, size * 1.05f);
            if(lights && k < 0.6f) Vars.renderer.lights.add(x, y, r * 4f, c, a * 0.5f);
        }
        Draw.blend();
        Draw.color();
    }
}
