package mshaders;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.graphics.Shaders.*;

/**
 * Replaces the shaders of the other liquid floors at run time, as WaterFx does for water: molten slag (crust drifting
 * on it with glowing seams), tar (an oily sheen, bubbles) and cryofluid (cold mist, frost along the shore, ice glints).
 * The game compiles its own in Shaders.init() and CacheLayer.init() copies the reference into the layer, so both are
 * switched together, and back when the setting is off.
 */
public class LiquidFx{
    private final Entry slag = new Entry(), tar = new Entry(), cryo = new Entry();

    private static class Entry{
        SurfaceShader original, fancy;
        ShaderLayer layer;
    }

    public void init(){
        set(slag, Shaders.slag, CacheLayer.slag, "mshaders-slag.frag");
        set(tar, Shaders.tar, CacheLayer.tar, "mshaders-tar.frag");
        set(cryo, Shaders.cryofluid, CacheLayer.cryofluid, "mshaders-cryofluid.frag");
        Events.run(Trigger.preDraw, this::sync);
    }

    private void set(Entry e, SurfaceShader original, CacheLayer layer, String file){
        e.original = original;
        e.layer = layer instanceof ShaderLayer s ? s : null;
        try{
            e.fancy = new SurfaceShader(ShaderMod.source("screenspace.vert"), ShaderMod.source(file)){
                @Override
                public void apply(){
                    super.apply();
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.liquidsStrength, 100));
                }
            };
        }catch(Throwable t){
            Log.err("[mshaders] " + file + " unavailable, keeping the built-in one", t);
        }
    }

    private void sync(){
        boolean on = Core.settings.getBool(ShaderMod.liquidsOn, true);
        sync(slag, on);
        sync(tar, on);
        sync(cryo, on);
    }

    private void sync(Entry e, boolean on){
        if(e.fancy == null) return;
        SurfaceShader want = on ? e.fancy : e.original, other = on ? e.original : e.fancy;
        // only touch the references that are still ours, in case another mod put its own shader there
        if(e == slag && Shaders.slag == other) Shaders.slag = want;
        if(e == tar && Shaders.tar == other) Shaders.tar = want;
        if(e == cryo && Shaders.cryofluid == other) Shaders.cryofluid = want;
        if(e.layer != null && e.layer.shader == other) e.layer.shader = want;
    }
}
