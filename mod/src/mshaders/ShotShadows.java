package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.entities.bullet.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;

/**
 * The shadows of shots that are off the ground: under each one its own shape in the dark of the game's unit shadows,
 * as far to the lower left as the shot is high - so a shot is seen to fly above the walls and to come down.
 *
 * The game gives shadows to units only. One in full flight has its shadow at UnitType.shadowTX/TY (12 left, 13 down),
 * drawn in Pal.shadow; here a unit in full flight hangs Scene.airLevel up, so a shot that high gets the same shift
 * and one lower a shorter one, by the same slope. How high a shot is, is what the lights already go by
 * (LightTap.shotHeightOf: "height of shots" for turrets and units on the ground, the height of the unit for shots
 * fired from the air).
 *
 * ON WHAT IT FALLS. A shadow lies as far out as the shot is above the thing it falls on - not above the ground. The
 * first version drew every shadow where it would lie on the ground, flat across whatever stood there: a shell passing
 * over a wall had its shadow lying half on the grass and half on top of the wall as if the wall were painted on the
 * grass (the maker saw it at once: "when it passes a building that has a height, the shadow should go up with it").
 * Now the shadow is drawn once for every height there is under it - the ground, and each height of block near it
 * (Heights.java, the same numbers the sun shadows and the lights use) - each time where it would lie on something
 * that tall, and mshaders-shot.frag keeps of each copy the part that is on tiles of that very height. So the shadow
 * climbs onto a wall at the wall's edge, runs along its top nearer to the shot, and steps down again behind it.
 * Blocks lower than half a tile (conveyors, routers, boulders) count as the ground. A block taller than the shot is
 * high gets no shadow.
 *
 * Only shots that are things: bullets and missiles and shells (sprites), bolts, blobs of liquid. A beam, a flame or
 * a lightning is light and throws none. As with units, no shadow on floors that take none (deep water; all water
 * while this mod shows mirror images on it).
 */
class ShotShadows{
    /** How far the shadow lies from its shot for every unit of height. */
    static final float perX = UnitType.shadowTX / Scene.airLevel, perY = UnitType.shadowTY / Scene.airLevel;
    /** Below this height above what it falls on the shadow fades: there it lies under the shot itself and only its rim would stick out. */
    private static final float fade = 6f;
    /** Heights below this (as the picture of heights has them, 255 = Heights.max) count as the ground: half a tile. As LOW in mshaders-shot.frag. */
    private static final int low = 43;

    /** How many were drawn in the last frame. */
    static int drawn;

    private static Shader shader;
    private static boolean failed;
    private static Scene scene;
    /** The heights found under one shot (as the picture of heights has them). */
    private static final int[] found = new int[8];

    private static Shader shader(){
        if(shader == null && !failed){
            try{
                shader = new Shader(Vars.tree.get("shaders/mshaders-shot.vert").readString(), Vars.tree.get("shaders/mshaders-shot.frag").readString()){
                    @Override
                    public void apply(){
                        Texture heights = scene == null ? null : scene.heights();
                        if(heights != null){
                            heights.bind(1);
                            // the batch binds its sprites to whichever unit is the active one
                            Gl.activeTexture(Gl.texture0);
                        }
                        setUniformi("u_heights", 1);
                        setUniformf("u_world", Vars.world.width() * (float)Vars.tilesize, Vars.world.height() * (float)Vars.tilesize);
                    }
                };
            }catch(Throwable t){
                failed = true;
                Log.err("[mshaders] shadows of shots are drawn flat (their shader could not be made)", t);
            }
        }
        return shader;
    }

    /** How tall what stands on that tile is, as the picture of heights has it (Occluders.value): 0 .. 255 = Heights.max tiles; below "low", the ground. */
    private static int tallAt(int tx, int ty){
        Tile tile = Vars.world.tile(tx, ty);
        if(tile == null || tile.block() == Blocks.air) return 0;
        int tall = Math.round(Heights.of(tile) / Heights.max * 255f);
        return tall < low ? 0 : tall;
    }

    /** Called at a layer above the blocks and the units on the ground, below the shots (a Draw.draw runnable). */
    static void draw(LightTap tap, Scene from){
        drawn = 0;
        if(tap == null || Groups.bullet.isEmpty()) return;
        scene = from;
        // with the picture of block heights: each shadow on what it falls on; without it, flat on the ground
        Shader onto = from != null && from.heights() != null ? shader() : null;
        if(onto != null) Draw.shader(onto);
        Rect view = Core.camera.bounds(Tmp.r1).grow(64f);
        for(Bullet b : Groups.bullet){
            if(!view.contains(b.x, b.y)) continue;
            BulletType type = b.type;
            if(!(type instanceof BasicBulletType) && !(type instanceof LiquidBulletType)) continue;
            float high = tap.shotHeightOf(b);
            if(high <= 0f) continue;

            // the shape, as the game draws that kind of shot
            TextureRegion region = null;
            float width = 0f, height = 0f, turn = 0f, reach;
            if(type instanceof LaserBoltBulletType bolt){
                width = bolt.width;
                height = bolt.height;
                reach = height * 0.5f + width;
            }else if(type instanceof BasicBulletType basic){
                // as BasicBulletType.draw: the sprite, shrinking as that type has it, spinning if it spins
                float shrink = basic.shrinkInterp.apply(b.fout());
                height = basic.height * ((1f - basic.shrinkY) + basic.shrinkY * shrink);
                width = basic.width * ((1f - basic.shrinkX) + basic.shrinkX * shrink);
                turn = b.rotation() - 90f + (basic.spin != 0f ? Mathf.randomSeed(b.id, 360f) + b.time * basic.spin : 0f) + basic.rotationOffset;
                region = basic.backRegion != null && basic.backRegion.found() ? basic.backRegion : basic.frontRegion;
                if(region == null || !region.found()) continue;
                reach = Math.max(width, height) * 0.75f;
            }else{
                reach = ((LiquidBulletType)type).orbSize;
            }

            // the heights there are under it: on every tile between the shot and where its shadow lies on the ground
            int count = 0;
            if(onto != null){
                float gx = b.x + perX * high, gy = b.y + perY * high;
                int x1 = World.toTile(Math.min(b.x, gx) - reach), x2 = World.toTile(Math.max(b.x, gx) + reach);
                int y1 = World.toTile(Math.min(b.y, gy) - reach), y2 = World.toTile(Math.max(b.y, gy) + reach);
                for(int tx = x1; tx <= x2; tx++){
                    for(int ty = y1; ty <= y2; ty++){
                        int tall = tallAt(tx, ty);
                        boolean known = false;
                        for(int i = 0; i < count; i++) known |= found[i] == tall;
                        if(!known && count < found.length) found[count++] = tall;
                    }
                }
            }else{
                found[count++] = 0;
            }

            for(int i = 0; i < count; i++){
                // how far above that surface the shot is, in world units
                float over = high - found[i] / 255f * Heights.max * Vars.tilesize;
                if(over <= 0f) continue;
                float x = b.x + perX * over, y = b.y + perY * over;
                if(found[i] == 0){
                    Floor floor = Vars.world.floorWorld(x, y);
                    if(floor == null || !floor.canShadow) continue;
                }
                Draw.color(Pal.shadow, Pal.shadow.a * Mathf.clamp(over / fade));
                // the red of the mix colour tells the shader which height this copy is for
                Draw.mixcol(Tmp.c2.set(found[i] / 255f, 0f, 0f, 1f), 0f);
                if(type instanceof LaserBoltBulletType){
                    // as LaserBoltBulletType.draw: a short line
                    Lines.stroke(width);
                    Lines.lineAngleCenter(x, y, b.rotation(), height);
                }else if(region != null){
                    Draw.rect(region, x, y, width, height, turn);
                }else{
                    // as LiquidBulletType.draw: a round blob
                    Fill.circle(x, y, reach);
                }
                drawn++;
            }
        }
        Draw.reset();
        if(onto != null) Draw.shader();
    }
}
