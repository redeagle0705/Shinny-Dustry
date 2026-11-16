package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.graphics.Shaders.*;
import mindustry.world.*;

/**
 * Dry ground with a shader of its own, done the way the ice is (IceFx): the floors of every kind of ground are moved
 * into one cache layer whose shader is mshaders-ground.frag. A kind whose strength is 0, or everything when the switch
 * is off, goes back to the game's normal layer and is drawn exactly as before.
 *
 * One layer for all kinds: a layer with a shader costs a clear of the effect buffer, the tiles drawn into it, and that
 * buffer laid over the whole screen - once per frame for every layer that has a tile in view. With a layer per kind
 * (as it was) a varied map seen from far out paid that up to seven times; on a phone that was the largest cost of the
 * whole mod. Now it is paid once, and the shader reads which kind a pixel is from a picture of the map with one texel
 * per tile (kinds).
 *
 * The borders between these floors are drawn as the game draws them (all in one layer, like the normal layer). Where
 * the edge of one floor lies over a tile of another, the edge gets the effect of the tile it lies on.
 */
public class FloorFx{
    /** One kind of ground: its number in the shader, its strength setting and the floors it covers (internal names). */
    static class Kind{
        final int id;
        final String setting, uniform;
        final String[] names;
        final Seq<Block> floors = new Seq<>();
        boolean applied;

        Kind(int id, String setting, String... names){
            this.id = id;
            this.setting = setting;
            this.uniform = "u_a" + id;
            this.names = names;
        }
    }

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

    private ShaderLayer layer;
    private boolean ready;
    /** The kind of each floor, by the floor's id; 0 = none. */
    private byte[] kindOf = {};
    /** Which kind the floor of every tile is: one texel per tile, red = kind * 32. */
    private Pixmap pixmap;
    private Texture texture;
    private boolean dirty;

    public boolean ready(){
        return ready;
    }

    public void init(){
        try{
            layer = new ShaderLayer(new GroundShader(this, ShaderMod.source("mshaders-ground.frag")), false);
            kindOf = new byte[Vars.content.blocks().size];
            for(Kind kind : kinds){
                for(String name : kind.names){
                    Block floor = Vars.content.block(name);
                    if(floor != null && floor.isFloor()){
                        kind.floors.add(floor);
                        kindOf[floor.id] = (byte)kind.id;
                    }
                }
            }
        }catch(Throwable t){
            Log.err("[mshaders] ground shader unavailable, the ground stays as it is", t);
            return;
        }
        // CacheLayer.add() puts a layer right in front of the game's normal layer
        CacheLayer.add(layer);
        ready = true;
        Events.on(WorldLoadEvent.class, e -> rebuild());
        Events.on(TileFloorChangeEvent.class, e -> changed(e.tile));
        apply();
        Events.run(Trigger.preDraw, this::apply);
    }

    /** Moves each kind into the shader layer or back, as the settings say; the floor is cached again only if something changed. */
    private void apply(){
        boolean on = Core.settings.getBool(ShaderMod.floorsOn, true), changed = false;
        for(Kind kind : kinds){
            boolean want = on && Core.settings.getInt(kind.setting, 100) > 0;
            if(want == kind.applied) continue;
            kind.applied = want;
            changed = true;
            for(Block floor : kind.floors){
                // leave it alone if another mod has put the floor into a layer of its own
                if(floor.cacheLayer == layer || floor.cacheLayer == CacheLayer.normal) floor.cacheLayer = want ? layer : CacheLayer.normal;
            }
        }
        if(changed && Vars.state.isGame()) Vars.renderer.blocks.floor.reload();
    }

    private int value(Tile tile){
        int id = tile.floorID();
        // row 0 of the pixmap is v = 0 of the texture, so tile y needs no flipping
        return (id >= 0 && id < kindOf.length ? kindOf[id] * 32 : 0) << 24 | 0xff;
    }

    private void rebuild(){
        int w = Vars.world.width(), h = Vars.world.height();
        if(texture != null) texture.dispose();
        if(pixmap != null) pixmap.dispose();
        texture = null;
        pixmap = null;
        if(w <= 0 || h <= 0) return;
        pixmap = new Pixmap(w, h);
        for(Tile tile : Vars.world.tiles){
            pixmap.set(tile.x, tile.y, value(tile));
        }
        texture = new Texture(pixmap);
        texture.setFilter(TextureFilter.nearest, TextureFilter.nearest);
        dirty = false;
    }

    private void changed(Tile tile){
        if(pixmap == null || tile == null || tile.x < 0 || tile.y < 0 || tile.x >= pixmap.width || tile.y >= pixmap.height) return;
        int now = value(tile);
        if(pixmap.get(tile.x, tile.y) != now){
            pixmap.set(tile.x, tile.y, now);
            dirty = true;
        }
    }

    /** @return the picture of kinds, brought up to date; null when there is no world */
    Texture kinds(){
        if(texture == null || pixmap.width != Vars.world.width() || pixmap.height != Vars.world.height()) rebuild();
        if(texture != null && dirty){
            texture.draw(pixmap);
            dirty = false;
        }
        return texture;
    }

    static class GroundShader extends SurfaceShader{
        final FloorFx owner;

        GroundShader(FloorFx owner, String source){
            super(ShaderMod.source("screenspace.vert"), source);
            this.owner = owner;
        }

        @Override
        public void apply(){
            Texture kinds = owner.kinds();
            if(kinds != null) kinds.bind(2);
            super.apply(); // u_campos, u_resolution, u_time, u_noise; ends with texture unit 0 active
            Gl.activeTexture(Gl.texture0);
            if(hasUniform("u_kinds")) setUniformi("u_kinds", 2);
            if(hasUniform("u_tiles")) setUniformf("u_tiles", kinds == null ? 0f : kinds.width, kinds == null ? 0f : kinds.height);
            for(Kind kind : owner.kinds){
                if(hasUniform(kind.uniform)) setUniformf(kind.uniform, ShaderMod.percent(kind.setting, 100));
            }
            if(hasUniform("u_px")) setUniformf("u_px", Core.camera.width / Math.max(Core.graphics.getWidth(), 1));
            if(hasUniform("u_relief")) setUniformf("u_relief", ShaderMod.percent(ShaderMod.floorRelief, 100));
            if(hasUniform("u_sunDir")) setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
        }
    }
}
