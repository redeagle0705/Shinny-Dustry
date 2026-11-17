package mshaders;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.type.*;
import mindustry.world.*;

/**
 * How glossy each block is: 0 = matt, 0.5 = half polished (the soft sheen every block had before there was such a
 * number, with a first hint of a mirror), 1 = polished metal. The game has no such number. A polished face works as a
 * mirror: the sun and the lamps show in it as small bright patches and a streak of sky lies across it; a matt one has
 * none of that (mshaders-shape.frag).
 *
 * A block's gloss comes from, in this order:
 *   1. mshaders-gloss.properties in the game's data folder, if the player has put one there;
 *   2. mshaders/gloss.properties inside the mod;
 *   3. what the block is built from: the gloss of each item it costs ({@link #ofItem}), weighted by how many of them -
 *      a copper wall is as glossy as copper, a block of plastanium and metal lies in between. A block that costs
 *      nothing (map scenery, sandbox blocks) is 0.5.
 * File format, one block per line:   internal-block-name = gloss     (# starts a comment)
 *
 * That is one gloss for the whole block. Where a block is not one stuff all over - a metal frame around a pane of
 * glass - a picture can say how glossy each pixel of its sprite is (Surfaces.java, the gloss maps): there the picture
 * counts and not this number.
 */
public class Gloss{
    /** A block of unknown make, and every block before this was written. */
    public static final float plain = 0.5f;

    private static final ObjectFloatMap<String> table = new ObjectFloatMap<>();
    private static final ObjectFloatMap<Block> cache = new ObjectFloatMap<>();
    private static final ObjectFloatMap<UnitType> unitCache = new ObjectFloatMap<>();
    private static boolean loaded;

    /** How glossy that block is, 0..1. */
    public static float of(Block block){
        float known = cache.get(block, -1f);
        if(known >= 0f) return known;
        if(!loaded) load();
        float gloss = table.get(block.name, -1f);
        if(gloss < 0f) gloss = byMake(block);
        gloss = Math.max(0f, Math.min(1f, gloss));
        cache.put(block, gloss);
        return gloss;
    }

    /**
     * How glossy that kind of unit is, 0..1: from the same files (by the unit's internal name), else from what the
     * first way of making it costs - the plan of its factory, or what the reconstructor takes for it.
     */
    public static float of(UnitType type){
        float known = unitCache.get(type, -1f);
        if(known >= 0f) return known;
        if(!loaded) load();
        float gloss = table.get(type.name, -1f);
        if(gloss < 0f){
            try{
                gloss = byMake(type.getFirstRequirements());
            }catch(Throwable t){
                gloss = plain;
            }
        }
        gloss = Math.max(0f, Math.min(1f, gloss));
        unitCache.put(type, gloss);
        return gloss;
    }

    /** From what the block costs to build: the gloss of each item, weighted by how many of them. */
    static float byMake(Block block){
        return byMake(block.requirements);
    }

    static float byMake(ItemStack[] cost){
        float sum = 0f, count = 0f;
        if(cost != null){
            for(ItemStack stack : cost){
                if(stack == null || stack.item == null || stack.amount <= 0) continue;
                sum += ofItem(stack.item) * stack.amount;
                count += stack.amount;
            }
        }
        return count > 0f ? sum / count : plain;
    }

    /**
     * How glossy something made of that item is: 0 = matt (cloth, plastic, stone), 0.5 = half polished,
     * 1 = polished metal.
     */
    static float ofItem(Item item){
        // First values, meant to be changed by eye: metals above the plain 0.5, the less metallic below it
        // (a friend of the maker's: "copper, titanium, surge should shine; thorium, plastanium, phase be matt").
        switch(item.name){
            // Serpulo
            case "surge-alloy": return 1f;
            case "metaglass": return 0.9f;
            case "titanium": return 0.85f;
            case "copper": return 0.8f;
            case "lead": return 0.6f;
            case "silicon": return 0.6f;
            case "thorium": return 0.35f;     // a metal, but a dull one
            case "graphite": return 0.25f;
            case "plastanium": return 0.2f;   // plastic
            case "phase-fabric": return 0.1f; // cloth
            // Erekir
            case "tungsten": return 0.8f;
            case "beryllium": return 0.7f;
            case "carbide": return 0.45f;
            case "oxide": return 0.3f;
            default: return plain;
        }
    }

    private static void load(){
        loaded = true;
        try{
            read(Vars.tree.get("mshaders/gloss.properties"));
            read(Vars.dataDirectory.child("mshaders-gloss.properties"));
        }catch(Throwable t){
            Log.err("[mshaders] block gloss file unreadable, using what the blocks are built from", t);
        }
        if(table.size > 0) Log.info("[mshaders] block gloss: @ taken from files", table.size);
    }

    private static void read(Fi file){
        if(file == null || !file.exists()) return;
        for(String line : file.readString().split("\n")){
            int hash = line.indexOf('#');
            if(hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if(eq <= 0) continue;
            try{
                table.put(line.substring(0, eq).trim(), Float.parseFloat(line.substring(eq + 1).trim()));
            }catch(NumberFormatException ignored){
                Log.warn("[mshaders] block gloss: cannot read '@' in @", line.trim(), file.name());
            }
        }
    }
}
