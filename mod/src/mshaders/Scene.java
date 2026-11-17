package mshaders;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.struct.FloatSeq;
import arc.struct.IntMap;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.*;

/**
 * Everything that needs the finished world image: colour pass, water reflections, lights blocked by blocks, metal glint.
 * Built like the game's Pixelator (graphics/Pixelator.java): the world is drawn into a frame buffer, opened on Trigger.preDraw
 * (first line of Renderer.draw) and closed on Trigger.postDraw (last line), then drawn to the screen through one shader.
 * The UI is drawn later and is not touched. If no effect that needs the buffer is on, the buffer is not opened at all.
 *
 * Buffers:
 *  scene    the world of this frame (screen size)
 *  (the picture of the ground alone, taken after the block shadows, is no longer needed: the mirror pass goes by tiles)
 *  before   copy of scene taken right before the bullet and effect layers
 *  after    copy of scene taken right after them
 *  glow     what those layers added: after - before, i.e. shots, beams, explosions and their bloom
 *  objects  the mirror picture: buildings (from before, on the tiles a solid building stands on) flipped at their lower edge and
 *           one tile long, plus the units as flipped sprites. The water and ice shaders of the NEXT frame lay objects
 *           over their surface and smear glow into streaks.
 *  light    light from the recorded light sources, cut off by the block mask (quarter size)
 *  aim      the direction most of that light comes from, for the glint (quarter size)
 */
public class Scene{
    /**
     * The most lights the shaders have room for (the size of their arrays, MAX_LIGHTS). How many are really used is the
     * player's "light limit" setting, see {@link #lightLimit}: if more lights are in view than that, the strongest stay.
     */
    public static final int maxLights = 256;
    /** How high the light of a unit in full flight hangs at 100% of the setting, in world units: three tiles. */
    public static final float airLevel = 24f;
    /** What the shaders were compiled with: maxLights, or 64 if the graphics card refused arrays that long. */
    private int shaderLights = 64;

    /** The light limit of this frame: the setting, within what the shaders can take. */
    private int lightLimit(){
        return Math.max(8, Math.min(Core.settings.getInt(ShaderMod.lightsMax, 64), shaderLights));
    }

    private FrameBuffer scene, objects, light, aim, before, after, glow;
    private Shader composite, objectShader, lightShader, glowShader;
    private long beforeFrame = -10, glowFrame = -10, aimFrame = -10, lightFrame = -10;
    private boolean shapeAimed, shapeLit;
    private LightTap tap;
    private boolean capturing, wantObjects, wantLight, wantGlint, wantShape, lightReady, aimReady, glintReady, shapeReady;
    /** Normal maps for blocks and where on the map each applies. */
    private final Surfaces surfaces = new Surfaces();

    /** The game's block shadow mask (one texel per tile), picked up while the game draws it; null if not seen this frame. */
    private Texture mask;
    /** Camera rectangle (x, y of the lower left corner, width, height) the objects buffer was made with, and the frame it is from. */
    private final float[] objectsCam = new float[4];
    private long objectsFrame = -10;

    private final float[] lightData = new float[maxLights * 4], lightColors = new float[maxLights * 4];
    private int lightCount, lightMode;
    private float[] scores = new float[0];
    private boolean[] chosen = new boolean[0];
    private FrameBuffer gameLight, raw, near;
    /**
     * Dark maps, for the units in the air (liftAir): their shapes (screen size), the lights with nothing in their way
     * (quarter size), and the light picture with those two worked in (screen size). "lit" is the picture the frame is
     * lit with: gameLight, or airLit while a unit is in the air.
     */
    private FrameBuffer air, open, airLit, lit;
    private Shader airShader;
    /** True while the light shader makes the picture of the lights with nothing in their way. */
    private boolean lightOpen;
    private int airCount;
    private final Cons<Unit> airUnit = this::airUnit;
    private Shader blurShader, deepShader, tintShader, shapeShader, normalShader, itemShader;
    /** The normals of the normal-mapped buildings in view, as drawn this frame (screen size), and the frame it is from. */
    private FrameBuffer normals;
    /** The same for the heads of those buildings (mass drivers, turrets), which the game draws later than the buildings; and the picture the shading shader reads right now. */
    private FrameBuffer headNormals, shapeFrom;
    private long normalsFrame = -10;
    /** Multiplies what is on screen: result = screen * 2 * shader output (so 0.5 leaves it as it is). Alpha is kept. */
    private static final Blending tintBlend = new Blending(Gl.dstColor, Gl.srcColor, Gl.zero, Gl.one);
    private TextureRegion circleRegion;
    private boolean takeover, gameLit;
    /** The round glow a light of the ground is painted with on bright maps (paintGround). */
    private TextureRegion groundRegion;
    /** How much stronger than reported the glow of the ground is painted on bright maps. */
    private static final float groundGain = 2.2f;
    /**
     * What 100% of the "glowing ground" setting stands for on bright maps, as a number of times stronger than plain
     * groundGain. A glow k times stronger is painted with k times the opacity (up to 1, all the picture holds) and
     * sqrt(k) times the radius, like the lights of moving things (LightTap.moving); the normal-mapped blocks get k
     * times its weight.
     */
    private static final float groundBoost = 1.5f;

    /**
     * A channel of the colour of a ground light, made deeper: the weaker channels of the colour are pushed down (each
     * squared against the strongest one), the strongest stays. The orange of slag (1, 0.65, 0) becomes (1, 0.42, 0).
     * As reported, its glow on grey rock came out a yellowish olive rather than orange: where the picture brightens
     * the rock, red and green end up close together.
     * @param top the strongest channel of that colour
     */
    static float deep(float channel, float top){
        return top <= 0f ? 0f : channel * channel / top;
    }

    /** The "glowing ground" setting: 1 = 100%, 0 = off. */
    private static float groundLevel(){
        return Math.max(ShaderMod.percent(ShaderMod.lightsGround, 100), 0f);
    }
    /** Where the glow of the ground comes from, for the normal-mapped blocks. */
    private final GlowField glowField = new GlowField();
    /** What of the units stands in the way of light this frame (bodies, feet, legs, each with its height). */
    private final UnitShades unitShades = new UnitShades();
    private final Runnable unitPass = this::unitPass;
    private boolean wantUnits;
    /** What stops light (solid blocks only), and the texture the light shader reads this frame. */
    private final Occluders occluders = new Occluders();
    private Texture lightMask;
    private boolean lightMaskOwn;
    /** The same picture of solid blocks, as the mirror pass of this frame uses it: only what stands up is mirrored. */
    private Texture mirrorSolid;
    /** Sprites onto a picture that holds colour already multiplied by its cover: colour blends as usual, cover adds up. */
    private static final Blending mirrorBlend = new Blending(Gl.srcAlpha, Gl.oneMinusSrcAlpha, Gl.one, Gl.oneMinusSrcAlpha);
    /** For pictures that hold colour already multiplied by its cover: what is drawn replaces what is there by its cover. */
    private static final Blending coverBlend = new Blending(Gl.one, Gl.oneMinusSrcAlpha);
    /** A sprite drawn with it empties the target where the sprite is, by as much as the sprite covers. */
    private static final Blending cutBlend = new Blending(Gl.zero, Gl.oneMinusSrcAlpha);
    /** For Surfaces.drawUnits: the batch set up to cut the shape of a game sprite out of the picture, and back to laying maps on it. */
    private final Runnable cutState = () -> {
        Draw.shader();
        Draw.blend(cutBlend);
    }, layState = () -> {
        Draw.shader(normalShader);
        Draw.blend(coverBlend);
    };
    /** Writes nothing at all: what is drawn with it leaves an empty place in the target. */
    private static final Blending eraseBlend = new Blending(Gl.zero, Gl.zero);
    private final Cons<Unit> unitMirror = this::mirrorUnit;

    private static final boolean report = System.getProperty("mstest.out") != null;

    private final Runnable shapePass = this::shapePass, headPass = this::headPass;
    /** The shading passes for the units, one per layer units with a map are on (keyed by that layer in tenths). */
    private final IntMap<Runnable> unitShapePasses = new IntMap<>();
    /** The frame the lights for the shading were last picked in (prepareShape). */
    private long shapePrepared = -10;
    private final Runnable grabMask = this::grabMask, buildObjects = this::buildObjects,
        snapshotBefore = this::snapshotBefore, buildGlow = this::buildGlow;

    public boolean ready(){
        return composite != null;
    }

    public boolean lightsReady(){
        return tap != null;
    }

    /**
     * How much light the lamps and other light sources give at each point of the screen this frame (quarter size,
     * already cut off by blocks): the same picture the bright-map lighting adds at the end of the frame, made early
     * for whoever needs it while the frame is still being drawn. The sun shadows use it: where a lamp shines, the
     * sun is no longer the only light, so its shadow is fainter there.
     * Every light of the frame is already known here: lights are reported while the frame is queued, and this is
     * called when the queue is played back.
     * @return null when the lights are not being listened to this frame
     */
    public Texture lampLight(){
        if(!capturing || !wantLight || tap == null) return null;
        long frame = Core.graphics.getFrameId();
        if(lightFrame != frame){
            pickLightMask();
            if(lightMask == null) return null;
            pickLights();
            Draw.flush();
            Blending.disabled.apply();
            lightPass(light, Math.max(scene.getWidth() / 4, 2), Math.max(scene.getHeight() / 4, 2), 0);
            paintGround(light, true);
            // back to what the frame expects (see copyScene)
            Blending.normal.apply();
            lightFrame = frame;
        }
        return light.getTexture();
    }

    /** The picture of solid blocks, buildings and block heights (Occluders.java), one texel per tile; null without a loaded map. */
    public Texture heights(){
        return ready() ? occluders.texture() : null;
    }

    /** Which blocks are not drawn as full squares, and where their outline is kept (Occluders.java), one texel per tile; null without a loaded map. */
    public Texture shapes(){
        return ready() ? occluders.shapes() : null;
    }

    /** The outlines of those blocks (Silhouettes.java); null while there are none. */
    public Texture outlines(){
        return ready() ? occluders.outlines() : null;
    }

    /** @return the objects buffer of the previous frame for the water shader, or null if there is none */
    public Texture reflection(){
        return objects != null && Core.graphics.getFrameId() - objectsFrame <= 1 ? objects.getTexture() : null;
    }

    /** @return what the bullet and effect layers added in that same frame, or null */
    public Texture reflectionGlow(){
        return reflection() != null && glowFrame == objectsFrame ? glow.getTexture() : null;
    }

    /**
     * For the water and ice shaders: binds the previous frame's pictures to texture units 2 and 3 and sets the uniforms of
     * mshaders-reflect.glsl. Call before SurfaceShader.apply(), which leaves unit 0 active again.
     * @return false if there is nothing to reflect this frame
     */
    public boolean bindReflection(Shader shader){
        Texture mirror = reflection();
        if(mirror == null || !shader.hasUniform("u_objects")) return false;
        Texture glowing = reflectionGlow();
        if(glowing != null) glowing.bind(3);
        set(shader, "u_glow", 3);
        set(shader, "u_glowing", glowing != null ? 1f : 0f);
        mirror.bind(2);
        set(shader, "u_objects", 2);
        if(shader.hasUniform("u_objcam")) shader.setUniformf("u_objcam", objectsCam[0], objectsCam[1], objectsCam[2], objectsCam[3]);
        if(shader.hasUniform("u_mirrorDir")) shader.setUniformf("u_mirrorDir", ShaderMod.sunX(), ShaderMod.sunY());
        return true;
    }

    public float[] reflectionCamera(){
        return objectsCam;
    }

    public void init(){
        try{
            String vert = Vars.tree.get("shaders/screenspace.vert").readString();
            // The two shaders that go through the list of lights get arrays for maxLights of them - if the graphics card
            // compiles that (each light is two uniform vectors, and cards differ in how many they allow); else for 64.
            String lightSource = Vars.tree.get("shaders/mshaders-light.frag").readString(), shapeSource = Vars.tree.get("shaders/mshaders-shape.frag").readString();
            try{
                String room = "#define MAX_LIGHTS " + maxLights + "\n";
                new Shader(vert, room + lightSource).dispose();
                new Shader(vert, room + shapeSource).dispose();
                shaderLights = maxLights;
            }catch(Throwable t){
                shaderLights = 64;
                Log.warn("[mshaders] room for @ lights refused by the graphics card, staying at 64: @", maxLights, t.getMessage());
            }
            String lightsDefine = "#define MAX_LIGHTS " + shaderLights + "\n";
            composite = new Shader(vert, Vars.tree.get("shaders/mshaders-post.frag").readString()){
                @Override
                public void apply(){
                    applyComposite(this);
                }
            };
            objectShader = new Shader(vert, Vars.tree.get("shaders/mshaders-objects.frag").readString()){
                @Override
                public void apply(){
                    mirrorSolid.bind(2);
                    scene.getTexture().bind(0);
                    set(this, "u_solid", 2);
                    if(hasUniform("u_sunDir")) setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
                    if(hasUniform("u_px")) setUniformf("u_px", Core.camera.width / Math.max(Core.graphics.getWidth(), 1));
                    setCamera(this);
                    if(hasUniform("u_tiles")) setUniformf("u_tiles", Vars.world.width(), Vars.world.height());
                }
            };
            glowShader = new Shader(vert, Vars.tree.get("shaders/mshaders-glow.frag").readString()){
                @Override
                public void apply(){
                    before.getTexture().bind(1);
                    after.getTexture().bind(0);
                    set(this, "u_before", 1);
                }
            };
            deepShader = new Shader(vert, Vars.tree.get("shaders/mshaders-deep.frag").readString()){
                @Override
                public void apply(){
                    lit.getTexture().bind(1);
                    near.getTexture().bind(0);
                    set(this, "u_lit", 1);
                    set(this, "u_deep", ShaderMod.percent(ShaderMod.darkDepth, 50));
                }
            };
            shapeShader = new Shader(vert, lightsDefine + shapeSource){
                @Override
                public void apply(){
                    // the glow of the ground: how strong, what colour and from where at every tile (GlowField)
                    glowField.bind(this, 3, 4);
                    set(this, "u_glowGain", groundLevel() * groundBoost);
                    aim.getTexture().bind(2);
                    shapeFrom.getTexture().bind(0);
                    set(this, "u_aim", 2);
                    set(this, "u_aimed", shapeAimed ? 1f : 0f);
                    set(this, "u_shape", ShaderMod.percent(ShaderMod.normalsStrength, 100));
                    // units are not lit by the glow they carry themselves, unless the player wants that
                    set(this, "u_own", shapingUnits && !Core.settings.getBool(ShaderMod.normalsOwnLight, false) ? 1f : 0f);
                    set(this, "u_glint", wantGlint ? ShaderMod.percent(ShaderMod.glintStrength, 100) : 0f);
                    set(this, "u_band", ShaderMod.percent(ShaderMod.glintBand, 100));
                    set(this, "u_time", Time.globalTime / 60f);
                    // On a dark map the daylight counts for little: what the darkness leaves of it. Lamps then do the lighting.
                    set(this, "u_day", ShaderMod.daylight());
                    // for the height maps: the lean of a pixel is read off the heights one sprite pixel away on the screen
                    if(hasUniform("u_px")) setUniformf("u_px", Core.camera.width / Math.max(Core.graphics.getWidth(), 1));
                    if(hasUniform("u_sunDir")) setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
                    // every light source of the frame, each with its own place (picked in shapePass just before this)
                    int count = shapeLit ? lightCount : 0;
                    set(this, "u_count", count);
                    if(count > 0 && (hasUniform("u_lights[0]") || hasUniform("u_lights"))) setUniform4fv("u_lights", lightData, 0, count * 4);
                    if(count > 0 && (hasUniform("u_colors[0]") || hasUniform("u_colors"))) setUniform4fv("u_colors", lightColors, 0, count * 4);
                    setCamera(this);
                    if(hasUniform("u_tiles")) setUniformf("u_tiles", Vars.world.width(), Vars.world.height());
                }
            };
            tintShader = new Shader(vert, Vars.tree.get("shaders/mshaders-tint.frag").readString()){
                @Override
                public void apply(){
                    set(this, "u_color", ShaderMod.percent(ShaderMod.lightsColor, 100));
                    // what the shots and effects of this frame added to the picture: those keep their own colour
                    boolean spared = glowFrame == Core.graphics.getFrameId();
                    if(spared){
                        glow.getTexture().bind(1);
                        lit.getTexture().bind(0);
                    }
                    set(this, "u_added", 1);
                    set(this, "u_spare", spared ? 1f : 0f);
                }
            };
            blurShader = new Shader(vert, Vars.tree.get("shaders/mshaders-blur.frag").readString()){
                @Override
                public void apply(){
                    if(hasUniform("u_texel")) setUniformf("u_texel", 1f / raw.getWidth(), 1f / raw.getHeight());
                }
            };
            lightShader = new Shader(vert, lightsDefine + lightSource){
                @Override
                public void apply(){
                    setCamera(this);
                    // units in the way of the light (UnitShades)
                    boolean shaded = wantUnits && unitShades.ready() && !lightOpen;
                    set(this, "u_unitsOn", shaded ? 1f : 0f);
                    if(shaded){
                        unitShades.texture().bind(1);
                        (lightMask != null ? lightMask : scene.getTexture()).bind(0);
                        set(this, "u_unitMask", 1);
                        set(this, "u_unitTop", unitShades.top());
                        // how much of the light a unit takes away where it is in the way: 1 = all of it
                        set(this, "u_unitDepth", Math.min(Math.max(ShaderMod.percent(ShaderMod.lightsUnitsDepth, 65), 0f), 1f));
                    }
                    set(this, "u_masked", lightMask != null && !lightOpen ? 1f : 0f);
                    set(this, "u_own", lightMaskOwn ? 1f : 0f);
                    set(this, "u_mode", lightMode);
                    set(this, "u_count", lightCount);
                    if(lightCount > 0){
                        // GL lists array uniforms as "name[0]"
                        if(hasUniform("u_lights[0]") || hasUniform("u_lights")) setUniform4fv("u_lights", lightData, 0, lightCount * 4);
                        if(hasUniform("u_colors[0]") || hasUniform("u_colors")) setUniform4fv("u_colors", lightColors, 0, lightCount * 4);
                    }
                }
            };
            scene = new FrameBuffer();
            objects = new FrameBuffer();
            light = new FrameBuffer();
            aim = new FrameBuffer();
            before = new FrameBuffer();
            glow = new FrameBuffer();
            after = new FrameBuffer();
            gameLight = new FrameBuffer();
            near = new FrameBuffer();
            air = new FrameBuffer();
            open = new FrameBuffer();
            airLit = new FrameBuffer();
            lit = gameLight;
            airShader = new Shader(vert, Vars.tree.get("shaders/mshaders-air.frag").readString()){
                @Override
                public void apply(){
                    open.getTexture().bind(2);
                    air.getTexture().bind(1);
                    gameLight.getTexture().bind(0);
                    set(this, "u_open", 2);
                    set(this, "u_air", 1);
                }
            };
            normals = new FrameBuffer();
            headNormals = new FrameBuffer();
            shapeFrom = normals;
            // drawn with the sprite batch, so with the vertex shader the batch uses
            normalShader = new Shader(Vars.tree.get("shaders/default.vert").readString(), Vars.tree.get("shaders/mshaders-normals.frag").readString());
            itemShader = new Shader(Vars.tree.get("shaders/default.vert").readString(), Vars.tree.get("shaders/mshaders-items.frag").readString());
            raw = new FrameBuffer();
            for(FrameBuffer b : new FrameBuffer[]{objects, light, aim, before, after, glow, gameLight, raw, near, air, open, airLit}){
                b.getTexture().setFilter(TextureFilter.linear, TextureFilter.linear);
            }
            // The round glow for the lights of the ground: full in the middle, fading to nothing at the rim the way the
            // game's own light sprite "circle-shadow" does (the fall-off mshaders-light.frag uses in mode 2). Colour and
            // cover hold the same value, so the largest of several glows can be taken channel by channel (paintGround).
            int size = 128;
            Pixmap round = new Pixmap(size, size);
            for(int x = 0; x < size; x++){
                for(int y = 0; y < size; y++){
                    float far = arc.math.Mathf.clamp((arc.math.Mathf.dst(x + 0.5f, y + 0.5f, size / 2f, size / 2f) / (size / 2f) - 0.2f) / 0.8f);
                    float v = 1f - far * far * (3f - 2f * far);
                    round.set(x, y, Tmp.c1.set(v, v, v, v).rgba());
                }
            }
            Texture glowing = new Texture(round);
            round.dispose();
            glowing.setFilter(TextureFilter.linear, TextureFilter.linear);
            groundRegion = new TextureRegion(glowing);
            unitShades.init();
        }catch(Throwable t){
            composite = null;
            Log.err("[mshaders] screen effects unavailable", t);
            return;
        }
        tap = LightTap.install(this);
        occluders.init();
        surfaces.init();

        Events.run(Trigger.preDraw, this::begin);
        Events.run(Trigger.draw, this::queue);
        Events.run(Trigger.postDraw, this::end);
    }

    private static boolean on(String key){
        return Core.settings.getBool(key, true);
    }

    private void begin(){
        capturing = false;
        mask = null;
        takeover = gameLit = false;
        if(tap != null){
            tap.capture = false;
            tap.takeover = false;
            tap.moving = 1f;
            tap.airHeight = 0f;
            tap.data.clear();
            tap.ground.clear();
        }
        glowField.clear();
        unitsShaded = 0;
        boolean post = on(ShaderMod.postOn);
        // the mirror pictures are needed by the water and by the ice (both are drawn through shader layers, which the game
        // only uses while "animated surfaces" is on)
        wantObjects = on(ShaderMod.reflectOn) && (on(ShaderMod.waterOn) || on(ShaderMod.iceOn)) && Vars.renderer.animateWater;
        wantLight = on(ShaderMod.lightsOn) && tap != null;
        wantUnits = wantLight && on(ShaderMod.lightsUnits);
        wantGlint = on(ShaderMod.glintOn);
        wantShape = on(ShaderMod.normalsOn) && surfaces.count() > 0;
        if(!post && !wantObjects && !wantLight && !wantGlint && !wantShape) return;
        // someone else is already drawing the world into a buffer (pixelation, map screenshot): stay out of the way
        if(Vars.renderer.pixelate || GLFrameBuffer.getBufferNesting() > 0) return;
        int w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        if(w < 8 || h < 8) return; // minimized window

        // lights are reported while the frame is put together, so listening has to start here
        if(tap != null){
            tap.capture = wantLight || wantGlint || wantShape;
            // shots, beams and effects made stronger (LightTap.moving); only while one of the effects that use lights is on
            tap.moving = tap.capture ? Math.max(ShaderMod.percent(ShaderMod.lightsMoving, 100), 1f) : 1f;
            // the glow of a flying unit hangs above the ground: 100% = airLevel world units up in full flight (LightTap.airHeight)
            tap.airHeight = tap.capture ? Math.max(ShaderMod.percent(ShaderMod.lightsAir, 100), 0f) * airLevel : 0f;
            // on a map with the lighting rule the game's light picture is drawn here instead, so blocks can stop the light
            takeover = tap.takeover = wantLight && tap.gameLighting();
        }
        scene.resize(w, h);
        scene.begin(Color.clear);
        capturing = true;
    }

    /** Early in the frame: the units in view drawn into the picture the light passes read (UnitShades), half the size of the screen. */
    private void unitPass(){
        if(!capturing || tap == null) return;
        // a flying unit hangs where its glow hangs (LightTap.airHeight); three tiles up if that setting is off
        unitShades.draw(scene.getWidth() / 2, scene.getHeight() / 2, tap.airHeight > 0f ? tap.airHeight : airLevel);
        // back to what the frame expects (see copyScene)
        Blending.normal.apply();
    }

    /** Trigger.draw fires inside Renderer.draw() while the frame's layers are being queued. */
    private void queue(){
        // the engines of a core that is coming down (drawn into the frame as the game draws its own, whatever else is on)
        Boosters.queueLanding();
        if(!capturing) return;
        // BlockRenderer.drawShadows() runs at Layer.block - 1; right after it its texture is still in Tmp.tr1
        if(wantLight || wantGlint) Draw.draw(Layer.block - 1f + 0.0005f, grabMask);
        // the picture of the units for the light passes: before the first of them (the sun shadows ask for the lamps' light at Layer.block - 1)
        if(wantUnits) Draw.draw(Layer.block - 2f, unitPass);
        // What the shots and effects add to the picture is wanted by the mirror pictures - and on a dark map by the
        // colouring of what the lights light, which must leave the things that glow by themselves alone (tintShader).
        if(wantObjects || (takeover && Core.settings.getInt(ShaderMod.lightsColor, 100) > 0)){
            // just outside the bullet and effect layers (the game's bloom wraps Layer.bullet - 0.02 .. Layer.effect + 0.02)
            Draw.draw(Layer.bullet - 0.03f, snapshotBefore);
            Draw.draw(Layer.effect + 0.03f, buildGlow);
        }
        if(wantObjects){
            // after bullets, effects and flying units, before overlays, weather and the game's lighting
            Draw.draw(Layer.flyingUnit + 1f, buildObjects);
        }
        // after the buildings (Layer.block .. blockBuilding), before turret heads, units, shots and effects
        if(wantShape){
            Draw.draw(Layer.turret - 1f, shapePass);
            // the heads of mass drivers and turrets are drawn at Layer.turret: their shading follows right after them (and before their heat glow, Layer.turretHeat)
            Draw.draw(Layer.turret + 0.05f, headPass);
            // units with a map: their shading follows right after each layer the game draws such units on
            FloatSeq unitLayers = surfaces.collectUnits();
            for(int i = 0; i < unitLayers.size; i++){
                float layer = unitLayers.get(i);
                int key = Math.round(layer * 10f);
                Runnable pass = unitShapePasses.get(key);
                if(pass == null) unitShapePasses.put(key, pass = () -> unitShapePass(layer));
                Draw.draw(layer + Surfaces.unitPassOffset, pass);
            }
        }
    }

    /**
     * What the shading needs besides the picture of the surfaces, once a frame: the light sources, each with its own
     * place, the glow of the ground and (for the glint) the direction most light comes from. Every light of the frame
     * is known by the time a pass runs: lights are reported while the frame is queued, the passes run when it is
     * played back.
     */
    private void prepareShape(){
        if(shapePrepared == Core.graphics.getFrameId()) return;
        shapePrepared = Core.graphics.getFrameId();
        pickLightMask();
        shapeAimed = false;
        // the light sources, each with its own place, for the shading
        shapeLit = tap != null;
        if(shapeLit) pickLights();
        // the glow of the ground is not among them: where it comes from at each tile is worked out apart (GlowField)
        if(tap != null && groundLevel() > 0f) glowField.build(tap.ground);
        // "the direction most light comes from" is now only needed by the glint
        if(wantGlint && lightMask != null && tap != null){
            Blending.disabled.apply();
            lightPass(aim, Math.max(scene.getWidth() / 4, 2), Math.max(scene.getHeight() / 4, 2), 1);
            aimFrame = Core.graphics.getFrameId();
            shapeAimed = true;
            Blending.normal.apply();
        }
    }

    /**
     * The same shading for the units that have a map, once per layer the game draws such units on, right after that
     * layer: the units of the layer are on screen then, and nothing that the game draws over them is yet. Their maps go
     * into a picture of their own (whatever is in it gets shaded), sprite for sprite as the game draws the unit - body,
     * weapons, legs, each turned as it is turned (Surfaces.drawUnits).
     */
    private void unitShapePass(float layer){
        if(!capturing || normalShader == null) return;
        Draw.flush();
        prepareShape();
        Blending previous = Draw.getBlend();
        Draw.sort(false);
        Draw.blend(coverBlend);
        Draw.shader(normalShader);
        headNormals.resize(scene.getWidth(), scene.getHeight());
        headNormals.begin(Color.clear);
        int drawn = surfaces.drawUnits(layer, cutState, layState);
        Draw.flush();
        headNormals.end();
        Draw.shader();
        Draw.blend(previous);
        Draw.sort(true);
        if(drawn > 0){
            unitsShaded += drawn;
            shapeFrom = headNormals;
            shapingUnits = true;
            tintBlend.apply();
            Draw.blit(headNormals.getTexture(), shapeShader);
            shapingUnits = false;
            shapeFrom = normals;
        }
        // back to what the frame expects (see copyScene)
        Blending.normal.apply();
    }

    /** How many units got their shading in this frame (for the test report). */
    private int unitsShaded;
    /** True while the shading is laid on units (unitShapePass), not on buildings. */
    private boolean shapingUnits;

    /**
     * Shading for the blocks that have a normal map, multiplied onto the frame as it stands at this moment - buildings
     * are drawn, units and everything above are not yet, so those are left alone.
     * The direction of the light is needed for it. Every light of the frame is already known here: lights are reported
     * while the frame is being queued, and this runs when the queue is played back.
     */
    private void shapePass(){
        if(!capturing || normalShader == null) return;
        Draw.flush();
        // which way the surface points at every pixel: the normal maps of the buildings in view, drawn sprite by sprite
        // into their own picture (the way drawUnitMirrors draws sprites from inside the frame)
        normals.resize(scene.getWidth(), scene.getHeight());
        normals.begin(Color.clear);
        Blending previous = Draw.getBlend();
        Draw.sort(false);
        Draw.blend(coverBlend);
        Draw.shader(normalShader);
        int drawn = surfaces.draw();
        Draw.flush();
        // what lies on those blocks (items on conveyors) is laid over them as small lumps of their own height, so they are lit as things on the belt instead of showing the belt's relief
        Draw.shader(itemShader);
        surfaces.drawCovers();
        Draw.flush();
        // ... except where the game draws a building over the item (an item half under the drill it came out of): there the
        // item is wiped out again and the building's own map, if it has one, drawn back
        Draw.shader();
        Draw.blend(eraseBlend);
        int wiped = surfaces.eraseOver();
        Draw.flush();
        Draw.blend(coverBlend);
        Draw.shader(normalShader);
        if(wiped > 0) surfaces.redrawOver();
        Draw.flush();
        Draw.shader();
        Draw.blend(previous);
        Draw.sort(true);
        normals.end();
        Blending.normal.apply();
        if(drawn == 0) return;
        normalsFrame = Core.graphics.getFrameId();
        prepareShape();
        shapeFrom = normals;
        tintBlend.apply();
        Draw.blit(normals.getTexture(), shapeShader);
        // back to what the frame expects (see copyScene)
        Blending.normal.apply();
    }

    /**
     * The same shading once more, for the heads of mass drivers and turrets. The game draws those at Layer.turret, after shapePass
     * has run - so by then they are on screen unshaded, over a base that is shaded already. Their maps go into a picture
     * of their own (only the heads: whatever is in it gets shaded, and the buildings must not be shaded twice), and the
     * shading is multiplied onto the frame as it stands now. Lights and light direction are those shapePass picked.
     */
    private void headPass(){
        if(!capturing || normalShader == null || normalsFrame != Core.graphics.getFrameId() || surfaces.headCount() == 0) return;
        Draw.flush();
        Blending previous = Draw.getBlend();
        Draw.sort(false);
        Draw.blend(coverBlend);
        Draw.shader(normalShader);
        headNormals.resize(scene.getWidth(), scene.getHeight());
        headNormals.begin(Color.clear);
        surfaces.drawHeads();
        Draw.flush();
        headNormals.end();
        // and on top of the buildings' picture, which the glint at the end of the frame reads (mshaders-post.frag): the
        // head hides the base under it there too
        normals.begin();
        surfaces.drawHeads();
        Draw.flush();
        normals.end();
        Draw.shader();
        Draw.blend(previous);
        Draw.sort(true);
        shapeFrom = headNormals;
        tintBlend.apply();
        Draw.blit(headNormals.getTexture(), shapeShader);
        shapeFrom = normals;
        // back to what the frame expects (see copyScene)
        Blending.normal.apply();
    }

    private void grabMask(){
        Texture t = Tmp.tr1.texture;
        // the shadow mask is exactly one texel per tile (BlockRenderer.java:257); anything else is not it
        mask = t != null && !t.isDisposed() && t.width == Vars.world.width() && t.height == Vars.world.height() ? t : null;
    }

    /** Half-size copy of the scene as it is right now, in the middle of the frame. */
    private void copyScene(FrameBuffer target){
        Draw.flush();
        target.resize(Math.max(scene.getWidth() / 2, 2), Math.max(scene.getHeight() / 2, 2));
        target.begin();
        Blending.disabled.apply();
        Draw.blit(scene.getTexture(), Shaders.screenspace);
        target.end(); // the scene buffer is the target again
        // Blending must be back on: the game blits its shield and build-beam buffers without setting it (Renderer.java:403-413)
        Blending.normal.apply();
    }

    private void snapshotBefore(){
        if(!capturing) return;
        copyScene(before);
        beforeFrame = Core.graphics.getFrameId();
    }

    /** Copies the scene right after the effect layers and works out what those layers added: shots, beams, explosions, bloom. */
    private void buildGlow(){
        if(!capturing || beforeFrame != Core.graphics.getFrameId()) return;
        copyScene(after);
        glow.resize(after.getWidth(), after.getHeight());
        glow.begin(Color.clear);
        Blending.disabled.apply();
        Draw.blit(after.getTexture(), glowShader);
        glow.end();
        Blending.normal.apply();
        glowFrame = Core.graphics.getFrameId();
    }

    /**
     * The mirror picture for the water and ice of the next frame: solid blocks flipped at their lower edge
     * (mshaders-objects.frag), then the units as flipped sprites.
     */
    private void buildObjects(){
        long frame = Core.graphics.getFrameId();
        if(!capturing || beforeFrame != frame) return;
        Draw.flush();
        objects.resize(Math.max(scene.getWidth() / 2, 2), Math.max(scene.getHeight() / 2, 2));
        objects.begin(Color.clear);
        mirrorSolid = occluders.texture();
        if(mirrorSolid != null){
            Blending.disabled.apply();
            // read from the full-size scene as it stands: the single pixel along a building's edge is what gets mirrored
            Draw.blit(scene.getTexture(), objectShader);
        }
        drawUnitMirrors();
        objects.end();
        Blending.normal.apply();
        objectsCam[0] = Core.camera.position.x - Core.camera.width / 2f;
        objectsCam[1] = Core.camera.position.y - Core.camera.height / 2f;
        objectsCam[2] = Core.camera.width;
        objectsCam[3] = Core.camera.height;
        objectsFrame = Core.graphics.getFrameId();
    }

    /**
     * Units into the mirror picture: the unit's own sprite, see-through, facing the SAME way as the unit, moved south.
     * Not flipped: the water is a level mirror, so it swaps up and down in height only - a unit seen from above keeps
     * its heading in the water, the way an aircraft over a lake does. (A flipped image pointed the other way and read
     * as a second unit flying backwards.) How far south stands for how high the unit is: half under a unit on the
     * ground, clear of a flying one. Sprites instead of the picture of the frame, because in the picture a unit cannot
     * be told from a conveyor or a laser. Drawn the way LightRenderer.draw() draws its sprites from inside the frame.
     */
    private void drawUnitMirrors(){
        Blending previous = Draw.getBlend();
        Draw.sort(false);
        Draw.blend(mirrorBlend);
        Draw.color();
        float w = Core.camera.width, h = Core.camera.height, margin = 64f;
        Groups.unit.intersect(Core.camera.position.x - w / 2f - margin, Core.camera.position.y - h / 2f - margin, w + margin * 2f, h + margin * 2f, unitMirror);
        Draw.reset();
        Draw.flush();
        Draw.blend(previous);
        Draw.sort(true);
    }

    private void mirrorUnit(Unit unit){
        TextureRegion icon = unit.type.fullIcon;
        // a unit hidden by the fog of war must not give itself away by its reflection
        if(icon == null || !icon.found() || !unit.isValid() || unit.inFogTo(Vars.player.team())) return;
        float size = Math.min(icon.width, icon.height) * icon.scl(), up = Math.min(Math.max(unit.elevation, 0f), 1f);
        // Away from the sun, the way shadows fall. How far stands for how high the unit is: half under a unit on the
        // ground; for a flying one as far as the game puts its shadow (UnitType.shadowTX/TY, about 17.7 units), so on
        // water the image sits where the shadow would be.
        float drop = size * 0.45f * (1f - up) + 17.7f * up;
        Draw.alpha(0.65f);
        Draw.rect(icon, unit.x - ShaderMod.sunX() * drop, unit.y - ShaderMod.sunY() * drop, unit.rotation - 90f);
    }

    private void end(){
        if(!capturing) return;
        capturing = false;
        scene.end();
        if(tap != null) tap.capture = tap.takeover = false;
        Blending.disabled.apply();

        // the light passes need the block mask and the recorded lights; the glint needs only the mask (it then has no moving lights)
        pickLightMask();
        boolean lit = (wantLight || wantGlint || wantShape) && lightMask != null && tap != null;
        lightReady = aimReady = false;
        glintReady = wantGlint && mask != null;
        shapeReady = wantShape && normalsFrame == Core.graphics.getFrameId();
        if(lit){
            pickLights();
            int w = Math.max(scene.getWidth() / 4, 2), h = Math.max(scene.getHeight() / 4, 2);
            // on a dark map the lights are already in the game's light picture (drawGameLighting), nothing is added on top
            if(wantLight && !takeover){
                // already made early in this frame if the shadows asked for it (lampLight)
                if(lightFrame != Core.graphics.getFrameId()){
                    lightPass(light, w, h, 0);
                    paintGround(light, false);
                }
                lightReady = true;
            }
            if(wantGlint || wantShape){
                // already made in the middle of this frame if a normal-mapped block needed it (shapePass)
                if(aimFrame != Core.graphics.getFrameId()) lightPass(aim, w, h, 1);
                aimReady = true;
            }
        }

        if(report){
            // only while the test kit is running: lets it write into its report what really happened this frame
            System.setProperty("mshaders.status", "mask " + (mask != null) + ", lights recorded " + (tap == null ? -1 : tap.data.size / LightTap.stride)
                + ", unit shapes " + unitShades.drawn() + ", units shaded by map " + unitsShaded + ", ground lights " + (tap == null ? -1 : tap.ground.size / LightTap.groundStride) + ", glow field " + glowField.ready() + " (last made in " + glowField.lastMillis + " ms)"
                + ", lights drawn " + (lit ? lightCount : 0) + ", light pass " + lightReady + ", game lighting drawn here " + gameLit + ", glint " + glintReady + ", reflection image " + (reflection() != null));
        }
        Draw.blit(scene.getTexture(), composite);
        Blending.normal.apply();
    }

    /** Runs the light shader into the raw buffer; the caller then blurs raw into its own target. */
    /** Solid blocks only if that picture exists; otherwise the game's shadow mask (every block that casts a shadow). */
    private void pickLightMask(){
        Texture own = occluders.texture();
        lightMask = own != null ? own : mask;
        lightMaskOwn = own != null;
    }

    private void rawLight(int w, int h, int mode){
        lightMode = mode;
        raw.resize(w, h);
        raw.begin(Color.clear);
        Draw.blit(lightMask != null ? lightMask : scene.getTexture(), lightShader);
        raw.end();
    }

    private void lightPass(FrameBuffer target, int w, int h, int mode){
        lightMode = mode;
        rawLight(w, h, mode);
        target.resize(w, h);
        target.begin(Color.clear);
        Draw.blit(raw.getTexture(), blurShader);
        target.end();
    }

    /**
     * Bright maps: the glow of the ground (LightTap.ground - slag, magma rock and the like, one light per tile) painted
     * into that light picture, each as a plain round glow, however many there are. Nothing stops this light and it is
     * not counted against the light limit. Where glows overlap the strongest counts, as in the game's own light picture
     * (LightRenderer.draw) - added up, a lake of slag was far brighter than the picture can hold and turned from orange
     * to yellow.
     * @param inFrame called while the frame's queue is being played back (the sprite batch is sorting)
     */
    private void paintGround(FrameBuffer target, boolean inFrame){
        if(tap == null || groundRegion == null) return;
        int n = tap.ground.size / LightTap.groundStride;
        float boost = groundLevel() * groundBoost;
        if(n == 0 || boost <= 0f) return;
        float wide = Math.min((float)Math.sqrt(boost), 2f) * 2f;
        float[] g = tap.ground.items;
        Draw.flush();
        if(inFrame) Draw.sort(false);
        Draw.color();
        target.begin();
        Gl.blendEquationSeparate(Gl.max, Gl.max);
        for(int i = 0; i < n; i++){
            int o = i * LightTap.groundStride;
            float strength = Math.min(g[o + 6] * groundGain * boost, 1f);
            float top = Math.max(g[o + 3], Math.max(g[o + 4], g[o + 5]));
            Draw.color(deep(g[o + 3], top) * strength, deep(g[o + 4], top) * strength, deep(g[o + 5], top) * strength, strength);
            Draw.rect(groundRegion, g[o], g[o + 1], g[o + 2] * wide, g[o + 2] * wide);
        }
        Draw.color();
        target.end();
        Gl.blendEquationSeparate(Gl.funcAdd, Gl.funcAdd);
        if(inFrame) Draw.sort(true);
        // the sprite batch has switched blending on; the passes around this one are drawn without
        Blending.disabled.apply();
    }

    /**
     * Dark maps: stands in for LightRenderer.draw() (graphics/LightRenderer.java:190-223) and makes the same kind of
     * picture - rgb = light colour, alpha = amount of light - which Shaders.light then lays over the world with the map's
     * ambient darkness. The difference: the round lights go through mshaders-light.frag, where blocks stop them.
     * Beams, sprite-shaped and custom lights (extras), and round lights beyond the light limit, are drawn the game's
     * way on top, so no light is lost; those are not stopped by blocks.
     * Called at Layer.light from inside the frame, like the original.
     */
    void drawGameLighting(arc.struct.Seq<Runnable> extras){
        if(!Vars.enableLight || tap == null) return;
        if(circleRegion == null) circleRegion = Core.atlas.find("circle-shadow");
        pickLightMask();
        pickLights();

        int w = Math.max(Core.graphics.getWidth() / 4, 2), h = Math.max(Core.graphics.getHeight() / 4, 2);
        Draw.flush();
        Blending.disabled.apply();
        // deepening the dark: how near the lights are, with a much wider reach than the light itself
        boolean deepen = Core.settings.getInt(ShaderMod.darkDepth, 50) > 0;
        if(deepen) lightPass(near, w, h, 3);
        rawLight(w, h, 2);
        gameLight.resize(w, h);
        Draw.color();
        gameLight.begin(Color.clear);
        Draw.sort(false);

        Blending.disabled.apply();
        Draw.blit(raw.getTexture(), blurShader);

        // as in the original: colours blend normally, the amount of light is the largest of all lights
        Gl.blendEquationSeparate(Gl.funcAdd, Gl.max);
        Blending.normal.apply();
        // the glow of the ground (slag, magma rock, ...), one light per tile: all of them, the way the game draws them -
        // at 100% of the "glowing ground" setting as strong and wide as the game has them, else more or less; the colour deeper (deep).
        // Before the beams and the other lights below: where one of those shines, the colour of the light is its own,
        // not that of the ground under it (drawn last, a field of hot rock gave every beam above it its orange).
        float level = groundLevel(), wide = Math.min((float)Math.sqrt(level), 2f) * 2f;
        float[] g = tap.ground.items;
        int grounds = level > 0f ? tap.ground.size / LightTap.groundStride : 0;
        for(int i = 0; i < grounds; i++){
            int o = i * LightTap.groundStride;
            float top = Math.max(g[o + 3], Math.max(g[o + 4], g[o + 5]));
            Draw.color(Color.toFloatBits(deep(g[o + 3], top), deep(g[o + 4], top), deep(g[o + 5], top), Math.min(g[o + 6] * level, 1f)));
            Draw.rect(circleRegion, g[o], g[o + 1], g[o + 2] * wide, g[o + 2] * wide);
        }
        Draw.color();
        for(Runnable run : extras){
            run.run();
        }
        float[] d = tap.data.items;
        int n = tap.data.size / LightTap.stride;
        for(int i = 0; i < n; i++){
            if(scores[i] < 0f || chosen[i]) continue;
            int o = i * LightTap.stride;
            Draw.color(Color.toFloatBits(d[o + 3], d[o + 4], d[o + 5], d[o + 6]));
            Draw.rect(circleRegion, d[o], d[o + 1], d[o + 2] * 2f, d[o + 2] * 2f);
        }
        Draw.reset();
        Draw.sort(true);
        gameLight.end();
        Gl.blendEquationSeparate(Gl.funcAdd, Gl.funcAdd);

        // what is in the air is above the blocks: the shade a wall throws on the ground does not lie on it
        lit = liftAir(w, h);
        Draw.color();
        // the colour of the light onto what it lights (the game's lighting below only takes darkness away)
        if(Core.settings.getInt(ShaderMod.lightsColor, 100) > 0){
            tintBlend.apply();
            Draw.blit(lit.getTexture(), tintShader);
            Blending.normal.apply();
        }
        Shaders.light.ambient.set(Vars.state.rules.ambientLight);
        lit.blit(Shaders.light);
        // the less light, the darker: black over what is far from every light (same blending as the line above)
        if(deepen) Draw.blit(near.getTexture(), deepShader);
        gameLit = true;
    }

    /**
     * Dark maps: the light picture (gameLight) is a picture of the ground - behind a wall that stops a light the ground
     * is dark. The game lays that picture over everything, the units in the air too, so the shade of a wall lay ON a
     * unit flying over it: black bars across the unit, most of all from its own glow, which every wall under it shades.
     * A unit in the air is above the blocks. So: the shapes of the units in the air go into a picture of their own, the
     * same lights are worked out once more with nothing in their way, and inside those shapes the light picture takes
     * that light instead (mshaders-air.frag).
     * @return the picture to light this frame with: gameLight itself while nothing is in the air
     */
    private FrameBuffer liftAir(int w, int h){
        Draw.flush();
        Blending previous = Draw.getBlend();
        Draw.sort(false);
        Draw.blend(mirrorBlend);
        Draw.color();
        air.resize(scene.getWidth(), scene.getHeight());
        air.begin(Color.clear);
        airCount = 0;
        float cw = Core.camera.width, ch = Core.camera.height, margin = 64f;
        Groups.unit.intersect(Core.camera.position.x - cw / 2f - margin, Core.camera.position.y - ch / 2f - margin, cw + margin * 2f, ch + margin * 2f, airUnit);
        Draw.flush();
        air.end();
        Draw.blend(previous);
        Draw.sort(true);
        if(airCount > 0){
            Blending.disabled.apply();
            lightOpen = true;
            lightPass(open, w, h, 2);
            lightOpen = false;
            airLit.resize(scene.getWidth(), scene.getHeight());
            airLit.begin(Color.clear);
            Draw.blit(gameLight.getTexture(), airShader);
            airLit.end();
        }
        // what the lighting below expects (the state the light picture was left in)
        Blending.normal.apply();
        return airCount > 0 ? airLit : gameLight;
    }

    /** A unit in the air, as its shape: the layer rule of the game (UnitType.draw) - off the ground by more than half. */
    private void airUnit(Unit unit){
        TextureRegion icon = unit.type.fullIcon;
        if(icon == null || !icon.found() || !unit.isValid() || unit.elevation <= 0.5f || unit.inFogTo(Vars.player.team())) return;
        Draw.rect(icon, unit.x, unit.y, unit.rotation - 90f);
        airCount++;
    }

    /** Copies the recorded lights that reach the screen into the shader arrays; if there are too many, the strongest stay. */
    private void pickLights(){
        float[] d = tap.data.items;
        int n = tap.data.size / LightTap.stride;
        float cx = Core.camera.position.x, cy = Core.camera.position.y, hw = Core.camera.width / 2f, hh = Core.camera.height / 2f;
        if(scores.length < n){
            scores = new float[n * 2];
            chosen = new boolean[n * 2];
        }
        int visible = 0;
        for(int i = 0; i < n; i++){
            int o = i * LightTap.stride;
            float r = d[o + 2];
            // lights just outside the view still count: their light, and for the deepening their wider surroundings
            float reach = r * 2.8f;
            boolean inView = Math.abs(d[o] - cx) < hw + reach && Math.abs(d[o + 1] - cy) < hh + reach;
            scores[i] = inView ? r * d[o + 6] : -1f;
            if(inView) visible++;
        }
        int limit = lightLimit();
        float cut = -0.5f;
        if(visible > limit){
            // threshold = score of the limit-th strongest; a partial selection is enough at these sizes
            float[] copy = new float[n];
            System.arraycopy(scores, 0, copy, 0, n);
            java.util.Arrays.sort(copy);
            cut = copy[n - limit];
        }
        // Two rounds, so the strongest always get in: first every light stronger than the threshold, then lights exactly
        // at the threshold until the places are used up. (One round in recording order was a bug: in a busy base many weak
        // lights of the same strength are reported first and took every place, and the lamps were left out - but only
        // while enough of them were in view, so the lamp light switched on and off with the zoom.)
        lightCount = 0;
        for(int i = 0; i < n; i++) chosen[i] = false;
        for(int round = 0; round < 2; round++){
            for(int i = 0; i < n && lightCount < limit; i++){
                if(chosen[i] || scores[i] < 0f) continue;
                if(round == 0 ? scores[i] <= cut : scores[i] < cut) continue;
                chosen[i] = true;
                int o = i * LightTap.stride, k = lightCount * 4;
                lightData[k] = d[o];
                lightData[k + 1] = d[o + 1];
                lightData[k + 2] = d[o + 2];
                lightData[k + 3] = d[o + 6];
                lightColors[k] = d[o + 3];
                lightColors[k + 1] = d[o + 4];
                lightColors[k + 2] = d[o + 5];
                // what the light sits in; for the glow of a flying unit, how high it hangs instead (1000 + world units)
                // a light in the air: 1000 + its height (below 100) + 100 * the radius of the unit it hangs on (whole units)
                float code = d[o + 7] > 0f ? 1000f + Math.min(d[o + 7], 99f) + 100f * Math.min((float)Math.floor(tap.unitRadiusAt(d[o], d[o + 1])), 80f) : lampCode(d[o], d[o + 1]);
                // a light that sits on a unit: minus the radius of that unit, which then is not in the way of its own light (mshaders-light.frag, unitsOpen)
                if(code == 0f) code = -tap.unitRadiusAt(d[o], d[o + 1]);
                lightColors[k + 3] = code;
                lightCount++;
            }
        }
    }

    /**
     * What a light sits in, for the light shader (mshaders-light.frag, reach): the distance from the light inside which
     * blocks belong to its own building - half the building's diagonal, so the whole of it lets its own light out -
     * plus 100 if that building is a core, whose light also passes through the blocks that touch it.
     * 0 when the light is not in a building (a unit, a shot).
     */
    private static float lampCode(float x, float y){
        mindustry.gen.Building build = Vars.world.buildWorld(x, y);
        if(build == null) return 0f;
        float own = Math.min(Math.max(build.block.size * 0.7072f + 0.3f, 2.2f), 99f);
        return own + (build.block instanceof mindustry.world.blocks.storage.CoreBlock ? 100f : 0f);
    }

    private void setCamera(Shader s){
        if(s.hasUniform("u_campos")) s.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        if(s.hasUniform("u_resolution")) s.setUniformf("u_resolution", Core.camera.width, Core.camera.height);
        if(s.hasUniform("u_world")) s.setUniformf("u_world", Vars.world.width() * (float)Vars.tilesize, Vars.world.height() * (float)Vars.tilesize);
    }

    private void applyComposite(Shader s){
        boolean post = on(ShaderMod.postOn);
        set(s, "u_vignette", post ? ShaderMod.percent(ShaderMod.vignette, 35) : 0f);
        set(s, "u_saturation", post ? ShaderMod.percent(ShaderMod.saturation, 115) : 1f);
        set(s, "u_contrast", post ? ShaderMod.percent(ShaderMod.contrast, 108) : 1f);
        set(s, "u_warmth", post ? ShaderMod.percent(ShaderMod.warmth, 15) : 0f);

        set(s, "u_light", lightReady ? ShaderMod.percent(ShaderMod.lightsStrength, 100) : 0f);
        set(s, "u_glint", glintReady ? ShaderMod.percent(ShaderMod.glintStrength, 100) : 0f);
        set(s, "u_aimed", aimReady ? 1f : 0f);
        set(s, "u_band", ShaderMod.percent(ShaderMod.glintBand, 100));
        set(s, "u_time", Time.globalTime / 60f);
        set(s, "u_day", ShaderMod.daylight());
        if(s.hasUniform("u_sunDir")) s.setUniformf("u_sunDir", ShaderMod.sunX(), ShaderMod.sunY());
        setCamera(s);
        if(lightReady){
            light.getTexture().bind(1);
            set(s, "u_lightmap", 1);
        }
        if(glintReady || shapeReady){
            (aimReady ? aim : scene).getTexture().bind(2);
            set(s, "u_aim", 2);
        }
        if(glintReady){
            mask.bind(3);
            set(s, "u_mask", 3);
        }
        // blocks that are not drawn as full squares: no glint on the ground in the corners they leave empty
        Texture shapes = glintReady ? occluders.shapes() : null, outlines = shapes == null ? null : occluders.outlines();
        if(outlines != null){
            shapes.bind(5);
            outlines.bind(6);
        }
        set(s, "u_shapes", 5);
        set(s, "u_outlines", 6);
        set(s, "u_shaped", outlines != null ? 1f : 0f);
        // how glossy each block is as a whole: the glint of the blocks that have no map of their own goes by it
        Texture glossy = glintReady ? occluders.gloss() : null;
        if(glossy != null) glossy.bind(7);
        set(s, "u_gloss", 7);
        set(s, "u_glossy", glossy != null ? 1f : 0f);
        // blocks with a normal map: their shading was laid on in the middle of the frame (shapePass); here the map only
        // tells the glint which way a face points
        boolean shapedGlint = glintReady && shapeReady;
        set(s, "u_shape", shapedGlint ? 1f : 0f);
        if(shapedGlint){
            normals.getTexture().bind(4);
            set(s, "u_surface", 4);
        }
        // leave texture unit 0 active, the sprite batch relies on it
        scene.getTexture().bind(0);
    }

    // Shader.setUniform* throws for a uniform the GLSL compiler removed, so check first
    static void set(Shader s, String name, float value){
        if(s.hasUniform(name)) s.setUniformf(name, value);
    }

    static void set(Shader s, String name, int value){
        if(s.hasUniform(name)) s.setUniformi(name, value);
    }
}
