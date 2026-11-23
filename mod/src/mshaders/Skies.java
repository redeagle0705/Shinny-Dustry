package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import arc.graphics.g2d.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;

/**
 * Weather on the ground (the game shows weather only as falling particles), drawn in the ground's own layer (FloorFx):
 *   rain       the ground darkens, wet; puddles gather here and there, shining, with rings where the drops fall
 *   snow       snow settles while it snows, first on what stands higher, then in broad drifts; melts slowly after
 *   sandstorm  sand blows over the ground in fast streaks downwind, the light turns dusty; sand drifts pile up
 *              along the wind and are blown away slowly after
 * How much of each weather there is is read from the game's weather states (Groups.weather) every frame. Snow and
 * sand build up over time and go again over time; rain is there at once.
 */
public class Skies{
    /** How much rain there is now, 0 .. 1. */
    static float rain;
    /** How much it snows / how much sand blows now, 0 .. 1. */
    static float snowing, blowing;
    /** How much snow / sand lies on the ground, 0 .. 1 (builds up and goes away over time). */
    static float snow, sand;
    /** Where the sandstorm blows towards, length 1. */
    static float windX = 0.94f, windY = 0.34f;

    /** Seconds for snow / sand to cover the ground fully at full strength, and to go away again. */
    static final float settle = 40f, melt = 90f;

    private static long lastFrame = -1;
    private static Scene scene;

    /** The solid blocks as the sand piles see them: eased towards the real ones over a few seconds (two, swapped). */
    private static FrameBuffer pileA, pileB;
    private static Shader ease;
    private static boolean fresh = true;
    private static float lastEase;
    private static Texture piles;

    static void init(Scene from){
        scene = from;
        try{
            ease = new Shader(Vars.tree.get("shaders/screenspace.vert").readString(),
                "varying vec2 v_texCoords;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform sampler2D u_solid;\n" +
                "uniform vec2 u_rate;\n" +
                "void main(){\n" +
                "    vec4 was = texture2D(u_texture, v_texCoords), now = texture2D(u_solid, v_texCoords);\n" +
                // a pile grows slowly where a block now stands, and blows away slowly where one is gone
                "    gl_FragColor = mix(was, now, now.r > was.r ? u_rate.x : u_rate.y);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] easing of the sand piles unavailable", t);
        }
        // before the frame is drawn (the ground layer reads it while it is drawn)
        Events.run(Trigger.preDraw, Skies::easePiles);
        // what lay on the last map does not come along to the next one
        Events.on(WorldLoadEvent.class, e -> {
            snow = 0f;
            sand = 0f;
            fresh = true;
        });
    }

    static void update(){
        long frame = Core.graphics.getFrameId();
        if(frame == lastFrame) return;
        lastFrame = frame;

        float r = 0f, s = 0f, b = 0f;
        if(Vars.state.isGame()){
            for(WeatherState w : Groups.weather){
                float amount = w.opacity * Math.max(w.intensity, 0.5f);
                if(w.weather == Weathers.rain) r = Math.max(r, amount);
                else if(w.weather == Weathers.snow) s = Math.max(s, amount);
                else if(w.weather == Weathers.sandstorm && amount > b){
                    b = amount;
                    if(!w.windVector.isZero()){
                        float len = w.windVector.len();
                        windX = w.windVector.x / len;
                        windY = w.windVector.y / len;
                    }
                }
            }
        }
        rain = Mathf.clamp(r);
        snowing = Mathf.clamp(s);
        blowing = Mathf.clamp(b);

        // build up while it falls (up to how strongly it falls), go away slowly when it has stopped
        float dt = Vars.state.isGame() && !Vars.state.isPaused() ? Time.delta / 60f : 0f;
        snow = towards(snow, snowing, dt);
        sand = towards(sand, blowing, dt);
        if(!Vars.state.isGame()){
            snow = 0f;
            sand = 0f;
        }

        // forced values for the test kit: the weather and what lies on the ground both at once
        int forced = Core.settings.getInt("mshaders-test-rain", -1);
        if(forced >= 0) rain = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-snow", -1);
        if(forced >= 0) snow = snowing = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-sand", -1);
        if(forced >= 0) sand = blowing = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-wind", -1);
        if(forced >= 0){
            windX = Mathf.cosDeg(forced);
            windY = Mathf.sinDeg(forced);
        }
    }

    private static float towards(float lying, float falling, float dt){
        if(falling > lying) return Math.min(falling, lying + dt * falling / settle);
        return Math.max(falling, lying - dt / melt);
    }

    /** The weather settings: 0 = off. */
    static float strength(){
        return Core.settings.getBool(ShaderMod.weatherOn, true) ? ShaderMod.percent(ShaderMod.weatherStrength, 100) : 0f;
    }

    /**
     * Eases the picture the sand piles are read from towards where the blocks stand now: a pile builds up over a few
     * seconds when a block is put down, and blows away over a few seconds when it is gone (at first the piles were read
     * from the blocks themselves and popped in and out at once - "a little unnatural").
     */
    private static void easePiles(){
        piles = null;
        update();
        if(sand <= 0f || scene == null || ease == null || !Vars.state.isGame()) return;
        Gl.activeTexture(Gl.texture1);
        Texture solid = scene.heights();
        Gl.activeTexture(Gl.texture0);
        if(solid == null) return;
        int w = solid.width, h = solid.height;
        if(pileA == null){
            pileA = new FrameBuffer(w, h);
            pileB = new FrameBuffer(w, h);
            fresh = true;
        }
        if(pileA.getWidth() != w || pileA.getHeight() != h){
            pileA.resize(w, h);
            pileB.resize(w, h);
            fresh = true;
        }
        pileA.getTexture().setFilter(Texture.TextureFilter.linear);
        pileB.getTexture().setFilter(Texture.TextureFilter.linear);
        float dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(Time.time - lastEase, 0f), 30f);
        lastEase = Time.time;
        Draw.flush();
        solid.bind(1);
        Gl.activeTexture(Gl.texture0);
        ease.bind();
        ease.setUniformi("u_solid", 1);
        // grows in some 4 seconds, goes in some 5 (game time)
        if(fresh) ease.setUniformf("u_rate", 1f, 1f);
        else ease.setUniformf("u_rate", 1f - (float)Math.exp(-dt / 240f), 1f - (float)Math.exp(-dt / 300f));
        fresh = false;
        Blending.disabled.apply();
        pileB.begin();
        Draw.blit(pileA.getTexture(), ease);
        pileB.end();
        Blending.normal.apply();
        FrameBuffer t = pileA;
        pileA = pileB;
        pileB = t;
        piles = pileA.getTexture();
    }

    /** Sets the weather uniforms of the ground's layer. */
    static void applyGround(Shader s){
        update();
        float k = strength();
        if(s.hasUniform("u_rain")) s.setUniformf("u_rain", rain * k);
        if(s.hasUniform("u_snow")) s.setUniformf("u_snow", Math.min(snow * k, 1f));
        if(s.hasUniform("u_sand")) s.setUniformf("u_sand", Math.min(sand * k, 1f), Math.min(blowing * k, 1f));
        if(s.hasUniform("u_wind")) s.setUniformf("u_wind", windX, windY);
        SnowCover.applyGround(s);
        Tracks.applyGround(s);
        // where the blocks stand (Occluders: red = solid), for the sand that piles up against them
        // (heights() uploads the blocks placed or broken since the last frame, binding the picture to the active
        // texture unit to do it: with unit 0 active that put it in place of the ground layer's own picture for the
        // frame, and the whole screen flashed whenever a block was placed in a sandstorm. So unit 5 is made active
        // first, where it goes anyway.)
        Texture solid = sand > 0f ? piles : null;
        if(solid != null){
            Gl.activeTexture(Gl.texture5);
            solid.bind(5);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_solid")) s.setUniformi("u_solid", 5);
        if(s.hasUniform("u_hasSolid")) s.setUniformf("u_hasSolid", solid != null ? 1f : 0f);
    }
}
