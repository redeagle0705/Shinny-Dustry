package mshaders;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import mindustry.graphics.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.entities.*;
import mindustry.entities.Effect.*;

/**
 * Explosions. The game shows an explosion with an effect from Fx (flakExplosion, blastExplosion, massiveExplosion,
 * dynamicExplosion for a building or unit that is destroyed, reactorExplosion ...). Their drawing is wrapped: the first
 * time such an effect is drawn, its place and size are taken, and the last step of the frame (mshaders-post.frag) bends
 * the picture in a ring that runs out from there - a shock wave.
 * (Explosions out of view make no wave - nor need any.)
 *
 * Scorch marks: the game leaves one where a unit dies or a generator blows up, none where a shell bursts. Every burst on
 * the ground (not the flak that bursts in the air) leaves a dark mark here, with a few streaks thrown outwards, that
 * fades over half a minute or so.
 *
 * And a flash: for a moment the explosion is a strong light on the ground (LightTap), so that the blocks around it are
 * lit on the side that faces it and throw their shadows away from it, on a bright map as on a dark one.
 */
public class Blasts{
    /** The explosions of late: x, y, the time (Time.time), how far the wave runs (world units). */
    private static final int kept = 16;
    private static final float[] blastX = new float[kept], blastY = new float[kept], blastAt = new float[kept], blastR = new float[kept];
    private static final int[] blastId = new int[kept];
    private static int next, tests;
    private static final float[] uniforms = new float[kept * 4];

    /** How far the wave of each explosion runs, by the name of its effect in Fx; others named "...xplosion" get a third of how far they are drawn. */
    private static final ObjectFloatMap<String> reach = new ObjectFloatMap<>();
    static{
        reach.put("flakExplosion", 16f);
        reach.put("titanExplosionFrag", 16f);
        reach.put("plasticExplosion", 24f);
        reach.put("plasticExplosionFlak", 24f);
        reach.put("blastExplosion", 26f);
        reach.put("sapExplosion", 24f);
        reach.put("explosion", 30f);
        reach.put("massiveExplosion", 40f);
        reach.put("flakExplosionBig", 40f);
        reach.put("sparkExplosion", 40f);
        reach.put("titanExplosionSmall", 36f);
        reach.put("scatheExplosionSmall", 40f);
        reach.put("titanExplosion", 56f);
        reach.put("scatheExplosion", 56f);
        reach.put("titanExplosionLarge", 72f);
        reach.put("coreExplosion", 100f);
        reach.put("reactorExplosion", 170f);
        reach.put("impactReactorExplosion", 210f);
    }

    private static final Color flashColor = new Color(1f, 0.86f, 0.62f), tint = new Color();

    /** Scorch marks: x, y, the time, size (world units); more than the waves, as they last longer. */
    private static final int marksKept = 96;
    private static final float[] markX = new float[marksKept], markY = new float[marksKept], markAt = new float[marksKept], markR = new float[marksKept];
    private static int nextMark;
    /** How long a mark lasts, ticks: the setting, in seconds (5 by default - in a long battle they pile up). */
    private static float markLife(){
        return 60f * Math.max(Core.settings.getInt(ShaderMod.blastsScorchTime, 5), 1);
    }

    /**
     * Shells that burst where they come down: the bullets in flight that hit the ground with a blast (splash damage),
     * by id - where each was last seen and how wide its blast is. When one is gone, it burst there. (Artillery - the
     * shells of ripples, hails, howitzers - shows its burst with the effect Fx.flakExplosion, the same as flak bursting
     * in the air, so the effect alone cannot tell them apart: "the turrets' shots too", the maker asked.)
     */
    private static final IntMap<float[]> shells = new IntMap<>();
    private static final IntSet seenShells = new IntSet();

    private static void watchShells(){
        if(Vars.state.isPaused() || !Core.settings.getBool(ShaderMod.blastsOn, true) || ShaderMod.percent(ShaderMod.blastsScorch, 100) <= 0f){
            if(!Vars.state.isPaused()) shells.clear();
            return;
        }
        seenShells.clear();
        for(mindustry.gen.Bullet b : mindustry.gen.Groups.bullet){
            var type = b.type;
            if(type == null || !type.collidesGround || type.splashDamageRadius <= 0f || type instanceof mindustry.entities.bullet.FlakBulletType) continue;
            seenShells.add(b.id);
            float[] at = shells.get(b.id);
            if(at == null) shells.put(b.id, at = new float[3]);
            at[0] = b.x;
            at[1] = b.y;
            at[2] = b.absorbed ? -1f : type.splashDamageRadius;
        }
        for(var it = shells.entries().iterator(); it.hasNext();){
            var entry = it.next();
            if(seenShells.contains(entry.key)) continue;
            float[] at = entry.value;
            // (stopped by a shield: no mark)
            if(at[2] > 0f) mark(at[0], at[1], Time.time, at[2] * 1.3f);
            it.remove();
        }
    }

    public static int install(){
        Events.run(Trigger.update, Blasts::watchShells);
        Events.run(Trigger.draw, Blasts::flashes);
        Events.run(Trigger.draw, Blasts::scorches);
        int wrapped = 0;
        for(java.lang.reflect.Field field : Fx.class.getFields()){
            if(field.getType() != Effect.class || !field.getName().toLowerCase().contains("xplosion")) continue;
            try{
                Effect effect = (Effect)field.get(null);
                if(effect == null) continue;
                String name = field.getName();
                boolean dynamic = name.equals("dynamicExplosion");
                // flak bursts in the air: no mark on the ground
                boolean marks = !name.toLowerCase().contains("flak");
                float far = reach.get(name, Mathf.clamp(effect.clip * 0.3f, 14f, 120f));
                Cons<EffectContainer> draw = effect.renderer;
                effect.renderer = e -> {
                    // the size of a dynamic explosion is in its rotation (how strong it is, about 1 .. 8)
                    if(e.id != 0) seen(e, dynamic ? Mathf.clamp(e.rotation * 14f, 18f, 140f) : far, marks);
                    draw.get(e);
                };
                wrapped++;
            }catch(Throwable t){
                Log.err("[mshaders] explosion effect " + field.getName() + " left as it is", t);
            }
        }
        return wrapped;
    }

    private static void seen(EffectContainer e, float far, boolean marks){
        for(int i = 0; i < kept; i++){
            if(blastId[i] == e.id) return;
        }
        add(e.id, e.x, e.y, Time.time - e.time, far);
        if(marks) mark(e.x, e.y, Time.time - e.time, far);
    }

    private static void mark(float x, float y, float at, float far){
        // only on ground that takes it (not on water or deep liquid, as the game's own decals)
        var tile = Vars.world.tileWorld(x, y);
        if(tile == null || !tile.floor().hasSurface()) return;
        markX[nextMark] = x;
        markY[nextMark] = y;
        markAt[nextMark] = at;
        float r = Math.min(far * 0.45f + 4f, 60f);
        // one burst seen twice (by its effect and by its shell): one mark, the larger
        for(int i = 0; i < marksKept; i++){
            if(markR[i] > 0f && Math.abs(markAt[i] - at) < 12f && Mathf.within(markX[i], markY[i], x, y, Math.max(r, markR[i]) * 0.5f)){
                markR[i] = Math.max(markR[i], r);
                return;
            }
        }
        markR[nextMark] = r;
        nextMark = (nextMark + 1) % marksKept;
    }

    private static final Color soot = new Color();

    /**
     * The scorch marks in view, on the layer of the game's own scorch marks (over the ground, under the buildings): a
     * soft dark patch, darker in the middle, and streaks of soot thrown outwards, each mark its own; they fade out.
     */
    private static void scorches(){
        if(!Core.settings.getBool(ShaderMod.blastsOn, true)) return;
        float strength = ShaderMod.percent(ShaderMod.blastsScorch, 100);
        if(strength <= 0f) return;
        TextureRegion soft = Core.atlas.find("circle-shadow");
        if(!soft.found()) return;
        Rect view = Core.camera.bounds(Tmp.r2);
        float markLife = markLife();
        Draw.z(Layer.scorch + 0.1f);
        for(int i = 0; i < marksKept; i++){
            float r = markR[i];
            if(r <= 0f) continue;
            float age = Time.time - markAt[i];
            if(age < 0f || age > markLife){
                if(age > markLife) markR[i] = 0f;
                continue;
            }
            float x = markX[i], y = markY[i];
            if(!view.overlaps(x - r * 2f, y - r * 2f, r * 4f, r * 4f)) continue;
            // dark at once, then fading: slowly at first, faster towards the end
            float k = age / markLife, a = Mathf.clamp(age / 4f) * (1f - k * k) * Math.min(strength, 2f);
            long seed = (long)(x * 31f) * 73856093L ^ (long)(y * 17f) * 19349663L;
            // the patch: two soft blobs, the inner one darker
            soot.set(0.05f, 0.04f, 0.035f, 0.42f * a);
            Draw.color(soot);
            Draw.rect(soft, x, y, r * 2.6f, r * 2.6f, Mathf.randomSeed(seed) * 360f);
            soot.a = 0.35f * a;
            Draw.color(soot);
            // (placed by the mark's own numbers: placed at random anew every frame, it shook - "it flickers a little")
            Draw.rect(soft, x + (Mathf.randomSeed(seed + 2L) - 0.5f) * r * 0.3f, y + (Mathf.randomSeed(seed + 3L) - 0.5f) * r * 0.3f, r * 1.3f, r * 1.3f);
            // streaks of soot thrown outwards
            int streaks = 5 + (int)(Mathf.randomSeed(seed + 1L) * 5f);
            // (soft, stretched blobs: drawn as sharp triangles they made a black star)
            soot.a = 0.28f * a;
            Draw.color(soot);
            for(int s = 0; s < streaks; s++){
                float angle = Mathf.randomSeed(seed + 10L + s) * 360f, len = r * (0.8f + 0.9f * Mathf.randomSeed(seed + 40L + s));
                float w = r * (0.30f + 0.20f * Mathf.randomSeed(seed + 70L + s));
                Draw.rect(soft, x + Angles.trnsx(angle, len * 0.5f), y + Angles.trnsy(angle, len * 0.5f), len * 1.1f, w, angle);
            }
        }
        Draw.color();
    }

    private static void add(int id, float x, float y, float at, float far){
        blastId[next] = id;
        blastX[next] = x;
        blastY[next] = y;
        blastAt[next] = at;
        blastR[next] = far;
        next = (next + 1) % kept;
    }

    /**
     * The flashes of this frame, as lights: each explosion flares up at once and dies away fast, the bigger the slower;
     * white-hot at first, then the orange of fire.
     */
    private static void flashes(){
        if(!(Vars.renderer.lights instanceof LightTap tap) || !Core.settings.getBool(ShaderMod.blastsOn, true)) return;
        float flash = ShaderMod.percent(ShaderMod.blastsFlash, 100);
        if(flash <= 0f) return;
        Rect view = Core.camera.bounds(Tmp.r1);
        for(int i = 0; i < kept; i++){
            if(blastId[i] == 0) continue;
            float far = blastR[i], age = Time.time - blastAt[i], life = 8f + far * 0.12f;
            if(age < 0f || age >= life) continue;
            float radius = far * 1.8f + 24f;
            if(!view.overlaps(blastX[i] - radius, blastY[i] - radius, radius * 2f, radius * 2f)) continue;
            float k = age / life, left = (1f - k) * (1f - k);
            tint.set(Color.white).lerp(flashColor, Mathf.clamp(k * 2.5f));
            // on the ground, where it burst: the blocks around throw long shadows away from it
            tap.addAt(blastX[i], blastY[i], radius * (0.85f + 0.15f * left), tint, Mathf.clamp(left * flash), 0f);
        }
    }

    /** For the test kit: an explosion at that place at that time (Time.time), reaching that far. */
    public static void testBlast(float x, float y, float at, float far){
        add(-1 - tests++, x, y, at, far);
        mark(x, y, at, far);
    }

    /** The explosions for the last step of the frame: u_blasts (x, y, age in ticks, reach; reach 0 = not in use) and u_shock. */
    static void apply(Shader s){
        float shock = Core.settings.getBool(ShaderMod.blastsOn, true) ? ShaderMod.percent(ShaderMod.blastsShock, 100) : 0f;
        for(int i = 0; i < kept; i++){
            float age = Time.time - blastAt[i], far = blastR[i];
            // the wave lives for as long as the shader lets it run (mshaders-post.frag, blastWarp)
            boolean live = blastId[i] != 0 && age >= 0f && age < 20f + far * 0.25f;
            uniforms[i * 4] = blastX[i];
            uniforms[i * 4 + 1] = blastY[i];
            uniforms[i * 4 + 2] = age;
            uniforms[i * 4 + 3] = live ? far : 0f;
        }
        if(s.hasUniform("u_shock")) s.setUniformf("u_shock", shock);
        if(s.hasUniform("u_flash")) s.setUniformf("u_flash", Core.settings.getBool(ShaderMod.blastsOn, true) ? ShaderMod.percent(ShaderMod.blastsFlash, 100) : 0f);
        if(s.hasUniform("u_blasts[0]") || s.hasUniform("u_blasts")) s.setUniform4fv("u_blasts", uniforms, 0, kept * 4);
    }
}
