package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import arc.graphics.g2d.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;

/**
 * Weather on the ground (the game shows weather only as falling particles), drawn in the ground's own layer (FloorFx):
 *   rain       the ground darkens, wet; puddles gather here and there, shining, with rings where the drops fall
 *   snow       snow settles while it snows, first on what stands higher, then in broad drifts; melts slowly after
 *   sandstorm  sand blows over the ground in fast streaks downwind, the light turns dusty; sand drifts pile up
 *              along the wind and are blown away slowly after
 * How much of each weather there is is read from the game's weather states (Groups.weather) every frame. Snow and
 * sand build up over time and go again over time; rain is there at once.
 */
public class Skies{
    /** How much rain there is now, 0 .. 1. */
    static float rain;
    /** How much it snows / how much sand blows now, 0 .. 1. */
    static float snowing, blowing;
    /** How much snow / sand lies on the ground, 0 .. 1 (builds up and goes away over time). */
    static float snow, sand;
    /** How thick a spore storm drifts now, and how many spores have settled on the ground and the buildings, 0 .. 1. */
    static float sporing, spores;
    /** How thick the fog of a fog weather is now, 0 .. 1 (it lies in the air only). */
    static float fogging;
    /** How much water stands in the puddles on the ground, 0 .. 1: fills while it rains, dries up slowly after it. */
    static float puddles;
    /** Seconds for the puddles to fill at full rain, and to dry up after it. */
    static final float puddleFill = 40f, puddleDry = 150f;
    /** Where the sandstorm blows towards, length 1. */
    static float windX = 0.94f, windY = 0.34f;

    /** Seconds for snow / sand to cover the ground fully at full strength, and to go away again. */
    static final float settle = 40f, melt = 90f;

    private static long lastFrame = -1;
    private static Scene scene;
    private static final arc.struct.Seq<WeatherState> broken = new arc.struct.Seq<>();
    private static boolean warnedBroken;

    /** The solid blocks as the sand piles see them: eased towards the real ones over a few seconds (two, swapped). */
    private static FrameBuffer pileA, pileB;
    private static Shader ease;
    private static boolean fresh = true;
    private static float lastEase;
    private static Texture piles;
    /**
     * The shape of the sand piles over the view, worked out once a frame at a few texels a tile instead of for every
     * pixel of the ground (that was 34 readings of the blocks a pixel - the heaviest part of a sandstorm, "why is it the
     * heaviest? that's quite a lot"). The shape is smooth (it comes from the blocks, one texel a tile, filtered), so
     * the ground reads it filtered and lays the same noise over it as before.
     */
    private static FrameBuffer field;
    private static Shader fieldShader;
    private static final float[] fieldRect = new float[4];
    private static boolean fieldReady;

    static void init(Scene from){
        scene = from;
        try{
            ease = new Shader(Vars.tree.get("shaders/screenspace.vert").readString(),
                "varying vec2 v_texCoords;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform sampler2D u_solid;\n" +
                "uniform vec2 u_rate;\n" +
                "void main(){\n" +
                "    vec4 was = texture2D(u_texture, v_texCoords), now = texture2D(u_solid, v_texCoords);\n" +
                // a pile grows slowly where a block now stands, and blows away slowly where one is gone
                "    gl_FragColor = mix(was, now, now.r > was.r ? u_rate.x : u_rate.y);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] easing of the sand piles unavailable", t);
        }
        try{
            // the shape of the sand piles over the view (see easePiles): u_texture = the eased blocks
            fieldShader = new Shader(Vars.tree.get("shaders/screenspace.vert").readString(),
                "varying vec2 v_texCoords;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform vec4 u_rect;\n" +
                "uniform vec2 u_tiles;\n" +
                "uniform vec2 u_wind;\n" +
                "float solidAt(vec2 p){ return texture2D(u_texture, (p + 4.0) / (8.0 * u_tiles)).r; }\n" +
                "void main(){\n" +
                "    vec2 world = u_rect.xy + v_texCoords * u_rect.zw;\n" +
                "    vec2 w = u_wind, side = vec2(-w.y, w.x);\n" +
                "    float cap = 0.0, tails = 0.0, lee = 0.0;\n" +
                "    for(int k = 0; k < 5; k++){\n" +
                "        float ang = (float(k) - 2.0) * 0.45;\n" +
                "        vec2 dv = w * cos(ang) + side * sin(ang);\n" +
                "        for(int m = 0; m < 2; m++){\n" +
                "            float r = 3.0 + float(m) * 5.0;\n" +
                "            cap = max(cap, solidAt(world + dv * r) * (1.0 - r / 15.0));\n" +
                "        }\n" +
                "    }\n" +
                "    for(int m = 0; m < 2; m++){\n" +
                "        float r = 4.0 + float(m) * 4.5;\n" +
                "        for(int k = 0; k < 5; k++){\n" +
                "            float a = -4.0 + float(k) * 7.0;\n" +
                "            float fall = (1.0 - r / 15.0) * (1.0 - max(a, 0.0) / 34.0);\n" +
                "            tails = max(tails, fall * max(solidAt(world + side * r - w * a), solidAt(world - side * r - w * a)));\n" +
                "        }\n" +
                "    }\n" +
                "    for(int k = 0; k < 4; k++){\n" +
                "        float a = 3.0 + float(k) * 7.0;\n" +
                "        lee = max(lee, solidAt(world - w * a));\n" +
                "    }\n" +
                "    gl_FragColor = vec4(max(cap, min(tails * 1.3, 1.0) * (1.0 - lee)), 0.0, 0.0, 1.0);\n" +
                "}\n");
        }catch(Throwable t){
            fieldShader = null;
            Log.err("[mshaders] sand pile field unavailable, worked out per pixel", t);
        }
        // before the frame is drawn (the ground layer reads it while it is drawn)
        Events.run(Trigger.preDraw, Skies::easePiles);
        // the game's sandstorm at a quarter size (drawLowSand)
        Events.run(Trigger.draw, Skies::queueLowSand);
        // A weather state with no weather - seen in a multiplayer game, sent by a host whose game differs (another
        // version, or a mod with weathers of its own): the game itself then fails at Logic.update (w.weather.attrs)
        // and closes. Such a state is taken out just before, every update; the host sends it again if it means it.
        Events.run(Trigger.beforeGameUpdate, () -> {
            broken.clear();
            for(WeatherState w : Groups.weather) if(w.weather == null) broken.add(w);
            if(broken.isEmpty()) return;
            if(!warnedBroken){
                warnedBroken = true;
                Log.warn("[mshaders] a weather state without a weather was taken out (it would have stopped the game); from the server?");
            }
            for(WeatherState w : broken) w.remove();
        });
        // what lay on the last map does not come along to the next one
        Events.on(WorldLoadEvent.class, e -> {
            snow = 0f;
            sand = 0f;
            puddles = 0f;
            spores = 0f;
            fresh = true;
        });
    }

    static void update(){
        long frame = Core.graphics.getFrameId();
        if(frame == lastFrame) return;
        lastFrame = frame;
        thinStorm();

        float r = 0f, s = 0f, b = 0f, sp = 0f, fg = 0f;
        if(ShaderMod.inWorld()){
            for(WeatherState w : Groups.weather){
                float amount = w.opacity * Math.max(w.intensity, 0.5f);
                if(w.weather == Weathers.rain) r = Math.max(r, amount);
                else if(w.weather == Weathers.snow) s = Math.max(s, amount);
                else if(w.weather == Weathers.sporestorm && amount >= sp){
                    sp = amount;
                    // how the game's own spores fly (Weather.drawParticles: wind * baseSpeed * intensity * 0.5..1 a tick),
                    // so the murk and the glowing spores go with them - at first they went their own way, up and right
                    // ("it is different from the direction of the spore storm")
                    if(w.weather instanceof mindustry.type.weather.ParticleWeather pw){
                        if(pw.useWindVector){
                            float k = pw.baseSpeed * w.intensity;
                            sporeVX = w.windVector.x * k;
                            sporeVY = w.windVector.y * k;
                        }else{
                            sporeVX = pw.xspeed;
                            sporeVY = pw.yspeed;
                        }
                    }
                }
                else if(w.weather == Weathers.fog) fg = Math.max(fg, amount);
                else if(w.weather == Weathers.sandstorm && amount > b){
                    b = amount;
                    if(!w.windVector.isZero()){
                        float len = w.windVector.len();
                        windX = w.windVector.x / len;
                        windY = w.windVector.y / len;
                    }
                }
            }
        }
        rain = Mathf.clamp(r);
        // (the test kit's rain counts as rain from here on: the puddles below fill from it too)
        int forcedRain = Core.settings.getInt("mshaders-test-rain", -1);
        if(forcedRain >= 0) rain = forcedRain / 100f;
        snowing = Mathf.clamp(s);
        blowing = Mathf.clamp(b);
        sporing = Mathf.clamp(sp);
        fogging = Mathf.clamp(fg);
        int forcedFog = Core.settings.getInt("mshaders-test-fog", -1);
        if(forcedFog >= 0) fogging = forcedFog / 100f;

        // build up while it falls (up to how strongly it falls), go away slowly when it has stopped
        float dt = ShaderMod.inWorld() && !Vars.state.isPaused() ? Time.delta / 60f : 0f;
        snow = towards(snow, snowing, dt);
        sand = towards(sand, blowing, dt);
        spores = towards(spores, sporing, dt);
        // (as deep as it rains; when it has stopped they stay and dry up slowly - they used to be gone the moment it stopped)
        if(rain > puddles) puddles = Math.min(rain, puddles + dt * rain / puddleFill);
        else puddles = Math.max(puddles - dt / puddleDry, rain > 0.01f ? Math.min(rain, puddles) : 0f);
        if(!ShaderMod.inWorld()){
            snow = 0f;
            sand = 0f;
            puddles = 0f;
            spores = 0f;
        }

        // the scene behind the menu on a snowy map: the snow lies as after a long snowfall (time stands still there, so
        // it would never settle by itself)
        float lying = MenuWorld.snowLying();
        if(lying > 0f) snow = Math.max(snow, lying);

        // forced values for the test kit: the weather and what lies on the ground both at once
        int forced = Core.settings.getInt("mshaders-test-puddles", -1);
        if(forced >= 0) puddles = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-snow", -1);
        if(forced >= 0) snow = snowing = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-spore", -1);
        if(forced >= 0) spores = sporing = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-sand", -1);
        if(forced >= 0) sand = blowing = forced / 100f;
        forced = Core.settings.getInt("mshaders-test-wind", -1);
        if(forced >= 0){
            windX = Mathf.cosDeg(forced);
            windY = Mathf.sinDeg(forced);
        }
    }

    private static float towards(float lying, float falling, float dt){
        if(falling > lying) return Math.min(falling, lying + dt * falling / settle);
        return Math.max(falling, lying - dt / melt);
    }

    /**
     * How thick the dust of a sandstorm hangs in the air now, 0 .. 1. The sun comes through it dimmed and scattered:
     * the glint of metal and the sparkle of water are weaker, sun shadows fainter and softer, and a haze drifts with the
     * wind (players: "less glitter while the sand blows" - "if only the colour turns yellow, that's just yellow dust").
     */
    static float dust(){
        update();
        return Math.min(blowing * sandK(), 1f) * airShown();
    }

    /** How much of the sun gets through that dust for glints and sparkles: 1 = all, down to 0.35 in a full storm. */
    static float sunThrough(){
        // (spores in the air dim it too, less than sand)
        // (and fog more than either: the sun is a pale disc behind it)
        return Math.max(1f - 0.65f * dust() - 0.35f * sporeAir() - 0.55f * fogAir(), 0.2f);
    }

    /**
     * How much of the big dust of a sandstorm shows at this zoom: all of it zoomed all the way out, about a third
     * zoomed all the way in. Seen from close up there is less dust between the eye and the ground ("the closer the
     * camera, the less of the big sandstorm - keep the small sand blowing"): the haze below and the game's own big
     * clouds of dust (the sandstorm's noise layer and its large particles) thin out; the sand streaming over the ground
     * (mshaders-ground.frag) stays as it is, and so do the dimmed glints and fainter shadows, which come from the sun.
     */
    static float bigDust(){
        float min = Vars.renderer.minScale(), max = Vars.renderer.maxScale(), now = Vars.renderer.getDisplayScale();
        float close = max > min ? Mathf.clamp((now - min) / (max - min)) : 0f;
        return Mathf.lerp(1f, 0.33f, Interp.smooth.apply(close));
    }

    /** The game's weathers as they were made: how many drops or bits each draws, whether it draws its noise layer, and
     *  (the sandstorm) how strongly its noise and its particles show. Kept, so a setting can be put back. */
    private static final arc.struct.ObjectMap<mindustry.type.Weather, float[]> made = new arc.struct.ObjectMap<>();

    /**
     * How the game draws its weathers this frame (only how they are drawn: what a weather does to the game goes by its
     * own strength, not these): a weather set to "off" draws nothing; the sandstorm's big dust thins as the camera
     * comes in (bigDust), unless it is drawn "as the game has it". Once a frame.
     */
    private static void thinStorm(){
        draw(Weathers.rain, ShaderMod.weatherRain, false);
        draw(Weathers.snow, ShaderMod.weatherSnow, false);
        draw(Weathers.sandstorm, ShaderMod.weatherSand, true);
        // (the spore storm thins as the sandstorm does: "spores the same - less of the game's own effect the closer you look")
        draw(Weathers.sporestorm, ShaderMod.weatherSpore, true);
        // (the fog: off / as the game has it / this mod's fog)
        draw(Weathers.fog, ShaderMod.weatherFog, false);
    }

    private static void draw(mindustry.type.Weather weather, String key, boolean thins){
        if(weather == null) return;
        float[] was = made.get(weather);
        if(was == null){
            float density = weather instanceof mindustry.type.weather.ParticleWeather p ? p.density : weather instanceof mindustry.type.weather.RainWeather r ? r.density : 0f;
            boolean noise = weather instanceof mindustry.type.weather.ParticleWeather p && p.drawNoise;
            float maxAlpha = weather instanceof mindustry.type.weather.ParticleWeather p ? p.maxAlpha : 0f;
            boolean particles = weather instanceof mindustry.type.weather.ParticleWeather p && p.drawParticles;
            made.put(weather, was = new float[]{density, noise ? 1f : 0f, weather.opacityMultiplier, maxAlpha, particles ? 1f : 0f});
        }
        int v = level(key);
        boolean off = v <= levelOff && ShaderMod.inWorld();
        float f = thins && v >= 0 && Core.settings.getBool(ShaderMod.weatherOn, true) && ShaderMod.inWorld() ? bigDust() : 1f;
        if(weather instanceof mindustry.type.weather.ParticleWeather p){
            // (the game draws  screen area / density  bits: off = a density so large that comes to none. 0 divided by
            // zero - it tried to draw countless drops, the screen turned to rain and the game all but froze)
            p.density = off ? 1e30f : was[0];
            p.drawNoise = !off && was[1] > 0.5f;
            p.drawParticles = was[4] > 0.5f;
            p.maxAlpha = was[3] * f;
            // The sandstorm's big dust is drawn by drawLowSand instead, the same drawing at a quarter of the screen's
            // size: the game's own draw is switched off here and on there
            // (the fog too: three noise layers over the whole view, as blurred)
            int low = lowIndex(weather);
            if(low >= 0 && lowSand()){
                lowNoise[low] = p.drawNoise;
                lowParticles[low] = p.drawParticles;
                p.drawNoise = false;
                p.drawParticles = false;
            }
        }else if(weather instanceof mindustry.type.weather.RainWeather r){
            r.density = off ? 1e30f : was[0];
        }
        weather.opacityMultiplier = was[2] * f;
    }

    /**
     * The game's sandstorm, drawn the same but cheaper ("let's just optimise the original game's sandstorm"): its
     * particles are huge soft blots (70 to 140 world units, at most 12% seen) - some 120 of them over each other cover
     * the screen many times over, tens of millions of pixels a frame for a faint haze - and one noise layer over the
     * whole view. All of it is that blurred already, so it is drawn by the game's own code (Weather.drawOver: the same
     * particles, places, colours and strength) into a picture a quarter of the screen's size, which is then laid over
     * the frame, filtered, where the game would have drawn it (Layer.weather).
     */
    private static FrameBuffer lowSandBuffer;
    /** What each weather drawn at a quarter size (sandstorm, fog) would draw itself: its noise layers, its particles. */
    private static final boolean[] lowNoise = new boolean[2], lowParticles = new boolean[2];

    private static int lowIndex(mindustry.type.Weather w){
        if(w == null) return -1;
        // (the fog was tried too - three noise layers over the view - and drawn the same at a quarter size, but no
        // faster: 516 / 515 fps; a few layers over the screen cost little. Its slot stays for another weather.)
        return w == Weathers.sandstorm ? 0 : -1;
    }
    private static final Blending gather = new Blending(Gl.srcAlpha, Gl.oneMinusSrcAlpha, Gl.one, Gl.oneMinusSrcAlpha);
    private static final Blending laidOver = new Blending(Gl.one, Gl.oneMinusSrcAlpha);
    private static final Runnable drawLowSand = Skies::drawLowSand;

    /** Is the sandstorm drawn at a quarter size? (The test kit can turn it off to compare.) */
    static boolean lowSand(){
        return ShaderMod.inWorld() && !Core.settings.getBool("mshaders-test-nolowsand", false);
    }

    /** Queued with the frame (Trigger.draw) while a sandstorm is on. */
    private static void queueLowSand(){
        if(!lowSand()) return;
        // the same gate as the game's own weather drawing (WeatherState.draw): no weather in a map screenshot
        // (drawWeather off) nor while a core lands or launches (weatherAlpha 0)
        if(!Core.settings.getBool("showweather", true) || !Vars.renderer.drawWeather || Vars.renderer.weatherAlpha <= 1e-4f) return;
        boolean any = false;
        for(WeatherState w : Groups.weather){
            int i = lowIndex(w.weather);
            if(i >= 0 && (lowNoise[i] || lowParticles[i])) any = true;
        }
        if(!any) return;
        float z = Draw.z();
        Draw.draw(mindustry.graphics.Layer.weather + 0.001f, drawLowSand);
        Draw.z(z);
    }

    private static void drawLowSand(){
        int w = Math.max(Core.graphics.getWidth() / 4, 8), h = Math.max(Core.graphics.getHeight() / 4, 8);
        if(lowSandBuffer == null) lowSandBuffer = new FrameBuffer(w, h);
        else if(lowSandBuffer.getWidth() != w || lowSandBuffer.getHeight() != h) lowSandBuffer.resize(w, h);
        lowSandBuffer.getTexture().setFilter(Texture.TextureFilter.linear);
        Draw.flush();
        Blending before = Draw.getBlend();
        lowSandBuffer.begin(Color.clear);
        // colour as it would land on the frame, coverage added up as on the frame (so it can be laid over as it is)
        Draw.blend(gather);
        // each such weather by the game's own drawing, in the order the game would draw them
        for(WeatherState s : Groups.weather){
            int i = lowIndex(s.weather);
            if(i < 0 || !(s.weather instanceof mindustry.type.weather.ParticleWeather p) || !(lowNoise[i] || lowParticles[i])) continue;
            p.drawNoise = lowNoise[i];
            p.drawParticles = lowParticles[i];
            try{
                p.drawOver(s);
                Draw.flush();
            }finally{
                p.drawNoise = false;
                p.drawParticles = false;
            }
        }
        lowSandBuffer.end();
        Draw.blend(laidOver);
        Draw.color();
        Draw.rect(Draw.wrap(lowSandBuffer.getTexture()), Core.camera.position.x, Core.camera.position.y, Core.camera.width, -Core.camera.height);
        Draw.flush();
        Draw.blend(before);
        Draw.color();
    }

    /** How far the dust haze has blown, world units (game time). */
    private static float dustX, dustY, dustTime, sporeX, sporeY, fogX, fogY;
    /** How fast the game's spores fly, world units a tick (until a storm says otherwise: slowly up and right). */
    private static float sporeVX = 0.15f, sporeVY = 0.07f;

    /** For the last step of the frame (mshaders-post.frag): the dust haze of a sandstorm, blown along with the wind. */
    static void applyComposite(arc.graphics.gl.Shader s){
        float d = dust();
        float time = Time.time, dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(time - dustTime, 0f), 30f);
        dustTime = time;
        // some 45 world units a second, with the wind of the storm
        dustX += windX * dt / 60f * 45f;
        dustY += windY * dt / 60f * 45f;
        if(s.hasUniform("u_dust")) s.setUniformf("u_dust", d * bigDust());
        if(s.hasUniform("u_dustShift")) s.setUniformf("u_dustShift", dustX, dustY);
        // the spores hanging in the air: a slow violet murk (thinning as the camera comes in, as the dust does), and
        // a few of them glowing, most of all at night
        float sp = sporeAir();
        // with the game's spores (world units a tick, see update), a little slower than the average of them (0.75):
        // the murk is a haze further off, the glowing ones drift at 1.4 times this (mshaders-post.frag)
        sporeX += sporeVX * dt * 0.55f;
        sporeY += sporeVY * dt * 0.55f;
        if(s.hasUniform("u_spore")) s.setUniformf("u_spore", sp * bigDust());
        if(s.hasUniform("u_sporeGlow")) s.setUniformf("u_sporeGlow", sp);
        if(s.hasUniform("u_sporeShift")) s.setUniformf("u_sporeShift", sporeX, sporeY);
        // the fog: lying over everything, creeping slowly along (some 3 world units a second, as the game's layers)
        fogX += dt / 60f * 3f;
        fogY += dt / 60f * 0.6f;
        if(s.hasUniform("u_fog")) s.setUniformf("u_fog", fogAir());
        if(s.hasUniform("u_fogShift")) s.setUniformf("u_fogShift", fogX, fogY);
    }

    /**
     * Each weather has a setting of its own (rain, snow, sandstorm, spore storm): "off" (-20) - the weather is not drawn
     * at all, not even the game's own drops and dust (only how it looks: what it does to the game stays) -; "as the game
     * has it" (-10) - the game's own drawing, none of this mod's effects -; or 0 .. 200 % of this mod's effects on top.
     */
    static final int levelOff = -20, levelGame = -10;

    static int level(String key){
        return Core.settings.getInt(key, 100);
    }

    /** How strongly this mod's effects of that weather show: 0 for "off", "as the game has it", or with the weather effects off. */
    static float of(String key){
        if(!Core.settings.getBool(ShaderMod.weatherOn, true)) return 0f;
        int v = level(key);
        // (times "weather: strength", over all of them - its slider had been left reading nothing)
        return v < 0 ? 0f : v / 100f * ShaderMod.percent(ShaderMod.weatherStrength, 100);
    }

    /**
     * How much of what hangs in the air is seen: as the game draws its own weather (WeatherState.draw) - none with
     * "show weather" off, in a map screenshot (drawWeather off), nor while a core lands or launches (weatherAlpha 0,
     * coming back as it comes back). The fog stayed thick over the screen when the game's own had gone.
     */
    static float airShown(){
        if(!Core.settings.getBool("showweather", true) || Vars.renderer == null || !Vars.renderer.drawWeather) return 0f;
        return Mathf.clamp(Vars.renderer.weatherAlpha);
    }

    static float rainK(){ return of(ShaderMod.weatherRain); }
    static float snowK(){ return of(ShaderMod.weatherSnow); }
    static float sandK(){ return of(ShaderMod.weatherSand); }
    static float sporeK(){ return of(ShaderMod.weatherSpore); }

    /** How much lies on the ground (and the blocks, the units) of snow, of sand piled against the blocks, of spores: 0 .. 2. */
    static float snowPile(){ return ShaderMod.percent(ShaderMod.weatherSnowPile, 100); }
    static float sandPile(){ return ShaderMod.percent(ShaderMod.weatherSandPile, 100); }
    static float sporePile(){ return ShaderMod.percent(ShaderMod.weatherSporePile, 100); }

    /** How thick the spores hang in the air now (this mod's effects of the spore storm), 0 .. 1. */
    static float sporeAir(){
        update();
        return Math.min(sporing * sporeK(), 1f) * airShown();
    }

    static float fogK(){ return of(ShaderMod.weatherFog); }

    /** How much weaker the sun shadows are: dust in the air, fog (it lies between the sun and the ground). */
    static float shadowDim(){ return Math.min(0.45f * dust() + 0.45f * fogAir(), 0.7f); }

    /** How soft the sun shadows are at the least: the light comes through dust or fog from all sides. */
    static float shadowSoft(){ return Math.min(Math.max(dust() * 0.75f, fogAir() * 0.85f), 1f); }

    /** How thick this mod's fog is drawn: the fog weather by its setting, 0 .. 2 (above 1 the 100-200 % of the slider
     *  still thicken a full fog: at 1 they did nothing). */
    static float fogAir(){
        update();
        return Math.min(fogging * fogK(), 2f) * airShown();
    }

    /**
     * Eases the picture the sand piles are read from towards where the blocks stand now: a pile builds up over a few
     * seconds when a block is put down, and blows away over a few seconds when it is gone (at first the piles were read
     * from the blocks themselves and popped in and out at once - "a little unnatural").
     */
    private static void easePiles(){
        piles = null;
        update();
        if(sand <= 0f || scene == null || ease == null || !ShaderMod.inWorld()){
            if(pileA != null){
                pileA.dispose();
                pileB.dispose();
                pileA = pileB = null;
            }
            if(field != null){
                field.dispose();
                field = null;
            }
            fieldReady = false;
            return;
        }
        Gl.activeTexture(Gl.texture1);
        Texture solid = scene.heights();
        Gl.activeTexture(Gl.texture0);
        if(solid == null) return;
        int w = solid.width, h = solid.height;
        if(pileA == null){
            pileA = new FrameBuffer(w, h);
            pileB = new FrameBuffer(w, h);
            fresh = true;
        }
        if(pileA.getWidth() != w || pileA.getHeight() != h){
            pileA.resize(w, h);
            pileB.resize(w, h);
            fresh = true;
        }
        pileA.getTexture().setFilter(Texture.TextureFilter.linear);
        pileB.getTexture().setFilter(Texture.TextureFilter.linear);
        float dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(Time.time - lastEase, 0f), 30f);
        lastEase = Time.time;
        Draw.flush();
        solid.bind(1);
        Gl.activeTexture(Gl.texture0);
        ease.bind();
        ease.setUniformi("u_solid", 1);
        // grows in some 4 seconds, goes in some 5 (game time)
        if(fresh) ease.setUniformf("u_rate", 1f, 1f);
        else ease.setUniformf("u_rate", 1f - (float)Math.exp(-dt / 240f), 1f - (float)Math.exp(-dt / 300f));
        fresh = false;
        Blending.disabled.apply();
        pileB.begin();
        Draw.blit(pileA.getTexture(), ease);
        pileB.end();
        Blending.normal.apply();
        FrameBuffer t = pileA;
        pileA = pileB;
        pileB = t;
        piles = pileA.getTexture();
        makeField();
    }

    /** The shape of the piles over the view and a little around it (see field). */
    private static void makeField(){
        fieldReady = false;
        // (the test kit can turn it off, to compare with the shape worked out per pixel)
        if(fieldShader == null || piles == null || Core.settings.getBool("mshaders-test-nofield", false)) return;
        float margin = 16f;
        float x = Core.camera.position.x - Core.camera.width / 2f - margin, y = Core.camera.position.y - Core.camera.height / 2f - margin;
        float fw = Core.camera.width + margin * 2f, fh = Core.camera.height + margin * 2f;
        // a texel every 2 world units (4 a tile), but never more than half the screen's pixels across
        int w = Math.max(Math.min((int)Math.ceil(fw / 2f), Math.max(Core.graphics.getWidth() / 2, 16)), 16);
        int h = Math.max(Math.min((int)Math.ceil(fh / 2f), Math.max(Core.graphics.getHeight() / 2, 16)), 16);
        if(field == null) field = new FrameBuffer(w, h);
        else if(field.getWidth() != w || field.getHeight() != h) field.resize(w, h);
        field.getTexture().setFilter(Texture.TextureFilter.linear);
        fieldRect[0] = x;
        fieldRect[1] = y;
        fieldRect[2] = fw;
        fieldRect[3] = fh;
        fieldShader.bind();
        fieldShader.setUniformf("u_rect", x, y, fw, fh);
        fieldShader.setUniformf("u_tiles", Vars.world.width(), Vars.world.height());
        fieldShader.setUniformf("u_wind", windX, windY);
        Blending.disabled.apply();
        field.begin();
        Draw.blit(piles, fieldShader);
        field.end();
        Blending.normal.apply();
        fieldReady = true;
    }

    /** How much snow lies on the ground, 0 .. 1 (as the ground's layer has it: u_snow). */
    static float groundSnow(){
        update();
        return Math.min(snow * snowK() * snowPile(), 1f);
    }

    /** Sets the weather uniforms of the ground's layer. */
    static void applyGround(Shader s){
        update();
        float kr = rainK(), ks = snowK(), kd = sandK();
        if(s.hasUniform("u_rain")) s.setUniformf("u_rain", rain * kr);
        if(s.hasUniform("u_pool")) s.setUniformf("u_pool", Math.min(puddles * kr, 1f));
        if(s.hasUniform("u_snow")) s.setUniformf("u_snow", Math.min(snow * ks * snowPile(), 1f));
        if(s.hasUniform("u_sandPile")) s.setUniformf("u_sandPile", sandPile());
        if(s.hasUniform("u_sand")) s.setUniformf("u_sand", Math.min(sand * kd, 1f), Math.min(blowing * kd, 1f));
        if(s.hasUniform("u_wind")) s.setUniformf("u_wind", windX, windY);
        // spores settled on the ground (patchy, as moss)
        if(s.hasUniform("u_spore")) s.setUniformf("u_spore", Math.min(spores * sporeK() * sporePile(), 1f));
        SnowCover.applyGround(s);
        Tracks.applyGround(s);
        // where the blocks stand (Occluders: red = solid), for the sand that piles up against them
        // (heights() uploads the blocks placed or broken since the last frame, binding the picture to the active
        // texture unit to do it: with unit 0 active that put it in place of the ground layer's own picture for the
        // frame, and the whole screen flashed whenever a block was placed in a sandstorm. So unit 5 is made active
        // first, where it goes anyway.)
        Texture solid = sand > 0f ? piles : null;
        if(solid != null){
            Gl.activeTexture(Gl.texture5);
            solid.bind(5);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_solid")) s.setUniformi("u_solid", 5);
        if(s.hasUniform("u_hasSolid")) s.setUniformf("u_hasSolid", solid != null ? 1f : 0f);
        // the shape of the piles over the view, worked out once this frame (makeField): on unit 5 in place of the blocks,
        // which the ground then does not need (unit 7 is the tracks', Tracks.applyGround)
        boolean hasField = solid != null && fieldReady && field != null;
        if(hasField){
            Gl.activeTexture(Gl.texture5);
            field.getTexture().bind(5);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_pileField")) s.setUniformi("u_pileField", 5);
        if(s.hasUniform("u_hasField")) s.setUniformf("u_hasField", hasField ? 1f : 0f);
        if(s.hasUniform("u_fieldRect")) s.setUniformf("u_fieldRect", fieldRect[0], fieldRect[1], fieldRect[2], fieldRect[3]);
    }
}
