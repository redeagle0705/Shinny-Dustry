package mshaders;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;

/**
 * Gloss by colour: how glossy a pixel of a sprite is, told from the colour the sprite has there.
 *
 * The maker draws the sprites of this mod in a handful of flat colours, one for each stuff - a grey for painted steel,
 * a brown for copper - and found that the gloss maps drawn for them said nothing new: the same colour got the same
 * grey ("give every colour its gloss and the maps need not be made at all"). Measured on the 34 maps drawn by then: in
 * 29 of them every colour has exactly one gloss; from block to block a colour does differ here and there (copper is
 * 182 on a wall and 223 on a drill). The three cores seemed to paint one grey in two glosses - the second one lies
 * exactly under the piece in the team's colour, which is a sprite of its own (<block>-team): hence "team" below.
 * Left over: one unit and one weapon, where small parts of a colour are polished more than the rest.
 *
 * So a table says it once, and a line can say something else for one block or sprite:
 *   colour = gloss             989aa4 = 0
 *   name.colour = gloss        mechanical-drill.b28768 = 223
 *   name.* = gloss             conveyor.* = 0          (every colour of it that has no line)
 *   name.clear = gloss         sorter.clear = 255      (where the sprite is transparent but its normal or height
 *                                                       map is not: the window of a sorter, which the game fills
 *                                                       with the colour of the item)
 *   team = gloss               team = 223              (where the block's piece in the team's colour lies, the
 *   name.team = gloss                                   sprite <block>-team; asked before the colour under it)
 * colour: as in the sprite, six hex digits (no # - that starts a comment). gloss: 0 .. 255, the grey that would be
 * painted in a gloss map (0 = matt, 255 = polished metal). name: the name of a sprite, or of a block for every sprite
 * of that block (its base, its head, its parts; every shape and frame of a conveyor).
 *
 * What a pixel's gloss is, in this order (Surfaces.encode):
 *   1. the gloss map of the sprite, where it is not transparent;
 *   2. the line for that sprite and colour, then for its block and colour, then for the colour alone;
 *   3. as glossy as the block is as a whole (Gloss.java).
 * So a gloss map is needed only where one colour of a sprite is to shine in two ways - and may be left transparent
 * everywhere else.
 *
 * The colours are those of the sprite in sprites-override/ (the sprites drawn for this mod; a sprite of the game's own
 * has many shades and is not looked at). A colour a shade off - one step of rounding in a drawing program - counts as
 * the colour next to it.
 *
 * Files: mshaders/gloss-colors.properties in the mod; the same file in the watched folder (Surfaces.watched) - of
 * those two the one saved last is read last and wins line by line; mshaders-gloss-colors.properties in the game's
 * data folder is read after both.
 */
class GlossColors{
    private static final String path = "mshaders/gloss-colors.properties";
    /** How far off, in each of red, green and blue, a colour still counts as the one in the table. */
    private static final int close = 2;
    /** The place of "name.* = gloss" among the colours of a name: every colour of it that has no line. */
    private static final int rest = 0x1000000;
    /** The place of "name.clear = gloss", and what to ask for where the sprite is transparent. */
    static final int clear = 0x2000000;
    /** The place of "team = gloss", and what to ask for where the block's piece in the team's colour lies. */
    static final int team = 0x3000000;

    private static final IntIntMap all = new IntIntMap();
    private static final ObjectMap<String, IntIntMap> named = new ObjectMap<>();

    /** The files the table is read from, oldest say first: null where there is none. */
    static Fi[] files(){
        Fi own = null, drawn = null, data = null;
        try{
            own = Vars.tree.get(path);
            if(!own.exists()) own = null;
            Fi watch = Surfaces.watched();
            drawn = watch == null ? null : watch.child(path);
            if(drawn != null && !drawn.exists()) drawn = null;
            data = Vars.dataDirectory.child("mshaders-gloss-colors.properties");
            if(!data.exists()) data = null;
        }catch(Throwable ignored){
        }
        // of the mod's own copy and the one in the folder pictures are drawn in, the one saved last counts
        boolean ownLater = own != null && drawn != null && own.lastModified() > drawn.lastModified() + 3000L;
        return ownLater ? new Fi[]{drawn, own, data} : new Fi[]{own, drawn, data};
    }

    /** Reads the table again from its files. */
    static void load(){
        all.clear();
        named.clear();
        int lines = 0;
        for(Fi file : files()){
            if(file == null) continue;
            try{
                lines += read(file);
            }catch(Throwable t){
                Log.err("[mshaders] gloss by colour: " + file.name() + " could not be read", t);
            }
        }
        if(lines > 0) Log.info("[mshaders] gloss by colour: @ colour(s), @ block(s) or sprite(s) with lines of their own", all.size, named.size);
    }

    private static int read(Fi file){
        int lines = 0;
        for(String line : file.readString().split("\n")){
            int hash = line.indexOf('#');
            if(hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if(eq <= 0) continue;
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            try{
                int dot = key.lastIndexOf('.');
                String hex = key.substring(dot + 1).trim();
                boolean others = dot >= 0 && hex.equals("*"), hole = dot >= 0 && hex.equals("clear"), side = hex.equals("team");
                if(!others && !hole && !side && hex.length() != 6) throw new NumberFormatException();
                int rgb = others ? rest : hole ? clear : side ? team : Integer.parseInt(hex, 16), gloss = Math.max(0, Math.min(255, Integer.parseInt(value)));
                (dot < 0 ? all : named.get(key.substring(0, dot).trim(), IntIntMap::new)).put(rgb, gloss);
                lines++;
            }catch(NumberFormatException e){
                Log.warn("[mshaders] gloss by colour: cannot read '@' in @ (colour = 0..255, or name.colour = 0..255)", line.trim(), file.name());
            }
        }
        return lines;
    }

    /** Is there anything in the table at all? */
    static boolean any(){
        return all.size > 0 || named.size > 0;
    }

    /** Are there lines for that block or sprite alone? */
    static boolean has(String name){
        return name != null && named.containsKey(name);
    }

    /**
     * How glossy a pixel of that colour is, 0 .. 255, or -1 if the table does not say.
     * @param sprite the name of the sprite, owner the name of the block it belongs to (or null), rgb 0xRRGGBB - or "clear" or "team"
     */
    static int of(String sprite, String owner, int rgb){
        int gloss = in(named.get(sprite), rgb);
        if(gloss < 0 && owner != null && !owner.equals(sprite)) gloss = in(named.get(owner), rgb);
        return gloss < 0 ? in(all, rgb) : gloss;
    }

    private static int in(IntIntMap map, int rgb){
        if(map == null || map.size == 0) return -1;
        int gloss = map.get(rgb, -1);
        if(gloss >= 0 || rgb >= rest) return gloss;
        int r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        for(IntIntMap.Entry e : map){
            if(e.key < rest && Math.abs((e.key >> 16 & 0xff) - r) <= close && Math.abs((e.key >> 8 & 0xff) - g) <= close && Math.abs((e.key & 0xff) - b) <= close) return e.value;
        }
        return map.get(rest, -1);
    }
}
