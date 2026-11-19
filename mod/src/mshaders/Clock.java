package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;

/**
 * Where a frame's time goes, step by step - for the developer option "time of each step".
 *
 * The frame rate cannot tell: on a desktop card a whole frame of this mod is under a millisecond, and switching one
 * effect off changes less than the frame rate wobbles by itself; on a phone the same frame is a hundred times that,
 * and nobody can attach a profiler to the phone of a player. So the steps time themselves: before a step and after it
 * the graphics card is told to finish everything it has been handed (glFinish), and the time between the two is that
 * step's - what the card really needed, not just how long it took to hand the work over.
 *
 * That waiting makes the frame itself slower than it is otherwise (a phone's chip likes to work on a whole frame at
 * once). The numbers say which step is the heavy one, not how fast the game runs without the clock.
 *
 * A step inside another (the light pass inside the shadows that ask for it) is counted for itself and taken off the
 * one around it, so the lines add up. What nobody timed - the game's own drawing, and the water and ice, which the
 * game draws with this mod's shaders at a moment the mod has no hand in - is the last line.
 */
final class Clock{
    /** True while the option is on and a game is being played. Looked at once a frame. */
    static boolean on;
    /** A line of facts about the frame, shown under the table (Scene.end sets it). */
    static String note = "";
    /** The table, made anew once a second; empty while the clock is off. */
    static String text = "";

    private static final int most = 32, deep = 16;
    private static final Seq<String> names = new Seq<>();
    private static final ObjectIntMap<String> places = new ObjectIntMap<>();
    private static final long[] sums = new long[most];
    private static final int[] counts = new int[most];
    private static final int[] stack = new int[deep];
    private static final long[] started = new long[deep], inside = new long[deep];
    private static int depth, frames, windows;
    private static long window;
    private static final boolean testing = System.getProperty("mstest.out") != null;

    private Clock(){}

    /** Once a frame, before anything of it is drawn. */
    static void frame(){
        boolean want = Core.settings.getBool(ShaderMod.devClock, false) && Vars.state.isGame();
        long now = System.nanoTime();
        if(want && !on) reset(now);
        if(!want && on) text = "";
        on = want;
        depth = 0;
        if(!on) return;
        frames++;
        if(now - window >= 1_000_000_000L && frames > 1) tell(now);
    }

    private static void reset(long now){
        window = now;
        frames = 0;
        java.util.Arrays.fill(sums, 0L);
        java.util.Arrays.fill(counts, 0);
    }

    /**
     * A step begins. Not from the middle of a frame's sorted drawing unless what the sprite batch holds has been sent
     * off (timed() sees to that); safe from inside a shader's apply().
     */
    static void begin(String name){
        if(!on) return;
        if(depth >= deep){
            depth++;
            return;
        }
        int place = places.get(name, -1);
        if(place < 0){
            if(names.size >= most){
                // no line left for it: gone through without being timed
                stack[depth] = -1;
                depth++;
                return;
            }
            place = names.size;
            names.add(name);
            places.put(name, place);
        }
        Gl.finish();
        stack[depth] = place;
        inside[depth] = 0L;
        started[depth] = System.nanoTime();
        depth++;
    }

    /** The step begun last is over. */
    static void end(){
        if(!on || depth <= 0) return;
        depth--;
        if(depth >= deep || stack[depth] < 0) return;
        Gl.finish();
        long took = System.nanoTime() - started[depth];
        sums[stack[depth]] += took - inside[depth];
        counts[stack[depth]]++;
        if(depth > 0) inside[depth - 1] += took;
    }

    /** That step, timed: for the steps that run while the frame's sorted drawing is played back (Draw.draw). */
    static Runnable timed(String name, Runnable step){
        return () -> {
            if(!on){
                step.run();
                return;
            }
            // what the sprite batch still holds belongs to whatever came before
            Draw.flush();
            begin(name);
            try{
                step.run();
                Draw.flush();
            }finally{
                end();
            }
        };
    }

    private static String named(String id){
        return Core.bundle.get("mshaders.clock." + id, id);
    }

    private static void tell(long now){
        float per = 1f / frames / 1e6f, whole = (now - window) * per, known = 0f;
        int n = names.size;
        Integer[] order = new Integer[n];
        for(int i = 0; i < n; i++){
            order[i] = i;
            known += sums[i] * per;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(sums[b], sums[a]));
        StringBuilder out = new StringBuilder();
        out.append(named("title")).append('\n');
        out.append(Strings.fixed(whole, 1)).append(" ms  ").append(named("whole")).append("  (").append(Strings.fixed(1000f / Math.max(whole, 0.001f), 0)).append(" fps)\n");
        for(int k = 0; k < n; k++){
            int i = order[k];
            if(counts[i] == 0) continue;
            float times = counts[i] / (float)frames;
            out.append(Strings.fixed(sums[i] * per, 2)).append(" ms  ").append(named(names.get(i)));
            if(Math.abs(times - 1f) > 0.05f) out.append("  x").append(Strings.fixed(times, 1));
            out.append('\n');
        }
        out.append(Strings.fixed(Math.max(whole - known, 0f), 2)).append(" ms  ").append(named("rest")).append('\n');
        out.append(Core.graphics.getWidth()).append('x').append(Core.graphics.getHeight());
        if(!note.isEmpty()) out.append(", ").append(note);
        text = out.toString();
        // into the log as well: every second while the test kit runs, else every fifth
        if(testing || windows % 5 == 0) Log.info("[mshaders] time of each step\n@", text);
        windows++;
        reset(now);
    }
}
