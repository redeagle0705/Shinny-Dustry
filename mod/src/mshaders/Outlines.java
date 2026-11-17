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
        Fi folder = mod.root.child("sprites-override");
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
