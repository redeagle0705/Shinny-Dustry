import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Makes a first-draft height map from a block sprite, and a preview that shows what a height map does under light
 * from other directions. Meant to be corrected by hand afterwards. Runs with the JDK alone:
 *
 *   java tools\HeightGen.java gen     sprite.png  out-prefix      writes out-prefix-height.png (+ -height-8x.png, -preview.png, -base.png)
 *   java tools\HeightGen.java preview sprite.png  height.png  out.png   re-renders the preview from an edited height map
 *
 * Height map format: same size as the sprite, grey, alpha copied from the sprite.
 *   0 = the ground, 153 (60%) = the flat top of the block, brighter = higher than that, darker = lower.
 *
 * How the draft is made. Block sprites are painted as if lit from the upper right, in flat tones: a lighter tone is a
 * face that leans towards the light, a darker tone one that leans away. But a tone can also just be darker paint, and
 * brightness alone cannot tell the two apart. Most blocks, though, are the same shape when mirrored across the diagonal
 * that runs from the upper left to the lower right - and that mirror swaps "towards the light" with "away from it" while
 * leaving the paint where it is. So for each pixel:
 *     lean  = (brightness here - brightness at the mirrored pixel) / 2      the part that is shading
 *     paint = (colour here + colour at the mirrored pixel) / 2              the part that is paint
 * The height is then the surface whose slope towards the light matches -lean everywhere as well as possible (solved by
 * relaxation, with height 0 all around the sprite), laid on top of a flat plate.
 * Where a sprite is NOT symmetric across that diagonal (turret heads, arrows, anything with a direction) the draft is
 * wrong there; "gen" prints how symmetric the paint is so you know how far to trust it.
 */
public class HeightGen{
    static final float PLATE = 0.60f;      // height of the block's flat top, 0..1
    static final float RELIEF = 0.34f;     // the largest bump or pit, 0..1

    public static void main(String[] args) throws Exception{
        if(args.length == 3 && args[0].equals("gen")){
            BufferedImage sprite = ImageIO.read(new File(args[1]));
            float[][] h = draft(sprite);
            ImageIO.write(heightImage(sprite, h), "png", new File(args[2] + "-height.png"));
            ImageIO.write(enlarge(heightImage(sprite, h), 8), "png", new File(args[2] + "-height-8x.png"));
            ImageIO.write(preview(sprite, h), "png", new File(args[2] + "-preview.png"));
            ImageIO.write(base(sprite), "png", new File(args[2] + "-base.png"));
        }else if(args.length == 4 && args[0].equals("preview")){
            BufferedImage sprite = ImageIO.read(new File(args[1])), map = ImageIO.read(new File(args[2]));
            if(map.getWidth() != sprite.getWidth() || map.getHeight() != sprite.getHeight()) throw new IllegalArgumentException("height map and sprite differ in size");
            float[][] h = new float[sprite.getWidth()][sprite.getHeight()];
            for(int x = 0; x < h.length; x++){
                for(int y = 0; y < h[0].length; y++){
                    int c = map.getRGB(x, y);
                    h[x][y] = (c >>> 24) < 128 ? 0f : ((c >> 16 & 0xff) + (c >> 8 & 0xff) + (c & 0xff)) / 765f;
                }
            }
            ImageIO.write(preview(sprite, h), "png", new File(args[3]));
        }else if(args.length == 4 && args[0].equals("normal")){
            BufferedImage sprite = ImageIO.read(new File(args[1])), map = ImageIO.read(new File(args[2]));
            if(map.getWidth() != sprite.getWidth() || map.getHeight() != sprite.getHeight()) throw new IllegalArgumentException("normal map and sprite differ in size");
            ImageIO.write(previewNormal(sprite, map), "png", new File(args[3]));
        }else if(args.length == 3 && args[0].equals("belts")){
            belts(new File(args[1]), new File(args[2]));
        }else{
            System.out.println("usage: gen sprite.png out-prefix | preview sprite.png height.png out.png | normal sprite.png normal.png out.png | belts sprite-folder out-folder");
        }
    }

    /**
     * Height maps for conveyor sprites, straight from their colours. A conveyor sprite is painted in three flat parts,
     * and the parts are also its three heights: the belt (dark grey) lowest, the arrows on it (mid grey) a step up,
     * the rails along its sides (light grey on the basic conveyor, blue on titanium, purple on the armoured one)
     * highest. The three greys are the ones the first hand-drawn height map used (conveyor-4-3): 55, 98, 169 -
     * this rule gives back that map pixel for pixel.
     *   rail:  bright (brightness 125 or more of 255) or coloured (blue more than 24 above red)
     *   belt:  what is left and darker than 98
     *   arrow: the rest
     * Reads every  sprite-<name>-1x.png  in the folder (the test kit writes these: sprites=name+name) whose name is a
     * conveyor frame (...conveyor-<shape>-<frame>) and writes  <name>.png  into the out folder.
     */
    static void belts(File in, File out) throws Exception{
        out.mkdirs();
        int made = 0;
        File[] files = in.listFiles();
        java.util.Arrays.sort(files);
        for(File f : files){
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("sprite-(.*conveyor-[0-9]-[0-9])-1x[.]png").matcher(f.getName());
            if(!m.matches()) continue;
            BufferedImage sprite = ImageIO.read(f);
            BufferedImage map = new BufferedImage(sprite.getWidth(), sprite.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int[] count = new int[3];
            for(int x = 0; x < sprite.getWidth(); x++){
                for(int y = 0; y < sprite.getHeight(); y++){
                    int c = sprite.getRGB(x, y);
                    if((c >>> 24) < 128) continue;   // stays transparent
                    int r = c >> 16 & 0xff, b = c & 0xff;
                    float lum = luma(c) * 255f;
                    int part = lum >= 125f || b - r > 24 ? 2 : lum < 98f ? 0 : 1;
                    int v = part == 2 ? 169 : part == 1 ? 98 : 55;
                    count[part]++;
                    map.setRGB(x, y, 0xff000000 | v << 16 | v << 8 | v);
                }
            }
            ImageIO.write(map, "png", new File(out, m.group(1) + ".png"));
            System.out.println(m.group(1) + ": belt " + count[0] + ", arrows " + count[1] + ", rails " + count[2]);
            made++;
        }
        System.out.println(made + " height maps written to " + out);
    }

    /**
     * The same six panels for a NORMAL map: a picture whose colour says which way each pixel's face points,
     * red = left (0) to right (255), green = down (0) to up (255), blue = towards the viewer; flat is 128,128,255.
     * Lit with half the light coming from all around and half from a lamp 45 degrees above the horizon.
     * Prints how far the "lit from the upper right" panel is from the sprite itself, next to the same number for a
     * completely flat map - the map earns its keep by the amount it gets below that.
     */
    static BufferedImage previewNormal(BufferedImage sprite, BufferedImage map){
        int w = sprite.getWidth(), ht = sprite.getHeight(), k = 8, gap = 8;
        float[][] lights = {{1f, 1f}, {-1f, 1f}, {-1f, -1f}, {1f, -1f}};   // x to the right, y up
        BufferedImage out = new BufferedImage((w * k + gap) * 6 - gap, ht * k, BufferedImage.TYPE_INT_ARGB);
        double diff = 0, flatDiff = 0;
        int count = 0;
        for(int panel = 0; panel < 6; panel++){
            for(int x = 0; x < w; x++){
                for(int y = 0; y < ht; y++){
                    int c;
                    if(!solid(sprite, x, y)){
                        c = 0xff202428;
                    }else if(panel == 0){
                        c = sprite.getRGB(x, y);
                    }else if(panel == 1){
                        c = map.getRGB(x, y) | 0xff000000;
                    }else{
                        int m = map.getRGB(x, y);
                        float nx = ((m >> 16 & 0xff) - 127.5f) / 127.5f, ny = ((m >> 8 & 0xff) - 127.5f) / 127.5f, nz = ((m & 0xff) - 127.5f) / 127.5f;
                        float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if(len < 1e-4f){ nx = 0f; ny = 0f; nz = 1f; len = 1f; }
                        float[] l = lights[panel - 2];
                        // lamp direction (l.x, l.y, sqrt 2) / 2: 45 degrees up; a flat face gets 0.7071 of it
                        float lamp = Math.max(0f, (nx * l[0] + ny * l[1] + nz * 1.4142f) / (2f * len));
                        float shade = 0.5f + 0.5f * lamp / 0.7071f;
                        int a = sprite.getRGB(x, y), b = mirror(sprite, x, y);
                        float pr = ((a >> 16 & 0xff) + (b >> 16 & 0xff)) / 2f, pg = ((a >> 8 & 0xff) + (b >> 8 & 0xff)) / 2f, pb = ((a & 0xff) + (b & 0xff)) / 2f;
                        c = 0xff000000 | clamp(pr * shade) << 16 | clamp(pg * shade) << 8 | clamp(pb * shade);
                        if(panel == 2){
                            diff += Math.abs(luma(c) - luma(a));
                            flatDiff += Math.abs(luma(0xff000000 | clamp(pr) << 16 | clamp(pg) << 8 | clamp(pb)) - luma(a));
                            count++;
                        }
                    }
                    for(int dx = 0; dx < k; dx++){
                        for(int dy = 0; dy < k; dy++){
                            out.setRGB(panel * (w * k + gap) + x * k + dx, y * k + dy, c);
                        }
                    }
                }
            }
        }
        System.out.printf("lit from the upper right, mean brightness difference from the sprite: %.4f with this normal map, %.4f with a flat one (of 1.0)%n",
            diff / Math.max(count, 1), flatDiff / Math.max(count, 1));
        return out;
    }

    static boolean solid(BufferedImage img, int x, int y){
        return x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && (img.getRGB(x, y) >>> 24) >= 128;
    }

    static float luma(int c){
        return (0.299f * (c >> 16 & 0xff) + 0.587f * (c >> 8 & 0xff) + 0.114f * (c & 0xff)) / 255f;
    }

    /** The pixel mirrored across the upper-left to lower-right diagonal (square sprites; others are clamped). */
    static int mirror(BufferedImage img, int x, int y){
        int mx = Math.min(y, img.getWidth() - 1), my = Math.min(x, img.getHeight() - 1);
        return solid(img, mx, my) ? img.getRGB(mx, my) : img.getRGB(x, y);
    }

    /**
     * The sprite with its painted shading taken out: every pixel averaged with its mirror image across the diagonal
     * (the "paint" of the explanation at the top). A face painted light on the lit side and dark on the far side
     * comes out in one even colour on both. This is the picture to use as the block's plain colour sprite
     * (sprites-override) next to the height map.
     */
    static BufferedImage base(BufferedImage sprite){
        BufferedImage out = new BufferedImage(sprite.getWidth(), sprite.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < sprite.getWidth(); x++){
            for(int y = 0; y < sprite.getHeight(); y++){
                if(!solid(sprite, x, y)) continue;
                int a = sprite.getRGB(x, y), b = mirror(sprite, x, y);
                out.setRGB(x, y, 0xff000000 | clamp(((a >> 16 & 0xff) + (b >> 16 & 0xff)) / 2f) << 16 | clamp(((a >> 8 & 0xff) + (b >> 8 & 0xff)) / 2f) << 8 | clamp(((a & 0xff) + (b & 0xff)) / 2f));
            }
        }
        return out;
    }

    static float[][] draft(BufferedImage img){
        int w = img.getWidth(), ht = img.getHeight();
        float[][] lean = new float[w][ht];
        double shading = 0, paintSpread = 0;
        int count = 0;
        for(int x = 0; x < w; x++){
            for(int y = 0; y < ht; y++){
                if(!solid(img, x, y)) continue;
                lean[x][y] = (luma(img.getRGB(x, y)) - luma(mirror(img, x, y))) / 2f;
                shading += Math.abs(lean[x][y]);
                count++;
            }
        }
        // how alike the sprite is under the other mirror (lower left to upper right), which shading does not disturb:
        // a symmetric block scores near 0 here
        for(int x = 0; x < w; x++){
            for(int y = 0; y < ht; y++){
                int ox = Math.min(ht - 1 - y, w - 1), oy = Math.min(w - 1 - x, ht - 1);
                if(solid(img, x, y) && solid(img, ox, oy)) paintSpread += Math.abs(luma(img.getRGB(x, y)) - luma(img.getRGB(ox, oy)));
            }
        }
        System.out.printf("%dx%d, %d opaque pixels; mean shading %.3f; asymmetry across the other diagonal %.3f (near 0 = symmetric block, the draft can be trusted more)%n",
            w, ht, count, shading / Math.max(count, 1), paintSpread / Math.max(count, 1));

        // Slope wanted at each pixel: the height falls towards the light (right and up; y grows downwards) where the
        // face is lit. gx, gy = wanted gradient.
        float[][] gx = new float[w][ht], gy = new float[w][ht];
        for(int x = 0; x < w; x++){
            for(int y = 0; y < ht; y++){
                gx[x][y] = -lean[x][y] * 0.7071f;
                gy[x][y] = lean[x][y] * 0.7071f;
            }
        }
        // least-squares surface for that gradient: Poisson equation, height 0 outside the sprite, Gauss-Seidel
        float[][] div = new float[w][ht], r = new float[w][ht];
        for(int x = 0; x < w; x++){
            for(int y = 0; y < ht; y++){
                float left = x > 0 ? gx[x - 1][y] : 0f, up = y > 0 ? gy[x][y - 1] : 0f;
                div[x][y] = (gx[x][y] - left) + (gy[x][y] - up);
            }
        }
        for(int it = 0; it < 4000; it++){
            for(int x = 0; x < w; x++){
                for(int y = 0; y < ht; y++){
                    if(!solid(img, x, y)) continue;
                    float sum = (solid(img, x - 1, y) ? r[x - 1][y] : 0f) + (solid(img, x + 1, y) ? r[x + 1][y] : 0f)
                        + (solid(img, x, y - 1) ? r[x][y - 1] : 0f) + (solid(img, x, y + 1) ? r[x][y + 1] : 0f);
                    r[x][y] = (sum - div[x][y]) / 4f;
                }
            }
        }
        // The method already takes the block to be the same shape on both sides of the diagonal the light comes along;
        // the solution should be too, but the differences above are taken one pixel to the right and down, which shifts
        // it by half a pixel: the left edge of a wall came out lower than its right edge, a small step - and a visible
        // line - wherever two walls stand side by side. So the surface is averaged with its own mirror image, and with
        // the mirror image across the other diagonal as well when the sprite is the same on both sides of that one.
        if(w == ht){
            boolean both = paintSpread / Math.max(count, 1) < 0.01;
            float[][] even = new float[w][ht];
            for(int x = 0; x < w; x++){
                for(int y = 0; y < ht; y++){
                    even[x][y] = both ? (r[x][y] + r[y][x] + r[w - 1 - y][w - 1 - x] + r[w - 1 - x][w - 1 - y]) / 4f : (r[x][y] + r[y][x]) / 2f;
                }
            }
            r = even;
        }
        float max = 1e-6f;
        for(float[] col : r) for(float v : col) max = Math.max(max, Math.abs(v));
        float[][] h = new float[w][ht];
        for(int x = 0; x < w; x++){
            for(int y = 0; y < ht; y++){
                h[x][y] = solid(img, x, y) ? Math.max(0f, Math.min(1f, PLATE + r[x][y] / max * RELIEF)) : 0f;
            }
        }
        return h;
    }

    static BufferedImage heightImage(BufferedImage sprite, float[][] h){
        BufferedImage out = new BufferedImage(sprite.getWidth(), sprite.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < out.getWidth(); x++){
            for(int y = 0; y < out.getHeight(); y++){
                int v = Math.round(h[x][y] * 255f);
                out.setRGB(x, y, solid(sprite, x, y) ? 0xff000000 | v << 16 | v << 8 | v : 0);
            }
        }
        return out;
    }

    static BufferedImage enlarge(BufferedImage img, int k){
        BufferedImage out = new BufferedImage(img.getWidth() * k, img.getHeight() * k, BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < out.getWidth(); x++){
            for(int y = 0; y < out.getHeight(); y++){
                out.setRGB(x, y, img.getRGB(x / k, y / k));
            }
        }
        return out;
    }

    /**
     * Six panels, 8x: the sprite, the height map, then the paint (shading removed) lit through the height map from the
     * upper right - which should look like the sprite again if the height map is right - and from the upper left, the
     * lower left and the lower right.
     */
    static BufferedImage preview(BufferedImage sprite, float[][] h){
        int w = sprite.getWidth(), ht = sprite.getHeight(), k = 8, gap = 8;
        float[][] lights = {{0.7071f, -0.7071f}, {-0.7071f, -0.7071f}, {-0.7071f, 0.7071f}, {0.7071f, 0.7071f}};
        BufferedImage out = new BufferedImage((w * k + gap) * 6 - gap, ht * k, BufferedImage.TYPE_INT_ARGB);
        for(int panel = 0; panel < 6; panel++){
            for(int x = 0; x < w; x++){
                for(int y = 0; y < ht; y++){
                    int c;
                    if(!solid(sprite, x, y)){
                        c = 0xff202428;
                    }else if(panel == 0){
                        c = sprite.getRGB(x, y);
                    }else if(panel == 1){
                        int v = Math.round(h[x][y] * 255f);
                        c = 0xff000000 | v << 16 | v << 8 | v;
                    }else{
                        // slope of the height map here (central differences; outside the sprite counts as the same height,
                        // so the outer edge does not turn into a cliff)
                        float hx = (at(sprite, h, x + 1, y, x, y) - at(sprite, h, x - 1, y, x, y)) / 2f;
                        float hy = (at(sprite, h, x, y + 1, x, y) - at(sprite, h, x, y - 1, x, y)) / 2f;
                        float[] l = lights[panel - 2];
                        // brighter where the surface falls towards the light
                        float shade = 1f - (hx * l[0] + hy * l[1]) * 9f;
                        shade = Math.max(0.45f, Math.min(1.6f, shade));
                        int a = sprite.getRGB(x, y), b = mirror(sprite, x, y);
                        c = 0xff000000
                            | clamp(((a >> 16 & 0xff) + (b >> 16 & 0xff)) / 2f * shade) << 16
                            | clamp(((a >> 8 & 0xff) + (b >> 8 & 0xff)) / 2f * shade) << 8
                            | clamp(((a & 0xff) + (b & 0xff)) / 2f * shade);
                    }
                    for(int dx = 0; dx < k; dx++){
                        for(int dy = 0; dy < k; dy++){
                            out.setRGB(panel * (w * k + gap) + x * k + dx, y * k + dy, c);
                        }
                    }
                }
            }
        }
        return out;
    }

    static float at(BufferedImage sprite, float[][] h, int x, int y, int fromX, int fromY){
        return solid(sprite, x, y) ? h[x][y] : h[fromX][fromY];
    }

    static int clamp(float v){
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
