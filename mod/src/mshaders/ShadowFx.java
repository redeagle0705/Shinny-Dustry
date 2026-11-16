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
        // Where a lamp shines the sun is not the only light, so its shadow is fainter there: the lamps' light of this
        // frame, made now (before the shadows are drawn) and read by both shadow shaders. Null when the lights are off.
        fill = Core.settings.getBool(ShaderMod.lightsOn, true) && this.scene != null ? this.scene.lampLight() : null;
        if(Shaders.darkness == original) Shaders.darkness = directional;
    };
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
        // BlockRenderer.drawShadows() has just run: its mask is still in Tmp.tr1 (BlockRenderer.java:513). Kept for the
        // shadows on blocks, which are drawn later in this frame.
        Texture t = Tmp.tr1.texture;
        // the mask is exactly one texel per tile; anything else is not it
        mask = t != null && !t.isDisposed() && t.width == Vars.world.width() && t.height == Vars.world.height() ? t : null;
        maskFrame = Core.graphics.getFrameId();
    };
    private final Runnable onBlocks = this::onBlocks;
    private Shader topShader;
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
     * Shadows grow and shrink like over a day: between 0.4 and 1.6 times the set length, once per cycle.
     * Runs on game time (GameState.tick), so it stands still while the game is paused. Cycle 0 = always the set length.
     */
    public static float dayFactor(){
        int minutes = Core.settings.getInt(ShaderMod.shadowCycle, 4);
        if(minutes <= 0) return 1f;
        double phase = Vars.state.tick / (minutes * 3600.0);
        return 1f + 0.6f * (float)Math.sin(phase * Math.PI * 2.0);
    }

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
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight());
                    // quality 100% = edges as sharp as the screen allows, 0% = soft and wide
                    if(hasUniform("u_soft")) setUniformf("u_soft", 1f - ShaderMod.percent(ShaderMod.shadowQuality, 80));
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
                    // whatever was bound above: the sprite batch binds its own texture next, to the active unit
                    Gl.activeTexture(Gl.texture0);
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
                    if(hasUniform("u_strength")) setUniformf("u_strength", ShaderMod.percent(ShaderMod.shadowStrength, 70) * ShaderMod.daylight());
                    if(hasUniform("u_soft")) setUniformf("u_soft", 1f - ShaderMod.percent(ShaderMod.shadowQuality, 80));
                    if(hasUniform("u_pixel")) setUniformf("u_pixel", Core.camera.width / Math.max(Core.graphics.getWidth(), 1) / Vars.tilesize);
                    if(hasUniform("u_sun")) setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
                }
            };
        }catch(Throwable t){
            topShader = null;
            Log.err("[mshaders] shadows on blocks unavailable, shadows stay under the blocks", t);
        }

        // Trigger.draw fires inside Renderer.draw() while the frame's layers are being queued
        Events.run(Trigger.draw, () -> {
            if(!Core.settings.getBool(ShaderMod.shadowOn, true)) return;
            Draw.drawRange(Layer.block - 1f, 0.001f, swapIn, swapOut);
            // after the buildings (Layer.block .. blockBuilding), before turret heads and units; just before the
            // normal-map shading of Scene.shapePass
            if(topShader != null) Draw.draw(Layer.turret - 1.5f, onBlocks);
        });
    }
}
