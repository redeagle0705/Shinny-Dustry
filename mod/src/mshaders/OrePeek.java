package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.production.*;

/**
 * With the cursor over a drill, what lies under it shows: the ground of each of its tiles and the ore on it are drawn
 * over the drill (as if it were not there), with a thin outline round the tiles that give its item (the maker: "when the
 * cursor is on a drill, show the ore under it"). Not while a block is being placed.
 */
public class OrePeek{
    /** For the test kit: where the cursor is taken to be (world units); NaN = the real cursor. */
    public static float testX = Float.NaN, testY = Float.NaN;
    private static float shown;
    private static Building last;
    private static final Color c = new Color();

    public static void init(){
        Events.run(Trigger.draw, OrePeek::draw);
    }

    private static void draw(){
        if(!Core.settings.getBool(ShaderMod.orePeekOn, true) || Vars.state.isMenu()) return;
        var input = Vars.control.input;
        boolean placing = input != null && (input.block != null || !input.selectPlans.isEmpty());
        float wx = Float.isNaN(testX) ? Core.input.mouseWorldX() : testX, wy = Float.isNaN(testY) ? Core.input.mouseWorldY() : testY;
        Building b = placing || Core.scene.hasMouse() && Float.isNaN(testX) ? null : Vars.world.buildWorld(wx, wy);
        boolean drill = b != null && (b.block instanceof Drill || b.block instanceof BeamDrill);
        // fades in and out over a few frames
        if(drill) last = b;
        shown = Mathf.approachDelta(shown, drill ? 1f : 0f, 0.15f);
        if(shown <= 0.001f || last == null || !last.isValid()) return;
        Building target = last;
        float s = shown;
        // after the shadows laid on blocks (Layer.turret - 1.5) and the normal-mapped shading (turret - 1): drawn
        // under those, the peek was darkened by the shadow that fell on the drill ("the shadow ate it all")
        Draw.draw(Layer.turret - 0.5f, () -> peek(target, s));
    }

    private static void peek(Building b, float s){
        Block block = b.block;
        int size = block.size, off = (size - 1) / 2;
        Item mined = b instanceof Drill.DrillBuild d ? d.dominantItem : null;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                Tile t = Vars.world.tile(b.tile.x - off + dx, b.tile.y - off + dy);
                if(t == null) continue;
                float x = t.worldx(), y = t.worldy();
                Floor over = t.overlay(), floor = t.floor();
                // the ground itself, as if the drill were not there
                TextureRegion[] ground = floor.variantRegions();
                if(ground != null && ground.length > 0){
                    Draw.color(1f, 1f, 1f, 0.92f * s);
                    Draw.rect(ground[Mathf.randomSeed(arc.math.geom.Point2.pack(t.x, t.y), 0, Math.max(0, ground.length - 1))], x, y);
                }
                // the ore on that tile, or what its floor gives (sand, scrap walls do not count)
                Floor shows = over != Blocks.air && over.itemDrop != null ? over : floor.itemDrop != null ? floor : null;
                if(shows == null) continue;
                TextureRegion[] regions = shows.variantRegions();
                TextureRegion r = regions[Mathf.randomSeed(t.pos(), 0, Math.max(0, regions.length - 1))];
                if(shows != floor){
                    Draw.color(1f, 1f, 1f, s);
                    Draw.rect(r, x, y);
                }
                // the tiles that give what the drill mines: a thin bright outline
                if(mined != null && shows.itemDrop == mined){
                    c.set(mined.color).a(0.7f * s);
                    Draw.color(c);
                    Lines.stroke(0.6f);
                    Lines.rect(x - Vars.tilesize / 2f + 0.3f, y - Vars.tilesize / 2f + 0.3f, Vars.tilesize - 0.6f, Vars.tilesize - 0.6f);
                }
            }
        }
        Draw.reset();
    }
}
