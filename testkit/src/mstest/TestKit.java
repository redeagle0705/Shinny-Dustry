package mstest;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.graphics.CacheLayer.*;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;

/**
 * Test profile only. With -Dmstest.out=<folder> it loads a built-in map, points the camera at a shore, and for each entry of
 * -Dmstest.shots ("name|key=value,key=value;name2|...") applies the settings, waits, saves <folder>/<name>.png, then exits.
 * Values true/false and integers are written to the game settings. Special keys:
 *   ui=true        draw the UI                      dialog=last    open the last settings category
 *   fx=true        spawn glowing effects            zoom=N         camera zoom
 *   blocks=true    place buildings on open land     shore=true     place buildings on a shore with water to the south
 *   belt=true      add conveyors, sorters and two linked power nodes to those buildings
 *   drill=true     add a mechanical drill on copper ore (its rotator turns)
 *   drillbelt=true add a conveyor along the south side of that drill with items held half under the drill (as when they come out of it)
 *   row=true       add six smelters side by side, touching (nothing may leak between them)
 *   door=true      add a short wall with a door in it      dooropen=true/false  open or shut that door
 *   cluster=true   add a core with a container touching each side, plus a conveyor and a boulder near it
 *   walls=true     a row of the ten wall materials (1x1, touching) and a row of their large versions; the camera moves there
 *   drivers=true   three mass drivers above the test buildings; the camera moves there      aim=N  hold their heads at N, N+115 and N+230 degrees   recoil=N  and pushed back (1 = right after a shot)
 *   turrets=true   duo, hail, lancer, ripple, repair point, parallax, segment and build tower in a row above the test buildings; aim=N and recoil=N hold their heads too
 *   joins=true     add four conveyor joins (fed from both sides, one per direction) above the buildings and look at them
 *   joincam=N      look at join N (0..3) alone       time=N   hold the animation clock (Time.time) at N
 *   cam=X:Y        look at the tile X,Y counted from the lower-left corner of the test buildings (after blocks=true)
 *   field=true     fill the view with conveyors (rows running right and left, a column joining them every few tiles)
 *   fill=true      put items on every conveyor in and around the view
 *   lamp=true      two test lamps (Drawf.light)     unit=flare     spawn that unit next to the buildings
 *   pin=X:Y        hold the unit spawned last at the tile X,Y counted from the lower-left corner of the test buildings (it does not fly off)
 *   band=true      wait until the glint band is over the view centre
 *   beam=true      a still glowing beam over the water (shore scene)
 *   flash=true     a still muzzle flash next to the spawned unit
 *   many=N         N weak lights of equal strength over the view (a busy base)
 *   illuminator=true  a real Illuminator block with power (blocks scene)   tick=N  set the game clock
 *   flicker=true   report how much 8 frames in a row differ
 *   dark=true      switch on the map lighting rule (the game's own darkness and lights)
 *   fps=true       report the frame rate of the shot (vsync and the fps cap are switched off in the test profile)
 *   sprites=a+b    save atlas sprites enlarged and report where their light and dark pixels are
 * Facts about the shaders go to <folder>/report.txt.
 */
public class TestKit extends Mod{
    final String outDir = System.getProperty("mstest.out");
    final String mapName = System.getProperty("mstest.map", "archipelago");
    /** Floor to point the camera at (its internal name, e.g. "ice"); empty = water. */
    final String findFloor = System.getProperty("mstest.find", "");
    final String shotSpec = System.getProperty("mstest.shots", "shot|");
    final int wait = Integer.getInteger("mstest.wait", 90);

    final Seq<String[]> shots = new Seq<>();
    final StringBuilder report = new StringBuilder();
    int index = -1, phase, fpsFrames;
    float camX, camY;
    boolean camSet, fx, lamp, fpsShot, bandShot, beam, flickerShot, flash;
    mindustry.gen.Unit lastUnit;
    /** Where the unit spawned last is held every frame (world units); null = it is left to itself. */
    float[] pinAt;
    int many;
    float[] beamPos;
    byte[] lastFrame;
    int flickerFrames;
    float flickerMean, flickerMax;
    float[] lampPos, unitPos, hoverPos;
    float holdTime = -1f;
    boolean holdSide;
    /** Where the test mass drivers stand (tile of the first one; 0 = none), and the angle their heads are held at. */
    int driverX, driverY;
    float driverAim = Float.NaN;
    float driverRecoil = 0f;
    /** Where the test turrets stand (tile of the first one; 0 = none); they are four tiles apart. */
    int turretX, turretY, turretCount;
    int joinX, joinY;
    long fpsStart, shotStart, lastFx, runStart = Time.millis();
    String pendingDialog;
    int viewX, viewY, viewW, viewH;
    int baseX, baseY, baseW;
    boolean baseShore;

    @Override
    public void init(){
        if(outDir == null || Vars.headless) return;
        for(String s : shotSpec.split(";")){
            if(s.trim().isEmpty()) continue;
            String[] p = s.trim().split("[|]", 2);
            shots.add(new String[]{p[0], p.length > 1 ? p[1] : ""});
        }
        Core.settings.put("backgroundpause", false);
        // frame rate measurements need the frame limiters off (test profile only)
        Core.settings.put("vsync", false);
        Core.settings.put("fpscap", 245);
        Events.on(ClientLoadEvent.class, e -> Core.app.post(this::start));
    }

    void start(){
        try{
            line("game: " + Version.buildString() + ", GL " + Core.graphics.getGLVersion());
            Vars.mods.list().each(m -> line("mod: " + m.name + " state=" + m.state));
            shaderFacts("at client load");

            Map map = Vars.maps.all().find(m -> m.file.nameWithoutExtension().equalsIgnoreCase(mapName) || m.name().equalsIgnoreCase(mapName));
            if(map == null){
                fail("map not found: " + mapName + " (have " + Vars.maps.all().map(m -> m.file.nameWithoutExtension()) + ")");
                return;
            }
            Core.graphics.setVSync(false);
            Vars.control.playMap(map, map.applyRules(Gamemode.sandbox));
            Events.run(Trigger.preDraw, () -> {
                if(camSet) Core.camera.position.set(camX, camY);
                // conveyors pick their animation frame from this clock while they are drawn
                if(holdTime >= 0f) Time.time = holdTime;
                if(holdSide) holdItemsAtDrill();
                if(pinAt != null && lastUnit != null && lastUnit.isValid()){
                    lastUnit.set(pinAt[0], pinAt[1]);
                    lastUnit.vel.setZero();
                    lastUnit.rotation = 90f;
                }
                if(driverX != 0 && !Float.isNaN(driverAim)) holdDrivers();
                if(turretX != 0 && !Float.isNaN(driverAim)) holdTurrets();
            });
            Events.run(Trigger.draw, this::drawLamps);
            Core.app.addListener(new ApplicationListener(){
                @Override
                public void update(){
                    try{
                        tick();
                    }catch(Throwable t){
                        fail(Strings.getStackTrace(t));
                    }
                }
            });
        }catch(Throwable t){
            fail(Strings.getStackTrace(t));
        }
    }

    /** Runs after ClientLauncher.update(), i.e. after the world and the UI of this frame are drawn and before the buffer swap. */
    void tick(){
        if(phase == 2) return;
        if(Time.timeSinceMillis(runStart) > 400_000){
            fail("timeout in phase " + phase + " shot " + index);
            return;
        }
        if(phase == 0){
            if(!Vars.state.isGame() || Vars.renderer.isCutscene() || Vars.world.isGenerating()) return;
            if(Core.scene.getDialog() != null) Core.scene.getDialog().hide();
            pickCamera();
            phase = 1;
            nextShot();
            return;
        }
        if(Vars.state.is(State.paused) && Core.scene.getDialog() == null) Vars.state.set(State.playing);
        // Everything here goes by the clock, not by frames: with the frame limiters off the game runs at thousands of
        // frames per second. "wait" keeps its meaning of frames at 60 per second.
        float elapsed = Time.timeSinceMillis(shotStart) / 1000f, need = wait / 60f;
        // the dialog resets itself to its first page while opening, so pick the category a moment later
        if(pendingDialog != null && elapsed >= 0.25f){
            openSettings(pendingDialog);
            pendingDialog = null;
        }
        if(elapsed >= 0.5f){
            if(fpsFrames == 0) fpsStart = Time.nanos();
            fpsFrames++;
        }
        if(fx && Time.timeSinceMillis(lastFx) >= 200){
            lastFx = Time.millis();
            // glowing effects inside the bloom layer range, to compare bloom strengths
            for(int i = -1; i <= 1; i++){
                Fx.titanExplosion.at(camX + i * 90f, camY, Pal.accent);
                Fx.shockwave.at(camX + i * 90f, camY + 60f);
            }
        }
        // band=true: hold the shot until the drifting sun band of the glint is over the camera centre.
        // Same formula as in mshaders-post.frag (a band every 170 units along the diagonal, 0.22 periods per second).
        if(bandShot && elapsed >= need && elapsed < need + 15f){
            float at = ((camX + camY) * 0.7071f / 170f - Time.globalTime / 60f * 0.22f) % 1f;
            if(at < 0f) at += 1f;
            if(Math.abs(at - 0.25f) > 0.02f) return;
        }
        // flicker=true: before the shot, compare 8 frames in a row. Nothing in the test scene moves except the water
        // animation, so a large change from one frame to the next is flicker.
        if(flickerShot && elapsed >= need && flickerFrames < 8){
            int w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
            byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
            if(lastFrame != null && lastFrame.length == px.length){
                long sum = 0;
                int big = 0;
                for(int i = 0; i < px.length; i += 4){
                    int d = Math.abs((px[i] & 0xff) - (lastFrame[i] & 0xff)) + Math.abs((px[i + 1] & 0xff) - (lastFrame[i + 1] & 0xff)) + Math.abs((px[i + 2] & 0xff) - (lastFrame[i + 2] & 0xff));
                    sum += d;
                    if(d > 3 * 12) big++;
                }
                flickerMean = Math.max(flickerMean, sum / (float)(px.length / 4 * 3));
                flickerMax = Math.max(flickerMax, big / (float)(px.length / 4));
            }
            lastFrame = px;
            flickerFrames++;
            if(flickerFrames < 8) return;
            line("  frame-to-frame change over 8 frames in a row: largest mean difference " + Strings.fixed(flickerMean, 3)
                + " (of 255), largest share of pixels changing by more than 12: " + Strings.fixed(flickerMax * 100f, 2) + "%");
        }
        if(elapsed >= need){
            if(fpsShot && fpsFrames > 1){
                float seconds = Time.timeSinceNanos(fpsStart) / 1e9f;
                line("  frame rate: " + Strings.fixed((fpsFrames - 1) / seconds, 0) + " fps (" + (fpsFrames - 1) + " frames in " + Strings.fixed(seconds, 2) + " s) at "
                    + Core.graphics.getWidth() + "x" + Core.graphics.getHeight() + ", vsync and fps cap off");
            }
            capture(shots.get(index)[0]);
            nextShot();
        }
    }

    void nextShot(){
        index++;
        if(index >= shots.size){
            finish("done");
            return;
        }
        String[] shot = shots.get(index);
        Vars.disableUI = true;
        fx = false;
        lamp = false;
        fpsShot = false;
        fpsFrames = 0;
        shotStart = Time.millis();
        bandShot = false;
        beam = false;
        flash = false;
        many = 0;
        flickerShot = false;
        flickerFrames = 0;
        flickerMean = flickerMax = 0f;
        lastFrame = null;
        if(Core.scene.getDialog() != null) Core.scene.getDialog().hide();
        line("");
        line("shot " + shot[0] + ": " + shot[1]);
        for(String kv : shot[1].split(",")){
            if(!kv.contains("=")) continue;
            String k = kv.substring(0, kv.indexOf('=')).trim(), v = kv.substring(kv.indexOf('=') + 1).trim();
            switch(k){
                case "ui" -> Vars.disableUI = !Boolean.parseBoolean(v);
                case "fx" -> fx = Boolean.parseBoolean(v);
                case "lamp" -> lamp = Boolean.parseBoolean(v);
                case "fps" -> fpsShot = Boolean.parseBoolean(v);
                case "band" -> bandShot = Boolean.parseBoolean(v);
                case "beam" -> beam = Boolean.parseBoolean(v);
                case "flash" -> flash = Boolean.parseBoolean(v);
                case "many" -> many = Integer.parseInt(v);
                case "illuminator" -> placeIlluminator();
                case "lampcolor" -> {
                    // colour of that illuminator, RRGGBB (the same setting a player changes by tapping the lamp)
                    var lampBuild = lampPos == null ? null : Vars.world.build((int)(lampPos[0] / Vars.tilesize), (int)(lampPos[1] / Vars.tilesize));
                    if(lampBuild != null && lampBuild.block == Blocks.illuminator) lampBuild.configureAny(Color.valueOf(v).rgba());
                    else line("  no illuminator to colour");
                }
                case "tick" -> Vars.state.tick = Double.parseDouble(v);
                case "flicker" -> flickerShot = Boolean.parseBoolean(v);
                case "dark" -> {
                    // the map rule that turns on the game's own lighting (dark maps); test profile only
                    Vars.state.rules.lighting = Boolean.parseBoolean(v);
                    Vars.state.rules.ambientLight.set(0.02f, 0.02f, 0.06f, 0.78f);
                }
                case "blocks" -> placeBlocks();
                case "shore" -> placeShore();
                case "belt" -> placeBelt();
                case "drill" -> placeDrill();
                case "drillbelt" -> placeDrillBelt();
                case "row" -> placeRow();
                case "cluster" -> placeCluster();
                case "walls" -> placeWalls();
                case "drivers" -> placeDrivers();
                case "turrets" -> placeTurrets();
                case "aim" -> driverAim = Float.parseFloat(v);
                case "recoil" -> driverRecoil = Float.parseFloat(v);
                case "joins" -> placeJoins();
                case "cam" -> {
                    camX = (baseX + Float.parseFloat(v.split(":")[0]) + 0.5f) * Vars.tilesize;
                    camY = (baseY + Float.parseFloat(v.split(":")[1]) + 0.5f) * Vars.tilesize;
                }
                case "field" -> placeField();
                case "fill" -> fillBelts();
                case "joincam" -> {
                    camX = (joinX + 6 * Integer.parseInt(v) + 0.5f) * Vars.tilesize;
                    camY = (joinY + 0.5f) * Vars.tilesize;
                }
                case "time" -> holdTime = Float.parseFloat(v);
                case "door" -> placeDoor();
                case "dooropen" -> {
                    // the same call the game makes when a player taps the door
                    var doorBuild = baseW == 0 ? null : Vars.world.build(baseX + 14, baseY + 3);
                    if(doorBuild != null && doorBuild.block == Blocks.door) doorBuild.configureAny(Boolean.parseBoolean(v));
                    else line("  no door to open");
                }
                case "paint" -> paintFloors(v);
                case "unit" -> {
                    var type = Vars.content.unit(v);
                    if(unitPos != null && type != null) lastUnit = type.spawn(Vars.player.team(), unitPos[0], unitPos[1]);
                }
                case "hover" -> {
                    // a unit right over the smelter (things above a block must not take the block's shading)
                    var hoverType = Vars.content.unit(v);
                    if(hoverPos != null && hoverType != null) lastUnit = hoverType.spawn(Vars.player.team(), hoverPos[0], hoverPos[1]);
                }
                case "pin" -> pinAt = new float[]{(baseX + Float.parseFloat(v.split(":")[0]) + 0.5f) * Vars.tilesize, (baseY + Float.parseFloat(v.split(":")[1]) + 0.5f) * Vars.tilesize};
                case "sprites" -> dumpSprites(v);
                case "zoom" -> {
                    // closer than the game lets a player go (for looking at single pixels of a sprite)
                    if(Integer.parseInt(v) > Vars.renderer.maxZoomInGame) Vars.renderer.maxZoom = Vars.renderer.maxZoomInGame = Integer.parseInt(v);
                    Vars.renderer.setScale(Scl.scl(Integer.parseInt(v)));
                }
                case "dialog" -> {
                    Vars.ui.settings.show();
                    pendingDialog = v;
                }
                default -> {
                    if(v.equals("true") || v.equals("false")) Core.settings.put(k, Boolean.parseBoolean(v));
                    else Core.settings.put(k, Integer.parseInt(v));
                }
            }
        }
    }

    /** Opens the settings dialog on a category ("last" or its button text); visible(int) is private, hence reflection. */
    void openSettings(String name){
        try{
            SettingsMenuDialog dialog = Vars.ui.settings;
            int i = name.equals("last") ? dialog.getCategories().size - 1 : dialog.getCategories().indexOf(c -> c.name.equalsIgnoreCase(name));
            if(i < 0){
                line("  settings category not found: " + name);
                return;
            }
            Method m = SettingsMenuDialog.class.getDeclaredMethod("visible", int.class);
            m.setAccessible(true);
            m.invoke(dialog, 4 + i); // pages 0-3 are the built-in game, graphics, sound, dev tables (SettingsMenuDialog.visible)
            line("  settings category shown with " + dialog.getCategories().get(i).table.getSettings().map(s -> s.name));
        }catch(Throwable t){
            line("  could not open settings: " + t);
        }
    }

    /** Looks for the screen-sized window of tiles that is closest to 55% open water with as few walls as possible. */
    void pickCamera(){
        int ww = Vars.world.width(), wh = Vars.world.height();
        int vw = Math.min(ww, (int)(Core.graphics.getWidth() / Vars.renderer.getScale() / Vars.tilesize) + 1);
        int vh = Math.min(wh, (int)(Core.graphics.getHeight() / Vars.renderer.getScale() / Vars.tilesize) + 1);
        // sum = open water tiles, walls = tiles covered by a wall (drawn dark, whatever the floor under them is)
        int[][] sum = new int[ww + 1][wh + 1], walls = new int[ww + 1][wh + 1];
        for(int x = 0; x < ww; x++){
            for(int y = 0; y < wh; y++){
                var tile = Vars.world.tile(x, y);
                int wall = tile.block() != Blocks.air ? 1 : 0;
                boolean wanted = findFloor.isEmpty() ? tile.floor().cacheLayer == CacheLayer.water : tile.floor().name.equals(findFloor);
                int water = wall == 0 && wanted ? 1 : 0;
                sum[x + 1][y + 1] = water + sum[x][y + 1] + sum[x + 1][y] - sum[x][y];
                walls[x + 1][y + 1] = wall + walls[x][y + 1] + walls[x + 1][y] - walls[x][y];
            }
        }
        float best = Float.MAX_VALUE;
        int bx = 0, by = 0;
        for(int x = 0; x + vw <= ww; x++){
            for(int y = 0; y + vh <= wh; y++){
                float frac = (sum[x + vw][y + vh] - sum[x][y + vh] - sum[x + vw][y] + sum[x][y]) / (float)(vw * vh);
                float wallFrac = (walls[x + vw][y + vh] - walls[x][y + vh] - walls[x + vw][y] + walls[x][y]) / (float)(vw * vh);
                float score = Math.abs(frac - 0.55f) + wallFrac * 2f;
                if(score < best){
                    best = score;
                    bx = x;
                    by = y;
                }
            }
        }
        viewX = bx;
        viewY = by;
        viewW = vw;
        viewH = vh;
        camX = (bx + vw / 2f) * Vars.tilesize;
        camY = (by + vh / 2f) * Vars.tilesize;
        camSet = true;
        line("map " + mapName + " " + ww + "x" + wh + ", camera at tile " + (bx + vw / 2) + "," + (by + vh / 2) + ", view " + vw + "x" + vh + " tiles");
    }

    /** Puts a few buildings of different sizes on open land inside the view and centres the camera on them (sandbox, test profile). */
    void placeBlocks(){
        int w = 16, h = 7;
        for(int x = viewX; x + w <= viewX + viewW; x++){
            search:
            for(int y = viewY; y + h <= viewY + viewH; y++){
                for(int dx = 0; dx < w; dx++){
                    for(int dy = 0; dy < h; dy++){
                        var t = Vars.world.tile(x + dx, y + dy);
                        // boulders are scattered differently on every load: they are cleared below; map walls are not
                        if(t == null || t.block().isStatic() || t.build != null || t.floor().isLiquid) continue search;
                    }
                }
                for(int dx = 0; dx < w; dx++){
                    for(int dy = 0; dy < h; dy++){
                        var t = Vars.world.tile(x + dx, y + dy);
                        if(t.block() != Blocks.air) t.setAir();
                    }
                }
                var team = Vars.player.team();
                Vars.world.tile(x + 1, y + 3).setBlock(Blocks.copperWall, team);
                for(int i = 3; i <= 5; i++) Vars.world.tile(x + i, y + 5).setBlock(Blocks.copperWall, team);
                Vars.world.tile(x + 4, y + 2).setBlock(Blocks.titaniumWallLarge, team);
                Vars.world.tile(x + 9, y + 3).setBlock(Blocks.siliconSmelter, team);
                Vars.world.tile(x + 13, y + 2).setBlock(Blocks.duo, team);
                Vars.world.tile(x + 13, y + 5).setBlock(Blocks.thoriumWall, team);
                camX = (x + w / 2f) * Vars.tilesize;
                camY = (y + h / 2f) * Vars.tilesize;
                // one lamp in the gap between the 2x2 wall and the smelter, one right of the smelter
                lampPos = new float[]{(x + 7.1f) * Vars.tilesize, (y + 3.6f) * Vars.tilesize, (x + 12.2f) * Vars.tilesize, (y + 4f) * Vars.tilesize};
                unitPos = new float[]{(x + 7f) * Vars.tilesize, (y + 0.8f) * Vars.tilesize};
                // the middle of the 2x2 smelter
                hoverPos = new float[]{(x + 9.5f) * Vars.tilesize, (y + 3.5f) * Vars.tilesize};
                baseX = x;
                baseY = y;
                baseW = w;
                baseShore = false;
                line("  placed test blocks at tile " + x + "," + y + " (1x1 walls, 2x2 wall, 2x2 smelter, duo)");
                return;
            }
        }
        line("  no open land of " + w + "x" + h + " tiles in view, no blocks placed");
    }

    /** Puts buildings on the last row of land directly north of open water (for the reflection) and looks at that shore. */
    void placeShore(){
        int w = 11, land = 3, water = 4;
        for(int y = water; y + land < Vars.world.height(); y++){
            search:
            for(int x = 0; x + w <= Vars.world.width(); x++){
                for(int dx = 0; dx < w; dx++){
                    for(int dy = -water; dy < land; dy++){
                        var t = Vars.world.tile(x + dx, y + dy);
                        boolean wet = t.floor().isLiquid && t.floor().cacheLayer == CacheLayer.water;
                        // boulders are scattered differently on every load: they are cleared below; map walls are not
                        if(t.block().isStatic() || t.build != null || wet != (dy < 0)) continue search;
                    }
                }
                for(int dx = 0; dx < w; dx++){
                    for(int dy = -water; dy < land; dy++){
                        var t = Vars.world.tile(x + dx, y + dy);
                        if(t.block() != Blocks.air) t.setAir();
                    }
                }
                var team = Vars.player.team();
                Vars.world.tile(x + 1, y).setBlock(Blocks.copperWall, team);
                Vars.world.tile(x + 3, y).setBlock(Blocks.titaniumWallLarge, team);
                Vars.world.tile(x + 6, y).setBlock(Blocks.siliconSmelter, team);
                Vars.world.tile(x + 9, y).setBlock(Blocks.duo, team);
                camX = (x + w / 2f) * Vars.tilesize;
                camY = (y - 0.5f) * Vars.tilesize;
                lampPos = new float[]{(x + 5.6f) * Vars.tilesize, (y + 0.4f) * Vars.tilesize, (x + 8.6f) * Vars.tilesize, (y - 1.2f) * Vars.tilesize};
                unitPos = new float[]{(x + 2.5f) * Vars.tilesize, (y - 1.6f) * Vars.tilesize};
                beamPos = new float[]{(x - 2f) * Vars.tilesize, (y - 3.2f) * Vars.tilesize, (x + 13f) * Vars.tilesize, (y - 1.4f) * Vars.tilesize};
                baseX = x;
                baseY = y;
                baseW = w;
                baseShore = true;
                line("  placed shore blocks at tile " + x + "," + y + " (water to the south)");
                return;
            }
        }
        line("  no straight shore with water to the south found, no blocks placed");
    }

    /**
     * Flat things next to the test buildings (after blocks=true or shore=true): conveyor rows, sorters, and two power nodes
     * joined by a laser. None of them stands up from the ground, so none of them should show as a mirror image.
     */
    void placeBelt(){
        if(baseW == 0) return;
        var team = Vars.player.team();
        int x = baseX, y = baseY;
        if(baseShore){
            // row y is the last row of land; water is south of it
            for(int dx = 0; dx < baseW; dx++){
                put(x + dx, y + 2, dx % 4 == 3 ? Blocks.sorter : Blocks.conveyor, team);
                put(x + dx, y, Blocks.conveyor, team);
            }
            put(x + 2, y - 2, Blocks.powerNode, team);
            put(x + 8, y - 3, Blocks.powerNode, team);
            link(x + 2, y - 2, x + 8, y - 3);
        }else{
            for(int dx = 0; dx < baseW; dx++){
                put(x + dx, y + 6, Blocks.conveyor, team);
                put(x + dx, y + 4, dx % 4 == 3 ? Blocks.sorter : Blocks.conveyor, team);
            }
            // five tiles apart: inside the node's laser range, so the laser is really drawn
            put(x + 10, y, Blocks.powerNode, team);
            put(x + 15, y, Blocks.powerNode, team);
            link(x + 10, y, x + 15, y);
        }
        line("  placed conveyors, sorters and two linked power nodes");
    }

    /**
     * paint=a+b+c: repaints the view (and a margin around it) in vertical strips of those floors, left to right, and
     * clears walls, boulders and ores from it - any floor can be looked at on any map, several side by side.
     */
    void paintFloors(String names){
        String[] list = names.split("[+]");
        int margin = 12, x0 = viewX - margin, w = viewW + margin * 2;
        for(int x = x0; x < x0 + w; x++){
            int strip = Mathf.clamp((x - viewX) * list.length / Math.max(viewW, 1), 0, list.length - 1);
            var floor = Vars.content.block(list[strip]);
            if(floor == null || !floor.isFloor()){
                line("  not a floor: " + list[strip]);
                return;
            }
            for(int y = viewY - margin; y < viewY + viewH + margin; y++){
                var t = Vars.world.tile(x, y);
                if(t == null) continue;
                if(t.build == null && t.block() != Blocks.air) t.setAir();
                t.setFloor(floor.asFloor());
                t.setOverlay(Blocks.air);
            }
        }
        Vars.renderer.blocks.floor.reload();
        line("  painted the view in strips: " + names);
    }

    /**
     * Six silicon smelters side by side, touching, a few tiles north of the test buildings (after blocks=true): a wall
     * of blocks. No light and no shadow may get through between two blocks that touch.
     */
    void placeRow(){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        for(int i = 0; i < 6; i++){
            int tx = baseX + 2 + i * 2, ty = baseY + 9;
            for(int dx = 0; dx < 2; dx++){
                for(int dy = 0; dy < 2; dy++){
                    var t = Vars.world.tile(tx + dx, ty + dy);
                    if(t != null && t.build == null && t.block() != Blocks.air) t.setAir();
                }
            }
            var origin = Vars.world.tile(tx, ty);
            if(origin != null && origin.build == null) origin.setBlock(Blocks.siliconSmelter, team, 0);
        }
        line("  placed a row of six smelters");
    }

    /**
     * A short wall with a door in it, right of the second test lamp (after blocks=true): shut, it must stop light and
     * cast a wall's shadow; open, light must pass and the shadow shrink to almost nothing.
     */
    void placeDoor(){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        put(baseX + 14, baseY + 2, Blocks.copperWall, team);
        put(baseX + 14, baseY + 3, Blocks.door, team);
        put(baseX + 14, baseY + 4, Blocks.copperWall, team);
        put(baseX + 14, baseY + 5, Blocks.copperWall, team);
        line("  placed a wall with a door");
    }

    /**
     * A core with a container touching it on each of its four sides, a few tiles north of the test buildings (after
     * blocks=true), and a conveyor and a boulder in the shadow of it: what touches the core must not shut its light in.
     */
    void placeCluster(){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        int cx = baseX + 8, cy = baseY + 11;
        for(int dx = -5; dx <= 5; dx++){
            for(int dy = -5; dy <= 5; dy++){
                var t = Vars.world.tile(cx + dx, cy + dy);
                if(t != null && t.build == null && t.block() != Blocks.air) t.setAir();
            }
        }
        Vars.world.tile(cx, cy).setBlock(Blocks.coreShard, team, 0);
        Vars.world.tile(cx - 3, cy).setBlock(Blocks.container, team, 0);
        Vars.world.tile(cx + 2, cy).setBlock(Blocks.container, team, 0);
        Vars.world.tile(cx, cy + 2).setBlock(Blocks.container, team, 0);
        Vars.world.tile(cx, cy - 3).setBlock(Blocks.container, team, 0);
        for(int i = 0; i < 4; i++) put(cx - 5 + i, cy - 4, Blocks.conveyor, team);
        Vars.world.tile(cx - 4, cy - 2).setBlock(Blocks.boulder);
        line("  placed a core with four containers touching it");
    }

    /** A mechanical drill on copper ore in the lower left of the test buildings (after blocks=true): it mines, so its rotator turns. */
    void placeDrill(){
        if(baseW == 0 || baseShore) return;
        for(int dx = 0; dx < 2; dx++){
            for(int dy = 0; dy < 2; dy++){
                var t = Vars.world.tile(baseX + 1 + dx, baseY + dy);
                if(t != null) t.setOverlay(Blocks.oreCopper);
            }
        }
        put(baseX + 1, baseY, Blocks.mechanicalDrill, Vars.player.team());
        Vars.renderer.blocks.floor.reload();
        line("  placed a mechanical drill on copper ore");
    }

    /**
     * Four conveyors that are each fed from both sides and from nothing behind (the game's conveyor shape 4), pointing
     * right, up, left and down, in a row above the test buildings (after blocks=true). The camera moves to the row.
     */
    void placeJoins(){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        joinX = baseX - 1;
        joinY = baseY + 11;
        for(int dx = -3; dx <= 21; dx++){
            for(int dy = -3; dy <= 3; dy++){
                var t = Vars.world.tile(joinX + dx, joinY + dy);
                if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            }
        }
        int missing = 0;
        for(int i = 0; i < 4; i++){
            int cx = joinX + 6 * i, cy = joinY, left = (i + 1) % 4, right = (i + 3) % 4;
            int[][] parts = {
                {0, 0, i}, {arc.math.geom.Geometry.d4x(i), arc.math.geom.Geometry.d4y(i), i}, {arc.math.geom.Geometry.d4x(i) * 2, arc.math.geom.Geometry.d4y(i) * 2, i},
                {arc.math.geom.Geometry.d4x(left), arc.math.geom.Geometry.d4y(left), right}, {arc.math.geom.Geometry.d4x(left) * 2, arc.math.geom.Geometry.d4y(left) * 2, right},
                {arc.math.geom.Geometry.d4x(right), arc.math.geom.Geometry.d4y(right), left}, {arc.math.geom.Geometry.d4x(right) * 2, arc.math.geom.Geometry.d4y(right) * 2, left}
            };
            for(int[] p : parts){
                var t = Vars.world.tile(cx + p[0], cy + p[1]);
                if(t != null && t.build == null && !t.block().isStatic()) t.setBlock(Blocks.conveyor, team, p[2]);
                else missing++;
            }
        }
        camX = (joinX + 9.5f) * Vars.tilesize;
        camY = (joinY + 0.5f) * Vars.tilesize;
        lampPos = new float[]{(joinX + 7.5f) * Vars.tilesize, (joinY + 2f) * Vars.tilesize, (joinX + 14.5f) * Vars.tilesize, (joinY - 1.5f) * Vars.tilesize};
        StringBuilder shapes = new StringBuilder();
        for(int i = 0; i < 4; i++){
            if(Vars.world.build(joinX + 6 * i, joinY) instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild b){
                b.onProximityUpdate();
                shapes.append(" ").append(b.blendbits).append(b.blendsclx < 0 ? "x" : "").append(b.blendscly < 0 ? "y" : "");
            }
        }
        line("  placed four conveyor joins at tile " + joinX + "," + joinY + " (shapes:" + shapes + ", tiles not free: " + missing + ")");
    }

    /** Conveyors over the whole view: every row a belt, alternately running right and left, every sixth column running up through them. */
    void placeField(){
        var team = Vars.player.team();
        int placed = 0;
        for(int x = viewX - 2; x < viewX + viewW + 2; x++){
            for(int y = viewY - 2; y < viewY + viewH + 2; y++){
                var t = Vars.world.tile(x, y);
                if(t == null || t.build != null || t.block().isStatic() || t.floor().isLiquid) continue;
                if(t.block() != Blocks.air) t.setAir();
                var kind = y % 3 == 0 ? Blocks.titaniumConveyor : y % 3 == 1 ? Blocks.conveyor : Blocks.armoredConveyor;
                t.setBlock(x % 6 == 0 ? Blocks.conveyor : kind, team, x % 6 == 0 ? 1 : y % 2 == 0 ? 0 : 2);
                placed++;
            }
        }
        line("  placed a field of " + placed + " conveyors");
    }

    void fillBelts(){
        int filled = 0;
        for(int x = viewX - 30; x < viewX + viewW + 30; x++){
            for(int y = viewY - 30; y < viewY + viewH + 30; y++){
                if(Vars.world.build(x, y) instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild b && b.tile.x == x && b.tile.y == y && b.len == 0){
                    b.handleStack(mindustry.content.Items.copper, 2, null);
                    filled++;
                }
            }
        }
        line("  put items on " + filled + " conveyors");
    }

    /** A conveyor running east just south of the drill (after drill=true), loaded; the camera moves there. */
    void placeDrillBelt(){
        if(baseW == 0 || baseShore) return;
        for(int x = baseX - 1; x <= baseX + 5; x++){
            var t = Vars.world.tile(x, baseY - 1);
            if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            if(t != null && t.build == null && !t.block().isStatic()) t.setBlock(Blocks.conveyor, Vars.player.team(), 0);
        }
        holdSide = true;
        camX = (baseX + 2f) * Vars.tilesize;
        camY = (baseY + 0.25f) * Vars.tilesize;
        line("  placed a conveyor south of the drill");
    }

    /** Every frame: one item on each of those conveyors, at the side of the belt next to the drill - where an item lies just after the drill has put it there. */
    void holdItemsAtDrill(){
        for(int x = baseX; x <= baseX + 3; x++){
            if(Vars.world.build(x, baseY - 1) instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild b){
                if(b.len == 0) b.handleStack(mindustry.content.Items.copper, 1, null);
                if(b.len > 0){
                    b.len = 1;
                    b.xs[0] = 1f;
                    b.ys[0] = 0.5f;
                }
            }
        }
    }

    /** The ten wall materials side by side above the test buildings (after blocks=true): 1x1 in the lower row, 2x2 in the upper. */
    void placeWalls(){
        if(baseW == 0 || baseShore) return;
        String[] kinds = {"copper", "titanium", "plastanium", "thorium", "phase", "surge", "beryllium", "tungsten", "reinforced-surge", "carbide"};
        int x0 = baseX - 2, y0 = baseY + 10, missing = 0;
        for(int dx = -2; dx <= 22; dx++){
            for(int dy = -2; dy <= 6; dy++){
                var t = Vars.world.tile(x0 + dx, y0 + dy);
                if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            }
        }
        for(int i = 0; i < kinds.length; i++){
            var small = Vars.world.tile(x0 + 5 + i, y0);
            var large = Vars.world.tile(x0 + i * 2, y0 + 2);
            if(small != null && small.build == null && !small.block().isStatic()) small.setBlock(Vars.content.block(kinds[i] + "-wall"), Vars.player.team(), 0); else missing++;
            if(large != null && large.build == null && !large.block().isStatic()) large.setBlock(Vars.content.block(kinds[i] + "-wall-large"), Vars.player.team(), 0); else missing++;
        }
        camX = (x0 + 10f) * Vars.tilesize;
        camY = (y0 + 2f) * Vars.tilesize;
        lampPos = new float[]{(x0 + 3f) * Vars.tilesize, (y0 + 0.5f) * Vars.tilesize, (x0 + 17f) * Vars.tilesize, (y0 + 0.5f) * Vars.tilesize};
        line("  placed the ten walls and their large versions at tile " + x0 + "," + y0 + " (not placed: " + missing + ")");
    }

    /** Three mass drivers in a row above the test buildings (after blocks=true), four tiles apart; the camera moves there. */
    void placeDrivers(){
        if(baseW == 0 || baseShore) return;
        driverX = baseX + 2;
        driverY = baseY + 12;
        for(int dx = -4; dx <= 14; dx++){
            for(int dy = -4; dy <= 4; dy++){
                var t = Vars.world.tile(driverX + dx, driverY + dy);
                if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            }
        }
        int placed = 0;
        for(int i = 0; i < 3; i++){
            put(driverX + i * 5, driverY, Blocks.massDriver, Vars.player.team());
            if(Vars.world.build(driverX + i * 5, driverY) instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild) placed++;
        }
        camX = (driverX + 5.5f) * Vars.tilesize;
        camY = (driverY + 0.5f) * Vars.tilesize;
        lampPos = new float[]{(driverX + 2.5f) * Vars.tilesize, (driverY + 3f) * Vars.tilesize, (driverX + 8f) * Vars.tilesize, (driverY - 2.5f) * Vars.tilesize};
        StringBuilder all = new StringBuilder();
        for(var b : mindustry.gen.Groups.build) if(b instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild) all.append(" ").append(b.tileX()).append(",").append(b.tileY()).append("(").append(b.team).append(")");
        line("  mass drivers on the map:" + all);
        line("  placed " + placed + " of 3 mass drivers at tile " + driverX + "," + driverY);
    }

    /** Every frame: the heads of those mass drivers held at driverAim, driverAim + 115 and driverAim + 230 degrees. */
    void holdDrivers(){
        for(int i = 0; i < 3; i++){
            if(Vars.world.build(driverX + i * 5, driverY) instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild b){
                b.rotation = driverAim + i * 115f;
                b.reloadCounter = driverRecoil;   // 1 = right after a shot: the head is pushed back by the block's knockback
            }
        }
    }

    /** One of each kind of block with a turning head, in a row above the test buildings (after blocks=true); the camera moves there. */
    void placeTurrets(){
        if(baseW == 0 || baseShore) return;
        mindustry.world.Block[] kinds = {Blocks.duo, Blocks.hail, Blocks.lancer, Blocks.ripple, Blocks.repairPoint, Blocks.parallax, Blocks.segment, Blocks.buildTower};
        turretX = baseX - 6;
        turretY = baseY + 12;
        turretCount = kinds.length;
        for(int dx = -3; dx <= kinds.length * 4 + 2; dx++){
            for(int dy = -4; dy <= 4; dy++){
                var t = Vars.world.tile(turretX + dx, turretY + dy);
                if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            }
        }
        StringBuilder placed = new StringBuilder();
        for(int i = 0; i < kinds.length; i++){
            put(turretX + i * 4, turretY, kinds[i], Vars.player.team());
            var b = Vars.world.build(turretX + i * 4, turretY);
            placed.append(" ").append(kinds[i].name).append(b != null && b.block == kinds[i] ? "" : "(NOT placed)");
        }
        camX = (turretX + (kinds.length - 1) * 2f + 0.5f) * Vars.tilesize;
        camY = (turretY + 0.5f) * Vars.tilesize;
        lampPos = new float[]{(turretX + 6f) * Vars.tilesize, (turretY + 3f) * Vars.tilesize, (turretX + 20f) * Vars.tilesize, (turretY - 2.5f) * Vars.tilesize};
        line("  placed at tile " + turretX + "," + turretY + ":" + placed);
    }

    /** Every frame: the heads of those blocks held at driverAim + 50 degrees per block, the turrets pushed back as right after a shot (recoil=1). */
    void holdTurrets(){
        for(int i = 0; i < turretCount; i++){
            var b = Vars.world.build(turretX + i * 4, turretY);
            float aim = driverAim + i * 50f;
            if(b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild t) t.rotation = aim;
            if(b instanceof mindustry.world.blocks.units.RepairTurret.RepairPointBuild t) t.rotation = aim;
            if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t){
                t.curRecoil = driverRecoil;
                // a turret with barrels that recoil one by one (the duo): only the first is pushed back, so the two can be told apart
                if(t.curRecoils != null) for(int r = 0; r < t.curRecoils.length; r++) t.curRecoils[r] = r == 0 ? driverRecoil : 0f;
                // Turret.updateTile works this out too, but only while the game is running the building
                mindustry.world.blocks.defense.turrets.Turret block = (mindustry.world.blocks.defense.turrets.Turret)t.block;
                t.recoilOffset.trns(aim, -arc.math.Mathf.pow(driverRecoil, block.recoilPow) * block.recoil);
            }
        }
    }

    void put(int x, int y, mindustry.world.Block block, Team team){
        var t = Vars.world.tile(x, y);
        if(t != null && t.build == null && !t.block().isStatic()) t.setBlock(block, team, 0);
    }

    void link(int x1, int y1, int x2, int y2){
        var a = Vars.world.build(x1, y1);
        var b = Vars.world.tile(x2, y2);
        if(a != null && b != null && b.build != null) a.configureAny(b.pos());
    }

    /** A real lamp: the Illuminator block where the first test lamp would be, wired to a sandbox power source. */
    void placeIlluminator(){
        if(lampPos == null) return;
        int tx = (int)(lampPos[0] / Vars.tilesize), ty = (int)(lampPos[1] / Vars.tilesize);
        var team = Vars.player.team();
        Vars.world.tile(tx, ty).setBlock(Blocks.illuminator, team);
        Vars.world.tile(tx, ty + 2).setBlock(Blocks.powerSource, team);
        var source = Vars.world.build(tx, ty + 2);
        if(source != null) source.configureAny(Vars.world.tile(tx, ty).pos());
        // a row of conveyors right under the lamp: low blocks, the light must pass over them
        for(int dx = -2; dx <= 2; dx++){
            var t = Vars.world.tile(tx + dx, ty - 1);
            if(t != null && t.block() == Blocks.air) t.setBlock(Blocks.conveyor, team, 0);
        }
        line("  placed an illuminator at tile " + tx + "," + ty + " with a power source above it");
    }

    /** Test lamps: plain Drawf.light calls, the same call blocks and bullets use, with a dot to show where the lamp is. */
    void drawLamps(){
        // many=N: N weak lights of equal strength scattered over the view, reported before the buildings report theirs
        // (a busy base: power lasers, small lamps). The strong lights must still win a place among the lights that are drawn.
        for(int i = 0; i < many; i++){
            float fx = Mathf.randomSeed(i * 2 + 1, -1f, 1f) * Core.camera.width * 0.45f, fy = Mathf.randomSeed(i * 2 + 2, -1f, 1f) * Core.camera.height * 0.45f;
            Drawf.light(camX + fx, camY + fy, 14f, Color.valueOf("ffd37f"), 0.5f);
        }
        if(beam && beamPos != null){
            // a still, glowing, see-through beam over the water (additive, in the bloom layers), like a laser shot frozen in place
            Draw.z(Layer.effect);
            Draw.blend(Blending.additive);
            Draw.color(Color.valueOf("ff9c40"));
            Lines.stroke(14f);
            Lines.line(beamPos[0], beamPos[1], beamPos[2], beamPos[3]);
            Draw.color(Color.white);
            Lines.stroke(6f);
            Lines.line(beamPos[0], beamPos[1], beamPos[2], beamPos[3]);
            Draw.blend();
            Draw.reset();
        }
        if(flash && lastUnit != null && lastUnit.isValid()){
            // a still muzzle flash next to the unit: additive, in the bloom layers, like a unit firing over water
            float fx0 = lastUnit.x + 9f, fy0 = lastUnit.y;
            Draw.z(Layer.effect);
            Draw.blend(Blending.additive);
            Draw.color(Color.valueOf("ffb040"));
            Fill.circle(fx0, fy0, 6f);
            Lines.stroke(5f);
            Lines.line(fx0, fy0, fx0 + 16f, fy0 + 3f);
            Draw.color(Color.white);
            Fill.circle(fx0, fy0, 3.5f);
            Draw.blend();
            Draw.reset();
        }
        if(!lamp || lampPos == null) return;
        Color[] colors = {Color.valueOf("ffb060"), Color.valueOf("70c8ff")};
        for(int i = 0; i < lampPos.length / 2; i++){
            float x = lampPos[i * 2], y = lampPos[i * 2 + 1];
            Drawf.light(x, y, 80f, colors[i % 2], 0.6f);
            Draw.z(Layer.effect);
            Draw.color(colors[i % 2]);
            Fill.circle(x, y, 1.6f);
            Draw.reset();
        }
    }

    void capture(String name){
        int w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        long r = 0, g = 0, b = 0;
        for(int i = 0; i < px.length; i += 4){
            r += px[i] & 0xff;
            g += px[i + 1] & 0xff;
            b += px[i + 2] & 0xff;
            px[i + 3] = (byte)255;
        }
        Pixmap pixmap = new Pixmap(w, h);
        Buffers.copy(px, 0, pixmap.pixels, px.length);
        Fi file = new Fi(outDir).child(name + ".png");
        PixmapIO.writePng(file, pixmap);
        pixmap.dispose();
        long n = (long)w * h;
        shaderFacts("  at capture");
        line("  saved " + file.name() + " " + w + "x" + h + ", mean RGB " + r / n + "," + g / n + "," + b / n + ", bloom " + (Vars.renderer.bloom != null));
    }

    /** What the swapped shaders really are right now: the measured side of experiments 1, 2 and 4. */
    void shaderFacts(String when){
        String marker = "EXP1_MARKER";
        Fi treeFile = Vars.tree.get("shaders/water.frag");
        var layer = CacheLayer.water instanceof ShaderLayer s ? s : null;
        line(when + ": Shaders.water=" + Shaders.water.getClass().getName()
            + ", compiled source has " + marker + "=" + Shaders.water.getFragmentShaderSource().contains(marker)
            + ", tree shaders/water.frag has " + marker + "=" + treeFile.readString().contains(marker) + " (" + treeFile.getClass().getSimpleName() + ")"
            + ", CacheLayer.water.shader same object=" + (layer != null && layer.shader == Shaders.water)
            + ", Shaders.darkness=" + Shaders.darkness.getClass().getName()
            + ", Renderer.lights=" + Vars.renderer.lights.getClass().getName()
            + ", map lighting rule=" + Vars.state.rules.lighting
            + ", scene: " + System.getProperty("mshaders.status", "-"));
    }

    /**
     * Experiment 7 check: saves each named atlas sprite 8x enlarged and reports where its light and dark pixels sit.
     * For every opaque pixel in the outer ring (up to 5 px from the sprite edge, the outermost pixel excluded) the brightness
     * is averaged per compass direction seen from the sprite centre. Light from the upper right means NE is the brightest
     * and SW the darkest.
     */
    void dumpSprites(String names){
        String[] dirs = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
        for(String name : names.split("[+]")){
            try{
                var region = Core.atlas.find(name);
                if(!region.found()){
                    line("  sprite " + name + ": not in atlas");
                    continue;
                }
                Pixmap p = Core.atlas.getPixmap(region).crop();
                int w = p.width, h = p.height;
                double[] sum = new double[8];
                int[] count = new int[8];
                ObjectIntMap<Integer> palette = new ObjectIntMap<>();
                for(int x = 0; x < w; x++){
                    for(int y = 0; y < h; y++){
                        int c = p.get(x, y);
                        if((c & 0xff) < 250) continue;
                        palette.increment(c, 0, 1);
                        int edge = Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
                        if(edge < 1 || edge > 5) continue;
                        float lum = (0.299f * ((c >>> 24) & 0xff) + 0.587f * ((c >>> 16) & 0xff) + 0.114f * ((c >>> 8) & 0xff)) / 255f;
                        // pixmap rows grow downwards, so flip y to get compass directions
                        float angle = Mathf.angle(x + 0.5f - w / 2f, -(y + 0.5f - h / 2f));
                        int d = (int)((angle + 22.5f) / 45f) % 8;
                        sum[d] += lum;
                        count[d]++;
                    }
                }
                StringBuilder sb = new StringBuilder("  sprite " + name + " " + w + "x" + h + " ring brightness:");
                for(int d = 0; d < 8; d++) sb.append(' ').append(dirs[d]).append('=').append(count[d] == 0 ? "-" : Strings.fixed((float)(sum[d] / count[d]), 2));
                java.util.List<Integer> top = new java.util.ArrayList<>();
                for(var e : palette.entries()) top.add(e.key);
                top.sort((a, b) -> palette.get(b, 0) - palette.get(a, 0));
                sb.append("; ").append(top.size()).append(" colours, most used:");
                for(int i = 0; i < Math.min(6, top.size()); i++){
                    int c = top.get(i);
                    sb.append(' ').append(String.format("#%06x", c >>> 8)).append('x').append(palette.get(c, 0));
                }
                // transparency from the centre to the right edge, in tenths of the radius (for the soft light sprites)
                sb.append("; alpha centre to edge:");
                for(int i = 0; i <= 10; i++) sb.append(" ").append(Strings.fixed((p.get(Math.min(w / 2 + (w / 2 - 1) * i / 10, w - 1), h / 2) & 0xff) / 255f, 2));
                line(sb.toString());

                // the sprite itself, pixel for pixel (input for tools\HeightGen.java)
                PixmapIO.writePng(new Fi(outDir).child("sprite-" + name + "-1x.png"), p);

                int k = 8;
                Pixmap big = new Pixmap(w * k, h * k);
                for(int x = 0; x < w * k; x++){
                    for(int y = 0; y < h * k; y++){
                        big.set(x, y, p.get(x / k, y / k));
                    }
                }
                PixmapIO.writePng(new Fi(outDir).child("sprite-" + name + ".png"), big);
                big.dispose();
                p.dispose();
            }catch(Throwable t){
                line("  sprite " + name + ": " + t);
            }
        }
    }

    void line(String s){
        report.append(s).append('\n');
        Log.info("[mstest] @", s);
    }

    void fail(String why){
        line("FAILED: " + why);
        finish("failed");
    }

    void finish(String result){
        if(phase == 2) return;
        phase = 2;
        line("result: " + result);
        new Fi(outDir).child("report.txt").writeString(report.toString());
        Core.app.exit();
    }
}
