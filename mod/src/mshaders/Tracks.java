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
 * Tracks of ground units in soft ground: footprints of mechs, the two treads of tanks, the feet of legged units, the
 * trail of other ground units - pressed into sand and snow (and into the snow and sand weather lays down), fading
 * over some 40 seconds. Kept in a picture of the map, four pixels to a tile, that the units of each frame are stamped
 * into and that fades on its own (two of them, swapped, as SnowCover's melt map); the ground's layer reads it.
 */
public class Tracks{
    private static FrameBuffer a, b;
    private static Shader fade;
    private static final FloatSeq stamps = new FloatSeq();
    private static final IntMap<float[]> last = new IntMap<>();
    private static float lastFade, fadeTime;
    private static Texture map;
    private static final Mat proj = new Mat(), before = new Mat();
    // (two pixels a tile: four made a picture of up to 4096 x 4096, 64 MB, twice over, on every map)
    private static final int perTile = 2;

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
            Log.err("[mshaders] tracks of units unavailable", t);
            return;
        }
        Events.run(Trigger.update, Tracks::walk);
        Events.run(Trigger.preDraw, Tracks::update);
        Events.on(WorldLoadEvent.class, e -> {
            last.clear();
            stamps.clear();
            fadeTime = 0f;
            free();
        });
    }

    private static void free(){
        if(a != null){
            a.dispose();
            b.dispose();
            a = b = null;
        }
    }

    static boolean on(){
        return Core.settings.getBool(ShaderMod.tracksOn, true);
    }

    /** Where the ground units have stepped since the last look: a print every so far they have moved. */
    private static void walk(){
        if(!on() || !Vars.state.isGame() || Vars.state.isPaused()) return;
        for(Unit u : Groups.unit){
            if(!u.isGrounded() || u.type.hovering || u.dead()) continue;
            float size = u.hitSize;
            float[] l = last.get(u.id);
            if(l == null){
                last.put(u.id, new float[]{u.x, u.y, 0f});
                continue;
            }
            float step = Math.max(size * 0.45f, 3f);
            float d = Mathf.dst(l[0], l[1], u.x, u.y);
            if(d < step) continue;
            float ang = Angles.angle(l[0], l[1], u.x, u.y), side = ang + 90f;
            if(u instanceof Mechc){
                // footprints, left and right in turn
                float s = l[2] > 0.5f ? 1f : -1f;
                l[2] = 1f - l[2];
                stamp(u.x + Angles.trnsx(side, size * 0.22f * s), u.y + Angles.trnsy(side, size * 0.22f * s), size * 0.16f, size * 0.24f, ang, 0.85f);
            }else if(u instanceof Tankc){
                // two treads, as long as the step
                for(int t = -1; t <= 1; t += 2){
                    float ox = Angles.trnsx(side, size * 0.36f * t), oy = Angles.trnsy(side, size * 0.36f * t);
                    stamp((l[0] + u.x) / 2f + ox, (l[1] + u.y) / 2f + oy, size * 0.2f, d * 1.15f, ang, 0.7f);
                }
            }else if(u instanceof Legsc legs){
                // where the feet are now
                for(var leg : legs.legs()){
                    if(leg.moving) continue;
                    stamp(leg.base.x, leg.base.y, size * 0.12f, size * 0.12f, ang, 0.8f);
                }
            }else{
                stamp((l[0] + u.x) / 2f, (l[1] + u.y) / 2f, size * 0.5f, d * 1.1f, ang, 0.5f);
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
            free();
            return;
        }
        // nothing made until there is something to keep (no units in water / on soft ground, no picture at all)
        if(a == null && stamps.isEmpty()) return;
        int w = Math.min(Vars.world.width() * perTile, 2048), h = Math.min(Vars.world.height() * perTile, 2048);
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
        // down one level of the picture's 256 at a time (a share each frame is lost to rounding): gone in some 40 s
        fadeTime += dt;
        int levels = (int)(fadeTime * 255f / 2400f);
        fadeTime -= levels * 2400f / 255f;
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

    /** Binds the picture of the tracks for the ground's layer (unit 7): u_tracks, u_hasTracks. */
    static void applyGround(Shader s){
        if(map != null){
            Gl.activeTexture(Gl.texture7);
            map.bind(7);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_tracks")) s.setUniformi("u_tracks", 7);
        if(s.hasUniform("u_hasTracks")) s.setUniformf("u_hasTracks", map != null ? ShaderMod.percent(ShaderMod.tracksStrength, 100) : 0f);
    }
}
