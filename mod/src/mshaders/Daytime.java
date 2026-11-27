package mshaders;

import arc.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.type.*;

/**
 * Day and night. Where the game has them - the Serpulo campaign, whose planet turns, so that the darkness of a sector
 * comes and goes (Universe.update, Sector.getLight) - the time of day is read from there; on other maps a day of the
 * mod's own can be turned on in the settings (off by default: there the darkness is the map's rule). From the time of
 * day:
 *   the sun   moves over the sky: low in the east in the morning, high at noon, low in the west in the evening -
 *             shadows long and turned one way in the morning, short at noon, long the other way in the evening
 *             (ShaderMod.sunX/sunY, ShadowFx.dayFactor; it swings round the set sun angle, not all the way, since the
 *             sprites are drawn lit from the upper right)
 *   the light warm and reddish at sunrise and sunset; at night bluish (and, with the mod's own day, darker - the
 *             campaign's own darkness does that there)
 */
public class Daytime{
    /** The height of the sun: 1 = straight above, 0 = on the horizon, below 0 = night. NaN = no time of day here. */
    private static float elevation = Float.NaN;
    /** -1 = sunrise .. 0 = noon .. 1 = sunset (beyond: night). */
    private static float hour;
    private static boolean own;
    private static boolean rising = true;
    private static long frame = -1;
    /** Worked out once a frame (sunX/sunY are asked for by many things, per unit and per smoke puff). */
    private static float sunX = 0.7071f, sunY = 0.7071f, length = 1f;

    // The sun is moved in small steps, not a little every frame: a shadow cast along a direction that changes by a
    // hair each frame shimmers along its edges ("the shadows sparkle a lot"). Held until the true place is a step
    // away, then put there.
    private static float heldTurn = Float.NaN, heldLength = Float.NaN;

    /** The campaign's light at a sector for a turn of its planet (as Sector.getLight, at any rotation). */
    private static float lightAt(Sector sector, float rotation){
        Vec3 normal = Tmp.v31.set(sector.tile.v).rotate(Vec3.Y, -rotation).nor();
        Vec3 light = Tmp.v32.set(sector.planet.solarSystem.position).sub(sector.planet.position).nor();
        return (normal.dot(light) + 1f) / 2f;
    }

    private static void update(){
        long now = Core.graphics.getFrameId();
        if(now == frame) return;
        frame = now;
        elevation = Float.NaN;
        own = false;

        float e = Float.NaN;
        if(ShaderMod.inWorld()){
            int forced = Core.settings.getInt("mshaders-test-daytime", -1);
            // the scene behind the menu: the time of day it is set at, as the mod's own day (MenuWorld)
            boolean menuTime = forced < 0 && MenuWorld.showing() && MenuWorld.daytime() >= 0f;
            var sector = Vars.state.hasSector() ? Vars.state.getSector() : null;
            if(forced >= 0 || menuTime){
                // test kit: 0 .. 100 = one day from midnight to midnight
                float phase = (menuTime ? MenuWorld.daytime() : forced) / 100f;
                e = -Mathf.cos(phase * Mathf.PI2);
                rising = phase < 0.5f;
                own = menuTime || Core.settings.getBool(ShaderMod.dayOwn, false);
            }else if(sector != null && sector.planet.updateLighting && !(sector.preset != null && sector.preset.noLighting)){
                if(Core.settings.getBool(ShaderMod.dayCampaign, true)){
                    float rotation = sector.planet.getRotation();
                    float light = lightAt(sector, rotation);
                    // morning or evening: whether the light is about to grow or to fall, a little further on in the
                    // planet's turn (at first it was learnt from how the light changed over time - guessed on the first
                    // frame and carried over from the last sector, so shadows swung round a while after a map loaded)
                    rising = lightAt(sector, rotation + 1f) > light;
                    e = light * 2f - 1f;
                }
            }else if(Core.settings.getBool(ShaderMod.dayOwn, false)){
                float minutes = Math.max(Core.settings.getInt(ShaderMod.dayMinutes, 12), 1);
                double phase = (Vars.state.tick / (minutes * 3600.0) + 0.3) % 1.0;
                e = -(float)Math.cos(phase * Math.PI * 2.0);
                rising = phase < 0.5;
                own = true;
            }
        }

        float set = Core.settings.getInt(ShaderMod.sunAngle, 45);
        if(Float.isNaN(e)){
            heldTurn = heldLength = Float.NaN;
            sunX = Mathf.cosDeg(set);
            sunY = Mathf.sinDeg(set);
            length = 1f;
            return;
        }
        elevation = e;
        // where along its way the sun is: an angle over the sky, 0 at sunrise, pi/2 at noon, pi at sunset
        float theta = rising ? (float)Math.asin(Mathf.clamp(e, -1f, 1f)) : Mathf.PI - (float)Math.asin(Mathf.clamp(e, -1f, 1f));
        hour = Mathf.clamp((theta - Mathf.halfPi) / Mathf.halfPi, -1.4f, 1.4f);

        // the turn of the sun: back to the middle through the night (at midnight morning and evening meet - from one
        // side to the other it jumped by 80 degrees)
        float turn = Mathf.clamp(hour, -1f, 1f) * 40f * (e < 0f ? Mathf.clamp(1f + e / 0.3f) : 1f);
        // (steps under a pixel at the tip of the longest shadow, as ShadowFx.lengthStep)
        float lstep = ShadowFx.lengthStep();
        float turnStep = (float)Math.toDegrees(lstep) * 0.8f;
        if(Float.isNaN(heldTurn) || Math.abs(turn - heldTurn) >= turnStep) heldTurn = turn;
        sunX = Mathf.cosDeg(set + heldTurn);
        sunY = Mathf.sinDeg(set + heldTurn);
        float l = Mathf.clamp(0.7f / Math.max(e, 0.3f), 0.6f, 1.9f);
        if(Float.isNaN(heldLength) || Math.abs(l - heldLength) >= lstep) heldLength = l;
        length = heldLength;
    }

    /** Whether there is a time of day now. */
    static boolean active(){
        update();
        return !Float.isNaN(elevation);
    }

    /** The direction towards the sun on the ground, length 1 (the set angle, moved by the time of day). */
    static float sunX(){
        update();
        return sunX;
    }

    static float sunY(){
        update();
        return sunY;
    }

    /** How long the shadows are now, as a share of the set length: short at noon, long with the sun low. */
    static float lengthFactor(){
        update();
        return length;
    }

    /** How much of the sun there is (for the mod's own day, which has no darkness of the game's): 1 by day, little at night. */
    static float sunlight(){
        if(!active() || !own) return 1f;
        return Mathf.lerp(0.2f, 1f, Mathf.clamp((elevation + 0.08f) / 0.33f));
    }

    /** For the last step of the frame: u_sky = a colour the picture is multiplied by, and how much of it. */
    static void apply(Shader s){
        float r = 1f, g = 1f, b = 1f, a = 0f, night = 0f, level = 1f;
        float k = ShaderMod.percent(ShaderMod.dayTint, 100);
        if(active() && k > 0f){
            float e = elevation;
            // sunrise and sunset: warm, reddish, strongest with the sun right on the horizon
            float dusk = (float)Math.exp(-(e - 0.06f) * (e - 0.06f) / 0.018f);
            r = Mathf.lerp(1f, 1.10f, dusk);
            g = Mathf.lerp(1f, 0.86f, dusk);
            b = Mathf.lerp(1f, 0.72f, dusk);
            a = Math.min(k, 2f);
            // Night is not a blue filter over the picture ("it looks like a photo with a blue filter, not real
            // moonlight"): the shader takes the colour out of what is dark (the eye sees little colour by
            // moonlight), cools it, darkens it - and leaves the light of lamps and lit buildings as it is, warm, so
            // they stand out of the night. (On the campaign the game's darkness is there already: less of it.)
            night = Mathf.clamp(-e / 0.3f) * Math.min(k, 1.5f);
            level = own ? 0.40f : 0.92f;
        }
        if(s.hasUniform("u_sky")) s.setUniformf("u_sky", r, g, b, a);
        if(s.hasUniform("u_night")) s.setUniformf("u_night", night, level);
    }
}
