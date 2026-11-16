import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a normal-map colour wheel (a picture of a faceted dome seen from above, the kind used to pick normal map
 * colours by eye) into a palette with exactly the wheel's colours, so a colour can be picked by name instead of by
 * aiming at the right facet.
 *
 *   java tools\NormalPalette.java wheel.png <centre x> <centre y> <out prefix>
 *   writes <prefix>.png        the colours laid out by direction (columns) and tilt (rows), each with its hex value
 *          <prefix>.gpl        the same as a palette file (GIMP format; Aseprite, Krita, Inkscape read it too)
 *          <prefix>-strip.png  one pixel per colour, for editors that load a palette from a picture
 *
 * The wheel is read along 16 spokes from its centre (every 22.5 degrees): each stretch of one colour at least 12
 * pixels long is a facet. Direction and tilt are worked out from the colour itself (red = right, green = up, blue =
 * towards the viewer), not from where it was found. Tilt = how far the face leans over: 0 = level, 90 = upright.
 * The thin outermost rim of the wheel is added as a last row, worked out where it cannot be read (marked in the sheet).
 */
public class NormalPalette{
    static final String[] NAMES = {"오른쪽", "", "오른쪽 위", "", "위", "", "왼쪽 위", "", "왼쪽", "", "왼쪽 아래", "", "아래", "", "오른쪽 아래", ""};
    static final String[] ARROWS = {"→", "", "↗", "", "↑", "", "↖", "", "←", "", "↙", "", "↓", "", "↘", ""};

    public static void main(String[] args) throws Exception{
        if(args.length != 4){
            System.out.println("usage: wheel.png <centre x> <centre y> <out prefix>");
            return;
        }
        BufferedImage wheel = ImageIO.read(new File(args[0]));
        int cx = Integer.parseInt(args[1]), cy = Integer.parseInt(args[2]);

        // colours[spoke] = the facets met from the centre outwards, without the level centre
        List<List<Integer>> colours = new ArrayList<>();
        int level = wheel.getRGB(cx, cy) & 0xffffff, rows = 0;
        for(int spoke = 0; spoke < 16; spoke++){
            double angle = Math.toRadians(spoke * 22.5);
            List<Integer> found = new ArrayList<>();
            int last = -1, run = 0;
            for(int r = 0; r < Math.max(wheel.getWidth(), wheel.getHeight()); r++){
                int x = (int)Math.round(cx + Math.cos(angle) * r), y = (int)Math.round(cy - Math.sin(angle) * r);
                if(x < 0 || y < 0 || x >= wheel.getWidth() || y >= wheel.getHeight()) break;
                int c = wheel.getRGB(x, y) & 0xffffff;
                if(c == last){
                    run++;
                    continue;
                }
                if(run >= 12 && tilt(last) > 5) found.add(last);
                // level again after the facets: the spoke has left the wheel (other figures may lie further out on the picture)
                if(run >= 12 && tilt(last) <= 5 && !found.isEmpty()) break;
                last = c;
                run = 1;
            }
            // what lies beyond the wheel (the background) is level again and was skipped by the tilt test
            colours.add(found);
            rows = Math.max(rows, found.size());
        }
        // keep the rows every spoke has ...
        int common = rows;
        for(List<Integer> f : colours) common = Math.min(common, f.size());
        // ... and add the outermost rim of the wheel as one more row. It is too thin to be read on most spokes, so it
        // is worked out: every face of the rim leans the same amount, which the blue of a rim colour that WAS read
        // gives (blue = how much the face points at the viewer), and only the direction differs from spoke to spoke.
        // Where a spoke did give a rim colour, that colour is kept and compared with the worked-out one.
        boolean[] computed = new boolean[16];
        int rim = -1;
        for(List<Integer> f : colours) if(f.size() > common) rim = f.get(common);
        if(rim >= 0){
            double nz = (rim & 0xff) / 255.0 * 2 - 1, lean = Math.sqrt(1 - nz * nz);
            int same = 0, read = 0;
            for(int spoke = 0; spoke < 16; spoke++){
                double angle = Math.toRadians(spoke * 22.5);
                int made = (int)Math.round((lean * Math.cos(angle) * 0.5 + 0.5) * 255) << 16 | (int)Math.round((lean * Math.sin(angle) * 0.5 + 0.5) * 255) << 8 | rim & 0xff;
                List<Integer> f = colours.get(spoke);
                if(f.size() > common){
                    read++;
                    if(f.get(common) == made) same++;
                    while(f.size() > common + 1) f.remove(f.size() - 1);
                }else{
                    f.add(made);
                    computed[spoke] = true;
                }
            }
            System.out.println("rim (tilt " + Math.round(tilt(rim)) + "): " + read + " colours read from the wheel, " + same + " of them equal to the worked-out colour; " + (16 - read) + " worked out");
            common++;
        }
        rows = common;
        int rimRow = rim >= 0 ? rows - 1 : -1;

        int cellW = 74, cellH = 62, swatch = 40, left = 70, top = 64;
        BufferedImage sheet = new BufferedImage(left + cellW * 16 + 10, top + cellH * (rows + 1) + 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x20, 0x24, 0x28));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        Font small = new Font("Malgun Gothic", Font.PLAIN, 12), bold = new Font("Malgun Gothic", Font.BOLD, 13);
        StringBuilder gpl = new StringBuilder("GIMP Palette\nName: Normal map faces\nColumns: 16\n#\n");
        BufferedImage strip = new BufferedImage(16, rows + 1, BufferedImage.TYPE_INT_RGB);

        g.setColor(Color.white);
        for(int spoke = 0; spoke < 16; spoke++){
            g.setFont(bold);
            g.drawString(ARROWS[spoke].isEmpty() ? String.format("%.1f°", spoke * 22.5) : ARROWS[spoke] + " " + (int)(spoke * 22.5) + "°", left + spoke * cellW + 6, 24);
            g.setFont(small);
            if(!NAMES[spoke].isEmpty()) g.drawString(NAMES[spoke], left + spoke * cellW + 6, 44);
        }
        for(int row = 0; row < rows; row++){
            g.setColor(Color.white);
            g.setFont(bold);
            g.drawString("기울기 " + Math.round(tilt(colours.get(0).get(row))) + "°", 4, top + row * cellH + 28);
            for(int spoke = 0; spoke < 16; spoke++){
                int c = colours.get(spoke).get(row);
                g.setColor(new Color(c));
                // the colour itself is left clean (nothing written on it, so it can be picked anywhere); its value goes underneath
                g.fillRect(left + spoke * cellW, top + row * cellH, cellW - 3, swatch);
                g.setColor(new Color(0xd0, 0xd4, 0xd8));
                g.setFont(small);
                g.drawString(String.format("#%06x", c) + (row == rimRow && computed[spoke] ? " *" : ""), left + spoke * cellW + 6, top + row * cellH + swatch + 15);
                gpl.append(String.format("%3d %3d %3d\tdir %s tilt %d\n", c >> 16 & 0xff, c >> 8 & 0xff, c & 0xff,
                    String.format("%.1f", spoke * 22.5), Math.round(tilt(c))));   // plain letters only: some editors cannot read other characters in a palette file
                strip.setRGB(spoke, row, c);
            }
        }
        // the level face, last
        g.setColor(Color.white);
        g.setFont(bold);
        g.drawString("평평", 4, top + rows * cellH + 28);
        g.setColor(new Color(level));
        g.fillRect(left, top + rows * cellH, cellW - 3, swatch);
        g.setColor(new Color(0xd0, 0xd4, 0xd8));
        g.setFont(small);
        g.drawString(String.format("#%06x", level), left + 6, top + rows * cellH + swatch + 15);
        if(rimRow >= 0) g.drawString("* 색상환에서 읽히지 않아 같은 기울기로 계산해 채운 색", left + cellW * 2, top + rows * cellH + swatch + 15);
        gpl.append(String.format("%3d %3d %3d\tlevel\n", level >> 16 & 0xff, level >> 8 & 0xff, level & 0xff));
        for(int spoke = 0; spoke < 16; spoke++) strip.setRGB(spoke, rows, level);
        g.dispose();

        ImageIO.write(sheet, "png", new File(args[3] + ".png"));
        ImageIO.write(strip, "png", new File(args[3] + "-strip.png"));
        try(PrintWriter out = new PrintWriter(new File(args[3] + ".gpl"), StandardCharsets.UTF_8)){
            out.print(gpl);
        }
        System.out.println(rows + " tilts x 16 directions + level = " + (rows * 16 + 1) + " colours");
        for(int row = 0; row < rows; row++){
            StringBuilder s = new StringBuilder("tilt " + Math.round(tilt(colours.get(0).get(row))) + ":");
            for(int spoke = 0; spoke < 16; spoke++) s.append(String.format(" %06x", colours.get(spoke).get(row)));
            System.out.println(s);
        }
    }

    /** How far the face of that colour leans over, in degrees. */
    static double tilt(int c){
        double x = (c >> 16 & 0xff) / 255.0 * 2 - 1, y = (c >> 8 & 0xff) / 255.0 * 2 - 1, z = (c & 0xff) / 255.0 * 2 - 1;
        return Math.toDegrees(Math.acos(z / Math.sqrt(x * x + y * y + z * z)));
    }

    static float luma(int c){
        return (0.299f * (c >> 16 & 0xff) + 0.587f * (c >> 8 & 0xff) + 0.114f * (c & 0xff)) / 255f;
    }
}
