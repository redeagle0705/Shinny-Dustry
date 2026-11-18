package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.type.UnitType.*;
import mindustry.world.blocks.storage.*;

/**
 * The engines of units, drawn as streaks of light.
 *
 * The game draws an engine as two discs: one in the team's colour and a smaller white one in it, a little off centre
 * (UnitType.UnitEngine.draw). Here every engine of every unit is swapped for one that draws, in the same place, on the
 * same layer, in the same colours and under the unit's body as before, what the engines of a ship in space look like:
 *   - a white-hot point at the nozzle with a halo in the team's colour;
 *   - a streak behind it, shaped like a long wedge: nearly as wide as the engine at the nozzle and narrowing evenly
 *     to a point; white at the nozzle, the team's colour further out, fading towards the tip - short while the unit
 *     hovers, many times as long at full speed (the maker drew that wedge over a screenshot of the thin streak of
 *     before: "almost this shape");
 *   - a soft glow along the streak; on a dark map that glow is a light of the game's light picture too, so the
 *     streak shines in the dark instead of being darkened like a painted thing.
 * The streak is what the engine has left behind, so it does not simply stick out of the back of the unit:
 *   - while the unit turns, the far end still points where the engine pointed a moment ago - the streak curves and
 *     its end swings out, the more the faster the unit turns;
 *   - while the unit drifts sideways (as flying units do in every turn), the streak leans the other way;
 *   - at speed a faint ripple runs down it.
 * (The first tries were a soft blob - it read as a lamp -, a drop-shaped flame in three plain colours, and a streak
 * that only grew longer with speed: the maker found that one stiff, "it does not swing sideways when it moves".)
 *
 * The game keeps the engines of a unit type in a list anyone may change (UnitType.engines), and asks each entry to draw
 * itself - so nothing of the game is patched: the entries are replaced by a subclass. With the setting off, that
 * subclass draws what the game draws. Engines that are already something of their own (another mod's) are left alone.
 * The colour: the game gives every engine the colour of the unit's team. Many units are marked with a colour of their
 * own - the green of the support units, the purple of others - and their streaks take that colour instead (a list by
 * unit name, mshaders/engine-colors.properties; the player's own list in the data folder comes first). An engine or a
 * unit type the game itself gives a colour keeps it. A setting gives every unit its team's colour back.
 * THE CORE COMING DOWN burns four engines, and the game draws those the same way - two discs each
 * (CoreBlock.CoreBuild.drawLanding). They get the same streaks ({@link #queueLanding}): four of them, one at each side
 * of the core, swept back by the turning of the core as it comes down. There is no list to swap an entry of here. The
 * size of the game's discs comes out of a table anyone may change (CoreBlock.thrusterSizes); for the moment the game
 * draws the landing that table is set to nothing - discs of no size - and put back right after.
 * What is laid on the streak, its colour, length and width can differ from one kind of unit to the next ({@link Look}):
 * a list by unit name (mshaders/engines.properties in the mod, mshaders-engines.properties in the data folder), written
 * by the engine window (EngineDialog). The maker's idea: "what if the engines differed from unit to unit - several
 * effects, and a window to dress them in?"
 * The size goes by the game's: the radius of the engine, breathing the way the game's disc does, times how far the unit
 * is off the ground for the units whose engines only burn in flight.
 */
public class Boosters{
    /** The game's soft round sprite (white, fading out towards its rim): what it draws its lights and soft shadows with. */
    private static TextureRegion soft;
    private static boolean on = true;
    private static long checked = -1;
    /** Per unit (by id): the way it faced when last drawn, how fast it is turning (degrees a tick, smoothed), and the frame of that. */
    private static final IntMap<float[]> turns = new IntMap<>();
    /** Where the streak drawn last ended (streak). */
    private static float tipX, tipY;
    /** The colour of the streaks of the unit types that have one of their own (engine-colors.properties). */
    private static final ObjectMap<UnitType, Color> own = new ObjectMap<>();
    private static boolean tinted = true;
    /**
     * How much the engines breathe - grow and shrink a little all the time, as the game's discs do: 1 = as the game has
     * it, 0 = not at all, 2 = twice as much (the setting, a slider: first a switch, then the maker: "or a slider?").
     */
    private static float breathing = 1f;

    /**
     * The radius of an engine right now. The game makes it swell by up to a quarter and back, twice a second or so
     * (UnitEngine.draw): it swings about the middle of that by as much as the breathing says, and stands still at 0.
     */
    private static float breathe(float radius){
        return radius * 1.125f + (Mathf.absin(Time.time, 2f, radius / 4f) - radius / 8f) * breathing;
    }

    /** What can be laid on the streak: nothing, rings around it, arcs across it, beads of light running down it. */
    public static final String[] styles = {"plain", "rings", "arcs", "pulses"};
    public static final int plain = 0, rings = 1, arcs = 2, pulses = 3;

    /** How the engines of one kind of unit look, as far as it can be chosen (the engine window, engines.properties). */
    public static class Look{
        /** One of {@link #styles}. */
        public int style = plain;
        /** "team" = the colour of the unit's team, "own" = the colour the unit is marked with (if it has one), else a colour of its own. */
        public String colour = "own";
        public final Color custom = new Color(Color.white);
        /** Times the usual length and width. */
        public float length = 1f, width = 1f;

        public boolean usual(){
            return style == plain && colour.equals("own") && length == 1f && width == 1f;
        }

        public boolean same(Look other){
            return style == other.style && colour.equals(other.colour) && length == other.length && width == other.width && (!colour.equals("custom") || custom.equals(other.custom));
        }

        public void set(Look other){
            style = other.style;
            colour = other.colour;
            custom.set(other.custom);
            length = other.length;
            width = other.width;
        }

        /** As a line of engines.properties: style, colour, length %, width %. */
        String write(){
            return styles[style] + ", " + (colour.equals("team") || colour.equals("own") ? colour : custom.toString().substring(0, 6)) + ", " + Math.round(length * 100f) + ", " + Math.round(width * 100f);
        }
    }

    private static final Look usual = new Look();
    /** The kinds of unit whose engines do not look the usual way. */
    private static final ObjectMap<UnitType, Look> looks = new ObjectMap<>();
    /** The same as the mod itself brings it (its own engines.properties): what "as the mod has it" goes back to. */
    private static final ObjectMap<UnitType, Look> shipped = new ObjectMap<>();

    /** How the mod itself dresses the engines of that kind of unit; never null. */
    public static Look defaultOf(UnitType type){
        Look look = shipped.get(type);
        return look == null ? usual : look;
    }

    /** How the engines of that kind of unit look; never null. */
    public static Look lookOf(UnitType type){
        Look look = looks.get(type);
        return look == null ? usual : look;
    }

    /** True when the list has changed since it was last written. */
    private static boolean unsaved;
    /** True while the engine window draws its unit: the clock that runs while the game is paused is used then. */
    private static boolean previewing;

    /** Sets how the engines of that kind of unit look (engine window); the list is written by {@link #saveNow}. */
    public static void setLook(UnitType type, Look look){
        if(look.usual()) looks.remove(type);
        else looks.get(type, Look::new).set(look);
        unsaved = true;
    }

    /** Writes the list if it has changed. */
    public static void saveNow(){
        if(unsaved) save();
        unsaved = false;
    }

    /**
     * Where the list is written: into the watched folder if pictures are drawn with the game open (that is the mod's
     * own list of the next build then), else into the game's data folder.
     */
    private static Fi listFile(){
        Fi watch = Surfaces.watched();
        return watch != null ? watch.child("mshaders").child("engines.properties") : Vars.dataDirectory.child("mshaders-engines.properties");
    }

    private static void save(){
        try{
            StringBuilder out = new StringBuilder();
            out.append("# How the engines of each kind of unit look:   internal-unit-name = style, colour, length %, width %\n");
            out.append("# style: plain, rings, arcs or pulses.  colour: team, own (the colour the unit is marked with, engine-colors.properties) or rrggbb.\n");
            out.append("# A unit that is not listed: plain, own, 100, 100. Written by the engine window (Settings > Shaders > Units).\n");
            // The list being worked on (watched folder) is the mod's own of the next build: every unit that does not
            // look the usual way. The player's list holds only what the player has changed - a unit put back to how
            // the mod has it is not in it, so it follows the mod when the mod changes; a unit the player wants plain
            // where the mod dresses it is in it, as plain.
            boolean whole = Surfaces.watched() != null;
            Seq<UnitType> listed = new Seq<>();
            for(UnitType type : Vars.content.units()){
                Look look = lookOf(type);
                if(whole ? !look.usual() : !look.same(defaultOf(type))) listed.add(type);
            }
            listed.sort((a, b) -> a.name.compareTo(b.name));
            for(UnitType type : listed) out.append(type.name).append(" = ").append(lookOf(type).write()).append('\n');
            Fi file = listFile();
            file.parent().mkdirs();
            file.writeString(out.toString());
        }catch(Throwable t){
            Log.err("[mshaders] the list of engine looks could not be written", t);
        }
    }

    /** Lines "unit = style, colour, length %, width %", into that list; a line that says the usual takes the unit off it. */
    private static void readLooks(Fi file, ObjectMap<UnitType, Look> looks){
        if(file == null || !file.exists()) return;
        for(String line : file.readString().split("\n")){
            int hash = line.indexOf('#');
            if(hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if(eq <= 0) continue;
            UnitType type = Vars.content.unit(line.substring(0, eq).trim());
            if(type == null) continue;
            String[] parts = line.substring(eq + 1).split(",");
            Look look = new Look();
            try{
                for(int i = 0; i < styles.length; i++) if(styles[i].equalsIgnoreCase(parts[0].trim())) look.style = i;
                if(parts.length > 1){
                    String colour = parts[1].trim().toLowerCase();
                    if(colour.equals("team") || colour.equals("own")){
                        look.colour = colour;
                    }else if(!colour.isEmpty()){
                        look.custom.set(Color.valueOf(colour));
                        look.colour = "custom";
                    }
                }
                if(parts.length > 2) look.length = Mathf.clamp(Float.parseFloat(parts[2].trim()) / 100f, 0.2f, 3f);
                if(parts.length > 3) look.width = Mathf.clamp(Float.parseFloat(parts[3].trim()) / 100f, 0.2f, 3f);
            }catch(Throwable t){
                Log.warn("[mshaders] engine looks: cannot read '@' in @", line.trim(), file.name());
                continue;
            }
            if(look.usual()) looks.remove(type);
            else looks.put(type, look);
        }
    }

    /** @return how many engines were swapped */
    public static int install(){
        int swapped = 0;
        for(UnitType type : Vars.content.units()){
            Seq<UnitEngine> engines = type.engines;
            for(int i = 0; i < engines.size; i++){
                UnitEngine engine = engines.get(i);
                if(engine == null || engine.getClass() != UnitEngine.class) continue;
                engines.set(i, new Flame(engine));
                swapped++;
            }
        }
        try{
            read(Vars.tree.get("mshaders/engine-colors.properties"));
            read(Vars.dataDirectory.child("mshaders-engine-colors.properties"));
        }catch(Throwable t){
            own.clear();
            Log.err("[mshaders] engine colours unreadable, every engine keeps its team's colour", t);
        }
        try{
            readLooks(Vars.tree.get("mshaders/engines.properties"), shipped);
            Fi watch = Surfaces.watched();
            Fi worked = watch == null ? null : watch.child("mshaders").child("engines.properties");
            if(worked != null && worked.exists()){
                // where pictures are drawn with the game open: the list being worked on, as it is
                readLooks(worked, looks);
            }else{
                // the mod's list, and over it what the player has changed
                for(ObjectMap.Entry<UnitType, Look> entry : shipped){
                    Look copy = new Look();
                    copy.set(entry.value);
                    looks.put(entry.key, copy);
                }
                readLooks(Vars.dataDirectory.child("mshaders-engines.properties"), looks);
            }
        }catch(Throwable t){
            looks.clear();
            shipped.clear();
            Log.err("[mshaders] engine looks unreadable, every engine looks the usual way", t);
        }
        return swapped;
    }

    /** How many unit types have a streak colour of their own. */
    public static int colours(){
        return own.size;
    }

    /** Lines "unit-name = rrggbb"; "unit-name = team" takes a unit off the list; # starts a comment. */
    private static void read(Fi file){
        if(file == null || !file.exists()) return;
        for(String line : file.readString().split("\n")){
            int hash = line.indexOf('#');
            if(hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if(eq <= 0) continue;
            String name = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            UnitType type = Vars.content.unit(name);
            if(type == null) continue;
            if(value.equalsIgnoreCase("team")){
                own.remove(type);
                continue;
            }
            try{
                own.put(type, Color.valueOf(value));
            }catch(Throwable t){
                Log.warn("[mshaders] engine colours: cannot read '@' in @", line.trim(), file.name());
            }
        }
    }

    private static boolean enabled(){
        long frame = Core.graphics.getFrameId();
        if(frame != checked){
            checked = frame;
            on = Core.settings.getBool(ShaderMod.boostersOn, true);
            tinted = Core.settings.getBool(ShaderMod.boostersTint, true);
            breathing = Math.max(ShaderMod.percent(ShaderMod.boostersBreath, 100), 0f);
            if(soft == null) soft = Core.atlas.find("circle-shadow");
        }
        return on && soft != null && soft.found();
    }

    /** The game's table of how large the engines of a landing core burn at each moment of the landing, as it was. */
    private static float[] thrust;
    /** True while the game's own table is set to nothing. */
    private static boolean silenced;
    private static final Runnable landingUnder = Boosters::landingUnder, landingDone = Boosters::landingDone;

    /**
     * While a core is coming down: its four engines as streaks, drawn right before the game draws the landing (so the
     * core lies on them, as it lay on the game's discs), with the game's discs switched off for just that moment.
     * To be called while the frame is queued (Trigger.draw).
     */
    public static void queueLanding(){
        landingDone();
        if(Vars.renderer.getLandTime() <= 0f || Vars.renderer.isLaunching() || !enabled()) return;
        // the game draws the landing at Layer.space (Renderer.draw)
        Draw.draw(Layer.space - 0.01f, landingUnder);
        Draw.draw(Layer.space + 0.01f, landingDone);
    }

    /** The core that is coming down, if it is a core that is. */
    private static CoreBlock.CoreBuild landingCore(){
        Object animator;
        try{
            animator = Reflect.get(Renderer.class, Vars.renderer, "launchAnimator");
        }catch(Throwable t){
            // the game's field cannot be looked at here: the core a landing ends on is the player's (Control)
            animator = Vars.player == null ? null : Vars.player.bestCore();
        }
        return animator instanceof CoreBlock.CoreBuild core ? core : null;
    }

    private static void landingUnder(){
        CoreBlock.CoreBuild core = landingCore();
        if(core == null || !(core.block instanceof CoreBlock block) || Vars.renderer.getLandTime() <= 0f) return;
        if(thrust == null) thrust = CoreBlock.thrusterSizes.clone();
        // CoreBlock.CoreBuild.drawLanding(): where the engines are and how large they burn
        float fin = Vars.renderer.getLandTimeIn(), fout = 1f - fin;
        float scl = Scl.scl(4f) / Vars.renderer.getDisplayScale();
        float rotation = Interp.pow2In.apply(fout) * 135f;
        float burning = Mathf.sample(thrust, fin);
        // the game's discs: none while it draws this frame's landing
        java.util.Arrays.fill(CoreBlock.thrusterSizes, 0f);
        silenced = true;
        if(burning <= 0.001f) return;
        float strength = (1f + (block.size - 3) / 2.5f) * scl * burning * (1f + (Mathf.absin(2f, 0.1f) - 0.05f) * breathing);
        float edge = (block.size * Vars.tilesize / 2f + 1f) * scl + (block.size - 3) * 3f * scl;
        float rad = 6f * strength, time = Time.time;
        // The core turns as it comes down: by 135 degrees times the square of what is left of the way, and that falls
        // by one in the time the landing takes. The streaks are swept back by that turning.
        float turning = -270f * fout / Math.max(core.launchDuration(), 1f);
        float bend = Mathf.clamp(turning * 26f, -55f, 55f);
        Color team = core.team.color;
        for(int i = 0; i < 4; i++){
            float out = i * 90f + rotation, seed = i * 2.4f;
            float shimmer = 1f + (0.06f * Mathf.sin(time * 0.6f + seed) + 0.04f * Mathf.sin(time * 1.7f + seed * 1.7f)) * breathing;
            Flame.burn(core.x + Mathf.cosDeg(out) * edge, core.y + Mathf.sinDeg(out) * edge, out, rad, rad * 6f * shimmer, 1.1f, bend, 0f, rad * 0.12f, time * 0.9f + seed,
                team, Color.white, 0.92f + 0.08f * Mathf.sin(time * 0.9f + seed) * Math.min(breathing, 1f));
        }
    }

    /** Puts the game's table back as it was. */
    private static void landingDone(){
        if(silenced && thrust != null) System.arraycopy(thrust, 0, CoreBlock.thrusterSizes, 0, Math.min(thrust.length, CoreBlock.thrusterSizes.length));
        silenced = false;
    }

    /**
     * For the engine window: the engines of that kind of unit burning, as if the unit stood at that place.
     * @param scale screen units per world unit; facing: where the unit looks, degrees
     * @param push 0 = hovering .. 1 = full speed; turning: degrees a tick
     */
    public static void preview(UnitType type, float cx, float cy, float scale, float facing, float push, float turning, Look look, Color teamColour){
        if(soft == null) soft = Core.atlas.find("circle-shadow");
        if(soft == null || !soft.found()) return;
        float turn = facing - 90f;
        previewing = true;
        for(int i = 0; i < type.engines.size; i++){
            UnitEngine engine = type.engines.get(i);
            float rad = breathe(engine.radius) * scale;
            Color marked = look.colour.equals("own") && engine.color == null && type.engineColor == null ? own.get(type) : null;
            Color team = look.colour.equals("custom") ? look.custom : engine.color != null ? engine.color : type.engineColor != null ? type.engineColor : marked != null ? marked : teamColour;
            Flame.engine(cx + Angles.trnsx(turn, engine.x, engine.y) * scale, cy + Angles.trnsy(turn, engine.x, engine.y) * scale, rad, turn + engine.rotation,
                push, 0f, turning, i * 2.9f, 1f, team, type.engineColorInner, look, false);
        }
        previewing = false;
        Draw.reset();
    }

    /** Does that kind of unit have a streak colour of its own to choose (engine-colors.properties)? */
    public static boolean hasOwnColour(UnitType type){
        return own.containsKey(type);
    }

    /** How fast that unit is turning right now, in degrees a tick (counter-clockwise is positive), smoothed over a few frames. */
    private static float turnOf(Unit unit){
        float frame = Core.graphics.getFrameId() % 1000000L;
        float[] state = turns.get(unit.id);
        if(state == null){
            // units come and go; their numbers are never used again
            if(turns.size > 4000) turns.clear();
            turns.put(unit.id, new float[]{unit.rotation, 0f, frame});
            return 0f;
        }
        if(state[2] != frame && !Vars.state.isPaused() && Time.delta > 0.0001f){
            float turned = Mathf.mod(unit.rotation - state[0] + 180f, 360f) - 180f;
            state[1] = Mathf.lerp(state[1], Mathf.clamp(turned / Time.delta, -8f, 8f), Mathf.clamp(0.2f * Time.delta));
            state[0] = unit.rotation;
            state[2] = frame;
        }
        return state[1];
    }

    static class Flame extends UnitEngine{
        Flame(UnitEngine from){
            super(from.x, from.y, from.radius, from.rotation);
            color = from.color;
        }

        @Override
        public void draw(Unit unit){
            if(!enabled()){
                super.draw(unit);
                return;
            }
            UnitType type = unit.type;
            float scale = type.useEngineElevation ? unit.elevation : 1f;
            if(scale <= 0.0001f) return;

            // where the game puts the engine and how large (UnitEngine.draw)
            float turn = unit.rotation - 90f, time = Time.time;
            float ex = unit.x + Angles.trnsx(turn, x, y), ey = unit.y + Angles.trnsy(turn, x, y);
            float rad = breathe(radius) * scale;
            Look look = lookOf(type);
            // the colour: one chosen for this kind of unit; else the one the game gives the engine, if it gives one;
            // else the colour the unit is marked with; else its team's
            Color marked = tinted && look.colour.equals("own") && this.color == null && type.engineColor == null ? own.get(type) : null;
            Color team = look.colour.equals("custom") ? look.custom : this.color != null ? this.color : type.engineColor != null ? type.engineColor : marked != null ? marked : unit.team.color;

            // the way the exhaust goes, and across it
            float out = turn + rotation;
            float ax = Mathf.cosDeg(out), ay = Mathf.sinDeg(out);
            float top = Math.max(type.speed, 0.1f);
            // how hard the engine pushes: how fast the unit flies the way this engine pushes it, as a share of its top speed
            float push = Mathf.clamp(-(unit.vel.x * ax + unit.vel.y * ay) / top, 0f, 1.2f);
            // how fast the unit drifts sideways, to the left of the exhaust, as a share of its top speed
            float drift = Mathf.clamp((unit.vel.x * -ay + unit.vel.y * ax) / top, -1f, 1f);
            engine(ex, ey, rad, out, push, drift, turnOf(unit), unit.id * 3.7f + x * 1.3f + y * 0.7f, Mathf.clamp(scale), team, type.engineColorInner, look,
                Vars.state.rules.lighting && Vars.renderer.lights != null);
        }

        /**
         * One engine, all of it: the streak with what is laid on it, and on a dark map its light.
         * @param push how hard it pushes, 0 = the unit hovers .. 1 = full speed; drift: how fast the unit slides to the left of the exhaust, -1 .. 1
         * @param turning how fast the unit turns, degrees a tick
         * @param seed a number of the engine's own, so that no two shimmer alike
         */
        private static void engine(float ex, float ey, float rad, float out, float push, float drift, float turning, float seed, float scale, Color team, Color hot, Look look, boolean lights){
            float time = previewing ? Time.globalTime : Time.time;
            // length and brightness waver a little, each engine in its own time - by as much as the breathing says
            float shimmer = 1f + (0.05f * Mathf.sin(time * 0.6f + seed) + 0.03f * Mathf.sin(time * 1.7f + seed * 1.7f)) * breathing;
            float bright = scale * (0.92f + 0.08f * Mathf.sin(time * 0.9f + seed * 0.6f) * Math.min(breathing, 1f));
            // from the nozzle to the tip: short while the unit hovers, long at full speed
            float length = rad * (2.4f + 11.6f * push) * shimmer * look.length;
            // The far end is where the engine pointed a moment ago: turned back by what the unit has turned since.
            float bend = Mathf.clamp(turning * 13f, -55f, 55f);
            // what was left behind stays where it was while the unit slides away sideways: the streak leans the other way
            float lean = -drift * (rad * 1.2f + length * 0.3f);
            float ripple = rad * 0.13f * push, phase = time * 0.9f + seed;

            // a streak that grows longer grows a little wider too
            burn(ex, ey, out, rad, length, (1f + 0.25f * Mathf.clamp(push)) * look.width, bend, lean, ripple, phase, team, hot, bright);
            if(look.style != plain) ornaments(look.style, ex, ey, out, rad * look.width, length, bend, lean, push, time, seed, team, hot, bright);

            // On a dark map the streak is a light, not a painted thing the dark lies on: its glow goes into the game's
            // light picture, the way the game puts the glow of its lasers there (nothing stops this light - it hangs
            // in the air with the unit).
            if(lights){
                // One unbroken shape: the streak itself, wider and soft, reaching a little past its tip - with a round
                // glow at the nozzle. (Three round glows along the streak, as at first, showed as three: the maker saw
                // the light "split at every joint".)
                float wide = Math.max(rad * 2.6f, length * 0.16f), reach = length * 1.15f + rad * 2f, lit = 0.75f * bright;
                Vars.renderer.lights.add(() -> {
                    streak(ex, ey, out, reach, 1f, bend, lean, 0f, 0f, wide, 0.2f, team.r, team.g, team.b, team.r, team.g, team.b, lit);
                    Draw.color(team, lit);
                    Draw.rect(soft, ex, ey, rad * 5f, rad * 5f);
                    Draw.color();
                });
            }
        }

        /**
         * What a kind of unit may have laid on its streaks. All of it is light and is added to what is there.
         *   rings   - rings around the streak, seen from the side as upright ovals: they leave the nozzle one after
         *             another, widen a little and fade (the maker's first sketch);
         *   arcs    - the far half of such rings only, heavier: bows running out ahead of the gas (the second sketch);
         *   pulses  - beads of light running down the streak.
         * They sit on the middle line of the streak, so they follow its curve, and they take up the first half of it.
         */
        private static void ornaments(int style, float ex, float ey, float out, float rad, float length, float bend, float lean, float push, float time, float seed, Color team, Color hot, float bright){
            Draw.blend(Blending.additive);
            int count = style == arcs ? 3 : style == pulses ? 3 : 4;
            // how far out they run: half the streak, but never so short that they sit on top of one another
            float reach = Math.max(length * (style == pulses ? 0.9f : 0.52f), rad * 2.6f) / Math.max(length, 0.001f);
            float flow = time * (style == pulses ? 0.02f : 0.012f) * (0.6f + 0.8f * push) + seed * 0.37f;
            flow -= (float)Math.floor(flow);
            for(int k = 0; k < count; k++){
                // 0 = just out of the nozzle .. 1 = gone
                float s = (k + flow) / count, at = 0.05f + s * reach;
                point(ex, ey, out, length, bend, lean, at);
                float cx = tipX, cy = tipY, angle = out - bend * at;
                // fainter while the unit hovers: the streak is short then, and they crowd at the nozzle
                float alpha = bright * (float)Math.pow(1f - s, 1.25) * Mathf.clamp(s * 7f) * (0.45f + 0.55f * Mathf.clamp(push));
                float mix = 0.25f + 0.6f * s;
                Draw.color(Mathf.lerp(hot.r, team.r, mix), Mathf.lerp(hot.g, team.g, mix), Mathf.lerp(hot.b, team.b, mix), alpha);
                if(style == pulses){
                    float size = rad * (2.2f - 1.2f * s);
                    Draw.rect(soft, cx, cy, size * 1.5f, size, angle);
                    continue;
                }
                // An oval: "along" the streak it is narrow (a ring seen from the side), across it wide. Drawn as a
                // band with a soft glow on either side of it: the first try was a thin line of sixteen straight pieces
                // - it came out angular, broken at the joints and without any glow, and the maker put it next to the
                // sketch: "it looks a bit of a pity".
                float across = rad * (1.75f + 0.35f * s), along = rad * (style == arcs ? 0.95f + 0.25f * s : 0.56f + 0.12f * s);
                float dx = Mathf.cosDeg(angle), dy = Mathf.sinDeg(angle);
                int steps = style == arcs ? 16 : 28;
                float from = style == arcs ? -80f : 0f, sweep = style == arcs ? 160f : 360f;
                // the band itself: white-hot, a little of the flame's colour further out; the glow: the flame's colour
                float whiten = 0.12f + 0.45f * s;
                float core = Color.toFloatBits(Mathf.lerp(hot.r, team.r, whiten), Mathf.lerp(hot.g, team.g, whiten), Mathf.lerp(hot.b, team.b, whiten), alpha);
                float glow = Color.toFloatBits(team.r, team.g, team.b, alpha * 0.55f), none = Color.toFloatBits(team.r, team.g, team.b, 0f);
                float pox = 0f, poy = 0f, pix = 0f, piy = 0f, pgx = 0f, pgy = 0f, phx = 0f, phy = 0f;
                for(int i = 0; i <= steps; i++){
                    float part = i / (float)steps, a = from + sweep * part;
                    float ca = Mathf.cosDeg(a), sa = Mathf.sinDeg(a);
                    // the point on the oval, and the way straight out of it there
                    float lx = ca * along, ly = sa * across, ox = ca / along, oy = sa / across, len = Mathf.len(ox, oy);
                    ox /= len;
                    oy /= len;
                    // a bow is heaviest in its middle and runs out to nothing at both ends; a ring is the same all round
                    float heavy = style == arcs ? 0.4f * (1f - 0.5f * s) * Mathf.sin(part * Mathf.pi) : 0.22f * (1f - 0.3f * s);
                    float half = rad * heavy * 0.5f, soft = half + rad * 0.42f;
                    float x = cx + dx * lx - dy * ly, y = cy + dy * lx + dx * ly;
                    float nx = dx * ox - dy * oy, ny = dy * ox + dx * oy;
                    float outX = x + nx * half, outY = y + ny * half, inX = x - nx * half, inY = y - ny * half;
                    float gX = x + nx * soft, gY = y + ny * soft, hX = x - nx * soft, hY = y - ny * soft;
                    if(i > 0){
                        Fill.quad(pgx, pgy, none, pox, poy, glow, outX, outY, glow, gX, gY, none);
                        Fill.quad(pox, poy, core, pix, piy, core, inX, inY, core, outX, outY, core);
                        Fill.quad(pix, piy, glow, phx, phy, none, hX, hY, none, inX, inY, glow);
                    }
                    pox = outX;
                    poy = outY;
                    pix = inX;
                    piy = inY;
                    pgx = gX;
                    pgy = gY;
                    phx = hX;
                    phy = hY;
                }
            }
            Draw.blend();
            Draw.color();
        }

        /**
         * One engine burning: the streak, the glow along it, the white-hot line in it, and the nozzle.
         * @param thick how many times as wide as at rest the streak is (the nozzle stays as it is)
         */
        private static void burn(float ex, float ey, float out, float rad, float length, float thick, float bend, float lean, float ripple, float phase, Color team, Color hot, float bright){
            // The root of the flame: a full, soft-edged ball of the flame's colour where the game had its disc. An
            // engine sits in a notch of the hull, and the game's disc filled that notch; the wedge alone is narrower
            // there and left the ground showing on either side of its root - on a dark map two black gaps between
            // hull and flame, as if the flame were cut off from the unit. (The soft sprite twice: once it is only half
            // full at the width of the disc.)
            Draw.color(Mathf.lerp(team.r, hot.r, 0.3f), Mathf.lerp(team.g, hot.g, 0.3f), Mathf.lerp(team.b, hot.b, 0.3f), 0.9f * bright);
            Draw.rect(soft, ex, ey, rad * 3.1f, rad * 3.1f);
            Draw.rect(soft, ex, ey, rad * 3.1f, rad * 3.1f);
            // The streak in the team's colour, laid over what is there: on bright ground light that is only added
            // washes out to white, and the colour would not show.
            streak(ex, ey, out, length, 1f, bend, lean, ripple, phase, rad * 0.78f * thick, 0.5f, team.r, team.g, team.b,
                Mathf.lerp(team.r, hot.r, 0.35f), Mathf.lerp(team.g, hot.g, 0.35f), Mathf.lerp(team.b, hot.b, 0.35f), 0.8f * bright);

            // everything else is light and is added
            Draw.blend(Blending.additive);
            // the glow along the streak: the same streak again, wide and faint, so it follows every curve
            // (one soft sprite pulled long lay straight from nozzle to tip, beside a streak that curved)
            streak(ex, ey, out, length, 1f, bend, lean, ripple, phase, rad * 1.9f * thick, 0f, team.r, team.g, team.b, team.r, team.g, team.b, 0.3f * bright);
            // the white-hot line inside
            streak(ex, ey, out, length, 0.7f, bend, lean, ripple, phase, rad * 0.4f * thick, 0.45f, hot.r, hot.g, hot.b, hot.r, hot.g, hot.b, 0.95f * bright);
            // the nozzle: a halo in the team's colour and a white point in it
            Draw.color(team, 0.4f * bright);
            Draw.rect(soft, ex, ey, rad * 3.6f, rad * 3.6f);
            Draw.color(hot, 0.95f * bright);
            Draw.rect(soft, ex, ey, rad * 1.5f, rad * 1.5f);
            Draw.blend();
            Draw.color();
        }

        /** The place on the middle line of the streak the share "at" of its length from the nozzle (without the ripple), into tipX, tipY. */
        private static void point(float ex, float ey, float out, float length, float bend, float lean, float at){
            float angle = out - bend * at, side = lean * (float)Math.pow(at, 1.6);
            tipX = ex + Mathf.cosDeg(angle) * length * at - Mathf.sinDeg(out) * side;
            tipY = ey + Mathf.sinDeg(angle) * length * at + Mathf.cosDeg(out) * side;
        }

        /**
         * A streak of light from the nozzle to its tip, shaped like a wedge: as wide as "wide" at the nozzle and
         * narrowing evenly to a point at the tip. Across, it is full over the middle share "solid" of its width and
         * fades to nothing from there to its two edges; along, it fades out towards the tip. Leaves the place of its
         * end in tipX, tipY.
         * @param out the way the engine points, degrees
         * @param length the length of the whole streak; span: how much of it this one covers (the white line inside is shorter)
         * @param bend by how many degrees the far end of the whole streak is turned back (the unit is turning)
         * @param lean how far the far end is pushed to the left of the exhaust (the unit drifts to the right), world units
         * @param ripple how far a wave running down the streak throws its far end to either side
         * @param wide half its width at the nozzle
         * @param solid the share of its width that is full (0 = only its middle line, as a glow is)
         * @param r the colour of the streak; r0: its colour right at the nozzle
         */
        private static void streak(float ex, float ey, float out, float length, float span, float bend, float lean, float ripple, float phase, float wide, float solid,
                                   float r, float g, float b, float r0, float g0, float b0, float alpha){
            int steps = 9;
            float sx = -Mathf.sinDeg(out), sy = Mathf.cosDeg(out);
            float px = 0f, py = 0f, pw = 0f, pMid = 0f, pRim = 0f, pnx = 0f, pny = 0f;
            for(int i = 0; i <= steps; i++){
                // more steps near the nozzle, where the light changes fastest
                float t = i / (float)steps;
                t *= t;
                // "at" runs along the whole streak, t along this one
                float at = t * span, left = 1f - t, mix = Mathf.clamp(t * 4f);
                float angle = out - bend * at, dx = Mathf.cosDeg(angle), dy = Mathf.sinDeg(angle);
                float side = lean * (float)Math.pow(at, 1.6) + ripple * at * Mathf.sin(phase - at * 6f);
                float cx = ex + dx * length * at + sx * side, cy = ey + dy * length * at + sy * side;
                // a wedge: the width falls evenly to nothing at the tip
                float nx = -dy, ny = dx, w = wide * left;
                float cr = Mathf.lerp(r0, r, mix), cg = Mathf.lerp(g0, g, mix), cb = Mathf.lerp(b0, b, mix);
                float mid = Color.toFloatBits(cr, cg, cb, alpha * (float)Math.pow(left, 0.75)), rim = Color.toFloatBits(cr, cg, cb, 0f);
                if(i > 0){
                    float ps = pw * solid, ws = w * solid;
                    // the two soft edges
                    Fill.quad(px + pnx * pw, py + pny * pw, pRim, px + pnx * ps, py + pny * ps, pMid, cx + nx * ws, cy + ny * ws, mid, cx + nx * w, cy + ny * w, rim);
                    Fill.quad(px - pnx * ps, py - pny * ps, pMid, px - pnx * pw, py - pny * pw, pRim, cx - nx * w, cy - ny * w, rim, cx - nx * ws, cy - ny * ws, mid);
                    // the full middle
                    if(solid > 0.001f) Fill.quad(px + pnx * ps, py + pny * ps, pMid, px - pnx * ps, py - pny * ps, pMid, cx - nx * ws, cy - ny * ws, mid, cx + nx * ws, cy + ny * ws, mid);
                }
                px = cx;
                py = cy;
                pw = w;
                pMid = mid;
                pRim = rim;
                pnx = nx;
                pny = ny;
            }
            tipX = px;
            tipY = py;
        }
    }
}
