package mshaders;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.storage.*;

/**
 * How tall each block is, in tiles. The game has no such number - its sprites are flat pictures seen from above - so
 * this is a table made up for the effects that need one: how long a block's shadow is and how far its mirror image
 * reaches. One number per kind of block, not per pixel.
 *
 * A height comes from, in this order:
 *   1. mshaders-heights.properties in the game's data folder, if the player has put one there;
 *   2. mshaders/heights.properties inside the mod (ships empty apart from its explanation);
 *   3. the rules in {@link #byRule}: by what kind of block it is and how many tiles it covers.
 * File format, one block per line:   internal-block-name = height     (# starts a comment)
 */
public class Heights{
    /** The tallest height the effects can show; taller entries count as this. */
    public static final float max = 3f;

    private static final ObjectFloatMap<String> table = new ObjectFloatMap<>();
    private static final ObjectFloatMap<Block> cache = new ObjectFloatMap<>();
    private static boolean loaded;

    /**
     * Is the block on that tile in the way right now? The game asks the same way (Tile.solid): a block is, or its
     * building says so at the moment - a door only while it is shut (Door.DoorBuild.checkSolid).
     */
    public static boolean standing(Tile tile){
        Block block = tile.block();
        return block != Blocks.air && (block.solid || (tile.build != null && tile.build.checkSolid()));
    }

    /** Height of whatever block stands on that tile; 0 for bare ground. */
    public static float of(Tile tile){
        Block block = tile.block();
        if(block == Blocks.air) return 0f;
        if(!loaded) load();
        // a door (a block that is only sometimes in the way): as tall as a wall while shut, flat on the ground while open
        if(block.solidifes){
            if(!standing(tile)) return 0.15f;
            float shut = table.get(block.name, -1f);
            return Math.max(0f, Math.min(max, shut >= 0f ? shut : byRule(block, true)));
        }
        float known = cache.get(block, -1f);
        if(known >= 0f) return known;
        float h = table.get(block.name, -1f);
        if(h < 0f) h = byRule(block, block.solid);
        h = Math.max(0f, Math.min(max, h));
        cache.put(block, h);
        return h;
    }

    static float byRule(Block block, boolean solid){
        int size = Math.max(block.size, 1);
        // the map's own walls and cliffs
        if(block.isStatic()) return 1.5f;
        // boulders and other scenery that units walk over
        if(!block.hasBuilding()) return 0.3f;
        if(!solid){
            // the boxes a conveyor line runs through stand a little above it: routers, sorters and inverted sorters,
            // overflow and underflow gates, junctions; the distributor (a router of four tiles) a little higher still
            if(block instanceof Router) return size > 1 ? 0.4f : 0.3f;
            if(block instanceof Sorter || block instanceof OverflowGate || block instanceof Junction) return 0.3f;
            // conveyors, conduits, bridges: they lie on the ground
            return 0.15f;
        }
        if(block instanceof CoreBlock) return 1.5f + 0.4f * (size - 3);
        if(block instanceof Wall) return 1f + 0.25f * (size - 1);
        if(block instanceof BaseTurret) return 1.1f + 0.3f * (size - 1);
        // factories, drills, generators, nodes, everything else that stands
        return 1f + 0.3f * (size - 1);
    }

    private static void load(){
        loaded = true;
        try{
            read(Vars.tree.get("mshaders/heights.properties"));
            read(Vars.dataDirectory.child("mshaders-heights.properties"));
        }catch(Throwable t){
            Log.err("[mshaders] block heights file unreadable, using the built-in rules", t);
        }
        if(table.size > 0) Log.info("[mshaders] block heights: @ taken from files", table.size);
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
                Log.warn("[mshaders] block heights: cannot read '@' in @", line.trim(), file.name());
            }
        }
    }
}
