package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.math.*;
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
 *
 * Ores. The game draws an ore with the floor it lies on, into the same layer - so the shader shaded it as part of that
 * floor, and its relief reads the picture as heights: lighter = higher. On dark rock an ore is lighter than the ground
 * and stood up as pebbles; on sand and grass, which are lighter than the ore, the same ore came out as holes dug into
 * the ground (the maker: "the ores look very different on sand and grass from the other tiles"). So the shader is told
 * where the ore is, exactly: the picture of the map also holds, for every tile, where in the game's sprite sheet the
 * ore sprite of that tile is, and the shader reads the sprite's alpha there. What is ore is raised above the ground it
 * lies on, whatever the two colours are, and is left out of what the wind and the cracks do to the ground.
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

    /** The one in use (for the weather on the ground, which needs to know what floor is where). */
    static FloorFx current;
    private ShaderLayer layer;
    private boolean ready;
    /** The kind of each floor, by the floor's id; 0 = none. */
    private byte[] kindOf = {};
    /**
     * Where the sprite of an overlay is in the sprite sheet, by the overlay's id: one entry per variant, x * 65536 + y
     * of its upper left corner in pixels; null = not an overlay drawn the game's plain way, or not on that sheet.
     */
    private int[][] oreAt = {};
    /** The sprite sheet those sprites are on (null = none), and how many pixels across such a sprite is. */
    private Texture oreSheet;
    private int oreSize;
    /**
     * Which kind the floor of every tile is, and where its ore sprite is: one texel per tile. The upper three bits of
     * red = the kind; the lower five bits of red and green = x of the ore sprite in the sheet, blue and alpha = y
     * (both 0 = no ore).
     */
    private Pixmap pixmap;
    private Texture texture;
    private boolean dirty;

    public boolean ready(){
        return ready;
    }

    public void init(){
        current = this;
        try{
            layer = new ShaderLayer(new GroundShader(this, ShaderMod.source("mshaders-ground.frag")), false){
                // timed for the developer option "time of each step": the floor tiles of these kinds and the shader over them
                @Override
                public void begin(){
                    Clock.begin("ground");
                    super.begin();
                }

                @Override
                public void end(){
                    super.end();
                    Clock.end();
                }
            };
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
        Events.on(TileOverlayChangeEvent.class, e -> changed(e.tile));
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
        int id = tile.floorID(), over = tile.overlayID(), x = 0, y = 0;
        if(over > 0 && over < oreAt.length && oreAt[over] != null){
            // the variant the game draws there (OverlayFloor.drawBase)
            int[] variants = oreAt[over];
            int at = variants[Mathf.randomSeed(tile.pos(), 0, Math.max(0, variants.length - 1))];
            x = at >>> 16;
            y = at & 0xffff;
        }
        // row 0 of the pixmap is v = 0 of the texture, so tile y needs no flipping
        return ((id >= 0 && id < kindOf.length ? kindOf[id] * 32 : 0) | x >> 8) << 24 | (x & 0xff) << 16 | (y >> 8) << 8 | (y & 0xff);
    }

    /**
     * Looks up where the sprites of the ores are. Only overlays the game draws its plain way (one sprite of a variant,
     * one tile large: whatever still draws with OverlayFloor.drawBase; no wall ores, which lie on walls), all on one
     * sheet and of one size.
     */
    private void findOres(){
        oreAt = new int[Vars.content.blocks().size][];
        oreSheet = null;
        oreSize = 0;
        for(Block block : Vars.content.blocks()){
            if(!(block instanceof OverlayFloor) || ((OverlayFloor)block).wallOre) continue;
            // (most ores are anonymous subclasses of OreBlock - "new OreBlock(item){{ ... }}" - so the class itself
            // says nothing: what counts is that the drawing is still OverlayFloor's)
            try{
                if(block.getClass().getMethod("drawBase", Tile.class).getDeclaringClass() != OverlayFloor.class) continue;
            }catch(Throwable t){
                continue;
            }
            TextureRegion[] regions = block.variantRegions;
            if(regions == null || regions.length == 0 || block.variants <= 0) continue;
            int[] at = new int[regions.length];
            boolean fits = true;
            for(int i = 0; i < regions.length && fits; i++){
                TextureRegion region = regions[i];
                if(region == null || !region.found() || region.texture == null || region.width != region.height || region.width <= 0){
                    fits = false;
                    break;
                }
                if(oreSheet == null){
                    oreSheet = region.texture;
                    oreSize = region.width;
                }
                int x = Math.round(Math.min(region.u, region.u2) * region.texture.width), y = Math.round(Math.min(region.v, region.v2) * region.texture.height);
                fits = region.texture == oreSheet && region.width == oreSize && x < 8192 && y < 65536 && x + y > 0;
                at[i] = x << 16 | y;
            }
            if(fits) oreAt[block.id] = at;
            else Log.info("[mshaders] overlay @ is not on the ore sheet (@ variants, class @)", block.name, regions.length, block.getClass().getSimpleName());
        }
        int found = 0;
        for(int[] at : oreAt) if(at != null) found++;
        Log.info("[mshaders] ores: @ overlay(s) found on a sheet of @x@, @ px each", found, oreSheet == null ? 0 : oreSheet.width, oreSheet == null ? 0 : oreSheet.height, oreSize);
    }

    private void rebuild(){
        int w = Vars.world.width(), h = Vars.world.height();
        if(texture != null) texture.dispose();
        if(pixmap != null) pixmap.dispose();
        texture = null;
        pixmap = null;
        if(w <= 0 || h <= 0) return;
        try{
            findOres();
        }catch(Throwable t){
            Log.err("[mshaders] the ores are not found in the sprite sheet, they are shaded as the ground is", t);
            oreAt = new int[0][];
            oreSheet = null;
        }
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
            Texture ores = kinds == null ? null : owner.oreSheet;
            if(ores != null) ores.bind(3);
            super.apply(); // u_campos, u_resolution, u_time, u_noise; ends with texture unit 0 active
            Gl.activeTexture(Gl.texture0);
            if(hasUniform("u_kinds")) setUniformi("u_kinds", 2);
            if(hasUniform("u_tiles")) setUniformf("u_tiles", kinds == null ? 0f : kinds.width, kinds == null ? 0f : kinds.height);
            if(hasUniform("u_ores")) setUniformi("u_ores", 3);
            // (the hidden setting is for comparing: off = the ores are shaded as part of the ground, as they were)
            boolean lift = ores != null && Core.settings.getBool("mshaders-ore-lift", true);
            if(hasUniform("u_sheet")) setUniformf("u_sheet", lift ? ores.width : 0f, lift ? ores.height : 0f);
            if(hasUniform("u_oreSize")) setUniformf("u_oreSize", owner.oreSize);
            for(Kind kind : owner.kinds){
                if(hasUniform(kind.uniform)) setUniformf(kind.uniform, ShaderMod.percent(kind.setting, 100));
            }
            if(hasUniform("u_px")) setUniformf("u_px", Core.camera.width / Math.max(Core.graphics.getWidth(), 1));
            if(hasUniform("u_relief")) setUniformf("u_relief", ShaderMod.percent(ShaderMod.floorRelief, 100));
            if(hasUniform("u_sunDir")) setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
            Skies.applyGround(this);
        }
    }
}
