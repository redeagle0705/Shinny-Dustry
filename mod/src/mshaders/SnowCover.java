package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.graphics.gl.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.game.Teams.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.distribution.*;

/**
 * Snow on buildings: while snow lies on the ground (Skies), a thin layer of it settles on the buildings too, in
 * patches, first on what is drawn lighter (the tops of their shapes). On a hot building it melts - a working smelter,
 * a generator, a reactor, a turret that keeps firing, a block heated by hits (HeatHaze.heatOf, HitHeat) - and comes
 * back slowly once the building has cooled.
 */
public class SnowCover{
    private static Shader shader, sporeShader;
    /** How many spores of a spore storm lie on the buildings (the same patches and the same melting by heat: burnt off). */
    private static float sporeAmount;
    private static final Seq<Building> builds = new Seq<>();
    /** How far the snow on each building has melted, by position: 0 = all there .. 1 = gone. */
    private static final IntFloatMap melt = new IntFloatMap();
    /** How hard the spores on each building are burning right now, by position: 0 .. 1. */
    private static final IntFloatMap burning = new IntFloatMap();
    /** How far the spores on each building have burnt away: 0 = all there .. 1 = gone (they grow back over some 15 seconds). */
    private static final IntFloatMap burnt = new IntFloatMap();
    /** The thawed places on the ground around hot buildings, by position: x, y, reach, how far thawed. */
    private static final IntMap<float[]> spots = new IntMap<>();
    private static final IntSet selfHot = new IntSet();
    /** When each building put up during this game was finished (Time.time), by position. */
    private static final IntFloatMap builtAt = new IntFloatMap();
    private static final FloatSeq sources = new FloatSeq();
    private static long lastLook = -100;
    private static float lastTime, amount;
    /** How far a long snowfall has buried the blocks: 0 = the thin first cover .. 1 = white all over. */
    private static float deep;
    private static final Rect view = new Rect();
    private static final Runnable drawAll = SnowCover::drawAll;
    /** The buildings whose snow has melted, for the ground around them to thaw too (mshaders-ground.frag): x, y, reach, how far melted. */
    static final int warmKept = 16;
    private static final float[] warm = new float[warmKept * 4];
    private static int warmCount;

    /**
     * Where beams have melted the snow on the ground ("let lasers like the meltdown clear it too"): a picture of the map,
     * two pixels to a tile, that the beams of each frame are stamped into (Beams: stamp) and that slowly fades back
     * (the snow comes back over some 20 seconds). Two of them, swapped, as the sand piles (Skies).
     */
    private static FrameBuffer meltA, meltB;
    private static Shader fade;
    private static final FloatSeq stamps = new FloatSeq();
    private static float lastFade, fadeTime;
    private static Texture meltMap;
    private static final Mat proj = new Mat(), before = new Mat();

    /** A place a beam is melting the snow this frame: x, y, radius (world units), how much (0 .. 1). */
    static void stamp(float x, float y, float r, float a){
        if((amount > 0.01f || sporeAmount > 0.01f) && stamps.size < 4 * 600){
            stamps.add(x, y, r, a);
            beamStamp(x, y, r, a);
        }
        // spores on the ground go up in smoke where a beam sweeps over them
        // (by the time passed, not once a call: a beam stamps every frame, and at a high frame rate it smoked far more)
        if(sporeAmount > 0.03f && !Vars.state.isPaused() && Mathf.chanceDelta(0.04f * a * Math.min(r / 6f, 2f))) mindustry.content.Fx.fireSmoke.at(x + Mathf.range(r), y + Mathf.range(r));
    }

    /**
     * The beams' melting once more, a tile to a value, for the blocks (groundThaw): the picture above lives on the
     * graphics card and is not read back. Each stamp adds as the soft circle does there; it goes down by itself at the
     * same pace, from full to nothing in some 20 seconds (worked out when read, from the time it was last stamped).
     */
    private static float[] beamValue, beamTime;
    private static int beamW, beamH;

    private static void beamStamp(float x, float y, float r, float a){
        if(Vars.world == null || Vars.world.width() <= 0) return;
        int w = Vars.world.width(), h = Vars.world.height();
        if(beamValue == null || beamW != w || beamH != h){
            beamW = w; beamH = h;
            beamValue = new float[w * h];
            beamTime = new float[w * h];
        }
        float now = Time.time, t = Vars.tilesize;
        int x0 = Math.max((int)Math.floor((x - r) / t + 0.5f), 0), x1 = Math.min((int)Math.floor((x + r) / t + 0.5f), w - 1);
        int y0 = Math.max((int)Math.floor((y - r) / t + 0.5f), 0), y1 = Math.min((int)Math.floor((y + r) / t + 0.5f), h - 1);
        for(int ty = y0; ty <= y1; ty++) for(int tx = x0; tx <= x1; tx++){
            float d = Mathf.dst(tx * t, ty * t, x, y) / Math.max(r, 0.001f);
            if(d >= 1f) continue;
            int i = ty * w + tx;
            beamValue[i] = Math.min(beamAt(i, now) + a * (1f - d), 1f);
            beamTime[i] = now;
        }
    }

    private static float beamAt(int i, float now){
        return Math.max(beamValue[i] - Math.max(now - beamTime[i], 0f) / 1200f, 0f);
    }

    private static float smooth(float lo, float hi, float v){
        float x = Mathf.clamp((v - lo) / (hi - lo));
        return x * x * (3f - 2f * x);
    }

    /**
     * How far the ground under a block has thawed (mshaders-ground.frag thawAt, without its ragged edge): the rings
     * round hot buildings and where beams have swept. A block standing there loses its snow and its spores as the
     * ground does - at first only a block's own heat counted, and a conveyor stood covered in a ring of bare ground
     * ("if there is a block on the place the heat cleared, let it follow the ground").
     */
    private static float groundThaw(Building b){
        float thaw = 0f;
        for(var e : spots){
            float[] v = e.value;
            float d = Mathf.dst(b.x, b.y, v[0], v[1]) / Math.max(v[2], 0.001f);
            thaw = Math.max(thaw, v[3] * (1f - smooth(0.35f, 1f, d)));
        }
        if(beamValue != null && beamW == Vars.world.width() && beamH == Vars.world.height()){
            float now = Time.time;
            int s = b.block.size, o = -(s - 1) / 2, tx = b.tileX(), ty = b.tileY();
            for(int dy = 0; dy < s; dy++) for(int dx = 0; dx < s; dx++){
                int x = tx + o + dx, y = ty + o + dy;
                if(x < 0 || y < 0 || x >= beamW || y >= beamH) continue;
                thaw = Math.max(thaw, smooth(0.15f, 0.6f, beamAt(y * beamW + x, now)));
            }
        }
        return thaw;
    }

    private static final IntSet dulled = new IntSet(), dulling = new IntSet();

    /**
     * Spores settled on a block take its shine away where they lie, the more of them the more (at the most three
     * quarters): written into the gloss of its tiles, which the glint of the blocks without a map of their own reads
     * (mshaders-post.frag) - it shone through the violet clumps before ("does a block the spores touch get less
     * metallic, as in a sandstorm?" - the sandstorm only dims the sun for everything). Blocks with a map are covered
     * by the clumps themselves. Tiles no longer spored go back to their own gloss.
     */
    static void dull(Occluders occ){
        dulling.clear();
        if(sporeAmount > 0.01f && ShaderMod.inWorld()){
            for(int i = 0; i < builds.size; i++){
                Building b = builds.get(i);
                if(!b.isValid() || b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) continue;
                float age = Time.time - builtAt.get(b.pos(), -100000f);
                float a = sporeAmount * (1f - Math.max(burnt.get(b.pos(), 0f), ground.get(b.pos(), 0f))) * Mathf.clamp(age / 900f);
                if(a <= 0.01f) continue;
                // (0.45 = all the spores a block takes)
                float f = Math.min(a / 0.45f, 1f) * 0.75f;
                int s = b.block.size, o = -(s - 1) / 2, tx = b.tileX(), ty = b.tileY();
                for(int dy = 0; dy < s; dy++) for(int dx = 0; dx < s; dx++){
                    int x = tx + o + dx, y = ty + o + dy;
                    occ.dull(x, y, f);
                    dulling.add(Point2.pack(x, y));
                }
            }
        }
        for(var it = dulled.iterator(); it.hasNext;){
            int p = it.next();
            if(!dulling.contains(p)) occ.dull(Point2.x(p), Point2.y(p), 0f);
        }
        dulled.clear();
        dulled.addAll(dulling);
    }

    /** groundThaw of each building in view this frame, by position. */
    private static final IntFloatMap ground = new IntFloatMap();

    private static void updateMelt(){
        meltMap = null;
        if((amount <= 0.01f && sporeAmount <= 0.01f) || fade == null || !ShaderMod.inWorld()){
            stamps.clear();
            // (no snow: the picture is let go - kept, it held memory on every map without snow)
            if(meltA != null){
                meltA.dispose();
                meltB.dispose();
                meltA = meltB = null;
            }
            return;
        }
        int w = Vars.world.width() * 2, h = Vars.world.height() * 2;
        boolean fresh = false;
        if(meltA == null){
            meltA = new FrameBuffer(w, h);
            meltB = new FrameBuffer(w, h);
            fresh = true;
        }else if(meltA.getWidth() != w || meltA.getHeight() != h){
            meltA.resize(w, h);
            meltB.resize(w, h);
            fresh = true;
        }
        meltA.getTexture().setFilter(Texture.TextureFilter.linear);
        meltB.getTexture().setFilter(Texture.TextureFilter.linear);
        float dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(Time.time - lastFade, 0f), 30f);
        lastFade = Time.time;
        // The picture keeps 256 levels: a fade by a tiny share each frame rounded back to where it was, and the melted
        // ground never froze over again ("20 seconds have passed and it isn't covered"). So it goes down one level at
        // a time, by the time gathered: from full to nothing in some 20 seconds.
        fadeTime += dt;
        int levels = (int)(fadeTime * 255f / 1200f);
        fadeTime -= levels * 1200f / 255f;
        Draw.flush();
        // fade what is there towards nothing
        fade.bind();
        fade.setUniformf("u_step", fresh ? 1f : levels / 255f);
        Blending.disabled.apply();
        meltB.begin();
        Draw.blit(meltA.getTexture(), fade);
        // and stamp this frame's beams into it, in world units (the map's tiles: n covers n * 8 - 4 .. n * 8 + 4)
        if(stamps.size > 0){
            before.set(Draw.proj());
            Draw.proj(proj.setOrtho(-4f, -4f, Vars.world.width() * Vars.tilesize, Vars.world.height() * Vars.tilesize));
            Draw.sort(false);
            Draw.blend(Blending.additive);
            TextureRegion soft = Core.atlas.find("circle-shadow");
            for(int i = 0; i < stamps.size; i += 4){
                Draw.color(1f, 1f, 1f, stamps.items[i + 3]);
                if(soft.found()) Draw.rect(soft, stamps.items[i], stamps.items[i + 1], stamps.items[i + 2] * 2f, stamps.items[i + 2] * 2f);
            }
            Draw.flush();
            Draw.blend();
            Draw.color();
            Draw.sort(true);
            Draw.proj(before);
        }
        meltB.end();
        Blending.normal.apply();
        stamps.clear();
        FrameBuffer t = meltA;
        meltA = meltB;
        meltB = t;
        meltMap = meltA.getTexture();
    }


    /** Sets u_warm and the melt map for the ground's layer. */
    static void applyGround(Shader s){
        for(int i = warmCount * 4; i < warm.length; i++) warm[i] = 0f;
        if(s.hasUniform("u_warm[0]") || s.hasUniform("u_warm")) s.setUniform4fv("u_warm", warm, 0, warmKept * 4);
        if(meltMap != null){
            Gl.activeTexture(Gl.texture6);
            meltMap.bind(6);
            Gl.activeTexture(Gl.texture0);
        }
        if(s.hasUniform("u_melt")) s.setUniformi("u_melt", 6);
        if(s.hasUniform("u_hasMelt")) s.setUniformf("u_hasMelt", meltMap != null ? 1f : 0f);
    }

    public static void init(){
        try{
            shader = new Shader(Cutaway.VERT,
                "varying lowp vec4 v_color;\n" +
                "varying highp vec2 v_texCoords;\n" +
                "uniform highp sampler2D u_texture;\n" +
                "uniform vec2 u_campos;\n" +
                "uniform vec2 u_size;\n" +
                "uniform vec2 u_screen;\n" +
                "float hash(vec2 p){\n" +
                "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n" +
                "    p3 += dot(p3, p3.yzx + 33.33);\n" +
                "    return fract((p3.x + p3.y) * p3.z);\n" +
                "}\n" +
                "float noise(vec2 p){\n" +
                "    vec2 i = floor(p), f = fract(p);\n" +
                "    f = f * f * (3.0 - 2.0 * f);\n" +
                "    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);\n" +
                "}\n" +
                "uniform vec2 u_texSize;\n" +
                "uniform vec2 u_sun, u_rimSun;\n" +
                "uniform float u_part, u_partA, u_partBurn;\n" +
                "uniform vec2 u_pivot, u_rot, u_seed;\n" +
                headMaskGlsl +
                // patches, first on what is drawn lighter; a = how much snow there is on this building
                "float coverAt(vec2 w, float lum, float th){\n" +
                "    float n = noise(w / 5.0) * 0.6 + noise(w / 1.7) * 0.4;\n" +
                "    return smoothstep(th, th + 0.18, n * 0.75 + lum * 0.45);\n" +
                "}\n" +
                // the game's outline colour (Block.outlineColor 404049) - 1 on it, 0 off it
                "float outl(vec3 c){ return 1.0 - smoothstep(0.035, 0.08, length(c - vec3(0.251, 0.251, 0.286))); }\n" +
                "void main(){\n" +
                "    vec4 c = texture2D(u_texture, v_texCoords);\n" +
                "    if(c.a < 0.5) discard;\n" +
                // (v_color.g = 0: a turret's head, which turns - the patches go round with it, read off the picture)
                // (u_part: a turret's moving parts, drawn by the game's own part drawing, which sets its own colours: how
                // much lies there comes in u_partA, their own alpha still counting - a heat glow drawn see-through)
                "    vec2 world = u_campos + gl_FragCoord.xy / u_screen * u_size;\n" +
                "    if(underHead(world)) discard;\n" +
                // (read in the turret's own frame, which turns with it - one frame for the head and all its parts: read
                // off each picture, every plate had patches of its own, cut off where it ended - "the snow looks cut")
                "    if(v_color.g < 0.5 || u_part > 0.5){ vec2 r = world - u_pivot; world = vec2(r.x * u_rot.x + r.y * u_rot.y, -r.x * u_rot.y + r.y * u_rot.x) + u_seed; }\n" +
                "    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
                // (a conveyor's bend: blue 0.3 its pale rails only, blue 0.5 its dark belt only)
                "    if(v_color.b > 0.2 && v_color.b < 0.4 && lum < 0.52) discard;\n" +
                "    if(v_color.b > 0.4 && v_color.b < 0.55 && lum >= 0.52) discard;\n" +
                // (alpha up to 1: 0.48 a thin sprinkling, past 1 the block all white - SnowCover.deep)
                "    float a = u_part > 0.5 ? u_partA * v_color.a : v_color.a;\n" +
                "    float th = 1.15 - a * 1.1 - max(a - 0.8, 0.0) * 1.5;\n" +
                "    float cover = coverAt(world, lum, th);\n" +
                // Depth (a flat stain at first - "it settles like a stain, no depth"): the snow's surface rises where the
                // cover does, so its slope is read off the cover next to it - lit on the side that faces the sun, shaded
                // on the other - and each lump casts a soft shadow on the block beside it, away from the sun.
                // (plus soft drifts on the snow itself, so a block buried all over is not flat white either)
                "    float e = 0.9;\n" +
                "    vec2 grad = vec2(coverAt(world + vec2(e, 0.0), lum, th) - coverAt(world - vec2(e, 0.0), lum, th),\n" +
                "                     coverAt(world + vec2(0.0, e), lum, th) - coverAt(world - vec2(0.0, e), lum, th));\n" +
                "    vec2 drift = vec2(noise((world + vec2(e, 0.0)) / 2.6) - noise((world - vec2(e, 0.0)) / 2.6),\n" +
                "                      noise((world + vec2(0.0, e)) / 2.6) - noise((world - vec2(0.0, e)) / 2.6));\n" +
                "    grad += drift * 0.35 * cover;\n" +
                // (a slope rising away from the sun faces it: lit by -dot(rise, towards the sun))
                "    float face = clamp(-dot(grad, u_sun) * 1.6, -1.0, 1.0);\n" +
                // (narrow, hugging the edge of each lump as ambient occlusion would - 1.6 units out it was "too wide";
                // a little more on the side away from the sun)
                "    float ring = max(max(coverAt(world + vec2(0.45, 0.0), lum, th), coverAt(world - vec2(0.45, 0.0), lum, th)),\n" +
                "                     max(coverAt(world + vec2(0.0, 0.45), lum, th), coverAt(world - vec2(0.0, 0.45), lum, th)));\n" +
                "    float shade = max(max(ring - cover, 0.0) * 0.75, max(coverAt(world + u_sun * 0.55, lum, th) - cover, 0.0));\n" +
                "    vec3 snow = vec3(0.90, 0.94, 1.0) * (0.80 + 0.22 * lum);\n" +
                // (towards the sun brighter, the far side bluish and darker; a deep cover softer still)
                "    snow *= 1.0 + 0.16 * face;\n" +
                "    snow = mix(snow, snow * vec3(0.80, 0.86, 1.0), max(-face, 0.0) * 0.6);\n" +
                // The block's edge under the snow: buried all over, the blocks were flat white squares with no edge ("when
                // the snow reaches the edges, show the edge as a shadow - plain white looks odd"). Near the sprite's
                // edge - where it ends, or its dark outline lies - the snow is shaded, softly, as over a rounded corner;
                // more on the side away from the sun. (The outline counts as well as the end of the sprite: around a
                // square block the picture's neighbours on the atlas need not be clear.)
                "    vec2 tx = 1.0 / u_texSize;\n" +
                "    float rim = 0.0, rimSun = 0.0, hits = 0.0;\n" +
                // (the game's outline is 404049, lum 0.25: a cut at 0.2 missed it, and the outlines stayed snowed on)
                // (by its colour, not by darkness alone: dark panels on a sprite are not outline)
                // (blue 0.7: an item lying on a belt - no outline of the game's there: dark coal passed for outline, and only a
                // C of snow round its edge was left - "put one coal on a conveyor while it snows")
                "    float isItem = v_color.b > 0.65 && v_color.b < 0.75 ? 1.0 : 0.0;\n" +
                "    if(isItem < 0.5 && outl(c.rgb) > 0.5){ rim = 1.0; }\n" +
                "    else for(int k = 0; k < 8; k++){\n" +
                "        float ang = float(k) * 0.7854;\n" +
                "        vec2 d = vec2(cos(ang), sin(ang));\n" +
                "        for(int s = 1; s <= 3; s++){\n" +
                "            vec4 n2 = texture2D(u_texture, v_texCoords + d * tx * float(s) * 1.4);\n" +
                "            if(n2.a < 0.5 || (isItem < 0.5 && outl(n2.rgb) > 0.5)){\n" +
                "                hits += 1.0;\n" +
                "                float w = 1.0 - (float(s) - 1.0) / 3.0;\n" +
                // (the side read in the picture's frame: only for a picture drawn unturned - blue 1)
                "                if(w > rim){ rim = w; rimSun = dot(d, vec2(u_rimSun.x, -u_rimSun.y)) * step(0.8, v_color.b); }\n" +
                "                break;\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                // (something thin - a conveyor's rail, a few pixels across - is edge on every side: all of it was shaded,
                // and the straight belts' rails looked bare grey beside the white bends. Where most ways out end close
                // by, little shading.)
                "    rim *= mix(1.0, 0.2, smoothstep(3.5, 6.0, hits));\n" +
                "    snow *= 1.0 - rim * (0.22 + 0.22 * max(-rimSun, 0.0)) + rim * 0.05 * max(rimSun, 0.0);\n" +
                "    snow = mix(snow, snow * vec3(0.82, 0.88, 1.0), rim * 0.5);\n" +
                "    float body = cover * mix(0.78, 0.95, clamp(a - 0.5, 0.0, 1.0));\n" +
                // (the dark outline itself stays bare: snow lay on a turret's moving parts' outlines too, a white ring
                // round each - the outline is the edge's shadow)
                "    body *= 1.0 - 0.95 * outl(c.rgb) * (1.0 - isItem);\n" +
                "    float sh = shade * 0.32 * (1.0 - cover);\n" +
                "    float alpha = body + (1.0 - body) * sh;\n" +
                "    vec3 col = (snow * body + vec3(0.10, 0.13, 0.20) * (1.0 - body) * sh) / max(alpha, 0.001);\n" +
                "    gl_FragColor = vec4(col, alpha);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] snow on buildings unavailable", t);
            return;
        }
        try{
            sporeShader = new Shader(Cutaway.VERT,
                "varying lowp vec4 v_color;\n" +
                "varying highp vec2 v_texCoords;\n" +
                "uniform highp sampler2D u_texture;\n" +
                "uniform vec2 u_campos;\n" +
                "uniform vec2 u_size;\n" +
                "uniform vec2 u_screen;\n" +
                "float hash(vec2 p){\n" +
                "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n" +
                "    p3 += dot(p3, p3.yzx + 33.33);\n" +
                "    return fract((p3.x + p3.y) * p3.z);\n" +
                "}\n" +
                "float noise(vec2 p){\n" +
                "    vec2 i = floor(p), f = fract(p);\n" +
                "    f = f * f * (3.0 - 2.0 * f);\n" +
                "    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);\n" +
                "}\n" +
                "uniform vec2 u_texSize;\n" +
                "uniform float u_time;\n" +
                "uniform float u_part, u_partA, u_partBurn;\n" +
                "uniform vec2 u_pivot, u_rot, u_seed;\n" +
                headMaskGlsl +
                "void main(){\n" +
                "    vec4 c = texture2D(u_texture, v_texCoords);\n" +
                "    if(c.a < 0.5) discard;\n" +
                // (u_part: a turret's moving parts, drawn by the game's own part drawing, which sets its own colours: how
                // much lies there comes in u_partA, their own alpha still counting - a heat glow drawn see-through)
                "    vec2 world = u_campos + gl_FragCoord.xy / u_screen * u_size;\n" +
                "    if(underHead(world)) discard;\n" +
                // (blue 0: a unit, one picture, read off the picture itself - a frame of its own each would cost a flush)
                "    if(v_color.b < 0.2 && u_part < 0.5) world = v_texCoords * u_texSize / 4.0;\n" +
                // (a conveyor's bend: blue 0.3 its pale rails only, blue 0.5 its dark belt only)
                "    float clum = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
                "    if(v_color.b > 0.2 && v_color.b < 0.4 && clum < 0.52) discard;\n" +
                "    if(v_color.b > 0.4 && v_color.b < 0.55 && clum >= 0.52) discard;\n" +
                "    else if(v_color.g < 0.5 || u_part > 0.5){ vec2 r = world - u_pivot; world = vec2(r.x * u_rot.x + r.y * u_rot.y, -r.x * u_rot.y + r.y * u_rot.x) + u_seed; }\n" +
                // clumps, as moss: fewer and smaller where little has settled; darker in the middle of a clump
                "    float n = noise(world / 9.0) * 0.7 + noise(world / 3.0) * 0.3;\n" +
                "    float th = 1.0 - (u_part > 0.5 ? u_partA * v_color.a : v_color.a) * 0.9;\n" +
                "    float cover = smoothstep(th, th + 0.12, n);\n" +
                "    vec3 spore = mix(vec3(0.32, 0.22, 0.48), vec3(0.58, 0.44, 0.82), smoothstep(th, th + 0.3, n));\n" +
                // Burning (v_color.r, a hot building): the clumps char and their edges glow like embers, flickering,
                // while they shrink away (the smoke rises from SnowCover.drawSpores).
                "    float burn = u_part > 0.5 ? u_partBurn : v_color.r;\n" +
                "    if(burn > 0.01){\n" +
                "        float edge = 1.0 - smoothstep(th, th + 0.16, n);\n" +
                "        float flick = noise(world / 2.5 + vec2(u_time * 2.3, -u_time * 1.7));\n" +
                "        vec3 ember = mix(vec3(0.95, 0.22, 0.05), vec3(1.0, 0.62, 0.18), flick);\n" +
                "        spore = mix(spore, vec3(0.16, 0.10, 0.12), burn * 0.55);\n" +
                "        spore = mix(spore, ember, burn * clamp(edge * 1.3 + 0.15, 0.0, 1.0) * (0.55 + 0.45 * flick));\n" +
                "    }\n" +
                "    gl_FragColor = vec4(spore, cover * 0.8);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] spores on buildings unavailable", t);
        }        try{
            fade = new Shader(Vars.tree.get("shaders/screenspace.vert").readString(),
                "varying vec2 v_texCoords;\n" +
                "uniform sampler2D u_texture;\n" +
                "uniform float u_step;\n" +
                "void main(){\n" +
                "    gl_FragColor = vec4(max(texture2D(u_texture, v_texCoords).rgb - u_step, 0.0), 1.0);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] snow melted by beams unavailable", t);
        }
        Events.run(Trigger.preDraw, SnowCover::updateMelt);
        Events.run(Trigger.draw, SnowCover::draw);
        Events.on(WorldLoadEvent.class, e -> {
            builds.clear();
            melt.clear();
            burning.clear();
            burnt.clear();
            unitSpores.clear();
            unitBurn.clear();
            spots.clear();
            builtAt.clear();
            ground.clear();
            beamValue = beamTime = null;
            // (a map loaded from inside a game, a launch to another sector, draws no frame in the menu between)
            deep = 0f;
            beltRest.clear();
            beltSeen.clear();
        });
        Events.on(BlockBuildEndEvent.class, e -> {
            if(!e.breaking && e.tile != null && e.tile.build != null) builtAt.put(e.tile.build.pos(), Time.time);
            if(builtAt.size > 5000) builtAt.clear();
        });
    }

    private static void draw(){
        float now = Time.time, dt = Vars.state.isPaused() ? 0f : Math.min(Math.max(now - lastTime, 0f), 30f);
        lastTime = now;
        // thin: about half the cover the ground gets ("shallow"), but settling sooner than on the ground (at the
        // ground's pace it came "a little slow")
        amount = Math.min(Skies.snow * Skies.snowK() * 1.8f, 1f) * 0.48f;
        // and as it goes on snowing, it piles up until the blocks are white all over - slowly, some 3 minutes of a full
        // snowfall ("not only part of the buildings: covered completely in time, but taking a while"); it settles back
        // over a minute or so once it has stopped
        float falling = Math.min(Skies.snowing * Skies.snowK(), 1f);
        if(falling > 0.05f) deep = Math.min(deep + dt * falling / (60f * 180f), Math.min(falling * 1.2f, 1f));
        else deep = Math.max(deep - dt / (60f * 70f), 0f);
        // (the scene behind the menu on a snowy map: buried a good way, as after a long fall - its time stands still)
        if(MenuWorld.snowLying() > 0f) deep = Math.max(deep, 0.6f);
        int forcedDeep = Core.settings.getInt("mshaders-test-snowdeep", -1);
        if(forcedDeep >= 0) deep = forcedDeep / 100f;
        if(!ShaderMod.inWorld()) deep = 0f;
        // (the "spores settle" setting: 0 .. 200 % of it, on the blocks and the units as on the ground)
        sporeAmount = Math.min(Math.min(Skies.spores * Skies.sporeK() * 1.6f, 1f) * 0.45f * Skies.sporePile(), 0.9f);
        // the spores on units go on gathering and falling off before anything else (they stay a while after the storm)
        if(!!ShaderMod.inWorld() && sporeShader != null && (sporeAmount > 0.01f || unitSpores.size > 0)) unitSpores(dt);
        else if(unitSpores.size > 0){
            unitSpores.clear();
            unitBurn.clear();
        }
        if((amount <= 0.01f && sporeAmount <= 0.01f) || !ShaderMod.inWorld()){
            melt.clear();
            ground.clear();
            burning.clear();
            burnt.clear();
            spots.clear();
            warmCount = 0;
            return;
        }
        Core.camera.bounds(view);
        long frame = Core.graphics.getFrameId();
        if(frame - lastLook >= 10){
            lastLook = frame;
            builds.clear();
            float cx = view.x + view.width / 2f, cy = view.y + view.height / 2f, reach = Mathf.dst(view.width, view.height) / 2f + 32f;
            for(TeamData data : Vars.state.teams.present){
                Vars.indexer.eachBlock(data.team, cx, cy, reach, b -> true, builds::add);
            }
            // (and the derelict blocks a map comes with: rain and snow fall on them too, though no team is theirs)
            if(!Vars.state.teams.present.contains(d -> d.team == mindustry.game.Team.derelict)) Vars.indexer.eachBlock(mindustry.game.Team.derelict, cx, cy, reach, b -> true, builds::add);
        }
        if(builds.isEmpty()) return;
        // the buildings hot of themselves this frame (by their own heat, not by how far their snow has melted: by that,
        // hot buildings side by side kept each other warm for ever and nothing froze over again)
        sources.clear();
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float own = ownHeat(b);
            if(own > 0.05f){
                selfHot.add(b.pos());
                sources.add(b.x, b.y, warmReach(b), own);
            }else{
                selfHot.remove(b.pos());
            }
        }
        // melting: fast on a hot building (a second or two), back slowly once it is cold (some 10 seconds)
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float own = ownHeat(b);
            int key = b.pos();
            // the warmth of hot buildings nearby reaches it too, as it reaches the ground (the maker: "when a building
            // gives off heat, the other buildings seem not to be touched at all")
            float near = 0f;
            for(int o = 0; o < sources.size; o += 4){
                float[] v = sources.items;
                if(v[o] == b.x && v[o + 1] == b.y) continue;
                float d = Mathf.dst(b.x, b.y, v[o], v[o + 1]) / (v[o + 2] + b.block.size * Vars.tilesize * 0.5f);
                near = Math.max(near, v[o + 3] * (1f - Interp.smooth.apply(Mathf.clamp((d - 0.4f) / 0.6f))));
            }
            // (armour keeps the warmth of the neighbours out, as it keeps its own heat in)
            if(insulated(b.block)) near = 0f;
            float warm = Math.max(own, near * 0.8f);
            float m = melt.get(key, 0f);
            // spores burning: while the heat is eating them (red, then smoke - "like catching fire, then smoke, gone");
            // dies down in half a second once they are gone or it has cooled
            // (slower than snow melts, some 3 seconds at full heat: at the snow's second the glow was over before it showed)
            // Only by a building's own heat, clearly hot, and over 3 to 7 seconds: warmed by a hot neighbour, a conveyor
            // beside two turrets burnt for most of a minute at the faint warmth it got ("exactly that one tile keeps
            // burning - sharing heat between blocks seems not so good")
            float g = burnt.get(key, 0f), bn = burning.get(key, 0f);
            if(own > 0.15f && sporeAmount > 0.01f){
                if(g < 0.97f) bn = Math.min(bn + dt / 20f, 1f);
                else bn = Math.max(bn - dt / 30f, 0f);
                g = Math.min(g + dt * (0.4f + 0.6f * Math.min(own, 1f)) / 180f, 1f);
            }else{
                bn = Math.max(bn - dt / 30f, 0f);
                g = Math.max(g - dt / 900f, 0f);
            }
            if(bn > 0f) burning.put(key, bn);
            else burning.remove(key, 0f);
            if(g > 0f) burnt.put(key, g);
            else burnt.remove(key, 0f);
            if(warm > 0.05f) m = Math.min(m + dt * warm / 60f, 1f);
            else m = Math.max(m - dt / 600f, 0f);
            if(m > 0f) melt.put(key, m);
            else melt.remove(key, 0f);
        }
        // The ground around hot buildings thaws too. Each place keeps its own record, which goes on cooling after the
        // building is gone (read from the building alone, the ground froze over at once when a smelter was taken
        // down: "it looks covered again immediately"); the most melted of them in view go to the ground's layer.
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float m = melt.get(b.pos(), 0f);
            if(m <= 0.02f) continue;
            float[] spot = spots.get(b.pos());
            // (only a building hot of itself thaws a place around it: one warmed by its neighbour would pass the
            // warmth on and on)
            if(spot == null && !selfHot.contains(b.pos())) continue;
            if(spot == null) spots.put(b.pos(), spot = new float[4]);
            spot[0] = b.x;
            spot[1] = b.y;
            // (a hot block thaws a ring around itself, larger the larger it is)
            spot[2] = warmReach(b);
            spot[3] = Math.max(spot[3], m);
        }
        for(var it = spots.iterator(); it.hasNext();){
            var e = it.next();
            var b = Vars.world.build(e.key);
            // a building still there follows its own melt; one gone cools on its own over some 20 seconds
            if(b != null && b.isValid() && b.pos() == e.key) e.value[3] = Math.min(e.value[3], Math.max(melt.get(e.key, 0f), e.value[3] - dt / 1200f));
            else e.value[3] -= dt / 1200f;
            if(e.value[3] <= 0.02f) it.remove();
        }
        // the ground under each block, for its snow and spores to follow
        ground.clear();
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float g = insulated(b.block) ? 0f : groundThaw(b);
            if(g > 0.01f) ground.put(b.pos(), g);
        }
        warmCount = 0;
        for(int k = 0; k < warmKept; k++){
            float[] best = null;
            float bestScore = 0.02f;
            for(var e : spots){
                float[] v = e.value;
                if(v[3] * v[2] <= bestScore * 20f || !view.overlaps(v[0] - v[2], v[1] - v[2], v[2] * 2f, v[2] * 2f)) continue;
                boolean taken = false;
                for(int j = 0; j < warmCount; j++) if(warm[j * 4] == v[0] && warm[j * 4 + 1] == v[1]) taken = true;
                if(taken) continue;
                best = v;
                bestScore = v[3] * v[2] / 20f;
            }
            if(best == null) break;
            System.arraycopy(best, 0, warm, warmCount++ * 4, 4);
        }
        // (just after the shading of the blocks with normal maps, Scene.shapePass: under it, the snow was lost)
        float z = Draw.z();
        Draw.draw(Layer.turret - 0.9f, drawAll);
        // turrets' heads are drawn at Layer.turret, over the pass above: the cover on them comes right after them (and
        // after their normal-map shading, Scene.headPass at Layer.turret + 0.05), before their heat glow
        Draw.draw(Layer.turret + 0.09f, drawHeads);
        Draw.z(z);
    }

    /** True while the covers on turrets' heads are drawn (drawHeads), false for everything else (drawAll). */
    private static boolean headPass;
    private static final Runnable drawHeads = () -> {
        headPass = true;
        drawAll();
        headPass = false;
    };

    /**
     * Masks what is drawn next by a turret's head (on): nothing where the head is - for the parts the game draws under
     * the head (Duo's barrels), drawn here over everything (Draw.z is passed over in a Draw.draw runnable; drawn in a
     * pass of their own under the heads, the shading of the normal maps, drawn after, hid them). The shader works out
     * where on the head's picture a point is from the turret's frame (u_pivot, u_rot: turnFrame or set here).
     */
    static void headMask(arc.graphics.gl.Shader s, Building b, boolean on){
        Draw.flush();
        s.bind();
        if(!on || !(b instanceof Turret.TurretBuild t) || !(b.block instanceof Turret turret) || turret.region == null || !turret.region.found()){
            s.setUniformf("u_headMask", 0f);
            return;
        }
        TextureRegion r = turret.region;
        float rot = t.drawrot();
        s.setUniformf("u_headMask", 1f);
        s.setUniformf("u_pivot", b.x + t.recoilOffset.x, b.y + t.recoilOffset.y);
        s.setUniformf("u_rot", Mathf.cosDeg(rot), Mathf.sinDeg(rot));
        s.setUniformf("u_headUV", r.u, r.v, r.u2, r.v2);
        s.setUniformf("u_headSize", r.width * Draw.scl * Draw.xscl, r.height * Draw.scl * Draw.yscl);
        s.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        s.setUniformf("u_size", Core.camera.width, Core.camera.height);
        s.setUniformf("u_screen", viewW(), viewH());
    }

    /**
     * A drill in its pieces, as the game draws it (Drill.DrillBuild.draw: the frame, the rotator turned by how long it
     * has drilled, the cap). Drawn as its icon, the cover lay still while the rotator span under it ("when it snows -
     * the drills turning, I hadn't thought of that"). A turning rotator throws its snow off: none on it, and none on the
     * frame where the rotator covers it (masked as the parts under a turret's head are). One standing still keeps its
     * snow, turned as it stopped. False for anything else.
     */
    private static boolean drawDrill(Building b, float burn, float a){
        if(!(b instanceof mindustry.world.blocks.production.Drill.DrillBuild db) || !(b.block instanceof mindustry.world.blocks.production.Drill drill)) return false;
        TextureRegion frame = drill.region, rotator = drill.rotatorRegion, top = drill.topRegion;
        if(frame == null || !frame.found() || rotator == null || !rotator.found() || drawing == null) return false;
        float spin = db.timeDrilled * drill.rotateSpeed;
        boolean turning = db.warmup > 0.02f;
        Draw.color(burn, 1f, 1f, a);
        if(turning) maskBy(drawing, rotator, b.x, b.y, spin);
        usePage(frame);
        Draw.rect(frame, b.x, b.y);
        if(turning){
            maskBy(drawing, null, 0f, 0f, 0f);
        }else{
            // (blue 0.6: drawn turned - its edges' sided shading, read in the picture's own frame, would turn with it)
            Draw.color(burn, 1f, 0.6f, a);
            usePage(rotator);
            Draw.rect(rotator, b.x, b.y, spin);
        }
        if(top != null && top.found()){
            Draw.color(burn, 1f, 1f, a);
            usePage(top);
            Draw.rect(top, b.x, b.y);
        }
        return true;
    }

    /** Masks what is drawn next by a picture laid at that place and turn (null: no mask) - headMask for anything. */
    private static void maskBy(arc.graphics.gl.Shader s, TextureRegion r, float x, float y, float rot){
        Draw.flush();
        s.bind();
        if(r == null){
            s.setUniformf("u_headMask", 0f);
            return;
        }
        s.setUniformf("u_headMask", 1f);
        s.setUniformf("u_pivot", x, y);
        s.setUniformf("u_rot", Mathf.cosDeg(rot), Mathf.sinDeg(rot));
        s.setUniformf("u_headUV", r.u, r.v, r.u2, r.v2);
        s.setUniformf("u_headSize", r.width * Draw.scl * Draw.xscl, r.height * Draw.scl * Draw.yscl);
        s.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        s.setUniformf("u_size", Core.camera.width, Core.camera.height);
        s.setUniformf("u_screen", viewW(), viewH());
    }

    /** The masking by the head (headMask), in GLSL: discards a point that lies on the head's picture. */
    static final String headMaskGlsl =
        "uniform float u_headMask;\n" +
        "uniform vec4 u_headUV;\n" +
        "uniform vec2 u_headSize;\n" +
        "bool underHead(vec2 world){\n" +
        "    if(u_headMask < 0.5) return false;\n" +
        "    vec2 r0 = world - u_pivot;\n" +
        "    vec2 q = vec2(r0.x * u_rot.x + r0.y * u_rot.y, -r0.x * u_rot.y + r0.y * u_rot.x) / u_headSize + 0.5;\n" +
        "    if(q.x <= 0.0 || q.y <= 0.0 || q.x >= 1.0 || q.y >= 1.0) return false;\n" +
        "    return texture2D(u_texture, vec2(mix(u_headUV.x, u_headUV.z, q.x), mix(u_headUV.w, u_headUV.y, q.y))).a > 0.5;\n" +
        "}\n";

    private static final float railShare = 0.125f;

    /** The two rails of a conveyor's picture (it faces right: the top and bottom eighth), by block id. */
    private static final IntMap<TextureRegion[]> railRegions = new IntMap<>();

    private static TextureRegion[] rails(Block block){
        TextureRegion[] r = railRegions.get(block.id);
        if(r == null){
            // (cut from the straight piece the game draws in the world, as the bends are: the block's icon is another
            // picture, with an outline round it, and its edge eighths were more outline than rail - the straight belts'
            // rails looked bare beside the snowy bends)
            TextureRegion icon = block.fullIcon;
            if(block instanceof Conveyor conv && conv.regions != null && conv.regions.length > 0 && conv.regions[0] != null && conv.regions[0].length > 0 && conv.regions[0][0] != null && conv.regions[0][0].found()) icon = conv.regions[0][0];
            // (the rails are about an eighth of it: a quarter reached onto the belt, and snow lay on its edge)
            int w = icon.width, h = icon.height, q = Math.max(Math.round(h * railShare), 1);
            // (and the belt between them, for what settles there while it stands)
            r = new TextureRegion[]{new TextureRegion(icon, 0, 0, w, q), new TextureRegion(icon, 0, h - q, w, q), new TextureRegion(icon, 0, q, w, h - 2 * q)};
            railRegions.put(block.id, r);
        }
        return r;
    }

    /**
     * How much lies on a conveyor's belt (by position), 0 .. 1: it settles there slowly while the belt stands, and is
     * carried off in a second or so once it runs. (It was all or nothing by whether it ran that moment - a belt that
     * kept starting and stopping blinked its snow on and off.) beltSeen: when it was last brought up to date, so the
     * passes of one frame (snow, spores) count the time once.
     */
    private static final arc.struct.IntFloatMap beltRest = new arc.struct.IntFloatMap(), beltSeen = new arc.struct.IntFloatMap();

    /**
     * How hot a building is of itself, 0 .. 1: hit hard (HitHeat), at work (HeatHaze: turrets firing, reactors,
     * generators, furnaces), or holding a hot liquid - slag in a conduit or a turret ("would a pipe or turret with slag
     * in it count as a heat source?"). Not through armour: the armoured conduits, conveyors and ducts keep it in.
     */
    static float ownHeat(Building b){
        float own = Math.max(HitHeat.heatAt(b.pos()), HeatHaze.canBeHot(b.block) ? HeatHaze.heatOf(b) : 0f);
        return Math.max(own, liquidHeat(b));
    }

    /**
     * How far a hot building's warmth reaches, world units from its middle: a ring larger the larger it is; a pipe, a
     * liquid router or a tank warmed only by what flows in it, hardly past its own edge (a slag conduit thawed a band two
     * tiles wide either side: "the pipes had better be less").
     */
    static float warmReach(Building b){
        float half = b.block.size * Vars.tilesize * 0.5f;
        // (liquid bridges are not LiquidBlocks: ItemBridge, DirectionBridge)
        if(b.block instanceof mindustry.world.blocks.liquid.LiquidBlock || b.block instanceof mindustry.world.blocks.liquid.LiquidBridge
            || b.block instanceof mindustry.world.blocks.distribution.DirectionLiquidBridge) return half + 4f;
        return half + 14f + b.block.size * 4f;
    }

    /** The heat of the liquid in it:by how much hotter than water it is (slag 1, water and oil none), and how full. */
    static float liquidHeat(Building b){
        // (not the derelict blocks a map comes with: they never update, and slag saved in them stayed hot for ever)
        if(b.liquids == null || !b.block.hasLiquids || insulated(b.block) || b.team == mindustry.game.Team.derelict) return 0f;
        // every hot liquid it holds, not only the one moved last (liquids.current() changes with every liquid put in
        // or taken out); hot from 0.6 up - neoplasm's 0.54 is lukewarm, slag's 1 is hot
        float best = 0f;
        var all = Vars.content.liquids();
        for(int i = 0; i < all.size; i++){
            mindustry.type.Liquid l = all.get(i);
            if(l.temperature <= 0.6f) continue;
            float amount = b.liquids.get(l);
            if(amount <= 0.001f) continue;
            float full = amount / Math.max(b.block.liquidCapacity, 0.001f);
            best = Math.max(best, Mathf.clamp((l.temperature - 0.6f) / 0.4f) * Mathf.clamp(full * 3f));
        }
        return best;
    }

    /** Armoured: plated and reinforced conduits (ArmoredConduit), armoured conveyors, armoured ducts. */
    static boolean insulated(Block block){
        return block instanceof mindustry.world.blocks.liquid.ArmoredConduit || block instanceof ArmoredConveyor
            || (block instanceof Duct duct && duct.armored);
    }

    private static float beltRest(Conveyor.ConveyorBuild c){
        int key = c.pos();
        float now = Time.time, r = beltRest.get(key, -1f);
        boolean running = c.enabled && c.clogHeat <= 0.5f && c.efficiency > 0f;
        if(r < 0f){
            r = running ? 0f : 1f;
        }else{
            float dt = Math.min(Math.max(now - beltSeen.get(key, now), 0f), 60f);
            // (some 8 seconds to settle over a belt that stands, a second and a half to be carried off)
            r = running ? Math.max(r - dt / 90f, 0f) : Math.min(r + dt / 480f, 1f);
        }
        beltRest.put(key, r);
        beltSeen.put(key, now);
        return r;
    }

    private static void drawAll(){
        // (whatever is waiting is drawn first, with its own shader: binding ours before that left the game's bound while
        // these were set - "GL error: invalid operation" once the covers on turrets' heads came after the heads)
        Draw.shader(shader);
        drawing = shader;
        Draw.flush();
        shader.bind();
        shader.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        shader.setUniformf("u_size", Core.camera.width, Core.camera.height);
        shader.setUniformf("u_screen", viewW(), viewH());
        shader.setUniformf("u_texSize", texWidth(), texHeight());
        pageNow = null;
        inPass = true;
        // (turrets' heads are read in their own picture's frame, which turns with the head: no sun-sided light there,
        // the lumps keep only their narrow rim)
        // (in the head pass the sun is turned into each turret's frame as it is drawn - turnFrame; the edges' sided
        // shading reads the picture's own frame, unturned there, so it goes without a side)
        shader.setUniformf("u_sun", ShaderMod.sunX(), ShaderMod.sunY());
        if(headPass) shader.setUniformf("u_rimSun", 0f, 0f);
        else shader.setUniformf("u_rimSun", ShaderMod.sunX(), ShaderMod.sunY());
        if(amount > 0.01f) for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float size = b.block.size * Vars.tilesize;
            if(!view.overlaps(b.x - size, b.y - size, size * 2f, size * 2f)) continue;
            // (a building just put up is bare, and gathers its snow over some 15 seconds: at first it came with the
            // snow already on - "snow lies on a building the moment it is built"; and none on one still being built)
            if(b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) continue;
            float age = Time.time - builtAt.get(b.pos(), -100000f);
            // (a building put up during the snow piles its deep cover in its own time, from bare)
            float d = Math.min(deep, Mathf.clamp(age / (60f * 180f)));
            float a = (amount + d * (1f - amount)) * (1f - Math.max(melt.get(b.pos(), 0f), ground.get(b.pos(), 0f))) * Mathf.clamp(age / 900f);
            if(a <= 0.01f) continue;
            drawShape(b, 0f, a);
        }
        inPass = false;
        Draw.shader();
        Draw.color();
        if(sporeAmount > 0.01f && sporeShader != null) drawSpores();
    }

    /** The sprite page the shader's u_texSize is set for now (null: not yet in this pass); true while a pass draws. */
    private static Texture pageNow;
    private static boolean inPass;

    /**
     * The size of the sprite page that picture is on, for the shader (u_texSize: one texel is 1 / size). It was the size
     * of Duo's page for everything - and a picture on a page of another size had its neighbouring texels read from
     * far away, other sprites' outlines coming out as marks on it ("the marks only on blocks with a team colour,
     * cores and containers - the same shape on every map"). Set again only when the page changes.
     */
    static void usePage(TextureRegion r){
        if(!inPass || drawing == null || r == null || r.texture == null || r.texture == pageNow) return;
        Draw.flush();
        drawing.bind();
        drawing.setUniformf("u_texSize", r.texture.width, r.texture.height);
        pageNow = r.texture;
    }

    /** The size of the page the blocks' pictures are on (for the patches on a turret's head, which go round with it). */
    private static float texWidth(){
        TextureRegion r = mindustry.content.Blocks.duo.region;
        return r != null && r.texture != null ? r.texture.width : 4096f;
    }

    private static float texHeight(){
        TextureRegion r = mindustry.content.Blocks.duo.region;
        return r != null && r.texture != null ? r.texture.height : 4096f;
    }

    /**
     * A building's picture as the game draws it, in the colour set (HitHeat lays the glow of hot metal over it): a
     * turret's base and its head as turned and pushed back, a conveyor's own piece, a turned block turned.
     */
    static void drawPicture(Building b){
        if(b instanceof Turret.TurretBuild){
            drawTurretBase(b);
            drawTurretHead(b);
            return;
        }
        if(b instanceof Conveyor.ConveyorBuild c && b.block instanceof Conveyor conv && conv.regions != null && conv.regions.length > 0){
            TextureRegion[] frames = conv.regions[Mathf.clamp(c.blendbits, 0, conv.regions.length - 1)];
            if(frames != null && frames.length > 0 && frames[0] != null){
                usePage(frames[0]);
                Draw.rect(frames[0], b.x, b.y, Vars.tilesize * c.blendsclx, Vars.tilesize * c.blendscly, c.rotation * 90f);
                return;
            }
        }
        // (a conduit or duct in the shape it has; the colour set by the caller stays)
        if(isPipe(b) && pipeRect(b)) return;
        Draw.rect(b.block.fullIcon, b.x, b.y, b.block.rotate && !(b.block instanceof BaseTurret) ? b.rotdeg() : 0f);
    }

    private static final ObjectMap<Block, TextureRegion> bases = new ObjectMap<>();
    private static final TextureRegion noBase = new TextureRegion();

    /** The base picture of a turning block that is not a Turret (its public field baseRegion), or null. */
    private static TextureRegion baseOf(Block block){
        TextureRegion r = bases.get(block);
        if(r == null){
            r = noBase;
            try{
                Object v = block.getClass().getField("baseRegion").get(block);
                if(v instanceof TextureRegion t && t.found()) r = t;
            }catch(Throwable ignored){
            }
            bases.put(block, r);
        }
        return r == noBase ? null : r;
    }

    /** A building that turns its own picture on a base picture of its own (not a Turret: those have DrawTurret). */
    private static boolean turnsOnBase(Building b){
        return !(b instanceof Turret.TurretBuild) && b instanceof mindustry.world.blocks.RotBlock && baseOf(b.block) != null;
    }

    /** The shader the covers are being drawn with now (snow or spores), for the parts' uniforms. */
    private static Shader drawing;

    /**
     * A turret's moving parts - barrels that recoil, plates that open (DrawTurret.parts) - drawn by the game's own part
     * drawing with the same values the game gives it, through the cover's shader: they were left bare ("only the moving
     * part of the turrets is not covered - try a salvo"). The parts set their own colours, so how much lies there goes
     * in a uniform for them.
     */
    private static void drawTurretParts(Building b, float burn, float a, boolean under){
        if(!(b instanceof Turret.TurretBuild t) || !(b.block instanceof Turret turret) || drawing == null) return;
        if(!(turret.drawer instanceof mindustry.world.draw.DrawTurret dt) || dt.parts.size == 0) return;
        Draw.flush();
        drawing.bind();
        drawing.setUniformf("u_part", 1f);
        drawing.setUniformf("u_partA", a);
        drawing.setUniformf("u_partBurn", burn);
        drawParts(b, under);
        Draw.flush();
        drawing.bind();
        drawing.setUniformf("u_part", 0f);
        Draw.blend();
        Draw.color(burn, 0f, 1f, a);
    }

    /**
     * A turret's moving parts (DrawTurret.parts) drawn by the game's own part drawing with the values the game gives it,
     * through whatever shader is bound (the parts set their own colours and blending). True if it has any.
     */
    static boolean drawParts(Building b, boolean under){
        if(!(b instanceof Turret.TurretBuild t) || !(b.block instanceof Turret turret)) return false;
        if(!(turret.drawer instanceof mindustry.world.draw.DrawTurret dt) || dt.parts.size == 0) return false;
        float p = t.progress();
        var params = mindustry.entities.part.DrawPart.params.set(t.warmup(), 1f - p, 1f - p, t.heat, t.curRecoil, t.charge, t.x + t.recoilOffset.x, t.y + t.recoilOffset.y, t.rotation);
        // In here the layers do not count (Draw.z is passed over while a Draw.draw runnable is drawn): a part's dark
        // outline and the parts drawn under the head came out on top of it. The outlines are turned off for the while,
        // and the parts under the head (under) are drawn in a pass of their own, where the game draws them - just
        // under the head (left out altogether, Duo's barrels were never covered: "the duo is slow, or gets none")
        outlined.clear();
        try{
            for(int i = 0; i < dt.parts.size; i++) noOutline(dt.parts.get(i));
            for(int i = 0; i < dt.parts.size; i++){
                var part = dt.parts.get(i);
                if(part.under != under) continue;
                // (each barrel its own recoil, as the game gives it: Duo's two fire in turn)
                int ri = part.recoilIndex;
                params.setRecoil(ri >= 0 && t.curRecoils != null && ri < t.curRecoils.length ? t.curRecoils[ri] : t.curRecoil);
                part.draw(params);
            }
        }catch(Throwable ignored){
        }finally{
            for(int i = 0; i < outlined.size; i++) outlined.get(i).outline = true;
            outlined.clear();
        }
        return true;
    }

    /** Has that turret parts drawn under its head? */
    static boolean hasUnder(Building b){
        if(!(b.block instanceof Turret turret) || !(turret.drawer instanceof mindustry.world.draw.DrawTurret dt)) return false;
        for(int i = 0; i < dt.parts.size; i++) if(dt.parts.get(i).under) return true;
        return false;
    }

    private static final Seq<mindustry.entities.part.RegionPart> outlined = new Seq<>();

    private static void noOutline(mindustry.entities.part.DrawPart part){
        if(!(part instanceof mindustry.entities.part.RegionPart r)) return;
        if(r.outline){
            r.outline = false;
            outlined.add(r);
        }
        if(r.children != null) for(int i = 0; i < r.children.size; i++) noOutline(r.children.get(i));
    }

    /** A turret's base (DrawTurret.base), where the game draws it. */
    static void drawTurretBase(Building b){
        if(b.block instanceof Turret turret && turret.drawer instanceof mindustry.world.draw.DrawTurret dt && dt.base != null && dt.base.found()){ usePage(dt.base); Draw.rect(dt.base, b.x, b.y); }
    }

    /** A turret's head as it is turned and pushed back (DrawTurret.drawTurret). */
    static void drawTurretHead(Building b){
        if(b instanceof Turret.TurretBuild t && b.block instanceof Turret turret && turret.region != null && turret.region.found()){
            usePage(turret.region);
            Draw.rect(turret.region, b.x + t.recoilOffset.x, b.y + t.recoilOffset.y, t.drawrot());
        }
    }

    /**
     * Draws the picture the snow or the spores lie on, through the shader bound (colour: red = burning, alpha = how
     * much lies there). The picture as the game draws it, so the cover keeps to the shape:
     * - a conveyor: its own piece (straight, bent, joined - "where a conveyor turns, the snow does not follow it");
     *   while its belt runs only its rails keep a cover, the two edges of a straight piece (the belt carries it away:
     *   "snow on the moving part of a conveyor is a bit odd", "the spore stains stay while the conveyor moves"); on a
     *   running bend or join nothing stays. Stopped - clogged, or switched off - it is covered all over;
     * - a turret: its base here, its head in the pass after the heads are drawn (headPass), the patches going round
     *   with the head (at first turrets were left bare: "the spores don't seem to settle on turrets");
     * - anything else: its icon.
     */
    /**
     * The frame a turning head's cover is read in: centred where the head is drawn (pushed back with it), turned with
     * it - the head and all its moving parts share it, so the patches run on across the plates instead of starting
     * again on each. (u_seed keeps one turret's patches from being every other's.) The snow's sun is turned into it.
     */
    private static void turnFrame(Building b, float x, float y, float rotDeg){
        if(drawing == null) return;
        Draw.flush();
        drawing.bind();
        float cs = Mathf.cosDeg(rotDeg), sn = Mathf.sinDeg(rotDeg);
        drawing.setUniformf("u_pivot", x, y);
        drawing.setUniformf("u_rot", cs, sn);
        drawing.setUniformf("u_seed", b.x * 1.37f, b.y * 0.71f);
        if(drawing.hasUniform("u_sun")){
            float sx = ShaderMod.sunX(), sy = ShaderMod.sunY();
            drawing.setUniformf("u_sun", sx * cs + sy * sn, -sx * sn + sy * cs);
        }
    }

    private static void drawShape(Building b, float burn, float a){
        if(headPass){
            if(b instanceof Turret.TurretBuild t){
                turnFrame(b, b.x + t.recoilOffset.x, b.y + t.recoilOffset.y, t.drawrot());
                // (green 0: the patches are read off the picture, so they turn with the head)
                Draw.color(burn, 0f, 1f, a);
                // the parts under the head first, where the head is not (Duo's barrels were never covered)
                if(drawing != null && hasUnder(b)){
                    headMask(drawing, b, true);
                    drawTurretParts(b, burn, a, true);
                    headMask(drawing, b, false);
                    turnFrame(b, b.x + t.recoilOffset.x, b.y + t.recoilOffset.y, t.drawrot());
                }
                drawTurretHead(b);
                drawTurretParts(b, burn, a, false);
            }else if(turnsOnBase(b) && b.block.region != null && b.block.region.found()){
                // the other turning things - point defence, tractor beam, repair point, build tower: their head is
                // their own picture turned (they were left bare: "the moving part of the turrets isn't covered")
                float rot = ((mindustry.world.blocks.RotBlock)b).buildRotation() - 90f;
                turnFrame(b, b.x, b.y, rot);
                Draw.color(burn, 0f, 1f, a);
                usePage(b.block.region);
                Draw.rect(b.block.region, b.x, b.y, rot);
            }
            return;
        }
        if(b instanceof Conveyor.ConveyorBuild c && b.block instanceof Conveyor conv){
            // (blue 0.6: drawn turned - its edges' sided shading, read in the picture's frame, would turn with it)
            float rest = beltRest(c);
            // What lies on the belt is drawn under this cover: the belt's own picture laid over a belt full of copper
            // put its pattern on the items ("on a conveyor full of copper the snow looks odd"). The fuller the belt,
            // the less of the belt's own is shown, and the items standing on it take theirs on their own shapes.
            float full = Math.min(c.len / 3f, 1f);
            if(rest > 0.01f && c.len > 0 && c.ids != null){
                Draw.color(burn, 1f, 0.7f, a * rest);
                float rot = c.rotation * 90f;
                for(int i = 0; i < c.len && i < c.ids.length; i++){
                    mindustry.type.Item item = c.ids[i];
                    if(item == null) continue;
                    // (where the game draws it: Conveyor.ConveyorBuild.draw)
                    float vx = Angles.trnsx(rot, Vars.tilesize), vy = Angles.trnsy(rot, Vars.tilesize);
                    float wx = Angles.trnsx(rot, -Vars.tilesize / 2f, c.xs[i] * Vars.tilesize / 2f), wy = Angles.trnsy(rot, -Vars.tilesize / 2f, c.xs[i] * Vars.tilesize / 2f);
                    usePage(item.fullIcon);
                    Draw.rect(item.fullIcon, b.x + vx * c.ys[i] + wx, b.y + vy * c.ys[i] + wy, 5f, 5f);
                }
            }
            rest *= 1f - 0.85f * full;
            if(c.blendbits == 0){
                // straight: the rails keep theirs; on the belt what has settled since it last ran
                TextureRegion[] rails = rails(b.block);
                float len = b.block.size * Vars.tilesize, wide = len * railShare, off = (len - wide) / 2f;
                float r = b.rotdeg();
                Draw.color(burn, 1f, 0.6f, a);
                usePage(rails[0]);
                Draw.rect(rails[0], b.x + Angles.trnsx(r + 90f, off), b.y + Angles.trnsy(r + 90f, off), len, wide, r);
                Draw.rect(rails[1], b.x + Angles.trnsx(r - 90f, off), b.y + Angles.trnsy(r - 90f, off), len, wide, r);
                if(rest > 0.01f){
                    Draw.color(burn, 1f, 0.6f, a * rest);
                    Draw.rect(rails[2], b.x, b.y, len, len - 2f * wide, r);
                }
                return;
            }
            // A bend or a join: its rails are not strips to cut out, so they are told by their colour - the pale metal
            // keeps its snow (blue 0.3: only what is pale), the dark belt has what settled on it (blue 0.5: only what is
            // dark). (While it ran the whole bend went bare, its rails too: "only at the bends the snow on the edge
            // seems melted".)
            if(conv.regions != null && conv.regions.length > 0){
                TextureRegion[] frames = conv.regions[Mathf.clamp(c.blendbits, 0, conv.regions.length - 1)];
                if(frames != null && frames.length > 0 && frames[0] != null){
                    Draw.color(burn, 1f, 0.3f, a);
                    usePage(frames[0]);
                    Draw.rect(frames[0], b.x, b.y, Vars.tilesize * c.blendsclx, Vars.tilesize * c.blendscly, c.rotation * 90f);
                    if(rest > 0.01f){
                        Draw.color(burn, 1f, 0.5f, a * rest);
                        usePage(frames[0]);
                        Draw.rect(frames[0], b.x, b.y, Vars.tilesize * c.blendsclx, Vars.tilesize * c.blendscly, c.rotation * 90f);
                    }
                    return;
                }
            }
        }
        if(drawPipe(b, burn, a)) return;
        if(b instanceof Turret.TurretBuild){
            Draw.color(burn, 1f, 1f, a);
            drawTurretBase(b);
            return;
        }
        // (other turning things - point defence, tractor beams, repair points, build towers: their base here, their
        // head in the pass after the heads)
        if(turnsOnBase(b)){
            Draw.color(burn, 1f, 1f, a);
            usePage(baseOf(b.block));
            Draw.rect(baseOf(b.block), b.x, b.y);
            return;
        }
        if(b.block instanceof BaseTurret) return;
        if(drawDrill(b, burn, a)) return;
        float rot = b.block.rotate ? b.rotdeg() : 0f;
        Draw.color(burn, 1f, rot % 360f == 0f ? 1f : 0.6f, a);
        usePage(b.block.fullIcon);
        Draw.rect(b.block.fullIcon, b.x, b.y, rot);
    }

    /**
     * A conduit or a duct: the top of the shape it has where it stands (straight, bent, joined - topRegions[blendbits]),
     * turned and mirrored as the game draws it (Conduit/Duct draw: Draw.scl(xscl, yscl), rotation * 90). It was drawn
     * as its icon, a straight piece: on a bend the cover lay over the clear corner of the picture ("snow on the
     * see-through parts of the building's tile"). False for anything else.
     */
    private static boolean drawPipe(Building b, float burn, float a){
        if(!isPipe(b)) return false;
        // (blue 0.6: drawn turned - the edges' sided shading would turn with it)
        Draw.color(burn, 1f, 0.6f, a);
        return pipeRect(b);
    }

    private static boolean isPipe(Building b){
        return b instanceof mindustry.world.blocks.liquid.Conduit.ConduitBuild || b instanceof mindustry.world.blocks.distribution.Duct.DuctBuild;
    }

    /** Draws a conduit's or duct's top in the colour set; false if it has none to draw. */
    private static boolean pipeRect(Building b){
        TextureRegion[] tops;
        int bits, sx, sy;
        if(b instanceof mindustry.world.blocks.liquid.Conduit.ConduitBuild c && b.block instanceof mindustry.world.blocks.liquid.Conduit conduit){
            tops = conduit.topRegions; bits = c.blendbits; sx = c.xscl; sy = c.yscl;
        }else if(b instanceof mindustry.world.blocks.distribution.Duct.DuctBuild d && b.block instanceof mindustry.world.blocks.distribution.Duct duct){
            tops = duct.topRegions; bits = d.blendbits; sx = d.xscl; sy = d.yscl;
        }else return false;
        if(tops == null || bits < 0 || bits >= tops.length || tops[bits] == null || !tops[bits].found()) return false;
        usePage(tops[bits]);
        Draw.rect(tops[bits], b.x, b.y, Vars.tilesize * sx, Vars.tilesize * sy, b.rotation * 90f);
        return true;
    }

    private static final java.nio.IntBuffer viewport =arc.util.Buffers.newIntBuffer(16);

    /**
     * The size of what is being drawn into, in pixels - the window, or the game's own buffer when it draws the world
     * into one (its "pixelate" setting, a map screenshot): the covers' patches are placed by gl_FragCoord over it, and
     * with the window's size there they were scaled wrong and slid with the camera.
     */
    private static float viewW(){
        arc.graphics.Gl.getIntegerv(arc.graphics.Gl.viewport, viewport);
        return Math.max(viewport.get(2), 1);
    }

    private static float viewH(){
        arc.graphics.Gl.getIntegerv(arc.graphics.Gl.viewport, viewport);
        return Math.max(viewport.get(3), 1);
    }

    /** How many spores cling to each unit, by unit id (0 .. sporeAmount), and how hard they are burning (0 .. 1). */
    private static final IntFloatMap unitSpores = new IntFloatMap(), unitBurn = new IntFloatMap();
    /** The units with spores on them in view this frame, by the layer they are drawn at (UnitType.draw). */
    private static final FloatSeq unitZ = new FloatSeq();
    private static final Seq<Seq<Unit>> unitLists = new Seq<>();
    private static final Seq<Runnable> unitRunners = new Seq<>();
    private static final IntSeq gone = new IntSeq();
    private static long lastSweep;

    /**
     * Spores on units ("spore storm option 5"): in a storm they gather on a unit over some 20 seconds (on one in the
     * air half as many), moving shakes them off - the faster, the more - and after the storm they fall off over some
     * 30 seconds. A unit burning or melting (StatusEffects) burns them off as the buildings do: embers, then smoke.
     * Drawn right after the units of each layer, the unit's picture again through the spore shader, the patches read
     * off the picture so they go along with the unit.
     */
    private static void unitSpores(float dt){
        float target = sporeAmount;
        // (the test kit's forced spores: on the units at once too, as on the ground)
        boolean forced = Core.settings.getInt("mshaders-test-spore", -1) >= 0;
        for(Unit u : Groups.unit){
            if(!u.isValid()) continue;
            int id = u.id;
            float s = unitSpores.get(id, 0f), bn = unitBurn.get(id, 0f);
            float t = target * (u.isFlying() ? 0.5f : 1f);
            boolean hot = u.hasEffect(mindustry.content.StatusEffects.burning) || u.hasEffect(mindustry.content.StatusEffects.melting);
            if(hot && s > 0.01f){
                bn = Math.min(bn + dt / 20f, 1f);
                s = Math.max(s - dt * 0.45f / 180f, 0f);
            }else{
                bn = Math.max(bn - dt / 30f, 0f);
                if(s < t) s = Math.min(t, s + dt * t / 1200f);
                else if(t < 0.01f) s = Math.max(s - dt * 0.45f / 1800f, 0f);
                // shaken off by moving (world units a tick: a dagger walks about 0.5, a flare flies about 4)
                s = Math.max(s - dt * s * u.vel.len() * 0.004f, 0f);
                if(forced) s = t;
            }
            if(s > 0.002f) unitSpores.put(id, s);
            else unitSpores.remove(id, 0f);
            if(bn > 0f) unitBurn.put(id, bn);
            else unitBurn.remove(id, 0f);
        }
        // the units that are gone, now and then
        long frame = Core.graphics.getFrameId();
        if(frame - lastSweep > 120){
            lastSweep = frame;
            gone.clear();
            for(var e : unitSpores) if(Groups.unit.getByID(e.key) == null) gone.add(e.key);
            for(int i = 0; i < gone.size; i++){
                unitSpores.remove(gone.items[i], 0f);
                unitBurn.remove(gone.items[i], 0f);
            }
        }
        if(unitSpores.size == 0) return;
        // which are in view, by layer
        for(int i = 0; i < unitLists.size; i++) unitLists.get(i).clear();
        unitZ.clear();
        Core.camera.bounds(view);
        mindustry.game.Team team = Vars.player.team();
        for(Unit u : Groups.unit){
            float s = unitSpores.get(u.id, 0f);
            // (crawling units bend along their length: a picture drawn straight would not lie on them)
            if(s <= 0.01f || !u.isValid() || u.inFogTo(team) || u instanceof mindustry.gen.Crawlc) continue;
            // the body alone, as the game draws it at the unit's place and turn - legs and turning weapons are drawn
            // apart and move (the whole icon had them in their resting place: patches stood where no leg was)
            TextureRegion body = u.type.region;
            if(body == null || !body.found()) continue;
            float r = u.hitSize * 1.5f;
            if(!view.overlaps(u.x - r, u.y - r, r * 2f, r * 2f)) continue;
            // (as UnitType.draw sets it: its own flying layer, which a type may set)
            float z = u.elevation > 0.5f || (u.type.flying && u.dead) ? u.type.flyingLayer : u.type.groundLayer + Mathf.clamp(u.hitSize / 4000f, 0f, 0.01f);
            // (by the layer to a ten-thousandth: right after the units of that layer, before the heavier ones a hair
            // above them - all at one layer plus 0.02 laid a small unit's spores over a big one walking over it)
            z = Math.round(z * 10000f) / 10000f;
            int k = unitZ.indexOf(z);
            if(k < 0){
                k = unitZ.size;
                unitZ.add(z);
                if(unitLists.size <= k){
                    unitLists.add(new Seq<Unit>(false));
                    final int index = k;
                    unitRunners.add(() -> drawUnitSpores(unitLists.get(index), unitZ.get(index)));
                }
            }
            unitLists.get(k).add(u);
        }
        float z = Draw.z();
        for(int i = 0; i < unitZ.size; i++) Draw.draw(unitZ.get(i) + 0.00005f, unitRunners.get(i));
        Draw.z(z);
    }

    private static Texture unitPage;

    private static void drawUnitSpores(Seq<Unit> list, float layer){
        if(list.isEmpty()) return;
        // Where the units are cut away around the cursor (Cutaway): no spores on those (a ghost of the unit in the
        // hole), and the cutting shader put back for the rest of its band, as Scene.unitShapePass does
        boolean cut = Cutaway.covers(layer);
        Draw.shader(sporeShader);
        Draw.flush();
        sporeShader.bind();
        // (a unit is read in its own picture's frame: the turn and the seed left by the last turret or drill drawn
        // swirled its clumps - uniforms stay set on the program)
        sporeShader.setUniformf("u_pivot", 0f, 0f);
        sporeShader.setUniformf("u_rot", 1f, 0f);
        if(sporeShader.hasUniform("u_seed")) sporeShader.setUniformf("u_seed", 0f, 0f);
        if(sporeShader.hasUniform("u_headMask")) sporeShader.setUniformf("u_headMask", 0f);
        sporeShader.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        sporeShader.setUniformf("u_size", Core.camera.width, Core.camera.height);
        sporeShader.setUniformf("u_screen", viewW(), viewH());
        sporeShader.setUniformf("u_texSize", texWidth(), texHeight());
        sporeShader.setUniformf("u_time", Time.time / 60f);
        unitPage = null;
        boolean smoke = !Vars.state.isPaused();
        for(int i = 0; i < list.size; i++){
            Unit u = list.get(i);
            if(!u.isValid()) continue;
            if(cut && Mathf.within(u.x, u.y, Cutaway.clearX(), Cutaway.clearY(), Cutaway.clearR() + u.hitSize)) continue;
            float s = unitSpores.get(u.id, 0f), bn = unitBurn.get(u.id, 0f);
            // (green 0: the patches read off the picture, so they go along with the unit)
            // (a unit is small: at the buildings' amount hardly a clump fitted on it - half again as many)
            Draw.color(bn, 0f, 0f, Math.min(s * 1.5f, 0.8f));
            // (the page the unit's picture is on: its clumps are read off the picture by its texels)
            if(u.type.region != null && u.type.region.texture != null && u.type.region.texture != unitPage){
                Draw.flush();
                sporeShader.bind();
                sporeShader.setUniformf("u_texSize", u.type.region.texture.width, u.type.region.texture.height);
                unitPage = u.type.region.texture;
            }
            Draw.rect(u.type.region, u.x, u.y, u.rotation - 90f);
            if(smoke && bn > 0.1f && Mathf.chanceDelta(0.08f * bn * Math.min(s * 3f, 1f) * Math.max(u.hitSize / 8f, 1f))){
                float r = u.hitSize * 0.4f;
                mindustry.content.Fx.fireSmoke.at(u.x + Mathf.range(r), u.y + Mathf.range(r));
            }
        }
        if(cut) Draw.shader(Cutaway.shader());
        else Draw.shader();
        Draw.color();
    }

    /** The spores on the buildings: as the snow, but in clumps, violet, and on every part of a conveyor (they cling). */
    private static void drawSpores(){
        Draw.shader(sporeShader);
        drawing = sporeShader;
        Draw.flush();
        sporeShader.bind();
        sporeShader.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        sporeShader.setUniformf("u_size", Core.camera.width, Core.camera.height);
        sporeShader.setUniformf("u_screen", viewW(), viewH());
        sporeShader.setUniformf("u_texSize", texWidth(), texHeight());
        sporeShader.setUniformf("u_time", Time.time / 60f);
        pageNow = null;
        inPass = true;
        boolean smoke = !Vars.state.isPaused() && !headPass;
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid() || b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) continue;
            float size = b.block.size * Vars.tilesize;
            if(!view.overlaps(b.x - size, b.y - size, size * 2f, size * 2f)) continue;
            float age = Time.time - builtAt.get(b.pos(), -100000f);
            float a = sporeAmount * (1f - Math.max(burnt.get(b.pos(), 0f), ground.get(b.pos(), 0f))) * Mathf.clamp(age / 900f);
            if(a <= 0.01f) continue;
            float burn = burning.get(b.pos(), 0f);
            drawShape(b, burn, a);
            // burning spores give off smoke, more from a larger block and while more of them are left
            if(smoke && burn > 0.1f && Mathf.chanceDelta(0.10f * burn * Math.min(a * 3f, 1f) * b.block.size)){
                float r = size * 0.4f;
                mindustry.content.Fx.fireSmoke.at(b.x + Mathf.range(r), b.y + Mathf.range(r));
            }
        }
        inPass = false;
        Draw.shader();
        Draw.color();
    }
}
