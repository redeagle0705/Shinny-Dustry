package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.mod.*;
import mindustry.entities.part.*;
import mindustry.game.Team;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.draw.*;

/**
 * Gives the sprites this mod replaces (sprites-override/) the dark outline the game's own sprites have.
 *
 * The heads of turrets, the mass driver and the like have an outline around them. It is not drawn while the game runs:
 * it is painted into the sprite when the game is built (Block.createIcons: Pixmaps.outline with the block's
 * outlineColor and outlineRadius, for blocks with outlineIcon set). The sprites the artists draw have none - and so
 * have the ones drawn for this mod. A sprite from sprites-override/ replaces the game's finished sprite as it is, so
 * the outline was simply gone.
 *
 * Here the same is done once the game has loaded: for every block of the game that has such an outline and whose
 * sprite this mod replaces, the outline is painted around the mod's sprite the way the game does it, and the sprite's
 * place in the game's list of sprites is pointed at the result. The picture is made as much larger as the outline is
 * wide, so a drawing may reach the very edge of its file; sprites are drawn around their middle, so nothing moves.
 * Normal maps need no outline: where they are transparent nothing is shaded, and the outline stays as dark as it is.
 *
 * Units too: the game paints the outline into the body of every unit, into its legs, feet, joints and treads, and into
 * the weapons that lie on top of it (UnitType.createIcons). A replaced sprite of any of those gets its outline the same
 * way, in the unit's outlineColor and outlineRadius.
 *
 * A sprite that already has an outline (most of its rim is of the outline colour) is left alone.
 *
 * One more thing the game makes when it is built, and a replaced sprite therefore lacks: the patch in the team's colour
 * on a core (and on any block that has one), "<block>-team". It is drawn in three marker tones - white, #dcc6c6,
 * #9d7f7f - and for every team that has a palette of three colours the game keeps a copy with the tones swapped for
 * that team's, "<block>-team-<team>", and draws that. So a replaced "<block>-team" showed only for the teams without a
 * palette - not for the player's own. Here those copies are made anew from the replaced sprite, the same way.
 */
public class Outlines{
    /** @return how many sprites were given an outline */
    public static int apply(){
        try{
            return make();
        }catch(Throwable t){
            Log.err("[mshaders] outlines of replaced sprites not made", t);
            return 0;
        }
    }

    private static int make(){
        Mods.LoadedMod mod = Vars.mods.getMod(ShaderMod.class);
        if(mod == null) return 0;
        return make(mod.root.child("sprites-override"), false);
    }

    /** When the watched folder is next looked at, what it held when its sprites were last taken, and what it held at the last look. */
    private static long nextLook, taken = Long.MIN_VALUE, last = Long.MIN_VALUE;
    /** The texture the sprites of the watched folder were last put into. */
    private static Texture watchedTexture;

    /**
     * What a sprite of the game was before a sprite of the watched folder took its place, kept from the first time
     * that happened. The sprites of the folder are all put into one texture, and at every reading into a new one, the
     * one before being thrown away. A sprite whose picture had been REMOVED from the folder in the meantime was left
     * pointing into the texture thrown away - and a texture thrown away draws as plain black over the whole square of
     * the sprite. (The maker deleted scatter-preview.png with the game open: the game draws the shadow of a turret's
     * head with that sprite, and the scatter got a dark square under it, turning with its head.)
     */
    private static class Was{
        final Texture texture;
        final float u, v, u2, v2, offsetX, offsetY;
        final int width, height, packedWidth, packedHeight, originalWidth, originalHeight;
        final PixmapRegion pixmapRegion;

        Was(AtlasRegion region){
            texture = region.texture;
            u = region.u;
            v = region.v;
            u2 = region.u2;
            v2 = region.v2;
            width = region.width;
            height = region.height;
            offsetX = region.offsetX;
            offsetY = region.offsetY;
            packedWidth = region.packedWidth;
            packedHeight = region.packedHeight;
            originalWidth = region.originalWidth;
            originalHeight = region.originalHeight;
            pixmapRegion = region.pixmapRegion;
        }

        void back(AtlasRegion region){
            region.texture = texture;
            region.u = u;
            region.v = v;
            region.u2 = u2;
            region.v2 = v2;
            region.width = width;
            region.height = height;
            region.offsetX = offsetX;
            region.offsetY = offsetY;
            region.packedWidth = packedWidth;
            region.packedHeight = packedHeight;
            region.originalWidth = originalWidth;
            region.originalHeight = originalHeight;
            region.pixmapRegion = pixmapRegion;
        }
    }

    /** Every sprite a sprite of the watched folder has ever taken the place of, with what it was before. */
    private static final ObjectMap<AtlasRegion, Was> before = new ObjectMap<>();
    /** The sprites that point into the watched folder's texture right now. */
    private static final Seq<AtlasRegion> watchedNow = new Seq<>();

    /** The sprites of the watched folder's texture that the folder no longer brings: back to what they were. */
    private static void giveBack(Seq<AtlasRegion> still){
        int back = 0;
        for(AtlasRegion region : watchedNow){
            if(still.contains(region, true)) continue;
            Was was = before.get(region);
            if(was == null) continue;
            was.back(region);
            back++;
        }
        watchedNow.clear();
        watchedNow.addAll(still);
        if(back > 0) Log.info("[mshaders] @ sprite(s) no longer in the watched folder: back to what they were", back);
    }

    /**
     * Drawing with the game open (Surfaces.watched): to be called every frame. Twice a second it looks whether a
     * sprite in the watched folder's sprites-override has been saved, added or removed; if so, every sprite of that
     * folder is put in place of the game's sprite of the same name, with its outline where the game gives one - as at
     * the start of the game, but from that folder and at once. (At the start they are taken once in any case:
     * takeWatched.)
     * A sprite that was removed from the folder goes back to what it was before the folder's sprite took its place
     * ({@link Was}).
     */
    public static void watch(){
        long now = Time.millis();
        if(now < nextLook) return;
        nextLook = now + 500;
        Fi watch = Surfaces.watched();
        Fi folder = watch == null ? null : watch.child("sprites-override");
        if(folder == null || !folder.exists()) return;
        try{
            long holds = holding(folder);
            // the same at two looks in a row: nothing is being written right now
            if(holds == taken || holds != last){
                last = holds;
                return;
            }
            taken = holds;
            Log.info("[mshaders] @ sprite(s) taken from the watched folder", take(folder));
        }catch(Throwable t){
            Log.err("[mshaders] sprites of the watched folder not taken", t);
        }
    }

    /** What that folder holds right now, as one number: the names, sizes and dates of the pictures in it. */
    private static long holding(Fi folder){
        long[] holds = {17};
        folder.walk(file -> {
            if(!file.extEquals("png")) return;
            holds[0] = holds[0] * 31 + file.name().hashCode();
            holds[0] = holds[0] * 31 + file.lastModified();
            holds[0] = holds[0] * 31 + file.length();
        });
        return holds[0];
    }

    /** At the start of the game: the sprites of the watched folder, if there is one, in place. */
    public static void takeWatched(){
        try{
            Fi watch = Surfaces.watched();
            Fi folder = watch == null ? null : watch.child("sprites-override");
            if(folder == null || !folder.exists()) return;
            taken = last = holding(folder);
            Log.info("[mshaders] @ sprite(s) taken from the watched folder", take(folder));
        }catch(Throwable t){
            Log.err("[mshaders] sprites of the watched folder not taken", t);
        }
    }

    /** Every sprite of that folder in place of the game's sprite of the same name, now; the texture of the time before is thrown away. */
    private static int take(Fi folder){
        Texture old = watchedTexture;
        int count = make(folder, true);
        // also when the folder brought nothing (the texture stays then): sprites may have gone back to what they were
        if(old != null) recache();
        if(old != null && old != watchedTexture) old.dispose();
        return count;
    }

    /**
     * Some blocks the game does not draw anew every frame: walls, routers, junctions, sorters, gates and more
     * (Block.drawCached) are drawn once into a mesh for their part of the map, and that mesh is shown until a block in
     * it changes (BlockRenderer.cacheChunk). The mesh holds the texture and the place of each sprite as they were when
     * it was made - so after the sprites had been read again these blocks still showed the texture of before, which
     * had just been thrown away: plain black squares (the maker pressed "read pictures again" and a row of blocks
     * went black). Every part of the map that holds such a block is marked to be drawn anew.
     */
    private static void recache(){
        try{
            if(Vars.headless || Vars.renderer == null || Vars.renderer.blocks == null || Vars.world == null || Vars.world.tiles == null) return;
            for(Tile tile : Vars.world.tiles){
                Block block = tile.block();
                if(block != null && block.drawCached) Vars.renderer.blocks.recacheBuilding(block.buildingCacheLayer, tile);
            }
        }catch(Throwable t){
            Log.err("[mshaders] blocks the game draws from a mesh not drawn anew", t);
        }
    }

    /**
     * The "read pictures again" button: every sprite of the watched folder's sprites-override - or, with no folder
     * watched, of the mod's own - is read from its file again and put in place.
     * @return how many sprites, or -1 if that failed
     */
    public static int reloadNow(){
        try{
            Fi watch = Surfaces.watched();
            Fi folder = watch == null ? null : watch.child("sprites-override");
            if(folder == null || !folder.exists()){
                Mods.LoadedMod mod = Vars.mods.getMod(ShaderMod.class);
                folder = mod == null ? null : mod.root.child("sprites-override");
            }
            return folder == null || !folder.exists() ? 0 : take(folder);
        }catch(Throwable t){
            Log.err("[mshaders] sprites not read again", t);
            return -1;
        }
    }

    /**
     * @param every false: only the sprites that need an outline painted on (the others the game has put in place
     *              itself); true: every sprite of the folder, the others as they are
     */
    private static int make(Fi folder, boolean every){
        if(!folder.exists()) return 0;
        ObjectMap<String, Fi> files = new ObjectMap<>();
        folder.walk(file -> {
            if(file.extEquals("png")) files.put(file.nameWithoutExtension(), file);
        });

        Seq<Pixmap> made = new Seq<>();
        Seq<AtlasRegion> targets = new Seq<>();
        for(Block block : Vars.content.blocks()){
            // blocks of mods get their outline from the game itself when the mod is loaded
            if(!block.outlineIcon || block.minfo.mod != null) continue;
            // Turrets put together from parts (the duo and its two barrels): the game draws the outline of the body and of
            // each part as a sprite of its own, "<sprite>-outline", under the turret (DrawTurret.draw, RegionPart.draw) -
            // the sprite itself with its outline around it. Made anew from the mod's sprite, unless the mod brings that
            // outline sprite too.
            if(block instanceof Turret turret && turret.drawer instanceof DrawTurret drawer){
                Seq<TextureRegion> parts = Seq.with(block.region);
                partRegions(drawer.parts, parts);
                for(TextureRegion part : parts){
                    if(!(part instanceof AtlasRegion named) || !part.found() || named.name == null) continue;
                    Fi drawn = files.get(named.name);
                    if(drawn == null || files.containsKey(named.name + "-outline")) continue;
                    AtlasRegion under = Core.atlas.find(named.name + "-outline");
                    if(under == null || !under.found() || targets.contains(under, true)) continue;
                    Pixmap out = outline(drawn, block);
                    if(out == null) continue;
                    made.add(out);
                    targets.add(under);
                }
            }
            // the sprite the game outlines (Block.createIcons)
            TextureRegion[] icons = block.getGeneratedIcons();
            if(icons == null || icons.length == 0) continue;
            TextureRegion pick = icons[block.outlinedIcon >= 0 ? Math.min(block.outlinedIcon, icons.length - 1) : icons.length - 1];
            if(!(pick instanceof AtlasRegion region) || !region.found() || region.name == null || targets.contains(region, true)) continue;
            Fi file = files.get(region.name);
            if(file == null) continue;

            Pixmap out = outline(file, block);
            if(out == null) continue;
            made.add(out);
            targets.add(region);
        }
        for(UnitType type : Vars.content.units()){
            // units of mods get their outline from the game itself when the mod is loaded
            if(!type.outlines || type.minfo.mod != null) continue;
            // the sprites the game outlines in place (UnitType.createIcons): body, legs, feet, joints, treads ...
            Seq<TextureRegion> parts = Seq.with(type.region, type.jointRegion, type.footRegion, type.baseJointRegion, type.legRegion, type.treadRegion, type.legBaseRegion);
            // ... and the weapons that lie on top of the unit
            for(Weapon weapon : type.weapons){
                if(weapon.top && weapon.name != null && !weapon.name.isEmpty()) parts.add(weapon.region);
            }
            for(TextureRegion part : parts){
                if(!(part instanceof AtlasRegion region) || !part.found() || region.name == null || targets.contains(region, true)) continue;
                Fi file = files.get(region.name);
                if(file == null) continue;
                Pixmap out = outline(file, type.outlineColor, type.outlineRadius);
                if(out == null) continue;
                made.add(out);
                targets.add(region);
            }
        }
        // the patch in the team's colour: one copy per team that has a palette, its three marker tones swapped for the
        // team's (Block.createIcons); a copy the folder brings itself is left to the folder
        for(Block block : Vars.content.blocks()){
            if(block.minfo.mod != null || block.teamRegion == null || !block.teamRegion.found()) continue;
            Fi file = files.get(block.name + "-team");
            if(file == null) continue;
            Pixmap base = null;
            for(Team team : Team.all){
                if(!team.hasPalette || files.containsKey(block.name + "-team-" + team.name)) continue;
                AtlasRegion region = Core.atlas.find(block.name + "-team-" + team.name);
                if(region == null || !region.found() || targets.contains(region, true)) continue;
                if(base == null) base = new Pixmap(file);
                Pixmap out = new Pixmap(base.width, base.height);
                for(int x = 0; x < base.width; x++){
                    for(int y = 0; y < base.height; y++){
                        int c = base.get(x, y);
                        // the marker tones as the game knows them, and as they come out of its sprite sheet
                        int index = c == 0xffffffff ? 0 : c == 0xdcc6c6ff || c == 0xdbc5c5ff ? 1 : c == 0x9d7f7fff || c == 0x9e8080ff || c == 0x9d7e7eff ? 2 : -1;
                        out.set(x, y, index == -1 ? c : team.palettei[index]);
                    }
                }
                Pixmaps.bleed(out);
                made.add(out);
                targets.add(region);
            }
            if(base != null) base.dispose();
        }
        if(every){
            // the sprites that need no outline: as they are drawn
            for(ObjectMap.Entry<String, Fi> entry : files){
                AtlasRegion region = Core.atlas.find(entry.key);
                if(region == null || !region.found() || targets.contains(region, true)) continue;
                Pixmap drawn = new Pixmap(entry.value);
                Pixmaps.bleed(drawn);
                made.add(drawn);
                targets.add(region);
            }
        }
        if(made.isEmpty()){
            // the folder brings nothing any more: all it had brought goes back (its texture stays, nothing points at it)
            if(every) giveBack(new Seq<>());
            return 0;
        }
        int count = made.size;
        // The sprites of the mod itself go into the game's own sprite texture, where there is room (fit). Those of a
        // watched folder do not: they are read again and again while the game runs.
        if(!every){
            try{
                int fitted = fit(made, targets);
                Log.info("[mshaders] @ of @ outlined sprite(s) put into the game's own sprite texture", fitted, count);
            }catch(Throwable t){
                Log.err("[mshaders] outlined sprites stay in a texture of their own", t);
            }
            if(made.isEmpty()) return count;
        }

        // all of them side by side in one texture, with a gap between them
        int gap = 2, width = gap, height = 0;
        for(Pixmap map : made){
            width += map.width + gap;
            height = Math.max(height, map.height + gap * 2);
        }
        Pixmap all = new Pixmap(width, height);
        int[] at = new int[made.size];
        int x = gap;
        for(int i = 0; i < made.size; i++){
            Pixmap map = made.get(i);
            all.draw(map, x, gap);
            // The outermost pixels once more, just outside the sprite - as the game's own packing has them. Read between
            // pixels, the edge of a sprite otherwise fades into the empty gap: a wall that fills its tile (the watched
            // folder's walls) got an edge half see-through, and what lies under it showed as a thin dark line between
            // every two walls set side by side.
            for(int px = 0; px < map.width; px++){
                all.set(x + px, gap - 1, map.get(px, 0));
                all.set(x + px, gap + map.height, map.get(px, map.height - 1));
            }
            for(int py = -1; py <= map.height; py++){
                int row = Math.min(Math.max(py, 0), map.height - 1);
                all.set(x - 1, gap + py, map.get(0, row));
                all.set(x + map.width, gap + py, map.get(map.width - 1, row));
            }
            at[i] = x;
            x += map.width + gap;
        }
        Texture texture = new Texture(all);
        all.dispose();
        TextureFilter filter = Core.settings.getBool("linear", true) ? TextureFilter.linear : TextureFilter.nearest;
        texture.setFilter(filter, filter);
        // in the game's own list of sprite textures: the game then switches its filtering along with the others
        // (the Linear Filtering setting)
        Core.atlas.getTextures().add(texture);
        if(every){
            if(watchedTexture != null) Core.atlas.getTextures().remove(watchedTexture);
            watchedTexture = texture;
        }

        if(every){
            for(AtlasRegion region : targets){
                if(!before.containsKey(region)) before.put(region, new Was(region));
            }
            giveBack(targets);
        }
        for(int i = 0; i < made.size; i++){
            AtlasRegion region = targets.get(i);
            Pixmap map = made.get(i);
            region.set(texture, at[i], gap, map.width, map.height);
            region.offsetX = region.offsetY = 0f;
            region.packedWidth = region.originalWidth = map.width;
            region.packedHeight = region.originalHeight = map.height;
            region.pixmapRegion = null;
            map.dispose();
        }
        return count;
    }

    private static final int cell = 8, around = 2;
    private static FrameBuffer probe;
    private static java.nio.ByteBuffer probed;

    /**
     * Puts the sprites into the texture each of their targets is in already - the game's own sprite texture - wherever
     * that has a free patch, and points the targets there. Those that found a place are taken off both lists.
     *
     * Why: a sprite in another texture than the one before it ends the run of sprites the game hands to the graphics
     * card in one go. Kept in a texture of their own, the outlined sprites (heads and parts of turrets, bodies and
     * weapons of units) did that twice each time one was drawn among the game's sprites: measured on a test base of
     * 309 blocks and eight units, a frame went from 56 draw calls to 213 - with every effect of the mod switched off.
     * A desktop card does not mind; a phone does, and a large base is thousands of them. (The maker: "even with every
     * option off the frame rate is lower than without the mod - why?")
     *
     * A free patch: no sprite of the game's list lies there (each with a few pixels to spare), and what is there reads
     * back as empty. The texture of the interface is left alone (the letters live there, and are in no list).
     * @return how many were placed
     */
    private static int fit(Seq<Pixmap> made, Seq<AtlasRegion> targets){
        Texture ui = Core.atlas.find("whiteui").texture;
        boolean[] done = new boolean[made.size];
        int placed = 0;
        // First of all: right where the target is now. The outlined picture is larger than the sprite by the width of
        // the outline on every side - but where the drawing keeps that far from the edge of its file (as the game's
        // own sprites do), the added rim is empty, and without it the picture is exactly as large as the place its
        // target has in the texture. Nothing else is touched, and the sprite keeps its size and its place.
        for(int i = 0; i < made.size; i++){
            AtlasRegion region = targets.get(i);
            Texture page = region.texture;
            if(page == null || page == ui || page.getMinFilter().isMipMap()) continue;
            Pixmap map = made.get(i);
            int x = Math.round(Math.min(region.u, region.u2) * page.width), y = Math.round(Math.min(region.v, region.v2) * page.height);
            int w = Math.round(Math.abs(region.u2 - region.u) * page.width), h = Math.round(Math.abs(region.v2 - region.v) * page.height);
            int pad = (map.width - w) / 2;
            if(pad < 0 || map.width - w != pad * 2 || map.height - h != pad * 2 || w < 1 || h < 1) continue;
            Pixmap part = map;
            if(pad > 0){
                boolean rim = false;
                for(int py = 0; py < map.height && !rim; py++){
                    for(int px = 0; px < map.width; px++){
                        if(px >= pad && px < map.width - pad && py >= pad && py < map.height - pad){
                            // straight across the middle
                            px = map.width - pad - 1;
                            continue;
                        }
                        if((map.get(px, py) & 0xff) != 0){
                            rim = true;
                            break;
                        }
                    }
                }
                if(rim) continue;
                part = map.crop(pad, pad, w, h);
            }
            page.bind();
            Gl.pixelStorei(Gl.unpackAlignment, 1);
            part.pixels.position(0);
            Gl.texSubImage2D(Gl.texture2d, 0, x, y, w, h, Gl.rgba, Gl.unsignedByte, part.pixels);
            if(part != map) part.dispose();
            region.pixmapRegion = null;
            done[i] = true;
            placed++;
        }
        int inPlace = placed;
        Seq<Texture> pages = new Seq<>();
        for(AtlasRegion target : targets){
            if(target.texture != null && !pages.contains(target.texture, true)) pages.add(target.texture);
        }
        for(Texture page : pages){
            if(page == ui || page.getMinFilter().isMipMap()) continue;
            int tw = page.width, th = page.height, gw = tw / cell, gh = th / cell;
            if(gw < 4 || gh < 4) continue;
            boolean[] used = new boolean[gw * gh];
            for(AtlasRegion r : Core.atlas.getRegions()){
                if(r.texture != page) continue;
                mark(used, gw, gh, Math.round(Math.min(r.u, r.u2) * tw) - 4, Math.round(Math.min(r.v, r.v2) * th) - 4,
                    Math.round(Math.max(r.u, r.u2) * tw) + 4, Math.round(Math.max(r.v, r.v2) * th) + 4);
            }
            // the tallest first
            Seq<Integer> order = new Seq<>();
            for(int i = 0; i < made.size; i++){
                if(!done[i] && targets.get(i).texture == page) order.add(i);
            }
            if(order.isEmpty()) continue;
            order.sort((a, b) -> made.get(b).height - made.get(a).height);
            int[] sums = new int[(gw + 1) * (gh + 1)];
            for(int index : order){
                Pixmap map = made.get(index);
                int w = map.width + around * 2, h = map.height + around * 2, cw = (w + cell - 1) / cell, ch = (h + cell - 1) / cell;
                if(cw > gw || ch > gh || w > 512 || h > 512) continue;
                // a few tries: a patch that does not read back as empty is struck off, and the next one is looked for
                for(int tries = 0; tries < 6; tries++){
                    // how many cells are taken above and to the left of each: tells whether a block of cells is free in one step
                    for(int y = 0; y < gh; y++){
                        int row = 0;
                        for(int x = 0; x < gw; x++){
                            if(used[y * gw + x]) row++;
                            sums[(y + 1) * (gw + 1) + x + 1] = sums[y * (gw + 1) + x + 1] + row;
                        }
                    }
                    int at = -1;
                    search:
                    for(int y = 0; y + ch <= gh; y++){
                        for(int x = 0; x + cw <= gw; x++){
                            if(sums[(y + ch) * (gw + 1) + x + cw] - sums[y * (gw + 1) + x + cw] - sums[(y + ch) * (gw + 1) + x] + sums[y * (gw + 1) + x] == 0){
                                at = y * gw + x;
                                break search;
                            }
                        }
                    }
                    if(at < 0) break;
                    int px = at % gw * cell, py = at / gw * cell;
                    mark(used, gw, gh, px, py, px + cw * cell, py + ch * cell);
                    if(!empty(page, px, py, cw * cell, ch * cell)) continue;
                    page.bind();
                    Gl.pixelStorei(Gl.unpackAlignment, 1);
                    map.pixels.position(0);
                    Gl.texSubImage2D(Gl.texture2d, 0, px + around, py + around, map.width, map.height, Gl.rgba, Gl.unsignedByte, map.pixels);
                    AtlasRegion region = targets.get(index);
                    region.set(page, px + around, py + around, map.width, map.height);
                    region.offsetX = region.offsetY = 0f;
                    region.packedWidth = region.originalWidth = map.width;
                    region.packedHeight = region.originalHeight = map.height;
                    region.pixmapRegion = null;
                    done[index] = true;
                    placed++;
                    break;
                }
            }
        }
        Log.info("[mshaders] outlined sprites: @ written where their sprite was, @ into a free patch of the same texture", inPlace, placed - inPlace);
        for(int i = made.size - 1; i >= 0; i--){
            if(!done[i]) continue;
            made.get(i).dispose();
            made.remove(i);
            targets.remove(i);
        }
        return placed;
    }

    private static void mark(boolean[] used, int gw, int gh, int x0, int y0, int x1, int y1){
        int cx0 = Math.max(Math.floorDiv(x0, cell), 0), cy0 = Math.max(Math.floorDiv(y0, cell), 0);
        int cx1 = Math.min(Math.floorDiv(Math.max(x1 - 1, x0), cell), gw - 1), cy1 = Math.min(Math.floorDiv(Math.max(y1 - 1, y0), cell), gh - 1);
        for(int y = cy0; y <= cy1; y++){
            for(int x = cx0; x <= cx1; x++) used[y * gw + x] = true;
        }
    }

    /** Is that patch of the texture empty - nothing in it that shows? Drawn into a small buffer and read back from there. */
    private static boolean empty(Texture page, int x, int y, int w, int h){
        if(probe == null){
            probe = new FrameBuffer(512, 512);
            probed = arc.util.Buffers.newByteBuffer(512 * 512 * 4);
        }
        Draw.flush();
        arc.math.Mat before = new arc.math.Mat(Draw.proj());
        probe.begin(Color.clear);
        Draw.proj(new arc.math.Mat().setOrtho(0f, 0f, 512f, 512f));
        Draw.reset();
        Draw.rect(new TextureRegion(page, x, y, w, h), w / 2f, h / 2f, w, h);
        Draw.flush();
        probed.clear();
        Gl.pixelStorei(Gl.packAlignment, 1);
        Gl.readPixels(0, 0, w, h, Gl.rgba, Gl.unsignedByte, probed);
        probe.end();
        Draw.proj(before);
        for(int i = 0, n = w * h * 4; i < n; i++){
            if(probed.get(i) != 0) return false;
        }
        return true;
    }

    /** That sprite with the outline of that block around it, in a picture larger by the width of the outline on every side; null if it has an outline already. */
    private static Pixmap outline(Fi file, Block block){
        return outline(file, block.outlineColor, block.outlineRadius);
    }

    private static Pixmap outline(Fi file, Color colour, int radius){
        Pixmap drawn = new Pixmap(file);
        if(outlined(drawn, colour.rgba())){
            drawn.dispose();
            return null;
        }
        int pad = Math.max(radius, 0);
        Pixmap room = new Pixmap(drawn.width + pad * 2, drawn.height + pad * 2);
        room.draw(drawn, pad, pad);
        drawn.dispose();
        Pixmap out = Pixmaps.outline(new PixmapRegion(room), colour, radius);
        room.dispose();
        // what the game does to its sprites when linear filtering is on (Drawf.checkBleed): colour under the transparent
        // pixels next to the sprite, so its edge does not get a pale fringe
        Pixmaps.bleed(out);
        return out;
    }

    /** The sprites of those parts that the game draws an outline under, and of the parts within them. */
    private static void partRegions(Seq<DrawPart> parts, Seq<TextureRegion> out){
        for(DrawPart drawn : parts){
            if(!(drawn instanceof RegionPart part)) continue;
            if(part.outline && part.drawRegion) out.addAll(part.regions);
            partRegions(part.children, out);
        }
    }

    /** Does that sprite have an outline already? Yes if most of its rim - the opaque pixels next to a transparent one or to the edge - is of the outline colour. */
    private static boolean outlined(Pixmap sprite, int outline){
        int rim = 0, dark = 0;
        for(int x = 0; x < sprite.width; x++){
            for(int y = 0; y < sprite.height; y++){
                int c = sprite.get(x, y);
                if((c & 0xff) < 128) continue;
                boolean edge = clear(sprite, x - 1, y) || clear(sprite, x + 1, y) || clear(sprite, x, y - 1) || clear(sprite, x, y + 1);
                if(!edge) continue;
                rim++;
                if((c | 0xff) == (outline | 0xff)) dark++;
            }
        }
        return rim > 0 && dark * 2 > rim;
    }

    private static boolean clear(Pixmap sprite, int x, int y){
        return x < 0 || y < 0 || x >= sprite.width || y >= sprite.height || (sprite.get(x, y) & 0xff) < 128;
    }
}
