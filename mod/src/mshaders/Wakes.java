package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;

/**
 * Wakes of units moving through water: a trail of foam behind them, with two lines of broken water spreading off its
 * sides, that fades in a few seconds. Kept as Tracks keeps its tracks (a picture of the map, stamped and fading); the
 * water's shader reads it (mshaders-water.frag).
 */
public class Wakes{
    private static FrameBuffer a, b;
    private static Shader fade;
    private static final FloatSeq stamps = new FloatSeq();
    private static final IntMap<float[]> last = new IntMap<>();
    private static float lastFade, fadeTime;
    private static Texture map;
    private static final Mat proj = new Mat(), before = new Mat();
    private static final int perTile = 4;

    public static void init(){
        try{
            fade = new Shader(Vars.tree.get("shaders/screenspace.vert").readString(),
                "varying vec2 v_texCoords;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform float u_step;\n" +
                "void main(){\n" +
                "    gl_FragColor = vec4(max(texture2D(u_texture, v_texCoords).rgb - u_step, 0.0), 1.0);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] wakes of units unavailable", t);
            return;
        }
        Events.run(Trigger.update, Wakes::walk);
        Events.run(Trigger.preDraw, Wakes::update);
        Events.on(WorldLoadEvent.class, e -> {
            last.clear();
            stamps.clear();
            fadeTime = 0f;
            if(a != null){
                a.dispose();
                b.dispose();
                a = b = null;
            }
        });
    }

    static boolean on(){
        return Core.settings.getBool(ShaderMod.wakesOn, true);
    }

    /** Where units in water have moved since the last look: foam behind them, every so far. */
    private static void walk(){
        if(!on() || !Vars.state.isGame() || Vars.state.isPaused()) return;
        for(Unit u : Groups.unit){
            if(u.dead() || u.isFlying()) continue;
            var floor = u.floorOn();
            if(floor == null || !floor.isLiquid) continue;
            float size = u.hitSize;
            float[] l = last.get(u.id);
            if(l == null){
                last.put(u.id, new float[]{u.x, u.y, 0f});
                continue;
            }
            float step = Math.max(size * 0.3f, 2.5f);
            float d = Mathf.dst(l[0], l[1], u.x, u.y);
            if(d < step) continue;
            float ang = Angles.angle(l[0], l[1], u.x, u.y), side = ang + 90f;
            float mx = (l[0] + u.x) / 2f, my = (l[1] + u.y) / 2f;
            // the churned water right behind it
            stamp(mx - Angles.trnsx(ang, size * 0.4f), my - Angles.trnsy(ang, size * 0.4f), size * 0.55f, Math.max(d * 1.5f, size * 0.5f), ang, 0.75f);
            // and the two lines off its sides, a little wider than it
            for(int t = -1; t <= 1; t += 2){
                stamp(mx + Angles.trnsx(side, size * 0.6f * t), my + Angles.trnsy(side, size * 0.6f * t), size * 0.16f, Math.max(d * 1.4f, size * 0.4f), ang + t * 14f, 0.75f);
            }
            l[0] = u.x;
            l[1] = u.y;
        }
        if(last.size > 2000) last.clear();
    }

    /** A print: middle, width across, length along, its direction (degrees), how deep (0 .. 1). */
    private static void stamp(float x, float y, float width, float length, float angle, float depth){
        if(stamps.size >= 6 * 2000) return;
        stamps.add(x, y, width, length);
        stamps.add(angle, depth);
    }

    private static void update(){
        map = null;
        if(!on() || fade == null || !Vars.state.isGame()){
            stamps.clear();
            return;
        }
        int w = Math.min(Vars.world.width() * perTile, 4096), h = Math.min(Vars.world.height() * perTile, 4096);
        boolean fresh = false;
        if(a == null){
            a = new FrameBuffer(w, h);
            b = new FrameBuffer(w, h);
            fresh = true;
        }
        a.getTexture().setFilter(Texture.TextureFilter.linear);
        b.getTexture().setFilter(Texture.TextureFilter.linear);
        float dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(Time.time - lastFade, 0f), 30f);
        lastFade = Time.time;
        // down one level of the picture's 256 at a time (a share each frame is lost to rounding): gone in some 4 s
        fadeTime += dt;
        int levels = (int)(fadeTime * 255f / 240f);
        fadeTime -= levels * 240f / 255f;
        if(!fresh && levels == 0 && stamps.isEmpty()){
            map = a.getTexture();
            return;
        }
        Draw.flush();
        fade.bind();
        fade.setUniformf("u_step", fresh ? 1f : levels / 255f);
        Blending.disabled.apply();
        b.begin();
        Draw.blit(a.getTexture(), fade);
        if(stamps.size > 0){
            before.set(Draw.proj());
            Draw.proj(proj.setOrtho(-4f, -4f, Vars.world.width() * Vars.tilesize, Vars.world.height() * Vars.tilesize));
            Draw.sort(false);
            Draw.blend(Blending.additive);
            TextureRegion soft = Core.atlas.find("circle-shadow");
            for(int i = 0; i < stamps.size; i += 6){
                float[] s = stamps.items;
                Draw.color(1f, 1f, 1f, s[i + 5]);
                if(soft.found()) Draw.rect(soft, s[i], s[i + 1], s[i + 3] * 1.3f, s[i + 2] * 1.3f, s[i + 4]);
            }
            Draw.flush();
            Draw.blend();
            Draw.color();
            Draw.sort(true);
            Draw.proj(before);
        }
        b.end();
        Blending.normal.apply();
        stamps.clear();
        FrameBuffer t = a;
        a = b;
        b = t;
        map = a.getTexture();
    }

    /** Binds the picture of the wakes for the water's shader (unit 8): u_wake, u_hasWake, u_tiles. */
    static void apply(Shader s){
        if(map != null){
            Gl.activeTexture(Gl.texture8);
            map.bind(8);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_wake")) s.setUniformi("u_wake", 8);
        if(s.hasUniform("u_hasWake")) s.setUniformf("u_hasWake", map != null ? ShaderMod.percent(ShaderMod.wakesStrength, 100) : 0f);
        if(s.hasUniform("u_wakeSize")) s.setUniformf("u_wakeSize", Vars.world.width() * Vars.tilesize, Vars.world.height() * Vars.tilesize);
    }
}
