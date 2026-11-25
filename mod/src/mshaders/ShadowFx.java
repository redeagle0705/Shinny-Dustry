package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.Shaders.*;

/**
 * Block shadows that fall to the lower left instead of sitting under the block.
 * The game keeps a shadow mask (one texel per tile) and draws it once with Shaders.darkness in BlockRenderer.drawShadows(),
 * queued at Layer.block - 1. Nothing of that is replaced: only for that one draw call Shaders.darkness is swapped for a shader
 * that reads the same mask several times along the light direction. The other user of Shaders.darkness (map darkness,
 * Layer.darkness) keeps the original because the swap is undone right after the shadow layer.
 */
public class ShadowFx{
    private DarknessShader original, directional;
    private final Runnable swapIn = () -> {
        // (timed from here until swapOut: the game draws its shadows with the shader put in here)
        if(Clock.on) Draw.flush();
        Clock.begin("shadow");
        // Where a lamp shines the sun is not the only light, so its shadow is fainter there: the lamps' light of this
        // frame, made now (before the shadows are drawn) and read by both shadow shaders. Null when the lights are off.
        fill = Core.settings.getBool(ShaderMod.lightsOn, true) && this.scene != null ? this.scene.lampLight() : null;
        this.check.atSwapIn(Shaders.darkness == original || Shaders.darkness == directional);
        if(Shaders.darkness == original) Shaders.darkness = directional;
    };

    /**
     * A watch on the sun shadows, for a blink seen on a player's map (never in the test scenes): now and then, for a
     * frame or two, the long shadows of the map's walls were gone. Each frame it notes whether the shadows really went
     * through the mod's shader, and what that shader was given; a frame where either is off from the frames around it
     * is written to the log ("[mshaders] shadow check"), the first few in full, then a count now and then.
     */
    final Check check = new Check();

    static class Check{
        long inFrame = -1, applyFrame = -1;
        int applies, logged, odd;
        boolean swappable;
        // what the shader was given, this frame and the last two
        final float[][] given = new float[3][];
        long lastSummary;

        void atSwapIn(boolean swappable){
            inFrame = Core.graphics.getFrameId();
            this.swappable = swappable;
            applies = 0;
        }

        void atApply(float... values){
            applyFrame = Core.graphics.getFrameId();
            applies++;
            if(applies == 1){
                given[0] = given[1];
                given[1] = given[2];
                given[2] = values;
            }
        }

        void atSwapOut(){
            long frame = Core.graphics.getFrameId();
            if(frame != inFrame) return;
            String what = null;
            if(!swappable) what = "Shaders.darkness was neither the game's nor the mod's";
            else if(applyFrame != frame) what = "the shadows were not drawn through the mod's shader";
            else if(applies > 1) what = "the mod's shader was set up " + applies + " times";
            // a value off for one frame and back the next: the frame before last, the last and this one
            if(what == null && given[0] != null && given[1] != null && given[2] != null && given[0].length == given[2].length){
                StringBuilder b = new StringBuilder();
                String[] names = {"heights", "fill", "fillK", "length", "strength", "sunX", "sunY", "shapes", "mirror", "wet", "pixel"};
                for(int i = 0; i < given[1].length && i < names.length; i++){
                    float a = given[0][i], m = given[1][i], c = given[2][i];
                    if(Math.abs(a - c) <= 1e-4f && Math.abs(m - a) > 1e-4f + Math.abs(a) * 0.01f){
                        b.append(b.length() == 0 ? "" : ", ").append(names[i]).append(' ').append(a).append(" -> ").append(m).append(" -> ").append(c);
                    }
                }
                if(b.length() > 0) what = "for one frame: " + b;
            }
            if(what != null){
                odd++;
                if(logged < 30){
                    logged++;
                    Log.info("[mshaders] shadow check, frame @: @", frame, what);
                }
            }
            if(odd > 0 && frame - lastSummary > 3600){
                lastSummary = frame;
                Log.info("[mshaders] shadow check: @ odd frames so far", odd);
            }
        }
    }
    private Texture fill;

    /** Binds the lamps' light to texture unit 2 and tells the shader how strongly it fills in the sun shadows. */
    private void applyFill(Shader s){
        boolean filled = fill != null && !fill.isDisposed();
        if(filled) fill.bind(2);
        if(s.hasUniform("u_fill")) s.setUniformi("u_fill", 2);
        if(s.hasUniform("u_filled")) s.setUniformf("u_filled", filled ? 1f : 0f);
        if(s.hasUniform("u_screen")) s.setUniformf("u_screen", Core.graphics.getWidth(), Core.graphics.getHeight());
        // the sun shadow keeps the share  daylight / (daylight + lamp light): the weaker the daylight, the sooner a lamp wins
        if(s.hasUniform("u_fillK")) s.setUniformf("u_fillK", 5f * ShaderMod.percent(ShaderMod.lightsStrength, 100) / ShaderMod.daylight());
    }
    /** Binds the picture of blocks that are not plain squares to texture unit 4 and their outlines to unit 5 (mshaders-shadow.frag, toOutline). */
    private void applyShapes(Shader s){
        Texture shapes = scene == null ? null : scene.shapes(), outlines = shapes == null ? null : scene.outlines();
        if(outlines != null){
            shapes.bind(4);
            outlines.bind(5);
        }
        if(s.hasUniform("u_shapes")) s.setUniformi("u_shapes", 4);
        if(s.hasUniform("u_outlines")) s.setUniformi("u_outlines", 5);
        if(s.hasUniform("u_shaped")) s.setUniformf("u_shaped", outlines != null ? 1f : 0f);
    }
    private final Runnable swapOut = () -> {
        if(Shaders.darkness == directional) Shaders.darkness = original;
        this.check.atSwapOut();
        // BlockRenderer.drawShadows() has just run: its mask is still in Tmp.tr1 (BlockRenderer.java:513). Kept for the
        // shadows on blocks, which are drawn later in this frame.
        Texture t = Tmp.tr1.texture;
        // the mask is exactly one texel per tile; anything else is not it
        mask = t != null && !t.isDisposed() && t.width == Vars.world.width() && t.height == Vars.world.height() ? t : null;
        maskFrame = Core.graphics.getFrameId();
        if(Clock.on) Draw.flush();
        Clock.end();
    };
    private final Runnable onBlocks = Clock.timed("shadowtop", this::onBlocks);
    private final Runnable onProps = Clock.timed("shadowprops", this::onProps);
    private Shader topShader, propShader;
    /** Boulders drawn the plain way (Prop.drawBase as it is: one picture on its tile), by block. */
    private static final arc.struct.ObjectMap<mindustry.world.Block, Boolean> plainProps = new arc.struct.ObjectMap<>();
    private Texture mask;
    private long maskFrame = -10;
    private Scene scene;

    /**
     * Shadows on top of blocks (mshaders-shadow-top.frag): laid over the frame right after the buildings are drawn and
     * before turret heads and units, so a tall block shades the lower blocks beside it and nothing above them.
     */
    private void onBlocks(){
        Texture heights = scene.heights();
        if(topShader == null || heights == null || mask == null || mask.isDisposed() || maskFrame != Core.graphics.getFrameId()) return;
        Draw.flush();
        Blending.normal.apply();
        Draw.blit(mask, topShader);
    }

    /**
     * The sun shadow on boulders (mshaders-shadow-top.frag with PROP): right after the boulders are drawn (Prop.layer),
     * each boulder in view is drawn again through that shader - the same picture the game picked for it, black, as dark
     * as the shadow that falls on each of its pixels. The shadow then fits the boulder whatever its shape; the round
     * patch the shadows on blocks used to lay on it was smaller than the picture, and its edge stayed lit.
     */
    private void onProps(){
        Texture heights = scene == null ? null : scene.heights();
        if(propShader == null || heights == null || mask == null || mask.isDisposed() || maskFrame != Core.graphics.getFrameId()) return;
        if(Vars.world == null || Vars.world.width() <= 0) return;
        float t = Vars.tilesize;
        int x0 = Math.max((int)Math.floor((Core.camera.position.x - Core.camera.width / 2f) / t) - 1, 0), x1 = Math.min((int)Math.ceil((Core.camera.position.x + Core.camera.width / 2f) / t) + 1, Vars.world.width() - 1);
        int y0 = Math.max((int)Math.floor((Core.camera.position.y - Core.camera.height / 2f) / t) - 1, 0), y1 = Math.min((int)Math.ceil((Core.camera.position.y + Core.camera.height / 2f) / t) + 1, Vars.world.height() - 1);
        boolean any = false;
        for(int y = y0; y <= y1; y++){
            for(int x = x0; x <= x1; x++){
                mindustry.world.Tile tile = Vars.world.rawTile(x, y);
                if(!(tile.block() instanceof mindustry.world.blocks.environment.Prop prop) || !plainProp(prop)) continue;
                if(!any){
                    Draw.flush();
                    Draw.shader(propShader);
                    any = true;
                }
                // the same picture Prop.drawBase picks
                TextureRegion region = prop.variants > 0 && prop.variantRegions != null && prop.variantRegions.length > 0
                    ? prop.variantRegions[arc.math.Mathf.randomSeed(tile.pos(), 0, Math.max(0, prop.variantRegions.length - 1))] : prop.region;
                // (its height goes in the colour, one draw for all of them; rounded as the heights picture has it -
                // Occluders - so a boulder of the same height never shades this one)
                Draw.color((Math.round(Heights.of(tile) / Heights.max * 255f) + 0.25f) / 255f, 0f, 0f, 1f);
                Draw.rect(region, tile.worldx(), tile.worldy());
            }
        }
        if(any){
            Draw.shader();
            Draw.color();
        }
    }

    /**
     * Is this a boulder drawn by Prop.drawBase itself (one picture on its tile, the same picture the static cache of
     * StaticProp draws), not by a drawing of its own (seaweed, sea bushes, wobbling props)? Occluders marks these
     * (alpha 129) so the shadows on blocks leave them to onProps.
     */
    static boolean plainProp(mindustry.world.Block block){
        if(!(block instanceof mindustry.world.blocks.environment.Prop)) return false;
        Boolean known = plainProps.get(block);
        if(known == null){
            try{
                known = block.getClass().getMethod("drawBase", mindustry.world.Tile.class).getDeclaringClass() == mindustry.world.blocks.environment.Prop.class;
            }catch(Throwable e){
                known = false;
            }
            plainProps.put(block, known);
        }
        return known;
    }

    /**
     * Shadows grow and shrink like over a day: between 0.4 and 1.6 times the set length, once per cycle.
     * Runs on game time (GameState.tick), so it stands still while the game is paused. Cycle 0 = always the set length.
     */
    public static float dayFactor(){
        // with a time of day (the campaign's, or the mod's own), the length goes by the height of the sun
        if(Daytime.active()) return Daytime.lengthFactor();
        int minutes = Core.settings.getInt(ShaderMod.shadowCycle, 4);
        if(minutes <= 0) return 1f;
        double phase = Vars.state.tick / (minutes * 3600.0);
        float length = 1f + 0.6f * (float)Math.sin(phase * Math.PI * 2.0);
        // in small steps, not a hair every frame (that made the shadow edges shimmer), as Daytime does
        if(Float.isNaN(heldLength) || Math.abs(length - heldLength) >= lengthStep()) heldLength = length;
        return heldLength;
    }

    /**
     * The smallest change of the length factor worth making: one that moves the tip of the longest shadow by under a
     * pixel on screen. (A fixed step moved the tips of tall blocks' long shadows by several pixels at once, and each
     * jump looked like the shadows being wiped and drawn again - "a blink"; changing a hair every frame made the edges
     * shimmer.) Small enough not to be seen, large enough to change only now and then.
     */
    static float lengthStep(){
        float tilesPerPixel = Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize;
        float longest = Heights.max * Math.max(Core.settings.getInt(ShaderMod.shadowLength, 15) / 10f, 0.1f);
        return Math.max(tilesPerPixel * 0.7f / longest, 0.0005f);
    }

    private static float heldLength = Float.NaN;

    public boolean ready(){
        return directional != null;
    }

    public void init(Scene scene){
        original = Shaders.darkness;
        // Shaders.darkness has the type DarknessShader, whose constructor always loads "shaders/darkness.frag" through the file
        // tree. So that name points at our file just while the object is built, and is put back afterwards.
        String name = "shaders/darkness.frag";
        Fi before = Vars.tree.get(name);
        try{
            Vars.tree.addFile(name, Vars.tree.get("shaders/mshaders-shadow.frag"));
            directional = new DarknessShader(){
                @Override
                public void apply(){
                    if(hasUniform("u_tile")) setUniformf("u_tile", 1f / Math.max(Vars.world.width(), 1), 1f / Math.max(Vars.world.height(), 1));
                    if(hasUniform("u_length")) setUniformf("u_length", Core.settings.getInt(ShaderMod.shadowLength, 15) / 10f * dayFactor());
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight() * (1f - Skies.shadowDim()));
                    // quality 100% = edges as sharp as the screen allows, 0% = soft and wide
                    if(hasUniform("u_soft")) setUniformf("u_soft", Math.max(1f - ShaderMod.percent(ShaderMod.shadowQuality, 80), Skies.shadowSoft()));
                    if(hasUniform("u_ao")) setUniformf("u_ao", ShaderMod.percent(ShaderMod.shadowAo, 100));
                    if(hasUniform("u_pixel")) setUniformf("u_pixel", Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize);
                    if(hasUniform("u_sun")) setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
                    applyFill(this);
                    applyShapes(this);
                    // lighter on water and ice while they show mirror images (the image lies inside the shadow)
                    boolean mirrors = Core.settings.getBool(ShaderMod.reflectOn, true) && Vars.renderer.animateWater
                        && (Core.settings.getBool(ShaderMod.waterOn, true) || Core.settings.getBool(ShaderMod.iceOn, true));
                    if(hasUniform("u_wet")) setUniformf("u_wet", mirrors ? 0.45f : 1f);
                    // the mirror picture of the previous frame: the shadow is lightened only where an image lies
                    Texture mirror = mirrors ? scene.reflection() : null;
                    if(mirror != null){
                        mirror.bind(3);
                        float[] cam = scene.reflectionCamera();
                        if(hasUniform("u_mirrorCam")) setUniformf("u_mirrorCam", cam[0], cam[1], cam[2], cam[3]);
                    }
                    if(hasUniform("u_mirror")) setUniformi("u_mirror", 3);
                    if(hasUniform("u_mirrored")) setUniformf("u_mirrored", mirror != null ? 1f : 0f);
                    // block heights: a tall block casts a long shadow, a conveyor hardly any (Heights.java)
                    Texture heights = scene.heights();
                    if(heights != null){
                        heights.bind(1);
                        // the sprite batch binds its own texture right after this, to whichever unit is active
                        Gl.activeTexture(Gl.texture0);
                    }
                    if(hasUniform("u_heights")) setUniformi("u_heights", 1);
                    if(hasUniform("u_tall")) setUniformf("u_tall", heights != null ? 1f : 0f);
                    // what the shadows fall on: the floor of each tile, and the snow lying on the ground
                    Texture floors = scene.floors();
                    if(floors != null) floors.bind(6);
                    if(hasUniform("u_floors")) setUniformi("u_floors", 6);
                    if(hasUniform("u_floored")) setUniformf("u_floored", floors != null ? 1f : 0f);
                    if(hasUniform("u_groundSnow")) setUniformf("u_groundSnow", Skies.groundSnow());
                    // whatever was bound above: the sprite batch binds its own texture next, to the active unit
                    Gl.activeTexture(Gl.texture0);
                    check.atApply(heights != null ? 1f : 0f, fill != null && !fill.isDisposed() ? 1f : 0f,
                        5f * ShaderMod.percent(ShaderMod.lightsStrength, 100) / ShaderMod.daylight(),
                        Core.settings.getInt(ShaderMod.shadowLength, 15) / 10f * dayFactor(),
                        ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight(),
                        ShaderMod.sunX(), ShaderMod.sunY(), scene.outlines() != null ? 1f : 0f, mirror != null ? 1f : 0f,
                        mirrors ? 0.45f : 1f, Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize);
                }
            };
        }catch(Throwable t){
            directional = null;
            Log.err("[mshaders] directional shadows unavailable, keeping the built-in ones", t);
        }finally{
            Vars.tree.addFile(name, before);
        }
        if(directional == null) return;
        this.scene = scene;
        try{
            topShader = new Shader(ShaderMod.source("screenspace.vert"), ShaderMod.source("mshaders-shadow-top.frag")){
                @Override
                public void apply(){
                    applyFill(this);
                    applyShapes(this);
                    scene.heights().bind(1);
                    mask.bind(0);
                    if(hasUniform("u_heights")) setUniformi("u_heights", 1);
                    if(hasUniform("u_campos")) setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
                    if(hasUniform("u_resolution")) setUniformf("u_resolution", Core.camera.width, Core.camera.height);
                    if(hasUniform("u_tile")) setUniformf("u_tile", 1f / Math.max(Vars.world.width(), 1), 1f / Math.max(Vars.world.height(), 1));
                    if(hasUniform("u_length")) setUniformf("u_length", Core.settings.getInt(ShaderMod.shadowLength, 15) / 10f * dayFactor());
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight() * (1f - Skies.shadowDim()));
                    if(hasUniform("u_soft")) setUniformf("u_soft", Math.max(1f - ShaderMod.percent(ShaderMod.shadowQuality, 80), Skies.shadowSoft()));
                    if(hasUniform("u_ao")) setUniformf("u_ao", ShaderMod.percent(ShaderMod.shadowAo, 100));
                    if(hasUniform("u_pixel")) setUniformf("u_pixel", Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize);
                    if(hasUniform("u_sun")) setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
                    // boulders are shaded through their own pictures (onProps): left alone here
                    if(hasUniform("u_props")) setUniformf("u_props", propShader != null ? 1f : 0f);
                }
            };
        }catch(Throwable t){
            topShader = null;
            Log.err("[mshaders] shadows on blocks unavailable, shadows stay under the blocks", t);
        }
        try{
            // the same walk, through each boulder's picture (the sprite batch's vertex shader that also hands on the world position)
            propShader = new Shader(ShaderMod.source("mshaders-shot.vert"), ShaderMod.source("mshaders-shadow-top.frag").replaceFirst("#define HIGHP", "#define HIGHP\n#define PROP")){
                @Override
                public void apply(){
                    applyFill(this);
                    applyShapes(this);
                    scene.heights().bind(1);
                    mask.bind(3);
                    // the sprite batch binds the boulders' texture to the active unit
                    Gl.activeTexture(Gl.texture0);
                    if(hasUniform("u_heights")) setUniformi("u_heights", 1);
                    if(hasUniform("u_mask")) setUniformi("u_mask", 3);
                    if(hasUniform("u_tile")) setUniformf("u_tile", 1f / Math.max(Vars.world.width(), 1), 1f / Math.max(Vars.world.height(), 1));
                    if(hasUniform("u_length")) setUniformf("u_length", Core.settings.getInt(ShaderMod.shadowLength, 15) / 10f * dayFactor());
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight() * (1f - Skies.shadowDim()));
                    if(hasUniform("u_soft")) setUniformf("u_soft", Math.max(1f - ShaderMod.percent(ShaderMod.shadowQuality, 80), Skies.shadowSoft()));
                    if(hasUniform("u_pixel")) setUniformf("u_pixel", Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize);
                    if(hasUniform("u_sun")) setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
                    if(hasUniform("u_ao")) setUniformf("u_ao", ShaderMod.percent(ShaderMod.shadowAo, 100));
                }
            };
        }catch(Throwable t){
            propShader = null;
            Log.err("[mshaders] shadows on boulders unavailable, they keep the round patch", t);
        }

        // Trigger.draw fires inside Renderer.draw() while the frame's layers are being queued
        Events.run(Trigger.draw, () -> {
            if(!Core.settings.getBool(ShaderMod.shadowOn, true)) return;
            Draw.drawRange(Layer.block - 1f, 0.001f, swapIn, swapOut);
            // after the buildings (Layer.block .. blockBuilding), before turret heads and units; after the normal-map
            // shading of Scene.shapePass (Layer.turret - 1: no sun glint inside a shadow) and after the snow and spores
            // on the buildings (SnowCover, Layer.turret - 0.9: laid after the shadow, they showed bright in it - "the
            // snow seems to pile up on top of the shadows")
            if(topShader != null) Draw.draw(Layer.turret - 0.85f, onBlocks);
            // after the boulders: the dynamic ones are drawn at Prop.layer (Layer.blockProp), the StaticProp ones (most
            // boulders) earlier, from the static cache at Layer.block or just under it. Where a boulder's picture hangs
            // over a taller block next to it, the shader leaves that block alone.
            if(propShader != null) Draw.draw(Layer.blockProp + 0.01f, onProps);
        });
    }
}
