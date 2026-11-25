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
import mindustry.world.*;

import java.lang.reflect.*;

/**
 * Test profile only. With -Dmstest.out=<folder> it loads a built-in map, points the camera at a shore, and for each entry of
 * -Dmstest.shots ("name|key=value,key=value;name2|...") applies the settings, waits, saves <folder>/<name>.png, then exits.
 * Values true/false and integers are written to the game settings. Special keys:
 *   ui=true        draw the UI                      dialog=last    open the last settings category
 *   fx=true        spawn glowing effects            zoom=N         camera zoom
 *   blocks=true    place buildings on open land     shore=true     place buildings on a shore with water to the south
 *   belt=true      add conveyors, sorters and two linked power nodes to those buildings
 *   blockrow=a+b   those blocks in a row above the test buildings, two tiles apart; the camera moves there
 *   sortrow=true   a row of conveyor, junction, sorter, inverted sorter, overflow gate, underflow gate, router, distributor above them; the camera moves there
 *   drill=true     add a mechanical drill on copper ore (its rotator turns)
 *   drillbelt=true add a conveyor along the south side of that drill with items held half under the drill (as when they come out of it)
 *   row=true       add six smelters side by side, touching (nothing may leak between them)
 *   door=true      add a short wall with a door in it      dooropen=true/false  open or shut that door
 *   cluster=true   add a core with a container touching each side, plus a conveyor and a boulder near it
 *   walls=true     a row of the ten wall materials (1x1, touching) and a row of their large versions; the camera moves there
 *   drivers=true   three mass drivers above the test buildings; the camera moves there      aim=N  hold their heads at N, N+115 and N+230 degrees   recoil=N  and pushed back (1 = right after a shot)
 *   turrets=true   duo, hail, lancer, ripple, repair point, parallax, segment and build tower in a row above the test buildings; aim=N and recoil=N hold their heads too
 *   turrets=a+b    those blocks instead, six tiles apart, all aiming at aim=N      warm=N  held that far warmed up (0..1; parts that open or slide)
 *   joins=true     add four conveyor joins (fed from both sides, one per direction) above the buildings and look at them
 *   joincam=N      look at join N (0..3) alone       time=N   hold the animation clock (Time.time) at N
 *   cam=X:Y        look at the tile X,Y counted from the lower-left corner of the test buildings (after blocks=true)
 *   field=true     fill the view with conveyors (rows running right and left, a column joining them every few tiles)
 *   fill=true      put items on every conveyor in and around the view
 *   lamp=true      two test lamps (Drawf.light)     unit=flare     spawn that unit next to the buildings
 *   pin=X:Y        hold the unit spawned last at the tile X,Y counted from the lower-left corner of the test buildings (it does not fly off)
 *   band=true      wait until the glint band is over the view centre
 *   beam=true      a still glowing beam over the water (shore scene)
 *   laser=true     the continuous laser of the vela, held in place across the middle of the view
 *   crowd=a+b      one unit of each of those kinds held in a row just below the first test lamp; the camera moves there
 *   enemy=name     a unit of the other team held to the right of that row, so the row shoots at it (nothing held dies); enemyright=N: that far right of the lamp (before enemy=; 0 = under the middle of the row)
 *   heldshot=F     a shot of the first unit of that row held at the first test lamp, with the share F of its time used up (0 = just fired .. 1)
 *   face=N         hold the units of that row turned to N degrees (0 = right, 90 = up)      facestep=M  each one M degrees further than the one before
 *   ring=DX:DY:AGE a shot stopped that far from the projector, AGE ticks before the held time (time=)
 *   shields=true   a force projector and a quasar with its force field (no power needed)      pelt=N  shots at them every N ticks
 *   swerve=N:K     the units of that row turn by N degrees at 8 a tick, and K ticks after the turn began the clock stops
 *   spin=N         turn the units of that row by N degrees a tick (from face=)      fly=F:S  give them a speed: F of their top speed
 *                  forwards and S of it to their left (they stay where they are held; only what goes by the speed shows it)
 *   unitcolors=true  report for every unit of the game the colour its sprite is marked with besides grey and the orange of
 *                  the team cell (the green of the support units, the purple of others), as "name = rrggbb" (run without the shader mod)
 *   landing=F      play the landing of the player's core and hold it at the share F of its way down (0 = high up .. 1 = down);
 *                  the camera goes where the game puts it      landing=off  ends it
 *   savefile=FROM>TO  copy that file there now (as if a picture had just been saved while the game is open)
 *   click=TEXT     press the button on screen that is named TEXT, or whose text holds TEXT (spaces as _)
 *   onwalls=true   move the row of units (crowd=) onto the nearest natural walls that border open ground, and the camera there
 *   kit=a+b        save those atlas sprites pixel for pixel as <sprite>.png, and the shape of each filled with the colour
 *                  of a flat face as <sprite>-flat.png - what there is to draw over (run without the shader mod)
 *   open=CLASS     make a dialog of that class (of any mod) and show it, e.g. open=mshaders.EngineDialog
 *   stride=N       hold the two-legged units of that row at the point N of their walk (0 .. 4 strides), so their legs show
 *   unitkit=a+b    for those units: report the sprites each is put together from (body, legs, treads, weapons, with sizes and
 *                  places) and save each sprite pixel for pixel as <sprite>.png, plus a flat normal map of its shape to
 *                  start drawing from as <sprite>-flat.png (run without the shader mod)
 *   flash=true     a still muzzle flash next to the spawned unit
 *   boom=true      the explosion effect of a thorium reactor next to the first test lamp (after blocks=true); nothing is destroyed
 *   many=N         N weak lights of equal strength over the view (a busy base)
 *   illuminator=true  a real Illuminator block with power (blocks scene)   tick=N  set the game clock
 *   flicker=true   report how much 8 frames in a row differ
 *   dark=true      switch on the map lighting rule (the game's own darkness and lights)
 *   fps=true       report the frame rate of the shot (vsync and the fps cap are switched off in the test profile)
 *   gl=true        report what a frame of the shot asks of the graphics card: draw calls, texture bindings, shader switches
 *                  (counting slows the game down: not together with fps=true)
 *   fieldof=a+b    fill the view with those blocks, taking turns (a busy base seen from far away)
 *   wallshapes=a+b for each of those walls, above the test buildings: one alone, a row of four, a column of three, an L, a
 *                  square of four and a T, each kind in a band of its own; the camera moves to the first band
 *   sprites=a+b    save atlas sprites enlarged and report where their light and dark pixels are
 *   turretlist=true  report every turret-like block with the sprites it is put together from (base, body, parts; effects apart)
 *   weaponlist=true  report every unit with its weapons: the colours of each shot and the colour of its light (run without the shader mod)
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
    /** gl=true: the counter of the engine, and what it counted over the frames of this shot. */
    arc.profiling.GLProfiler profiler;
    boolean glShot;
    long glFrames, glDraws, glBinds, glSwitches, glCalls;
    float camX, camY;
    boolean camSet, fx, lamp, fpsShot, bandShot, beam, flickerShot, flash;
    mindustry.gen.Unit lastUnit;
    boolean laser;
    mindustry.gen.Bullet laserShot;
    /** Where the unit spawned last is held every frame (world units); null = it is left to itself. */
    float[] pinAt;
    /** crowd=a+b: units held in a row just below the first test lamp, and where each is held. */
    final Seq<mindustry.gen.Unit> crowd = new Seq<>();
    final FloatSeq crowdAt = new FloatSeq();
    /** face=N: the direction the units of the row are held turned to (NaN = left alone); facestep=M: each one that much further. */
    float face = Float.NaN, faceStep;
    /** landing=F: the share of its way down the landing of the core is held at (NaN = no landing). */
    float landing = Float.NaN;
    /** spin=N: degrees a tick the units of the row are turned by; fly=F:S: the speed they are given, forwards and to the left, as shares of their top speed. */
    float spin = Float.NaN, flyForward = Float.NaN, flySide;
    /**
     * swerve=N:K: the units of the row fly straight (face=), then turn by N degrees at 8 degrees a tick; K ticks after
     * the turn began the game's clock is stopped and they are held as they are - a short sharp turn, looked at at one
     * moment. swerveFrom: when the turn begins (Time.time).
     */
    float swerve = Float.NaN, swerveStop, swerveFrom;
    /**
     * shields=true: a force projector (its team set to need no power) and a quasar with its force field beside it, the
     * camera between them. pelt=N: every N ticks a shot of another team flies at each of them from the right (0 = none).
     */
    float shieldX = Float.NaN, shieldY, pelt, lastPelt;
    boolean shieldHold = true;
    /** pulses=true: where the mend projector stands (for wave=). */
    float pulseX, pulseY;
    /** place=: hold the mouse cursor on the middle of the window. */
    boolean holdCursor;
    /** churn=true: a wall is put down and taken away again every frame, beside the camera (the block picture is uploaded every frame). */
    boolean churn;
    /** construct=true: the player's unit is given walls to build one after another beside the camera (real construction, with its beam). */
    boolean construct;
    /** hot=true: every crafter is held fully warmed up and every turret at full barrel heat (for the heat haze). */
    boolean hot;
    /** smoke=true: smoke puffs and clouds rise beside the middle of the view, a few every frame. */
    boolean smoke;
    /** beams=true: a meltdown's beam sweeping slowly over the ground left of the middle, and a lancer firing every second right of it. */
    boolean beams;
    mindustry.gen.Bullet sweep;
    boolean beamWalls;
    boolean hitWalls, hitPlaced;
    final arc.struct.Seq<mindustry.gen.Building> stoppedBelts = new arc.struct.Seq<>();
    final arc.struct.Seq<mindustry.gen.Unit> walkers = new arc.struct.Seq<>();
    float walkStart;
    float hitX, hitY;
    int constructAt;
    /** statuses=a+b+c (after crowd=): the units of the row get those status effects, one each in turn, held. */
    String[] statusRow;
    /** barrage=N: keep about N shots in the air over the view, fired across it from its edges. */
    int barrage;
    /** layers=true: time each band of the game's draw layers until the shot is taken (LayerTimer). */
    final LayerTimer layerTimer = new LayerTimer();
    boolean layersShot;
    /** shieldhurt=F: how much of both shields is held used up, 0 .. 1. */
    float shieldHurt;
    mindustry.gen.Unit shieldUnit;
    boolean swerveArmed;
    /** stride=N: where in their walk the two-legged units of the row are held (NaN = left alone). */
    float stride = Float.NaN;
    /** How far below the lamp the row is, world units (crowdbelow=N, before crowd=). */
    float crowdBelow = 34f;
    /** How far to the right of the lamp the unit of the other team is held, world units (enemyright=N, before enemy=). */
    float enemyRight = 64f;
    /** heldshot=F: a shot of the first unit of the row, held at the first test lamp with that share of its time used up (0..1); below 0 = none. */
    float heldShot = -1f, heldDx, heldDy;
    mindustry.gen.Bullet shotHeld;
    int many;
    float[] beamPos;
    byte[] lastFrame;
    int flickerFrames, flickerCount = 8;
    float flickerMean, flickerMax;
    final StringBuilder flickerLumas = new StringBuilder(), flickerSpikes = new StringBuilder();
    final arc.struct.FloatSeq flickerLumaSeq = new arc.struct.FloatSeq();
    final arc.struct.LongSeq flickerFrameIds = new arc.struct.LongSeq();
    float[] lampPos, unitPos, hoverPos;
    float holdTime = -1f;
    boolean holdSide;
    /** Where the test mass drivers stand (tile of the first one; 0 = none), and the angle their heads are held at. */
    int driverX, driverY;
    float driverAim = Float.NaN;
    float driverRecoil = 0f;
    /** Where the test turrets stand (tile of the first one; 0 = none); they are four tiles apart. */
    int turretX, turretY, turretCount, turretStep = 4;
    /** How far each of them is turned on from the one before, and how far warmed up they are held (NaN = left alone). */
    float turretTurn = 50f, turretWarm = Float.NaN;
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
            // "erekir/onset", "serpulo/groundZero": a map of the campaign, read from the game's own files (maps/<name>.msav)
            if(map == null && mapName.contains("/")){
                Fi file = Vars.tree.get("maps/" + mapName + "." + Vars.mapExtension);
                if(file.exists()) map = mindustry.io.MapIO.createMap(file, false);
            }
            // a map file given by its path (a copy of one the maker sent, in the test profile)
            if(map == null && mapName.endsWith("." + Vars.mapExtension)){
                Fi file = new Fi(mapName);
                if(file.exists()) map = mindustry.io.MapIO.createMap(file, true);
            }
            if(map == null){
                fail("map not found: " + mapName + " (have " + Vars.maps.all().map(m -> m.file.nameWithoutExtension()) + ")");
                return;
            }
            Core.graphics.setVSync(false);
            Rules rules = map.applyRules(Gamemode.sandbox);
            if(mapName.startsWith("erekir/")){
                // what the planet lays on every map of it (Planet.applyRules): its surroundings - Env.scorching, which is
                // what makes the game draw the red haze (EnvRenderers) - and fog of war. The fog is left out here, it
                // would hide all but the surroundings of the core; the key fog=true turns it on for a shot.
                Planets.erekir.applyRules(rules, true);
                rules.fog = rules.staticFog = false;
            }
            Vars.control.playMap(map, rules);
            // (also at the update, before the frame: the mod reads the camera at the start of the frame - Wetness - and
            // saw the place the game had eased it to, a frame's worth of edge left dry)
            Events.run(Trigger.update, () -> {
                if(camSet) Core.camera.position.set(camX, camY);
            });
            Events.run(Trigger.preDraw, () -> {
                if(camSet) Core.camera.position.set(camX, camY);
                if(construct && Vars.player.unit() != null && !Vars.player.unit().activelyBuilding()){
                    int tx = (int)(Core.camera.position.x / 8f) + 3 + (constructAt % 6), ty = (int)(Core.camera.position.y / 8f) + 3 + (constructAt / 6) % 3;
                    constructAt++;
                    Vars.player.unit().addBuild(new mindustry.entities.units.BuildPlan(tx, ty, 0, Blocks.copperWall));
                }
                for(var sb : stoppedBelts) sb.enabled = false;
                for(int i = 0; i < walkers.size; i++){
                    var u = walkers.get(i);
                    if(!u.isValid()) continue;
                    float ang = (Time.time - walkStart) * 0.6f + i * 120f, r = 40f + i * 18f;
                    float wx = camX - 60f + i * 60f + Angles.trnsx(ang, r), wy = camY + Angles.trnsy(ang, r);
                    u.rotation(ang + 90f);
                    u.vel.set(wx - u.x, wy - u.y);
                    u.set(wx, wy);
                }
                if(hitWalls && !Vars.state.isPaused()){
                    // three enemy walls above the middle: the left one under heavy fire, the middle one light, the right one none
                    if(!hitPlaced){
                        hitPlaced = true;
                        Vars.state.rules.teams.get(mindustry.game.Team.crux).blockHealthMultiplier = 100000f;
                        int cx = (int)(Core.camera.position.x / Vars.tilesize), cy = (int)(Core.camera.position.y / Vars.tilesize);
                        for(int w = -1; w <= 1; w++){
                            var t = Vars.world.tile(cx + w * 4, cy + 3);
                            if(t != null){
                                if(t.build != null || t.block() != Blocks.air) t.setAir();
                                t.setBlock(Blocks.titaniumWallLarge, mindustry.game.Team.crux, 0);
                            }
                        }
                        hitX = cx * Vars.tilesize;
                        hitY = (cy + 3) * Vars.tilesize;
                    }
                    var shot = mindustry.content.UnitTypes.dagger.weapons.first().bullet;
                    long f = Core.graphics.getFrameId();
                    if(f % 3 == 0) shot.create(null, Vars.player.team(), hitX - 32f + Mathf.range(4f), hitY - 60f, 90f);
                    if(f % 30 == 0) shot.create(null, Vars.player.team(), hitX + Mathf.range(4f), hitY - 60f, 90f);
                }
                if(beams && !beamWalls){
                    // a row of walls across the meltdown's sweep and the lancer's line, where the camera is now
                    beamWalls = true;
                    // (enemy walls, or the beams pass through them; nearly unbreakable, or the meltdown cuts them away at once)
                    Vars.state.rules.teams.get(mindustry.game.Team.crux).blockHealthMultiplier = 100000f;
                    int cx = (int)(Core.camera.position.x / Vars.tilesize), cy = (int)(Core.camera.position.y / Vars.tilesize);
                    for(int dx = -16; dx <= 16; dx++){
                        var t = Vars.world.tile(cx + dx, cy + 3);
                        if(t != null && t.build == null && !t.floor().isDeep()){
                            if(t.block() != Blocks.air) t.setAir();
                            t.setBlock(Blocks.titaniumWall, mindustry.game.Team.crux, 0);
                        }
                    }
                }
                if(beams && !Vars.state.isPaused()){
                    var team = Vars.player.team();
                    var melt = ((mindustry.world.blocks.defense.turrets.LaserTurret)Blocks.meltdown).shootType;
                    float ang = -90f + Mathf.sin((float)(mindustry.Vars.state.tick / 60.0), 3f, 35f);
                    if(sweep == null || !sweep.isAdded() || sweep.type != melt) sweep = melt.create(null, team, Core.camera.position.x - 60f, Core.camera.position.y + 70f, ang);
                    sweep.time = Math.min(sweep.time, 10f);
                    sweep.rotation(ang);
                    if(mindustry.Vars.state.tick % 60 < 1){
                        var lancer = ((mindustry.world.blocks.defense.turrets.PowerTurret)Blocks.lancer).shootType;
                        lancer.create(null, team, Core.camera.position.x + 40f, Core.camera.position.y + 70f, -100f);
                    }
                }
                if(smoke && Core.graphics.getFrameId() % 6 == 0){
                    float sx = camX - 40f, sy = camY + 10f;
                    Fx.smokeCloud.at(sx + Mathf.range(14f), sy + Mathf.range(14f));
                    Fx.smoke.at(sx + 60f + Mathf.range(8f), sy + Mathf.range(8f));
                    Fx.shootBigSmoke.at(sx + 110f, sy - 20f, 90f);
                    Fx.smeltsmoke.at(sx + 20f + Mathf.range(6f), sy - 50f + Mathf.range(6f));
                }
                if(hot){
                    for(mindustry.gen.Building b : mindustry.gen.Groups.build){
                        if(b instanceof mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild c) c.warmup = 1f;
                        if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild tb) tb.heat = 1f;
                    }
                }
                if(churn){
                    Tile t = Vars.world.tileWorld(Core.camera.position.x + 24f, Core.camera.position.y + 24f);
                    if(t != null){
                        if(t.build != null) t.remove();
                        else t.setNet(Blocks.copperWall, Vars.player.team(), 0);
                    }
                }
                if(!Float.isNaN(landing)){
                    var core = Vars.player.team().core();
                    // the game counts the landing down from its full time; held at the same moment every frame
                    if(core != null) arc.util.Reflect.set(mindustry.core.Renderer.class, Vars.renderer, "landTime", core.launchDuration() * Math.max(1f - landing, 0.004f));
                }
                // conveyors pick their animation frame from this clock while they are drawn
                if(holdTime >= 0f) Time.time = holdTime;
                if(holdSide) holdItemsAtDrill();
                for(int c = 0; c < crowd.size; c++){
                    if(crowd.get(c).isValid()){
                        crowd.get(c).set(crowdAt.get(c * 2), crowdAt.get(c * 2 + 1));
                        crowd.get(c).health = crowd.get(c).maxHealth;
                        if(!Float.isNaN(face)){
                            crowd.get(c).rotation = face + c * faceStep;
                            if(crowd.get(c) instanceof mindustry.gen.Mechc mech) mech.baseRotation(face + c * faceStep);
                        }
                        if(!Float.isNaN(spin)){
                            crowd.get(c).rotation = (Float.isNaN(face) ? 90f : face) + c * faceStep + Time.time * spin;
                        }
                        if(statusRow != null){
                            var effect = Vars.content.statusEffect(statusRow[c % statusRow.length]);
                            if(effect != null) crowd.get(c).apply(effect, 120f);
                        }
                        if(!Float.isNaN(swerve)){
                            // (Counted from here, not from where the key is read: the clock held for the shot before
                            // is held only while the game draws, and once let go it is as far on as if it had never
                            // been held - the turn would be over within a frame.)
                            if(swerveArmed){
                                swerveArmed = false;
                                swerveFrom = Time.time + 30f;
                                line("  swerve: now " + Time.time + ", the turn begins at " + swerveFrom);
                            }
                            float since = Mathf.clamp(Time.time - swerveFrom, 0f, swerveStop);
                            crowd.get(c).rotation = (Float.isNaN(face) ? 90f : face) + c * faceStep + Math.signum(swerve) * Math.min(since * 8f, Math.abs(swerve));
                            if(holdTime < 0f && Time.time - swerveFrom >= swerveStop){
                                holdTime = Time.time;
                                line("  swerve: clock stopped at " + Time.time + ", unit turned to " + crowd.get(c).rotation);
                            }
                        }
                        if(!Float.isNaN(flyForward)){
                            var held = crowd.get(c);
                            held.vel.trns(held.rotation, flyForward * held.type.speed, flySide * held.type.speed);
                        }
                        if(!Float.isNaN(stride) && crowd.get(c) instanceof mindustry.gen.Mechc mech) mech.walkTime(stride * crowd.get(c).type.mechStride);
                    }
                }
                if(!Float.isNaN(shieldX)) holdShields();

                if(barrage > 0){
                    var shotType = UnitTypes.dagger.weapons.first().bullet;
                    int missing = Math.min(barrage - mindustry.gen.Groups.bullet.size(), 200);
                    for(int i = 0; i < missing; i++){
                        float a = Mathf.random(360f), r = Math.max(Core.camera.width, Core.camera.height) * 0.5f;
                        float bx = Core.camera.position.x + Angles.trnsx(a, r), by = Core.camera.position.y + Angles.trnsy(a, r);
                        shotType.create(null, i % 2 == 0 ? Vars.player.team() : mindustry.game.Team.crux, bx, by, a + 180f + Mathf.range(40f), -1f, 1f, r * 2f / (shotType.speed * shotType.lifetime), null);
                    }
                }
                if(pinAt != null && lastUnit != null && lastUnit.isValid()){
                    lastUnit.set(pinAt[0], pinAt[1]);
                    lastUnit.vel.setZero();
                    lastUnit.rotation = 90f;
                }
                if(driverX != 0 && !Float.isNaN(driverAim)) holdDrivers();
                if(turretX != 0 && !Float.isNaN(driverAim)) holdTurrets();
            });
            Events.run(Trigger.draw, this::drawLamps);
            layerTimer.install();
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
        if(profiler != null && profiler.isEnabled()){
            if(glShot && elapsed >= 0.5f){
                glFrames++;
                glDraws += profiler.getDrawCalls();
                glBinds += profiler.getTextureBindings();
                glSwitches += profiler.getShaderSwitches();
                glCalls += profiler.getCalls();
            }
            profiler.reset();
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
        if(pendingSprites != null){
            String names = pendingSprites;
            pendingSprites = null;
            spriteFb(names);
        }
        if(flickerShot && elapsed >= need && flickerFrames < flickerCount){
            int w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
            byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
            // dumpframes=N: the first N frames of the comparison saved as pictures (to look at a frame that blinks)
            int dump = Core.settings.getInt("mshaders-test-dumpframes", 0);
            if(flickerFrames < dump){
                Pixmap shot = new Pixmap(w, h);
                java.nio.ByteBuffer buf = shot.getPixels();
                buf.clear();
                buf.put(px);
                buf.position(0);
                PixmapIO.writePng(new Fi(outDir).child("frame-" + flickerFrames + ".png"), shot);
                shot.dispose();
            }
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
                // frames where many pixels jump: their number and how many
                if(big * 1000L / (px.length / 4) >= 5) flickerSpikes.append(" #").append(flickerFrames).append(":").append(big * 1000L / (px.length / 4)).append("/1000");
            }
            {
                // each frame's mean brightness: a whole-screen flash shows as one frame far off the others
                long all = 0;
                for(int i = 0; i < px.length; i += 4) all += (px[i] & 0xff) + (px[i + 1] & 0xff) + (px[i + 2] & 0xff);
                float luma = all / (float)(px.length / 4 * 3);
                flickerLumas.append(flickerFrames == 0 ? "" : " ").append(Strings.fixed(luma, 1));
                flickerLumaSeq.add(luma);
                flickerFrameIds.add(Core.graphics.getFrameId());
            }
            lastFrame = px;
            flickerFrames++;
            if(flickerFrames < flickerCount) return;
            line("  frame-to-frame change over 8 frames in a row: largest mean difference " + Strings.fixed(flickerMean, 3)
                + " (of 255), largest share of pixels changing by more than 12: " + Strings.fixed(flickerMax * 100f, 2) + "%");
            if(flickerCount <= 16) line("  mean brightness of those frames: " + flickerLumas);
            line("  frames with many pixels jumping:" + (flickerSpikes.length() == 0 ? " none" : flickerSpikes.toString()));
            // One frame (or two) brighter or darker than the frames around it, then back: a blink of the whole picture
            // or of a large part of it (falling snow changes many pixels a little, the mean hardly).
            StringBuilder blips = new StringBuilder();
            for(int i = 2; i < flickerLumaSeq.size - 2; i++){
                float around = (flickerLumaSeq.get(i - 2) + flickerLumaSeq.get(i + 2)) / 2f;
                float off = flickerLumaSeq.get(i) - around;
                if(Math.abs(off) >= 0.3f && Math.abs(flickerLumaSeq.get(i - 2) - flickerLumaSeq.get(i + 2)) < 0.3f) blips.append(" #").append(i).append("(frame ").append(flickerFrameIds.get(i)).append("):").append(Strings.fixed(off, 2));
            }
            line("  frames off the brightness of the frames around them:" + (blips.length() == 0 ? " none" : blips.toString()));
            flickerLumaSeq.clear();
            flickerFrameIds.clear();
            flickerSpikes.setLength(0);
            flickerLumas.setLength(0);
        }
        if(elapsed >= need){
            if(fpsShot && fpsFrames > 1){
                float seconds = Time.timeSinceNanos(fpsStart) / 1e9f;
                line("  frame rate: " + Strings.fixed((fpsFrames - 1) / seconds, 0) + " fps (" + (fpsFrames - 1) + " frames in " + Strings.fixed(seconds, 2) + " s) at "
                    + Core.graphics.getWidth() + "x" + Core.graphics.getHeight() + ", vsync and fps cap off");
            }
            if(glShot && glFrames > 0){
                line("  a frame: " + glDraws / glFrames + " draw calls, " + glBinds / glFrames + " texture bindings, " + glSwitches / glFrames + " shader switches, "
                    + glCalls / glFrames + " calls in all (mean of " + glFrames + " frames)");
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
        glShot = false;
        glFrames = glDraws = glBinds = glSwitches = glCalls = 0;
        if(profiler != null && profiler.isEnabled()) profiler.disable();
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
                case "gl" -> {
                    glShot = Boolean.parseBoolean(v);
                    if(glShot){
                        if(profiler == null) profiler = new arc.profiling.GLProfiler(Core.graphics);
                        profiler.enable();
                    }
                }
                case "band" -> bandShot = Boolean.parseBoolean(v);
                case "beam" -> beam = Boolean.parseBoolean(v);
                case "laser" -> laser = Boolean.parseBoolean(v);
                case "flash" -> flash = Boolean.parseBoolean(v);
                case "boom" -> {
                    // boom=true: the explosion of a thorium reactor (the effect alone, nothing is destroyed), four tiles right of the first test lamp
                    if(lampPos != null && Boolean.parseBoolean(v)) Fx.reactorExplosion.at(lampPos[0] + 32f, lampPos[1]);
                }
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
                case "flickern" -> flickerCount = Integer.parseInt(v);
                case "dark" -> {
                    // the map rule that turns on the game's own lighting (dark maps); test profile only
                    // dark=true / false, or dark=0.95: how dark (the share of light the darkness takes away; true = 0.78)
                    boolean number = !v.equals("true") && !v.equals("false");
                    Vars.state.rules.lighting = number || Boolean.parseBoolean(v);
                    Vars.state.rules.ambientLight.set(0.02f, 0.02f, 0.06f, number ? Float.parseFloat(v) : 0.78f);
                }
                case "fog" -> Vars.state.rules.fog = Vars.state.rules.staticFog = Boolean.parseBoolean(v);
                // haze=false: the map without the red haze of a scorching planet (the game draws it while this bit is set)
                case "haze" -> Vars.state.rules.env = Boolean.parseBoolean(v) ? Vars.state.rules.env | mindustry.world.meta.Env.scorching : Vars.state.rules.env & ~mindustry.world.meta.Env.scorching;
                case "atcore" -> {
                    var core = Vars.player.team().core();
                    if(core != null){
                        camX = core.x + Float.parseFloat(v.split(":")[0]) * Vars.tilesize;
                        camY = core.y + Float.parseFloat(v.split(":")[1]) * Vars.tilesize;
                        camSet = true;
                    }
                }
                // view=x:y: the view (and with it where field, blocks ... are put) at that tile of the map, the same
                // in every run - the place the camera picks for itself differs from run to run
                case "view" -> {
                    viewX = Integer.parseInt(v.split(":")[0]);
                    viewY = Integer.parseInt(v.split(":")[1]);
                    camX = (viewX + viewW / 2f) * Vars.tilesize;
                    camY = (viewY + viewH / 2f) * Vars.tilesize;
                    camSet = true;
                }
                case "blocks" -> placeBlocks();
                case "churn" -> churn = Boolean.parseBoolean(v);
                case "construct" -> construct = Boolean.parseBoolean(v);
                case "hot" -> hot = Boolean.parseBoolean(v);
                case "smoke" -> smoke = Boolean.parseBoolean(v);
                case "hitwalls" -> hitWalls = Boolean.parseBoolean(v);
                case "lake" -> {
                    // lake=true: deep water all over the view (for units on water)
                    int cx = (int)(camX / Vars.tilesize), cy = (int)(camY / Vars.tilesize);
                    for(int dx = -30; dx <= 30; dx++) for(int dy = -18; dy <= 18; dy++){
                        var t = Vars.world.tile(cx + dx, cy + dy);
                        if(t == null) continue;
                        if(t.block() != Blocks.air) t.setAir();
                        t.setFloor(Blocks.deepwater.asFloor());
                    }
                    Vars.renderer.blocks.floor.reload();
                }
                case "walkers" -> {
                    // walkers=TYPE+TYPE..: units of those types that walk round in circles beside the middle of the view
                    walkers.clear();
                    String[] types = v.split("[+]");
                    for(int i = 0; i < types.length; i++){
                        var type = Vars.content.unit(types[i]);
                        if(type != null) walkers.add(type.spawn(Vars.player.team(), camX, camY));
                    }
                    walkStart = Time.time;
                }
                case "belts" -> {
                    // belts=true: a running conveyor line (titanium) and below it one held stopped (switched off), left of the middle
                    int cx = (int)(camX / Vars.tilesize) - 10, cy = (int)(camY / Vars.tilesize);
                    stoppedBelts.clear();
                    for(int dx = 0; dx < 8; dx++) for(int row = 0; row < 2; row++){
                        var t = Vars.world.tile(cx + dx, cy + row * 3);
                        if(t == null) continue;
                        if(t.block() != Blocks.air) t.setAir();
                        t.setBlock(Blocks.titaniumConveyor, Vars.player.team(), 0);
                        if(row == 1 && t.build != null) stoppedBelts.add(t.build);
                    }
                }
                case "smelters" -> {
                    // smelters=true: four silicon smelters side by side, a little right of the middle
                    int cx = (int)(camX / Vars.tilesize) + 6, cy = (int)(camY / Vars.tilesize);
                    for(int dx = -2; dx <= 4; dx++) for(int dy = -2; dy <= 4; dy++){
                        var t = Vars.world.tile(cx + dx, cy + dy);
                        if(t != null && t.block() != Blocks.air) t.setAir();
                    }
                    for(int i = 0; i < 2; i++) for(int j = 0; j < 2; j++) Vars.world.tile(cx + i * 2, cy + j * 2).setBlock(Blocks.siliconSmelter, Vars.player.team(), 0);
                }
                case "beams" -> {
                    beams = Boolean.parseBoolean(v);
                    beamWalls = false;
                }
                case "pools" -> {
                    // pools=true: three round pools beside the middle of the view - slag, tar, cryofluid
                    mindustry.world.Block[] kinds = {Blocks.slag, Blocks.tar, Blocks.cryofluid};
                    int mx = (int)(camX / Vars.tilesize), my = (int)(camY / Vars.tilesize);
                    for(int pk = 0; pk < 3; pk++){
                        int cx = mx - 14 + pk * 14, cy = my;
                        for(int dx = -6; dx <= 6; dx++){
                            for(int dy = -6; dy <= 6; dy++){
                                if(dx * dx + dy * dy > 30) continue;
                                var t = Vars.world.tile(cx + dx, cy + dy);
                                if(t == null) continue;
                                if(t.build != null || t.block() != Blocks.air) t.setAir();
                                t.setFloor(kinds[pk].asFloor());
                            }
                        }
                    }
                    Vars.renderer.blocks.floor.reload();
                    line("  pools of slag, tar and cryofluid");
                }
                case "ownhere" -> {
                    // ownhere=DX:DY: the player's unit is put that far (world units) from the middle of the view
                    String[] p = v.split(":");
                    if(Vars.player.unit() != null) Vars.player.unit().set(camX + Float.parseFloat(p[0]), camY + Float.parseFloat(p[1]));
                }
                case "weather" -> {
                    // weather=NAME: the game's own weather of that name starts (rain, snowing, sandstorm, ...), at full strength
                    for(mindustry.type.Weather w : Vars.content.<mindustry.type.Weather>getBy(mindustry.ctype.ContentType.weather)){
                        if(w.name.equals(v)){
                            mindustry.gen.WeatherState st = w.create(1f, 60f * 600f);
                            st.opacity(1f);
                            line("  weather " + v + " started");
                        }
                    }
                }
                case "shore" -> placeShore();
                case "belt" -> placeBelt();
                case "sortrow" -> placeSortRow();
                case "blockrow" -> placeBlockRow(v);
                case "dumpweather" -> {
                    // dumpweather=NAME: every field of that weather, to the report
                    for(mindustry.type.Weather w : Vars.content.<mindustry.type.Weather>getBy(mindustry.ctype.ContentType.weather)){
                        if(!w.name.equals(v)) continue;
                        StringBuilder sb = new StringBuilder("  " + v + " (" + w.getClass().getName() + "):");
                        for(Class<?> c = w.getClass(); c != null && c != Object.class; c = c.getSuperclass()){
                            for(java.lang.reflect.Field f : c.getDeclaredFields()){
                                if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                try{
                                    f.setAccessible(true);
                                    Object val = f.get(w);
                                    if(val instanceof Number || val instanceof Boolean || val instanceof String || val instanceof Color) sb.append(' ').append(f.getName()).append('=').append(val);
                                }catch(Throwable ignored){}
                            }
                        }
                        line(sb.toString());
                        line("  minScale " + Vars.renderer.minScale() + ", maxScale " + Vars.renderer.maxScale() + ", scale now " + Vars.renderer.getDisplayScale() + ", camera " + Core.camera.width + "x" + Core.camera.height);
                    }
                }
                case "put" -> {
                    // put=name@dx@dy[@rotation]: one block (or boulder) that many tiles from where blockrow starts (row 9 above the test buildings)
                    String[] a = v.split("@");
                    var block = Vars.content.block(a[0]);
                    var t = block == null ? null : Vars.world.tile(baseX + Integer.parseInt(a[1]), baseY + 9 + Integer.parseInt(a[2]));
                    if(t != null){
                        t.setBlock(block, Vars.player.team(), a.length > 3 ? Integer.parseInt(a[3]) : 0);
                        line("  put " + a[0] + " at " + t.x + "," + t.y);
                    }else line("  could not put " + v);
                }
                case "fillbelt" -> {
                    // fillbelt=ITEM: every conveyor gets a load of that item (put a wall at the end and they clog)
                    var item = Vars.content.item(v);
                    int n = 0;
                    if(item != null) for(var bd : Vars.state.teams.get(Vars.player.team()).buildings){
                        if(bd instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild cb){
                            for(int q = 0; q < 4; q++) if(cb.acceptStack(item, 1, null) > 0) cb.handleStack(item, 1, null);
                            n++;
                        }
                    }
                    line("  filled " + n + " conveyor(s) with " + v);
                }
                case "drill" -> placeDrill();
                case "peekdrill" -> {
                    // peekdrill=true (after drill=true): three ores under the drill and none on one tile, and the cursor on it
                    var d = Vars.world.build(baseX + 1, baseY);
                    if(d != null){
                        mindustry.world.Block[] ores = {Blocks.oreCopper, Blocks.oreLead, Blocks.oreCopper, Blocks.air};
                        int oreAt = 0;
                        for(var t : d.tile.getLinkedTiles(new arc.struct.Seq<>())) t.setOverlay(ores[oreAt++ % 4]);
                        Vars.renderer.blocks.floor.reload();
                        try{
                            Class<?> c = Vars.mods.mainLoader().loadClass("mshaders.OrePeek");
                            c.getField("testX").setFloat(null, d.x);
                            c.getField("testY").setFloat(null, d.y);
                        }catch(Throwable t){
                            line("  no peek: " + t);
                        }
                        camX = d.x;
                        camY = d.y;
                        camSet = true;
                    }
                }
                case "drillbelt" -> placeDrillBelt();
                case "row" -> placeRow();
                case "cluster" -> placeCluster();
                case "walls" -> placeWalls();
                case "drivers" -> placeDrivers();
                case "turrets" -> placeTurrets(v);
                case "warm" -> turretWarm = Float.parseFloat(v);
                case "aim" -> driverAim = Float.parseFloat(v);
                case "recoil" -> driverRecoil = Float.parseFloat(v);
                case "joins" -> placeJoins();
                case "cam" -> {
                    camX = (baseX + Float.parseFloat(v.split(":")[0]) + 0.5f) * Vars.tilesize;
                    camY = (baseY + Float.parseFloat(v.split(":")[1]) + 0.5f) * Vars.tilesize;
                    camSet = true;
                }
                case "field" -> placeField();
                case "fieldof" -> placeFieldOf(v);
                case "wallshapes" -> placeWallShapes(v);
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
                case "ore" -> paintOre(v);
                case "unit" -> {
                    var type = Vars.content.unit(v);
                    if(unitPos != null && type != null) lastUnit = type.spawn(Vars.player.team(), unitPos[0], unitPos[1]);
                }
                case "hover" -> {
                    // a unit right over the smelter (things above a block must not take the block's shading)
                    var hoverType = Vars.content.unit(v);
                    if(hoverPos != null && hoverType != null) lastUnit = hoverType.spawn(Vars.player.team(), hoverPos[0], hoverPos[1]);
                }
                case "crowdbelow" -> crowdBelow = Float.parseFloat(v);
                case "enemyright" -> enemyRight = Float.parseFloat(v);
                case "crowd" -> spawnCrowd(v);
                case "face" -> face = Float.parseFloat(v);
                case "facestep" -> faceStep = Float.parseFloat(v);
                case "stride" -> stride = Float.parseFloat(v);
                case "close" -> arc.util.Timer.schedule(() -> {
                    // close=SECONDS: closes the dialog that is open, that long after the shot began
                    if(Core.scene.getDialog() != null) Core.scene.getDialog().hide();
                }, Float.parseFloat(v));
                case "open" -> {
                    try{
                        Object made = Vars.mods.mainLoader().loadClass(v).getDeclaredConstructor().newInstance();
                        ((arc.scene.ui.Dialog)made).show();
                        line("  opened " + v);
                    }catch(Throwable t){
                        line("  could not open " + v + ": " + t);
                    }
                }
                case "kit" -> {
                    for(String sprite : v.split("[+]")){
                        try{
                            var region = Core.atlas.find(sprite);
                            if(!region.found()){
                                line("  sprite " + sprite + ": not in atlas");
                                continue;
                            }
                            Pixmap p = Core.atlas.getPixmap(region).crop();
                            PixmapIO.writePng(new Fi(outDir).child(sprite + ".png"), p);
                            Pixmap flat = new Pixmap(p.width, p.height);
                            ObjectIntMap<Integer> used = new ObjectIntMap<>();
                            for(int x = 0; x < p.width; x++){
                                for(int y = 0; y < p.height; y++){
                                    if((p.get(x, y) & 0xff) < 128) continue;
                                    flat.set(x, y, 0x8080ffff);
                                    used.increment(p.get(x, y), 0, 1);
                                }
                            }
                            PixmapIO.writePng(new Fi(outDir).child(sprite + "-flat.png"), flat);
                            java.util.List<Integer> top = new java.util.ArrayList<>();
                            for(var e : used.entries()) top.add(e.key);
                            top.sort((c1, c2) -> used.get(c2, 0) - used.get(c1, 0));
                            StringBuilder colours = new StringBuilder();
                            for(int i = 0; i < Math.min(5, top.size()); i++) colours.append(String.format(" #%06x", top.get(i) >>> 8));
                            line("  sprite " + sprite + " " + p.width + "x" + p.height + ", " + top.size() + " colours, most used:" + colours);
                            flat.dispose();
                            p.dispose();
                        }catch(Throwable t){
                            line("  sprite " + sprite + ": " + t);
                        }
                    }
                }
                case "onwalls" -> ontoWalls();
                case "click" -> {
                    // a moment later: a dialog opened by the same shot has built itself by then. click=NAME@SECONDS
                    // presses it that long after the shot began (several presses in one shot, one after another).
                    String target = v.contains("@") ? v.substring(0, v.indexOf('@')) : v;
                    float after = v.contains("@") ? Float.parseFloat(v.substring(v.indexOf('@') + 1)) : 0.3f;
                    String wanted = target.replace('_', ' ');
                    arc.util.Timer.schedule(() -> {
                        arc.scene.Element hit = Core.scene.root.find(target);
                        if(hit == null) hit = Core.scene.root.find(e -> e instanceof arc.scene.ui.TextButton b && b.getText().toString().contains(wanted));
                        if(hit instanceof arc.scene.ui.Button b){
                            b.fireClick();
                            line("  pressed the button '" + (b instanceof arc.scene.ui.TextButton t && !t.getText().toString().isEmpty() ? target : target) + "'");
                        }else{
                            line("  no button with the name or text '" + wanted + "'");
                        }
                    }, after);
                }
                case "savefile" -> {
                    String[] ends = v.split(">", 2);
                    new Fi(ends[0]).copyTo(new Fi(ends[1]));
                    line("  copied " + new Fi(ends[0]).name() + " to " + ends[1]);
                }
                // removefile=path: a picture taken out of a watched folder while the game runs. Only inside the
                // system's folder for temporary files: the pictures of the mod itself are never deleted by a test.
                case "removefile" -> {
                    java.io.File gone = new java.io.File(v).getAbsoluteFile().toPath().normalize().toFile();
                    java.io.File temp = new java.io.File(System.getProperty("java.io.tmpdir")).getAbsoluteFile().toPath().normalize().toFile();
                    if(!gone.toPath().startsWith(temp.toPath())) line("  NOT removed (not in the temporary folder): " + v);
                    else line("  removed " + gone.getName() + ": " + gone.delete());
                }
                case "landing" -> {
                    var core = Vars.player.team().core();
                    if(v.equals("off") || core == null){
                        if(core == null) line("  no core to land");
                        if(!Float.isNaN(landing)) arc.util.Reflect.set(mindustry.core.Renderer.class, Vars.renderer, "landTime", 0.001f);
                        landing = Float.NaN;
                    }else{
                        if(Float.isNaN(landing)) Vars.renderer.showLanding(core);
                        landing = Float.parseFloat(v);
                        camSet = false;
                    }
                }
                case "spin" -> spin = Float.parseFloat(v);
                case "shields" -> placeShields();
                case "erekirshields" -> {
                    // erekirshields=true: a large shield projector, and a column of shielded walls beside it (the team cheats, so all is powered)
                    var team = Vars.player.team();
                    Vars.state.rules.teams.get(team).cheat = true;
                    int mx = (int)(camX / Vars.tilesize), my = (int)(camY / Vars.tilesize);
                    for(int dx = -16; dx <= 16; dx++){
                        for(int dy = -12; dy <= 12; dy++){
                            var t = Vars.world.tile(mx + dx, my + dy);
                            if(t != null && t.block() != Blocks.air) t.setAir();
                        }
                    }
                    // the large shield far to the left, out of the way of the others (shields that overlap merge into one shape)
                    var big = Vars.world.tile(mx - 60, my);
                    if(big != null) big.setBlock(Blocks.largeShieldProjector, team, 0);
                    for(int dy = -6; dy <= 6; dy += 2) Vars.world.tile(mx + 2, my + dy).setBlock(Blocks.shieldedWall, team, 0);
                    Vars.world.tile(mx + 40, my).setBlock(Blocks.forceProjector, team, 0);
                    line("  large shield projector, shielded walls, a force projector");
                }
                case "zoomf" -> {
                    // zoomf=F: any zoom, fractions too (0.5 = as far out as the game lets a player go)
                    Vars.renderer.minZoom = Math.min(Vars.renderer.minZoom, Float.parseFloat(v));
                    Vars.renderer.setScale(Scl.scl(Float.parseFloat(v)));
                }
                case "center" -> {
                    // center=X:Y: the camera on that share of the map (0.5:0.5 = its middle)
                    camX = Vars.world.unitWidth() * Float.parseFloat(v.split(":")[0]);
                    camY = Vars.world.unitHeight() * Float.parseFloat(v.split(":")[1]);
                    camSet = true;
                    line("  camera at " + (int)(camX / 8) + "," + (int)(camY / 8) + " of " + Vars.world.width() + "x" + Vars.world.height());
                }
                case "swarm" -> {
                    // swarm=TYPE:N: N units of that kind, half the player's team and half the enemy's, scattered over
                    // the view - they fight on their own (many units, many shots)
                    String[] p = v.split(":");
                    var type = Vars.content.unit(p[0]);
                    int n = Integer.parseInt(p[1]);
                    float w = Core.camera.width * 0.45f, h = Core.camera.height * 0.45f;
                    for(int i = 0; type != null && i < n; i++){
                        var team = i % 2 == 0 ? Vars.player.team() : mindustry.game.Team.crux;
                        float side = i % 2 == 0 ? -1f : 1f;
                        type.spawn(team, camX + side * Mathf.random(0.1f, 1f) * w, camY + Mathf.range(h));
                    }
                    Vars.state.rules.waves = false;
                    line("  swarm of " + n + " " + p[0] + " (units now " + mindustry.gen.Groups.unit.size() + ")");
                }
                case "barrage" -> barrage = Integer.parseInt(v);
                case "layers" -> {
                    layersShot = Boolean.parseBoolean(v);
                    if(layersShot) layerTimer.start();
                }
                case "pulses" -> {
                    // pulses=true: a mender and an overdrive projector (no power needed) among some walls, the camera between them
                    var team = Vars.player.team();
                    Vars.state.rules.teams.get(team).cheat = true;
                    int bx = (int)(camX / Vars.tilesize), by = (int)(camY / Vars.tilesize);
                    for(int dx = -12; dx <= 12; dx++) for(int dy = -8; dy <= 8; dy++){
                        var tt = Vars.world.tile(bx + dx, by + dy);
                        if(tt != null && tt.build == null && tt.block() != Blocks.air) tt.setAir();
                    }
                    var m = Vars.world.tile(bx - 6, by);
                    if(m != null) m.setBlock(Blocks.mendProjector, team, 0);
                    var o = Vars.world.tile(bx + 7, by);
                    if(o != null) o.setBlock(Blocks.overdriveProjector, team, 0);
                    for(int i = -3; i <= 3; i += 2){
                        var w1 = Vars.world.tile(bx - 6 + i, by + 4);
                        if(w1 != null && w1.build == null) w1.setBlock(Blocks.titaniumWall, team, 0);
                        var w2 = Vars.world.tile(bx + 7 + i, by - 4);
                        if(w2 != null && w2.build == null) w2.setBlock(Blocks.titaniumWall, team, 0);
                    }
                    pulseX = (bx - 6) * Vars.tilesize + 4f;
                    pulseY = by * Vars.tilesize + 4f;
                    camSet = true;
                    line("  mend projector and overdrive projector near " + bx + "," + by);
                }
                case "menders" -> {
                    // menders=true (after pulses=true): twelve menders packed in a block of 4 x 3 above the mend projector
                    var team = Vars.player.team();
                    int mx = (int)(pulseX / Vars.tilesize), my = (int)(pulseY / Vars.tilesize) + 6;
                    for(int i = 0; i < 4; i++) for(int j = 0; j < 3; j++){
                        var tt = Vars.world.tile(mx - 2 + i, my + j);
                        if(tt != null){
                            if(tt.build == null && tt.block() != Blocks.air) tt.setAir();
                            if(tt.build == null) tt.setBlock(Blocks.mender, team, 0);
                        }
                    }
                    camX = pulseX;
                    camY = pulseY + 48f;
                    line("  twelve menders at " + mx + "," + my);
                }
                case "menderrow" -> {
                    // menderrow=N (after pulses=true): N menders in a row, left to right, below the mend projector
                    var team = Vars.player.team();
                    int mx = (int)(pulseX / Vars.tilesize) - 2, my = (int)(pulseY / Vars.tilesize) - 6;
                    for(int i = 0; i < Integer.parseInt(v); i++){
                        var tt = Vars.world.tile(mx + i, my);
                        if(tt != null){
                            if(tt.build == null && tt.block() != Blocks.air) tt.setAir();
                            if(tt.build == null) tt.setBlock(Blocks.mender, team, 0);
                        }
                    }
                    camX = pulseX + Integer.parseInt(v) * 4f;
                    camY = pulseY - 48f;
                    line("  a row of " + v + " menders at " + mx + "," + my);
                }
                case "statuses" -> statusRow = v.split("[+]");
                case "grid" -> {
                    // grid=true: a combustion generator line feeding a few power nodes and a ground factory making daggers
                    var team = Vars.player.team();
                    Vars.state.rules.teams.get(team).cheat = true;
                    int bx = (int)(camX / Vars.tilesize), by = (int)(camY / Vars.tilesize);
                    for(int dx = -14; dx <= 14; dx++) for(int dy = -9; dy <= 9; dy++){
                        var tt = Vars.world.tile(bx + dx, by + dy);
                        if(tt != null && tt.build == null && tt.block() != Blocks.air) tt.setAir();
                    }
                    int[][] at = {{-12, 4}, {-6, 6}, {0, 4}, {6, 6}};
                    var ft = Vars.world.tile(bx + 6, by + 1);
                    if(ft != null) ft.setBlock(Blocks.groundFactory, team, 0);
                    var gen = Vars.world.tile(bx - 12, by + 1);
                    if(gen != null) gen.setBlock(Blocks.largeSolarPanel, team, 0);
                    mindustry.gen.Building prev = null, first = null;
                    for(int[] a : at){
                        var nt = Vars.world.tile(bx + a[0], by + a[1]);
                        if(nt == null) continue;
                        nt.setBlock(Blocks.powerNode, team, 0);
                        if(prev != null && nt.build != null) prev.configureAny(nt.build.pos());
                        if(first == null) first = nt.build;
                        prev = nt.build;
                    }
                    // the nodes link to what they power (a generator takes no links of its own)
                    if(first != null && gen != null && gen.build != null) first.configureAny(gen.build.pos());
                    if(prev != null && ft != null && ft.build != null) prev.configureAny(ft.build.pos());
                    if(ft != null && ft.build instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild fb){
                        fb.configureAny(0);
                        fb.items.add(Items.silicon, 100);
                        fb.items.add(Items.lead, 100);
                    }
                    camSet = true;
                    line("  power nodes and a ground factory near " + bx + "," + by);
                }
                case "place" -> {
                    // place=BLOCK: the player is placing that block (as if picked from the menu); the cursor is held on the camera's middle
                    Vars.control.input.block = Vars.content.block(v);
                    holdCursor = true;
                    try{
                        Class<?> c = Vars.mods.mainLoader().loadClass("mshaders.Cutaway");
                        c.getField("testX").setFloat(null, camX);
                        c.getField("testY").setFloat(null, camY);
                    }catch(Throwable t){
                        line("  no cursor: " + t);
                    }
                }
                case "menderl" -> {
                    // menderl=true (after pulses=true): an L of menders, 10 across and 6 up, below the mend projector
                    var team = Vars.player.team();
                    int mx = (int)(pulseX / Vars.tilesize) - 2, my = (int)(pulseY / Vars.tilesize) - 12;
                    for(int i = 0; i < 16; i++){
                        var tt = i < 10 ? Vars.world.tile(mx + i, my) : Vars.world.tile(mx + 9, my + i - 9);
                        if(tt != null){
                            if(tt.build == null && tt.block() != Blocks.air) tt.setAir();
                            if(tt.build == null) tt.setBlock(Blocks.mender, team, 0);
                        }
                    }
                    camX = (mx + 5) * Vars.tilesize;
                    camY = (my + 3) * Vars.tilesize;
                    line("  an L of menders at " + mx + "," + my);
                }
                case "groupwave" -> {
                    // groupwave=AGE (after menderl= or menders=, time=): the wave of the group of menders nearest the camera, AGE ticks ago
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    try{
                        Object r = Vars.mods.mainLoader().loadClass("mshaders.Pulses").getMethod("testGroup", float.class, float.class, float.class)
                            .invoke(null, camX, camY, now - Float.parseFloat(v));
                        line("  group wave: " + r);
                    }catch(Throwable t){
                        line("  no wave: " + t);
                    }
                }
                case "rowwave" -> {
                    // rowwave=AGE (after menderrow=16, time=): that row's wave AGE ticks after it went out
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    float y0 = ((int)(pulseY / Vars.tilesize) - 6) * Vars.tilesize, x0 = ((int)(pulseX / Vars.tilesize) - 2) * Vars.tilesize;
                    try{
                        Vars.mods.mainLoader().loadClass("mshaders.Pulses").getMethod("testLine", float.class, float.class, float.class, float.class, float.class, float.class)
                            .invoke(null, x0, y0, x0 + 15 * Vars.tilesize, y0, 40f, now - Float.parseFloat(v));
                    }catch(Throwable t){
                        line("  no wave: " + t);
                    }
                }
                case "odwave" -> {
                    // odwave=AGE (after time=): the overdrive projector's faint wave AGE ticks after it went out
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    try{
                        Vars.mods.mainLoader().loadClass("mshaders.Pulses").getMethod("testOverdrive", float.class, float.class, float.class, float.class)
                            .invoke(null, pulseX + 13 * Vars.tilesize, pulseY, 80f, now - Float.parseFloat(v));
                    }catch(Throwable t){
                        line("  no wave: " + t);
                    }
                }
                case "wave" -> {
                    // wave=AGE (after time=): the mend projector's wave AGE ticks after it healed
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    try{
                        Vars.mods.mainLoader().loadClass("mshaders.Pulses").getMethod("testWave", float.class, float.class, float.class, float.class)
                            .invoke(null, pulseX, pulseY, 85f, now - Float.parseFloat(v));
                    }catch(Throwable t){
                        line("  no wave: " + t);
                    }
                }
                case "bigshield" -> {
                    // bigshield=true: Erekir's large shield projector (round) in the middle of the view, no power needed
                    var team = Vars.player.team();
                    Vars.state.rules.teams.get(team).cheat = true;
                    int bx = (int)(camX / Vars.tilesize), by = (int)(camY / Vars.tilesize);
                    for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++){
                        var tt = Vars.world.tile(bx + dx, by + dy);
                        if(tt != null && tt.build == null && tt.block() != Blocks.air) tt.setAir();
                    }
                    var tt = Vars.world.tile(bx, by);
                    if(tt != null) tt.setBlock(Blocks.largeShieldProjector, team, 0);
                    camSet = true;
                    line("  large shield projector at " + bx + "," + by);
                }
                case "shields2" -> {
                    // a second force projector 7 tiles right of the first: two shields that merge
                    placeShields();
                    var t2 = Vars.world.tile((int)(shieldX / Vars.tilesize) + 7, (int)(shieldY / Vars.tilesize));
                    if(t2 != null){
                        if(t2.build == null && t2.block() != Blocks.air) t2.setAir();
                        t2.setBlock(Blocks.forceProjector, Vars.player.team(), 0);
                    }
                    if(shieldUnit != null) shieldUnit.kill();
                    shieldUnit = null;
                    camX = shieldX + 28f;
                }
                case "pelt" -> pelt = Float.parseFloat(v);
                case "shell" -> {
                    // shell=DX:DY (camera middle + that): a ripple's graphite shell, fired from 200 units to the left,
                    // timed to come down there (the shells a turret fires)
                    String[] p = v.split(":");
                    float tx = camX + Float.parseFloat(p[0]), ty = camY + Float.parseFloat(p[1]), fx = tx - 200f, fy = ty - 30f;
                    var shellType = ((mindustry.world.blocks.defense.turrets.ItemTurret)Blocks.ripple).ammoTypes.get(Items.graphite);
                    float dist = Mathf.dst(fx, fy, tx, ty);
                    shellType.create(null, mindustry.game.Team.crux, fx, fy, Angles.angle(fx, fy, tx, ty), -1f, 1f, dist / (shellType.speed * shellType.lifetime), null);
                }
                case "blast" -> {
                    // blast=DX:DY:REACH:AGE (after time=): an explosion that far from the camera's middle, AGE ticks ago
                    String[] p = v.split(":");
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    try{
                        Vars.mods.mainLoader().loadClass("mshaders.Blasts").getMethod("testBlast", float.class, float.class, float.class, float.class)
                            .invoke(null, camX + Float.parseFloat(p[0]), camY + Float.parseFloat(p[1]), now - Float.parseFloat(p[3]), Float.parseFloat(p[2]));
                    }catch(Throwable t){
                        line("  no blast: " + t);
                    }
                }
                case "shieldhurt" -> shieldHurt = Float.parseFloat(v);
                case "shieldbreak" -> {
                    // breaks both shields now (and stops holding them up); shieldback=true lets them come back
                    shieldHold = false;
                    var b = Vars.world.buildWorld(shieldX, shieldY);
                    if(b instanceof mindustry.world.blocks.defense.ForceProjector.ForceBuild force) force.buildup = 1e6f;
                    if(shieldUnit != null && shieldUnit.isValid()) shieldUnit.shield = 0f;
                }
                case "shieldback" -> {
                    shieldHold = true;
                    var b = Vars.world.buildWorld(shieldX, shieldY);
                    if(b instanceof mindustry.world.blocks.defense.ForceProjector.ForceBuild force) force.buildup = 0f;
                }
                case "ring" -> {
                    // ring=DX:DY:AGE (after shields= and time=): a shot stopped that far from the projector, AGE ticks ago
                    String[] p = v.split(":");
                    float now = holdTime >= 0f ? holdTime : Time.time;
                    try{
                        Vars.mods.mainLoader().loadClass("mshaders.Shields").getMethod("testHit", float.class, float.class, float.class)
                            .invoke(null, shieldX + Float.parseFloat(p[0]), shieldY + Float.parseFloat(p[1]), now - Float.parseFloat(p[2]));
                    }catch(Throwable t){
                        line("  no ring: " + t);
                    }
                }
                case "swerve" -> {
                    swerve = Float.parseFloat(v.split(":")[0]);
                    swerveStop = v.contains(":") ? Float.parseFloat(v.split(":")[1]) : 6f;
                    // the clock runs again; the turn begins half a second from now
                    holdTime = -1f;
                    swerveFrom = Float.MAX_VALUE;
                    swerveArmed = true;
                }
                case "fly" -> {
                    flyForward = Float.parseFloat(v.split(":")[0]);
                    flySide = v.contains(":") ? Float.parseFloat(v.split(":")[1]) : 0f;
                }
                case "unitkit" -> unitKit(v);
                case "heldshot" -> heldShot = Float.parseFloat(v);
                // heldat=dx:dy: the held shot that far (world units) from the first test lamp, to move it across a block
                case "heldat" -> {
                    heldDx = Float.parseFloat(v.split(":")[0]);
                    heldDy = Float.parseFloat(v.split(":")[1]);
                }
                case "enemy" -> {
                    // enemy=name: a unit of the other team held to the right of the row, for the row to shoot at; nothing in the row dies
                    var foe = Vars.content.unit(v);
                    if(foe != null && lampPos != null){
                        float ex = lampPos[0] + enemyRight, ey = lampPos[1] - crowdBelow;
                        crowd.add(foe.spawn(Team.crux, ex, ey));
                        crowdAt.add(ex, ey);
                    }
                }
                // ammo=true: every item turret of the player's team gets its first kind of ammunition, so it fires at what comes near
                case "ammo" -> {
                    int fed = 0;
                    for(var build : mindustry.gen.Groups.build){
                        if(build.team != Vars.player.team() || !(build.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret turret)) continue;
                        var item = turret.ammoTypes.keys().toSeq().first();
                        for(int i = 0; i < 30 && build.acceptItem(build, item); i++) build.handleItem(build, item);
                        fed++;
                    }
                    line("  ammunition given to " + fed + " turret(s)");
                }
                case "pin" -> pinAt = new float[]{(baseX + Float.parseFloat(v.split(":")[0]) + 0.5f) * Vars.tilesize, (baseY + Float.parseFloat(v.split(":")[1]) + 0.5f) * Vars.tilesize};
                case "sprites" -> dumpSprites(v);
                case "spritefiles" -> spriteFiles();
                case "spritefb" -> pendingSprites = v;
                case "mem" -> {
                    // mem=true: memory in use now, after a collection (Java heap, and native memory the engine counts)
                    System.gc();
                    var rt = Runtime.getRuntime();
                    String ws = "?";
                    try{
                        var pr = new ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id " + ProcessHandle.current().pid() + ").WorkingSet64; (Get-Process -Id " + ProcessHandle.current().pid() + ").PrivateMemorySize64").redirectErrorStream(true).start();
                        String[] out = new String(pr.getInputStream().readAllBytes()).trim().split("[\\r\\n ]+");
                        ws = Long.parseLong(out[0]) / 1048576 + " MB working set, " + Long.parseLong(out[1]) / 1048576 + " MB private";
                    }catch(Throwable t){
                        ws = t.toString();
                    }
                    line("  memory: java heap " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " MB; process " + ws);
                }
                case "turretlist" -> listTurrets();
                case "weaponlist" -> listWeapons();
                case "unitcolors" -> unitColors();
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
            // dialog=dev: the game's own developer options (page 3)
            if(name.equals("dev")){
                Method page = SettingsMenuDialog.class.getDeclaredMethod("visible", int.class);
                page.setAccessible(true);
                page.invoke(dialog, 3);
                line("  developer options shown with " + dialog.dev.getSettings().map(s -> s.name));
                return;
            }
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
     * blockrow=a+b+c: those blocks in a row, two tiles between them, nine tiles above the test buildings (after
     * blocks=true) - any blocks side by side, to compare their shadows. The camera moves to the middle of the row.
     */
    void placeBlockRow(String names){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        int x = baseX, placed = 0;
        for(String name : names.split("[+]")){
            var block = Vars.content.block(name);
            if(block == null){
                line("  no such block: " + name);
                continue;
            }
            int size = block.size;
            for(int dx = -2; dx < size + 2; dx++){
                for(int dy = -3; dy < size + 3; dy++){
                    var t = Vars.world.tile(x + dx, baseY + 9 + dy);
                    if(t != null && t.build == null && t.block() != Blocks.air) t.setAir();
                }
            }
            // setBlock takes the tile the block is centred on
            var t = Vars.world.tile(x - block.sizeOffset, baseY + 9 - block.sizeOffset);
            if(t != null && t.build == null) t.setBlock(block, team, 0);
            x += size + 2;
            placed++;
        }
        camX = (baseX + (x - baseX - 2) / 2f - 0.5f) * Vars.tilesize;
        camY = (baseY + 10) * Vars.tilesize;
        line("  placed a row of " + placed + " blocks: " + names);
    }

    /**
     * sortrow=true: a row with one of each box a conveyor line runs through, a tile apart, eight tiles above the test
     * buildings (after blocks=true): conveyor, junction, sorter, inverted sorter, overflow gate, underflow gate, router,
     * distributor - to compare how long their shadows are. The camera moves there.
     */
    void placeSortRow(){
        if(baseW == 0 || baseShore) return;
        var team = Vars.player.team();
        mindustry.world.Block[] row = {Blocks.conveyor, Blocks.junction, Blocks.sorter, Blocks.invertedSorter, Blocks.overflowGate, Blocks.underflowGate, Blocks.router, Blocks.distributor};
        for(int dx = -1; dx < row.length * 2 + 2; dx++){
            for(int dy = -2; dy <= 2; dy++){
                var t = Vars.world.tile(baseX + dx, baseY + 9 + dy);
                if(t != null && t.build == null && t.block() != Blocks.air) t.setAir();
            }
        }
        for(int i = 0; i < row.length; i++) put(baseX + i * 2, baseY + 9, row[i], team);
        camX = (baseX + row.length - 0.5f) * Vars.tilesize;
        camY = (baseY + 9) * Vars.tilesize;
        line("  placed a row: conveyor, junction, sorter, inverted sorter, overflow gate, underflow gate, router, distributor");
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

    void placeShields(){
        if(baseW == 0) return;
        var team = Vars.player.team();
        Vars.state.rules.teams.get(team).cheat = true;
        int x = baseX + 4, y = baseY + 10;
        for(int dx = -12; dx < 14; dx++){
            for(int dy = -10; dy < 10; dy++){
                var t = Vars.world.tile(x + dx, y + dy);
                if(t != null && t.build == null && t.block() != Blocks.air) t.setAir();
            }
        }
        var t = Vars.world.tile(x, y);
        if(t != null) t.setBlock(Blocks.forceProjector, team, 0);
        shieldX = x * Vars.tilesize;
        shieldY = y * Vars.tilesize;
        shieldUnit = mindustry.content.UnitTypes.quasar.spawn(team, shieldX + 150f, shieldY);
        camX = shieldX + 75f;
        camY = shieldY;
        camSet = true;
        line("  force projector at tile " + x + "," + y + " and a quasar beside it");
    }

    void holdShields(){
        if(!shieldHold){
            if(shieldUnit != null && shieldUnit.isValid()){
                shieldUnit.set(shieldX + 150f, shieldY);
                shieldUnit.vel.setZero();
                shieldUnit.health = shieldUnit.maxHealth;
            }
            return;
        }
        if(shieldUnit != null && shieldUnit.isValid()){
            shieldUnit.set(shieldX + 150f, shieldY);
            shieldUnit.vel.setZero();
            shieldUnit.rotation = 90f;
            shieldUnit.shield = 400f * (1f - shieldHurt);
            shieldUnit.health = shieldUnit.maxHealth;
        }
        var b = Vars.world.buildWorld(shieldX, shieldY);
        if(b != null) b.health = b.maxHealth;
        if(b instanceof mindustry.world.blocks.defense.ForceProjector.ForceBuild force) force.buildup = ((mindustry.world.blocks.defense.ForceProjector)force.block).shieldHealth * shieldHurt;
        if(pelt > 0f && Time.time - lastPelt >= pelt){
            lastPelt = Time.time;
            var shot = mindustry.content.UnitTypes.dagger.weapons.first().bullet;
            float sy = shieldY + Mathf.range(40f);
            shot.create(null, mindustry.game.Team.crux, shieldX + 260f, sy, Angles.angle(shieldX + 260f, sy, shieldX, shieldY));
            if(shieldUnit != null && shieldUnit.isValid()){
                float uy = shieldY + Mathf.range(20f);
                shot.create(null, mindustry.game.Team.crux, shieldX + 260f, uy + 60f, Angles.angle(shieldX + 260f, uy + 60f, shieldX + 150f, shieldY));
            }
        }
    }

    /**
     * ore=NAME (after paint=): that ore in a band six tiles high across the middle of the view, over every strip - the
     * same ore on several floors, side by side.
     */
    void paintOre(String name){
        var ore = Vars.content.block(name);
        if(ore == null || !ore.isOverlay()){
            line("  not an ore: " + name);
            return;
        }
        int mid = viewY + viewH / 2;
        for(int x = viewX - 12; x < viewX + viewW + 12; x++){
            for(int y = mid - 3; y < mid + 3; y++){
                var t = Vars.world.tile(x, y);
                if(t != null) t.setOverlay(ore);
            }
        }
        Vars.renderer.blocks.floor.reload();
        line("  ore " + name + " across the middle of the view");
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

    /** wallshapes=a+b: walls of one kind touching each other in a few shapes, a band for each kind. */
    void placeWallShapes(String names){
        if(baseW == 0) return;
        var team = Vars.player.team();
        // the shapes, in steps of one wall: where each wall of a shape stands
        int[][][] shapes = {
            {{0, 0}},
            {{0, 0}, {1, 0}, {2, 0}, {3, 0}},
            {{0, 0}, {0, 1}, {0, 2}},
            {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {0, 2}},
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},
            {{0, 2}, {1, 2}, {2, 2}, {1, 1}, {1, 0}},
        };
        int band = baseY + 10, placed = 0;
        boolean first = true;
        for(String name : names.split("[+]")){
            mindustry.world.Block block = Vars.content.block(name);
            if(block == null){
                line("  no block named " + name);
                continue;
            }
            int s = block.size, x = baseX - 4;
            // room
            for(int cx = x - 2; cx < x + 30 * s; cx++){
                for(int cy = band - 2; cy < band + 3 * s + 2; cy++){
                    var t = Vars.world.tile(cx, cy);
                    if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
                }
            }
            if(s > 1){
                // first of all: a large wall under the seam of two others, half a wall along - these must not join
                int[][] off = {{x, band + s}, {x + s, band + s}, {x + s / 2, band}};
                for(int[] at : off){
                    var t = Vars.world.tile(at[0], at[1]);
                    if(t != null && t.build == null && !t.block().isStatic() && !t.floor().isLiquid){
                        t.setBlock(block, team, 0);
                        placed++;
                    }
                }
                x += 3 * s;
            }
            for(int[][] shape : shapes){
                int wide = 0;
                for(int[] at : shape){
                    var t = Vars.world.tile(x + at[0] * s, band + at[1] * s);
                    if(t != null && t.build == null && !t.block().isStatic() && !t.floor().isLiquid){
                        t.setBlock(block, team, 0);
                        placed++;
                    }
                    wide = Math.max(wide, at[0] + 1);
                }
                x += (wide + 1) * s + (s > 1 ? 0 : 1);
            }
            if(first){
                camX = (baseX - 4 + (x - (baseX - 4)) / 2f) * Vars.tilesize;
                camY = (band + 1.5f * s) * Vars.tilesize;
                first = false;
            }
            band += 3 * s + 3;
        }
        line("  placed " + placed + " walls in shapes (" + names + ")");
    }

    /** fieldof=a+b: those blocks over the whole view, taking turns, each on the next free square of its size. */
    void placeFieldOf(String names){
        var team = Vars.player.team();
        Seq<mindustry.world.Block> kinds = new Seq<>();
        int step = 1;
        for(String name : names.split("[+]")){
            mindustry.world.Block block = Vars.content.block(name);
            if(block == null){
                line("  no block named " + name);
                continue;
            }
            kinds.add(block);
            step = Math.max(step, block.size);
        }
        if(kinds.isEmpty()) return;
        int placed = 0;
        for(int x = viewX - 2; x < viewX + viewW + 2; x += step){
            for(int y = viewY - 2; y < viewY + viewH + 2; y += step){
                var t = Vars.world.tile(x, y);
                if(t == null) continue;
                mindustry.world.Block block = kinds.get(placed % kinds.size);
                boolean free = true;
                for(int dx = 0; dx < block.size && free; dx++){
                    for(int dy = 0; dy < block.size && free; dy++){
                        var o = Vars.world.tile(x + dx + block.sizeOffset, y + dy + block.sizeOffset);
                        free = o != null && o.build == null && !o.block().isStatic() && !o.floor().isLiquid;
                    }
                }
                if(!free) continue;
                t.setBlock(block, team, 0);
                placed++;
            }
        }
        line("  placed a field of " + placed + " blocks (" + names + ")");
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
    void placeTurrets(String which){
        if(baseW == 0 || baseShore) return;
        mindustry.world.Block[] kinds = {Blocks.duo, Blocks.hail, Blocks.lancer, Blocks.ripple, Blocks.repairPoint, Blocks.parallax, Blocks.segment, Blocks.buildTower};
        turretStep = 4;
        turretTurn = 50f;
        if(!which.equals("true")){
            // turrets=a+b: those blocks instead, six tiles apart (room for the large ones), all aiming the same way
            String[] names = which.split("[+]");
            kinds = new mindustry.world.Block[names.length];
            for(int i = 0; i < names.length; i++) kinds[i] = Vars.content.block(names[i]);
            turretStep = 6;
            turretTurn = 0f;
        }
        turretX = baseX - 6;
        turretY = baseY + 12;
        turretCount = kinds.length;
        for(int dx = -3; dx <= kinds.length * turretStep + 2; dx++){
            for(int dy = -4; dy <= 4; dy++){
                var t = Vars.world.tile(turretX + dx, turretY + dy);
                if(t != null && t.build == null && t.block() != Blocks.air && !t.block().isStatic()) t.setAir();
            }
        }
        StringBuilder placed = new StringBuilder();
        for(int i = 0; i < kinds.length; i++){
            put(turretX + i * turretStep, turretY, kinds[i], Vars.player.team());
            var b = Vars.world.build(turretX + i * turretStep, turretY);
            placed.append(" ").append(kinds[i].name).append(b != null && b.block == kinds[i] ? "" : "(NOT placed)");
        }
        camX = (turretX + (kinds.length - 1) * turretStep / 2f + 0.5f) * Vars.tilesize;
        camY = (turretY + 0.5f) * Vars.tilesize;
        lampPos = new float[]{(turretX + 6f) * Vars.tilesize, (turretY + 3f) * Vars.tilesize, (turretX + 20f) * Vars.tilesize, (turretY - 2.5f) * Vars.tilesize};
        line("  placed at tile " + turretX + "," + turretY + ":" + placed);
    }

    /** Every frame: the heads of those blocks held at driverAim + 50 degrees per block, the turrets pushed back as right after a shot (recoil=1). */
    void holdTurrets(){
        for(int i = 0; i < turretCount; i++){
            var b = Vars.world.build(turretX + i * turretStep, turretY);
            float aim = driverAim + i * turretTurn;
            if(b instanceof mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild t) t.rotation = aim;
            if(b instanceof mindustry.world.blocks.units.RepairTurret.RepairPointBuild t) t.rotation = aim;
            if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t){
                t.curRecoil = driverRecoil;
                if(!Float.isNaN(turretWarm)) t.shootWarmup = turretWarm;
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
        if(laser){
            // laser=true: the continuous laser of the vela (green, with the orange light the game gives such lasers), held in place from left of the middle of the view
            if(laserShot == null || !laserShot.isAdded()){
                laserShot = UnitTypes.vela.weapons.first().bullet.create(Vars.player.unit(), Vars.player.team(), camX - 70f, camY - 30f, 25f);
            }
            if(laserShot != null && laserShot.time > 40f) laserShot.time = 40f;
        }
        if(heldShot >= 0f && crowd.size > 0 && lampPos != null && crowd.first().type.weapons.size > 0){
            // heldshot=F: what a shot of that unit lights on its way, next to the test buildings and away from the unit's own glow
            if(shotHeld == null || !shotHeld.isAdded()){
                shotHeld = crowd.first().type.weapons.first().bullet.create(crowd.first(), crowd.first().team, lampPos[0], lampPos[1], 0f);
                // counted as fired by that unit itself (LightTap.firedAt tells by where a shot started), though it is held far from it
                if(shotHeld != null){
                    shotHeld.originX = crowd.first().x;
                    shotHeld.originY = crowd.first().y;
                }
            }
            if(shotHeld != null){
                shotHeld.set(lampPos[0] + heldDx, lampPos[1] + heldDy);
                shotHeld.time = Math.min(heldShot, 0.98f) * shotHeld.lifetime;
            }
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
        if(layersShot){
            line(layerTimer.report());
            layersShot = false;
        }
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

    /**
     * turretlist=true: every block of the game that is drawn as a base with a head turning on it, and the sprites the game
     * puts it together from, with their sizes: the ones that carry the picture (base, body, parts) and the ones that are
     * only effects laid over it (heat, liquid, top). A list of what there is to draw for each of them.
     */
    void listTurrets(){
        for(mindustry.world.Block block : Vars.content.blocks()){
            if(block.minfo.mod != null) continue;
            if(block instanceof mindustry.world.blocks.defense.turrets.Turret turret && turret.drawer instanceof mindustry.world.draw.DrawTurret drawer){
                line("turret " + block.name + " size=" + block.size + " base=" + reg(drawer.base) + " body=" + reg(block.region) + " outline=" + reg(drawer.outline)
                    + " preview=" + reg(drawer.preview) + " | effects: heat=" + reg(drawer.heat) + " liquid=" + reg(drawer.liquid) + " top=" + reg(drawer.top));
                listParts(drawer.parts, 1);
            }else if(block instanceof mindustry.world.blocks.distribution.MassDriver b){
                line("head " + block.name + " size=" + block.size + " base=" + reg(b.baseRegion) + " body=" + reg(block.region));
            }else if(block instanceof mindustry.world.blocks.units.RepairTurret b){
                line("head " + block.name + " size=" + block.size + " base=" + reg(b.baseRegion) + " body=" + reg(block.region));
            }else if(block instanceof mindustry.world.blocks.defense.BuildTurret b){
                line("head " + block.name + " size=" + block.size + " base=" + reg(b.baseRegion) + " body=" + reg(block.region) + " | effects: glow=" + reg(b.glowRegion));
            }else if(block instanceof mindustry.world.blocks.defense.turrets.TractorBeamTurret b){
                line("head " + block.name + " size=" + block.size + " base=" + reg(b.baseRegion) + " body=" + reg(block.region));
            }else if(block instanceof mindustry.world.blocks.defense.turrets.PointDefenseTurret b){
                line("head " + block.name + " size=" + block.size + " base=" + reg(b.baseRegion) + " body=" + reg(block.region));
            }
        }
    }

    void listParts(Seq<mindustry.entities.part.DrawPart> parts, int depth){
        for(mindustry.entities.part.DrawPart drawn : parts){
            String pad = "  ".repeat(depth);
            if(!(drawn instanceof mindustry.entities.part.RegionPart part)){
                line(pad + "effect-part " + drawn.getClass().getSimpleName());
                continue;
            }
            StringBuilder sb = new StringBuilder(pad + (part.drawRegion ? "part" : "heat-only-part"));
            for(TextureRegion r : part.regions) sb.append(' ').append(reg(r));
            if(part.under) sb.append(" under");
            if(part.mirror) sb.append(" mirror");
            if(part.outline) sb.append(" outline");
            if(part.blending != Blending.normal) sb.append(" blended");
            if(part.color != null || part.mixColor != null) sb.append(" coloured");
            if(part.layer > 0 || part.layerOffset != 0f) sb.append(" layer=").append(part.layer).append('+').append(part.layerOffset);
            sb.append(" move=").append(part.moveX).append(',').append(part.moveY).append(" rot=").append(part.moveRot).append(" moves=").append(part.moves.size);
            if(part.growX != 0f || part.growY != 0f || part.xScl != 1f || part.yScl != 1f) sb.append(" scaled");
            sb.append(" | heat=").append(reg(part.heat));
            line(sb.toString());
            listParts(part.children, depth + 1);
        }
    }

    static String reg(TextureRegion r){
        return r instanceof TextureAtlas.AtlasRegion a && r.found() && a.name != null ? a.name + "(" + r.width + "x" + r.height + ")" : "-";
    }

    /**
     * unitkit=a+b: what there is to draw for those units. For each: the sprites the game puts it together from and
     * where; each sprite saved pixel for pixel, and its shape filled with the colour of a flat face (128, 128, 255) as
     * something to start a normal map from.
     */
    void unitKit(String names){
        ObjectSet<String> saved = new ObjectSet<>();
        for(String name : names.split("[+]")){
            var type = Vars.content.unit(name);
            if(type == null){
                line("  no such unit: " + name);
                continue;
            }
            var sample = type.constructor.get();
            String kind = sample instanceof mindustry.gen.Mechc ? "walks on two legs" : sample instanceof mindustry.gen.Legsc ? "walks on many legs"
                : sample instanceof mindustry.gen.Tankc ? "tank" : type.flying ? "flies" : type.naval ? "ship" : "other";
            line("unit " + type.name + " (" + kind + ", hit size " + type.hitSize + ")");
            line("  body " + reg(type.region) + "  cell " + reg(type.cellRegion) + "  outline " + reg(type.outlineRegion));
            if(sample instanceof mindustry.gen.Mechc) line("  leg " + reg(type.legRegion) + "  hip " + reg(type.baseRegion));
            if(sample instanceof mindustry.gen.Tankc) line("  treads " + reg(type.treadRegion));
            Seq<String> sprites = Seq.with(type.name);
            if(sample instanceof mindustry.gen.Mechc) sprites.add(type.name + "-leg", type.name + "-base");
            if(sample instanceof mindustry.gen.Tankc) sprites.add(type.name + "-treads");
            for(var weapon : type.weapons){
                line("  weapon " + reg(weapon.region) + " at " + weapon.x + "," + weapon.y + (weapon.flipSprite ? " (flipped)" : "") + (weapon.rotate ? " turns" : "")
                    + (weapon.layerOffset != 0f ? " layer " + weapon.layerOffset : "") + (weapon.parts.size > 0 ? " parts " + weapon.parts.size : ""));
                if(weapon.region.found() && !sprites.contains(weapon.name)) sprites.add(weapon.name);
            }
            for(String sprite : sprites){
                if(!saved.add(sprite)) continue;
                try{
                    var region = Core.atlas.find(sprite);
                    if(!region.found()){
                        line("  sprite " + sprite + ": not in atlas");
                        continue;
                    }
                    Pixmap p = Core.atlas.getPixmap(region).crop();
                    PixmapIO.writePng(new Fi(outDir).child(sprite + ".png"), p);
                    Pixmap flat = new Pixmap(p.width, p.height);
                    for(int x = 0; x < p.width; x++){
                        for(int y = 0; y < p.height; y++){
                            if((p.get(x, y) & 0xff) >= 128) flat.set(x, y, 0x8080ffff);
                        }
                    }
                    PixmapIO.writePng(new Fi(outDir).child(sprite + "-flat.png"), flat);
                    flat.dispose();
                    p.dispose();
                }catch(Throwable t){
                    line("  sprite " + sprite + ": " + t);
                }
            }
        }
    }

    /**
     * unitcolors=true: the colour each unit's body sprite is marked with. Pixels that are clearly coloured and bright
     * are sorted by hue into twelve bins; the orange, red and yellow of the team cell and of warning stripes (hue below
     * 50 degrees or above 345) do not count. The fullest bin wins if it holds at least 1.2% of the sprite; the line
     * gives the colour used most in it. A unit without such a colour is listed with a dash.
     */
    /**
     * spritefiles=true: every sprite of the game's own buildings and units as a picture file of its own, into
     * <out>/sprites/blocks and <out>/sprites/units - to count the colours the game paints with (run it without the
     * shader mod, which replaces sprites). Left out: copies and masks (icons, outlines, team pieces, heat and glow).
     */
    String pendingSprites;

    /** spritefb=NAME+NAME..: each sprite drawn alone into a picture of its own size and saved (sprites/NAME.png), as the game has it. */
    void spriteFb(String names){
        for(String name : names.split("[+]")){
            var region = Core.atlas.find(name);
            if(!region.found()){
                line("  sprite " + name + ": not in atlas");
                continue;
            }
            int w = region.width, h = region.height;
            var fb = new arc.graphics.gl.FrameBuffer(w, h);
            var before = new arc.math.Mat(Draw.proj());
            Draw.flush();
            fb.begin(Color.clear);
            Draw.proj(new arc.math.Mat().setOrtho(0f, 0f, w, h));
            Draw.blend(Blending.disabled);
            Draw.color();
            Draw.rect(region, w / 2f, h / 2f, w, h);
            Draw.flush();
            byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
            fb.end();
            Draw.blend();
            Draw.proj(before);
            fb.dispose();
            Pixmap pm = new Pixmap(w, h);
            Buffers.copy(px, 0, pm.pixels, px.length);
            PixmapIO.writePng(new Fi(outDir).child("sprites").child(name + ".png"), pm);
            pm.dispose();
            line("  sprite " + name + " " + w + "x" + h);
        }
    }

    void spriteFiles(){
        String[] skip = {"-heat", "-glow", "-light", "-shadow", "-full", "-ui", "-icon", "-preview", "-outline", "-liquid", "-blur", "-invert"};
        Seq<String> blocks = new Seq<>(), units = new Seq<>();
        for(mindustry.world.Block block : Vars.content.blocks()) if(block.minfo.mod == null && block.synthetic()) blocks.add(block.name);
        for(mindustry.type.UnitType type : Vars.content.units()){
            if(type.minfo.mod != null) continue;
            units.add(type.name);
            for(var weapon : type.weapons) if(weapon.name != null && !weapon.name.isEmpty() && !units.contains(weapon.name)) units.add(weapon.name);
        }
        int[] wrote = new int[2];
        ObjectSet<String> done = new ObjectSet<>();
        for(TextureAtlas.AtlasRegion region : Core.atlas.getRegions()){
            String name = region.name;
            if(name == null || !done.add(name) || name.contains("-team") || name.contains("-cell")) continue;
            boolean out = false;
            for(String end : skip) out |= name.endsWith(end);
            if(out) continue;
            int kind = -1;
            if(name.matches("block-[0-9]+")) kind = 0;
            for(String block : blocks) if(kind < 0 && (name.equals(block) || name.startsWith(block + "-"))) kind = 0;
            for(String unit : units) if(kind < 0 && (name.equals(unit) || name.startsWith(unit + "-"))) kind = 1;
            if(kind < 0) continue;
            try{
                Pixmap p = Core.atlas.getPixmap(region).crop();
                PixmapIO.writePng(new Fi(outDir).child("sprites").child(kind == 0 ? "blocks" : "units").child(name + ".png"), p);
                p.dispose();
                wrote[kind]++;
            }catch(Throwable t){
                line("  sprite " + name + ": " + t);
            }
        }
        line("  sprite files: " + wrote[0] + " of blocks, " + wrote[1] + " of units");
    }

    void unitColors(){
        for(mindustry.type.UnitType type : Vars.content.units()){
            if(type.minfo.mod != null || !type.region.found()) continue;
            try{
                Pixmap p = Core.atlas.getPixmap(type.region).crop();
                int opaque = 0;
                int[] count = new int[12];
                @SuppressWarnings("unchecked")
                ObjectIntMap<Integer>[] used = new ObjectIntMap[12];
                for(int i = 0; i < 12; i++) used[i] = new ObjectIntMap<>();
                float[] hsb = new float[3];
                for(int x = 0; x < p.width; x++){
                    for(int y = 0; y < p.height; y++){
                        int c = p.get(x, y);
                        if((c & 0xff) < 250) continue;
                        opaque++;
                        java.awt.Color.RGBtoHSB(c >>> 24, c >>> 16 & 0xff, c >>> 8 & 0xff, hsb);
                        float hue = hsb[0] * 360f;
                        if(hsb[1] < 0.3f || hsb[2] < 0.5f || hue < 50f || hue > 345f) continue;
                        int bin = Math.min((int)(hue / 30f), 11);
                        count[bin]++;
                        used[bin].increment(c, 0, 1);
                    }
                }
                p.dispose();
                int win = 0;
                for(int i = 1; i < 12; i++) if(count[i] > count[win]) win = i;
                if(count[win] < Math.max(12, opaque * 0.012f)){
                    line("unitcolor " + type.name + " = -");
                    continue;
                }
                int most = 0, mostCount = -1;
                for(var e : used[win].entries()){
                    if(e.value > mostCount){
                        mostCount = e.value;
                        most = e.key;
                    }
                }
                line("unitcolor " + type.name + " = " + String.format("%06x", most >>> 8) + "   # " + count[win] + " of " + opaque + " pixels, hue " + win * 30 + ".." + (win * 30 + 30));
            }catch(Throwable t){
                line("unitcolor " + type.name + ": " + t);
            }
        }
    }

    /**
     * onwalls=true: the nearest place to the row of units where natural walls fill everything two tiles around and open
     * ground lies within four tiles (so the walls are not yet in the black of deep rock); the row is moved there.
     */
    void ontoWalls(){
        if(crowd.isEmpty()) return;
        int cx = (int)(crowdAt.get(0) / Vars.tilesize), cy = (int)(crowdAt.get(1) / Vars.tilesize);
        int best = -1;
        float bestDst = Float.MAX_VALUE;
        for(int x = 3; x < Vars.world.width() - 3; x++){
            for(int y = 3; y < Vars.world.height() - 3; y++){
                float dst = Mathf.dst2(x, y, cx, cy);
                if(dst >= bestDst) continue;
                boolean walls = true, open = false;
                for(int dx = -4; dx <= 4 && walls; dx++){
                    for(int dy = -4; dy <= 4; dy++){
                        var tile = Vars.world.tile(x + dx, y + dy);
                        if(tile == null) continue;
                        boolean rock = tile.block().isStatic();
                        if(Math.abs(dx) <= 2 && Math.abs(dy) <= 2 && !rock){
                            walls = false;
                            break;
                        }
                        if(!rock && tile.build == null && !tile.floor().isLiquid) open = true;
                    }
                }
                if(walls && open){
                    best = x + y * Vars.world.width();
                    bestDst = dst;
                }
            }
        }
        if(best < 0){
            line("  no natural walls found");
            return;
        }
        float wx = (best % Vars.world.width()) * Vars.tilesize, wy = (best / Vars.world.width()) * Vars.tilesize;
        float shiftX = wx - crowdAt.get(0), shiftY = wy - crowdAt.get(1);
        for(int c = 0; c < crowd.size; c++){
            crowdAt.set(c * 2, crowdAt.get(c * 2) + shiftX);
            crowdAt.set(c * 2 + 1, crowdAt.get(c * 2 + 1) + shiftY);
        }
        camX = wx;
        camY = wy;
        camSet = true;
        line("  row moved onto natural walls at tile " + best % Vars.world.width() + "," + best / Vars.world.width());
    }

    /** crowd=a+b+c: one unit of each of those kinds in a row just below the first test lamp (after blocks=true and the like), held there; the camera moves to them. */
    void spawnCrowd(String names){
        if(lampPos == null) return;
        String[] list = names.split("[+]");
        for(int i = 0; i < list.length; i++){
            var type = Vars.content.unit(list[i]);
            if(type == null){
                line("  no such unit: " + list[i]);
                continue;
            }
            float x = lampPos[0] + (i - (list.length - 1) / 2f) * 44f, y = lampPos[1] - crowdBelow;
            crowd.add(type.spawn(Vars.player.team(), x, y));
            crowdAt.add(x, y);
        }
        camX = lampPos[0];
        camY = lampPos[1] - crowdBelow * 0.7f;
        line("  crowd of " + crowd.size + " below the lamp at " + lampPos[0] + "," + lampPos[1]);
    }

    /**
     * weaponlist=true: every unit of the game with its weapons, and for each what colour the shot is drawn in and what
     * colour the game gives its light - to find the ones where the two do not go together. To be run without the shader
     * mod (it gives the bullet types Color objects of their own, and "default" is told here by the object).
     */
    void listWeapons(){
        for(mindustry.type.UnitType type : Vars.content.units()){
            if(type.internal || type.minfo.mod != null) continue;
            line("unit " + type.name + (type.weapons.size == 0 ? " (no weapons)" : ""));
            for(mindustry.type.Weapon weapon : type.weapons){
                String extra = weapon instanceof mindustry.type.weapons.RepairBeamWeapon r ? " beam=" + hex(r.laserColor)
                    : weapon instanceof mindustry.type.weapons.PointDefenseWeapon p ? " beam=" + hex(p.color) : "";
                line("  weapon " + kind(weapon) + " " + weapon.name + extra);
                bulletLine(weapon.bullet, 2);
            }
        }
    }

    void bulletLine(mindustry.entities.bullet.BulletType b, int depth){
        if(b == null || depth > 4) return;
        String own = b instanceof mindustry.entities.bullet.ContinuousLaserBulletType l ? "colors=" + hexes(l.colors)
            : b instanceof mindustry.entities.bullet.ContinuousFlameBulletType f ? "colors=" + hexes(f.colors)
            : b instanceof mindustry.entities.bullet.LaserBulletType l ? "colors=" + hexes(l.colors) + " (its light takes colors[0])"
            : b instanceof mindustry.entities.bullet.ShrapnelBulletType s ? "from=" + hex(s.fromColor) + " to=" + hex(s.toColor)
            : b instanceof mindustry.entities.bullet.SapBulletType s ? "color=" + hex(s.color) + " (drawn with Drawf.laser)"
            : b instanceof mindustry.entities.bullet.PointLaserBulletType p ? "color=" + hex(p.color)
            : b instanceof mindustry.entities.bullet.LightningBulletType ? "lightning=" + hex(b.lightningColor)
            : b instanceof mindustry.entities.bullet.LiquidBulletType q ? "liquid=" + (q.liquid == null ? "-" : q.liquid.name)
            : b instanceof mindustry.entities.bullet.BasicBulletType a ? "back=" + hex(a.backColor) + " front=" + hex(a.frontColor)
            : "hit=" + hex(b.hitColor) + " trail=" + hex(b.trailColor);
        String given = b.lightColor == mindustry.graphics.Pal.powerLight ? "DEFAULT-yellow" : b.lightColor == Color.orange ? "DEFAULT-orange" : b.lightColor == Color.white ? "DEFAULT-white" : "set";
        line("  ".repeat(depth) + "bullet " + kind(b) + " light=" + hex(b.lightColor) + " " + given + " r=" + (int)b.lightRadius + " a=" + b.lightOpacity + " | " + own
            + (b.spawnUnit != null ? " spawns=" + b.spawnUnit.name : ""));
        if(b.fragBullet != null && b.fragBullet != b) bulletLine(b.fragBullet, depth + 1);
        if(b.intervalBullet != null && b.intervalBullet != b) bulletLine(b.intervalBullet, depth + 1);
    }

    static String kind(Object o){
        Class<?> c = o.getClass();
        while(c.isAnonymousClass()) c = c.getSuperclass();
        return c.getSimpleName();
    }

    static String hex(Color c){
        return c == null ? "-" : "#" + c.toString();
    }

    static String hexes(Color[] colors){
        StringBuilder sb = new StringBuilder();
        for(Color c : colors) sb.append(hex(c)).append(' ');
        return sb.toString().trim();
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
