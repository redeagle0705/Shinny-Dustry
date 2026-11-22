package mstest;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.profiling.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;

/**
 * layers=true: how long the game takes to draw each band of its draw layers, in ms a frame, and how many draw calls and
 * texture bindings each band makes. The game queues everything it draws (Draw.sort) and draws it in order of layer at
 * the end of Renderer.draw; a marker between the bands waits for the graphics card (Gl.finish) and reads the clock, so
 * each band's time is what it takes to draw it, CPU and GPU together. Also: the time from the start of the frame's
 * drawing to the first band (queueing every block, unit, shot and effect, and the shader mod's own work before it), and
 * the whole frame. Measuring this way makes the frame slower than it is; the shares are what count.
 */
class LayerTimer{
    /** The band starting at each of these layers, up to the next. */
    static final float[] starts = {-11f, 0f, 10f, 29.5f, 30f, 35f, 50f, 60f, 70f, 80f, 90f, 100f, 110f, 115f, 120f, 125f, 130f, 140f, 155f, 200f};
    static final String[] names = {"background", "floor", "scorch/debris", "block under", "blocks", "block over/building", "turrets", "ground units",
        "power lasers", "legs/darkness/plans", "low flying", "bullets", "effects", "flying units", "overlay UI/build beams", "shields", "weather", "lights",
        "fog/space", "end"};

    final double[] ms = new double[starts.length];
    final long[] draws = new long[starts.length], binds = new long[starts.length];
    double queue, wholeDraw, frameMs;
    int frames;
    boolean on;
    long frameStart, lastFrameAt, mark;
    int band = -1, lastDraws, lastBinds;
    GLProfiler profiler;

    void install(){
        profiler = new GLProfiler(Core.graphics);
        Events.run(Trigger.preDraw, () -> {
            if(!on) return;
            Gl.finish();
            long now = Time.nanos();
            if(lastFrameAt != 0){
                frameMs += (now - lastFrameAt) / 1e6;
                frames++;
            }
            lastFrameAt = now;
            frameStart = now;
            band = -1;
            profiler.reset();
        });
        Events.run(Trigger.draw, () -> {
            if(!on) return;
            for(int i = 0; i < starts.length; i++){
                int b = i;
                Draw.draw(starts[i] - 0.0001f, () -> mark(b));
            }
        });
        Events.run(Trigger.postDraw, () -> {
            if(!on || band < 0) return;
            mark(-2);
        });
    }

    void mark(int next){
        Draw.flush();
        Gl.finish();
        long now = Time.nanos();
        int d = profiler.getDrawCalls(), t = profiler.getTextureBindings();
        if(band == -1) queue += (now - frameStart) / 1e6;
        else if(band >= 0){
            ms[band] += (now - mark) / 1e6;
            draws[band] += d - lastDraws;
            binds[band] += t - lastBinds;
        }
        if(next == -2) wholeDraw += (now - frameStart) / 1e6;
        lastDraws = d;
        lastBinds = t;
        mark = now;
        band = next;
    }

    void start(){
        java.util.Arrays.fill(ms, 0);
        java.util.Arrays.fill(draws, 0);
        java.util.Arrays.fill(binds, 0);
        queue = wholeDraw = frameMs = 0;
        frames = 0;
        lastFrameAt = 0;
        on = true;
        profiler.enable();
    }

    String report(){
        on = false;
        profiler.disable();
        int f = Math.max(frames, 1);
        StringBuilder out = new StringBuilder();
        out.append(String.format("  layers, mean of %d frames: whole frame %.2f ms, world drawn in %.2f ms (queueing before the first band %.2f ms)%n", frames, frameMs / f, wholeDraw / f, queue / f));
        Seq<Integer> order = new Seq<>();
        for(int i = 0; i < starts.length; i++) order.add(i);
        order.sort(i -> (float)-ms[i]);
        for(int i : order){
            if(ms[i] / f < 0.02) continue;
            out.append(String.format("    %6.2f ms  %5d draws  %5d binds  %s%n", ms[i] / f, draws[i] / f, binds[i] / f, names[i]));
        }
        return out.toString();
    }
}
