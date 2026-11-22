package mshaders;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.Effect.*;
import mindustry.entities.abilities.*;
import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.graphics.Shaders.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.ForceProjector.*;
import mindustry.world.meta.*;

/**
 * Shields. The game draws every shield of a frame - force projectors, the force fields of units, shield arcs - as a
 * flat shape in its colour into one buffer, and lays that over the screen with Shaders.shield (Renderer.draw): the
 * shape see-through, a line round its edge, stripes drifting across. That shader is swapped for mshaders-shield.frag,
 * which keeps all of it and adds:
 *   bubble   see-through in the middle, thicker and brighter at the edge, a glint where the edge faces the sun
 *   ripples  where a shot is stopped, a ring runs out across the shield (the game only lights the whole shield up)
 * and on a map with lighting, a shield gives light to the ground under it: the game draws shields under the darkness,
 * so on a dark map they were hardly there.
 *
 * Where a shot was stopped is read from the effect the game shows there (Fx.absorb): its drawing is wrapped, and the
 * first time an effect is drawn its place is taken. (Shots stopped out of view make no rings - nor need any.)
 *
 * Turning on and breaking ("when a shield comes on and when it breaks", the maker asked - "glass breaking, or the
 * honeycomb going out cell by cell"):
 *   on      the cells of the honeycomb come on one by one, from the middle out, each with a little flash (the shader:
 *           a shield that has just come on is told to the shader with its place and size)
 *   breaking the game's effect (Fx.shieldBreak, a widening outline) is drawn here instead: the whole shield flashes, then
 *           its cells go out one by one, from the middle out and a little at random, and each one that goes falls apart
 *           into shards of glass that fly outwards and fade
 */
public class Shields{
    /** The places where shots were stopped lately: x, y, the time (Time.time) - as many as the shader takes. */
    private static final int kept = 16;
    private static final float[] hitX = new float[kept], hitY = new float[kept], hitAt = new float[kept];
    private static final int[] hitId = new int[kept];
    private static int next;
    private static final float[] uniforms = new float[kept * 4];

    private static ShieldShader original, ours;

    /** Shields that have just come on: x, y, the time, the full radius. */
    private static final int startsKept = 8;
    private static final float[] startX = new float[startsKept], startY = new float[startsKept], startAt = new float[startsKept], startR = new float[startsKept];
    private static int nextStart;
    private static final float[] startUniforms = new float[startsKept * 4];
    /** Whether each force projector (by building id) and each unit's force field (by unit id) was up when last looked at. */
    private static final IntMap<Boolean> buildsUp = new IntMap<>(), unitsUp = new IntMap<>();

    /** The shields in view this frame, for the waves of light: x, y, radius, a number of its own; at most 8. */
    private static final int inViewKept = 8;
    private static final float[] inView = new float[inViewKept * 4];
    private static int inViewCount;

    /** The side of a cell of the honeycomb, world units: the same as in mshaders-shield.frag (hexLine). */
    static final float cell = 5.2f;

    private static Scene scene;
    private static final Runnable grabBackdrop = () -> {
        if(scene != null) scene.shieldBackdrop();
    };

    public static void init(Scene world){
        scene = world;
        original = Shaders.shield;
        if(original == null) return;   // the game has no animated shields here (it could not load its shader)
        // Shaders.shield has the type ShieldShader, whose constructor loads "shaders/shield.frag" through the file tree:
        // that name points at our file just while the object is built (as ShadowFx does with the darkness)
        String name = "shaders/shield.frag";
        Fi before = Vars.tree.get(name);
        try{
            Vars.tree.addFile(name, Vars.tree.get("shaders/mshaders-shield.frag"));
            ours = new ShieldShader(){
                @Override
                public void apply(){
                    super.apply();
                    boolean on = Core.settings.getBool(ShaderMod.shieldsOn, true);
                    setUniformf("u_bubble", on ? ShaderMod.percent(ShaderMod.shieldsBubble, 100) : 0f);
                    float ripple = on ? ShaderMod.percent(ShaderMod.shieldsRipple, 100) : 0f;
                    setUniformf("u_ripple", ripple);
                    setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
                    for(int i = 0; i < kept; i++){
                        float age = Time.time - hitAt[i];
                        boolean live = ripple > 0f && hitId[i] != 0 && age >= 0f && age < 40f;
                        uniforms[i * 4] = hitX[i];
                        uniforms[i * 4 + 1] = hitY[i];
                        uniforms[i * 4 + 2] = age;
                        uniforms[i * 4 + 3] = live ? 1f : 0f;
                    }
                    if(hasUniform("u_hits[0]") || hasUniform("u_hits")) setUniform4fv("u_hits", uniforms, 0, kept * 4);
                    setUniformf("u_hex", on ? ShaderMod.percent(ShaderMod.shieldsHex, 100) : 0f);
                    // the refraction: the world as it was right before the shields, on texture unit 3
                    Texture back = on && scene != null ? scene.backdropNow() : null;
                    float refract = back != null ? ShaderMod.percent(ShaderMod.shieldsRefract, 100) : 0f;
                    if(back != null){
                        back.bind(3);
                        Gl.activeTexture(Gl.texture0);
                    }
                    setUniformi("u_backdrop", 3);
                    setUniformf("u_refract", refract);
                    boolean turns = on && Core.settings.getBool(ShaderMod.shieldsTurns, true);
                    for(int i = 0; i < startsKept; i++){
                        float age = Time.time - startAt[i];
                        boolean live = turns && startR[i] > 0f && age >= 0f && age < 60f;
                        startUniforms[i * 4] = startX[i];
                        startUniforms[i * 4 + 1] = startY[i];
                        startUniforms[i * 4 + 2] = age;
                        startUniforms[i * 4 + 3] = live ? startR[i] : 0f;
                    }
                    if(hasUniform("u_starts[0]") || hasUniform("u_starts")) setUniform4fv("u_starts", startUniforms, 0, startsKept * 4);
                    setUniformf("u_sheen", on ? ShaderMod.percent(ShaderMod.shieldsSheen, 100) : 0f);
                    for(int i = inViewCount * 4; i < inView.length; i++) inView[i] = 0f;
                    if(hasUniform("u_shields[0]") || hasUniform("u_shields")) setUniform4fv("u_shields", inView, 0, inViewKept * 4);
                }
            };
        }catch(Throwable t){
            Log.err("[mshaders] shield shader unavailable, shields stay as the game draws them", t);
            ours = null;
        }finally{
            Vars.tree.addFile(name, before);
        }
        if(ours == null) return;

        // where shots are stopped: the effect the game shows there
        Cons<EffectContainer> draw = Fx.absorb.renderer;
        Fx.absorb.renderer = e -> {
            struck(e);
            draw.get(e);
        };

        // how shields break: drawn here instead of the game's widening outline (while this is on)
        Cons<EffectContainer> breakDraw = Fx.shieldBreak.renderer;
        float breakLife = Fx.shieldBreak.lifetime;
        Fx.shieldBreak.renderer = e -> {
            if(turnsOn() && Vars.renderer.animateShields) shatter(e);
            else breakDraw.get(e);
        };

        Events.run(Trigger.preDraw, () -> {
            boolean on = Core.settings.getBool(ShaderMod.shieldsOn, true);
            Shaders.shield = on ? ours : original;
            Fx.shieldBreak.lifetime = turnsOn() ? 60f : breakLife;
        });
        Events.run(Trigger.draw, () -> {
            findInView();
            lights();
            if(turnsOn()) watchStarts();
            // the shields are drawn into their buffer from Layer.shields - 1 (Draw.drawRange): just before that, the
            // world as it stands is copied for the refraction - only while some shield is in view
            if(Core.settings.getBool(ShaderMod.shieldsOn, true) && ShaderMod.percent(ShaderMod.shieldsRefract, 100) > 0f && anyInView()) Draw.draw(Layer.shields - 1.01f, grabBackdrop);
        });
    }

    private static void struck(EffectContainer e){
        if(e.id == 0) return;
        for(int i = 0; i < kept; i++){
            if(hitId[i] == e.id) return;
        }
        hit(e.id, e.x, e.y, Time.time - e.time);
    }

    private static void hit(int id, float x, float y, float at){
        hitId[next] = id;
        hitX[next] = x;
        hitY[next] = y;
        hitAt[next] = at;
        next = (next + 1) % kept;
    }

    private static int tests;

    /** For the test kit: a shot stopped at that place at that time (Time.time), as if the game had shown its effect. */
    public static void testHit(float x, float y, float at){
        hit(-1 - tests++, x, y, at);
    }

    /** The shields in view, for the waves of light (u_shields): force projectors, then units with a force field. */
    private static void findInView(){
        inViewCount = 0;
        if(!Core.settings.getBool(ShaderMod.shieldsOn, true) || ShaderMod.percent(ShaderMod.shieldsSheen, 100) <= 0f) return;
        Rect view = Core.camera.bounds(Tmp.r3);
        for(TeamData data : Vars.state.teams.present){
            for(var build : Vars.indexer.getFlagged(data.team, BlockFlag.shield)){
                if(!(build instanceof ForceBuild force) || force.broken) continue;
                float radius = force.realRadius();
                if(radius >= 1f && view.overlaps(force.x - radius, force.y - radius, radius * 2f, radius * 2f)) note(force.x, force.y, radius, force.pos());
            }
        }
        for(Unit unit : Groups.unit){
            if(unit.shield <= 0f || unit.abilities.length == 0) continue;
            for(Ability ability : unit.abilities){
                if(ability instanceof ForceFieldAbility field && view.overlaps(unit.x - field.radius, unit.y - field.radius, field.radius * 2f, field.radius * 2f)){
                    note(unit.x, unit.y, field.radius, unit.id);
                    break;
                }
            }
        }
    }

    private static void note(float x, float y, float radius, int id){
        if(inViewCount >= inViewKept) return;
        int i = inViewCount++ * 4;
        inView[i] = x;
        inView[i + 1] = y;
        inView[i + 2] = radius;
        // a number of its own between 1 and 2 (0 = not in use): which way its waves go and when they come
        inView[i + 3] = 1f + Mathf.randomSeed(id * 7919L + 13L);
    }

    private static boolean turnsOn(){
        return Shaders.shield == ours && ours != null && Core.settings.getBool(ShaderMod.shieldsOn, true) && Core.settings.getBool(ShaderMod.shieldsTurns, true);
    }

    private static void started(float x, float y, float radius){
        startX[nextStart] = x;
        startY[nextStart] = y;
        startAt[nextStart] = Time.time;
        startR[nextStart] = radius;
        nextStart = (nextStart + 1) % startsKept;
    }

    /**
     * Looks for shields that have just come on: a force projector whose shield is drawn again (after it broke, or got
     * power), a unit whose force field is back. One seen for the first time is only noted.
     */
    private static void watchStarts(){
        Rect view = Core.camera.bounds(Tmp.r2);
        if(buildsUp.size > 4000) buildsUp.clear();
        if(unitsUp.size > 4000) unitsUp.clear();
        for(TeamData data : Vars.state.teams.present){
            for(var build : Vars.indexer.getFlagged(data.team, BlockFlag.shield)){
                if(!(build instanceof ForceBuild force) || !(force.block instanceof ForceProjector type)) continue;
                boolean up = !force.broken && force.realRadius() > 1f;
                Boolean was = buildsUp.get(force.id);
                buildsUp.put(force.id, up);
                float full = type.radius + type.phaseRadiusBoost * force.phaseHeat;
                if(was != null && !was && up && view.overlaps(force.x - full, force.y - full, full * 2f, full * 2f)) started(force.x, force.y, full);
            }
        }
        for(Unit unit : Groups.unit){
            if(unit.abilities.length == 0) continue;
            for(Ability ability : unit.abilities){
                if(!(ability instanceof ForceFieldAbility field)) continue;
                boolean up = unit.shield > 0f;
                Boolean was = unitsUp.get(unit.id);
                unitsUp.put(unit.id, up);
                if(was != null && !was && up && view.overlaps(unit.x - field.radius, unit.y - field.radius, field.radius * 2f, field.radius * 2f)) started(unit.x, unit.y, field.radius);
                break;
            }
        }
    }

    private static final Color shardColor = new Color(), cellColor = new Color();

    /**
     * A shield breaking (in place of Fx.shieldBreak): its cells of the honeycomb, drawn into the shields' buffer so the
     * shader lays them over the screen like the shield itself, go out one by one; each one that goes falls apart into
     * shards that fly outwards.
     */
    private static void shatter(EffectContainer e){
        int sides = e.data instanceof ForceProjector f ? f.sides : e.data instanceof ForceFieldAbility a ? a.sides : 6;
        float turn = e.data instanceof ForceProjector f ? f.shieldRotation : e.data instanceof ForceFieldAbility a ? a.rotation : 6f;
        float radius = e.rotation, fin = e.fin();
        if(radius < 2f) return;
        // larger cells for a very large shield, so it stays a few hundred
        float side = cell * Math.max(1f, (float)Math.floor(radius / 110f) + 1f);
        float dx = side, dy = side * 1.7320508f;
        int x0 = (int)Math.floor((e.x - radius) / dx) - 1, x1 = (int)Math.ceil((e.x + radius) / dx) + 1;
        int y0 = (int)Math.floor((e.y - radius) / dy) - 1, y1 = (int)Math.ceil((e.y + radius) / dy) + 1;
        float hexRadius = side * 0.57735f;
        float flash = Mathf.clamp(1f - fin / 0.12f);
        for(int lattice = 0; lattice < 2; lattice++){
            float off = lattice * 0.5f;
            for(int i = x0; i <= x1; i++){
                for(int j = y0; j <= y1; j++){
                    float cx = (i + off) * dx, cy = (j + off) * dy;
                    if(!Intersector.isInRegularPolygon(sides, e.x, e.y, radius, turn, cx, cy)) continue;
                    long seed = (long)i * 73856093L ^ (long)j * 19349663L ^ lattice * 83492791L;
                    float h = Mathf.randomSeed(seed), dist = Mathf.dst(cx, cy, e.x, e.y) / radius;
                    // when this cell goes, as a share of the effect's life: after the flash, the middle first
                    float goes = 0.12f + 0.40f * (0.55f * dist + 0.45f * h);
                    if(fin < goes){
                        // still there: in the shield's colour, white in the flash and just before it goes
                        float blink = Mathf.clamp(1f - (goes - fin) / 0.05f);
                        cellColor.set(e.color).lerp(Color.white, Math.max(flash, blink) * 0.8f);
                        Draw.z(Layer.shields);
                        Draw.color(cellColor);
                        // a hair larger than the cell: cells side by side make one whole, so the shader draws its
                        // edge line only round the holes (with a gap between them, a line ran round every cell)
                        Fill.poly(cx, cy, 6, hexRadius * 1.04f, 90f);
                    }else{
                        // shards: from about every third cell, one or two pieces of glass larger than the cell,
                        // flying outwards from the middle, turning, fading (from every cell, two or three pieces as
                        // small as the cell looked like dust)
                        if(Mathf.randomSeed(seed + 11L) > 0.36f) continue;
                        float t = (fin - goes) / 0.42f;
                        if(t >= 1f) continue;
                        float away = Angles.angle(e.x, e.y, cx, cy);
                        int pieces = h < 0.6f ? 1 : 2;
                        Draw.z(Layer.effect + 0.5f);
                        for(int k = 0; k < pieces; k++){
                            float r1 = Mathf.randomSeed(seed + k * 7L + 1L), r2 = Mathf.randomSeed(seed + k * 7L + 2L);
                            float dir = away + (r1 - 0.5f) * 60f, speed = 6f + 12f * r2;
                            float ease = 1f - (1f - t) * (1f - t);
                            float sx = cx + Mathf.cosDeg(dir) * speed * ease, sy = cy + Mathf.sinDeg(dir) * speed * ease;
                            float spin = r1 * 360f + t * (r2 - 0.5f) * 400f, size = hexRadius * (1.1f + 0.7f * r2) * (1f - 0.35f * t);
                            shardColor.set(e.color).lerp(Color.white, 0.35f + 0.3f * r1);
                            shardColor.a = (1f - t) * 0.85f;
                            Draw.color(shardColor);
                            Fill.tri(sx + Angles.trnsx(spin, size), sy + Angles.trnsy(spin, size),
                                sx + Angles.trnsx(spin + 130f, size * 0.7f), sy + Angles.trnsy(spin + 130f, size * 0.7f),
                                sx + Angles.trnsx(spin + 235f, size * 0.85f), sy + Angles.trnsy(spin + 235f, size * 0.85f));
                        }
                    }
                }
            }
        }
        Draw.color();
        Draw.z(Layer.effect);
    }

    /** Is any shield in view? (Force projectors and units with a force field; the shield arcs are not looked for.) */
    private static boolean anyInView(){
        Rect view = Core.camera.bounds(Tmp.r1);
        for(TeamData data : Vars.state.teams.present){
            for(var build : Vars.indexer.getFlagged(data.team, BlockFlag.shield)){
                if(!(build instanceof ForceBuild force) || force.broken) continue;
                float radius = force.realRadius();
                if(radius >= 1f && view.overlaps(force.x - radius, force.y - radius, radius * 2f, radius * 2f)) return true;
            }
        }
        for(Unit unit : Groups.unit){
            if(unit.shield <= 0f || unit.abilities.length == 0) continue;
            for(Ability ability : unit.abilities){
                if(ability instanceof ForceFieldAbility field && view.overlaps(unit.x - field.radius, unit.y - field.radius, field.radius * 2f, field.radius * 2f)) return true;
            }
        }
        return false;
    }

    /** On a map with lighting: the shields in view give light to what is under them. */
    private static void lights(){
        if(!Vars.state.rules.lighting || Vars.renderer.lights == null || !Core.settings.getBool(ShaderMod.shieldsOn, true)) return;
        float strength = ShaderMod.percent(ShaderMod.shieldsLight, 100);
        if(strength <= 0f) return;
        Rect view = Core.camera.bounds(Tmp.r1);
        for(TeamData data : Vars.state.teams.present){
            for(var build : Vars.indexer.getFlagged(data.team, BlockFlag.shield)){
                if(!(build instanceof ForceBuild force) || force.broken) continue;
                float radius = force.realRadius();
                if(radius < 1f || !view.overlaps(force.x - radius, force.y - radius, radius * 2f, radius * 2f)) continue;
                glow(force.x, force.y, radius, force.team.color, force.hit, strength);
            }
        }
        for(Unit unit : Groups.unit){
            if(unit.shield <= 0f || unit.abilities.length == 0) continue;
            for(Ability ability : unit.abilities){
                if(ability instanceof ForceFieldAbility field){
                    float radius = field.radius;
                    if(!view.overlaps(unit.x - radius, unit.y - radius, radius * 2f, radius * 2f)) continue;
                    glow(unit.x, unit.y, radius, unit.team.color, 0f, strength);
                }
            }
        }
    }

    private static final Color tint = new Color();

    private static void glow(float x, float y, float radius, Color team, float hit, float strength){
        tint.set(team).lerp(Color.white, Mathf.clamp(hit) * 0.5f);
        float opacity = Mathf.clamp((0.35f + 0.35f * Mathf.clamp(hit)) * strength);
        // from the dome, not from a point on the ground: high enough that the blocks under it throw no long shadows
        if(Vars.renderer.lights instanceof LightTap tap) tap.addAt(x, y, radius * 1.25f, tint, opacity, Math.min(radius * 0.5f, 48f));
        else Vars.renderer.lights.add(x, y, radius * 1.25f, tint, opacity);
    }
}
