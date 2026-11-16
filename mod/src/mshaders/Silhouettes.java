package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.draw.*;

import java.nio.*;

/**
 * The outlines of the blocks that are not drawn as full squares. Many blocks leave part of their tiles empty: corners
 * left transparent to look round or cut off (power nodes, most turret bases of Erekir), dents in their sides, a
 * picture smaller than its tiles. The shadow of such a block must be the shadow of what is drawn, not of a square.
 *
 * One picture holds them all, 16 texels per tile, each kind of block in a place of its own ("slot"; a block that is
 * drawn turned with its building has one per direction). Per texel:
 *   green = how much of the texel the block covers (its sprite's opacity);
 *   blue  = the same, blurred over a quarter of a tile (for the soft rim of shade around a block);
 *   red   = how far from here, going towards the sun, the block begins: 0 on the block, 255 = it never does, else
 *           tiles * unit. Worked out here, once, for the direction the sun is in - so the shadow shader reads ONE texel
 *           per block to know whether a point is in its shadow: at the place where the line from the point towards
 *           the sun enters the block's tiles (mshaders-shadow.frag, toOutline). Made again when the sun is moved.
 *
 * A block whose sprite fills its tiles (all but 1.5% of it) has no slot and stays a plain square.
 *
 * The game does not hand out the pixels of its sprites while it runs (TextureAtlas.getPixmap: "Calling getPixmap
 * outside of createIcons is not supported"), so the sprite is drawn into a small buffer and read back from there.
 * That needs the sprite batch: slot() is to be called from the game's own thread and not in the middle of a frame's
 * drawing (it is called when a world loads and when a block is placed or turned).
 */
public class Silhouettes{
    /** Texels per tile. */
    public static final int cell = 16;
    /** The picture is this many tiles wide and high (CELLS in the shaders). */
    public static final int cells = 64;
    /** Red: tiles * unit (FAR in the shaders); 255 = never. */
    public static final float unit = 24f;
    /** The buffer a sprite is drawn into to be read; larger sprites are drawn smaller to fit. */
    private static final int room = 256;
    private static final int none = -1;

    private Pixmap pixmap;
    private Texture texture;
    private boolean dirty, failed;
    /** block id * 4 + direction -> slot x | slot y << 8 | size << 16, or none */
    private final IntIntMap slots = new IntIntMap();
    private final IntSeq taken = new IntSeq();
    private int nextX, nextY, rowTall;
    private float sunX = Float.NaN, sunY = Float.NaN;
    private FrameBuffer buffer;
    private ByteBuffer pixels;

    /**
     * Where the outline of that block, turned that way (0..3, quarter turns to the left), is in the picture.
     * @return slot x | slot y << 8, in tiles of the picture; -1 for a plain square
     */
    public int slot(Block block, int turned){
        int key = block.id * 4 + (turned & 3);
        int known = slots.get(key, Integer.MIN_VALUE);
        if(known != Integer.MIN_VALUE) return known == none ? none : known & 0xffff;
        int made = none;
        try{
            if(!failed) made = make(block, turned & 3);
        }catch(Throwable t){
            // then every block is a square, as before
            Log.err("[mshaders] sprite of " + block.name + " cannot be read, block shadows stay square", t);
            failed = true;
        }
        slots.put(key, made);
        return made == none ? none : made & 0xffff;
    }

    /** @return the picture, brought up to date for where the sun is; null while it holds nothing */
    public Texture texture(){
        if(pixmap == null) return null;
        float x = ShaderMod.sunX(), y = ShaderMod.sunY();
        if(x != sunX || y != sunY){
            sunX = x;
            sunY = y;
            for(int i = 0; i < taken.size; i++){
                int slot = taken.get(i);
                towardsSun(slot & 0xff, slot >> 8 & 0xff, slot >> 16);
            }
            dirty = true;
        }
        if(texture == null){
            texture = new Texture(pixmap);
            texture.setFilter(TextureFilter.linear, TextureFilter.linear);
            dirty = false;
        }else if(dirty){
            texture.draw(pixmap);
            dirty = false;
        }
        return texture;
    }

    /** The sprite that shows the outline of the block: the base of a turret (its head turns and overhangs), else the block's picture. */
    private static TextureRegion outline(Block block){
        TextureRegion region = block instanceof Turret turret && turret.drawer instanceof DrawTurret draw ? draw.base : null;
        if(region == null || !region.found()) region = Core.atlas.find(block.name + "-base");
        if(!region.found() && block instanceof BaseTurret) region = Core.atlas.find("block-" + block.size);
        if(!region.found()) region = block.fullIcon;
        return region;
    }

    private int make(Block block, int turned){
        TextureRegion region = outline(block);
        int size = block.size;
        if(region == null || !region.found() || size < 1 || size > 16) return none;
        // only sprites that are as large as the block (a sprite of another size says nothing about the tiles)
        if(region.width < 8 || region.width != region.height || Math.abs(region.width - size * 32f) > 2f) return none;
        int w = Math.min(region.width, room);

        // the sprite, drawn into a buffer and read back
        if(buffer == null){
            buffer = new FrameBuffer(room, room);
            pixels = Buffers.newByteBuffer(room * room * 4);
        }
        Draw.flush();
        Mat before = new Mat(Draw.proj());
        buffer.begin(Color.clear);
        Draw.proj(new Mat().setOrtho(0f, 0f, room, room));
        Draw.reset();
        Draw.rect(region, w / 2f, w / 2f, w, w);
        Draw.flush();
        pixels.clear();
        Gl.pixelStorei(Gl.packAlignment, 1);
        Gl.readPixels(0, 0, w, w, Gl.rgba, Gl.unsignedByte, pixels);
        buffer.end();
        Draw.proj(before);

        // 16 texels per tile: each the mean opacity of the sprite pixels under it, turned as the building is
        int n = size * cell;
        float[] cover = new float[n * n];
        float per = w / (float)n;
        int empty = 0;
        for(int j = 0; j < n; j++){
            for(int i = 0; i < n; i++){
                // where this texel is on the sprite as drawn unturned
                int si = turned == 0 ? i : turned == 1 ? j : turned == 2 ? n - 1 - i : n - 1 - j;
                int sj = turned == 0 ? j : turned == 1 ? n - 1 - i : turned == 2 ? n - 1 - j : i;
                int x0 = (int)(si * per), x1 = Math.max((int)((si + 1) * per), x0 + 1), y0 = (int)(sj * per), y1 = Math.max((int)((sj + 1) * per), y0 + 1);
                int sum = 0, count = 0;
                for(int y = y0; y < y1 && y < w; y++){
                    for(int x = x0; x < x1 && x < w; x++){
                        sum += pixels.get((y * w + x) * 4 + 3) & 0xff;
                        count++;
                    }
                }
                float c = count == 0 ? 0f : sum / (count * 255f);
                cover[j * n + i] = c;
                if(c < 0.5f) empty++;
            }
        }
        // it fills its tiles: a plain square
        if(empty < n * n * 0.015f) return none;

        // a place in the picture: rows of blocks, each row as tall as its tallest
        if(nextX + size > cells){
            nextX = 0;
            nextY += rowTall;
            rowTall = 0;
        }
        if(nextY + size > cells){
            if(!failed) Log.warn("[mshaders] no room left for the outline of @, its shadow stays square", block.name);
            return none;
        }
        int sx = nextX, sy = nextY;
        nextX += size;
        rowTall = Math.max(rowTall, size);

        if(pixmap == null){
            pixmap = new Pixmap(cells * cell, cells * cell);
            pixmap.fill(0xff0000ff);   // red 255: no block anywhere
        }
        // blurred: the mean over a quarter of a tile each way, nothing outside the block's tiles
        int reach = cell / 4;
        float[] across = new float[n * n];
        for(int j = 0; j < n; j++){
            for(int i = 0; i < n; i++){
                float sum = 0f;
                for(int d = -reach; d <= reach; d++){
                    if(i + d >= 0 && i + d < n) sum += cover[j * n + i + d];
                }
                across[j * n + i] = sum / (reach * 2 + 1);
            }
        }
        for(int j = 0; j < n; j++){
            for(int i = 0; i < n; i++){
                float sum = 0f;
                for(int d = -reach; d <= reach; d++){
                    if(j + d >= 0 && j + d < n) sum += across[(j + d) * n + i];
                }
                int green = Math.round(cover[j * n + i] * 255f), blue = Math.round(sum / (reach * 2 + 1) * 255f);
                pixmap.set(sx * cell + i, sy * cell + j, 0xff000000 | green << 16 | blue << 8 | 0xff);
            }
        }
        int slot = sx | sy << 8 | size << 16;
        taken.add(slot);
        if(!Float.isNaN(sunX)) towardsSun(sx, sy, size);
        dirty = true;
        return slot;
    }

    /** Fills in the red of one slot: from every texel, how far towards the sun the block begins. */
    private void towardsSun(int sx, int sy, int size){
        int n = size * cell;
        float[] cover = new float[n * n];
        for(int j = 0; j < n; j++){
            for(int i = 0; i < n; i++){
                cover[j * n + i] = (pixmap.get(sx * cell + i, sy * cell + j) >>> 16 & 0xff) / 255f;
            }
        }
        float step = 0.5f;
        for(int j = 0; j < n; j++){
            for(int i = 0; i < n; i++){
                int red;
                if(cover[j * n + i] >= 0.5f){
                    red = 0;
                }else{
                    red = 255;
                    float x = i + 0.5f, y = j + 0.5f, last = cover[j * n + i];
                    for(float s = step; ; s += step){
                        float px = x + sunX * s, py = y + sunY * s;
                        if(px < 0f || py < 0f || px > n || py > n) break;
                        float now = sample(cover, n, px, py);
                        if(now >= 0.5f){
                            // where between the last step and this one the half was passed
                            float hit = s - step * (now - 0.5f) / Math.max(now - last, 0.001f);
                            red = Math.min(Math.round(hit / cell * unit), 254);
                            break;
                        }
                        last = now;
                    }
                }
                int at = pixmap.get(sx * cell + i, sy * cell + j);
                pixmap.set(sx * cell + i, sy * cell + j, at & 0x00ffffff | red << 24);
            }
        }
    }

    /** The cover at a point (texel i has its middle at i + 0.5), between the texels around it; the edge texels reach to the edge. */
    private static float sample(float[] cover, int n, float x, float y){
        float fx = Mathf.clamp(x - 0.5f, 0f, n - 1f), fy = Mathf.clamp(y - 0.5f, 0f, n - 1f);
        int x0 = (int)fx, y0 = (int)fy, x1 = Math.min(x0 + 1, n - 1), y1 = Math.min(y0 + 1, n - 1);
        float ax = fx - x0, ay = fy - y0;
        return Mathf.lerp(Mathf.lerp(cover[y0 * n + x0], cover[y0 * n + x1], ax), Mathf.lerp(cover[y1 * n + x0], cover[y1 * n + x1], ax), ay);
    }
}
