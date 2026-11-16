import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Normal maps and plain colour sprites for wall blocks, made from the game's own wall sprites by the rule of the
 * copper wall that was drawn by hand (heightmaps\hand-drawn\copper-wall-normal.png): the block is made of FLAT
 * FACES, nine kinds of them - facing up, down, left, right, the four diagonals, and level - each one colour of the
 * normal map. No soft slopes.
 *
 *   java tools\WallGen.java <folder with sprite-<name>-1x.png> <out folder> [hand-drawn normal map of copper-wall, to compare]
 *   writes <name>-normal.png and <name>-base.png for every sprite in the folder
 *
 * How a pixel gets its face. Wall sprites are painted as if lit from the upper right, in flat tones, and are the same
 * shape on both sides of the diagonal that runs from the upper left to the lower right. So a pixel is compared with
 * its mirror image across that diagonal:
 *   brighter than its mirror image  -> painted LIT:    a face that leans towards the upper right
 *   darker                          -> painted SHADED: a face that leans towards the lower left
 *   the same                        -> neither: level, or leaning along the other diagonal (which the light does not show)
 * Which way exactly, the painting does not say. That comes from where the pixel is:
 *   1. The rim: lit and shaded areas that reach the edge of the sprite are the bevel around the block. A lit rim pixel
 *      faces up if the top edge is clearly nearer than the right edge, right if the right edge is clearly nearer, and
 *      diagonally up-right in between (the corner). Shaded rim pixels the same with left, bottom and down-left.
 *   2. The two other corners: where the lit rim and the shaded rim meet (upper left, lower right) the sprite shows a
 *      strip that is neither lit nor shaded. A pixel that has lit rim close by towards the upper right and shaded rim
 *      close by towards the lower left is part of that corner face: up-left or down-right.
 *   3. Inside: a lit or shaded area that does not reach the edge (a boss, a groove, a panel) faces across its own
 *      length - a band lying flat faces up or down, an upright one right or left - on the side its tone says; a band
 *      that runs along the light, or a blob with no length to speak of, faces diagonally towards or away from the light.
 *   4. Everything else is level.
 *
 * The plain colour sprite is the sprite with the painted shading taken out: every pixel averaged with its mirror image.
 */
public class WallGen{
    // the nine faces, in the colours of the hand-drawn copper wall (direction: red = right, green = up; all lean the same amount)
    static final int LEVEL = 0xff8080ff, UP = 0xff80f6af, DOWN = 0xff8009af, LEFT = 0xff0980af, RIGHT = 0xfff680af,
        UP_LEFT = 0xff2cd3af, UP_RIGHT = 0xffd3d3af, DOWN_LEFT = 0xff2c2caf, DOWN_RIGHT = 0xffd32caf;

    static final float TONE = 0.35f;   // share of the sprite's strongest light-to-shade difference that counts as lit or shaded (less is anti-aliasing)
    static final int CORNER = 2;       // a rim pixel is a corner while its two edge distances differ by no more than this
    static final int REACH = 3;        // how far a corner strip pixel may be from the lit and the shaded rim (both together: the width of the strip + 1)
    static final int SPECK = 3;        // an inside area of fewer pixels than this is left level

    public static void main(String[] args) throws Exception{
        if(args.length < 2){
            System.out.println("usage: <folder with sprite-<name>-1x.png> <out folder> [hand-drawn copper-wall normal map]");
            return;
        }
        File out = new File(args[1]);
        out.mkdirs();
        File[] files = new File(args[0]).listFiles();
        Arrays.sort(files);
        for(File f : files){
            String n = f.getName();
            if(!n.startsWith("sprite-") || !n.endsWith("-1x.png")) continue;
            String name = n.substring(7, n.length() - 7);
            BufferedImage sprite = ImageIO.read(f);
            if(sprite.getWidth() != sprite.getHeight()){
                System.out.println(name + ": not square, skipped");
                continue;
            }
            int[] count = new int[4];
            BufferedImage normal = normal(sprite, count);
            ImageIO.write(normal, "png", new File(out, name + "-normal.png"));
            ImageIO.write(base(sprite), "png", new File(out, name + "-base.png"));
            String line = name + " " + sprite.getWidth() + "px: rim " + count[0] + ", corner strips " + count[1] + ", inside " + count[2] + ", level " + count[3];
            if(args.length > 2 && name.equals("copper-wall")){
                BufferedImage hand = ImageIO.read(new File(args[2]));
                int differ = 0;
                for(int x = 0; x < 32; x++) for(int y = 0; y < 32; y++) if((hand.getRGB(x, y) & 0xffffff) != (normal.getRGB(x, y) & 0xffffff)) differ++;
                line += "; pixels that differ from the hand-drawn map: " + differ + " of 1024";
            }
            System.out.println(line);
        }
    }

    static float luma(int c){
        return (0.299f * (c >> 16 & 0xff) + 0.587f * (c >> 8 & 0xff) + 0.114f * (c & 0xff)) / 255f;
    }

    static boolean solid(BufferedImage img, int x, int y){
        return x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && (img.getRGB(x, y) >>> 24) >= 128;
    }

    /** The sprite without its painted shading: each pixel averaged with its mirror image across the diagonal. */
    static BufferedImage base(BufferedImage sprite){
        int s = sprite.getWidth();
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < s; x++){
            for(int y = 0; y < s; y++){
                if(!solid(sprite, x, y)) continue;
                int a = sprite.getRGB(x, y), b = solid(sprite, y, x) ? sprite.getRGB(y, x) : a;
                out.setRGB(x, y, 0xff000000 | ((a >> 16 & 0xff) + (b >> 16 & 0xff) + 1) / 2 << 16 | ((a >> 8 & 0xff) + (b >> 8 & 0xff) + 1) / 2 << 8 | ((a & 0xff) + (b & 0xff) + 1) / 2);
            }
        }
        return out;
    }

    /** count: rim pixels, corner strip pixels, inside pixels, level pixels */
    static BufferedImage normal(BufferedImage sprite, int[] count){
        int s = sprite.getWidth();
        // 1 = painted lit, -1 = painted shaded, 0 = neither
        int[][] tone = new int[s][s];
        float strongest = 0f;
        for(int x = 0; x < s; x++){
            for(int y = 0; y < s; y++){
                if(solid(sprite, x, y) && solid(sprite, y, x)) strongest = Math.max(strongest, Math.abs(luma(sprite.getRGB(x, y)) - luma(sprite.getRGB(y, x))));
            }
        }
        float limit = Math.max(strongest * TONE, 0.02f);
        for(int x = 0; x < s; x++){
            for(int y = 0; y < s; y++){
                if(!solid(sprite, x, y) || !solid(sprite, y, x)) continue;
                float d = luma(sprite.getRGB(x, y)) - luma(sprite.getRGB(y, x));
                tone[x][y] = d > limit ? 1 : d < -limit ? -1 : 0;
            }
        }
        // areas of one tone that hang together; which of them reach the edge of the sprite
        int[][] area = new int[s][s];
        int areas = 0;
        java.util.List<int[]> members = new java.util.ArrayList<>();
        java.util.List<Boolean> atEdge = new java.util.ArrayList<>();
        members.add(null);
        atEdge.add(false);
        for(int x = 0; x < s; x++){
            for(int y = 0; y < s; y++){
                if(tone[x][y] == 0 || area[x][y] != 0) continue;
                areas++;
                ArrayDeque<int[]> open = new ArrayDeque<>();
                java.util.List<Integer> list = new java.util.ArrayList<>();
                boolean edge = false;
                open.add(new int[]{x, y});
                area[x][y] = areas;
                while(!open.isEmpty()){
                    int[] p = open.poll();
                    list.add(p[0]);
                    list.add(p[1]);
                    if(p[0] == 0 || p[1] == 0 || p[0] == s - 1 || p[1] == s - 1) edge = true;
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for(int[] d : around){
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if(nx < 0 || ny < 0 || nx >= s || ny >= s || area[nx][ny] != 0 || tone[nx][ny] != tone[x][y]) continue;
                        area[nx][ny] = areas;
                        open.add(new int[]{nx, ny});
                    }
                }
                int[] flat = new int[list.size()];
                for(int i = 0; i < flat.length; i++) flat[i] = list.get(i);
                members.add(flat);
                atEdge.add(edge);
            }
        }
        boolean rimmed = false;
        // how wide the untoned strip is where it starts, at the upper left corner of the sprite: the lit and the shaded
        // rim are that far apart there (3 pixels on the copper wall, 4 on the walls of the other planet)
        int strip = 0;
        while(strip < s / 4 && tone[strip][0] == 0) strip++;
        int span = Math.max(3, strip + 1);
        for(int i = 1; i <= areas; i++) rimmed |= atEdge.get(i);

        // the face of each inside area: across its own length, on the side its tone says
        int[] inside = new int[areas + 1];
        for(int i = 1; i <= areas; i++){
            if(atEdge.get(i)) continue;
            int[] m = members.get(i);
            int n = m.length / 2;
            double mx = 0, my = 0;
            for(int k = 0; k < n; k++){
                mx += m[k * 2];
                my += m[k * 2 + 1];
            }
            mx /= n;
            my /= n;
            double xx = 0, yy = 0, xy = 0;
            for(int k = 0; k < n; k++){
                double dx = m[k * 2] - mx, dy = m[k * 2 + 1] - my;
                xx += dx * dx;
                yy += dy * dy;
                xy += dx * dy;
            }
            // direction of the area's length, and how much longer than wide it is
            double angle = 0.5 * Math.atan2(2 * xy, xx - yy);
            double spread = Math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy), sum = xx + yy;
            double longer = (sum + spread) / Math.max(sum - spread, 1e-6);
            boolean lit = tone[m[0]][m[1]] > 0;
            int face = lit ? UP_RIGHT : DOWN_LEFT;
            if(n < SPECK) face = LEVEL;
            if(n >= 4 && longer > 2.5){
                double deg = Math.toDegrees(angle);   // -90..90, picture rows run downwards
                if(Math.abs(deg) < 22.5) face = lit ? UP : DOWN;               // lies flat
                else if(Math.abs(deg) > 67.5) face = lit ? RIGHT : LEFT;       // stands upright
                // runs along either diagonal: towards or away from the light, as set above
            }
            inside[i] = face;
        }

        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for(int x = 0; x < s; x++){
            for(int y = 0; y < s; y++){
                if(!solid(sprite, x, y)) continue;
                int top = y, bottom = s - 1 - y, left = x, right = s - 1 - x;
                int face = LEVEL;
                if(tone[x][y] != 0 && atEdge.get(area[x][y])){
                    if(tone[x][y] > 0) face = top < right - CORNER ? UP : right < top - CORNER ? RIGHT : UP_RIGHT;
                    else face = left < bottom - CORNER ? LEFT : bottom < left - CORNER ? DOWN : DOWN_LEFT;
                    count[0]++;
                }else if(tone[x][y] != 0){
                    face = inside[area[x][y]];
                    count[2]++;
                }else if(rimmed && steps(tone, area, atEdge, x, y, 1, -1, 1) + steps(tone, area, atEdge, x, y, -1, 1, -1) <= span){
                    face = left + top < right + bottom ? UP_LEFT : DOWN_RIGHT;
                    count[1]++;
                }else{
                    count[3]++;
                }
                out.setRGB(x, y, face);
            }
        }
        return out;
    }

    /**
     * How many steps it is to rim of that tone in that diagonal direction, with only untoned pixels in between; a large
     * number if there is none within REACH.
     * Running off the sprite counts as reaching it (the outermost pixels of a corner strip have nothing beyond them).
     */
    static int steps(int[][] tone, int[][] area, java.util.List<Boolean> atEdge, int x, int y, int dx, int dy, int wanted){
        int s = tone.length;
        for(int i = 1; i <= REACH; i++){
            int nx = x + dx * i, ny = y + dy * i;
            if(nx < 0 || ny < 0 || nx >= s || ny >= s) return i;
            if(tone[nx][ny] == 0) continue;
            return tone[nx][ny] == wanted && atEdge.get(area[nx][ny]) ? i : 99;
        }
        return 99;
    }
}
