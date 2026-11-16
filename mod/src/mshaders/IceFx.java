package mshaders;

import arc.*;
import arc.graphics.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.graphics.Shaders.*;
import mindustry.world.*;

/**
 * Ice floor with a shader of its own.
 * The game draws floors in "cache layers"; a layer can have a shader (that is how water, tar and slag are animated,
 * graphics/CacheLayer.java:85-112), and CacheLayer.add() is the game's own way for a mod to add one. So the ice floors
 * are moved into a new layer whose shader is mshaders-ice.frag. Switched off, they go back to the normal layer and the
 * game draws them exactly as before.
 */
public class IceFx{
    private ShaderLayer layer;
    private Block[] floors;
    private boolean applied;

    public boolean ready(){
        return layer != null;
    }

    public void init(Scene scene){
        try{
            layer = new ShaderLayer(new IceShader(scene), false);
        }catch(Throwable t){
            layer = null;
            Log.err("[mshaders] ice shader unavailable, ice stays as it is", t);
            return;
        }
        // ice-snow is left out on purpose: it is snow with lumps of ice, and gloss over snow looks wrong
        floors = new Block[]{Blocks.ice, Blocks.redIce};
        CacheLayer.add(layer);
        apply(Core.settings.getBool(ShaderMod.iceOn, true));
        Events.run(Trigger.preDraw, () -> {
            boolean on = Core.settings.getBool(ShaderMod.iceOn, true);
            if(on != applied) apply(on);
        });
    }

    /** Moves the ice floors into the shader layer or back; the floor has to be cached again for that to show. */
    private void apply(boolean on){
        applied = on;
        for(Block floor : floors){
            // leave it alone if another mod has put the floor into a layer of its own
            if(floor.cacheLayer == layer || floor.cacheLayer == CacheLayer.normal) floor.cacheLayer = on ? layer : CacheLayer.normal;
        }
        if(Vars.state.isGame()) Vars.renderer.blocks.floor.reload();
    }

    static class IceShader extends SurfaceShader{
        final Scene scene;

        IceShader(Scene scene){
            super(ShaderMod.source("screenspace.vert"), ShaderMod.source("mshaders-ice.frag"));
            this.scene = scene;
        }

        @Override
        public void apply(){
            boolean mirror = Core.settings.getBool(ShaderMod.reflectOn, true) && scene.bindReflection(this);
            if(hasUniform("u_reflect")) setUniformf("u_reflect", mirror ? ShaderMod.percent(ShaderMod.reflectStrength, 100) : 0f);
            super.apply(); // u_campos, u_resolution, u_time, u_noise; ends with texture unit 0 active
            if(hasUniform("u_ice")) setUniformf("u_ice", ShaderMod.percent(ShaderMod.iceStrength, 100));
        }
    }
}
