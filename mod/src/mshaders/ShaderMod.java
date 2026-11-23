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
    liquidsOn = "mshaders-liquids", liquidsStrength = "mshaders-liquids-strength",
    waterOn = "mshaders-water", waterWave = "mshaders-water-wave", waterSparkle = "mshaders-water-sparkle", waterFoam = "mshaders-water-foam",
    iceOn = "mshaders-ice", iceStrength = "mshaders-ice-strength",
    floorsOn = "mshaders-floors", floorRock = "mshaders-floor-rock", floorSand = "mshaders-floor-sand", floorSnow = "mshaders-floor-snow",
    floorGrass = "mshaders-floor-grass", floorHot = "mshaders-floor-hot", floorMetal = "mshaders-floor-metal", floorRelief = "mshaders-floor-relief",
    reflectOn = "mshaders-reflect", reflectStrength = "mshaders-reflect-strength",
    shadowOn = "mshaders-shadow", shadowLength = "mshaders-shadow-length", shadowStrength = "mshaders-shadow-strength", shadowCycle = "mshaders-shadow-cycle", shadowQuality = "mshaders-shadow-quality", shadowAo = "mshaders-shadow-ao",
    shieldsOn = "mshaders-shields", shieldsBubble = "mshaders-shields-bubble", shieldsRipple = "mshaders-shields-ripple", shieldsLight = "mshaders-shields-light", shieldsHex = "mshaders-shields-hex", shieldsRefract = "mshaders-shields-refract", shieldsTurns = "mshaders-shields-turns", shieldsSheen = "mshaders-shields-sheen",
    shieldsCrack = "mshaders-shields-crack",
    powerOn = "mshaders-power", powerStrength = "mshaders-power-strength", factoriesOn = "mshaders-factories",
    orePeekOn = "mshaders-ore-peek", weatherOn = "mshaders-weather", weatherStrength = "mshaders-weather-strength",
    heatOn = "mshaders-heat", heatStrength = "mshaders-heat-strength", beamsOn = "mshaders-beams",
    hitHeatOn = "mshaders-hit-heat", hitHeatStrength = "mshaders-hit-heat-strength", tracksOn = "mshaders-tracks", tracksStrength = "mshaders-tracks-strength", wakesOn = "mshaders-wakes", wakesStrength = "mshaders-wakes-strength",
    dayCampaign = "mshaders-day-campaign", dayOwn = "mshaders-day-own", dayMinutes = "mshaders-day-minutes", dayTint = "mshaders-day-tint",
    smokeShadowsOn = "mshaders-smoke-shadows", smokeShadowsStrength = "mshaders-smoke-shadows-strength",
    cutawayStyle = "mshaders-cutaway", cutawaySize = "mshaders-cutaway-size",
    statusesOn = "mshaders-statuses", statusesStrength = "mshaders-statuses-strength",
    pulsesOn = "mshaders-pulses", pulsesWave = "mshaders-pulses-wave", pulsesHaze = "mshaders-pulses-haze",
    blastsOn = "mshaders-blasts", blastsShock = "mshaders-blasts-shock", blastsFlash = "mshaders-blasts-flash", blastsScorch = "mshaders-blasts-scorch", blastsScorchTime = "mshaders-blasts-scorch-time", blastsDust = "mshaders-blasts-dust", sunAngle = "mshaders-sun-angle",
    lightsOn = "mshaders-lights", lightsStrength = "mshaders-lights-strength", darkDepth = "mshaders-dark-depth", lightsColor = "mshaders-lights-color", lightsMoving = "mshaders-lights-moving", lightsMax = "mshaders-lights-max", lightsDetail = "mshaders-lights-detail", lightsAir = "mshaders-lights-air", lightsShots = "mshaders-lights-shots", shotShadows = "mshaders-shot-shadows", lightsGround = "mshaders-lights-ground", lightsUnits = "mshaders-lights-units", lightsUnitsDepth = "mshaders-lights-units-depth",
    // found, not listed: the slider appears once a thorium reactor has blown up in view (LightTap.reactorBoost)
    reactorFun = "mshaders-reactor-fun", reactorFound = "mshaders-reactor-found",
    glintOn = "mshaders-glint", glintStrength = "mshaders-glint-strength", glintBand = "mshaders-glint-band",
    normalsOn = "mshaders-normals", normalsStrength = "mshaders-normals-strength", normalsMetal = "mshaders-normals-metal", normalsHeight = "mshaders-normals-height", normalsOwnLight = "mshaders-normals-own-light", boostersOn = "mshaders-boosters", boostersTint = "mshaders-boosters-tint", boostersBreath = "mshaders-boosters-breath", boostersFlicker = "mshaders-boosters-flicker", devWatch = "mshaders-dev-watch", devButton = "mshaders-dev-button", devGlossTable = "mshaders-dev-gloss-table", devClock = "mshaders-dev-clock", devSquares = "mshaders-light-squares",
    postOn = "mshaders-post", vignette = "mshaders-vignette", saturation = "mshaders-saturation", contrast = "mshaders-contrast", warmth = "mshaders-warmth", spare = "mshaders-spare";

    /**
     * Before the game puts its sprites together (this runs when the mod is loaded, the packing comes later):
     * room for all of them in ONE texture.
     *
     * When any mod brings sprites, the game packs every sprite anew (Mods.loadAsync), largest first, into pages of
     * 4096 x 4096 (MultiPacker.PageType.main). Its own sprites alone fill such a page to the brim - packed here they
     * no longer quite fit, and the smallest ones (walls, conveyors, items, small turrets: everything of 32 pixels) end
     * up on a second page. From then on, every time a small sprite is drawn after a large one the run of sprites the
     * graphics card is handed in one go ends. Measured on a test base of 309 blocks of eight kinds: 56 draw calls a
     * frame without the mod, 213 with it - with every effect switched off. A desktop card does not mind; a phone
     * does, and a large base is thousands of them. (The maker: "even with every option off the frame rate is lower
     * than without the mod - why?")
     *
     * A page of 4096 x 8192 holds them all, and is no more memory than the two pages were (the second one was made
     * as large as the first, and stood nearly empty). Where the graphics card takes no texture that tall, the game
     * itself cuts the page back to what it takes (MultiPacker: the smaller of the two), and all is as before.
     */
    public ShaderMod(){
        try{
            mindustry.graphics.MultiPacker.PageType.main.height = Math.max(mindustry.graphics.MultiPacker.PageType.main.height, 8192);
        }catch(Throwable t){
            Log.err("[mshaders] the game's sprite texture stays as it is", t);
        }
    }

    private EngineDialog engineDialog;
    public final WaterFx water = new WaterFx();
    public final LiquidFx liquids = new LiquidFx();
    public final Scene scene = new Scene();
    public final ShadowFx shadow = new ShadowFx();
    public final IceFx ice = new IceFx();
    public final FloorFx floors = new FloorFx();

    /** Called on the main thread after every game module is initialized (ClientLauncher.update), so GL objects can be made here. */
    @Override
    public void init(){
        if(Vars.headless) return;

        Core.settings.defaults(
            liquidsOn, true, liquidsStrength, 100,
            waterOn, true, waterWave, 100, waterSparkle, 100, waterFoam, 100,
            iceOn, true, iceStrength, 100,
            floorsOn, true, floorRock, 100, floorSand, 100, floorSnow, 100, floorGrass, 100, floorHot, 100, floorMetal, 100, floorRelief, 100,
            reflectOn, true, reflectStrength, 100,
            shadowOn, true, shadowLength, 15, shadowStrength, 70, shadowCycle, 4, shadowQuality, 80, shadowAo, 100, sunAngle, 45,
            lightsOn, true, lightsStrength, 100, darkDepth, 50, lightsColor, 100, lightsMoving, 100, lightsMax, 64, lightsDetail, 100, lightsAir, 100, lightsShots, 100, shotShadows, true, lightsGround, 100, lightsUnits, true, lightsUnitsDepth, 65,
            glintOn, true, glintStrength, 100, glintBand, 100,
            normalsOn, true, normalsStrength, 100, normalsMetal, 100, normalsHeight, true, normalsOwnLight, false, boostersOn, true, boostersTint, true, boostersBreath, 100, boostersFlicker, 100, devWatch, false, devButton, false, devGlossTable, false, devClock, false, devSquares, true,
            shieldsOn, true, shieldsBubble, 100, shieldsRipple, 100, shieldsLight, 100, shieldsHex, 100, shieldsRefract, 100, shieldsTurns, true, shieldsSheen, 100, shieldsCrack, 100,
            pulsesOn, true, pulsesWave, 100, pulsesHaze, 100,
            statusesOn, true, statusesStrength, 100,
            cutawayStyle, 1, cutawaySize, 100, orePeekOn, true, weatherOn, true, weatherStrength, 100, heatOn, true, heatStrength, 100, beamsOn, true, hitHeatOn, true, hitHeatStrength, 100, tracksOn, true, tracksStrength, 100, wakesOn, true, wakesStrength, 100, dayCampaign, true, dayOwn, false, dayMinutes, 12, dayTint, 100, smokeShadowsOn, true, smokeShadowsStrength, 100,
            powerOn, true, powerStrength, 100, factoriesOn, true,
            blastsOn, true, blastsShock, 100, blastsFlash, 100, blastsScorch, 100, blastsScorchTime, 5, blastsDust, 100,
            postOn, true, vignette, 35, saturation, 115, contrast, 108, warmth, 15, spare, 100
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
        liquids.init();
        shadow.init(scene);
        ice.init(scene);
        floors.init();
        Shields.init(scene);
        Skies.init(scene);
        HeatHaze.init();
        Beams.init();
        HitHeat.init();
        SnowCover.init();
        Tracks.init();
        Wakes.init();
        Pulses.init();
        Statuses.init();
        Cutaway.init();
        OrePeek.init();
        PowerLines.init();
        Factories.init();
        try{
            Log.info("[mshaders] explosion effects with a shock wave: @", Blasts.install());
            Log.info("[mshaders] smoke effects with a shadow: @", SmokeShadows.install());
        }catch(Throwable t){
            Log.err("[mshaders] explosions stay as the game draws them", t);
        }

        // The settings are folded under the headline of their section (Folds.java): the switch of each effect, then its sliders.
        Vars.ui.settings.addCategory(Core.bundle.get("mshaders.category", "Shaders"), Icon.effect, t -> {
            Folds f = new Folds(t);
            String off = Core.bundle.get("mshaders.off", "off");

            // Presets (friends of the maker: "the mod is getting heavier - split the features"): one press sets many
            // switches at once; each can still be changed by hand after. Measured on a large base: the block shadows,
            // the normal-map shading and the glint cost the most there; the mirror images cost on water maps.
            f.section("presets");
            // five steps, as other games have them ("low / medium / high / ultra / maximum")
            for(int level = 0; level < 5; level++){
                int l = level;
                f.button("mshaders-preset-" + l, () -> {
                    preset(l);
                    f.rebuild();
                });
            }

            f.section("water");
            f.check(waterOn, true);
            f.slider(waterWave, 100, 0, 200, 5, i -> i + "%");
            f.slider(waterSparkle, 100, 0, 200, 5, i -> i + "%");
            f.slider(waterFoam, 100, 0, 200, 5, i -> i + "%");
            f.check(wakesOn, true);
            f.slider(wakesStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(iceOn, true);
            f.slider(iceStrength, 100, 0, 200, 5, i -> i + "%");
            f.check(reflectOn, true);
            f.slider(reflectStrength, 100, 0, 200, 5, i -> i + "%");
            f.check(liquidsOn, true);
            f.slider(liquidsStrength, 100, 0, 100, 10, i -> i == 0 ? off : i + "%");

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
            f.slider(shadowAo, 100, 0, 150, 5, i -> i + "%");
            f.slider(sunAngle, 45, 0, 355, 5, i -> i + "°");
            f.slider(shadowCycle, 4, 0, 30, 1, i -> i == 0 ? off : Core.bundle.format("mshaders.minutes", i));

            f.section("day");
            f.check(dayCampaign, true);
            f.check(dayOwn, false);
            f.slider(dayMinutes, 12, 2, 60, 1, i -> Core.bundle.format("mshaders.minutes", i));
            f.slider(dayTint, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");

            f.section("lights");
            f.check(lightsOn, true);
            f.check(lightsUnits, true);
            f.slider(lightsUnitsDepth, 65, 10, 100, 5, i -> i + "%");
            f.slider(lightsStrength, 100, 0, 200, 5, i -> i + "%");
            f.slider(darkDepth, 50, 0, 100, 5, i -> i == 0 ? off : i + "%");
            f.slider(lightsColor, 100, 0, 200, 5, i -> i == 0 ? off : i + "%");
            f.slider(lightsMoving, 100, 100, 500, 25, i -> i + "%");
            f.slider(lightsMax, 64, 16, Scene.maxLights, 16, i -> i + "");
            f.slider(lightsDetail, 100, 25, 100, 25, i -> i + "%");
            f.slider(lightsAir, 100, 0, 300, 25, i -> i == 0 ? off : i + "%");
            f.slider(lightsShots, 100, 0, 300, 25, i -> i == 0 ? off : i + "%");
            f.check(shotShadows, true);
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
            f.check(powerOn, true);
            f.slider(powerStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(factoriesOn, true);
            f.check(orePeekOn, true);

            f.section("units");
            f.check(boostersOn, true);
            f.check(boostersTint, true);
            f.slider(boostersBreath, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(boostersFlicker, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.button("mshaders-engines-edit", () -> {
                if(engineDialog == null) engineDialog = new EngineDialog();
                engineDialog.show();
            });
            f.check(normalsOwnLight, false);
            f.slider(cutawayStyle, 1, 0, 3, 1, i -> i == 0 ? off : Core.bundle.get(i == 1 ? "mshaders.cutaway.dither" : i == 2 ? "mshaders.cutaway.cut" : "mshaders.cutaway.fade"));
            f.slider(cutawaySize, 100, 50, 200, 10, i -> i + "%");
            f.check(statusesOn, true);
            f.slider(statusesStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");

            f.section("shields");
            f.check(shieldsOn, true);
            f.slider(shieldsBubble, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(shieldsRipple, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(shieldsLight, 100, 0, 300, 25, i -> i == 0 ? off : i + "%");
            f.slider(shieldsHex, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(shieldsRefract, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(shieldsTurns, true);
            f.slider(shieldsSheen, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(shieldsCrack, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");

            f.section("pulses");
            f.check(pulsesOn, true);
            f.slider(pulsesWave, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(pulsesHaze, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");

            f.section("weather");
            f.check(weatherOn, true);
            f.slider(weatherStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(tracksOn, true);
            f.slider(tracksStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(heatOn, true);
            f.slider(heatStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(beamsOn, true);
            f.check(hitHeatOn, true);
            f.slider(hitHeatStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.check(smokeShadowsOn, true);
            f.slider(smokeShadowsStrength, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");

            f.section("blasts");
            f.check(blastsOn, true);
            f.slider(blastsShock, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(blastsFlash, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(blastsDust, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(blastsScorch, 100, 0, 200, 10, i -> i == 0 ? off : i + "%");
            f.slider(blastsScorchTime, 5, 1, 60, 1, i -> Core.bundle.format("mshaders.seconds", i));

            f.section("post");
            f.check(postOn, true);
            f.slider(vignette, 35, 0, 100, 5, i -> i + "%");
            f.slider(saturation, 115, 0, 200, 5, i -> i + "%");
            f.slider(contrast, 108, 50, 150, 2, i -> i + "%");
            f.slider(warmth, 15, -100, 100, 5, i -> (i > 0 ? "+" : "") + i);
            f.slider(spare, 100, 0, 100, 5, i -> i == 0 ? off : i + "%");
        });

        // The two switches for those who draw pictures are no effects: they sit with the game's own developer options
        // (Settings > Developer Options), after the game's.
        try{
            Vars.ui.settings.dev.checkPref(devWatch, false);
            Vars.ui.settings.dev.checkPref(devButton, false);
            Vars.ui.settings.dev.checkPref(devGlossTable, false);
            Vars.ui.settings.dev.checkPref(devClock, false);
            Vars.ui.settings.dev.checkPref(devSquares, true);
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

        // For finding out what is slow on a machine nobody can attach a profiler to (a phone): the time each step of
        // the frame takes, in the middle of the screen, while the developer option is on (Clock.java).
        try{
            Events.run(mindustry.game.EventType.Trigger.preDraw, Clock::frame);
            Vars.ui.hudGroup.fill(t -> {
                t.touchable = arc.scene.event.Touchable.disabled;
                t.table(mindustry.ui.Styles.black6, box -> box.label(() -> Clock.text).style(mindustry.ui.Styles.outlineLabel).fontScale(0.8f).left()).margin(8f);
                t.visible(() -> Clock.on && !Clock.text.isEmpty());
            });
        }catch(Throwable t){
            Log.err("[mshaders] the time of each step cannot be shown", t);
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
    // (with a time of day, the sun moves round the set angle: Daytime)
    static float sunX(){
        return Daytime.sunX();
    }

    static float sunY(){
        return Daytime.sunY();
    }

    /** How much daylight the map has: 1 on a bright map; on a dark one what its darkness leaves, but not less than 0.15. The sun counts by this much. */
    static float daylight(){
        return (Vars.state.rules.lighting ? Math.max(1f - Vars.state.rules.ambientLight.a, 0.15f) : 1f) * Daytime.sunlight();
    }

    /**
     * A preset: 0 low, 1 medium, 2 high, 3 ultra (as it comes), 4 maximum. Each lower step turns off more of the
     * heavier effects; maximum turns the quality settings all the way up.
     */
    static void preset(int level){
        full();
        var s = Core.settings;
        if(level == 4){
            s.put(shadowQuality, 100);
            s.put(lightsMax, Scene.maxLights);
            s.put(lightsDetail, 100);
            s.put(dayOwn, true);
            return;
        }
        if(level >= 3) return;
        // high
        s.put(glintOn, false);
        s.put(shadowQuality, 60);
        s.put(smokeShadowsOn, false);
        s.put(tracksOn, false);
        s.put(wakesOn, false);
        if(level >= 2) return;
        // medium
        s.put(shadowQuality, 35);
        s.put(shieldsRefract, 0);
        s.put(normalsOn, false);
        s.put(reflectOn, false);
        s.put(lightsUnits, false);
        s.put(heatOn, false);
        s.put(liquidsOn, false);
        s.put(shadowAo, 0);
        if(level >= 1) return;
        // low
        s.put(shadowQuality, 0);
        s.put(lightsOn, false);
        s.put(weatherOn, false);
        s.put(beamsOn, false);
        s.put(hitHeatOn, false);
        s.put(shieldsHex, 0);
        s.put(lightsDetail, 50);
        s.put(lightsMax, 32);
    }

    /** Everything as it comes (every setting of the mod back to its default). */
    static void full(){
        for(String key : new String[]{glintOn, shadowQuality, smokeShadowsOn, tracksOn, wakesOn, shieldsRefract, normalsOn, reflectOn,
            lightsUnits, heatOn, liquidsOn, shadowAo, pulsesHaze, lightsOn, weatherOn, beamsOn, hitHeatOn, waterOn, shadowOn, floorsOn,
            shieldsHex, lightsDetail, lightsMax, dayOwn}){
            Core.settings.remove(key);
        }
    }

    static float percent(String key, int def){
        return Core.settings.getInt(key, def) / 100f;
    }
}
