package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
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

    static void init(Scene from){
        scene = from;
        // what lay on the last map does not come along to the next one
        Events.on(WorldLoadEvent.class, e -> {
            snow = 0f;
            sand = 0f;
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

    /** Sets the weather uniforms of the ground's layer. */
    static void applyGround(Shader s){
        update();
        float k = strength();
        if(s.hasUniform("u_rain")) s.setUniformf("u_rain", rain * k);
        if(s.hasUniform("u_snow")) s.setUniformf("u_snow", Math.min(snow * k, 1f));
        if(s.hasUniform("u_sand")) s.setUniformf("u_sand", Math.min(sand * k, 1f), Math.min(blowing * k, 1f));
        if(s.hasUniform("u_wind")) s.setUniformf("u_wind", windX, windY);
        // where the blocks stand (Occluders: red = solid), for the sand that piles up against them
        // (heights() uploads the blocks placed or broken since the last frame, binding the picture to the active
        // texture unit to do it: with unit 0 active that put it in place of the ground layer's own picture for the
        // frame, and the whole screen flashed whenever a block was placed in a sandstorm. So unit 5 is made active
        // first, where it goes anyway.)
        Texture solid = null;
        if(sand > 0f && scene != null){
            Gl.activeTexture(Gl.texture5);
            solid = scene.heights();
            if(solid != null) solid.bind(5);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_solid")) s.setUniformi("u_solid", 5);
        if(s.hasUniform("u_hasSolid")) s.setUniformf("u_hasSolid", solid != null ? 1f : 0f);
    }
}
