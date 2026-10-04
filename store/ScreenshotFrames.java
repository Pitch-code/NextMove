import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Wraps real NextMove screenshots in captioned store frames.
 * Usage: java store/ScreenshotFrames.java <rawDir> <outDir>
 * Output: phone 1080x1920 and tablet 1440x2560 (both 9:16, valid for Play).
 */
public class ScreenshotFrames {
    static final Color INK = new Color(0x18231F), INK2 = new Color(0x26372F),
            SAFFRON = new Color(0xF5A524), MUTED = new Color(0xC6D3CD);

    static final String[][] CAPTIONS = {
        {"01-home", "One clear, safe next step", "Share it, say it, or set a reminder."},
        {"02-scam-alert", "Scam messages flagged in time", "See why it looks risky and what to do."},
        {"03-link-check", "Check any link or number", "Fake bank sites, short links, reported scams."},
        {"04-panic", "Scammed? Act in minutes", "Clear steps, 1930 helpline and reporting."},
        {"05-reminder", "Bills and dates, never missed", "Amounts, places and dates in one tap."},
        {"06-activity", "Your week of protection", "Messages checked, scams flagged, reminders."},
        {"07-languages", "Made for India", "Six Indian languages, large text, dark mode."},
    };

    static Font font(String[] files, int style, float size) {
        for (String f : files) {
            File file = new File(f);
            if (file.isFile()) {
                try { return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(size); }
                catch (Exception ignored) { }
            }
        }
        return new Font(Font.SANS_SERIF, style, Math.round(size));
    }

    static final String[] BOLD = {
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
        "/usr/share/fonts/google-noto/NotoSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"};
    static final String[] REG = {
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/google-noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"};

    static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(trial) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** Draws one frame in 1080x1920 design units, scaled to the target width. */
    static BufferedImage frame(BufferedImage shot, String title, String sub, int outW, int outH) {
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = outW / 1080.0;
        g.scale(s, s);

        g.setPaint(new GradientPaint(0, 0, INK, 0, 1920, INK2));
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(new Color(245, 165, 36, 28));
        g.fill(new Ellipse2D.Double(620, -260, 760, 760));
        g.setColor(new Color(255, 255, 255, 10));
        g.fill(new Ellipse2D.Double(-300, 1200, 900, 900));

        // Brand pill
        Font pill = font(BOLD, Font.BOLD, 30f);
        g.setFont(pill);
        int pw = g.getFontMetrics().stringWidth("NextMove") + 56;
        g.setColor(SAFFRON);
        g.fill(new RoundRectangle2D.Double(90, 96, pw, 58, 58, 58));
        g.setColor(INK);
        g.drawString("NextMove", 118, 137);

        // Title + subtitle
        Font tf = font(BOLD, Font.BOLD, 76f);
        g.setFont(tf);
        g.setColor(Color.WHITE);
        int y = 268;
        for (String line : wrap(title, g.getFontMetrics(), 900)) {
            g.drawString(line, 90, y);
            y += 90;
        }
        Font sf = font(REG, Font.PLAIN, 38f);
        g.setFont(sf);
        g.setColor(MUTED);
        y += 6;
        for (String line : wrap(sub, g.getFontMetrics(), 900)) {
            g.drawString(line, 90, y);
            y += 52;
        }

        // Device frame with the real screenshot, bleeding off the bottom edge.
        int top = Math.max(y + 46, 560);
        int devW = 800, bezel = 16;
        int devX = (1080 - devW) / 2;
        double scale = (devW - 2.0 * bezel) / shot.getWidth();
        int shotH = (int) Math.round(shot.getHeight() * scale);
        int devH = shotH + 2 * bezel;
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(devX + 6, top + 14, devW, devH, 92, 92));
        g.setColor(new Color(0x0E1512));
        g.fill(new RoundRectangle2D.Double(devX, top, devW, devH, 92, 92));
        g.setColor(new Color(255, 255, 255, 40));
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(devX, top, devW, devH, 92, 92));
        Shape screen = new RoundRectangle2D.Double(devX + bezel, top + bezel,
                devW - 2 * bezel, shotH, 72, 72);
        Graphics2D c = (Graphics2D) g.create();
        c.clip(screen);
        c.drawImage(shot, devX + bezel, top + bezel, devW - 2 * bezel, shotH, null);
        c.dispose();
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        File raw = new File(args[0]), out = new File(args[1]);
        new File(out, "phone").mkdirs();
        new File(out, "tablet").mkdirs();
        for (String[] cap : CAPTIONS) {
            File in = new File(raw, cap[0] + ".png");
            if (!in.isFile()) {
                System.out.println("missing " + in);
                continue;
            }
            BufferedImage shot = ImageIO.read(in);
            ImageIO.write(frame(shot, cap[1], cap[2], 1080, 1920), "png",
                    new File(out, "phone/" + cap[0] + ".png"));
            ImageIO.write(frame(shot, cap[1], cap[2], 1440, 2560), "png",
                    new File(out, "tablet/" + cap[0] + ".png"));
            System.out.println("framed " + cap[0] + " (" + shot.getWidth() + "x" + shot.getHeight() + ")");
        }
    }
}
