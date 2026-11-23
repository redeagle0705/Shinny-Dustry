package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.PowerNode.*;

/**
 * Power lines (the lasers between power nodes and what they connect): bright motes of energy run along each line,
 * faster and brighter the better its network is supplied; when it is short of power they come in fits, and they
 * stop when it has none. On a dark map a line gives a little light. Laid over the game's own line, on its layer.
 * The nodes in view are looked up a few times a second.
 */
public class PowerLines{
    private static final Seq<PowerNodeBuild> nodes = new Seq<>();
    private static long lastLook = -100;
    private static final Color core = new Color(), halo = new Color();
    private static final Rect view = new Rect();

    public static void init(){
        Events.run(Trigger.draw, PowerLines::draw);
        Events.on(WorldLoadEvent.class, e -> nodes.clear());
    }

    private static void draw(){
        if(!Core.settings.getBool(ShaderMod.powerOn, true) || Vars.state.isMenu() || Mathf.zero(Renderer.laserOpacity)) return;
        float k = ShaderMod.percent(ShaderMod.powerStrength, 100);
        if(k <= 0f) return;
        Core.camera.bounds(view);
        long frame = Core.graphics.getFrameId();
        if(frame - lastLook >= 10){
            lastLook = frame;
            nodes.clear();
            float cx = view.x + view.width / 2f, cy = view.y + view.height / 2f;
            // a laser reaches at most some 15 tiles (large power nodes, towers): the view and that much around it
            float reach = Mathf.dst(view.width, view.height) / 2f + 130f;
            for(TeamData data : Vars.state.teams.present){
                if(data.team == Team.derelict) continue;
                Vars.indexer.eachBlock(data.team, cx, cy, reach, b -> b instanceof PowerNodeBuild, b -> nodes.add((PowerNodeBuild)b));
            }
        }
        if(nodes.isEmpty()) return;
        Draw.draw(Layer.power + 0.01f, () -> lines(k));
    }

    private static void lines(float k){
        float t = Time.time;
        TextureRegion soft = Core.atlas.find("circle-shadow");
        boolean lit = Vars.state.rules.lighting && Vars.renderer.lights != null;
        for(PowerNodeBuild node : nodes){
            if(!node.isValid() || node.power == null) continue;
            PowerNode type = (PowerNode)node.block;
            float sat = node.power.graph.getSatisfaction();
            if(sat <= 0.001f) continue;
            // short of power: in fits
            float fits = sat >= 0.99f ? 1f : Mathf.clamp(0.35f + 0.65f * sat + 0.5f * Mathf.sin(t * 0.37f + node.id) * (1f - sat));
            for(int i = 0; i < node.power.links.size; i++){
                Building link = Vars.world.build(node.power.links.get(i));
                if(!type.linkValid(node, link)) continue;
                if(link.block instanceof PowerNode && link.id >= node.id) continue;
                float angle = Angles.angle(node.x, node.y, link.x, link.y), vx = Mathf.cosDeg(angle), vy = Mathf.sinDeg(angle);
                float len1 = node.block.size * Vars.tilesize / 2f - 1.5f, len2 = link.block.size * Vars.tilesize / 2f - 1.5f;
                float x1 = node.x + vx * len1, y1 = node.y + vy * len1, x2 = link.x - vx * len2, y2 = link.y - vy * len2;
                float len = Mathf.dst(x1, y1, x2, y2);
                if(len < 2f) continue;
                if(!view.overlaps(Math.min(x1, x2) - 4f, Math.min(y1, y2) - 4f, Math.abs(x2 - x1) + 8f, Math.abs(y2 - y1) + 8f)) continue;
                int style = Core.settings.getInt("mshaders-power-style", 2);
                float strength = Mathf.clamp(fits * Renderer.laserOpacity * 2f) * Math.min(k, 2f);
                halo.set(type.laserColor2).lerp(Color.orange, 0.2f);
                if(style == 2){
                    // a pulse: now and then a soft, long brightening runs along the line from the node out, as
                    // current surging through it (the motes looked odd)
                    float period = 70f + (node.id % 7) * 9f, phase = ((t + node.id * 13f + i * 17f) % period) / period;
                    float head = phase * (len + 30f) - 15f;
                    for(int seg = 0; seg < 5; seg++){
                        float d = head - seg * 4f;
                        if(d < 0f || d > len) continue;
                        float fade = (1f - seg / 5f) * Mathf.clamp(Math.min(d, len - d) / 6f);
                        halo.a = Mathf.clamp(0.42f * fade * strength);
                        Draw.color(halo);
                        if(soft.found()) Draw.rect(soft, x1 + vx * d, y1 + vy * d, 12f, 5f, angle);
                    }
                    // and at the head of the pulse, a tiny arc jumps off the line now and then (the maker: "mix the
                    // small sparks of 3 into 2")
                    if(head > 2f && head < len - 2f){
                        int span = (int)(t / 3f);
                        long seed = node.id * 7919L + i * 104729L + span * 31L;
                        if(Mathf.randomSeed(seed) < 0.5f){
                            float side = (Mathf.randomSeed(seed + 2) < 0.5f ? -1f : 1f) * (1.5f + Mathf.randomSeed(seed + 3, 1.5f));
                            float px = x1 + vx * head, py = y1 + vy * head;
                            core.set(Color.white).a(Mathf.clamp(0.85f * strength));
                            Draw.color(core);
                            Lines.stroke(0.6f);
                            float qx = px - vx * 1.5f - vy * side, qy = py - vy * 1.5f + vx * side;
                            Lines.line(px, py, qx, qy);
                            Lines.line(qx, qy, qx - vx * 2f + vy * side * 0.5f, qy - vy * 2f - vx * side * 0.5f);
                        }
                    }
                    if(lit) Vars.renderer.lights.add((x1 + x2) / 2f, (y1 + y2) / 2f, len / 2f + 10f, type.laserColor2, 0.12f * sat * Math.min(k, 2f));
                    continue;
                }
                if(style == 3){
                    // a glow all along the line that hums, and now and then a tiny arc jumps beside it
                    float hum = 0.75f + 0.25f * Mathf.sin(t * 0.21f + node.id + i);
                    halo.a = Mathf.clamp(0.22f * hum * strength);
                    Draw.color(halo);
                    if(soft.found()) Draw.rect(soft, (x1 + x2) / 2f, (y1 + y2) / 2f, len + 6f, 6f, angle);
                    int span = (int)(t / 4f);
                    long seed = node.id * 7919L + i * 104729L + span * 31L;
                    if(Mathf.randomSeed(seed) < 0.35f){
                        float at = Mathf.randomSeed(seed + 1, len), side = Mathf.randomSeedRange(seed + 2, 2.5f);
                        float px = x1 + vx * at, py = y1 + vy * at;
                        core.set(Color.white).a(Mathf.clamp(0.8f * strength));
                        Draw.color(core);
                        Lines.stroke(0.6f);
                        float qx = px + vx * 2.5f - vy * side, qy = py + vy * 2.5f + vx * side;
                        Lines.line(px, py, qx, qy);
                        Lines.line(qx, qy, qx + vx * 2.5f + vy * side * 0.6f, qy + vy * 2.5f - vx * side * 0.6f);
                    }
                    if(lit) Vars.renderer.lights.add((x1 + x2) / 2f, (y1 + y2) / 2f, len / 2f + 10f, type.laserColor2, 0.12f * sat * Math.min(k, 2f));
                    continue;
                }
                // (in world units: scaled by the laser's own width, a quarter of a unit, they were too small to see)
                float width = 1f;
                // motes: every 14 units, moving at 0.4 .. 0.9 units a tick, from the node out
                float spacing = 18f, speed = 0.4f + 0.5f * sat, shift = (t * speed + node.id * 5.3f + i * 3.1f) % spacing;
                core.set(Color.white);
                halo.set(type.laserColor2).lerp(Color.orange, 0.2f);
                for(float d = shift; d < len; d += spacing){
                    // fading in and out at the ends of the line
                    float edge = Mathf.clamp(Math.min(d, len - d) / 6f);
                    float x = x1 + vx * d, y = y1 + vy * d;
                    // a soft glow and a small bright core (hard discs looked like beads on a string)
                    halo.a = Mathf.clamp(0.55f * edge * fits * Math.min(k, 1.5f) * Renderer.laserOpacity * 2f);
                    Draw.color(halo);
                    if(soft.found()) Draw.rect(soft, x, y, 7f * width, 7f * width);
                    core.a = Mathf.clamp(0.8f * edge * fits * k * Renderer.laserOpacity * 2f);
                    Draw.color(core);
                    Fill.circle(x, y, 0.65f * width);
                }
                if(lit){
                    Vars.renderer.lights.add((x1 + x2) / 2f, (y1 + y2) / 2f, len / 2f + 10f, type.laserColor2, 0.12f * sat * Math.min(k, 2f));
                }
            }
        }
        Draw.reset();
    }
}
