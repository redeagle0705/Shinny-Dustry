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
    /**
     * A fourth picture: the floor of each tile, as the map shows it (its map colour), alpha 0 on water. For the sun
     * shadows, which take after what they fall on - pale and soft on snow, where the light thrown back from all round
     * fills them in ("shadows this sharp look odd on the snow"), deep on dark rock. Read filtered, so it changes softly
     * from one floor to the next.
     */
    private Pixmap floorPixmap;
    private Texture floorTexture;
    private boolean dirty;
    /**
     * The tiles changed since the pictures were last sent to the graphics card: the smallest rectangle that holds them.
     * Only that is sent. (All three pictures used to be sent whole whenever a single block was placed or shot down -
     * on a busy server several times a second, a map's worth of data each time.)
     */
    private int fromX, fromY, toX, toY;

    private void changedAt(int x, int y){
        if(!dirty){
            fromX = toX = x;
            fromY = toY = y;
        }else{
            fromX = Math.min(fromX, x);
            toX = Math.max(toX, x);
            fromY = Math.min(fromY, y);
            toY = Math.max(toY, y);
        }
        dirty = true;
    }

    private static void send(Texture texture, Pixmap whole, int x, int y, int w, int h){
        Pixmap part = whole.crop(x, y, w, h);
        texture.draw(part, x, y);
        part.dispose();
    }
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
        // (129: a boulder drawn as one plain picture, which ShadowFx.onProps shades through that picture; the shadows on
        // blocks leave it alone. Others - seaweed, sea bushes - keep the round patch.)
        if(!Heights.standing(tile)) return (tile.build == null && !block.isStatic() ? (ShadowFx.plainProp(block) ? 0x00000081 : 0x00000080) : open) | height;
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
        // under a turret or a mass driver alpha 128 (= not known yet to keep still): its head turns, and the running
        // water and puddles of the rain (Wetness), laid out on the ground under it, would flicker over it as it does
        // ("the drops sparkle when the turret turns"); Wetness writes how still it is, 128 .. 254
        return Math.round(gloss * 255f) << 24 | (tile.build != null && (tile.block() instanceof mindustry.world.blocks.defense.turrets.BaseTurret || tile.block() instanceof mindustry.world.blocks.distribution.MassDriver) ? 128 : 0xff);
    }

    /**
     * How wet the block on that tile is and how deep water stands on it (Wetness.java), 0 .. 255 each, kept in the green and blue of the gloss picture: a block wet
     * with rain shines as a glossy one does. Sent with the rest at the start of the next frame.
     */
    public void wet(int x, int y, int amount, int depth, float steady){
        wet(x, y, amount, depth, steady, -1);
    }

    /**
     * @param open for a tile at the edge of the map's own walls, which of its sides face open ground (1 = up, 2 = right,
     *             4 = down, 8 = left): alpha 64 + that. The shaders slope a band along those sides outwards - the bevel
     *             such a wall is drawn with - and keep the rest flat, so the rain runs off the edge. -1 = not such a tile.
     */
    public void wet(int x, int y, int amount, int depth, float steady, int open){
        if(glossPixmap == null || x < 0 || y < 0 || x >= glossPixmap.width || y >= glossPixmap.height) return;
        int at = glossPixmap.get(x, y), now = at & 0xff0000ff | (amount & 0xff) << 16 | (depth & 0xff) << 8;
        // under a head that turns: alpha 128 .. 254 = how still it is (Wetness.steadiness); 255 = nothing turns here
        if(steady >= 0f) now = now & 0xffffff00 | 128 + Math.round(Math.min(steady, 1f) * 126f);
        else if(open >= 0) now = now & 0xffffff00 | 64 + (open & 15);
        if(now == at) return;
        glossPixmap.set(x, y, now);
        changedAt(x, y);
    }

    /**
     * How glossy the block on that tile is, lowered by f (0 = as it is made, 1 = matt): spores settled on it dull it
     * (SnowCover.dull). The red of the gloss picture; the rest is kept.
     */
    public void dull(int x, int y, float f){
        if(glossPixmap == null || x < 0 || y < 0 || x >= glossPixmap.width || y >= glossPixmap.height) return;
        Tile tile = Vars.world.tile(x, y);
        if(tile == null) return;
        int base = gloss(tile) >>> 24;
        int r = Math.round(base * (1f - Math.max(0f, Math.min(f, 1f))));
        int at = glossPixmap.get(x, y), now = at & 0x00ffffff | (r & 0xff) << 24;
        if(now == at) return;
        glossPixmap.set(x, y, now);
        changedAt(x, y);
    }

    /** @return how wet the block on that tile was last set to be, 0 .. 255 */
    public int wetAt(int x, int y){
        if(glossPixmap == null || x < 0 || y < 0 || x >= glossPixmap.width || y >= glossPixmap.height) return 0;
        return glossPixmap.get(x, y) >>> 16 & 0xff;
    }

    private void rebuild(){
        int w = Vars.world.width(), h = Vars.world.height();
        if(texture != null) texture.dispose();
        if(pixmap != null) pixmap.dispose();
        if(shapeTexture != null) shapeTexture.dispose();
        if(shapePixmap != null) shapePixmap.dispose();
        if(glossTexture != null) glossTexture.dispose();
        if(glossPixmap != null) glossPixmap.dispose();
        if(floorTexture != null) floorTexture.dispose();
        if(floorPixmap != null) floorPixmap.dispose();
        texture = shapeTexture = glossTexture = floorTexture = null;
        pixmap = shapePixmap = glossPixmap = floorPixmap = null;
        if(w <= 0 || h <= 0) return;

        pixmap = new Pixmap(w, h);
        shapePixmap = new Pixmap(w, h);
        glossPixmap = new Pixmap(w, h);
        floorPixmap = new Pixmap(w, h);
        doors.clear();
        for(Tile tile : Vars.world.tiles){
            // row 0 of the pixmap is v = 0 of the texture, so tile y needs no flipping
            pixmap.set(tile.x, tile.y, value(tile));
            shapePixmap.set(tile.x, tile.y, shape(tile));
            glossPixmap.set(tile.x, tile.y, gloss(tile));
            floorPixmap.set(tile.x, tile.y, floor(tile));
            if(tile.block().solidifes) doors.add(tile.pos());
        }
        floorTexture = new Texture(floorPixmap);
        floorTexture.setFilter(TextureFilter.linear, TextureFilter.linear);
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
        // (how wet the tile is, in green, is kept: a block turned or changed in the rain flashed dry until the next
        // pass of Wetness, which corrects a block that is gone or new within a few frames anyway)
        glossPixmap.set(tile.x, tile.y, gloss(tile) | glossPixmap.get(tile.x, tile.y) & 0x00ffff00);
        floorPixmap.set(tile.x, tile.y, floor(tile));
        // keep the list of doors up to date
        int pos = tile.pos();
        boolean listed = doors.contains(pos);
        if(tile.block().solidifes && !listed) doors.add(pos);
        else if(!tile.block().solidifes && listed) doors.removeValue(pos);
        changedAt(tile.x, tile.y);
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
                    changedAt(tile.x, tile.y);
                }
            }
        }
        if(dirty){
            Clock.begin("upload");
            int w = toX - fromX + 1, h = toY - fromY + 1;
            if(w * h * 2 >= pixmap.width * pixmap.height){
                // most of the map: whole
                texture.draw(pixmap);
                shapeTexture.draw(shapePixmap);
                glossTexture.draw(glossPixmap);
                floorTexture.draw(floorPixmap);
            }else{
                send(texture, pixmap, fromX, fromY, w, h);
                send(shapeTexture, shapePixmap, fromX, fromY, w, h);
                send(glossTexture, glossPixmap, fromX, fromY, w, h);
                send(floorTexture, floorPixmap, fromX, fromY, w, h);
            }
            Clock.end();
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

    /** @return the floor of each tile (see floorPixmap), brought up to date; null when there is no world */
    public Texture floors(){
        return texture() == null ? null : floorTexture;
    }

    /** What the floor picture says about a tile: its floor's map colour, alpha 0 on water (no snow lies there). */
    private static int floor(Tile tile){
        var floor = tile.floor();
        Color c = floor.mapColor;
        boolean wet = floor.isLiquid || floor.cacheLayer == CacheLayer.water;
        return Color.rgba8888(c.r, c.g, c.b, wet ? 0f : 1f);
    }

    /** @return the outlines those blocks have (Silhouettes.java), for where the sun is now; null while there are none */
    public Texture outlines(){
        return silhouettes.texture();
    }
}
