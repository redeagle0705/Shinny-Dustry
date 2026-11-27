package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.GameState.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.maps.*;

/**
 * A real world behind the main menu, drawn by the game's own renderer - so every effect of the mod shows there (the
 * game's menu background is a picture of its own, MenuRenderer, that no effect reaches: "make the main menu load a
 * real map"), in scenes as Factorio's menu has them - still, a moment of a base held, with its weather and its time of
 * day, the camera going over it ("scenery").
 *
 * A scene is a game saved paused (menu-scenes/<name>.msav in the mod, made by the maker) with its weather, time of day
 * and the camera's way over it (Scene). In the menu it is loaded (SaveIO.load - the state stays "menu": nothing
 * updates, no HUD) and the menu's background (MenuFragment.renderer) is replaced by one that draws the world with
 * Renderer.draw. The effects of the mod take it for a game (ShaderMod.inWorld). Starting a game resets the world as
 * always; back in the menu the scene is loaded again. Without a scene a built-in map; off (setting), or if anything
 * fails, the game's own background.
 */
class MenuWorld{
    /** One scene: its save, the time of day (0 .. 100 from midnight, -1 = the map's own), a weather, the camera's way. */
    static class Scene{
        final String file, weather;
        float daytime;
        /** Tiles: where the camera holds first, and where it ends; zoom (screen pixels per world unit) at each end. */
        float fromX, fromY, toX, toY, fromZoom, toZoom;
        /** Seconds: held at the start, moving, held at the end. */
        float hold, move, rest;
        /** No way given: worked out from the base when it is loaded (autoPath). */
        boolean auto;

        /** The map's own darkness (the lighting rule, a campaign night as it was saved) taken away: the time of day lights it. */
        boolean clearDark;

        /** A scene with the save's own weather and light, the camera going between the two busiest parts of the base. */
        Scene(String file){
            this(file, -1f, null, 0f, 0f, 3.5f, 0f, 0f, 3.5f, 4f, 12f, 4f);
            auto = true;
        }

        /** Goes on living instead of held still: the factories work, the belts run, the units go about (live()). */
        boolean live;

        Scene live(){
            live = true;
            return this;
        }

        /** At that time of day instead of the save's own darkness ("the places too dark: put dusk in, while at it"). */
        Scene at(float daytime){
            this.daytime = daytime;
            clearDark = true;
            return this;
        }
        /** Seconds the scene runs on after it is loaded before it is held (shots in the air: a save keeps none). */
        float warm = 2f;

        Scene(String file, float daytime, String weather, float fromX, float fromY, float fromZoom, float toX, float toY, float toZoom, float hold, float move, float rest){
            this.file = file;
            this.daytime = daytime;
            this.weather = weather;
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromZoom = fromZoom;
            this.toX = toX;
            this.toY = toY;
            this.toZoom = toZoom;
            this.hold = hold;
            this.move = move;
            this.rest = rest;
        }

        float length(){
            // (test kit: a short round, to see the next scene come in)
            int test = Core.settings.getInt("mshaders-test-menuround", -1);
            if(test > 0) return test;
            return hold + move + rest;
        }
    }

    /** The scenes, in turn (the maker's: "night, rain; the camera stays at 291,244, then zooms out slowly towards 405,105"). */
    static final Scene[] scenes = {
        // ("129,184 -> 129,55, the zoom kept"): the save's own weather and light
        new Scene("groundzero", -1f, null, 129f, 184f, 3.5f, 129f, 55f, 3.5f, 4f, 14f, 4f).live(),
        // the maker's own campaign sectors ("can you just take a few of the campaigns in my game? they can't be
        // exported"): copied, written again with no sector (TestKit resave), the way found from the base
        // (the three saved in a campaign night, at dusk: the sun just over the horizon, warm and low - Daytime, 73 of 100)
        // (every scene lives - the battles too: each is loaded anew from its save when its round is over, so nothing runs off;
        // the maker: "if it is reset anyway, the fighting places don't matter either, do they?")
        new Scene("serpulo-97").at(73f).live(),
        new Scene("serpulo-223").at(73f).live(),
        // ("the craters: fixed at 124,144, from the closest zoom slowly out" - -1 = as close as the game lets one zoom)
        new Scene("serpulo-219", 73f, null, 124f, 144f, -1f, 124f, 144f, 2.5f, 2f, 32f, 4f).at(73f).live(),
        new Scene("serpulo-41").live(),
        new Scene("engage", 0f, "rain", 291f, 244f, 4.5f, 405f, 105f, 1.6f, 5f, 28f, 6f).live()
    };
    /** Without a scene: a built-in map. */
    static final String mapName = "archipelago";

    private static boolean loaded, failed;
    private static Scene scene;
    /** The round of the scene shown is over and the next is being loaded: black meanwhile. */
    private static boolean switching;
    private static int sceneIndex = -1;
    private static float startTime;
    /** Whether the loaded scene has been drawn yet: its time starts at the first frame drawn, not at the load. */
    private static boolean started;
    /** The game's clock as the scene was held: every frame of the scene is drawn at it (see Background.render). */
    private static float heldTime, heldGlobal;

    static boolean on(){
        return Core.settings.getBool(ShaderMod.menuWorldOn, true) && !failed;
    }

    /** The mod's own loads (a scene, a preview's little world), which reset the world - and say so (StateChangeEvent). */
    private static boolean selfLoading;

    /**
     * The world is someone else's now: the map editor's (open, or playing a map from it), a server's being joined.
     * Nothing is loaded over it then - a scene loaded over the editor's map would be edited and saved in its place.
     */
    static boolean busy(){
        return (Vars.ui != null && Vars.ui.editor != null && Vars.ui.editor.isShown()) || Vars.state.rules.editor
            || (Vars.net != null && (Vars.net.client() || Vars.net.active()));
    }

    /** Is the scene behind the menu shown now. */
    static boolean showing(){
        return loaded && on() && Vars.state.isMenu() && !busy();
    }

    /** The scene's time of day, 0 .. 100 from midnight; -1 for none. */
    static float daytime(){
        if(PreviewWorld.current != null) return PreviewWorld.current.daytime;
        return scene == null ? -1f : scene.daytime;
    }

    /**
     * The little world of a settings preview (PreviewWorld) that shows that setting, made in place of the scene if it
     * is not the one loaded already. Only in the menu, and only with the scenes behind the menu on (the world there is
     * the mod's own then). False if there is none to show.
     */
    static boolean preview(String key){
        if(!on() || !Vars.state.isMenu() || busy()) return false;
        PreviewWorld.Group g = PreviewWorld.of(key);
        if(PreviewWorld.current == g && loaded) return true;
        selfLoading = true;
        boolean made;
        try{
            made = PreviewWorld.load(g);
        }finally{
            selfLoading = false;
        }
        if(!made){
            // (the load wipes the world before it can fail: what was loaded is gone - the scene is asked for again)
            scene = null;
            snowy = false;
            loaded = false;
            ask();
            return false;
        }
        scene = null;
        snowy = g.snow;
        loaded = true;
        started = false;
        switching = false;
        heldTime = Time.time;
        heldGlobal = Time.globalTime;
        forgetView();
        return true;
    }

    private static boolean quieted;

    /** Silences the sounds and the music (a settings preview is shown), or lets them be heard again. */
    static void quiet(boolean q){
        if(!q && !quieted) return;
        try{
            if(Core.audio.soundBus != null) Core.audio.soundBus.setVolume(q ? 0f : 1f);
            if(Core.audio.musicBus != null) Core.audio.musicBus.setVolume(q ? 0f : 1f);
            quieted = q;
        }catch(Throwable t){
            Log.err("[mshaders] the preview cannot silence the sounds", t);
        }
    }

    /** The settings are closed: the scene comes back (the next out of the bag). */
    static void endPreview(){
        quiet(false);
        PreviewWorld.clearCursor();
        if(PreviewWorld.current == null) return;
        PreviewWorld.current = null;
        loaded = false;
        if(Vars.state.isMenu()) ask();
    }

    static void init(){
        try{
            var field = mindustry.ui.fragments.MenuFragment.class.getDeclaredField("renderer");
            field.setAccessible(true);
            Object before = field.get(Vars.ui.menufrag);
            field.set(Vars.ui.menufrag, new Background());
            if(before instanceof Disposable d) d.dispose();
        }catch(Throwable t){
            failed = true;
            Log.err("[mshaders] the menu keeps the game's own background", t);
            return;
        }
        // into the menu (at the start, and back from a game): the scene, a frame later (after the game has reset)
        Events.on(StateChangeEvent.class, e -> {
            if(e.to == State.menu){
                // (the world was reset while in the menu - leaving the map editor, connecting to a server: not by
                // the mod's own loads - so what was loaded is gone, and is asked for again)
                if(!selfLoading){
                    loaded = false;
                    PreviewWorld.current = null;
                    PreviewWorld.clearCursor();
                }
                Core.app.post(MenuWorld::load);
            }else{
                loaded = false;
                PreviewWorld.current = null;
                quiet(false);
                // (a preview's cursor and the block it pretended to place: never carried into a game)
                PreviewWorld.clearCursor();
            }
        });
        // a scene that stopped living: its loops stopped (see stopLoopsIfDone)
        Events.run(mindustry.game.EventType.Trigger.update, () -> {
            stopLoopsIfDone();
            // and the sound comes back as soon as no preview is shown (whatever ended it)
            if(PreviewWorld.current == null || !Vars.state.isMenu()) quiet(false);
        });
        // (not now, while the game is still starting: the first scene, loaded then, came out wrong - "the first one is
        // odd, from the second on it's fine"; it is loaded when the menu's background is first drawn - Background)
    }

    /** Asked by the background once it is drawn and nothing is loaded: the scene, in the next frame. */
    private static boolean asked;

    private static void ask(){
        if(asked || loaded || !on() || busy()) return;
        asked = true;
        Core.app.post(() -> {
            asked = false;
            load();
        });
    }

    private static Fi sceneFile(Scene s){
        var mod = Vars.mods.getMod(ShaderMod.class);
        if(mod == null || mod.root == null) return null;
        Fi f = mod.root.child("menu-scenes").child(s.file + ".msav");
        return f.exists() ? f : null;
    }

    private static final IntSeq bag = new IntSeq();

    /** The next scene out of the bag; a new shuffled bag when it is empty (not starting with the one just shown). */
    private static int nextFromBag(){
        // (the bag is kept in the settings: the menu is mostly seen once per start of the game, and a bag that began
        // anew each start was a coin toss each time - with the game's own random numbers, seeded the same way at every
        // start, the same side every time: "why does only 27 come again and again?")
        if(bag.isEmpty()){
            String kept = Core.settings.getString(bagKey, "");
            for(String s : kept.split(",")){
                if(s.isEmpty()) continue;
                try{
                    int k = Integer.parseInt(s);
                    if(k >= 0 && k < scenes.length && !bag.contains(k)) bag.add(k);
                }catch(NumberFormatException ignored){}
            }
            int last = Core.settings.getInt(bagKey + "-last", sceneIndex);
            if(sceneIndex < 0) sceneIndex = last;
        }
        if(bag.isEmpty()){
            for(int i = 0; i < scenes.length; i++) bag.add(i);
            var random = new java.util.Random();
            for(int i = bag.size - 1; i > 0; i--) bag.swap(i, random.nextInt(i + 1));
            // (the last of one bag and the first of the next could be the same: the same scene twice in a row)
            if(bag.size > 1 && bag.peek() == sceneIndex) bag.swap(bag.size - 1, 0);
        }
        int k = bag.pop();
        StringBuilder rest = new StringBuilder();
        for(int i = 0; i < bag.size; i++) rest.append(i > 0 ? "," : "").append(bag.get(i));
        Core.settings.put(bagKey, rest.toString());
        Core.settings.put(bagKey + "-last", k);
        return k;
    }

    // (by how many scenes there are: a bag kept from another list of scenes would name the wrong ones)
    private static final String bagKey = "mshaders-menu-bag-" + scenes.length;

    /**
     * The scene is held, but a conveyor's moving surface is drawn by the clock (Conveyor.ConveyorBuild.draw: the frame
     * from Time.time, which goes on in the menu - the rain needs it), so the belts ran under items that stood still
     * ("the items stopped, but the conveyors look as if they did not"). A building's own time scale stands in for its
     * speed there; nothing updates the buildings in the menu to set it back.
     */
    private static void holdBelts(){
        for(var b : mindustry.gen.Groups.build){
            if(b instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild belt) belt.applySlowdown(0f, 1e9f);
        }
    }

    /** Whether the scene's map has snow on its ground: then snow lies on the buildings as well (Skies, SnowCover). */
    private static boolean snowy;

    /** Snow lying on the ground and the buildings of the scene behind the menu, 0 .. 1 (0 = none, or no scene). */
    static float snowLying(){
        return showing() && (PreviewWorld.current != null ? PreviewWorld.current.snow : snowy) ? 1f : 0f;
    }

    /**
     * Whether a fair part of the map's open ground is snow ("a map with snow floor in it: let snow settle there too").
     * Some 3 % of the open ground: a few snowy patches on a desert do not make a winter.
     */
    private static boolean snowFloor(){
        var snow = mindustry.content.Blocks.snow;
        var iceSnow = mindustry.content.Blocks.iceSnow;
        int open = 0, white = 0;
        for(var tile : Vars.world.tiles){
            if(tile.block().solid && tile.block() instanceof mindustry.world.blocks.environment.StaticWall) continue;
            open++;
            if(tile.floor() == snow || tile.floor() == iceSnow) white++;
        }
        boolean yes = open > 0 && white >= open * 0.03f;
        Log.info("[mshaders] menu scene: snow on @ of @ open tiles@", white, open, yes ? " - snow settles" : "");
        return yes;
    }

    /**
     * A way for the camera over a base nobody chose one for: from the busiest part of the player's base (the most
     * building in a square about a screen wide) to the busiest part some way off from it, at a steady speed.
     */
    private static void autoPath(Scene s){
        int w = Vars.world.width(), h = Vars.world.height(), cell = 4;
        int cw = (w + cell - 1) / cell, ch = (h + cell - 1) / cell;
        float[] mass = new float[cw * ch];
        var team = Vars.state.rules.defaultTeam;
        for(var b : mindustry.gen.Groups.build){
            if(b.team != team) continue;
            int cx = Math.min(b.tileX() / cell, cw - 1), cy = Math.min(b.tileY() / cell, ch - 1);
            mass[cx + cy * cw] += b.block.size * b.block.size;
        }
        // the building in a square of 5 x 5 cells (20 tiles) round each cell
        float[] sum = new float[cw * ch];
        for(int y = 0; y < ch; y++){
            for(int x = 0; x < cw; x++){
                float t = 0f;
                for(int dy = -2; dy <= 2; dy++){
                    for(int dx = -2; dx <= 2; dx++){
                        int xx = x + dx, yy = y + dy;
                        if(xx >= 0 && yy >= 0 && xx < cw && yy < ch) t += mass[xx + yy * cw];
                    }
                }
                sum[x + y * cw] = t;
            }
        }
        int a = 0;
        for(int i = 1; i < sum.length; i++) if(sum[i] > sum[a]) a = i;
        float ax = (a % cw) * cell + cell / 2f, ay = (a / cw) * cell + cell / 2f;
        // the far end: well away (some 60 tiles, less on a small base), as busy as can be
        int b = -1;
        for(float need = 60f; need >= 15f && b < 0; need -= 15f){
            for(int i = 0; i < sum.length; i++){
                if(sum[i] < sum[a] * 0.25f) continue;
                float x = (i % cw) * cell + cell / 2f, y = (i / cw) * cell + cell / 2f;
                if(Mathf.dst(x, y, ax, ay) < need) continue;
                if(b < 0 || sum[i] > sum[b]) b = i;
            }
        }
        float bx = b < 0 ? ax : (b % cw) * cell + cell / 2f, by = b < 0 ? ay : (b / cw) * cell + cell / 2f;
        s.fromX = ax;
        s.fromY = ay;
        s.toX = bx;
        s.toY = by;
        // as fast as the maker found right on the engage scene (about 9 tiles a second, eased)
        s.move = Math.max(Mathf.dst(ax, ay, bx, by) / 9f, 6f);
        Log.info("[mshaders] menu scene @: camera from @,@ to @,@ in @ s", s.file, (int)ax, (int)ay, (int)bx, (int)by, Strings.fixed(s.move, 1));
    }

    private static void load(){
        if(!on() || !Vars.state.isMenu() || loaded || busy()) return;
        try{
            // the next scene that has its save; else the built-in map
            // (drawn as Tetris draws its pieces, a bag at a time: every scene once in a shuffled order, then a new bag -
            // by chance alone, the same one could come again and again; the maker: "random, but as a bag, like 7-bag")
            scene = null;
            snowy = false;
            PreviewWorld.current = null;
            int forced = Core.settings.getInt("mshaders-test-menuscene", -1);
            for(int i = 0; i < scenes.length * 2 && scene == null; i++){
                int k = forced >= 0 && forced < scenes.length && i == 0 ? forced : nextFromBag();
                if(sceneFile(scenes[k]) != null){
                    sceneIndex = k;
                    scene = scenes[k];
                }
            }
            if(scene != null){
                selfLoading = true;
                try{
                    SaveIO.load(sceneFile(scene));
                }finally{
                    selfLoading = false;
                }
                // (a scene is never a sector of the campaign: taken for one, the game would keep its state as that
                // sector's - the saves are written without, this is only in case)
                if(Vars.state.rules.sector != null){
                    Log.warn("[mshaders] menu scene @ was saved as a sector: taken as a plain map", scene.file);
                    Vars.state.rules.sector = null;
                }
                if(scene.auto) autoPath(scene);
                if(scene.clearDark) Vars.state.rules.lighting = false;
                snowy = snowFloor();
                // no fog of war: nothing runs here to see through it, and the whole base was hidden ("all the
                // buildings are gone")
                Vars.state.rules.fog = false;
                Vars.state.rules.staticFog = false;
                if(scene.weather != null){
                    var weather = Vars.content.getByName(mindustry.ctype.ContentType.weather, scene.weather);
                    if(weather instanceof mindustry.type.Weather w){
                        var state = w.create(1f, 60f * 60f * 60f);
                        state.opacity(1f);
                    }
                }
                if(Core.settings.getBool("mshaders-test-menulog", false)){
                    int near = 0, far = 0;
                    for(var b : mindustry.gen.Groups.build){
                        if(Mathf.dst(b.tileX(), b.tileY(), scene.fromX, scene.fromY) < 25f) near++;
                        if(Mathf.dst(b.tileX(), b.tileY(), scene.toX, scene.toY) < 25f) far++;
                    }
                    Log.info("[mshaders] menu scene: map @x@, buildings within 25 tiles of the start @, of the end @, player team @", Vars.world.width(), Vars.world.height(), near, far, Vars.player.team());
                }
                int testWarm = Core.settings.getInt("mshaders-test-menuwarm", -1);
                warm(testWarm >= 0 ? testWarm / 10f : scene.warm);
                if(!scene.live) holdBelts();
            }else{
                Map map = Vars.maps.all().find(m -> m.file.nameWithoutExtension().equalsIgnoreCase(mapName));
                if(map == null){
                    Log.warn("[mshaders] menu map @ not found", mapName);
                    failed = true;
                    return;
                }
                Vars.world.loadMap(map);
            }
            // (loading puts the game in no other state; make sure it is still the menu)
            if(!Vars.state.isMenu()) Vars.state.set(State.menu);
            forgetView();
            loaded = true;
            started = false;
            switching = false;
            heldTime = Time.time;
            heldGlobal = Time.globalTime;
            startTime = Time.globalTime;
            Log.info("[mshaders] menu scene loaded: @", scene != null ? scene.file : mapName);
        }catch(Throwable t){
            failed = true;
            loaded = false;
            Log.err("[mshaders] the menu scene cannot be loaded, the game's own background stays", t);
        }
    }

    /**
     * Lets the scene run that many seconds before it is shown, then holds it: a save keeps no shots in the air (only
     * buildings, units and the weather), and a battle saved paused came back with every bullet gone ("I wanted the
     * shots held where they were fired - they're all gone"). Only the entities are updated (Logic.updateEntities: units,
     * buildings, bullets, effects - no waves, no end of the game, no AI building), all in this one frame, with the game
     * taken for running meanwhile (the state set behind the events' back, so nothing else sees a game start) and the
     * sound off.
     */
    private static java.lang.reflect.Field liveState;
    private static java.lang.reflect.Method liveUpdate, liveLoops;
    private static boolean liveFailed;

    /**
     * One frame of a living scene ("the peaceful ones: let them move"): the entities updated as the warm-up does it -
     * units, buildings, shots, effects; no waves, no AI building, no end of the game - with the game taken for running
     * meanwhile. Its sounds are heard, as in a game ("let them be heard: handy for checking the effect volumes too");
     * only the warm-up, two seconds run in one frame, is silent. The game's clock goes on by itself here.
     */
    private static void live(){
        if(liveFailed) return;
        Object was = null;
        try{
            if(liveUpdate == null){
                liveState = mindustry.core.GameState.class.getDeclaredField("state");
                liveState.setAccessible(true);
                liveUpdate = mindustry.core.Logic.class.getDeclaredMethod("updateEntities");
                liveUpdate.setAccessible(true);
            }
            was = liveState.get(Vars.state);
            liveState.set(Vars.state, State.playing);
            // the loops playing (as the last frame left them): the game empties its list of them each frame outside
            // a game, and a loop it no longer knows plays on and is started again - one more each frame
            var playing = loopMap();
            if(playing != null){
                playing.clear();
                playing.putAll(loops);
            }
            Vars.state.teams.updateTeamStats();
            liveUpdate.invoke(Vars.logic);
            // the sounds that go on - the weather (rain, wind), drills, factories, generators: the entities ask for
            // them each frame (SoundControl.loop), and the game plays them only in a game ("can the menu have the
            // ambient sounds too?")
            if(liveLoops == null){
                liveLoops = mindustry.audio.SoundControl.class.getDeclaredMethod("updateLoops");
                liveLoops.setAccessible(true);
            }
            if(Vars.control != null && Vars.control.sound != null) liveLoops.invoke(Vars.control.sound);
            if(playing != null){
                loops.clear();
                loops.putAll(playing);
                loopsFrame = Core.graphics.getFrameId();
            }
        }catch(Throwable t){
            liveFailed = true;
            Log.err("[mshaders] the menu scene cannot go on living: held still from here", t);
        }finally{
            try{
                if(was != null) liveState.set(Vars.state, was);
            }catch(Throwable ignored){
            }
        }
    }

    /**
     * After a drawing of the world that threw half way: what the game's drawing had switched on is switched off again
     * (the sorting of what is drawn, a shader) and the mod's buffer is closed - else the menu itself would be drawn into
     * it, unseen, for the rest of the session.
     */
    private static void recover(){
        try{
            Draw.sort(false);
            Draw.shader();
            Draw.color();
            if(mshaders.Scene.active != null) mshaders.Scene.active.abort();
        }catch(Throwable t){
            Log.err("[mshaders] the menu could not tidy up after a failed drawing", t);
        }
    }

    /** The loops of the living scene (SoundControl's own list, kept from frame to frame), and the frame they were last heard. */
    private static final arc.struct.ObjectMap<Object, Object> loops = new arc.struct.ObjectMap<>();
    private static long loopsFrame = -1;
    private static java.lang.reflect.Field loopsField;
    private static boolean loopsFailed;

    @SuppressWarnings("unchecked")
    private static arc.struct.ObjectMap<Object, Object> loopMap(){
        if(loopsFailed || Vars.control == null || Vars.control.sound == null) return null;
        try{
            if(loopsField == null){
                loopsField = mindustry.audio.SoundControl.class.getDeclaredField("sounds");
                loopsField.setAccessible(true);
            }
            return (arc.struct.ObjectMap<Object, Object>)loopsField.get(Vars.control.sound);
        }catch(Throwable t){
            loopsFailed = true;
            Log.err("[mshaders] the menu scene's loops cannot be kept", t);
            return null;
        }
    }

    /**
     * Each frame: when the scene has stopped living (switched off, a game started, the editor opened, the world
     * failed), its loops are stopped - nothing else would: the game no longer knows them.
     */
    private static void stopLoopsIfDone(){
        if(loops.size == 0 || Core.graphics.getFrameId() - loopsFrame <= 2) return;
        for(Object sound : loops.keys()){
            if(sound instanceof arc.audio.Sound s) s.stop();
        }
        loops.clear();
    }

    private static void warm(float seconds){
        if(seconds <= 0f) return;
        java.lang.reflect.Field stateField = null;
        Object was = null;
        float delta = Time.delta, sound = Core.audio.sfxVolume;
        try{
            stateField = mindustry.core.GameState.class.getDeclaredField("state");
            stateField.setAccessible(true);
            was = stateField.get(Vars.state);
            var update = mindustry.core.Logic.class.getDeclaredMethod("updateEntities");
            update.setAccessible(true);
            stateField.set(Vars.state, State.playing);
            Core.audio.sfxVolume = 0f;
            Time.delta = 1f;
            int b0 = mindustry.gen.Groups.build.size(), u0 = mindustry.gen.Groups.unit.size();
            // (the game's clock goes on too: turrets look for a target on timers that read it - with the clock held
            // none ever aimed, and no shot was fired)
            for(int i = 0, n = (int)(seconds * 60f); i < n; i++){
                Time.time += 1f;
                // (each team's lists of units, which the turrets pick their targets from: with them empty no turret
                // ever aimed)
                Vars.state.teams.updateTeamStats();
                update.invoke(Vars.logic);
            }
            if(Core.settings.getBool("mshaders-test-menulog", false)){
            int turrets = 0, ammo = 0, aimed = 0, enabled = 0, powered = 0;
            for(var b : mindustry.gen.Groups.build){
                if(b instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t){
                    turrets++;
                    if(t.hasAmmo()) ammo++;
                    if(t.target != null) aimed++;
                    if(t.enabled) enabled++;
                    if(t.efficiency > 0f) powered++;
                }
            }
            Log.info("[mshaders] menu scene turrets @: with ammo @, aimed @, enabled @, working @; teams: @", turrets, ammo, aimed, enabled, powered, Vars.state.teams.getActive().map(d -> d.team.name + ":" + d.units.size));
            Log.info("[mshaders] menu scene ran @ s: buildings @ -> @, units @ -> @, shots @, fog @ static @", seconds, b0, mindustry.gen.Groups.build.size(), u0, mindustry.gen.Groups.unit.size(), mindustry.gen.Groups.bullet.size(), Vars.state.rules.fog, Vars.state.rules.staticFog);
            }
        }catch(Throwable t){
            Log.err("[mshaders] the menu scene could not run on: shown as saved", t);
        }finally{
            Time.delta = delta;
            Core.audio.sfxVolume = sound;
            try{
                if(stateField != null && was != null) stateField.set(Vars.state, was);
            }catch(Throwable ignored){
            }
        }
    }

    /**
     * Makes the game's block drawing work out anew which blocks are in view. It does so only when the camera has moved
     * to other tiles (BlockRenderer.processBlocks: lastCamX/Y, lastRangeX/Y) - and the camera holding still where a
     * scene starts kept the list of the world before: the first time round the scene showed none of its buildings ("the
     * first one is odd, from the second on it's fine").
     */
    private static void forgetView(){
        try{
            for(String name : new String[]{"lastCamX", "lastCamY", "lastRangeX", "lastRangeY"}){
                var f = BlockRenderer.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(Vars.renderer.blocks, Integer.MIN_VALUE);
            }
        }catch(Throwable t){
            Log.err("[mshaders] the blocks in view could not be worked out anew", t);
        }
    }

    /** The menu's background: the loaded world through the game's renderer, or else the game's own. */
    static class Background extends MenuRenderer{
        private final arc.math.Mat before = new arc.math.Mat();
        private float lastLog;
        private int lastReload = Core.settings.getInt("mshaders-test-menureload", 0);

        @Override
        public void render(){
            if(!showing() || Vars.world.width() <= 0){
                if(Vars.state.isMenu()) ask();
                // (the next scene being loaded: black, as the round ended - not a frame of the game's own background)
                if(switching && on()){
                    Draw.color(0f, 0f, 0f, 1f);
                    Fill.crect(0f, 0f, Core.graphics.getWidth(), Core.graphics.getHeight());
                    Draw.color();
                    return;
                }
                super.render();
                return;
            }
            // (test kit: a new number loads the scene again, as coming back to the menu would)
            int reload = Core.settings.getInt("mshaders-test-menureload", 0);
            if(reload != lastReload){
                lastReload = reload;
                if(reload != 0){
                    loaded = false;
                    ask();
                }
            }
            if(PreviewWorld.current != null){
                // a settings preview's little world (Preview): looked at from its middle, living, and its picture taken
                // for the panel as soon as it is drawn
                try{
                    before.set(Draw.proj());
                    Draw.flush();
                    // (as wide as on a 1280-wide screen, whatever the screen: the panel shows the whole of it shrunk;
                    // never wider than the little map)
                    float zoom = PreviewWorld.current.zoom, sw = Core.graphics.getWidth(), sh = Core.graphics.getHeight();
                    float vw = 1280f / zoom, vh = vw * sh / Math.max(sw, 1f);
                    float fit = Math.min(1f, PreviewWorld.size * Vars.tilesize / Math.max(vw, vh));
                    Core.camera.position.set(PreviewWorld.current.camX * Vars.tilesize, PreviewWorld.current.camY * Vars.tilesize);
                    Core.camera.resize(vw * fit, vh * fit);
                    // silent while a preview is shown ("set every effect and background sound to 0 there - not in a
                    // game"): its shots, blasts and loops, and the menu's music. Again each frame (the game may make
                    // its buses anew); the volume of a sound is read when it is played, here, so the one-shot sounds
                    // are held at 0 for this frame too. (The buttons' clicks have a bus of their own.)
                    quiet(true);
                    Core.audio.sfxVolume = 0f;
                    live();
                    PreviewWorld.keep();
                    if(Core.settings.getBool("mshaders-test-menulog", false) && (Time.globalTime - lastLog > 60f || Time.globalTime < lastLog)){
                        lastLog = Time.globalTime;
                        Log.info("[mshaders] preview @: daytime @, day active @, sunlight @, showing @, post @", PreviewWorld.current.name, daytime(), Daytime.active(), Daytime.sunlight(), showing(), Core.settings.getBool(ShaderMod.postOn, true));
                    }
                    Vars.renderer.draw();
                    Draw.flush();
                    Preview.capture();
                    Draw.proj(before);
                }catch(Throwable t){
                    recover();
                    Draw.proj(before);
                    Log.err("[mshaders] the settings preview cannot be drawn", t);
                    endPreview();
                }
                return;
            }
            try{
                before.set(Draw.proj());
                Draw.flush();
                // (the load and the warm-up take a long frame or two: counted from the load, the fade-in was over before
                // the scene was ever seen - "fade in from black when it first shows too")
                if(!started){
                    started = true;
                    startTime = Time.globalTime;
                }
                float sinceStart = (Time.globalTime - startTime) / 60f;
                float t = sinceStart, fade = 0f;
                float zoom;
                if(scene != null){
                    float len = scene.length();
                    // round and round: held, moving (eased), held; a short fade through black where it starts again
                    t %= len;
                    // first across to the end, close up; then out (the zoom setting off a little before the camera stops, so
                    // the one runs into the other) - together, the zoom hid the moving ("it only zooms out, it does not
                    // seem to move"; the maker chose: move, then zoom)
                    float s = Mathf.clamp((t - scene.hold) / scene.move);
                    // (with no zoom to follow, the moving takes the whole time)
                    boolean zooms = scene.fromZoom != scene.toZoom;
                    float k = Interp.smoother.apply(Mathf.clamp(zooms ? s / 0.6f : s));
                    // (a camera that stays where it is zooms the whole time; one that moves, after it has moved)
                    boolean moves = scene.fromX != scene.toX || scene.fromY != scene.toY;
                    float z = Interp.smoother.apply(Mathf.clamp(moves ? (s - 0.5f) / 0.5f : s));
                    Core.camera.position.set(Mathf.lerp(scene.fromX, scene.toX, k) * Vars.tilesize, Mathf.lerp(scene.fromY, scene.toY, k) * Vars.tilesize);
                    // (evenly as the eye sees it: by ratio, not by difference)
                    float from = scene.fromZoom < 0f ? Vars.renderer.maxScale() : scene.fromZoom;
                    float to = scene.toZoom < 0f ? Vars.renderer.maxScale() : scene.toZoom;
                    zoom = from * (float)Math.pow(to / from, z);
                    fade = Math.max(1f - Mathf.clamp(t / 0.8f), Mathf.clamp((t - (len - 0.8f)) / 0.8f));
                }else{
                    // drifting slowly over the middle of the map, a long slow loop
                    float w = Vars.world.unitWidth(), h = Vars.world.unitHeight();
                    Core.camera.position.set(w / 2f + Mathf.sin(t * 0.021f) * w * 0.22f, h / 2f + Mathf.sin(t * 0.013f + 1.3f) * h * 0.18f);
                    zoom = Vars.renderer.getDisplayScale();
                }
                // the very first time: out of black, slowly
                fade = Math.max(fade, 1f - Mathf.clamp(sinceStart / 2f));
                Core.camera.resize(Core.graphics.getWidth() / zoom, Core.graphics.getHeight() / zoom);
                if(Core.settings.getBool("mshaders-test-menulog", false) && Time.globalTime - lastLog > 120f){
                    lastLog = Time.globalTime;
                    float ux = 0f;
                    for(var u : mindustry.gen.Groups.unit) ux += u.x + u.y;
                    int items = 0;
                    for(var b : mindustry.gen.Groups.build) if(b.items != null) items += b.items.total();
                    Log.info("[mshaders] menu scene life: clock @, units @ (sum of places @), items held @, shots @", Strings.fixed(Time.time, 0), mindustry.gen.Groups.unit.size(), Strings.fixed(ux, 0), items, mindustry.gen.Groups.bullet.size());
                    Log.info("[mshaders] menu scene snow: lying @, Skies.snow @, k @, pile @, inWorld @", snowLying(), Skies.snow, Skies.snowK(), Skies.snowPile(), ShaderMod.inWorld());
                    Log.info("[mshaders] menu camera t=@ at tile @,@ zoom @",Strings.fixed(t, 1), Strings.fixed(Core.camera.position.x / Vars.tilesize, 1), Strings.fixed(Core.camera.position.y / Vars.tilesize, 1), Strings.fixed(zoom, 2));
                }
                // truly paused: drawn at the moment it was held, the rain hanging in the air too - whatever moves by the
                // clock (the conveyors' surface, the rain, flames, the shaders' own time) stands still ("the rain may stop
                // too: the feel of a real pause"; and "the conveyors still move"). Only the camera goes on, by the
                // menu's own clock.
                // (the shaders' own clock is the steady one, Time.globalTime - water shimmering: held as well)
                // Where the camera's round ends (in the black), the next scene out of the bag comes in - a living one
                // left to run on would get clogged, run dry, wander off from what it was; and one scene over and over was
                // dull ("only the craters, again and again")
                if(scene != null && (int)(sinceStart / scene.length()) > 0 && !switching){
                    switching = true;
                    loaded = false;
                    ask();
                }
                boolean living = scene != null && scene.live;
                if(living){
                    // a living scene: a frame of the world, then drawn as it is now
                    live();
                    Vars.renderer.draw();
                }else{
                    float clock = Time.time, delta = Time.delta, global = Time.globalTime;
                    Time.time = heldTime;
                    Time.globalTime = heldGlobal;
                    Time.delta = 0f;
                    try{
                        Vars.renderer.draw();
                    }finally{
                        Time.time = clock;
                        Time.globalTime = global;
                        Time.delta = delta;
                    }
                }
                Draw.flush();
                Draw.proj(before);
                // a little darker, under the buttons; black where the scene starts over
                Draw.color(0f, 0f, 0f, 0.25f + 0.75f * fade);
                Fill.crect(0f, 0f, Core.graphics.getWidth(), Core.graphics.getHeight());
                Draw.color();
            }catch(Throwable t){
                failed = true;
                recover();
                Draw.proj(before);
                Draw.color();
                Log.err("[mshaders] the menu scene cannot be drawn, the game's own background comes back", t);
            }
        }
    }
}
