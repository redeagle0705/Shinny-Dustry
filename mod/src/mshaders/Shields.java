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
    /** How much of each of those shields is used up: 0 = whole, 1 = about to break (for the cracks). */
    private static final float[] hurt = new float[inViewKept];
    /** The shape of each: how many sides, and the angle of its first corner (degrees), as the game draws it (Fill.poly). */
    private static final float[] shapes = new float[inViewKept * 2];
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
                    for(int i = inViewCount; i < hurt.length; i++) hurt[i] = 0f;
                    setUniformf("u_crack", on ? ShaderMod.percent(ShaderMod.shieldsCrack, 100) : 0f);
                    if(hasUniform("u_hurt[0]") || hasUniform("u_hurt")) setUniform1fv("u_hurt", hurt, 0, inViewKept);
                    if(hasUniform("u_sides[0]") || hasUniform("u_sides")) setUniform2fv("u_sides", shapes, 0, inViewKept * 2);
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

    /**
     * The shields in view, for the waves of light and the cracks (u_shields, u_hurt): force projectors, then units with
     * a force field. How much of a shield is used up: a projector's buildup out of what it holds, a unit's shield out
     * of its most.
     */
    private static void findInView(){
        inViewCount = 0;
        if(!Core.settings.getBool(ShaderMod.shieldsOn, true)
            || (ShaderMod.percent(ShaderMod.shieldsSheen, 100) <= 0f && ShaderMod.percent(ShaderMod.shieldsCrack, 100) <= 0f
                && ShaderMod.percent(ShaderMod.shieldsBubble, 100) <= 0f)) return;
        Rect view = Core.camera.bounds(Tmp.r3);
        for(TeamData data : Vars.state.teams.present){
            for(var build : Vars.indexer.getFlagged(data.team, BlockFlag.shield)){
                if(!(build instanceof ForceBuild force) || force.broken) continue;
                float radius = force.realRadius();
                if(radius >= 1f && view.overlaps(force.x - radius, force.y - radius, radius * 2f, radius * 2f) && force.block instanceof ForceProjector type){
                    shape(type.sides, type.shieldRotation);
                    note(force.x, force.y, radius, force.pos(), force.buildup / Math.max(type.shieldHealth + type.phaseShieldBoost * force.phaseHeat, 1f));
                }
            }
        }
        for(Unit unit : Groups.unit){
            if(unit.shield <= 0f || unit.abilities.length == 0) continue;
            for(Ability ability : unit.abilities){
                if(ability instanceof ForceFieldAbility field && view.overlaps(unit.x - field.radius, unit.y - field.radius, field.radius * 2f, field.radius * 2f)){
                    shape(field.sides, field.rotation);
                    note(unit.x, unit.y, field.radius, unit.id, 1f - unit.shield / Math.max(field.scaledMax(unit), 1f));
                    break;
                }
            }
        }
    }

    private static void shape(int sides, float rotation){
        if(inViewCount >= inViewKept) return;
        shapes[inViewCount * 2] = sides;
        shapes[inViewCount * 2 + 1] = rotation;
    }

    private static void note(float x, float y, float radius, int id, float used){
        if(inViewCount >= inViewKept) return;
        hurt[inViewCount] = Mathf.clamp(used);
        int i = inViewCount++ * 4;
        inView[i] = x;
        inView[i + 1] = y;
        inView[i + 2] = radius;
        // a number of its own between 1 and 2 (0 = not in use): which way its waves go and when they come
        inView[i + 3] = 1f + crackSeed(id);
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

    private static final Color shardColor = new Color(), cellColor = new Color(), edgeColor = new Color();
    /** The side of a piece of a breaking shield, world units: as large as the broad cracks (mshaders-shield.frag, crackCells). */
    static final float piece = 16f;
    private static final FloatSeq poly = new FloatSeq(), clipped = new FloatSeq();

    /** Cuts the polygon in "poly" down to where a * x + b * y <= c (Sutherland-Hodgman, one edge). */
    private static void clip(float a, float b, float c){
        clipped.clear();
        int n = poly.size / 2;
        for(int i = 0; i < n; i++){
            float x1 = poly.items[i * 2], y1 = poly.items[i * 2 + 1];
            float x2 = poly.items[((i + 1) % n) * 2], y2 = poly.items[((i + 1) % n) * 2 + 1];
            float d1 = a * x1 + b * y1 - c, d2 = a * x2 + b * y2 - c;
            if(d1 <= 0f) clipped.add(x1, y1);
            if((d1 < 0f) != (d2 < 0f)){
                float t = d1 / (d1 - d2);
                clipped.add(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
            }
        }
        poly.clear();
        poly.addAll(clipped);
    }

    private static float fract(float x){
        return x - (float)Math.floor(x);
    }

    private static float hashX, hashY;

    /** crackHash of mshaders-shield.frag, in floats as the graphics card does it: the first two numbers into hashX, hashY. */
    private static void crackHash(float px, float py, float pz){
        float x = fract(px * 0.1031f), y = fract(py * 0.1030f), z = fract(pz * 0.0973f);
        float d = x * (y + 33.33f) + y * (x + 33.33f) + z * (z + 33.33f);
        x += d;
        y += d;
        z += d;
        hashX = fract((x + y) * z);
        hashY = fract((x + x) * y);
    }

    /** The shield's own number as the shader gets it (u_shields: 1 + it, less 1 there): which way its cracks run. */
    static float crackSeed(int id){
        return (1f + Mathf.randomSeed(id * 7919L + 13L)) - 1f;
    }

    /** The seed of the shield that broke with this effect, found the first time it is drawn. */
    private static final IntFloatMap breakSeeds = new IntFloatMap();
    private static float crackSeed;

    private static float seedOf(EffectContainer e){
        if(breakSeeds.containsKey(e.id)) return breakSeeds.get(e.id, 0f);
        if(breakSeeds.size > 200) breakSeeds.clear();
        int id = -1;
        if(e.data instanceof ForceProjector){
            var build = Vars.world.buildWorld(e.x, e.y);
            if(build != null) id = build.pos();
        }else{
            for(Unit unit : Groups.unit){
                if(unit.within(e.x, e.y, 6f)){
                    for(Ability ability : unit.abilities){
                        if(ability instanceof ForceFieldAbility) id = unit.id;
                    }
                    if(id >= 0) break;
                }
            }
        }
        float seed = id >= 0 ? crackSeed(id) : Mathf.randomSeed(e.id);
        breakSeeds.put(e.id, seed);
        return seed;
    }

    /** Where the point of the piece at (i, j) of a breaking shield lies: as the shader's cracks have it (crackCells). */
    private static float siteX(float cx, int i, int j, long salt){
        crackHash(i, j, crackSeed * 97f);
        return cx + (i + hashX) * piece;
    }

    private static float siteY(float cy, int i, int j, long salt){
        crackHash(i, j, crackSeed * 97f);
        return cy + (j + hashY) * piece;
    }

    /**
     * A shield breaking (in place of Fx.shieldBreak). It falls apart along cracks into pieces of glass as large as the
     * broad cracks a worn shield shows (the cells of scattered points, cut to the shield's polygon). First the whole
     * shield flashes, already cut by thin cracks (the pieces are drawn into the shields' buffer a little apart, and the
     * shader draws its edge line round each); then the pieces come loose one by one, the middle first, and fly outwards
     * as panes of glass - turning, shrinking a little, fading. (Before, the honeycomb cells went out and only a third of
     * them threw small shards: "the shards should be about as big as the cracks", a player said.)
     */
    private static void shatter(EffectContainer e){
        int sides = e.data instanceof ForceProjector f ? f.sides : e.data instanceof ForceFieldAbility a ? a.sides : 6;
        float turn = e.data instanceof ForceProjector f ? f.shieldRotation : e.data instanceof ForceFieldAbility a ? a.rotation : 6f;
        float radius = e.rotation, fin = e.fin();
        if(radius < 2f) return;
        long salt = e.id * 977L;
        // the same cracks the worn shield showed (mshaders-shield.frag): it breaks along them
        crackSeed = seedOf(e);
        float flash = Mathf.clamp(1f - fin / 0.10f);
        int span = (int)Math.ceil(radius / piece) + 1;
        for(int i = -span; i < span; i++){
            for(int j = -span; j < span; j++){
                float sx = siteX(e.x, i, j, salt), sy = siteY(e.y, i, j, salt);
                if(Mathf.dst(sx, sy, e.x, e.y) > radius + piece) continue;
                // the piece: the square around its point, cut by the halfway lines to the points around, then by the
                // shield's polygon
                poly.clear();
                poly.add(sx - piece * 2f, sy - piece * 2f, sx + piece * 2f, sy - piece * 2f);
                poly.add(sx + piece * 2f, sy + piece * 2f, sx - piece * 2f, sy + piece * 2f);
                for(int di = -2; di <= 2 && poly.size >= 6; di++){
                    for(int dj = -2; dj <= 2 && poly.size >= 6; dj++){
                        if(di == 0 && dj == 0) continue;
                        float nx = siteX(e.x, i + di, j + dj, salt), ny = siteY(e.y, i + di, j + dj, salt);
                        float a = nx - sx, b = ny - sy;
                        clip(a, b, a * (sx + nx) / 2f + b * (sy + ny) / 2f);
                    }
                }
                for(int k = 0; k < sides && poly.size >= 6; k++){
                    float a0 = turn + 360f * k / sides, a1 = turn + 360f * (k + 1) / sides;
                    float v0x = e.x + Mathf.cosDeg(a0) * radius, v0y = e.y + Mathf.sinDeg(a0) * radius;
                    float ex = Mathf.cosDeg(a1) * radius - Mathf.cosDeg(a0) * radius, ey = Mathf.sinDeg(a1) * radius - Mathf.sinDeg(a0) * radius;
                    clip(ey, -ex, ey * v0x - ex * v0y);
                }
                int n = poly.size / 2;
                if(n < 3) continue;
                float gx = 0f, gy = 0f;
                for(int k = 0; k < n; k++){
                    gx += poly.items[k * 2];
                    gy += poly.items[k * 2 + 1];
                }
                gx /= n;
                gy /= n;

                long seed = salt ^ (long)i * 19349663L ^ (long)j * 73856093L;
                float h = Mathf.randomSeed(seed), r1 = Mathf.randomSeed(seed + 1L), r2 = Mathf.randomSeed(seed + 2L);
                float dist = Mathf.dst(gx, gy, e.x, e.y) / radius;
                // when this piece comes loose: after the flash, the middle first
                float goes = 0.10f + 0.30f * (0.5f * dist + 0.5f * h);
                if(fin < goes){
                    // still in place, a little apart from its neighbours: white in the flash and just before it goes
                    float blink = Mathf.clamp(1f - (goes - fin) / 0.05f);
                    cellColor.set(e.color).lerp(Color.white, Math.max(flash, blink) * 0.8f);
                    Draw.z(Layer.shields);
                    Draw.color(cellColor);
                    drawPiece(gx, gy, 0f, 0f, 0f, 1f, 0.7f, n, true);
                }else{
                    float t = (fin - goes) / 0.55f;
                    if(t >= 1f) continue;
                    float ease = 1f - (1f - t) * (1f - t);
                    float away = Angles.angle(e.x, e.y, gx, gy) + (r1 - 0.5f) * 40f, far = (8f + 18f * r2) * ease;
                    float ox = Mathf.cosDeg(away) * far, oy = Mathf.sinDeg(away) * far;
                    float spin = (r1 - 0.5f) * 140f * ease, scale = 1f - 0.25f * t;
                    Draw.z(Layer.effect + 0.5f);
                    // a pane of glass: faintly tinted, with a bright edge
                    shardColor.set(e.color).lerp(Color.white, 0.4f);
                    shardColor.a = 0.38f * (1f - t);
                    Draw.color(shardColor);
                    drawPiece(gx, gy, ox, oy, spin, scale, 0f, n, true);
                    edgeColor.set(e.color).lerp(Color.white, 0.7f);
                    edgeColor.a = 0.9f * (1f - t);
                    Draw.color(edgeColor);
                    Lines.stroke(1.1f);
                    drawPiece(gx, gy, ox, oy, spin, scale, 0f, n, false);
                }
            }
        }
        Draw.color();
        Draw.z(Layer.effect);
    }

    /** The piece in "poly" (n corners, middle gx, gy): moved, turned about its middle, scaled, pulled in by "inset" world units; filled or as an outline. */
    private static void drawPiece(float gx, float gy, float ox, float oy, float spin, float scale, float inset, int n, boolean fill){
        float cos = Mathf.cosDeg(spin), sin = Mathf.sinDeg(spin);
        float px = 0f, py = 0f, fx = 0f, fy = 0f;
        for(int k = 0; k <= n; k++){
            int q = k % n;
            float rx = poly.items[q * 2] - gx, ry = poly.items[q * 2 + 1] - gy;
            float len = (float)Math.sqrt(rx * rx + ry * ry);
            float shrink = len > 0.001f ? Math.max(len - inset, 0f) / len * scale : scale;
            rx *= shrink;
            ry *= shrink;
            float x = gx + ox + rx * cos - ry * sin, y = gy + oy + rx * sin + ry * cos;
            if(k > 0){
                if(fill) Fill.tri(gx + ox, gy + oy, px, py, x, y);
                else Lines.line(px, py, x, y);
            }else{
                fx = x;
                fy = y;
            }
            px = x;
            py = y;
        }
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
