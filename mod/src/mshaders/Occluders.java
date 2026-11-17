package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.struct.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.world.*;

/**
 * What stops light: one texel per tile, white where a solid block stands (walls, factories, turrets, map walls),
 * black elsewhere. "Solid" is the game's own Block.solid - what a ground unit cannot walk through - so conveyors,
 * conduits and other low blocks let light pass. (The game's shadow mask cannot be used for this: nearly every block
 * casts a shadow, conveyors included.)
 * The same picture also carries, in its other channels, which solid blocks are buildings and how tall every block is
 * (Heights.java) - for the mirror images and the shadows.
 * Rebuilt when a world loads, updated tile by tile when blocks are placed or removed.
 */
public class Occluders{
    // red = stops light and ground units; green = it is a building (not a map wall): only buildings get a mirror image;
    // blue = how tall the block is, Heights.max tiles = 255 (every block, also the low ones that stop no light)
    private static final int building = 0xffff00ff, wall = 0xff0000ff, open = 0x000000ff;

    private Pixmap pixmap;
    private Texture texture;
    /**
     * A second picture, for blocks that are not drawn as full squares: red, green = where the block's outline is in
     * the picture of outlines (Silhouettes.java; x and y of its slot, in tiles), blue = which tile of its block this
     * is (x * 16 + y, from the block's lower left), alpha = the block's size * 15. All 0 on a tile whose block is a
     * plain square, or that has none. Read unfiltered.
     */
    private final Silhouettes silhouettes = new Silhouettes();
    private Pixmap shapePixmap;
    private Texture shapeTexture;
    /** A third picture: how glossy the block on each tile is (Gloss.java), red 0..255 = 0..1; 128 where no building stands. Read unfiltered. */
    private Pixmap glossPixmap;
    private Texture glossTexture;
    private boolean dirty;
    /** Tiles holding a block that is only sometimes in the way (doors). The game sends no event when one opens or shuts, so these are looked at once a frame. */
    private final IntSeq doors = new IntSeq();
    private long doorsFrame = -1;

    public void init(){
        Events.on(WorldLoadEvent.class, e -> rebuild());
        Events.on(TileChangeEvent.class, e -> changed(e.tile));
        // a block that is drawn turned with its building has another outline now
        Events.on(BuildRotateEvent.class, e -> {
            if(e.build != null) changed(e.build.tile);
        });
    }

    private static int value(Tile tile){
        Block block = tile.block();
        // Bare water and ice (alpha 64): a surface that shows mirror images. The sun shadow is laid on more lightly
        // there, so the image of a building can be seen inside its own shadow.
        if(block == Blocks.air){
            var floor = tile.floor();
            return floor.cacheLayer == CacheLayer.water || floor == Blocks.ice || floor == Blocks.redIce ? 0x00000040 : open;
        }
        int height = Math.round(Heights.of(tile) / Heights.max * 255f) << 8;
        // boulders and other loose scenery: alpha 128 tells the shadow shader to give them a round shadow, not a square one
        // in the way right now? (a door only while it is shut)
        if(!Heights.standing(tile)) return (tile.build == null && !block.isStatic() ? 0x00000080 : open) | height;
        return (tile.build != null ? building : wall) | height;
    }

    /** What the second picture says about that tile (see shapePixmap). */
    private int shape(Tile tile){
        Block block = tile.block();
        // only what stands: the low things units walk over (conveyors, ducts) cast next to no shadow anyway
        if(block == Blocks.air || tile.build == null || tile.build.tile == null || !block.solid || block.size > 16) return 0;
        int slot = silhouettes.slot(block, block.rotate && block.rotateDraw ? tile.build.rotation : 0);
        if(slot < 0) return 0;
        int ox = tile.x - (tile.build.tile.x + block.sizeOffset), oy = tile.y - (tile.build.tile.y + block.sizeOffset);
        if(ox < 0 || oy < 0 || ox >= block.size || oy >= block.size) return 0;
        return (slot & 0xff) << 24 | (slot >> 8 & 0xff) << 16 | (ox * 16 + oy) << 8 | block.size * 15;
    }

    private static int gloss(Tile tile){
        float gloss = tile.build == null ? Gloss.plain : Gloss.of(tile.block());
        return Math.round(gloss * 255f) << 24 | 0xff;
    }

    private void rebuild(){
        int w = Vars.world.width(), h = Vars.world.height();
        if(texture != null) texture.dispose();
        if(pixmap != null) pixmap.dispose();
        if(shapeTexture != null) shapeTexture.dispose();
        if(shapePixmap != null) shapePixmap.dispose();
        if(glossTexture != null) glossTexture.dispose();
        if(glossPixmap != null) glossPixmap.dispose();
        texture = shapeTexture = glossTexture = null;
        pixmap = shapePixmap = glossPixmap = null;
        if(w <= 0 || h <= 0) return;

        pixmap = new Pixmap(w, h);
        shapePixmap = new Pixmap(w, h);
        glossPixmap = new Pixmap(w, h);
        doors.clear();
        for(Tile tile : Vars.world.tiles){
            // row 0 of the pixmap is v = 0 of the texture, so tile y needs no flipping
            pixmap.set(tile.x, tile.y, value(tile));
            shapePixmap.set(tile.x, tile.y, shape(tile));
            glossPixmap.set(tile.x, tile.y, gloss(tile));
            if(tile.block().solidifes) doors.add(tile.pos());
        }
        texture = new Texture(pixmap);
        texture.setFilter(TextureFilter.linear, TextureFilter.linear);
        shapeTexture = new Texture(shapePixmap);
        shapeTexture.setFilter(TextureFilter.nearest, TextureFilter.nearest);
        glossTexture = new Texture(glossPixmap);
        glossTexture.setFilter(TextureFilter.nearest, TextureFilter.nearest);
        dirty = false;
    }

    private void changed(Tile tile){
        if(pixmap == null || tile == null) return;
        write(tile);
        // a building covers several tiles; the event is not guaranteed for each of them
        if(tile.build != null && tile.build.tile != null){
            Tile origin = tile.build.tile;
            int size = tile.build.block.size, offset = tile.build.block.sizeOffset;
            for(int dx = 0; dx < size; dx++){
                for(int dy = 0; dy < size; dy++){
                    Tile other = Vars.world.tile(origin.x + dx + offset, origin.y + dy + offset);
                    if(other != null) write(other);
                }
            }
        }
    }

    private void write(Tile tile){
        if(tile.x < 0 || tile.y < 0 || tile.x >= pixmap.width || tile.y >= pixmap.height) return;
        pixmap.set(tile.x, tile.y, value(tile));
        shapePixmap.set(tile.x, tile.y, shape(tile));
        glossPixmap.set(tile.x, tile.y, gloss(tile));
        // keep the list of doors up to date
        int pos = tile.pos();
        boolean listed = doors.contains(pos);
        if(tile.block().solidifes && !listed) doors.add(pos);
        else if(!tile.block().solidifes && listed) doors.removeValue(pos);
        dirty = true;
    }

    /** @return the texture, brought up to date; null when there is no world or it does not match the loaded map */
    public Texture texture(){
        if(texture == null || pixmap.width != Vars.world.width() || pixmap.height != Vars.world.height()) return null;
        long frame = Core.graphics.getFrameId();
        if(doorsFrame != frame){
            doorsFrame = frame;
            // a door that opened or shut since the last frame
            for(int i = 0; i < doors.size; i++){
                Tile tile = Vars.world.tile(doors.get(i));
                if(tile == null) continue;
                int now = value(tile);
                if(pixmap.get(tile.x, tile.y) != now){
                    pixmap.set(tile.x, tile.y, now);
                    dirty = true;
                }
            }
        }
        if(dirty){
            texture.draw(pixmap);
            shapeTexture.draw(shapePixmap);
            glossTexture.draw(glossPixmap);
            dirty = false;
        }
        return texture;
    }

    /** @return the picture of blocks that are not plain squares, brought up to date; null when there is no world */
    public Texture shapes(){
        return texture() == null ? null : shapeTexture;
    }

    /** @return how glossy the block on each tile is, brought up to date; null when there is no world */
    public Texture gloss(){
        return texture() == null ? null : glossTexture;
    }

    /** @return the outlines those blocks have (Silhouettes.java), for where the sun is now; null while there are none */
    public Texture outlines(){
        return silhouettes.texture();
    }
}
