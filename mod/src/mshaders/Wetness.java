package mshaders;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;

/**
 * Blocks wet with rain. While it rains the tops of the blocks get wet over some seconds, and a wet block shines as a
 * glossy one does (the glint and its sky streak, mshaders-post.frag and mshaders-shape.frag: stronger, tighter), a
 * little darker, with drops splashing on it. Where a face slopes the rain runs down it in thin streaks; where it is
 * flat the water stands, in puddles that spread the longer it rains, and the deeper they are the clearer they mirror
 * the sky, the sun and the lamps. After the rain the blocks dry slowly and the puddles go more slowly still; a hot
 * building - a working smelter, a generator, a turret that keeps firing, a block heated by hits (HeatHaze.heatOf,
 * HitHeat) - dries within a few seconds, steaming, and stays dry while it rains.
 *
 * Kept per tile in the gloss picture the shaders read anyway (Occluders.wet): green = how wet, blue = how deep the
 * water stands. No texture of its own.
 */
public class Wetness{
    /** How wet everything not kept dry is: follows the rain, up in some seconds, down slowly after it. */
    static float wet;
    /** How deep the water stands on flat faces: fills slowly while it rains, goes more slowly still after it. */
    static float pool;
    /** How much it rains on the blocks now, 0 .. 1 (for the drops and the runoff). */
    static float raining;

    /** Seconds to get wet through at full rain, and to dry when the rain has stopped. */
    static final float soak = 6f, dry = 75f;
    /** Seconds for the puddles to fill at full rain, and to dry up after it. */
    static final float fill = 30f, evaporate = 120f;

    /** How far each building has dried beyond the rest (0 = as wet as everything, 1 = dry), by position. */
    private static final IntFloatMap dried = new IntFloatMap();
    /** How far the puddles on each building have dried beyond the rest, by position: they fill again at their own slow pace. */
    private static final IntFloatMap drained = new IntFloatMap();
    /** This pass: how much of the standing water each building in view keeps, by position. */
    private static final IntFloatMap poolValues = new IntFloatMap();
    /** This pass: how still the turning head of each building in view is (-1 = none). */
    private static final IntFloatMap steadyValues = new IntFloatMap();
    /** This pass: how much of the wet each building in view keeps (1 - dried), by position (worked out once a building). */
    private static final IntFloatMap values = new IntFloatMap();
    /** The tiles written last time: cleared when nothing is written there any more (a building taken down). */
    private static final IntSeq written = new IntSeq(), writing = new IntSeq();
    private static final IntSet now = new IntSet();
    private static final Rect view = new Rect();
    private static long lastLook = -100;
    private static float lastTime, gathered;
    private static boolean wasOn;
    /** The tiles the last pass went over (x0, y0, x1, y1): a view that has moved out of them is gone over again at once. */
    private static int sx0, sy0, sx1 = -1, sy1 = -1;
    /** The buildings in view on the last pass, and on this one: a hot building seen for the first time is already dry. */
    private static IntSet seen = new IntSet(), seeing = new IntSet();
    /** Tiles beyond the view gone over too, so that a view that moves a little shows no dry edge. */
    static final int margin = 4;
    /** What building() found for the standing water of the building it was asked about. */
    private static float lastPoolKeep = 1f;
    /** What building() found for how long a turning head has been still (0 .. 1; -1 = no head that turns). */
    private static float lastSteady = -1f;
    /** Heads that turn (turrets, mass drivers), by position: the angle last seen, how long it has kept still (ticks), how still it counts (0 .. 1). */
    private static final IntMap<float[]> heads = new IntMap<>();
    /** Units, by id: x, y, angle last seen, how long kept still (ticks), how still it counts (0 .. 1), the frame last seen. */
    private static final IntMap<float[]> units = new IntMap<>();
    /** A head or a unit counts as still after this long without turning or moving (ticks): then water runs on it. */
    static final float stillAfter = 60f;

    static void init(){
        Events.on(WorldLoadEvent.class, e -> {
            dried.clear();
            drained.clear();
            heads.clear();
            units.clear();
            written.clear();
            seen.clear();
            seeing.clear();
            sx1 = sy1 = -1;
            wet = pool = 0f;
        });
    }

    static boolean on(){
        // (it shows through the glint: with that off there is nothing to do)
        return Core.settings.getBool(ShaderMod.wetOn, true) && Core.settings.getBool(ShaderMod.weatherOn, true) && Core.settings.getBool(ShaderMod.glintOn, true);
    }

    /** Is anything wet at all? */
    private static boolean any(){
        return wet > 0.004f || pool > 0.004f;
    }

    /** Before the frame (Scene, at Trigger.preDraw, before the gloss picture is sent): brings the wetness up to date. */
    static void update(Occluders occluders){
        float time = Time.time, dt = ShaderMod.inWorld() && !Vars.state.isPaused() ? Math.min(Math.max(time - lastTime, 0f), 30f) : 0f;
        lastTime = time;
        boolean on = on() && ShaderMod.inWorld();
        float r = on ? Math.min(Skies.rain * Skies.rainK(), 1f) : 0f;
        raining = r;
        if(r > wet) wet = Math.min(r, wet + dt * r / (soak * 60f));
        else wet = Math.max(r, wet - dt / (dry * 60f));
        // the puddles fill only while it rains, as deep as it rains, and go when it has stopped
        if(r > 0.01f) pool = pool < r ? Math.min(r, pool + dt * r / (fill * 60f)) : Math.max(r, pool - dt / (evaporate * 60f));
        else pool = Math.max(pool - dt / (evaporate * 60f), 0f);
        // test kit: hold them where they are asked to be
        int forced = Core.settings.getInt("mshaders-test-wet", -1);
        if(forced >= 0) wet = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-pool", -1);
        if(forced >= 0) pool = forced / 100f;
        if(!on) wet = pool = 0f;

        gathered += dt;
        long frame = Core.graphics.getFrameId();
        // (as soon as the view leaves the tiles the last pass went over - panning fast, a jump on the minimap - the
        // blocks that come into view would show dry for a few frames and then all turn wet at once)
        Core.camera.bounds(view);
        boolean covered = (int)(view.x / Vars.tilesize) >= sx0 && (int)(view.y / Vars.tilesize) >= sy0
            && (int)((view.x + view.width) / Vars.tilesize) + 1 <= sx1 && (int)((view.y + view.height) / Vars.tilesize) + 1 <= sy1;
        if(frame - lastLook < 6 && wasOn == any() && (covered || !any())) return;
        lastLook = frame;
        float step = gathered;
        gathered = 0f;
        wasOn = any();

        writing.clear();
        now.clear();
        if(any()){
            // Every tile in view (and a few beyond) with something on it: buildings, and the map's own blocks too -
            // walls, scenery a map is decorated with, boulders - rain falls on all of them. (At first only buildings
            // were looked at, through the game's list of them: on a map built of decoration blocks nothing got wet.)
            int x0 = Math.max((int)(view.x / Vars.tilesize) - margin, 0), y0 = Math.max((int)(view.y / Vars.tilesize) - margin, 0);
            int x1 = Math.min((int)((view.x + view.width) / Vars.tilesize) + 1 + margin, Vars.world.width() - 1);
            int y1 = Math.min((int)((view.y + view.height) / Vars.tilesize) + 1 + margin, Vars.world.height() - 1);
            sx0 = x0;
            sy0 = y0;
            sx1 = x1;
            sy1 = y1;
            IntSet last = seen;
            seen = seeing;
            seeing = last;
            seeing.clear();
            values.clear();
            poolValues.clear();
            steadyValues.clear();
            for(int y = y0; y <= y1; y++){
                for(int x = x0; x <= x1; x++){
                    Tile tile = Vars.world.tile(x, y);
                    if(tile == null || tile.block() == Blocks.air) continue;
                    float keep, poolKeep, steady = -1f;
                    int open = -1;
                    Building b = tile.build;
                    if(b == null){
                        // The map's own walls: only their outer edge is wet - the rest of a wall is the inside of
                        // the rock, dark, and water running over it looked odd ("only the outside should show").
                        // The game keeps how deep in a wall a tile lies (World.addDarkness: 1 = at the edge).
                        if(tile.isDarkened()){
                            keep = poolKeep = tile.data <= 1 ? 1f : tile.data == 2 ? 0.35f : 0f;
                            // Which sides of it face open ground: such walls are drawn with a bevel along their outer
                            // edge and flat inside ("the inside of these joined walls is all flat"), and the shaders
                            // slope that band outwards so the rain runs off the edge.
                            if(keep > 0f) open = openAt(x, y + 1, 1) | openAt(x + 1, y, 2) | openAt(x, y - 1, 4) | openAt(x - 1, y, 8);
                        }else keep = poolKeep = 1f;
                    }else{
                        int key = b.pos();
                        // a building covers several tiles: worked out once for all of them
                        keep = values.get(key, -1f);
                        if(keep < 0f){
                            seeing.add(key);
                            keep = building(b, key, r, step);
                            values.put(key, keep);
                            poolValues.put(key, lastPoolKeep);
                            steadyValues.put(key, lastSteady);
                        }
                        poolKeep = poolValues.get(key, keep);
                        steady = steadyValues.get(key, -1f);
                    }
                    occluders.wet(x, y, Math.round(Mathf.clamp(wet * keep) * 255f), Math.round(Mathf.clamp(pool * poolKeep) * 255f), steady, open);
                    int pos = Point2.pack(x, y);
                    if(now.add(pos)) writing.add(pos);
                }
            }
            if(dried.size > 4000) dried.clear();
            if(drained.size > 4000) drained.clear();
        }else{
            dried.clear();
            drained.clear();
            seen.clear();
            seeing.clear();
            sx1 = sy1 = -1;
        }
        // what was written before and not now: no building there any more, or all dry
        for(int i = 0; i < written.size; i++){
            int pos = written.get(i);
            if(!now.contains(pos)) occluders.wet(Point2.x(pos), Point2.y(pos), 0, 0, -1f);
        }
        written.clear();
        written.addAll(writing);
    }

    /** That bit if the tile there is open ground (not inside the map's walls), else 0. */
    private static int openAt(int x, int y, int bit){
        Tile t = Vars.world.tile(x, y);
        return t != null && !t.isDarkened() ? bit : 0;
    }

    /**
     * How much of the wet that building keeps now: all of it, less as far as it has dried. A hot building dries in a
     * few seconds, steaming while there is water on it to boil off; a cold one gets wet again only while it rains.
     */
    private static float building(Building b, int key, float rain, float step){
        // a block still being built is a frame of scaffolding: no film of water on it
        // (and the building it turns into starts dry: it gets wet, and its puddles fill, at the pace everything does)
        lastSteady = steadiness(b, key, step);
        if(b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild){
            dried.put(key, 1f);
            drained.put(key, 1f);
            lastPoolKeep = 0f;
            return 0f;
        }
        float heat = SnowCover.ownHeat(b);
        float d = dried.get(key, 0f);
        // a building working away out of sight has been drying all along: hot when first seen, it is dry already
        // (else every smelter came into view soaked and steamed itself dry there and then)
        if(heat > 0.05f && !seen.contains(key)) d = 1f;
        float before = Math.max(wet, pool) * (1f - d);
        if(heat > 0.05f){
            d = Math.min(d + step * heat / (2.5f * 60f), 1f);
            // (a wisp now and then: at first it was a puff several times a second, and a turret firing on and off,
            // wet again between bursts, steamed all the time - "the evaporation is too strong")
            if(before > 0.08f && Mathf.chance(Math.min(step * 0.005f * before * heat * b.block.size, 0.12f))){
                float s = b.block.size * Vars.tilesize * 0.4f;
                Fx.vaporSmall.at(b.x + Mathf.range(s), b.y + Mathf.range(s));
            }
        }else{
            d = Math.max(d - step * rain / (soak * 60f), 0f);
        }
        if(d > 0.001f) dried.put(key, d);
        else dried.remove(key, 0f);
        // The puddles on it go with the heat as fast as the film of water, but come back only at the pace puddles
        // fill (else a building that cooled had its puddles back, full, within seconds).
        float p = drained.get(key, 0f);
        if(heat > 0.05f && !seen.contains(key)) p = 1f;
        if(heat > 0.05f) p = Math.max(p, d);
        else p = Math.max(p - step * rain / (fill * 60f), 0f);
        if(p > 0.001f) drained.put(key, p);
        else drained.remove(key, 0f);
        // (no puddles on a conveyor: a belt with items on it is no place for water to stand)
        lastPoolKeep = b instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild ? 0f : 1f - p;
        return 1f - d;
    }

    /**
     * How still the turning head of that building is: 0 while it turns, up to 1 once it has kept still a second
     * (water only runs and stands on it then: laid out on the ground under a turning head, it flickered over it -
     * "the drops sparkle when the turret turns"). -1 for a building with no head that turns.
     */
    private static float steadiness(Building b, int key, float step){
        float angle;
        boolean moving;
        if(b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild t) angle = t.rotation;
        else if(b instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild m) angle = m.rotation;
        // A conveyor whose belt runs (as SnowCover tells it: on, not clogged, powered): the drops and puddles, laid
        // out on the ground, stood still on the moving belt ("the drops stay while the conveyor moves"). A stopped
        // or clogged belt counts as still, as a turret that has stopped turning.
        else if(b instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c){
            // A full belt stops and starts again many times a second while its items jam and clear: it counts as
            // running until it has stayed clear of a jam for a while, and as stopped only once it has kept still a
            // second (with "running" read as each moment, the drops on a jammed belt came and went - a flicker)
            float[] s = heads.get(key);
            if(s == null || s.length < 4) heads.put(key, s = new float[]{0f, 0f, 0f, 0f});
            boolean runs = c.enabled && c.efficiency > 0f && c.clogHeat <= 0.5f;
            // s[3]: how long it has run without a stop (ticks); s[1]: how long it has kept still
            if(runs){
                s[3] += step;
                if(s[3] > 20f) s[1] = 0f;
            }else{
                s[3] = 0f;
                s[1] += step;
            }
            s[2] = s[1] >= stillAfter ? Math.min(s[2] + step / 30f, 1f) : Math.max(s[2] - step / 20f, 0f);
            return s[2];
        }
        else return -1f;
        float[] s = heads.get(key);
        if(s == null) heads.put(key, s = new float[]{angle, 0f, 0f});
        moving = Math.abs(Angles.angleDist(angle, s[0])) > 0.3f;
        if(moving) s[1] = 0f;
        else s[1] += step;
        s[0] = angle;
        // in over half a second once still for one; out at once when it turns
        s[2] = s[1] >= stillAfter ? Math.min(s[2] + step / 30f, 1f) : Math.max(s[2] - step / 4f, 0f);
        if(heads.size > 2000) heads.clear();
        return s[2];
    }

    /** How still that unit is, 0 .. 1 (as steadiness: still a second without moving or turning). Asked once a frame while it is drawn. */
    static float unitStill(Unit unit){
        // A unit in the air never keeps quite still (it hovers and sways, unless the player flies it and lets go), and
        // drops laid out on the ground slid over it: no running water on it, only the film and the splashes
        if(!any() || unit.isFlying()) return 0f;
        long frame = Core.graphics.getFrameId();
        float[] s = units.get(unit.id);
        if(s == null) units.put(unit.id, s = new float[]{unit.x, unit.y, unit.rotation, 0f, 0f, frame});
        float dt = Vars.state.isPaused() ? 0f : Time.delta;
        if(s[5] == frame) return s[4];
        if(Mathf.dst(unit.x, unit.y, s[0], s[1]) > 0.05f || Math.abs(Angles.angleDist(unit.rotation, s[2])) > 0.3f) s[3] = 0f;
        else s[3] += dt;
        s[0] = unit.x;
        s[1] = unit.y;
        s[2] = unit.rotation;
        s[4] = s[3] >= stillAfter ? Math.min(s[4] + dt / 30f, 1f) : Math.max(s[4] - dt / 4f, 0f);
        s[5] = frame;
        // units gone for a while are let go of
        if(units.size > 300){
            for(var it = units.iterator(); it.hasNext();) if(frame - it.next().value[5] > 600) it.remove();
        }
        return s[4];
    }
}
