package mshaders;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.graphics.Shaders.*;
import mindustry.world.*;

/**
 * Dry ground with shaders of its own, done the way the ice is (IceFx): every kind of ground gets a cache layer whose
 * shader is mshaders-ground.frag compiled for that kind. A kind whose strength is 0, or everything when the switch is
 * off, goes back to the game's normal layer and is drawn exactly as before.
 *
 * Where two floors of different layers meet, the game lets the layer drawn later spill its edge sprites over the other
 * (Floor.drawNonLayer), whatever the floors' usual order is. So the layers are drawn in about the order the floors are
 * listed in the game (rock first, metal last), which keeps most borders as they were; a few pairs swap which floor
 * overlaps which.
 */
public class FloorFx{
    /** One kind of ground: its number in the shader, its strength setting and the floors it covers (internal names). */
    static class Kind{
        final int id;
        final String setting;
        final String[] names;
        final Seq<Block> floors = new Seq<>();
        ShaderLayer layer;
        boolean applied;

        Kind(int id, String setting, String... names){
            this.id = id;
            this.setting = setting;
            this.names = names;
        }
    }

    /** In drawing order: the first is drawn first, so later ones spill their edges over earlier ones. */
    final Kind[] kinds = {
        new Kind(1, ShaderMod.floorRock, "stone", "crater-stone", "char", "basalt", "dirt", "dacite", "rhyolite", "rhyolite-crater",
            "rough-rhyolite", "regolith", "yellow-stone", "carbon-stone", "ferric-stone", "ferric-craters", "beryllic-stone",
            "crystalline-stone", "crystal-floor", "yellow-stone-plates", "red-stone", "dense-red-stone", "arkyic-stone", "shale"),
        new Kind(6, ShaderMod.floorHot, "hotrock", "magmarock"),
        new Kind(2, ShaderMod.floorSand, "sand-floor", "darksand"),
        new Kind(5, ShaderMod.floorGrass, "grass", "moss", "spore-moss"),
        new Kind(4, ShaderMod.floorSnow, "salt"),
        new Kind(3, ShaderMod.floorSnow, "snow", "ice-snow"),
        new Kind(7, ShaderMod.floorMetal, "metal-floor", "metal-floor-damaged", "metal-floor-2", "metal-floor-3", "metal-floor-4",
            "metal-floor-5", "dark-panel-1", "dark-panel-2", "dark-panel-3", "dark-panel-4", "dark-panel-5", "dark-panel-6",
            "metal-tiles-1", "metal-tiles-2", "metal-tiles-3", "metal-tiles-4", "metal-tiles-5", "metal-tiles-6", "metal-tiles-7",
            "metal-tiles-8", "metal-tiles-9", "metal-tiles-10", "metal-tiles-11", "metal-tiles-12", "metal-tiles-13"),
    };

    private boolean ready;

    public boolean ready(){
        return ready;
    }

    public void init(){
        try{
            String source = ShaderMod.source("mshaders-ground.frag");
            for(Kind kind : kinds){
                kind.layer = new ShaderLayer(new GroundShader(kind, source), false);
                for(String name : kind.names){
                    Block floor = Vars.content.block(name);
                    if(floor != null && floor.isFloor()) kind.floors.add(floor);
                }
            }
        }catch(Throwable t){
            Log.err("[mshaders] ground shaders unavailable, the ground stays as it is", t);
            return;
        }
        // CacheLayer.add() puts a layer right in front of the game's normal layer, so the one added last is drawn first
        for(int i = kinds.length - 1; i >= 0; i--){
            CacheLayer.add(kinds[i].layer);
        }
        ready = true;
        apply();
        Events.run(Trigger.preDraw, this::apply);
    }

    /** Moves each kind into its shader layer or back, as the settings say; the floor is cached again only if something changed. */
    private void apply(){
        boolean on = Core.settings.getBool(ShaderMod.floorsOn, true), changed = false;
        for(Kind kind : kinds){
            boolean want = on && Core.settings.getInt(kind.setting, 100) > 0;
            if(want == kind.applied) continue;
            kind.applied = want;
            changed = true;
            for(Block floor : kind.floors){
                // leave it alone if another mod has put the floor into a layer of its own
                if(floor.cacheLayer == kind.layer || floor.cacheLayer == CacheLayer.normal) floor.cacheLayer = want ? kind.layer : CacheLayer.normal;
            }
        }
        if(changed && Vars.state.isGame()) Vars.renderer.blocks.floor.reload();
    }

    static class GroundShader extends SurfaceShader{
        final Kind kind;

        GroundShader(Kind kind, String source){
            super(ShaderMod.source("screenspace.vert"), "#define KIND " + kind.id + "\n" + source);
            this.kind = kind;
        }

        @Override
        public void apply(){
            super.apply(); // u_campos, u_resolution, u_time, u_noise; ends with texture unit 0 active
            if(hasUniform("u_amount")) setUniformf("u_amount", ShaderMod.percent(kind.setting, 100));
            if(hasUniform("u_px")) setUniformf("u_px", Core.camera.width / Math.max(Core.graphics.getWidth(), 1));
            if(hasUniform("u_relief")) setUniformf("u_relief", ShaderMod.percent(ShaderMod.floorRelief, 100));
            if(hasUniform("u_sunDir")) setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
        }
    }
}
