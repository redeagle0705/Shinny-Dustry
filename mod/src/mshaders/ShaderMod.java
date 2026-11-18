package mshaders;

import arc.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.mod.*;

/** Visual effects only. Each effect has its own switch in Settings > Shaders and does nothing when switched off. */
public class ShaderMod extends Mod{
    // setting keys (stored in the game's settings file)
    public static final String
    waterOn = "mshaders-water", waterWave = "mshaders-water-wave", waterSparkle = "mshaders-water-sparkle", waterFoam = "mshaders-water-foam",
    iceOn = "mshaders-ice", iceStrength = "mshaders-ice-strength",
    floorsOn = "mshaders-floors", floorRock = "mshaders-floor-rock", floorSand = "mshaders-floor-sand", floorSnow = "mshaders-floor-snow",
    floorGrass = "mshaders-floor-grass", floorHot = "mshaders-floor-hot", floorMetal = "mshaders-floor-metal", floorRelief = "mshaders-floor-relief",
    reflectOn = "mshaders-reflect", reflectStrength = "mshaders-reflect-strength",
    shadowOn = "mshaders-shadow", shadowLength = "mshaders-shadow-length", shadowStrength = "mshaders-shadow-strength", shadowCycle = "mshaders-shadow-cycle", shadowQuality = "mshaders-shadow-quality", sunAngle = "mshaders-sun-angle",
    lightsOn = "mshaders-lights", lightsStrength = "mshaders-lights-strength", darkDepth = "mshaders-dark-depth", lightsColor = "mshaders-lights-color", lightsMoving = "mshaders-lights-moving", lightsMax = "mshaders-lights-max", lightsAir = "mshaders-lights-air", lightsGround = "mshaders-lights-ground", lightsUnits = "mshaders-lights-units", lightsUnitsDepth = "mshaders-lights-units-depth",
    // found, not listed: the slider appears once a thorium reactor has blown up in view (LightTap.reactorBoost)
    reactorFun = "mshaders-reactor-fun", reactorFound = "mshaders-reactor-found",
    glintOn = "mshaders-glint", glintStrength = "mshaders-glint-strength", glintBand = "mshaders-glint-band",
    normalsOn = "mshaders-normals", normalsStrength = "mshaders-normals-strength", normalsMetal = "mshaders-normals-metal", normalsHeight = "mshaders-normals-height", normalsOwnLight = "mshaders-normals-own-light", boostersOn = "mshaders-boosters", boostersTint = "mshaders-boosters-tint", boostersBreath = "mshaders-boosters-breath", devWatch = "mshaders-dev-watch", devButton = "mshaders-dev-button",
    postOn = "mshaders-post", vignette = "mshaders-vignette", saturation = "mshaders-saturation", contrast = "mshaders-contrast", warmth = "mshaders-warmth";

    private EngineDialog engineDialog;
    public final WaterFx water = new WaterFx();
    public final Scene scene = new Scene();
    public final ShadowFx shadow = new ShadowFx();
    public final IceFx ice = new IceFx();
    public final FloorFx floors = new FloorFx();

    /** Called on the main thread after every game module is initialized (ClientLauncher.update), so GL objects can be made here. */
    @Override
    public void init(){
        if(Vars.headless) return;

        Core.settings.defaults(
            waterOn, true, waterWave, 100, waterSparkle, 100, waterFoam, 100,
            iceOn, true, iceStrength, 100,
            floorsOn, true, floorRock, 100, floorSand, 100, floorSnow, 100, floorGrass, 100, floorHot, 100, floorMetal, 100, floorRelief, 100,
            reflectOn, true, reflectStrength, 100,
            shadowOn, true, shadowLength, 15, shadowStrength, 70, shadowCycle, 4, shadowQuality, 80, sunAngle, 45,
            lightsOn, true, lightsStrength, 100, darkDepth, 50, lightsColor, 100, lightsMoving, 100, lightsMax, 64, lightsAir, 100, lightsGround, 100, lightsUnits, true, lightsUnitsDepth, 65,
            glintOn, true, glintStrength, 100, glintBand, 100,
            normalsOn, true, normalsStrength, 100, normalsMetal, 100, normalsHeight, true, normalsOwnLight, false, boostersOn, true, boostersTint, true, boostersBreath, 100, devWatch, false, devButton, false,
            postOn, true, vignette, 35, saturation, 115, contrast, 108, warmth, 15
        );

        // the sprites this mod replaces get back the dark outline the game's own sprites have (turret heads, mass driver)
        int outlines = Outlines.apply();
        if(outlines > 0) Log.info("[mshaders] outlines painted around @ replaced sprite(s)", outlines);
        // where a folder of pictures is watched, its sprites count from the start (as its maps do), whether or not
        // they are read again by themselves later
        Outlines.takeWatched();

        // the engines of units as flames instead of two discs
        try{
            int engines = Boosters.install();
            Log.info("[mshaders] engines drawn as streaks: @, @ unit type(s) with a streak colour of their own", engines, Boosters.colours());
        }catch(Throwable t){
            Log.err("[mshaders] engines stay as the game draws them", t);
        }

        // the scene registers its frame hooks first, so its buffer is open before the others run
        scene.init();
        water.init(scene);
        shadow.init(scene);
        ice.init(scene);
        floors.init();

        // The settings are folded under the headline of their section (Folds.java): the switch of each effect, then its sliders.
        Vars.ui.settings.addCategory(Core.bundle.get("mshaders.category", "Shaders"), Icon.effect, t -> {
            Folds f = new Folds(t);
            String off = Core.bundle.get("mshaders.off", "off");

            f.section("water");
            f.check(waterOn, true);
            f.slider(waterWave, 100, 0, 200, 5, i -> i + "%");
            f.slider(waterSparkle, 100, 0, 200, 5, i -> i + "%");
            f.slider(waterFoam, 100, 0, 200, 5, i -> i + "%");
            f.check(iceOn, true);
            f.slider(iceStrength, 100, 0, 200, 5, i -> i + "%");
            f.check(reflectOn, true);
            f.slider(reflectStrength, 100, 0, 200, 5, i -> i + "%");

            f.section("ground");
            f.check(floorsOn, true);
            for(String key : new String[]{floorRock, floorSand, floorSnow, floorGrass, floorHot, floorMetal, floorRelief}){
                f.slider(key, 100, 0, 200, 5, i -> i == 0 ? off : i + "%");
            }

            f.section("shadow");
            f.check(shadowOn, true);
            f.slider(shadowLength, 15, 0, 40, 1, i -> Strings.autoFixed(i / 10f, 1));
            f.slider(shadowStrength, 70, 0, 100, 5, i -> i + "%");
            f.slider(shadowQuality, 80, 0, 100, 5, i -> i + "%");
            f.slider(sunAngle, 45, 0, 355, 5, i -> i + "°");
            f.slider(shadowCycle, 4, 0, 30, 1, i -> i == 0 ? off : Core.bundle.format("mshaders.minutes", i));

            f.section("lights");
            f.check(lightsOn, true);
            f.check(lightsUnits, true);
            f.slider(lightsUnitsDepth, 65, 10, 100, 5, i -> i + "%");
            f.slider(lightsStrength, 100, 0, 200, 5, i -> i + "%");
            f.slider(darkDepth, 50, 0, 100, 5, i -> i == 0 ? off : i + "%");
            f.slider(lightsColor, 100, 0, 200, 5, i -> i == 0 ? off : i + "%");
            f.slider(lightsMoving, 100, 100, 500, 25, i -> i + "%");
            f.slider(lightsMax, 64, 16, Scene.maxLights, 16, i -> i + "");
            f.slider(lightsAir, 100, 0, 300, 25, i -> i == 0 ? off : i + "%");
            f.slider(lightsGround, 100, 0, 300, 25, i -> i == 0 ? off : i + "%");
            f.secret(reactorFound, reactorFun, 10, 1, 50, 1, i -> "x" + i);
            folds = f;

            f.section("blocks");
            f.check(glintOn, true);
            f.slider(glintStrength, 100, 0, 200, 5, i -> i + "%");
            f.slider(glintBand, 100, 0, 200, 5, i -> i == 0 ? off : i + "%");
            f.check(normalsOn, true);
            f.slider(normalsStrength, 100, 0, 200, 5, i -> i + "%");
            f.slider(normalsMetal, 100, 0, 200, 5, i -> i == 0 ? off : i + "%");
            f.check(normalsHeight, true);

            f.section("units");
            f.check(boostersOn, true);
            f.check(boostersTint, true);
            f.slider(boostersBreath, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.button("mshaders-engines-edit", () -> {
                if(engineDialog == null) engineDialog = new EngineDialog();
                engineDialog.show();
            });
            f.check(normalsOwnLight, false);

            f.section("post");
            f.check(postOn, true);
            f.slider(vignette, 35, 0, 100, 5, i -> i + "%");
            f.slider(saturation, 115, 0, 200, 5, i -> i + "%");
            f.slider(contrast, 108, 50, 150, 2, i -> i + "%");
            f.slider(warmth, 15, -100, 100, 5, i -> (i > 0 ? "+" : "") + i);
        });

        // The two switches for those who draw pictures are no effects: they sit with the game's own developer options
        // (Settings > Developer Options), after the game's.
        try{
            Vars.ui.settings.dev.checkPref(devWatch, false);
            Vars.ui.settings.dev.checkPref(devButton, false);
        }catch(Throwable t){
            Log.err("[mshaders] the switches for drawing could not be added to the developer options", t);
        }

        // For those who draw pictures for the mod: a button at the left edge of the screen, while the game is played and
        // the setting is on, that reads every picture again from its file - normal maps, height maps, gloss maps and
        // replaced sprites - so a drawing can be looked at without starting the game anew.
        try{
            Vars.ui.hudGroup.fill(t -> {
                t.left();
                t.button(Core.bundle.get("mshaders.dev.reload", "Read pictures again"), Icon.refresh, mindustry.ui.Styles.flatt, 24f, () -> {
                    int[] n = scene.reloadPictures();
                    Vars.ui.showInfoToast(Core.bundle.format("mshaders.dev.reloaded", n[0], n[1], n[2], n[3] < 0 ? "?" : String.valueOf(n[3])), 4f);
                }).size(190f, 44f).margin(6f).name("mshaders-reload");
                t.visible(() -> Core.settings.getBool(devButton, false) && Vars.state.isGame() && Vars.ui.hudfrag.shown);
            });
        }catch(Throwable t){
            Log.err("[mshaders] the button that reads pictures again could not be made", t);
        }

        Log.info("[mshaders] water shader: @, ice shader: @, ground shaders: @, screen effects: @, light listener: @, directional shadows: @",
            state(water.ready()), state(ice.ready()), state(floors.ready()), state(scene.ready()), state(scene.lightsReady()), state(shadow.ready()));
    }

    private static Folds folds;

    /**
     * A thorium reactor has blown up in view for the first time: from now on the settings show "ReactorFUNvalue" under
     * Lights. The message that pops up only hints at it - where the setting is, is left to be found.
     * Called while the frame is being drawn, so what touches the UI waits until the frame is done.
     */
    static void foundReactor(){
        Core.settings.put(reactorFound, true);
        Core.app.post(() -> {
            if(folds != null) folds.rebuild();
            Vars.ui.hudfrag.showToast(Icon.effect, Core.bundle.get("mshaders.reactorfound", "Thorium wants to shine brighter."));
        });
    }

    private static String state(boolean ready){
        return ready ? "ok" : "unavailable";
    }

    /**
     * Reads a shader file from shaders/ (the mod's or the game's) and puts the named file in place of every line of the
     * form  #include "name"  - GLSL has no include of its own.
     */
    static String source(String name){
        StringBuilder out = new StringBuilder();
        for(String line : Vars.tree.get("shaders/" + name).readString().split("\n")){
            String trimmed = line.trim();
            if(trimmed.startsWith("#include \"") && trimmed.endsWith("\"")){
                out.append(source(trimmed.substring(10, trimmed.length() - 1)));
            }else{
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Direction towards the sun on the ground, as the setting says: 0 = right, 90 = up. One value for shadows, normal-map shading and ground relief. */
    static float sunX(){
        return Mathf.cosDeg(Core.settings.getInt(sunAngle, 45));
    }

    static float sunY(){
        return Mathf.sinDeg(Core.settings.getInt(sunAngle, 45));
    }

    /** How much daylight the map has: 1 on a bright map; on a dark one what its darkness leaves, but not less than 0.15. The sun counts by this much. */
    static float daylight(){
        return Vars.state.rules.lighting ? Math.max(1f - Vars.state.rules.ambientLight.a, 0.15f) : 1f;
    }

    static float percent(String key, int def){
        return Core.settings.getInt(key, def) / 100f;
    }
}
