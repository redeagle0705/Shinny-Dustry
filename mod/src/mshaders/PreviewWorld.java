package mshaders;

import arc.func.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.production.*;

/**
 * The little worlds the settings' previews are drawn from, in the main menu (Preview): for each group of settings a
 * map of 50 x 50 tiles ("a very small map, 50x50, is fine to show it") made on the spot, with what that group's effect
 * needs to be seen - a lake for the water, ice, a snowy field with walkers for the tracks, a small base with turrets
 * firing at targets for the shadows and the heat - and the weather and the time of day it shows best in. It lives
 * (MenuWorld.live) while it is shown, and the panel shows it through the game's renderer as it is drawn now: with the
 * settings as they stand, so changing one changes the picture at once.
 *
 * There is only ever one world in the game: these are made only in the menu, in place of the scene behind it (which
 * comes back when the settings are closed). In a game the panel shows the game itself (Preview.capture).
 */
class PreviewWorld{
    static final int size = 50;
    /** The middle of the map, where the camera looks. */
    static final float mid = size / 2f;

    /** One little world: what is built there, its weather and time of day, how close the camera is. */
    static class Group{
        final String name;
        /** 0 .. 100 from midnight (Daytime, as the menu's scenes). */
        float daytime = 45f;
        /** A weather that falls there, or null. */
        Weather weather;
        /** Snow lying on the ground and the buildings (MenuWorld.snowLying). */
        boolean snow;
        /** Screen pixels per world unit (the panel shows the screen shrunk to 480 x 270: close up). */
        float zoom = 7f;
        /** Where the camera looks, in tiles. */
        float camX = mid, camY = 23f;
        final Cons<Builder> build;

        Group(String name, Cons<Builder> build){
            this.name = name;
            this.build = build;
        }

        Group at(float daytime){
            this.daytime = daytime;
            return this;
        }

        Group weather(Weather w){
            weather = w;
            return this;
        }

        Group snowing(){
            weather = Weathers.snow;
            snow = true;
            return this;
        }

        Group zoom(float z){
            zoom = z;
            return this;
        }

        /** Dark by the map's own rule (the game's lighting), not by the time of day. */
        boolean dark;
        /** Where the cursor is taken to be (tiles; NaN: the real one), and a block being placed there (or null). */
        float cursorX = Float.NaN, cursorY = Float.NaN;
        Block placing;

        Group darkMap(){
            dark = true;
            return this;
        }

        Group cursor(float x, float y, Block placing){
            cursorX = x;
            cursorY = y;
            this.placing = placing;
            return this;
        }

        Group cam(float x, float y){
            camX = x;
            camY = y;
            return this;
        }
    }

    /** What a group's map is made with: the ground first (in the generator), then blocks and units (after it). */
    static class Builder{
        Tiles tiles;
        final Team us = Team.sharded, them = Team.crux;

        void floor(Block floor, int x0, int y0, int x1, int y1){
            if(!buildingGround) return;
            for(int y = Math.max(y0, 0); y <= Math.min(y1, size - 1); y++){
                for(int x = Math.max(x0, 0); x <= Math.min(x1, size - 1); x++) tiles.getn(x, y).setFloor(floor.asFloor());
            }
        }

        void ore(Block ore, int x0, int y0, int x1, int y1){
            if(!buildingGround) return;
            for(int y = Math.max(y0, 0); y <= Math.min(y1, size - 1); y++){
                for(int x = Math.max(x0, 0); x <= Math.min(x1, size - 1); x++) tiles.getn(x, y).setOverlay(ore);
            }
        }

        /** A block of ours at that tile (the middle tile of a larger one). */
        Building put(Block block, int x, int y){
            return put(block, x, y, 0);
        }

        Building put(Block block, int x, int y, int rotation){
            if(buildingGround) return null;
            Tile t = Vars.world.tile(x, y);
            if(t == null) return null;
            t.setBlock(block, us, rotation);
            return t.build;
        }

        /** A block of the enemy's (it fires at ours; ours cannot bring it down). */
        Building enemy(Block block, int x, int y){
            if(buildingGround) return null;
            Tile t = Vars.world.tile(x, y);
            if(t == null) return null;
            t.setBlock(block, them, 0);
            return t.build;
        }

        /** A row of conveyors from x0 to x1 at y, running right, with items on them. */
        void belt(int x0, int x1, int y, Item item){
            if(buildingGround) return;
            for(int x = x0; x <= x1; x++){
                Building b = put(Blocks.conveyor, x, y, 0);
                if(b != null && item != null) b.handleStack(item, 2, null);
            }
        }

        Unit unit(UnitType type, Team team, float x, float y){
            if(buildingGround) return null;
            Unit u = type.spawn(team, x * Vars.tilesize, y * Vars.tilesize);
            return u;
        }

        /** An enemy standing still that the turrets keep firing at, and that does not go down (nor fire back, unless told to). */
        Unit target(UnitType type, float x, float y, boolean armed){
            if(buildingGround) return null;
            Unit u = unit(type, them, x, y);
            u.apply(StatusEffects.unmoving, 1e9f);
            if(!armed) u.apply(StatusEffects.disarmed, 1e9f);
            u.health = 1e9f;
            return u;
        }

        /** A unit of ours going back and forth between two places (tiles). */
        Unit patrol(UnitType type, float x0, float y0, float x1, float y1){
            if(buildingGround) return null;
            Unit u = unit(type, us, x0, y0);
            u.controller(new Patrol(x0 * Vars.tilesize, y0 * Vars.tilesize, x1 * Vars.tilesize, y1 * Vars.tilesize));
            return u;
        }

        /** A unit of ours going round a place (tiles). */
        Unit circling(UnitType type, float x, float y, float radius){
            if(buildingGround) return null;
            Unit u = unit(type, us, x + radius, y);
            u.controller(new Circle(x * Vars.tilesize, y * Vars.tilesize, radius * Vars.tilesize));
            return u;
        }
    }

    /** Back and forth between two points. */
    static class Patrol extends AIController{
        final Vec2 a = new Vec2(), b = new Vec2();
        boolean toB = true;

        Patrol(float ax, float ay, float bx, float by){
            a.set(ax, ay);
            b.set(bx, by);
        }

        @Override
        public void updateMovement(){
            Vec2 to = toB ? b : a;
            if(unit.within(to, 6f)) toB = !toB;
            moveTo(to, 2f);
            unit.lookAt(to);
        }

        @Override
        public void updateTargeting(){
        }
    }

    static class Circle extends AIController{
        final Vec2 at = new Vec2();
        final float radius;

        Circle(float x, float y, float radius){
            at.set(x, y);
            this.radius = radius;
        }

        @Override
        public void updateMovement(){
            circle(at, radius);
        }

        @Override
        public void updateTargeting(){
        }
    }

    /** A small base: turrets firing at targets, a smelter at work, walls, a belt with copper. */
    static void base(Builder b){
        b.floor(Blocks.darksand, 0, 0, size - 1, size - 1);
        b.floor(Blocks.stone, 0, 0, size - 1, 17);
        b.floor(Blocks.metalFloor, 15, 19, 34, 31);
        b.ore(Blocks.oreCopper, 5, 5, 9, 8);
        b.put(Blocks.ripple, 18, 22);
        b.put(Blocks.lancer, 23, 22);
        b.put(Blocks.duo, 26, 21);
        b.put(Blocks.duo, 27, 21);
        b.put(Blocks.hail, 28, 22);
        b.put(Blocks.siliconSmelter, 31, 28);
        b.put(Blocks.graphitePress, 18, 28);
        b.put(Blocks.thoriumWallLarge, 21, 18);
        b.put(Blocks.titaniumWallLarge, 24, 18);
        b.put(Blocks.thoriumWall, 27, 18);
        b.put(Blocks.thoriumWall, 28, 18);
        b.belt(15, 33, 25, Items.copper);
        b.put(Blocks.mechanicalDrill, 6, 6);
        b.put(Blocks.mechanicalDrill, 8, 6);
        b.target(UnitTypes.dagger, 19, 13, false);
        b.target(UnitTypes.mace, 25, 12, false);
        b.target(UnitTypes.fortress, 31, 13, false);
    }

    /** An illuminator's colour (rgba). */
    static void lamp(Building b, int rgba){
        if(b != null && !buildingGround) b.configureAny(rgba);
    }

    /** Whether the cursor (and a block being placed) was set by a preview: to be given back. */
    private static boolean cursorSet;

    /** The cursor and the placing back to the player's own (a preview's ore peek or cut-away is over). */
    static void clearCursor(){
        if(!cursorSet) return;
        cursorSet = false;
        OrePeek.testX = OrePeek.testY = Float.NaN;
        Cutaway.testX = Cutaway.testY = Float.NaN;
        if(Vars.control != null && Vars.control.input != null) Vars.control.input.block = null;
    }

    private static void setCursor(Group g){
        if(Float.isNaN(g.cursorX)) return;
        float x = g.cursorX * Vars.tilesize, y = g.cursorY * Vars.tilesize;
        OrePeek.testX = Cutaway.testX = x;
        OrePeek.testY = Cutaway.testY = y;
        if(g.placing != null && Vars.control != null && Vars.control.input != null) Vars.control.input.block = g.placing;
        cursorSet = true;
    }

    /** A power node's laser to another building (as the player draws it). */
    static void link(Building node, Building other){
        if(node == null || other == null || buildingGround) return;
        if(node instanceof mindustry.world.blocks.power.PowerNode.PowerNodeBuild n && !n.power.links.contains(other.pos())) node.configureAny(other.pos());
    }

    /** The groups, and which settings each shows (by the start of their key, longest first). */
    static final Seq<Group> groups = new Seq<>();
    private static final OrderedMap<String, Group> byPrefix = new OrderedMap<>();

    static{
        Group base = new Group("base", PreviewWorld::base);
        Group dusk = new Group("dusk", PreviewWorld::base).at(76f);
        // (one lamp, at the side: an illuminator lights some 20 tiles round it - two in the middle lit the whole little
        // map, and the night looked like day). Units walk through its light - they keep it off what is behind them - and
        // a core and units are lit by their own light.
        Group night = new Group("night", b -> {
            base(b);
            b.put(Blocks.illuminator, 34, 19);
            b.put(Blocks.coreShard, 13, 21);
            b.patrol(UnitTypes.dagger, 26, 20, 34, 23);
            b.patrol(UnitTypes.mace, 33, 26, 24, 21);
        }).at(0f);
        // a map dark by its own rule (the game's lighting), lamps of three colours
        Group dark = new Group("dark", b -> {
            base(b);
            lamp(b.put(Blocks.illuminator, 16, 30), 0xff5040ff);
            lamp(b.put(Blocks.illuminator, 34, 30), 0x40ff70ff);
            lamp(b.put(Blocks.illuminator, 25, 17), 0x5090ffff);
            b.put(Blocks.coreShard, 13, 21);
            b.patrol(UnitTypes.dagger, 18, 20, 32, 20);
        }).darkMap();
        // floors that glow of themselves, at night
        Group glow = new Group("glow", b -> {
            b.floor(Blocks.basalt, 0, 0, size - 1, size - 1);
            b.floor(Blocks.hotrock, 14, 17, 23, 31);
            b.floor(Blocks.magmarock, 24, 17, 30, 31);
            b.floor(Blocks.slag, 31, 19, 37, 29);
            b.put(Blocks.siliconSmelter, 18, 24);
            b.put(Blocks.ripple, 27, 22);
        }).at(0f).zoom(6f).cam(25f, 24f);        Group rain = new Group("rain", PreviewWorld::base).weather(Weathers.rain);
        Group snow = new Group("snow", PreviewWorld::base).snowing();
        Group fog = new Group("fog", PreviewWorld::base).weather(Weathers.fog).zoom(5f);
        Group spore = new Group("spore", PreviewWorld::base).weather(Weathers.sporestorm);
        Group sand = new Group("sand", PreviewWorld::base).weather(Weathers.sandstorm);
        Group clouds = new Group("clouds", PreviewWorld::base).zoom(3f);
        Group metal = new Group("metal", b -> {
            b.floor(Blocks.metalFloor2, 0, 0, size - 1, size - 1);
            b.put(Blocks.thoriumWallLarge, 21, 25);
            b.put(Blocks.titaniumWallLarge, 23, 25);
            b.put(Blocks.plastaniumWallLarge, 25, 25);
            b.put(Blocks.surgeWallLarge, 27, 25);
            b.put(Blocks.phaseWallLarge, 29, 25);
            b.put(Blocks.thoriumWall, 21, 22);
            b.put(Blocks.titaniumWall, 23, 22);
            b.put(Blocks.surgeWall, 25, 22);
        }).at(68f).zoom(10f).cam(25f, 24f);
        Group floors = new Group("floors", b -> {
            // strips across the view: sand, grass, snow, rock, hot rock, metal
            b.floor(Blocks.stone, 0, 0, size - 1, size - 1);
            b.floor(Blocks.sand, 0, 0, 15, size - 1);
            b.floor(Blocks.grass, 16, 0, 19, size - 1);
            b.floor(Blocks.snow, 20, 0, 23, size - 1);
            b.floor(Blocks.basalt, 24, 0, 27, size - 1);
            b.floor(Blocks.hotrock, 28, 0, 29, size - 1);
            b.floor(Blocks.magmarock, 30, 0, 31, size - 1);
            b.floor(Blocks.metalFloor, 32, 0, 35, size - 1);
            b.floor(Blocks.darkPanel3, 36, 0, size - 1, size - 1);
            b.ore(Blocks.oreCopper, 12, 26, 17, 29);
            b.ore(Blocks.oreLead, 22, 19, 26, 22);
        }).at(60f).zoom(6f).cam(25f, 24f);        Group liquids = new Group("liquids", b -> {
            // pools of slag, tar and cryofluid on the ground (what the setting changes), conduits and tanks above them
            b.floor(Blocks.darksand, 0, 0, size - 1, size - 1);
            Block[] pools = {Blocks.slag, Blocks.tar, Blocks.cryofluid};
            for(int pk = 0; pk < pools.length; pk++){
                int cx = 16 + pk * 9, cy = 21;
                for(int dx = -4; dx <= 4; dx++){
                    for(int dy = -4; dy <= 4; dy++){
                        if(dx * dx + dy * dy <= 15) b.floor(pools[pk], cx + dx, cy + dy, cx + dx, cy + dy);
                    }
                }
            }
            Liquid[] kinds = {Liquids.water, Liquids.slag, Liquids.oil, Liquids.cryofluid};
            for(int i = 0; i < kinds.length; i++){
                int x = 16 + i * 6;
                Building tank = b.put(Blocks.liquidContainer, x, 30);
                if(tank != null) tank.handleLiquid(null, kinds[i], 600f);
                for(int k = x + 2; k <= x + 4; k++){
                    Building c = b.put(Blocks.conduit, k, 30, 0);
                    if(c != null) c.handleLiquid(null, kinds[i], 10f);
                }
            }
        }).zoom(5f).cam(25f, 25f);        Group grid = new Group("grid", b -> {
            b.floor(Blocks.darksand, 0, 0, size - 1, size - 1);
            b.floor(Blocks.metalFloor, 15, 18, 35, 30);
            // generators and batteries on the left, the works on the right, nodes joining them (the power lines show)
            for(int x = 17; x <= 21; x++) b.put(Blocks.combustionGenerator, x, 27);
            for(int x = 17; x <= 21; x++) b.put(Blocks.battery, x, 20);
            b.put(Blocks.graphitePress, 29, 27);
            b.put(Blocks.siliconSmelter, 33, 27);
            b.put(Blocks.kiln, 29, 21);
            b.put(Blocks.pneumaticDrill, 33, 21);
            b.put(Blocks.illuminator, 25, 29);
            Building left = b.put(Blocks.powerNode, 22, 24), right = b.put(Blocks.powerNode, 28, 24);
            link(left, right);
            for(int x = 17; x <= 21; x += 2){
                link(left, Vars.world.build(x, 27));
                link(left, Vars.world.build(x, 20));
            }
            link(right, Vars.world.build(29, 27));
            link(right, Vars.world.build(33, 27));
            link(right, Vars.world.build(29, 21));
            link(right, Vars.world.build(33, 21));
            link(right, Vars.world.build(25, 29));
            b.belt(15, 35, 25, Items.coal);
            // a unit factory at work (the factories setting)
            Building f = b.put(Blocks.groundFactory, 25, 19);
            if(f != null) f.configure(UnitTypes.dagger);
            link(right, f);
        }).cam(25f, 23f);
        Group pulses = new Group("pulses", b -> {
            base(b);
            b.put(Blocks.mender, 22, 26);
            b.put(Blocks.mender, 27, 26);
            b.put(Blocks.overdriveProjector, 25, 29);
        }).zoom(6f).cam(25f, 25f);
        Group shields = new Group("shields", b -> {
            base(b);
            b.put(Blocks.forceProjector, 25, 27);
            b.target(UnitTypes.dagger, 21, 34, true);
            b.target(UnitTypes.dagger, 29, 34, true);
            // enemy turrets firing at the shield: it ripples, cracks, breaks and comes back (shots it can catch - a
            // lancer's laser goes through a shield, rippling nothing and wearing the base down)
            // (outside the shield - a turret inside it has every shot caught at the muzzle - in reach of the units)
            b.enemy(Blocks.duo, 19, 40);
            b.enemy(Blocks.duo, 31, 40);
            b.enemy(Blocks.hail, 25, 42);
        }).zoom(6f).cam(25f, 31f);
        Group water = new Group("water", b -> {
            b.floor(Blocks.sand, 0, 0, size - 1, size - 1);
            b.floor(Blocks.darksandWater, 0, 0, size - 1, 23);
            b.floor(Blocks.water, 0, 0, size - 1, 20);
            b.floor(Blocks.deepwater, 0, 0, size - 1, 13);
            b.put(Blocks.ripple, 18, 26);
            b.put(Blocks.lancer, 23, 26);
            b.put(Blocks.thoriumWallLarge, 27, 25);
            b.put(Blocks.siliconSmelter, 31, 26);
            b.patrol(UnitTypes.risso, 12, 18, 38, 18);
            b.patrol(UnitTypes.minke, 38, 15, 12, 15);
        }).cam(25f, 21f);
        Group ice = new Group("ice", b -> {
            b.floor(Blocks.ice, 0, 0, size - 1, size - 1);
            b.floor(Blocks.iceSnow, 0, 0, 16, size - 1);
            b.floor(Blocks.snow, 34, 0, size - 1, size - 1);
            b.ore(Blocks.oreCopper, 10, 12, 40, 15);
            b.put(Blocks.ripple, 20, 25);
            b.put(Blocks.siliconSmelter, 27, 25);
            b.put(Blocks.thoriumWallLarge, 23, 21);
            b.belt(14, 36, 30, Items.lead);
        });
        Group tracks = new Group("tracks", b -> {
            b.floor(Blocks.snow, 0, 0, size - 1, size - 1);
            b.floor(Blocks.sand, 0, 0, size - 1, 15);
            b.patrol(UnitTypes.dagger, 10, 20, 40, 20);
            b.patrol(UnitTypes.mace, 40, 25, 10, 25);
            b.patrol(UnitTypes.fortress, 12, 30, 38, 30);
            b.patrol(UnitTypes.crawler, 10, 16, 40, 16);
        }).snowing().zoom(6f);
        Group statuses = new Group("statuses", b -> {
            base(b);
            StatusEffect[] effects = {StatusEffects.burning, StatusEffects.wet, StatusEffects.freezing, StatusEffects.overclock, StatusEffects.tarred, StatusEffects.shocked};
            for(int i = 0; i < effects.length && !PreviewWorld.buildingGround; i++){
                Unit u = b.unit(i % 2 == 0 ? UnitTypes.dagger : UnitTypes.mace, b.us, 15 + i * 4, 27);
                u.apply(effects[i], 1e9f);
                u.apply(StatusEffects.unmoving, 1e9f);
                u.health = 1e9f;
            }
        }).zoom(6f).cam(25f, 25f);
        Group flyers = new Group("flyers", b -> {
            base(b);
            b.circling(UnitTypes.horizon, 25, 25, 9);
            b.circling(UnitTypes.flare, 25, 25, 6);
            b.circling(UnitTypes.zenith, 25, 25, 8);
        }).at(5f).zoom(5f).cam(25f, 25f);
        // placing a block where air units are: those round the cursor are seen through
        Group cutaway = new Group("cutaway", b -> {
            base(b);
            for(int i = 0; i < 5 && !PreviewWorld.buildingGround; i++){
                Unit u = b.unit(i % 2 == 0 ? UnitTypes.zenith : UnitTypes.horizon, b.us, 22 + i * 1.6f, 24 + (i % 2) * 2f);
                u.apply(StatusEffects.unmoving, 1e9f);
                u.health = 1e9f;
            }
        }).cursor(25f, 25f, Blocks.thoriumWallLarge).zoom(7f).cam(25f, 24f);
        Group orePeek = new Group("orepeek", b -> {
            b.floor(Blocks.stone, 0, 0, size - 1, size - 1);
            b.ore(Blocks.oreCopper, 18, 20, 32, 30);
            b.ore(Blocks.oreLead, 24, 24, 26, 26);
            b.put(Blocks.mechanicalDrill, 22, 24);
            b.put(Blocks.pneumaticDrill, 28, 24);
        }).cursor(22.5f, 24.5f, null).zoom(9f).cam(25.5f, 25f);

        groups.addAll(base, dusk, night, dark, glow, cutaway, rain, snow, fog, spore, sand, clouds, metal, floors, liquids, grid, pulses, shields, water, ice, tracks, statuses, flyers, orePeek);
        // (by the start of the setting's key; the longest that fits wins)
        String[][] map = {
            {"mshaders-day", "dusk"},
            {"mshaders-sun", "dusk"},
            {"mshaders-lights", "night"}, {"mshaders-fire", "night"}, {"mshaders-normals-own-light", "night"},
            {"mshaders-lights-color", "dark"}, {"mshaders-dark-depth", "dark"},
            {"mshaders-lights-ground", "glow"},
            {"mshaders-weather", "rain"}, {"mshaders-weather-rain", "rain"}, {"mshaders-wet", "rain"},
            {"mshaders-weather-snow", "snow"},
            {"mshaders-weather-fog", "fog"},
            {"mshaders-weather-spore", "spore"},
            {"mshaders-weather-sand", "sand"},
            {"mshaders-clouds", "clouds"},
            {"mshaders-glint", "metal"},
            {"mshaders-floor", "floors"},
            {"mshaders-liquids", "liquids"},
            {"mshaders-power", "grid"}, {"mshaders-factories", "grid"},
            {"mshaders-pulses", "pulses"},
            {"mshaders-shields", "shields"},
            {"mshaders-water", "water"}, {"mshaders-reflect", "water"}, {"mshaders-wakes", "water"},
            {"mshaders-ice", "ice"},
            {"mshaders-tracks", "tracks"},
            {"mshaders-statuses", "statuses"},
            {"mshaders-boosters", "flyers"}, {"mshaders-cutaway", "cutaway"}, {"mshaders-shot-shadows", "flyers"},
            {"mshaders-ore-peek", "orepeek"},
        };
        for(String[] m : map) byPrefix.put(m[0], groups.find(g -> g.name.equals(m[1])));
    }

    /** The group that shows that setting (the base if none fits closer). */
    static Group of(String key){
        Group best = null;
        int len = -1;
        if(key != null) for(var e : byPrefix){
            if(key.startsWith(e.key) && e.key.length() > len){
                best = e.value;
                len = e.key.length();
            }
        }
        return best != null ? best : groups.first();
    }

    /** The group whose map is loaded now (null: none). */
    static Group current;
    private static float lastTick;

    /** Makes that group's map, in place of whatever the menu showed. False if it could not be made. */
    static boolean load(Group g){
        try{
            clearCursor();
            Builder b = new Builder();
            // (a made map brings no entities of its own and takes none away: the last one's units, shots, weather and
            // puddles were left over - snow falling on the next preview)
            Groups.clear();
            Vars.world.loadGenerator(size, size, tiles -> {
                b.tiles = tiles;
                tiles.each((x, y) -> tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air)));
                // the ground: what each group lays down first (blocks come after the map is loaded)
                buildingGround = true;
                g.build.get(b);
                buildingGround = false;
            });
            Rules rules = new Rules();
            rules.planet = Planets.serpulo;
            // (the base's blocks work without power or items, the turrets without running dry: a shop window)
            rules.teams.get(Team.sharded).cheat = true;
            // (the enemy's turrets fire without running dry too, and ours do not bring them down)
            rules.teams.get(Team.crux).cheat = true;
            rules.teams.get(Team.crux).blockHealthMultiplier = 1000f;
            // (and theirs do not bring ours down: the window stays whole however long it is looked at)
            rules.teams.get(Team.sharded).blockHealthMultiplier = 1000f;
            rules.fog = false;
            rules.lighting = g.dark;
            if(g.dark) rules.ambientLight.set(0.01f, 0.01f, 0.04f, 0.92f);
            rules.waves = false;
            Vars.state.rules = rules;
            g.build.get(b);
            if(g.weather != null) g.weather.create(1f, 60f * 60f * 60f).opacity(1f);
            current = g;
            setCursor(g);
            return true;
        }catch(Throwable t){
            Log.err("[mshaders] the preview's map " + g.name + " could not be made", t);
            current = null;
            return false;
        }
    }

    /** True while the ground is being laid (the generator): the builders then only set floors and ores. */
    static boolean buildingGround;

    /** Each frame the map lives: the turrets kept in ammunition, the crafters emptied, now and then an explosion. */
    static void keep(){
        if(current == null) return;
        // (the game lets go of the block being placed while in the menu: held again each frame for the cut-away)
        if(cursorSet && current.placing != null && Vars.control != null && Vars.control.input != null) Vars.control.input.block = current.placing;
        if(Time.time - lastTick < 60f && Time.time >= lastTick) return;
        lastTick = Time.time;
        for(Building b : Groups.build){
            if(b instanceof ItemTurret.ItemTurretBuild t && b.block instanceof ItemTurret it && t.totalAmmo < 10 && it.ammoTypes.size > 0){
                t.handleStack(it.ammoTypes.keys().toSeq().first(), 10, null);
            }else if((b instanceof GenericCrafter.GenericCrafterBuild || b instanceof Drill.DrillBuild) && b.items != null){
                b.items.clear();
            }
        }
        // the base group's blast (a few tiles off the base, every few seconds)
        if(current.name.equals("base")){
            if(Mathf.chance(0.35)) Damage.dynamicExplosion(25f * Vars.tilesize + Mathf.range(40f), 16f * Vars.tilesize, 0f, 6f, 0f, 24f, false);
        }
    }
}
