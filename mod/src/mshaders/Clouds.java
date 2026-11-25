package mshaders;

import arc.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;

/**
 * Clouds seen from high up: zoomed all the way out (as far as the game lets one go), thin clouds drift over the map,
 * their shadows sliding over the ground beside them, offset away from the sun. They come in as the view goes out
 * (none at a working zoom) - the maker: "when I zoom out all the way, clouds faintly?". More and heavier while it
 * rains or snows; at night dim and bluish. Drawn in the last step of the frame (mshaders-post.frag), above the world.
 */
public class Clouds{
    /** How far the clouds have drifted, world units (game time: they stand still while the game is paused). */
    private static float shiftX, shiftY, lastTime;

    /** How much the clouds show at this zoom, 0 .. 1: from about twice the furthest zoom out down to it. */
    static float fade(){
        // (off unless switched on: to someone who has just put the mod in, clouds over the map could be in the way)
        if(!Core.settings.getBool(ShaderMod.cloudsOn, false) || !Vars.state.isGame()) return 0f;
        float min = Vars.renderer.minScale(), now = Vars.renderer.getDisplayScale();
        if(min <= 0f) return 0f;
        float far = now / min;   // 1 = as far out as it goes
        return 1f - Interp.smooth.apply(Mathf.clamp((far - 1.05f) / 0.85f));
    }

    static void apply(Shader s){
        float f = fade() * ShaderMod.percent(ShaderMod.cloudsStrength, 100);
        float time = Time.time, dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(time - lastTime, 0f), 30f);
        lastTime = time;
        // drifting slowly, some 6 world units a second, from the upper right
        shiftX += dt / 60f * 6f;
        shiftY += dt / 60f * 2.6f;
        // (after an hour some 22000 units: with the clouds 520 units across, and the sine-free hash, still precise)
        // how much of the sky they cover: more while it rains or snows, more again in a sandstorm's dust
        float cover = Mathf.clamp(0.35f + Skies.rain * 0.5f + Skies.snowing * 0.4f + Skies.blowing * 0.15f);
        if(s.hasUniform("u_cloud")) s.setUniformf("u_cloud", f);
        if(s.hasUniform("u_cloudCover")) s.setUniformf("u_cloudCover", cover);
        if(s.hasUniform("u_cloudShift")) s.setUniformf("u_cloudShift", shiftX, shiftY);
    }
}
