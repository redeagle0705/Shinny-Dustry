package mshaders;

import arc.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;

import java.nio.*;

/**
 * Where the glow of the ground comes from, for the blocks that have a normal map.
 *
 * Ground that glows (molten slag, magma rock, hot rock, cryofluid) reports one light per tile. A lake of it is
 * hundreds of lights - far more than the shaders go through one by one - so those lights are not in the list of light
 * sources (LightTap.ground). Their glow on the ground is simply painted (Scene.paintGround); what a block with a normal
 * map needs to know is worked out here instead, for every tile in view:
 *   - how strong the glow is there: that of the strongest single tile in reach, as in the painted picture;
 *   - where it comes from: how strongly the glowing ground in reach pulls to each of the four sides, every glowing
 *     tile pulling towards itself by its weight. A face gets the more light the more glowing ground lies on its side;
 *     glow on the far side takes nothing away (kept as one pull, two lakes on opposite sides cancelled out and
 *     the block between them was lit from above only, its sides dark);
 *   - its colour.
 * Two small pictures, one texel per tile, which mshaders-shape.frag reads between the texels - so the direction turns
 * smoothly across a block. Nothing counts against the light limit. Each glowing tile is added to the tiles in its
 * reach here, on the processor; so the pictures are only made in frames that show a normal-mapped block and glowing
 * ground at once, and kept while neither the view (in whole tiles) nor the glowing tiles change.
 */
public class GlowField{
    /** Tiles the pictures have room for; a view larger than that gets no glow shading. */
    public static final int maxWidth = 512, maxHeight = 512;
    /** The weight of a light at its own place, per unit of opacity - as for the other lights (mshaders-shape.frag). */
    private static final float gain = 1.3f;
    /** Directions are kept as sideways distance per unit of height, squeezed from -lean..lean into a byte (LEAN in mshaders-shape.frag). */
    public static final float lean = 4f;
    /**
     * How high above the ground the glow of the ground is taken to come from, in world units (8 = one tile). The other
     * lights hang 10 + 0.08 radius up (13 for slag) - a lamp on a post. Glowing ground lies flat, so its light comes in
     * low: it catches the sides of a block that face it far more than its top.
     */
    public static final float glowHeight = 5f;

    private Texture colour, towards;
    private ByteBuffer colourData, towardsData;
    private float[] sum = new float[0], right, left, up, down, most, red, green, blue;
    private final IntMap<float[]> kernels = new IntMap<>();
    private long stamp;
    private boolean ready;
    /** How long the last working out took, in milliseconds (for the test report). */
    public float lastMillis;
    /** World position of the lower left corner of the first texel. */
    private float originX, originY;

    /** @return whether the pictures hold the glow of this frame */
    public boolean ready(){
        return ready;
    }

    /** Nothing to show this frame. */
    public void clear(){
        ready = false;
    }

    /**
     * Works the two pictures out from those ground lights (LightTap.ground) for the tiles in view.
     * @return false if there is nothing to show: no glowing ground, or the view is too large
     */
    public boolean build(FloatSeq ground){
        ready = false;
        int n = ground.size / LightTap.groundStride;
        if(n == 0) return false;
        float t = Vars.tilesize;
        // two tiles beyond the view: the shader reads between texels, and lights are reported that far out (BlockRenderer)
        int x0 = Math.round((Core.camera.position.x - Core.camera.width / 2f) / t) - 2, y0 = Math.round((Core.camera.position.y - Core.camera.height / 2f) / t) - 2;
        int w = Math.round((Core.camera.position.x + Core.camera.width / 2f) / t) + 2 - x0 + 1, h = Math.round((Core.camera.position.y + Core.camera.height / 2f) / t) + 2 - y0 + 1;
        if(w > maxWidth || h > maxHeight || w < 1 || h < 1) return false;

        float[] g = ground.items;
        long now = ((x0 * 31L + y0) * 31L + w) * 31L + h;
        for(int i = 0; i < n; i++){
            int o = i * LightTap.groundStride;
            now = now * 31L + Float.floatToIntBits(g[o]) * 17L + Float.floatToIntBits(g[o + 1]) + Float.floatToIntBits(g[o + 6]);
        }
        if(colour != null && now == stamp) return ready = true;

        if(colour == null){
            colour = new Texture(maxWidth, maxHeight);
            towards = new Texture(maxWidth, maxHeight);
            colour.setFilter(TextureFilter.linear, TextureFilter.linear);
            towards.setFilter(TextureFilter.linear, TextureFilter.linear);
            colourData = Buffers.newByteBuffer(maxWidth * maxHeight * 4);
            towardsData = Buffers.newByteBuffer(maxWidth * maxHeight * 4);
        }
        long started = System.nanoTime();
        originX = (x0 - 0.5f) * t;
        originY = (y0 - 0.5f) * t;
        int cells = w * h;
        if(sum.length < cells){
            sum = new float[cells];
            right = new float[cells];
            left = new float[cells];
            up = new float[cells];
            down = new float[cells];
            most = new float[cells];
            red = new float[cells];
            green = new float[cells];
            blue = new float[cells];
        }else{
            java.util.Arrays.fill(sum, 0, cells, 0f);
            java.util.Arrays.fill(right, 0, cells, 0f);
            java.util.Arrays.fill(left, 0, cells, 0f);
            java.util.Arrays.fill(up, 0, cells, 0f);
            java.util.Arrays.fill(down, 0, cells, 0f);
            java.util.Arrays.fill(most, 0, cells, 0f);
            java.util.Arrays.fill(red, 0, cells, 0f);
            java.util.Arrays.fill(green, 0, cells, 0f);
            java.util.Arrays.fill(blue, 0, cells, 0f);
        }

        for(int i = 0; i < n; i++){
            int o = i * LightTap.groundStride;
            float radius = g[o + 2], opacity = g[o + 6], top = Math.max(g[o + 3], Math.max(g[o + 4], g[o + 5]));
            // the colour made deeper, as it is painted on the ground (Scene.deep)
            float r = Scene.deep(g[o + 3], top), gr = Scene.deep(g[o + 4], top), b = Scene.deep(g[o + 5], top);
            int cx = Math.round(g[o] / t) - x0, cy = Math.round(g[o + 1] / t) - y0;
            int reach = (int)Math.ceil(radius / t);
            float[] kernel = kernel(radius, reach);
            int side = reach * 2 + 1;
            for(int dy = -reach; dy <= reach; dy++){
                int y = cy + dy;
                if(y < 0 || y >= h) continue;
                for(int dx = -reach; dx <= reach; dx++){
                    int x = cx + dx;
                    if(x < 0 || x >= w) continue;
                    int k = ((dy + reach) * side + dx + reach) * 3;
                    float weight = kernel[k] * opacity;
                    if(weight <= 0f) continue;
                    int cell = y * w + x;
                    sum[cell] += weight;
                    float px = kernel[k + 1] * opacity, py = kernel[k + 2] * opacity;
                    if(px > 0f) right[cell] += px; else left[cell] -= px;
                    if(py > 0f) up[cell] += py; else down[cell] -= py;
                    if(weight > most[cell]) most[cell] = weight;
                    red[cell] += weight * r;
                    green[cell] += weight * gr;
                    blue[cell] += weight * b;
                }
            }
        }

        colourData.clear();
        towardsData.clear();
        for(int cell = 0; cell < cells; cell++){
            float total = sum[cell];
            if(total <= 0f){
                colourData.putInt(0);
                towardsData.putInt(0);
                continue;
            }
            // strength as its square root: more steps for the faint outer part of the glow
            float strength = Mathf.clamp((float)Math.sqrt(most[cell] / gain));
            colourData.put(unit(red[cell] / total * strength)).put(unit(green[cell] / total * strength)).put(unit(blue[cell] / total * strength)).put(unit(strength));
            towardsData.put(unit(right[cell] / total / lean)).put(unit(left[cell] / total / lean)).put(unit(up[cell] / total / lean)).put(unit(down[cell] / total / lean));
        }
        colourData.flip();
        towardsData.flip();
        Gl.pixelStorei(Gl.unpackAlignment, 1);
        colour.bind();
        Gl.texSubImage2D(Gl.texture2d, 0, 0, 0, w, h, Gl.rgba, Gl.unsignedByte, colourData);
        towards.bind();
        Gl.texSubImage2D(Gl.texture2d, 0, 0, 0, w, h, Gl.rgba, Gl.unsignedByte, towardsData);
        stamp = now;
        lastMillis = (System.nanoTime() - started) / 1000000f;
        return ready = true;
    }

    /** Binds the two pictures to those texture units and sets the uniforms of mshaders-shape.frag. Leaves one of those units active. */
    public void bind(Shader shader, int colourUnit, int towardsUnit){
        boolean on = ready && colour != null;
        Scene.set(shader, "u_glowOn", on ? 1f : 0f);
        if(!on) return;
        colour.bind(colourUnit);
        towards.bind(towardsUnit);
        Scene.set(shader, "u_glow", colourUnit);
        Scene.set(shader, "u_glowDir", towardsUnit);
        if(shader.hasUniform("u_glowRect")) shader.setUniformf("u_glowRect", originX, originY, 1f / (maxWidth * Vars.tilesize), 1f / (maxHeight * Vars.tilesize));
    }

    /**
     * What a light of that radius adds to the tiles around it, three numbers per tile of the square it reaches: its
     * weight there (the fall-off the shading uses for every light), and that weight times the way from the tile to the
     * light, sideways per unit of height (glowHeight).
     */
    private float[] kernel(float radius, int reach){
        int key = Float.floatToIntBits(radius);
        float[] kernel = kernels.get(key);
        if(kernel != null) return kernel;
        int side = reach * 2 + 1;
        kernel = new float[side * side * 3];
        float height = glowHeight;
        for(int dy = -reach; dy <= reach; dy++){
            for(int dx = -reach; dx <= reach; dx++){
                float dist = (float)Math.sqrt(dx * dx + dy * dy) * Vars.tilesize;
                if(dist >= radius) continue;
                float f = 1f - dist / radius, weight = f * f * gain;
                int k = ((dy + reach) * side + dx + reach) * 3;
                kernel[k] = weight;
                kernel[k + 1] = weight * -dx * Vars.tilesize / height;
                kernel[k + 2] = weight * -dy * Vars.tilesize / height;
            }
        }
        kernels.put(key, kernel);
        return kernel;
    }

    private static byte unit(float value){
        return (byte)(int)(Mathf.clamp(value) * 255f + 0.5f);
    }
}
