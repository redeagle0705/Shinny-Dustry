package mshaders;

import arc.*;
import arc.graphics.gl.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.graphics.*;

/**
 * While a block is being placed, the units that stand or fly over the ground (legged units, flying units) are opened up
 * in a circle around the cursor so what lies under them can be seen ("when air or legged units hide where I build"):
 * dithered (a fine checker pattern lets the ground show through, more towards the middle) or cut away (a clean hole
 * with a dithered rim). Done by drawing those layers through a shader of their own that drops pixels near the cursor
 * (the game's own sprite shader otherwise; mshaders-cutaway.frag).
 */
public class Cutaway{
    private static Shader shader;
    /** The cursor, in screen pixels of the frame being drawn, and the radius; 0 radius = off this frame. */
    private static float cx, cy, radius;
    private static int style;

    public static void init(){
        try{
            shader = new Shader(VERT, Vars.tree.get("shaders/mshaders-cutaway.frag").readString()){
                @Override
                public void apply(){
                    setUniformf("u_cursor", cx, cy);
                    setUniformf("u_radius", radius);
                    setUniformf("u_style", style);
                }
            };
        }catch(Throwable t){
            Log.err("[mshaders] cut-away around the cursor unavailable", t);
            return;
        }
        Events.run(Trigger.draw, Cutaway::draw);
    }

    private static final Vec2 world = new Vec2();
    /** For the test kit: where the cursor is taken to be (world units); NaN = the real cursor. */
    public static float testX = Float.NaN, testY = Float.NaN;

    private static boolean placing(){
        var input = Vars.control.input;
        return input != null && (input.block != null || !input.selectPlans.isEmpty());
    }

    /** The frame the cut-away was set up in, and its circle in world units (for the units' shading pass, Scene). */
    private static long activeFrame = -1;
    private static float worldX, worldY, worldR;

    /** Whether units drawn at this layer are cut away in this frame. */
    static boolean covers(float layer){
        if(activeFrame != Core.graphics.getFrameId() || shader == null) return false;
        return (layer >= Layer.legUnit - 1.5f && layer < Layer.legUnit + 0.9f)
            || (layer >= Layer.flyingUnitLow - 0.5f && layer < Layer.flyingUnitLow + 0.9f)
            || (layer >= Layer.flyingUnit - 0.5f && layer < Layer.flyingUnit + 0.9f);
    }

    static Shader shader(){
        return shader;
    }

    /** The middle of the circle, where nothing of the units is left (world units): x, y, radius. */
    static float clearX(){ return worldX; }
    static float clearY(){ return worldY; }
    static float clearR(){
        // dithered: clean to 45% of the radius; cut away: all of it; fade: nearly nothing left inside 35%
        return worldR * (style == 2 ? 1f : style == 3 ? 0.35f : 0.45f);
    }

    private static void draw(){
        style = Core.settings.getInt(ShaderMod.cutawayStyle, 1);
        if(style <= 0 || !ShaderMod.inWorld() || !placing()) return;
        // (with the game's "pixelate" the units are drawn into a picture the size of the camera, not of the window:
        // the circle in window pixels would be in the wrong place)
        if(Vars.renderer.pixelate) return;
        var input = Vars.control.input;
        if(Float.isNaN(testX)) world.set(Core.input.mouseWorld());
        else world.set(testX, testY);
        float size = input.block != null ? input.block.size * Vars.tilesize : Vars.tilesize;
        float pxw = Core.camera.width / Math.max(Core.graphics.getWidth(), 1);
        // the circle: well past the block, never smaller than six tiles across (at first it was half this: "a bit larger")
        float worldRadius = Math.max(size * 1.6f + 20f, 48f);
        // a schematic being placed (its plans follow the cursor): the circle is just large enough to show all of it,
        // around its middle ("when placing a schematic, just enough that the whole of it shows")
        if(input.block == null && !input.selectPlans.isEmpty()){
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for(var plan : input.selectPlans){
                float half = plan.block.size * Vars.tilesize / 2f;
                float px = plan.drawx(), py = plan.drawy();
                x0 = Math.min(x0, px - half);
                y0 = Math.min(y0, py - half);
                x1 = Math.max(x1, px + half);
                y1 = Math.max(y1, py + half);
            }
            world.set((x0 + x1) / 2f, (y0 + y1) / 2f);
            worldRadius = Mathf.dst(x1 - x0, y1 - y0) / 2f + 16f;
        }
        worldRadius *= ShaderMod.percent(ShaderMod.cutawaySize, 100);
        radius = worldRadius / pxw;
        activeFrame = Core.graphics.getFrameId();
        worldX = world.x;
        worldY = world.y;
        worldR = worldRadius;
        cx = (world.x - Core.camera.position.x + Core.camera.width / 2f) / pxw;
        cy = (world.y - Core.camera.position.y + Core.camera.height / 2f) / pxw;
        // legged units, low flying units, flying units: each layer drawn through the shader
        wrap(Layer.legUnit - 1.5f, Layer.legUnit + 0.9f);
        wrap(Layer.flyingUnitLow - 0.5f, Layer.flyingUnitLow + 0.9f);
        wrap(Layer.flyingUnit - 0.5f, Layer.flyingUnit + 0.9f);
    }

    private static void wrap(float from, float to){
        Draw.draw(from, () -> Draw.shader(shader));
        Draw.draw(to, () -> Draw.shader());
    }

    static final String VERT = "attribute vec4 a_position;\n" +
        "attribute vec4 a_color;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "attribute vec4 a_mix_color;\n" +
        "uniform mat4 u_projTrans;\n" +
        "varying vec4 v_color;\n" +
        "varying vec4 v_mix_color;\n" +
        "varying vec2 v_texCoords;\n" +
        "void main(){\n" +
        "   v_color = a_color;\n" +
        "   v_color.a = v_color.a * (255.0/254.0);\n" +
        "   v_mix_color = a_mix_color;\n" +
        "   v_mix_color.a *= (255.0/254.0);\n" +
        "   v_texCoords = a_texCoord0;\n" +
        "   gl_Position = u_projTrans * a_position;\n" +
        "}";
}
