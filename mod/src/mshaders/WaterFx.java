package mshaders;

import arc.*;
import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.graphics.Shaders.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

/**
 * Replaces the water shader at run time.
 * The built-in one is compiled in Shaders.init() before any mod file is known, and CacheLayer.init() copies the reference into
 * CacheLayer.water, so both Shaders.water and CacheLayer.water.shader are switched together. The original is kept for switching back.
 */
public class WaterFx{
    private SurfaceShader original, fancy;
    private ShaderLayer layer;
    /** The water floors, with whether units cast a shadow on them as the game defines it (Floor.canShadow). */
    private final ObjectMap<Floor, Boolean> shadowed = new ObjectMap<>();
    private boolean shadowsOff;

    public boolean ready(){
        return fancy != null;
    }

    public void init(Scene scene){
        original = Shaders.water;
        layer = CacheLayer.water instanceof ShaderLayer s ? s : null;
        try{
            fancy = new FancyWater(scene);
        }catch(Throwable t){
            // e.g. the shader does not compile on this GPU: leave the game's own water alone
            Log.err("[mshaders] water shader unavailable, keeping the built-in one", t);
            return;
        }
        for(Block block : Vars.content.blocks()){
            if(block instanceof Floor floor && floor.cacheLayer == CacheLayer.water) shadowed.put(floor, floor.canShadow);
        }
        Events.run(Trigger.preDraw, this::sync);
    }

    /** Applies the on/off setting; runs once per frame before the world is drawn. */
    private void sync(){
        SurfaceShader want = Core.settings.getBool(ShaderMod.waterOn, true) ? fancy : original;
        SurfaceShader other = want == fancy ? original : fancy;
        // only touch the references that are still ours, in case another mod put its own shader there
        if(Shaders.water == other) Shaders.water = want;
        if(layer != null && layer.shader == other) layer.shader = want;

        // A unit over water showed twice: its shadow, which the game draws, and its mirror image, which this mod adds -
        // it read as two shadows. While the mirror image is there, the shadow on water is left out. The game has a
        // switch for exactly this, per floor (Floor.canShadow, read only in UnitType.drawShadow, which fades the shadow
        // in and out as a unit crosses the shore); it is put back whenever the mirror image is not shown.
        boolean mirrored = want == fancy && Core.settings.getBool(ShaderMod.reflectOn, true) && Vars.renderer.animateWater;
        if(mirrored != shadowsOff){
            shadowsOff = mirrored;
            for(var entry : shadowed){
                entry.key.canShadow = !mirrored && entry.value;
            }
        }
    }

    static class FancyWater extends SurfaceShader{
        final Scene scene;

        FancyWater(Scene scene){
            super(ShaderMod.source("screenspace.vert"), ShaderMod.source("mshaders-water.frag"));
            this.scene = scene;
        }

        @Override
        public void apply(){
            // the water is drawn before anything that could be mirrored in it, so the image of the previous frame is used
            boolean mirror = Core.settings.getBool(ShaderMod.reflectOn, true) && scene.bindReflection(this);
            set("u_reflect", mirror ? ShaderMod.percent(ShaderMod.reflectStrength, 100) : 0f);
            super.apply(); // u_campos, u_resolution, u_time, u_noise; ends with texture unit 0 active
            set("u_wave", ShaderMod.percent(ShaderMod.waterWave, 100));
            set("u_sparkle", ShaderMod.percent(ShaderMod.waterSparkle, 100));
            set("u_foam", ShaderMod.percent(ShaderMod.waterFoam, 100));
        }

        // Shader.setUniformf throws for a uniform the GLSL compiler removed, so check first
        private void set(String name, float value){
            if(hasUniform(name)) setUniformf(name, value);
        }
    }
}
