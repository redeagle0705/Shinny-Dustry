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
    private static Shader shader;
    private static final Seq<Building> builds = new Seq<>();
    /** How far the snow on each building has melted, by position: 0 = all there .. 1 = gone. */
    private static final IntFloatMap melt = new IntFloatMap();
    /** The thawed places on the ground around hot buildings, by position: x, y, reach, how far thawed. */
    private static final IntMap<float[]> spots = new IntMap<>();
    private static final IntSet selfHot = new IntSet();
    /** When each building put up during this game was finished (Time.time), by position. */
    private static final IntFloatMap builtAt = new IntFloatMap();
    private static final FloatSeq sources = new FloatSeq();
    private static long lastLook = -100;
    private static float lastTime, amount;
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
        if(amount > 0.01f && stamps.size < 4 * 600) stamps.add(x, y, r, a);
    }

    private static void updateMelt(){
        meltMap = null;
        if(amount <= 0.01f || fade == null || !Vars.state.isGame()){
            stamps.clear();
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
                "void main(){\n" +
                "    vec4 c = texture2D(u_texture, v_texCoords);\n" +
                "    if(c.a < 0.5) discard;\n" +
                "    vec2 world = u_campos + gl_FragCoord.xy / u_screen * u_size;\n" +
                "    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
                // patches, first on what is drawn lighter; v_color.a = how much snow there is on this building
                "    float n = noise(world / 5.0) * 0.6 + noise(world / 1.7) * 0.4;\n" +
                "    float th = 1.15 - v_color.a * 1.1;\n" +
                "    float cover = smoothstep(th, th + 0.18, n * 0.75 + lum * 0.45);\n" +
                "    vec3 snow = vec3(0.90, 0.94, 1.0) * (0.78 + 0.25 * lum);\n" +
                "    gl_FragColor = vec4(snow, cover * 0.75);\n" +
                "}\n");
        }catch(Throwable t){
            Log.err("[mshaders] snow on buildings unavailable", t);
            return;
        }
        try{
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
            spots.clear();
            builtAt.clear();
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
        amount = Math.min(Skies.snow * Skies.strength() * 1.8f, 1f) * 0.48f;
        if(amount <= 0.01f || Vars.state.isMenu()){
            melt.clear();
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
        }
        if(builds.isEmpty()) return;
        // the buildings hot of themselves this frame (by their own heat, not by how far their snow has melted: by that,
        // hot buildings side by side kept each other warm for ever and nothing froze over again)
        sources.clear();
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float own = Math.max(HitHeat.heatAt(b.pos()), HeatHaze.canBeHot(b.block) ? HeatHaze.heatOf(b) : 0f);
            if(own > 0.05f){
                selfHot.add(b.pos());
                sources.add(b.x, b.y, b.block.size * Vars.tilesize * 0.5f + 14f + b.block.size * 4f, own);
            }else{
                selfHot.remove(b.pos());
            }
        }
        // melting: fast on a hot building (a second or two), back slowly once it is cold (some 10 seconds)
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(!b.isValid()) continue;
            float own = Math.max(HitHeat.heatAt(b.pos()), HeatHaze.canBeHot(b.block) ? HeatHaze.heatOf(b) : 0f);
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
            float warm = Math.max(own, near * 0.8f);
            float m = melt.get(key, 0f);
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
            spot[2] = b.block.size * Vars.tilesize * 0.5f + 14f + b.block.size * 4f;
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
        Draw.z(z);
    }

    private static final float railShare = 0.125f;

    /** The two rails of a conveyor's picture (it faces right: the top and bottom eighth), by block id. */
    private static final IntMap<TextureRegion[]> railRegions = new IntMap<>();

    private static TextureRegion[] rails(Block block){
        TextureRegion[] r = railRegions.get(block.id);
        if(r == null){
            TextureRegion icon = block.fullIcon;
            // (the rails are about an eighth of it: a quarter reached onto the belt, and snow lay on its edge)
            int w = icon.width, h = icon.height, q = Math.max(Math.round(h * railShare), 1);
            r = new TextureRegion[]{new TextureRegion(icon, 0, 0, w, q), new TextureRegion(icon, 0, h - q, w, q)};
            railRegions.put(block.id, r);
        }
        return r;
    }

    private static void drawAll(){
        shader.bind();
        Draw.shader(shader);
        shader.setUniformf("u_campos", Core.camera.position.x - Core.camera.width / 2f, Core.camera.position.y - Core.camera.height / 2f);
        shader.setUniformf("u_size", Core.camera.width, Core.camera.height);
        shader.setUniformf("u_screen", Core.graphics.getWidth(), Core.graphics.getHeight());
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            // (turrets turn their heads, and the picture of the block would not follow; on belts that keep moving -
            // conveyors, ducts - snow does not stay: "snow on the moving part of a conveyor is a bit odd")
            if(!b.isValid() || b.block instanceof BaseTurret) continue;
            float size = b.block.size * Vars.tilesize;
            if(!view.overlaps(b.x - size, b.y - size, size * 2f, size * 2f)) continue;
            // (a building just put up is bare, and gathers its snow over some 15 seconds: at first it came with the
            // snow already on - "snow lies on a building the moment it is built"; and none on one still being built)
            if(b instanceof mindustry.world.blocks.ConstructBlock.ConstructBuild) continue;
            float age = Time.time - builtAt.get(b.pos(), -100000f);
            float a = amount * (1f - melt.get(b.pos(), 0f)) * Mathf.clamp(age / 900f);
            if(a <= 0.01f) continue;
            Draw.color(1f, 1f, 1f, a);
            // A conveyor whose belt is running: snow only on its rails, the two edges along it (the belt carries it
            // away); a stopped one - clogged, or switched off - is covered all over ("the parts of a conveyor that
            // don't move get covered, and the whole of it when it is blocked")
            if(b instanceof Conveyor.ConveyorBuild c && c.enabled && c.clogHeat <= 0.5f && c.efficiency > 0f){
                TextureRegion[] rails = rails(b.block);
                float len = b.block.size * Vars.tilesize, wide = len * railShare, off = (len - wide) / 2f;
                float r = b.rotdeg();
                Draw.rect(rails[0], b.x + Angles.trnsx(r + 90f, off), b.y + Angles.trnsy(r + 90f, off), len, wide, r);
                Draw.rect(rails[1], b.x + Angles.trnsx(r - 90f, off), b.y + Angles.trnsy(r - 90f, off), len, wide, r);
                continue;
            }
            Draw.rect(b.block.fullIcon, b.x, b.y, b.block.rotate ? b.rotdeg() : 0f);
        }
        Draw.shader();
        Draw.color();
    }
}
