package mshaders;

import arc.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.Turret.*;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.heat.HeatProducer.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.NuclearReactor.*;
import mindustry.world.blocks.power.PowerGenerator.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.GenericCrafter.*;
import mindustry.world.draw.*;

/**
 * Heat haze: the air over hot things shimmers - smelters, kilns and furnaces at work (the ones that are drawn with a
 * flame, an arc or a glow), burning generators, reactors, heaters, and turrets that have just fired (their barrels
 * glow). The places are gathered here (the hottest few in view) and the last step of the frame bends the picture a
 * little around them (mshaders-post.frag, heatWarp).
 */
public class HeatHaze{
    static final int kept = 24;
    private static final float[] uniforms = new float[kept * 4];
    private static int count;
    private static final Seq<Building> hot = new Seq<>();
    /** Per block id: 1 = can be hot, 2 = never; 0 = not looked at yet. */
    private static final IntIntMap kinds = new IntIntMap();
    private static long lastLook = -100;
    private static final Rect view = new Rect();
    private static final FloatSeq found = new FloatSeq();
    /** Hot places of this frame from elsewhere (the air along beams, Beams.java): x, y, reach, how hot. Used up by apply. */
    private static final FloatSeq extras = new FloatSeq();

    static void extra(float x, float y, float reach, float heat){
        if(extras.size < 4 * 64) extras.add(x, y, reach, heat);
    }

    public static void init(){
        Events.on(WorldLoadEvent.class, e -> {
            hot.clear();
            count = 0;
        });
    }

    static boolean on(){
        return Core.settings.getBool(ShaderMod.heatOn, true) && ShaderMod.percent(ShaderMod.heatStrength, 100) > 0f;
    }

    /** Whether buildings of this block ever get hot. */
    static boolean canBeHot(Block block){
        int k = kinds.get(block.id, 0);
        if(k == 0){
            k = hotKind(block) ? 1 : 2;
            kinds.put(block.id, k);
        }
        return k == 1;
    }

    private static boolean hotKind(Block block){
        if(block instanceof mindustry.world.blocks.defense.turrets.Turret) return true;
        if(block instanceof NuclearReactor || block instanceof ImpactReactor || block instanceof HeatProducer) return true;
        if(block instanceof ConsumeGenerator || block instanceof ThermalGenerator || block instanceof HeaterGenerator) return true;
        if(block instanceof GenericCrafter c) return hotDrawer(c.drawer);
        return false;
    }

    private static boolean hotDrawer(DrawBlock d){
        if(d instanceof DrawMulti m){
            for(DrawBlock part : m.drawers) if(hotDrawer(part)) return true;
            return false;
        }
        return d instanceof DrawFlame || d instanceof DrawArcSmelt || d instanceof DrawCrucibleFlame || d instanceof DrawHeatOutput;
    }

    /** How hot this building is now, 0 .. 1. */
    static float heatOf(Building b){
        if(b instanceof TurretBuild t) return Mathf.clamp(t.heat);
        if(b instanceof NuclearReactorBuild r) return Mathf.clamp(Math.max(r.heat, r.productionEfficiency));
        if(b instanceof HeatProducerBuild h) return Mathf.clamp(h.heat / Math.max(((HeatProducer)h.block).heatOutput, 0.001f));
        if(b instanceof GeneratorBuild g) return Mathf.clamp(g.productionEfficiency);
        if(b instanceof GenericCrafterBuild c) return Mathf.clamp(c.warmup);
        return 0f;
    }

    /** For the last step of the frame: u_heats (x, y, reach, how hot), u_heat (strength). */
    static void apply(Shader s){
        float strength = Vars.state.isGame() && on() ? ShaderMod.percent(ShaderMod.heatStrength, 100) : 0f;
        // Far out the shimmer moves the picture by less than a pixel: it cannot be seen, and nothing is worked out
        // (on a large base zoomed out it was a millisecond a frame for nothing).
        float px = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        strength *= 1f - Mathf.clamp((px - 0.6f) / 0.4f);
        count = 0;
        if(strength > 0f){
            Core.camera.bounds(view);
            long frame = Core.graphics.getFrameId();
            if(frame - lastLook >= 10){
                lastLook = frame;
                hot.clear();
                float cx = view.x + view.width / 2f, cy = view.y + view.height / 2f, reach = Mathf.dst(view.width, view.height) / 2f + 32f;
                for(TeamData data : Vars.state.teams.present){
                    Vars.indexer.eachBlock(data.team, cx, cy, reach, b -> canBeHot(b.block), hot::add);
                }
            }
            // the hottest (by heat and size) of them in view; beams first (they are brief and bright)
            found.clear();
            // (only those that reach into view: points of beams just off screen took every slot from what is on it)
            for(int i = 0; i < extras.size; i += 4){
                float ex = extras.items[i], ey = extras.items[i + 1], er = extras.items[i + 2];
                if(!view.overlaps(ex - er, ey - er, er * 2f, er * 2f)) continue;
                found.add(ex, ey, er, extras.items[i + 3] * 4f);
            }
            for(int i = 0; i < hot.size; i++){
                Building b = hot.get(i);
                if(!b.isValid()) continue;
                float h = heatOf(b);
                if(h < 0.03f) continue;
                boolean turret = b instanceof TurretBuild;
                float r = b.block.size * Vars.tilesize * (turret ? 0.75f : 0.95f) + 6f;
                if(!view.overlaps(b.x - r, b.y - r, r * 2f, r * 2f)) continue;
                found.add(b.x, b.y, r, h);
            }
            int n = found.size / 4;
            // pick the largest h * r one after another (few, so a simple selection)
            for(int k = 0; k < Math.min(n, kept); k++){
                int best = -1;
                float bestScore = -1f;
                for(int i = 0; i < n; i++){
                    float score = found.items[i * 4 + 2] * found.items[i * 4 + 3];
                    if(score > bestScore){
                        bestScore = score;
                        best = i;
                    }
                }
                if(best < 0 || bestScore <= 0f) break;
                System.arraycopy(found.items, best * 4, uniforms, count * 4, 4);
                uniforms[count * 4 + 3] = Math.min(uniforms[count * 4 + 3], 1f);
                found.items[best * 4 + 3] = 0f;
                count++;
            }
        }
        extras.clear();
        for(int i = count * 4; i < uniforms.length; i++) uniforms[i] = 0f;
        // nothing hot in view: the shader skips the whole pass
        if(s.hasUniform("u_heat")) s.setUniformf("u_heat", count > 0 ? strength : 0f);
        if(s.hasUniform("u_heats[0]") || s.hasUniform("u_heats")) s.setUniform4fv("u_heats", uniforms, 0, kept * 4);
    }
}
