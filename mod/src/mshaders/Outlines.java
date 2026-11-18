package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.TextureAtlas.*;
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
     * Drawing with the game open (Surfaces.watched): to be called every frame. Twice a second it looks whether a
     * sprite in the watched folder's sprites-override has been saved, added or removed; if so, every sprite of that
     * folder is put in place of the game's sprite of the same name, with its outline where the game gives one - as at
     * the start of the game, but from that folder and at once. (At the start they are taken once in any case:
     * takeWatched.)
     * (A sprite that was removed from the folder stays as it is until the game is started again.)
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
        if(old != null && old != watchedTexture) old.dispose();
        return count;
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
        if(made.isEmpty()) return 0;

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
            all.draw(made.get(i), x, gap);
            at[i] = x;
            x += made.get(i).width + gap;
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
        return made.size;
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
