package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.Rect;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.part.*;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.UnitType;
import mindustry.type.Weapon;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.units.*;
import mindustry.world.draw.*;

/**
 * Which way each pixel of a block's surface points, so the block can be lit by calculation instead of by the shading
 * painted into its sprite. A picture per sprite says so, in one of two ways:
 *
 *   - a NORMAL MAP: the colour is the direction (red = left 0 .. right 255, green = down 0 .. up 255, blue = towards
 *     the viewer; flat is 128,128,255; transparent where the sprite is);
 *   - a HEIGHT MAP: the brightness is how high the pixel stands (black = low, white = high; transparent where the
 *     sprite is). Easier to draw - no angles to work out. The lean of each pixel is worked out while drawing, from
 *     the heights around it on the screen (mshaders-shape.frag): a pixel faces away from its higher neighbours.
 *
 * The game draws only colour. To know, for every pixel of the screen, which way the surface there points, the maps of
 * the buildings in view are drawn a second time - only those buildings, a few sprites each - into a picture of their
 * own ({@link #draw}), sprite for sprite the way the game draws the block:
 *   - any block: its base;
 *   - a drill: then its rotator at the angle it has right now, then its top;
 *   - a conveyor: the one sprite of the shape it has (straight, bend, joins ...) and of the moment of its animation,
 *     turned the way the conveyor points and mirrored where the game mirrors it;
 *   - a block with a head that turns - a mass driver, a turret, a repair point, a build tower: its base (the sprite
 *     the game uses for it: <name>-base, or the shared block-<size> for turrets). Its head (the sprite <name>) the game
 *     draws later than the buildings, on the turret layer - so it is not part of this picture: it is drawn into a
 *     picture of its own once the game has drawn the heads ({@link #drawHeads}), turned and pushed back the way the
 *     game draws it. (The heads of tractor beams and point defence turrets the game draws with the buildings: those
 *     are drawn here, right over their base.)
 * A sprite that is turned or mirrored has its directions turned and mirrored with it (both travel in the sprite's
 * colour, mshaders-normals.frag). The shading pass then reads that picture.
 *
 * Files, by sprite name (the name the game's own sprite has):
 *   normal maps:  mshaders-normals/<sprite>.png  in the game's data folder, then  normals/<sprite>.png  in the mod;
 *   height maps:  mshaders-heightmaps/<sprite>.png  in the data folder, then  heightmaps/<sprite>.png  in the mod.
 *   gloss maps:   mshaders-gloss/<sprite>.png  in the data folder, then  gloss/<sprite>.png  in the mod - a picture
 *                 that says how glossy each pixel of that sprite is: black = matt, mid grey = half polished,
 *                 white = polished metal. Where it is transparent, and for a sprite without one, the
 *                 pixel is as glossy as the table of gloss by colour says for the colour the sprite has there
 *                 (GlossColors.java, mshaders/gloss-colors.properties) - so a map is needed only where one colour
 *                 shines in two ways - and where the table says nothing, as glossy as the block is as a whole
 *                 (Gloss.java: what it is built from). It goes along
 *                 with the sprite's normal or height map and does nothing without one.
 * (The data folder comes first so a picture can be tried without rebuilding the mod.)
 * FOLDERS INSIDE THOSE FOLDERS: a picture is found by its name alone, however deep it lies - so the pictures can be
 * sorted the way the game sorts its sprites and the way sprites-override/ has to be
 * (gloss/blocks/distribution/conveyors/conveyor-0-0.png, normals/units/weapons/missiles-mount.png), or lie loose as
 * before. Where one name is there twice in a folder, the picture saved last counts ({@link #shelf}).
 *
 * DRAWING WITH THE GAME OPEN: those folders are looked at twice a second, and when a picture in them has been saved,
 * added or removed, all maps are read again - what is on screen changes within a second or two, without a restart.
 * One more folder can be watched: a folder laid out like the mod's own assets (normals/, heightmaps/, gloss/,
 * sprites-override/), named in a one-line file "mshaders-watch.txt" in the data folder or in the mod's folder
 * ({@link #watched}). Its pictures come before the mod's own - unless the mod's own copy was saved later: of those
 * two, the one saved last counts. (The data folder still comes before both.) The maker's deploy script puts that file there, pointing at the folder the
 * pictures are drawn in; the jar that is published has none.
 * Sprite names: the block's name for its base; for a drill also <name>-rotator and <name>-top; for a mass driver
 * <name>-base for the base and <name> for the head; for a turret (also repair point, build tower, tractor beam, point
 * defence) the name of the base sprite the game uses - block-<size> for nearly all of them, one picture serving every
 * turret of that size - and <name> for the head; for a conveyor
 * <name>-<shape 0..6>-<frame 0..3>, or <name>-<shape> for a picture that serves all four frames. A frame without a
 * picture of its own uses the nearest frame of that shape that has one, so the shading does not blink while the belt runs.
 * A door also has <name>-open, used while it stands open; a thruster <name>-top, which turns with it; a unit factory
 * or reconstructor its frame (<name>-top, or factory-top-<size> shared by every factory of that size) and arrows
 * (factory-out-<size>, factory-in-<size>, unless the block has its own); and a block the
 * game draws as one of several sprites (the scrap walls) has one picture per sprite: <name>1, <name>2, ... - the game
 * picks one by where the building stands, and so is the map picked.
 * Where a sprite has both kinds of map, the setting "prefer height maps" picks.
 *
 * UNITS have maps the same way, in the same folders, by the names of their sprites:
 *   <unit>            its body (the sprite points up; the game turns it to where the unit looks);
 *   <weapon>          each of its weapons, by the weapon's own sprite name - "large-weapon" serves every unit that
 *                     carries that weapon. The game draws one sprite for both sides, flipped on one of them;
 *   <unit>-leg, <unit>-base   for a unit that walks on two legs: the leg (one sprite, flipped for the other side) and
 *                     the hip the body sits on;
 *   <unit>-treads     for a tank: its treads.
 * The cell in the team's colour needs none: it lies on the body and takes the body's shading. A weapon without a map
 * of its own does NOT take the body's shading: it is a thing of its own lying on the body, so its shape is cut out of
 * the body's map and it keeps the look the game gives it. Units move about on
 * several layers, later than the buildings, so their maps go into a picture of their own, once per layer the game
 * draws such units on ({@link #collectUnits}, {@link #drawUnits}). Not done yet: the legs of the many-legged units,
 * units made of segments, and a unit carried as a payload.
 */
public class Surfaces{
    /** The layers of a block other than a conveyor, in drawing order; a missing layer is null. */
    private static final int base = 0, rotator = 1, top = 2;
    private static final String[] suffixes = {"", "-rotator", "-top"};
    /** The second layer of a block with a head that turns (mass driver, turrets): that head. */
    private static final int head = 1;
    /** The second layer of a door: the sprite it has while it stands open. Of a thruster: its top, which turns with it. */
    private static final int ajar = 1, lid = 1;
    /** Conveyor sprites: Conveyor.regions, "@-#1-#2" with lengths {7, 4}. */
    private static final int beltShapes = 7, beltFrames = 4;

    /** The two kinds of picture, and the folders they are looked for in. */
    private static final int byNormal = 0, byHeight = 1;
    private static final String[] folders = {"normals", "heightmaps"};
    /** The folder of the pictures that say how glossy each pixel is; found by the same sprite names. */
    private static final String glossFolder = "gloss";
    /** The folder of the sprites drawn for this mod: gloss by colour goes by their colours (GlossColors.java). */
    private static final String spriteFolder = "sprites-override";
    /** True while the gloss maps are left out and the table of gloss by colour alone counts (a developer option, to compare). */
    private boolean mapsOff;
    private int byColour;
    /**
     * How the gloss travels: in the blue of every pixel, beside what the pixel is. A direction needs only its red and
     * green (the part pointing up follows from them), a height only its red - so blue is free, and is split in two:
     *   blue 0 .. 0.3    a height;     gloss = blue / 0.3
     *   blue 0.6 .. 1    a direction;  gloss = (blue - 0.6) / 0.4
     * The gap in between is what tells the two kinds apart (mshaders-normals.frag, mshaders-shape.frag).
     * That is the picture of the frame. In the pictures kept here one more thing has to be said: "this pixel has no
     * gloss of its own, it takes the block's". That is the very top of each range; a pixel's own gloss fills the
     * range up to "ownShare" of it (mshaders-normals.frag puts the block's gloss in where it finds the top).
     */
    private static final float heightBlue = 0.3f, normalBlue = 0.6f, ownShare = 0.88f;
    /** How glossy the block being drawn is (Gloss.of): it rides along in the alpha of each sprite's colour. */
    private float shine = Gloss.plain;

    /** Per block: [byNormal] and [byHeight], each with one region per sprite of the block (null without a picture). */
    private final ObjectMap<Block, TextureRegion[][]> layers = new ObjectMap<>();
    /** Conveyors drawn in this frame that carry items (filled by draw, used by drawCovers). */
    private final Seq<Conveyor.ConveyorBuild> carrying = new Seq<>();
    /** Buildings that one of those items reaches under (filled by drawCovers, used by eraseOver and redrawOver). */
    private final Seq<Building> over = new Seq<>();
    /** Buildings drawn in this frame whose heads are still to come (filled by draw, used by drawHeads). */
    private final Seq<Building> heads = new Seq<>();
    /** True while draw() goes through the buildings in view: only then are heads noted for later. */
    private boolean collecting;
    /** Where the head of the building last asked about is (headOf). */
    private float headX, headY, headDegrees;
    /** For the moving parts of turrets (the two barrels of a duo): the place of each part sprite among the pictures of its block. */
    private static final ObjectIntMap<TextureRegion> partSlots = new ObjectIntMap<>();
    /** The values a part works its place out from, one set per depth of parts within parts. */
    private final DrawPart.PartParams[] partLevels = {new DrawPart.PartParams(), new DrawPart.PartParams(), new DrawPart.PartParams(), new DrawPart.PartParams()};
    /** The shading of the heads is laid on this much above the turret layer (Scene.queue); parts the game draws later than that are left out. */
    public static final float headPassOffset = 0.05f;
    private Texture atlas;
    private Boolean filtered;

    /** The pictures of one kind of unit: for each of its sprites [byNormal] and [byHeight], or null without any. */
    private static class UnitLook{
        TextureRegion[] body, leg, hip, treads;
        /** One per weapon of the unit type, in the order of the unit's mounts. */
        TextureRegion[][] weapons;
        /** How glossy the unit is as a whole (Gloss.of). */
        float gloss;
    }
    /** The kinds of unit that have a picture for at least one of their sprites. */
    private final ObjectMap<UnitType, UnitLook> looks = new ObjectMap<>();
    /** The units in view that have pictures, lowest first (collectUnits), and the layers the game draws them on. */
    private final Seq<Unit> units = new Seq<>();
    private final FloatSeq unitLayers = new FloatSeq();
    /**
     * How far above the layer of its units their shading is laid on: past the 0.01 by which larger units lie above
     * smaller ones and past the weapons that are drawn a little above their unit (Weapon.layerOffset up to 0.02).
     */
    public static final float unitPassOffset = 0.035f;

    /** The pictures read so far while loading: each file is read once, however many sprites it serves. */
    private final Seq<Pixmap> maps = new Seq<>();
    private final ObjectIntMap<String> known = new ObjectIntMap<>();
    private final int[] found = new int[2];
    private int glossMaps;

    public void init(){
        try{
            load();
        }catch(Throwable t){
            layers.clear();
            looks.clear();
            Log.err("[mshaders] normal and height maps unreadable, blocks keep their painted shading", t);
        }
        try{
            read = last = holding();
        }catch(Throwable ignored){
        }
    }

    /**
     * The same filtering the game gives its own sprites (Graphics > Linear Filtering): with it a turned sprite has smooth
     * edges. Read pixel by pixel instead, the shading of a turned rotator came out in jagged steps over smooth colour.
     */
    private void applyFilter(){
        boolean linear = Core.settings.getBool("linear", true);
        if(atlas == null || (filtered != null && filtered == linear)) return;
        filtered = linear;
        TextureFilter filter = linear ? TextureFilter.linear : TextureFilter.nearest;
        atlas.setFilter(filter, filter);
    }

    /** How many blocks and kinds of unit have a map of either kind. */
    public int count(){
        return layers.size + looks.size;
    }

    private static Fi find(int kind, String name){
        return find(folders[kind], name);
    }

    private static Fi find(String folder, String name){
        // the player's own picture in the data folder comes before everything
        Fi file = shelf("data:" + folder, Vars.dataDirectory.child("mshaders-" + folder)).get(name);
        if(file != null) return file;
        mindustry.mod.Mods.LoadedMod mod = Vars.mods.getMod(ShaderMod.class);
        file = shelf("own:" + folder, mod == null || mod.root == null ? null : mod.root.child(folder)).get(name);
        // as before there were folders inside the folders: whatever the game's file tree has under that plain name
        if(file == null) file = Vars.tree.get(folder + "/" + name + ".png");
        Fi watch = watched();
        if(watch != null){
            // The watched folder against the mod's own copy of the same picture: the one saved last counts. (The
            // watched folder always came first at the start - and a gloss map the maker had saved into the game's
            // mod folder was passed over for an older one in the watched folder: "I painted it black and it still
            // shines.") A deploy copies the watched folder's pictures with their dates, so untouched copies tie, and
            // the watched folder wins a tie.
            Fi drawn = shelf("watch:" + folder, watch.child(folder)).get(name);
            if(drawn != null && !(file.exists() && file.lastModified() > drawn.lastModified() + 3000L)) return drawn;
        }
        return file.exists() ? file : null;
    }

    /** The pictures of each folder that has been looked through since the maps were last read, by sprite name. */
    private static final ObjectMap<String, ObjectMap<String, Fi>> shelves = new ObjectMap<>();

    /**
     * Every picture in a folder and in the folders inside it, by its name without ".png". The maker sorts the
     * pictures into folders the way sprites-override/ is sorted (blocks/distribution/conveyors/...), and a sprite's
     * name is one of a kind in the game, so the name alone says which picture is meant. A name that is there twice -
     * a picture saved loose into the folder out of habit, beside the sorted one - goes to the one saved last, and the
     * log says so.
     * @param key which folder (kept until the maps are read again), dir may be null or missing
     */
    private static ObjectMap<String, Fi> shelf(String key, Fi dir){
        ObjectMap<String, Fi> shelf = shelves.get(key);
        if(shelf != null) return shelf;
        ObjectMap<String, Fi> made = new ObjectMap<>();
        shelves.put(key, made);
        try{
            if(dir != null && dir.exists() && dir.isDirectory()){
                dir.walk(file -> {
                    if(!file.extEquals("png")) return;
                    String name = file.nameWithoutExtension();
                    Fi other = made.get(name);
                    if(other == null){
                        made.put(name, file);
                        return;
                    }
                    boolean later = file.lastModified() > other.lastModified();
                    Log.warn("[mshaders] @ is there twice: @ and @ - the one saved last is used (@)", name, other.path(), file.path(), (later ? file : other).path());
                    if(later) made.put(name, file);
                });
            }
        }catch(Throwable t){
            Log.err("[mshaders] the pictures in " + key + " could not be listed", t);
        }
        return made;
    }

    private static Fi watchDir;
    private static boolean watchLooked;

    /**
     * The folder pictures are drawn in while the game is open, or null: the folder named in the first line of
     * "mshaders-watch.txt" in the game's data folder, else of the file of that name in this mod's own folder.
     */
    public static Fi watched(){
        if(watchLooked) return watchDir;
        watchLooked = true;
        try{
            Fi note = Vars.dataDirectory.child("mshaders-watch.txt");
            if(!note.exists()){
                mindustry.mod.Mods.LoadedMod mod = Vars.mods.getMod(ShaderMod.class);
                note = mod == null ? null : mod.root.child("mshaders-watch.txt");
            }
            if(note != null && note.exists()){
                String path = note.readString().replace("\uFEFF", "").split("\n")[0].trim();
                Fi dir = path.isEmpty() ? null : new Fi(path);
                if(dir != null && dir.exists() && dir.isDirectory()){
                    watchDir = dir;
                    Log.info("[mshaders] pictures are also read from @, and read again when they change", dir.absolutePath());
                }
            }
        }catch(Throwable t){
            Log.err("[mshaders] the folder to watch could not be found", t);
        }
        return watchDir;
    }

    /** When the folders are next looked at (milliseconds), what they held when the maps were last read, and what they held at the last look. */
    private long nextLook;
    private long read = Long.MIN_VALUE, last = Long.MIN_VALUE;

    /** What the folders of maps hold right now, as one number: the names, sizes and dates of the pictures in them. */
    private static long holding(){
        long sum = 17;
        Fi watch = watched();
        // the mod's own folder too, where it is a folder and not a jar: a picture saved there counts as well (find)
        mindustry.mod.Mods.LoadedMod mod = watch == null ? null : Vars.mods.getMod(ShaderMod.class);
        Fi own = mod == null || mod.root == null || !mod.file.isDirectory() ? null : mod.root;
        for(String folder : new String[]{folders[byNormal], folders[byHeight], glossFolder}){
            for(int place = 0; place < 3; place++){
                Fi dir = place == 0 ? (watch == null ? null : watch.child(folder)) : place == 1 ? Vars.dataDirectory.child("mshaders-" + folder) : (own == null ? null : own.child(folder));
                if(dir == null || !dir.exists() || !dir.isDirectory()) continue;
                // the folders inside too; a picture moved from one of them to another counts as a change (path)
                long[] part = {0};
                dir.walk(file -> {
                    if(!file.extEquals("png")) return;
                    part[0] = part[0] * 31 + file.path().hashCode();
                    part[0] = part[0] * 31 + file.lastModified();
                    part[0] = part[0] * 31 + file.length();
                });
                sum = sum * 31 + part[0];
            }
        }
        // the table of gloss by colour: saved, it is read again like a picture
        for(Fi file : GlossColors.files()){
            if(file == null) continue;
            sum = sum * 31 + file.lastModified();
            sum = sum * 31 + file.length();
        }
        return sum;
    }

    /**
     * To be called every frame: twice a second it looks whether a map has been saved, added or removed, and if so
     * reads all maps again. A picture that is being written right now differs from look to look, so the folders must
     * hold the same at two looks in a row before anything is read.
     * @return true if the maps were just read again
     */
    public boolean watch(){
        long now = Time.millis();
        if(now < nextLook) return false;
        nextLook = now + 500;
        long holds;
        try{
            holds = holding();
        }catch(Throwable t){
            return false;
        }
        if(holds == read || holds != last){
            last = holds;
            return false;
        }
        read = holds;
        reload();
        return true;
    }

    /** To be called every frame: reads the maps again when "gloss by colour only" has been switched. */
    public void sync(){
        if(Core.settings.getBool(ShaderMod.devGlossTable, false) != mapsOff) reload();
    }

    /** Reads all maps again now, whatever the folders held before (the "read pictures again" button). */
    public void reloadNow(){
        try{
            read = last = holding();
        }catch(Throwable ignored){
        }
        reload();
    }

    /** How many normal maps, height maps and gloss maps were read last. */
    public int[] counts(){
        return new int[]{found[byNormal], found[byHeight], glossMaps};
    }

    private void reload(){
        Texture old = atlas;
        layers.clear();
        looks.clear();
        atlas = null;
        filtered = null;
        try{
            load();
        }catch(Throwable t){
            // a picture that cannot be read (half saved, not a picture): nothing is shaded until the next change
            for(Pixmap map : maps) map.dispose();
            maps.clear();
            layers.clear();
            looks.clear();
            Log.err("[mshaders] maps could not be read again", t);
        }
        if(old != null) old.dispose();
    }

    /**
     * Puts a map into the form it is kept in: red and green = the direction (made exactly one long first), or red =
     * the height; blue = the gloss of the pixel, in the range of its kind (heightBlue, normalBlue), or the top of that
     * range for "as glossy as the block". "gloss" is the gloss map of the sprite, or null.
     */
    private static boolean encode(Pixmap map, int kind, Pixmap gloss, Pixmap colours, Pixmap team, String sprite, String owner){
        if(gloss != null && (gloss.width != map.width || gloss.height != map.height)){
            Log.warn("[mshaders] a gloss map of @x@ does not fit its @x@ sprite and is left out", gloss.width, gloss.height, map.width, map.height);
            gloss = null;
        }
        // the sprite itself, for gloss by colour: only where it lies pixel on pixel under the map
        if(colours != null && (colours.width != map.width || colours.height != map.height)) colours = null;
        if(team != null && (team.width != map.width || team.height != map.height)) team = null;
        boolean told = false;
        for(int x = 0; x < map.width; x++){
            for(int y = 0; y < map.height; y++){
                int c = map.get(x, y);
                if((c & 0xff) < 128) continue;
                int g = gloss == null ? 0 : gloss.get(x, y);
                // the pixel's own gloss in the lower part of the range; the top of the range = the block's
                float glossy;
                if((g & 0xff) >= 128){
                    glossy = level(g) * ownShare;
                }else{
                    // no gloss painted here: what the table says for the colour the sprite has here (GlossColors.java)
                    int paint = colours == null ? 0 : colours.get(x, y);
                    // the piece in the team's colour lies over the sprite: there the table is asked for "team" first
                    int by = team != null && (team.get(x, y) & 0xff) >= 128 ? GlossColors.of(sprite, owner, GlossColors.team) : -1;
                    if(by < 0 && colours != null) by = GlossColors.of(sprite, owner, (paint & 0xff) < 128 ? GlossColors.clear : paint >>> 8);
                    glossy = by < 0 ? 1f : by / 255f * ownShare;
                    told |= by >= 0;
                }
                if(kind == byHeight){
                    map.set(x, y, Color.rgba8888((c >>> 24) / 255f, 0f, heightBlue * glossy, 1f));
                }else{
                    float nx = (c >>> 24) / 255f * 2f - 1f, ny = (c >>> 16 & 0xff) / 255f * 2f - 1f, nz = (c >>> 8 & 0xff) / 255f * 2f - 1f;
                    float len = Math.max((float)Math.sqrt(nx * nx + ny * ny + nz * nz), 0.001f);
                    map.set(x, y, Color.rgba8888(nx / len * 0.5f + 0.5f, ny / len * 0.5f + 0.5f, normalBlue + (1f - normalBlue) * glossy, 1f));
                }
            }
        }
        return told;
    }

    /** The sprite names of a block, one per slot of its region arrays. */
    private static String[] sprites(Block block){
        if(block instanceof Conveyor){
            String[] names = new String[beltShapes * beltFrames];
            for(int i = 0; i < names.length; i++) names[i] = block.name + "-" + i / beltFrames + "-" + i % beltFrames;
            return names;
        }
        // rotator and top are parts of a drill; other blocks have only a base so far
        if(block instanceof Drill) return new String[]{block.name, block.name + suffixes[rotator], block.name + suffixes[top]};
        // a base and a head that turns: the sprite with the block's own name is the head
        if(headed(block)){
            Seq<String> names = Seq.with(baseName(block), block.name);
            // a turret may be put together from parts that move on their own (DrawTurret.parts): one picture per part sprite
            if(block instanceof Turret turret && turret.drawer instanceof DrawTurret drawer) partNames(drawer.parts, names);
            return names.toArray(String.class);
        }
        // a door has a second sprite, drawn while it stands open (Door.openRegion, AutoDoor.openRegion)
        if(block instanceof Door || block instanceof AutoDoor) return new String[]{block.name, block.name + "-open"};
        // the top of a thruster is a sprite of its own, turned with the building (Thruster.topRegion)
        if(block instanceof Thruster) return new String[]{block.name, block.name + "-top"};
        // a unit factory or reconstructor: its floor, and the frame drawn over the unit being made (UnitFactory.topRegion,
        // Reconstructor.topRegion) - two pictures, "<name>" and "<name>-top"
        if(block instanceof mindustry.world.blocks.units.UnitFactory || block instanceof mindustry.world.blocks.units.Reconstructor){
            // as PayloadBlock.load() finds them: a picture of the block's own if there is one, else the one every factory
            // of that size shares ("factory-top-3", ...); the arrows are turned with the building
            boolean in = block instanceof mindustry.world.blocks.units.Reconstructor;
            return new String[]{block.name, shared(block, "-out", "factory-out-"), in ? shared(block, "-in", "factory-in-") : null, shared(block, "-top", "factory-top-")};
        }
        // a building drawn as one of several sprites, picked by where it stands (Block.variants: the scrap walls):
        // "<name>1", "<name>2", ... as Block.load() names them - there is no sprite with the block's own name
        if(block.variants > 0 && block.hasBuilding()){
            String[] names = new String[block.variants];
            for(int i = 0; i < names.length; i++) names[i] = block.name + (i + 1);
            return names;
        }
        return new String[]{block.name};
    }

    /** The name of a sprite of that block: "<block><suffix>" if the game has one, else the one shared by blocks of its size. */
    private static String shared(Block block, String suffix, String common){
        return Core.atlas.has(block.name + suffix) ? block.name + suffix : common + block.size;
    }
    /** Adds the names of the sprites of those parts (and of the parts within them) and notes the place of each in partSlots. */
    private static void partNames(Seq<DrawPart> parts, Seq<String> names){
        for(DrawPart drawn : parts){
            if(!(drawn instanceof RegionPart part)) continue;
            if(part.drawRegion){
                for(TextureRegion region : part.regions){
                    if(!(region instanceof TextureAtlas.AtlasRegion named) || !region.found() || named.name == null) continue;
                    int slot = names.indexOf(named.name, false);
                    if(slot < head){
                        slot = names.size;
                        names.add(named.name);
                    }
                    partSlots.put(region, slot);
                }
            }
            partNames(part.children, names);
        }
    }

    /** Does the game draw that block as a base with a head that turns on it? */
    private static boolean headed(Block block){
        return block instanceof MassDriver || (block instanceof Turret turret && turret.drawer instanceof DrawTurret)
            || block instanceof TractorBeamTurret || block instanceof PointDefenseTurret || block instanceof BuildTurret || block instanceof RepairTurret;
    }

    /**
     * Does the game draw the head of that block after the buildings, on the turret layer? Not the tractor beam and the
     * point defence turret: their draw() never leaves the building layer.
     */
    private static boolean lateHead(Block block){
        return !(block instanceof TractorBeamTurret || block instanceof PointDefenseTurret);
    }

    /**
     * The name of the sprite the game draws as the base of such a block: "<name>-base" where the block has one of its
     * own (mass driver), else the plate shared by all blocks of that size, "block-<size>" (DrawTurret.load, and the
     * fallback of the baseRegion fields) - taken from the region the game has loaded, so it is whatever the game uses.
     */
    private static String baseName(Block block){
        TextureRegion region = block instanceof MassDriver b ? b.baseRegion
            : block instanceof Turret b ? ((DrawTurret)b.drawer).base
            : block instanceof TractorBeamTurret b ? b.baseRegion
            : block instanceof PointDefenseTurret b ? b.baseRegion
            : block instanceof BuildTurret b ? b.baseRegion
            : block instanceof RepairTurret b ? b.baseRegion : null;
        return region instanceof TextureAtlas.AtlasRegion named && region.found() && named.name != null ? named.name : block.name + "-base";
    }

    /**
     * Where the game draws the head of that building right now, into headX, headY and headDegrees. The sprite of a head
     * points up, so it is turned by the direction it aims in - 90.
     * @return false for a building without a head
     */
    private boolean headOf(Building build){
        float aim;
        headX = build.x;
        headY = build.y;
        if(build instanceof MassDriver.MassDriverBuild driver){
            // MassDriver.MassDriverBuild.draw(): pushed back by its recoil right after a shot
            float back = driver.reloadCounter * ((MassDriver)driver.block).knockback;
            headX += Angles.trnsx(driver.rotation + 180f, back);
            headY += Angles.trnsy(driver.rotation + 180f, back);
            aim = driver.rotation;
        }else if(build instanceof Turret.TurretBuild turret){
            // DrawTurret.drawTurret(): at recoilOffset, turned by drawrot()
            headX += turret.recoilOffset.x;
            headY += turret.recoilOffset.y;
            aim = turret.rotation;
        }else if(build instanceof BaseTurret.BaseTurretBuild turret){
            // TractorBeamTurret, PointDefenseTurret, BuildTurret: Draw.rect(region, x, y, rotation - 90)
            aim = turret.rotation;
        }else if(build instanceof RepairTurret.RepairPointBuild point){
            aim = point.rotation;
        }else{
            return false;
        }
        headDegrees = aim - 90f;
        return true;
    }

    /**
     * Reads that picture, with the gloss map that goes with it, unless it has been read already.
     * @param sprite the name of the sprite it is for, owner the name of its block (null for a unit's)
     * @return its place among the pictures (maps)
     */
    private int read(int kind, Fi file, Fi glossFile, String sprite, String owner){
        if(mapsOff) glossFile = null;
        // gloss by colour goes by the sprite's own picture, and the table may say something else for this sprite or block
        Fi paint = GlossColors.any() ? find(spriteFolder, sprite) : null;
        // the piece in the team's colour that the game lays over the base of a block (Block.teamRegion: <name>-team)
        Fi side = paint != null && sprite.equals(owner) && GlossColors.of(sprite, owner, GlossColors.team) >= 0 ? find(spriteFolder, sprite + "-team") : null;
        String key = kind + ":" + file.path() + (glossFile == null ? "" : "|" + glossFile.path())
            + (paint == null ? "" : "|" + paint.path() + (GlossColors.has(sprite) ? "|" + sprite : "") + (GlossColors.has(owner) ? "|" + owner : "") + (side == null ? "" : "|" + side.path()));
        int index = known.get(key, -1);
        if(index >= 0) return index;
        Pixmap map = new Pixmap(file);
        if(kind == byHeight){
            Pixmap made = fromHeight(map);
            map.dispose();
            map = made;
        }
        Pixmap gloss = glossFile == null ? null : new Pixmap(glossFile);
        Pixmap colours = paint == null ? null : new Pixmap(paint), team = side == null ? null : new Pixmap(side);
        if(encode(map, kind, gloss, colours, team, sprite, owner)) byColour++;
        if(colours != null) colours.dispose();
        if(team != null) team.dispose();
        if(gloss != null){
            gloss.dispose();
            glossMaps++;
        }
        index = maps.size;
        maps.add(map);
        known.put(key, index);
        found[kind]++;
        return index;
    }

    /** The sprites of a unit that can have a picture: body, leg and hip (two-legged units), treads (tanks), then one per weapon. */
    private static String[] unitSprites(UnitType type){
        String[] names = new String[unitWeapons + type.weapons.size];
        names[0] = type.name;
        names[1] = type.name + "-leg";
        names[2] = type.name + "-base";
        names[3] = type.name + "-treads";
        for(int i = 0; i < type.weapons.size; i++) names[unitWeapons + i] = type.weapons.get(i).name;
        return names;
    }

    /** Where the weapons begin among the sprites of a unit (unitSprites). */
    private static final int unitWeapons = 4;

    private void load(){
        shelves.clear();
        maps.clear();
        known.clear();
        found[byNormal] = found[byHeight] = 0;
        glossMaps = byColour = 0;
        mapsOff = Core.settings.getBool(ShaderMod.devGlossTable, false);
        // the mod's own pictures left out (ArtSwitch): no normal maps either - they are drawn for those sprites
        if(!ArtSwitch.on) return;
        GlossColors.load();
        Seq<Block> owners = new Seq<>();
        IntSeq kinds = new IntSeq(), slots = new IntSeq(), which = new IntSeq();
        Seq<UnitType> unitOwners = new Seq<>();
        IntSeq unitKinds = new IntSeq(), unitSlots = new IntSeq(), unitWhich = new IntSeq();
        for(UnitType type : Vars.content.units()){
            String[] names = unitSprites(type);
            for(int kind = 0; kind < 2; kind++){
                for(int slot = 0; slot < names.length; slot++){
                    if(names[slot] == null || names[slot].isEmpty()) continue;
                    Fi file = find(kind, names[slot]);
                    if(file == null) continue;
                    unitOwners.add(type);
                    unitKinds.add(kind);
                    unitSlots.add(slot);
                    unitWhich.add(read(kind, file, find(glossFolder, names[slot]), names[slot], null));
                }
            }
        }
        for(Block block : Vars.content.blocks()){
            String[] names = sprites(block);
            for(int kind = 0; kind < 2; kind++){
                for(int slot = 0; slot < names.length; slot++){
                    // (an empty slot: a layer that block does not have - a unit factory has no input arrow)
                    if(names[slot] == null) continue;
                    Fi file = find(kind, names[slot]);
                    // a conveyor picture without a frame number serves every frame of its shape
                    if(file == null && block instanceof Conveyor) file = find(kind, block.name + "-" + slot / beltFrames);
                    if(file == null) continue;
                    // how glossy each pixel of that sprite is, if somebody has drawn that
                    Fi glossFile = find(glossFolder, names[slot]);
                    if(glossFile == null && block instanceof Conveyor) glossFile = find(glossFolder, block.name + "-" + slot / beltFrames);
                    owners.add(block);
                    kinds.add(kind);
                    slots.add(slot);
                    which.add(read(kind, file, glossFile, names[slot], block.name));
                }
            }
        }
        if(maps.isEmpty()) return;

        // All maps side by side in one texture. Each has a rim one pixel wide around it that repeats its outermost
        // pixels: read between pixels at the very edge of a sprite, the texture then gives the edge pixel itself
        // instead of half of it (which showed as dots along the line where two conveyor tiles meet).
        int width = 0, height = 0, rim = 1;
        for(Pixmap map : maps){
            width += map.width + rim * 2;
            height = Math.max(height, map.height + rim * 2);
        }
        Pixmap all = new Pixmap(width, height);
        TextureRegion[] regions = new TextureRegion[maps.size];
        int[] at = new int[maps.size];
        int x = 0;
        for(int i = 0; i < maps.size; i++){
            Pixmap map = maps.get(i);
            at[i] = x + rim;
            for(int px = -rim; px < map.width + rim; px++){
                for(int py = -rim; py < map.height + rim; py++){
                    // Colour is kept only where the map is opaque (colour times alpha): when the texture is read
                    // between pixels, a transparent neighbour then adds nothing, and dividing by the alpha that
                    // comes out gives the direction of the opaque pixels alone (mshaders-normals.frag).
                    int c = map.get(Mathf.clamp(px, 0, map.width - 1), Mathf.clamp(py, 0, map.height - 1));
                    all.set(x + rim + px, rim + py, (c & 0xff) >= 128 ? (c | 0xff) : 0);
                }
            }
            x += map.width + rim * 2;
        }
        atlas = new Texture(all);
        applyFilter();
        all.dispose();
        for(int i = 0; i < maps.size; i++){
            regions[i] = new TextureRegion(atlas, at[i], rim, maps.get(i).width, maps.get(i).height);
            maps.get(i).dispose();
        }
        for(int i = 0; i < owners.size; i++){
            Block block = owners.get(i);
            TextureRegion[][] of = layers.get(block, () -> {
                int n = sprites(block).length;
                return new TextureRegion[][]{new TextureRegion[n], new TextureRegion[n]};
            });
            of[kinds.get(i)][slots.get(i)] = regions[which.get(i)];
        }
        for(int i = 0; i < unitOwners.size; i++){
            UnitType type = unitOwners.get(i);
            UnitLook look = looks.get(type, () -> {
                UnitLook made = new UnitLook();
                made.weapons = new TextureRegion[type.weapons.size][];
                made.gloss = Gloss.of(type);
                return made;
            });
            int slot = unitSlots.get(i);
            TextureRegion[] of = slot == 0 ? look.body : slot == 1 ? look.leg : slot == 2 ? look.hip : slot == 3 ? look.treads : look.weapons[slot - unitWeapons];
            if(of == null){
                of = new TextureRegion[2];
                if(slot == 0) look.body = of;
                else if(slot == 1) look.leg = of;
                else if(slot == 2) look.hip = of;
                else if(slot == 3) look.treads = of;
                else look.weapons[slot - unitWeapons] = of;
            }
            of[unitKinds.get(i)] = regions[unitWhich.get(i)];
        }
        maps.clear();
        known.clear();
        // conveyors: a frame without a picture borrows the nearest frame of the same shape that has one
        for(ObjectMap.Entry<Block, TextureRegion[][]> e : layers){
            if(!(e.key instanceof Conveyor)) continue;
            for(TextureRegion[] of : e.value){
                for(int shape = 0; shape < beltShapes; shape++){
                    TextureRegion[] own = new TextureRegion[beltFrames];
                    System.arraycopy(of, shape * beltFrames, own, 0, beltFrames);
                    for(int frame = 0; frame < beltFrames; frame++){
                        for(int away = 1; away < beltFrames && of[shape * beltFrames + frame] == null; away++){
                            TextureRegion near = own[(frame + away) % beltFrames];
                            if(near == null) near = own[(frame - away + beltFrames) % beltFrames];
                            of[shape * beltFrames + frame] = near;
                        }
                    }
                }
            }
        }
        Log.info("[mshaders] surface maps: @ normal map(s) and @ height map(s) for @ block(s) and @ kind(s) of unit, @ of them with a gloss map@, @ with gloss by colour",
            found[byNormal], found[byHeight], layers.size, looks.size, glossMaps, mapsOff ? " (gloss maps are left out: developer option)" : "", byColour);
    }

    /** The layer the game draws that unit on (UnitType.draw). */
    private static float layerOf(Unit unit){
        UnitType type = unit.type;
        return unit.elevation > 0.5f || (type.flying && unit.dead) ? type.flyingLayer : type.groundLayer + Mathf.clamp(type.hitSize / 4000f, 0f, 0.01f);
    }

    /** Units whose layers differ by less than a tenth are shaded together: the layer rounded to tenths. */
    private static float passOf(float layer){
        return Mathf.round(layer * 10f) / 10f;
    }

    private static final java.util.Comparator<Unit> byLayer = (a, b) -> Float.compare(layerOf(a), layerOf(b));

    /**
     * Finds the units in view that have a map. To be called once a frame, before the frame is played back.
     * @return the layers they are on, each rounded to tenths - one shading pass is due right after each ({@link #drawUnits})
     */
    public FloatSeq collectUnits(){
        units.clear();
        unitLayers.clear();
        if(looks.isEmpty() || Vars.player == null) return unitLayers;
        Rect view = Core.camera.bounds(Tmp.r1);
        for(Unit unit : Groups.unit){
            if(!looks.containsKey(unit.type)) continue;
            float reach = Math.max(unit.hitSize * 2f, 16f) + 8f;
            if(unit.x + reach < view.x || unit.x - reach > view.x + view.width || unit.y + reach < view.y || unit.y - reach > view.y + view.height) continue;
            // nothing the fog of war hides (UnitType.draw); units made of segments are drawn piece by piece - not yet
            if(!unit.isValid() || unit.inFogTo(Vars.player.team()) || unit instanceof Crawlc || unit instanceof Segmentc) continue;
            units.add(unit);
            float pass = passOf(layerOf(unit));
            if(!unitLayers.contains(pass)) unitLayers.add(pass);
        }
        // where two units overlap, the one on the higher layer is on top - here as in the game
        if(units.size > 1) units.sort(byLayer);
        return unitLayers;
    }

    /** What drawUnits was given: how to switch the batch to cutting shapes out of the picture, and back to laying maps on it. */
    private Runnable cutState, layState;
    private boolean cutting;

    /**
     * Draws the maps of the units of that layer (one of those collectUnits gave), with whatever batch shader, blend
     * mode and target are set - those of "lay".
     * @param cut sets the batch up so that a sprite of the game drawn with it empties the picture where the sprite is
     * @param lay sets it back to drawing maps
     * @return how many units were drawn
     */
    public int drawUnits(float pass, Runnable cut, Runnable lay){
        cutState = cut;
        layState = lay;
        cutting = false;
        applyFilter();
        first = Core.settings.getBool(ShaderMod.normalsHeight, true) ? byHeight : byNormal;
        int drawn = 0;
        for(Unit unit : units){
            if(passOf(layerOf(unit)) != pass) continue;
            UnitLook look = looks.get(unit.type);
            if(look == null) continue;
            drawUnit(unit, look);
            drawn++;
        }
        Draw.color();
        return drawn;
    }

    private TextureRegion pick(TextureRegion[] of){
        return of == null ? null : of[first] != null ? of[first] : of[1 - first];
    }

    /** One unit's sprites, each placed and turned the way the game draws it (UnitType.draw). */
    private void drawUnit(Unit unit, UnitLook look){
        UnitType type = unit.type;
        shine = look.gloss;
        float x = unit.x, y = unit.y, turn = unit.rotation - 90f;
        if(unit instanceof Mechc mech){
            // UnitType.drawMech(): two legs - one sprite, flipped for the other side - that swing forward and back and
            // are drawn shorter while lifted; then the hip. In flight (a nova boosting) the legs fold back.
            float e = unit.elevation, base = mech.baseRotation();
            float sin = Mathf.lerp(Mathf.sin(mech.walkExtend(true), 2f / Mathf.PI, 1f), 0f, e);
            float extension = Mathf.lerp(mech.walkExtend(false), 0f, e), boost = e * 2f;
            TextureRegion leg = pick(look.leg);
            if(leg != null){
                for(int i : Mathf.signs){
                    layer(leg, unit.x + Angles.trnsx(base, extension * i - boost, -boost * i), unit.y + Angles.trnsy(base, extension * i - boost, -boost * i),
                        i, 1f - Math.max(-sin * i, 0f) * 0.5f, 0f, 0f, base - 90f + 35f * i * e);
                }
            }
            layer(pick(look.hip), unit.x, unit.y, false, false, base - 90f);
            // the body sways on the legs (UnitType.draw moves the unit by this much while it draws the rest)
            float side = Mathf.lerp(Mathf.sin(mech.walkExtend(true), 2f / Mathf.PI, 1f) * type.mechSideSway, 0f, e);
            float front = Mathf.lerp(Mathf.sin(mech.walkExtend(true), 1f / Mathf.PI, 1f) * type.mechFrontSway, 0f, e);
            x += Angles.trnsx(base, 0f, side) + Angles.trnsx(base + 90f, 0f, front);
            y += Angles.trnsy(base, 0f, side) + Angles.trnsy(base + 90f, 0f, front);
        }
        // UnitType.drawTank(): the treads lie under the body, turned with it
        if(unit instanceof Tankc) layer(pick(look.treads), unit.x, unit.y, false, false, turn);
        // weapons the game draws below the body, the body, then the weapons on it
        drawWeapons(unit, look, x, y, true);
        if(type.drawBody) layer(pick(look.body), x, y, false, false, turn);
        drawWeapons(unit, look, x, y, false);
    }

    /** Weapon.draw(): each weapon at its place on the unit, turned to where it aims and pushed back by its recoil. */
    private void drawWeapons(Unit unit, UnitLook look, float x, float y, boolean under){
        float turn = unit.rotation - 90f;
        WeaponMount[] mounts = unit.mounts;
        for(int i = 0; i < mounts.length && i < look.weapons.length; i++){
            TextureRegion map = pick(look.weapons[i]);
            Weapon weapon = mounts[i].weapon;
            // a weapon drawn far above its unit comes after the shading of that layer: left out
            if((weapon.layerOffset < 0f) != under || weapon.layerOffset > 0.02f) continue;
            // a weapon under the body needs no cutting out: the body's map covers it as the body covers the weapon
            if(map == null && (under || !weapon.region.found() || cutState == null)) continue;
            float recoil = Mathf.pow(mounts[i].recoil, weapon.recoilPow) * weapon.recoil;
            float aim = turn + (weapon.rotate ? mounts[i].rotation : weapon.baseRotation);
            float wx = x + Angles.trnsx(turn, weapon.x, weapon.y) + Angles.trnsx(aim, 0f, -recoil), wy = y + Angles.trnsy(turn, weapon.x, weapon.y) + Angles.trnsy(aim, 0f, -recoil);
            if(map == null){
                // No map of its own: the weapon's own sprite, exactly where the game draws it, empties the picture
                // there - so the weapon is not shaded as if it were part of the plates it lies on.
                if(!cutting){
                    cutState.run();
                    cutting = true;
                }
                Draw.color();
                Draw.rect(weapon.region, wx, wy, weapon.region.width * weapon.region.scl() * (weapon.flipSprite ? -1f : 1f), weapon.region.height * weapon.region.scl(), aim);
                continue;
            }
            if(cutting){
                layState.run();
                cutting = false;
            }
            // the weapon of the other side is the same sprite flipped left to right (Weapon.flipSprite)
            layer(map, wx, wy, weapon.flipSprite, false, aim);
        }
        if(cutting){
            layState.run();
            cutting = false;
        }
    }

    /**
     * A height map the way it is kept in the texture: red = height (the brightness of the picture), green and blue 0,
     * transparent where the picture is. Blue 0 is what tells it from a normal map, whose blue is never below half.
     *
     * The heights are NOT turned into directions here. They are drawn into the frame's picture as heights, and the
     * shading pass works out the lean of every pixel from the heights around it on the screen (mshaders-shape.frag) -
     * so a pixel at the edge of a tile sees the height of the tile next to it, whatever shape, frame and direction that
     * one has. (The first version worked the directions out here, per picture, taking "the same height" for everything
     * beyond the picture's edge: every outline was cut off where two conveyor tiles met.)
     */
    static Pixmap fromHeight(Pixmap heights){
        Pixmap out = new Pixmap(heights.width, heights.height);
        for(int x = 0; x < heights.width; x++){
            for(int y = 0; y < heights.height; y++){
                int c = heights.get(x, y);
                out.set(x, y, (c & 0xff) < 128 ? 0 : Color.rgba8888(level(c), 0f, 0f, 1f));
            }
        }
        return out;
    }

    private static float level(int rgba){
        return ((rgba >>> 24) + (rgba >>> 16 & 0xff) + (rgba >>> 8 & 0xff)) / (3f * 255f);
    }

    /**
     * Draws the maps of every building in view that has any, with whatever batch shader and target are set.
     * @return how many buildings were drawn
     */
    public int draw(){
        if(layers.isEmpty() || Vars.world == null) return 0;
        applyFilter();
        carrying.clear();
        heads.clear();
        first = Core.settings.getBool(ShaderMod.normalsHeight, true) ? byHeight : byNormal;
        float hw = Core.camera.width / 2f, hh = Core.camera.height / 2f;
        // a building is found on its own tile, which for a large block lies near its middle: look a few tiles beyond the view
        int margin = 8;
        int x0 = Math.max((int)((Core.camera.position.x - hw) / Vars.tilesize) - margin, 0);
        int y0 = Math.max((int)((Core.camera.position.y - hh) / Vars.tilesize) - margin, 0);
        int x1 = Math.min((int)((Core.camera.position.x + hw) / Vars.tilesize) + margin, Vars.world.width() - 1);
        int y1 = Math.min((int)((Core.camera.position.y + hh) / Vars.tilesize) + margin, Vars.world.height() - 1);
        int drawn = 0;
        for(int x = x0; x <= x1; x++){
            for(int y = y0; y <= y1; y++){
                Tile tile = Vars.world.tile(x, y);
                Building build = tile == null ? null : tile.build;
                if(build == null || build.tile != tile) continue;
                TextureRegion[][] of = layers.get(build.block);
                if(of == null) continue;
                // the same rule the game draws buildings by (BlockRenderer.drawBlocks): nothing that the fog of war hides
                if(build.inFogTo(Vars.player.team()) && !build.wasVisible) continue;
                collecting = true;
                if(draw(build, of)) drawn++;
                collecting = false;
            }
        }
        Draw.color();
        return drawn;
    }

    /** The kind of map that wins where a sprite has both (the "prefer height maps" setting). */
    private int first;

    private TextureRegion pick(TextureRegion[][] of, int slot){
        TextureRegion map = of[first][slot];
        return map != null ? map : of[1 - first][slot];
    }

    /** One building's layers, placed and turned the way the game draws its sprites. */
    private boolean draw(Building build, TextureRegion[][] of){
        Block block = build.block;
        shine = Gloss.of(block);
        if(build instanceof Conveyor.ConveyorBuild belt){
            // Conveyor.ConveyorBuild.draw(): which of the four frames of the animation shows right now ...
            Conveyor conveyor = (Conveyor)block;
            int frame = belt.enabled && belt.clogHeat <= 0.5f && !Vars.state.rules.editor ?
                (int)((Time.time * conveyor.speed * 8f * belt.timeScale() * belt.efficiency) % beltFrames) : 0;
            if(belt.blendbits < 0 || belt.blendbits >= beltShapes || frame < 0 || frame >= beltFrames) return false;
            TextureRegion map = pick(of, belt.blendbits * beltFrames + frame);
            if(map == null) return false;   // no picture for the shape this conveyor has
            // ... drawn one tile large, mirrored where the game mirrors it (a bend to the other side, a join from the other side)
            layer(map, build.x, build.y, belt.blendsclx < 0, belt.blendscly < 0, build.rotation * 90f);
            if(belt.len > 0) carrying.add(belt);
            return true;
        }
        if(headed(block)){
            // the base sits still
            layer(pick(of, base), build.x, build.y, false, false, 0f);
            if(hasHead(of)){
                if(lateHead(block)){
                    // the head comes later (drawHeads)
                    if(collecting) heads.add(build);
                }else if(headOf(build)){
                    layer(pick(of, head), headX, headY, false, false, headDegrees);
                }
            }
            return true;
        }
        if(build instanceof Thruster.ThrusterBuild){
            // Thruster.ThrusterBuild.drawCached(): the body sits still, the top turns with the building
            layer(pick(of, base), build.x, build.y, false, false, 0f);
            layer(pick(of, lid), build.x, build.y, false, false, build.rotdeg());
            return true;
        }
        // which of its sprites the game draws right now: a door's open one while it stands open (Door.DoorBuild.draw,
        // AutoDoor.AutoDoorBuild.draw), else the one picked by the place of the building (Building.draw)
        int slot = base;
        if(build instanceof Door.DoorBuild door) slot = door.open ? ajar : base;
        else if(build instanceof AutoDoor.AutoDoorBuild door) slot = door.open ? ajar : base;
        else if(block.variants > 0 && block.variantRegions != null) slot = Mathf.randomSeed(build.tile.pos(), 0, Math.max(0, block.variantRegions.length - 1));
        if(slot >= of[byNormal].length) slot = base;
        // the base: turned with the building only for blocks whose sprite the game turns
        layer(pick(of, slot), build.x, build.y, false, false, block.rotate && block.rotateDraw ? build.rotdeg() : 0f);
        // the frame of a unit factory or reconstructor, over its floor (and over the unit being made, which the game
        // draws between the two), not turned
        if(block instanceof mindustry.world.blocks.units.UnitFactory || block instanceof mindustry.world.blocks.units.Reconstructor){
            // UnitFactory/Reconstructor.draw(): the floor, the arrows turned with the building, (the unit being made,)
            // the frame on top
            if(of[byNormal].length > 3){
                layer(pick(of, 1), build.x, build.y, false, false, build.rotdeg());
                layer(pick(of, 2), build.x, build.y, false, false, build.rotdeg());
                layer(pick(of, 3), build.x, build.y, false, false, 0f);
            }
            return true;
        }
        if(build instanceof Drill.DrillBuild drill){
            // Drill.DrillBuild.draw(): the rotator spins by timeDrilled * rotateSpeed, the top sits still
            layer(pick(of, rotator), build.x, build.y, false, false, drill.timeDrilled * ((Drill)block).rotateSpeed);
            layer(pick(of, top), build.x, build.y, false, false, 0f);
        }
        return true;
    }

    /**
     * The things that lie on top of the blocks just drawn: the items on those conveyors, each drawn where and as large
     * as the game draws it (Conveyor.ConveyorBuild.draw). To be called with the batch shader that writes them as lumps
     * of their own height (mshaders-items.frag), so the belt under an item does not show through it.
     * @return how many items were drawn
     */
    public int drawCovers(){
        over.clear();
        int drawn = 0;
        Draw.color();
        for(Conveyor.ConveyorBuild belt : carrying){
            Tmp.v1.trns(belt.rotation * 90, Vars.tilesize, 0);
            float alongX = Tmp.v1.x, alongY = Tmp.v1.y;
            for(int i = 0; i < belt.len; i++){
                if(belt.ids[i] == null) continue;
                Tmp.v2.trns(belt.rotation * 90, -Vars.tilesize / 2f, belt.xs[i] * Vars.tilesize / 2f);
                float ix = belt.x + alongX * belt.ys[i] + Tmp.v2.x, iy = belt.y + alongY * belt.ys[i] + Tmp.v2.y;
                Draw.rect(belt.ids[i].fullIcon, ix, iy, Vars.itemSize, Vars.itemSize);
                // An item that has just come out of a machine, or is about to go into one, lies half under it: the game
                // draws that building over the item. Remember the building, so the item is taken off it again.
                for(int corner = 0; corner < 4; corner++){
                    Building at = Vars.world.buildWorld(ix + (corner % 2 == 0 ? -1 : 1) * Vars.itemSize / 2f, iy + (corner < 2 ? -1 : 1) * Vars.itemSize / 2f);
                    // conveyors of every kind are drawn below the items; everything else is drawn above them
                    if(at != null && !(at.block instanceof Conveyor) && !(at.block instanceof StackConveyor) && !over.contains(at, true)) over.add(at);
                }
                drawn++;
            }
        }
        carrying.clear();
        return drawn;
    }

    /**
     * Wipes the picture where the buildings stand that an item reaches under: the place each of them covers, as a plain
     * square. To be called with a blend mode that writes nothing (the picture becomes empty there), after drawCovers.
     * @return how many buildings were wiped
     */
    public int eraseOver(){
        Draw.color();
        for(Building build : over){
            float side = build.block.size * Vars.tilesize;
            Fill.rect(build.x, build.y, side, side);
        }
        return over.size;
    }

    /** Draws the maps of those buildings again - the ones that have any - now on top of the items, as the game draws them. */
    public void redrawOver(){
        for(Building build : over){
            TextureRegion[][] of = layers.get(build.block);
            if(of != null) draw(build, of);
        }
        over.clear();
        Draw.color();
    }

    /** How many heads the buildings drawn by {@link #draw} in this frame have. */
    public int headCount(){
        return heads.size;
    }

    /**
     * The heads of the mass drivers and turrets drawn by {@link #draw} in this frame, each where the game draws it
     * (headOf): turned to where it aims and pushed back by its recoil right after a shot. The list is kept, so this
     * can be called more than once a frame.
     * @return how many heads were drawn
     */
    public int drawHeads(){
        for(Building build : heads){
            TextureRegion[][] of = layers.get(build.block);
            if(of == null || !headOf(build)) continue;
            shine = Gloss.of(build.block);
            // a turret put together from parts: those the game draws under its body first, then the body, then the rest
            boolean parts = build instanceof Turret.TurretBuild && of[byNormal].length > head + 1;
            if(parts) drawParts((Turret.TurretBuild)build, of, true);
            layer(pick(of, head), headX, headY, false, false, headDegrees);
            if(parts) drawParts((Turret.TurretBuild)build, of, false);
        }
        Draw.color();
        return heads.size;
    }

    /** Is there a picture for the head of that block, or for one of the parts its head is put together from? */
    private static boolean hasHead(TextureRegion[][] of){
        for(TextureRegion[] kind : of){
            for(int slot = head; slot < kind.length; slot++) if(kind[slot] != null) return true;
        }
        return false;
    }

    /**
     * The moving parts of a turret (DrawTurret.draw): the ones drawn under its body (below = true) or the ones drawn over
     * it. Each part works out where it is from what the turret is doing - how far its last shot has pushed it back, and
     * so on - exactly as the game does when it draws the part.
     */
    private void drawParts(Turret.TurretBuild turret, TextureRegion[][] of, boolean below){
        DrawTurret drawer = (DrawTurret)((Turret)turret.block).drawer;
        if(drawer.parts.size == 0) return;
        float progress = turret.progress();
        DrawPart.PartParams params = partLevels[0].set(turret.warmup(), 1f - progress, 1f - progress, turret.heat, turret.curRecoil, turret.charge,
            turret.x + turret.recoilOffset.x, turret.y + turret.recoilOffset.y, turret.rotation);
        for(DrawPart drawn : drawer.parts){
            if(!(drawn instanceof RegionPart part)) continue;
            // the layer the game draws the part on (RegionPart.draw)
            float z = (part.layer > 0 ? part.layer : drawer.turretLayer) - (part.under ? 0.0001f : 0f) + part.layerOffset;
            if(z > Layer.turret + headPassOffset || (z < drawer.turretLayer) != below) continue;
            params.setRecoil(part.recoilIndex >= 0 && turret.curRecoils != null && part.recoilIndex < turret.curRecoils.length ? turret.curRecoils[part.recoilIndex] : turret.curRecoil);
            drawPart(part, params, of, 1);
        }
    }

    /** RegionPart.draw(), with the picture of the part in place of its sprite; then the parts within it. */
    private void drawPart(RegionPart part, DrawPart.PartParams params, TextureRegion[][] of, int depth){
        float prog = part.progress.getClamp(params, part.clampProgress), grow = part.growProgress.getClamp(params, part.clampProgress);
        float mx = part.moveX * prog, my = part.moveY * prog, mr = part.moveRot * prog + part.rotation, gx = part.growX * grow, gy = part.growY * grow;
        for(DrawPart.PartMove move : part.moves){
            float p = move.progress.getClamp(params, part.clampProgress);
            mx += move.x * p;
            my += move.y * p;
            mr += move.rot * p;
            gx += move.gx * p;
            gy += move.gy * p;
        }
        int sides = part.mirror && params.sideOverride == -1 ? 2 : 1;
        float sclX = part.xScl + gx, sclY = part.yScl + gy;
        for(int s = 0; s < sides; s++){
            int i = params.sideOverride == -1 ? s : params.sideOverride;
            TextureRegion region = part.drawRegion && part.regions.length > 0 ? part.regions[Math.min(i, part.regions.length - 1)] : null;
            int slot = region == null ? -1 : partSlots.get(region, -1);
            TextureRegion map = slot < 0 || slot >= of[byNormal].length ? null : pick(of, slot);
            if(map == null) continue;
            // the other side of a mirrored pair is the same sprite flipped left to right
            float sign = (i == 0 ? 1 : -1) * params.sideMultiplier, flipped = sclX * sign;
            Tmp.v1.set((part.x + mx) * sign, part.y + my).rotateRadExact((params.rotation - 90f) * Mathf.degRad);
            if(part.originX != 0f || part.originY != 0f){
                Tmp.v1.sub(Tmp.v2.set(-part.originX * flipped, -part.originY * sclY).rotate(params.rotation - 90f).add(part.originX * flipped, part.originY * sclY));
            }
            layer(map, params.x + Tmp.v1.x, params.y + Tmp.v1.y, flipped, sclY, part.originX * flipped, part.originY * sclY, mr * sign + params.rotation - 90f);
        }
        if(part.children.size == 0 || depth >= partLevels.length) return;
        for(int s = 0; s < sides; s++){
            int i = params.sideOverride == -1 ? s : params.sideOverride;
            float sign = (i == 1 ? -1 : 1) * params.sideMultiplier;
            Tmp.v1.set((part.x + mx) * sign, part.y + my).rotateRadExact((params.rotation - 90f) * Mathf.degRad);
            DrawPart.PartParams inner = partLevels[depth].set(params.warmup, params.reload, params.smoothReload, params.heat, params.recoil, params.charge,
                params.x + Tmp.v1.x, params.y + Tmp.v1.y, mr * sign + params.rotation);
            inner.sideMultiplier = params.sideMultiplier;
            inner.life = params.life;
            inner.sideOverride = i;
            for(DrawPart child : part.children){
                if(child instanceof RegionPart region) drawPart(region, inner, of, depth + 1);
            }
        }
    }

    /** A picture drawn stretched (a negative stretch mirrors it) and turned about a point other than its middle, as RegionPart.rect does with the sprite. */
    private void layer(TextureRegion map, float x, float y, float sclX, float sclY, float originX, float originY, float degrees){
        if(map == null) return;
        Draw.color(Mathf.cosDeg(degrees) * 0.5f + 0.5f, Mathf.sinDeg(degrees) * 0.5f + 0.5f, (sclX < 0f ? 0.25f : 0f) + (sclY < 0f ? 0.5f : 0f), shine);
        float w = map.width / 4f * sclX, h = map.height / 4f * sclY;
        Draw.rect(map, x, y, w, h, w / 2f + originX, h / 2f + originY, degrees);
    }

    private void layer(TextureRegion map, float x, float y, boolean flipX, boolean flipY, float degrees){
        if(map == null) return;
        // The angle rides along as a colour: red = cosine, green = sine (0..1); blue says which way the sprite is
        // mirrored (0, 1/4 = left-right, 1/2 = up-down, 3/4 = both); alpha = how glossy the block is. Read back in
        // mshaders-normals.frag.
        Draw.color(Mathf.cosDeg(degrees) * 0.5f + 0.5f, Mathf.sinDeg(degrees) * 0.5f + 0.5f, (flipX ? 0.25f : 0f) + (flipY ? 0.5f : 0f), shine);
        // sprites have 4 pixels per world unit
        Draw.rect(map, x, y, map.width / 4f * (flipX ? -1f : 1f), map.height / 4f * (flipY ? -1f : 1f), degrees);
    }
}
