package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.MendProjector.*;
import mindustry.world.blocks.defense.OverdriveProjector.*;

/**
 * Blocks that work in pulses or over an area (Mindustry draws them as thin lines):
 *   menders and mend projectors: every time one heals (its charge runs full and starts again), a wave of its light runs
 *     out over the ground and the blocks around it to the edge of its range (mshaders-post.frag, pulseLight)
 *   overdrive projectors and domes: while one is working, every two seconds a very faint wave of its colour runs out to
 *     the edge of its range, bending the picture a little as it passes, like a soft shock wave (the maker, after a
 *     shimmer over the whole range: "not so good - how about a faint orange shock wave now and then?")
 * The blocks in view are looked up a few times a second (BlockIndexer), not every frame.
 */
public class Pulses{
    static final int wavesKept = 16, hazesKept = 8;
    private static final float[] waves = new float[wavesKept * 4], waveColors = new float[wavesKept * 4];
    /** The other end of each wave's line (the same as x, y for a single block: a round wave). */
    private static final float[] waveX2 = new float[wavesKept], waveY2 = new float[wavesKept], waveEnds = new float[wavesKept * 4];
    private static final float[] waveX = new float[wavesKept], waveY = new float[wavesKept], waveAt = new float[wavesKept], waveR = new float[wavesKept];
    private static final Color[] waveColor = new Color[wavesKept];
    /** How strong each wave is: 1 for a mender's, less for an overdrive's. */
    private static final float[] waveStrength = new float[wavesKept];
    /** How often an overdrive sends out a wave, ticks; and how strong it is next to a mender's. */
    static final float overdriveEvery = 120f, overdriveStrength = 0.6f;
    private static final IntFloatMap lastBeat = new IntFloatMap();
    private static int nextWave;
    private static final float[] hazes = new float[hazesKept * 4], hazeColors = new float[hazesKept * 4];
    private static int hazeCount;

    /** The menders and overdrives in and around the view, found every few frames. */
    private static final Seq<MendBuild> menders = new Seq<>();
    private static final Seq<OverdriveBuild> overdrives = new Seq<>();
    private static final IntFloatMap lastCharge = new IntFloatMap();
    /**
     * Menders that touch (or nearly) make one group, and a group sends out one wave: from the line through it (its two
     * members farthest apart), so a long row sends a long wave with rounded ends and a block of them a wide one. By the
     * mender's id: its group (an index into groups: the ends of its line and its range); a group is known by the
     * lowest id among its members (groupKey), and when it last sent a wave is kept under that key.
     */
    private static final IntIntMap groupOf = new IntIntMap();
    private static final FloatSeq groups = new FloatSeq();
    private static final IntSeq groupKey = new IntSeq();
    private static final IntFloatMap lastGroupWave = new IntFloatMap();
    /**
     * A group's wave runs out from where its menders stand (from the nearest of them), so it takes the group's own shape
     * - a row, a block, an L. (From the line through the two ends, an L made a triangle: "it really just makes a
     * triangle".) Up to 12 of the menders are used: all of a small group, of a large one those spread farthest apart.
     * Per group: its points (x, y, ...). Four slots for the shapes of waves running at once: their points, the count.
     */
    static final int shapeSlots = 4, shapePoints = 12;
    private static final Seq<float[]> groupPoints = new Seq<>();
    private static final float[] slotPoints = new float[shapeSlots * shapePoints * 2];
    private static final int[] slotCount = new int[shapeSlots];
    private static int nextSlot;
    /** Which slot each wave's shape is in, 1 .. shapeSlots; 0 = a round wave from its point. */
    private static final int[] waveSlot = new int[wavesKept];
    private static final float[] shapeUniform = new float[shapeSlots * shapePoints * 2];
    private static long lastLook = -100;

    /** How long a wave takes to reach the edge of the range, ticks. */
    static final float waveLife = 45f;

    static{
        for(int i = 0; i < wavesKept; i++) waveColor[i] = new Color();
    }

    public static void init(){
        Events.run(Trigger.draw, Pulses::look);
        Events.on(WorldLoadEvent.class, e -> {
            menders.clear();
            overdrives.clear();
            lastCharge.clear();
            for(int i = 0; i < wavesKept; i++) waveR[i] = 0f;
        });
    }

    private static boolean on(){
        return Core.settings.getBool(ShaderMod.pulsesOn, true);
    }

    private static void look(){
        if(!on() || !ShaderMod.inWorld()) return;
        long frame = Core.graphics.getFrameId();
        Rect view = Core.camera.bounds(Tmp.r1);
        if(frame - lastLook >= 10){
            lastLook = frame;
            menders.clear();
            overdrives.clear();
            float cx = view.x + view.width / 2f, cy = view.y + view.height / 2f;
            // the largest range of these blocks (an overdrive dome reaches 200) past the edge of the view
            float reach = Mathf.dst(view.width, view.height) / 2f + 210f;
            for(TeamData data : Vars.state.teams.present){
                Vars.indexer.eachBlock(data.team, cx, cy, reach, b -> b instanceof MendBuild || b instanceof OverdriveBuild, b -> {
                    if(b instanceof MendBuild m) menders.add(m);
                    else overdrives.add((OverdriveBuild)b);
                });
            }
            if(lastCharge.size > 4000) lastCharge.clear();
            if(lastGroupWave.size > 4000) lastGroupWave.clear();
            group();
        }
        // a mender has healed when its charge starts again from nothing
        for(MendBuild m : menders){
            if(!m.isValid()) continue;
            float was = lastCharge.get(m.id, -1f);
            lastCharge.put(m.id, m.charge);
            if(was >= 0f && m.charge < was - 1f && m.efficiency > 0f){
                MendProjector type = (MendProjector)m.block;
                float range = type.range + m.phaseHeat * type.phaseRangeBoost;
                Color c = Tmp.c2.set(type.baseColor).lerp(type.phaseColor, m.phaseHeat);
                int g = groupOf.get(m.id, -1);
                if(g < 0){
                    if(view.overlaps(m.x - range, m.y - range, range * 2f, range * 2f)) wave(m.x, m.y, m.x, m.y, range, c, 1f);
                }else{
                    // one wave for the whole group, and not again until it has passed
                    int key = groupKey.get(g);
                    if(Time.time - lastGroupWave.get(key, -1e9f) < waveLife * 1.6f) continue;
                    lastGroupWave.put(key, Time.time);
                    float ax = groups.get(g * 5), ay = groups.get(g * 5 + 1), bx = groups.get(g * 5 + 2), by = groups.get(g * 5 + 3), gr = groups.get(g * 5 + 4);
                    if(view.overlaps(Math.min(ax, bx) - gr, Math.min(ay, by) - gr, Math.abs(ax - bx) + gr * 2f, Math.abs(ay - by) + gr * 2f)){
                        place(ax, ay, ax, ay, gr, c, 1f);
                        // its shape: the menders' places, in a slot of their own
                        float[] pts = groupPoints.get(g);
                        int slot = nextSlot;
                        nextSlot = (nextSlot + 1) % shapeSlots;
                        // a wave still running in that slot goes back to being round (the oldest; rarely)
                        for(int w = 0; w < wavesKept; w++) if(waveSlot[w] == slot + 1) waveSlot[w] = 0;
                        System.arraycopy(pts, 0, slotPoints, slot * shapePoints * 2, pts.length);
                        slotCount[slot] = pts.length / 2;
                        waveSlot[(nextWave + wavesKept - 1) % wavesKept] = slot + 1;
                    }
                }
            }
        }
        // an overdrive at work sends out a faint wave every two seconds, each at its own moment
        if(ShaderMod.percent(ShaderMod.pulsesHaze, 100) > 0f){
            for(OverdriveBuild o : overdrives){
                if(!o.isValid() || o.heat < 0.5f) continue;
                float beat = (float)Math.floor((Time.time + o.id * 37f) / overdriveEvery);
                float was = lastBeat.get(o.id, beat);
                lastBeat.put(o.id, beat);
                if(beat == was) continue;
                OverdriveProjector type = (OverdriveProjector)o.block;
                float range = type.range + o.phaseHeat * type.phaseRangeBoost;
                if(view.overlaps(o.x - range, o.y - range, range * 2f, range * 2f)){
                    wave(o.x, o.y, o.x, o.y, range, Tmp.c2.set(type.baseColor).lerp(type.phaseColor, o.phaseHeat), overdriveStrength * o.heat);
                }
            }
        }
    }

    /**
     * Menders stand packed side by side, each healing at its own moment: one wave each made a field of them flicker
     * all the time ("put several together and they all flash"). So a new wave is not started where a wave of the same
     * kind is still running out from close by (nearer than half its range) - the area is in a wave already; the next
     * one comes when that has passed.
     */
    private static boolean covered(float x, float y, float range, float strength){
        for(int i = 0; i < wavesKept; i++){
            if(waveR[i] <= 0f || Time.time - waveAt[i] >= waveLife * 1.6f) continue;
            if((waveStrength[i] >= 0.99f) != (strength >= 0.99f)) continue;
            if(Mathf.within(waveX[i], waveY[i], x, y, Math.max(waveR[i], range) * 0.5f)) return true;
        }
        return false;
    }

    /** The menders in view grouped: those within a tile of each other (edge to edge) are one group. */
    private static void group(){
        groupOf.clear();
        groups.clear();
        groupKey.clear();
        groupPoints.clear();
        int n = menders.size;
        boolean[] seen = new boolean[n];
        IntSeq stack = new IntSeq(), members = new IntSeq();
        for(int s = 0; s < n; s++){
            if(seen[s]) continue;
            members.clear();
            stack.add(s);
            seen[s] = true;
            while(!stack.isEmpty()){
                int i = stack.pop();
                members.add(i);
                MendBuild a = menders.get(i);
                for(int j = 0; j < n; j++){
                    if(seen[j]) continue;
                    MendBuild b = menders.get(j);
                    float gap = (a.block.size + b.block.size) * 4f + 9f;
                    if(Math.abs(a.x - b.x) <= gap && Math.abs(a.y - b.y) <= gap){
                        seen[j] = true;
                        stack.add(j);
                    }
                }
            }
            if(members.size < 2) continue;
            // the line through the group: from the member farthest from the first, to the member farthest from that
            MendBuild first = menders.get(members.first()), end1 = first;
            for(int k = 0; k < members.size; k++){
                MendBuild m = menders.get(members.get(k));
                if(m.dst2(first) > end1.dst2(first)) end1 = m;
            }
            MendBuild end2 = end1;
            float range = 0f;
            int key = Integer.MAX_VALUE;
            for(int k = 0; k < members.size; k++){
                MendBuild m = menders.get(members.get(k));
                if(m.dst2(end1) > end2.dst2(end1)) end2 = m;
                MendProjector type = (MendProjector)m.block;
                range = Math.max(range, type.range + m.phaseHeat * type.phaseRangeBoost);
                key = Math.min(key, m.id);
            }
            int g = groupKey.size;
            groups.add(end1.x, end1.y, end2.x, end2.y);
            groups.add(range);
            groupKey.add(key);
            // its points: all its menders if few, else those spread farthest apart (each next one the farthest from
            // those already taken)
            int take = Math.min(members.size, shapePoints);
            float[] pts = new float[take * 2];
            boolean[] used = new boolean[members.size];
            int at = 0;
            used[0] = true;
            pts[0] = menders.get(members.get(0)).x;
            pts[1] = menders.get(members.get(0)).y;
            for(int t = 1; t < take; t++){
                int best = -1;
                float bestD = -1f;
                for(int k = 0; k < members.size; k++){
                    if(used[k]) continue;
                    MendBuild m = menders.get(members.get(k));
                    float near = Float.MAX_VALUE;
                    for(int q = 0; q < t; q++) near = Math.min(near, Mathf.dst2(m.x, m.y, pts[q * 2], pts[q * 2 + 1]));
                    if(near > bestD){
                        bestD = near;
                        best = k;
                    }
                }
                used[best] = true;
                pts[t * 2] = menders.get(members.get(best)).x;
                pts[t * 2 + 1] = menders.get(members.get(best)).y;
            }
            groupPoints.add(pts);
            for(int k = 0; k < members.size; k++) groupOf.put(menders.get(members.get(k)).id, g);
        }
    }

    private static void wave(float x, float y, float x2, float y2, float range, Color color, float strength){
        if(covered(x, y, range, strength)) return;
        place(x, y, x2, y2, range, color, strength);
    }

    private static void place(float x, float y, float x2, float y2, float range, Color color, float strength){
        waveSlot[nextWave] = 0;
        waveX2[nextWave] = x2;
        waveY2[nextWave] = y2;
        waveStrength[nextWave] = strength;
        waveX[nextWave] = x;
        waveY[nextWave] = y;
        waveAt[nextWave] = Time.time;
        waveR[nextWave] = range;
        waveColor[nextWave].set(color);
        nextWave = (nextWave + 1) % wavesKept;
    }

    /** For the test kit: an overdrive projector's wave at that place, AGE ticks ago. */
    public static void testOverdrive(float x, float y, float range, float at){
        place(x, y, x, y, range, Color.valueOf("feb380"), overdriveStrength);
        waveAt[(nextWave + wavesKept - 1) % wavesKept] = at;
    }

    /** For the test kit: the wave of the group of menders nearest that place, AGE ticks ago (in its shape). */
    public static String testGroup(float x, float y, float at){
        MendBuild near = null;
        for(MendBuild m : menders) if(near == null || m.dst2(x, y) < near.dst2(x, y)) near = m;
        if(near == null) return "no menders seen";
        int g = groupOf.get(near.id, -1);
        if(g < 0) return "that mender is in no group";
        float ax = groups.get(g * 5), ay = groups.get(g * 5 + 1), gr = groups.get(g * 5 + 4);
        place(ax, ay, ax, ay, gr, Color.valueOf("84f491"), 1f);
        int w = (nextWave + wavesKept - 1) % wavesKept;
        waveAt[w] = at;
        float[] pts = groupPoints.get(g);
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % shapeSlots;
        System.arraycopy(pts, 0, slotPoints, slot * shapePoints * 2, pts.length);
        slotCount[slot] = pts.length / 2;
        waveSlot[w] = slot + 1;
        return "group of " + pts.length / 2 + " points";
    }

    /** For the test kit: a group's wave from the line x, y - x2, y2, AGE ticks ago. */
    public static void testLine(float x, float y, float x2, float y2, float range, float at){
        place(x, y, x2, y2, range, Color.valueOf("84f491"), 1f);
        waveAt[(nextWave + wavesKept - 1) % wavesKept] = at;
    }

    /** For the test kit: a mender's wave at that place now. */
    public static void testWave(float x, float y, float range, float at){
        place(x, y, x, y, range, Color.valueOf("84f491"), 1f);
        waveAt[(nextWave + wavesKept - 1) % wavesKept] = at;
    }

    /** For the last step of the frame: u_waves, u_waveColors, u_hazes, u_hazeColors, u_pulse, u_haze. */
    static void apply(Shader s){
        boolean on = on();
        float pulse = on ? ShaderMod.percent(ShaderMod.pulsesWave, 100) : 0f, haze = on ? ShaderMod.percent(ShaderMod.pulsesHaze, 100) : 0f;
        for(int i = 0; i < wavesKept; i++){
            float age = Time.time - waveAt[i];
            boolean live = pulse > 0f && waveR[i] > 0f && age >= 0f && age < waveLife;
            waves[i * 4] = waveX[i];
            waves[i * 4 + 1] = waveY[i];
            waves[i * 4 + 2] = age / waveLife;
            waves[i * 4 + 3] = live ? waveR[i] : 0f;
            waveColors[i * 4] = waveColor[i].r;
            waveColors[i * 4 + 1] = waveColor[i].g;
            waveColors[i * 4 + 2] = waveColor[i].b;
            waveColors[i * 4 + 3] = waveStrength[i];
            waveEnds[i * 4] = waveX2[i];
            waveEnds[i * 4 + 1] = waveY2[i];
            int slot = waveSlot[i];
            waveEnds[i * 4 + 2] = slot;
            waveEnds[i * 4 + 3] = slot > 0 ? slotCount[slot - 1] : 0f;
        }
        hazeCount = 0;
        if(false){
            Rect view = Core.camera.bounds(Tmp.r1);
            for(OverdriveBuild o : overdrives){
                if(hazeCount >= hazesKept || !o.isValid() || o.heat < 0.01f) continue;
                OverdriveProjector type = (OverdriveProjector)o.block;
                float range = type.range + o.phaseHeat * type.phaseRangeBoost;
                if(!view.overlaps(o.x - range, o.y - range, range * 2f, range * 2f)) continue;
                int i = hazeCount++ * 4;
                hazes[i] = o.x;
                hazes[i + 1] = o.y;
                hazes[i + 2] = range;
                hazes[i + 3] = o.heat;
                Tmp.c3.set(type.baseColor).lerp(type.phaseColor, o.phaseHeat);
                hazeColors[i] = Tmp.c3.r;
                hazeColors[i + 1] = Tmp.c3.g;
                hazeColors[i + 2] = Tmp.c3.b;
                hazeColors[i + 3] = 1f;
            }
        }
        for(int i = hazeCount * 4; i < hazes.length; i++) hazes[i] = 0f;
        if(s.hasUniform("u_pulse")) s.setUniformf("u_pulse", pulse);
        if(s.hasUniform("u_haze")) s.setUniformf("u_haze", haze);
        if(s.hasUniform("u_waves[0]") || s.hasUniform("u_waves")) s.setUniform4fv("u_waves", waves, 0, wavesKept * 4);
        if(s.hasUniform("u_waveColors[0]") || s.hasUniform("u_waveColors")) s.setUniform4fv("u_waveColors", waveColors, 0, wavesKept * 4);
        if(s.hasUniform("u_waveEnds[0]") || s.hasUniform("u_waveEnds")) s.setUniform4fv("u_waveEnds", waveEnds, 0, wavesKept * 4);
        System.arraycopy(slotPoints, 0, shapeUniform, 0, shapeUniform.length);
        if(s.hasUniform("u_shapes4[0]") || s.hasUniform("u_shapes4")) s.setUniform4fv("u_shapes4", shapeUniform, 0, shapeUniform.length);
        if(s.hasUniform("u_hazes[0]") || s.hasUniform("u_hazes")) s.setUniform4fv("u_hazes", hazes, 0, hazesKept * 4);
        if(s.hasUniform("u_hazeColors[0]") || s.hasUniform("u_hazeColors")) s.setUniform4fv("u_hazeColors", hazeColors, 0, hazesKept * 4);
    }
}
