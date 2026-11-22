import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class RenderRpmPreview {
    private static final int MAX_RPM = 8000;
    private static final int SHIFT_RPM = 5800;
    private static final int REDLINE_RPM = 6500;
    private static final Color BLUE = new Color(0x00A8FF);
    private static final Color GREEN = new Color(0x22C55E);
    private static final Color YELLOW = new Color(0xEAB308);
    private static final Color RED = new Color(0xEF4444);

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "rpm_radial_preview_v3.png";
        int W = 1640, H = 1280;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(new Color(0x05070B));
        g.fillRect(0, 0, W, H);

        // Header
        g.setColor(new Color(0xF3F5F8));
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.drawString("ApexOBD — RPM Radial v3 (Electric Blue Sweep · Green → Yellow → Red Shift Lamp)", 36, 46);
        g.setColor(new Color(0x8B96A8));
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.drawString("Sweep stays Electric Blue (#00A8FF) + always-red Redline (6,500)  ·  Shift lamp (no label): Row 1 Green (3,900)  ·  Row 2 Yellow (5,100)  ·  Row 3 Red Flash (6,150)", 36, 72);

        int cardW = 372, cardH = 370, gap = 22, startX = 36;
        String[] titles = {
            "A · GT Motorsport Tach",
            "B · Sequential F1 LEDs",
            "C · Track Chrono",
            "D · Compact OLED Halo"
        };

        int[] rpms = {3900, 5100, 6150};
        String[] rowTags = {"LAMP: GREEN", "LAMP: YELLOW", "LAMP: RED FLASH"};
        boolean[] flashes = {false, false, true};

        for (int row = 0; row < 3; row++) {
            int y = 94 + row * (cardH + 20);
            for (int col = 0; col < 4; col++) {
                int x = startX + col * (cardW + gap);
                drawCard(g, x, y, cardW, cardH, titles[col] + "  ·  " + rowTags[row], flashes[row]);
                drawGauge(g, col, x, y + 32, cardW, cardH - 32, rpms[row], flashes[row]);
            }
        }

        g.dispose();
        File out = new File(outPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    private static int shiftStage(int rpm) {
        int trigger = Math.min(SHIFT_RPM, REDLINE_RPM);
        if (rpm >= trigger || rpm >= REDLINE_RPM) return 3; // RED_FLASH
        if (rpm >= trigger * 0.80) return 2; // YELLOW
        if (rpm >= trigger * 0.60) return 1; // GREEN
        return 0; // OFF
    }

    private static void drawCard(Graphics2D g, int x, int y, int w, int h, String title, boolean shiftFlash) {
        g.setColor(Color.BLACK);
        g.fillRoundRect(x, y, w, h, 20, 20);
        if (shiftFlash) {
            g.setColor(new Color(239, 68, 68, 220));
            g.setStroke(new BasicStroke(3f));
        } else {
            g.setColor(new Color(255, 255, 255, 38));
            g.setStroke(new BasicStroke(1.5f));
        }
        g.drawRoundRect(x, y, w, h, 20, 20);

        g.setColor(shiftFlash ? new Color(0xFCA5A5) : new Color(0xDCE4F0));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString(title, x + 14, y + 22);
    }

    private static void drawShiftDot(Graphics2D g, double cx, double cy, double r, int stage, boolean flashOn) {
        Color fill = new Color(0x181F2A);
        Color stroke = new Color(255, 255, 255, 52);
        Color glow = null;
        if (stage == 1) {
            fill = GREEN; stroke = new Color(0x86EFAC); glow = GREEN;
        } else if (stage == 2) {
            fill = YELLOW; stroke = new Color(0xFEF08A); glow = YELLOW;
        } else if (stage == 3) {
            if (flashOn) {
                fill = new Color(0xFF4242); stroke = new Color(0xFECACA); glow = RED;
            } else {
                fill = new Color(0x421014); stroke = new Color(239, 68, 68, 140);
            }
        }
        if (glow != null) {
            double gr = (stage == 3) ? r * 2.5 : r * 2.0;
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 68));
            g.fill(new Ellipse2D.Double(cx - gr, cy - gr, gr * 2, gr * 2));
            double gr2 = r * 1.45;
            g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 135));
            g.fill(new Ellipse2D.Double(cx - gr2, cy - gr2, gr2 * 2, gr2 * 2));
        }
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(stroke);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    private static void drawBlueWithRedlineArc(Graphics2D g, double cx, double cy, double R, double startDeg, double sweepDeg, double pct, double redPct, float width, int cap) {
        double blueEnd = Math.min(pct, redPct);
        if (blueEnd > 0.002) {
            drawArcStroke(g, cx, cy, R, startDeg, sweepDeg * blueEnd, width, cap, BLUE);
        }
        if (pct > redPct) {
            drawArcStroke(g, cx, cy, R, startDeg + sweepDeg * redPct, sweepDeg * (pct - redPct), width, cap, RED);
        }
    }

    private static void drawGauge(Graphics2D g, int variant, int x, int y, int w, int h, int rpm, boolean flash) {
        double cx = x + w / 2.0;
        double cy = y + h * 0.54;
        double R = Math.min(w, h) * 0.40;
        double pct = Math.min(1.0, Math.max(0.0, rpm / (double) MAX_RPM));
        double redPct = REDLINE_RPM / (double) MAX_RPM;
        double shiftPct = SHIFT_RPM / (double) MAX_RPM;
        int stage = shiftStage(rpm);
        boolean inRedline = rpm >= REDLINE_RPM;

        if (variant == 0) {
            // Option A: GT Motorsport Tach — integrated 12-o'clock dot (no label)
            double startDeg = 135, sweepDeg = 270;
            drawArcStroke(g, cx, cy, R, startDeg, sweepDeg, 13f, BasicStroke.CAP_ROUND, new Color(255,255,255,30));
            drawArcStroke(g, cx, cy, R, startDeg + sweepDeg * redPct, sweepDeg * (1 - redPct), 13f, BasicStroke.CAP_ROUND, new Color(239,68,68,130));
            drawBlueWithRedlineArc(g, cx, cy, R, startDeg, sweepDeg, pct, redPct, 13f, BasicStroke.CAP_ROUND);

            double smRad = Math.toRadians(startDeg + sweepDeg * shiftPct);
            g.setColor(YELLOW);
            g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(cx + (R - 9)*Math.cos(smRad), cy + (R - 9)*Math.sin(smRad), cx + (R + 9)*Math.cos(smRad), cy + (R + 9)*Math.sin(smRad)));

            for (int i = 0; i <= 32; i++) {
                double f = i / 32.0;
                double rad = Math.toRadians(startDeg + sweepDeg * f);
                boolean major = i % 4 == 0;
                boolean isRed = f * MAX_RPM >= REDLINE_RPM;
                g.setColor(isRed ? RED : (major ? new Color(255,255,255,220) : new Color(255,255,255,75)));
                g.setStroke(new BasicStroke(major ? 2.5f : 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(cx + (major ? R - 20 : R - 13)*Math.cos(rad), cy + (major ? R - 20 : R - 13)*Math.sin(rad), cx + (R - 7)*Math.cos(rad), cy + (R - 7)*Math.sin(rad)));
                if (major) {
                    drawCenteredText(g, String.valueOf(i / 4), cx + (R - 35)*Math.cos(rad), cy + (R - 35)*Math.sin(rad),
                        new Font("SansSerif", Font.BOLD, 17), isRed ? RED : new Color(0xDCE4F0));
                }
            }

            // 12 o'clock Shift Dot (no text label)
            drawShiftDot(g, cx, cy - R * 0.44, 11, stage, flash);

            double nRad = Math.toRadians(startDeg + sweepDeg * pct);
            Path2D needle = new Path2D.Double();
            double tipX = cx + (R - 16)*Math.cos(nRad), tipY = cy + (R - 16)*Math.sin(nRad);
            double px = -Math.sin(nRad), py = Math.cos(nRad);
            needle.moveTo(tipX, tipY);
            needle.lineTo(cx + px*5 - Math.cos(nRad)*18, cy + py*5 - Math.sin(nRad)*18);
            needle.lineTo(cx - px*5 - Math.cos(nRad)*18, cy - py*5 - Math.sin(nRad)*18);
            needle.closePath();
            g.setColor(inRedline ? RED : Color.WHITE);
            g.fill(needle);

            g.setColor(new Color(0x0B0F17));
            g.fill(new Ellipse2D.Double(cx - 14, cy - 14, 28, 28));
            g.setColor(inRedline ? RED : BLUE);
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Double(cx - 14, cy - 14, 28, 28));

            drawCenteredText(g, String.valueOf(rpm), cx, cy + R * 0.62, new Font("SansSerif", Font.BOLD, 34),
                inRedline ? RED : new Color(0xF3F5F8));
            drawCenteredText(g, "RPM", cx, cy + R * 0.80, new Font("SansSerif", Font.BOLD, 11), new Color(0x8B96A8));

        } else if (variant == 1) {
            // Option B: Sequential F1 Shift LEDs (Blue segmented sweep + Green->Yellow->Red crown LEDs, no label)
            double startDeg = 145, sweepDeg = 250;
            cy += 10;
            double[] ledA = {-44, -22, 0, 22, 44};
            Color[] ledC = {GREEN, GREEN, YELLOW, YELLOW, RED};
            int trigger = Math.min(SHIFT_RPM, REDLINE_RPM);
            double seqStart = trigger * 0.56;
            double seqProg = (rpm - seqStart) / Math.max(200.0, trigger - seqStart);
            boolean atShift = stage == 3;
            for (int i = 0; i < 5; i++) {
                double rad = Math.toRadians(-90 + ledA[i]);
                double lx = cx + (R + 22)*Math.cos(rad);
                double ly = cy + (R + 22)*Math.sin(rad) + 8;
                boolean lit = atShift ? flash : (seqProg >= (i + 0.25) / 5.0);
                Color c = atShift ? RED : ledC[i];
                if (lit) {
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
                    g.fill(new Ellipse2D.Double(lx - 14, ly - 14, 28, 28));
                    g.setColor(c);
                } else {
                    g.setColor(new Color(0x181F2A));
                }
                g.fill(new Ellipse2D.Double(lx - 8, ly - 8, 16, 16));
                g.setColor(lit ? new Color(255,255,255,140) : new Color(255,255,255,55));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new Ellipse2D.Double(lx - 8, ly - 8, 16, 16));
            }

            int segs = 32;
            for (int i = 0; i < segs; i++) {
                double f0 = i / (double) segs;
                double fMid = (i + 0.36) / (double) segs;
                double segSpan = (sweepDeg / segs) * 0.72;
                boolean active = f0 < pct;
                boolean isRed = fMid * MAX_RPM >= REDLINE_RPM;
                Color col = active
                    ? (isRed ? RED : BLUE)
                    : (isRed ? new Color(239,68,68,85) : new Color(255,255,255,26));
                drawArcStroke(g, cx, cy, R - 8, startDeg + sweepDeg * f0, segSpan, 17f, BasicStroke.CAP_BUTT, col);
            }
            for (int n = 0; n <= 8; n++) {
                double rad = Math.toRadians(startDeg + sweepDeg * (n / 8.0));
                drawCenteredText(g, String.valueOf(n), cx + (R - 33)*Math.cos(rad), cy + (R - 33)*Math.sin(rad),
                    new Font("SansSerif", Font.BOLD, 16), n * 1000 >= REDLINE_RPM ? RED : new Color(0xB8C4D4));
            }
            drawCenteredText(g, String.valueOf(rpm), cx, cy + 10, new Font("SansSerif", Font.BOLD, 44),
                inRedline ? RED : new Color(0xF3F5F8));
            drawCenteredText(g, "RPM", cx, cy + 36, new Font("SansSerif", Font.BOLD, 12), new Color(0x8B96A8));

        } else if (variant == 2) {
            // Option C: Track Chrono (Electric Blue twin-track + lower-center Shift Dot, no label)
            double startDeg = 135, sweepDeg = 270;
            cy = y + h * 0.50;
            R = Math.min(w, h) * 0.39;
            double innerR = R * 0.56;
            double midBandR = (R + innerR) * 0.5;
            float bandW = (float) (R - innerR - 4);
            Color activeBlue = inRedline ? RED : BLUE;

            drawArcStroke(g, cx, cy, R + 5, startDeg, sweepDeg, 2f, BasicStroke.CAP_BUTT, new Color(255,255,255,48));

            double blueEnd = Math.min(pct, redPct);
            if (blueEnd > 0.002) {
                drawArcStroke(g, cx, cy, midBandR, startDeg, sweepDeg * blueEnd, bandW, BasicStroke.CAP_BUTT, new Color(0, 168, 255, 56));
            }
            if (pct > redPct) {
                drawArcStroke(g, cx, cy, midBandR, startDeg + sweepDeg * redPct, sweepDeg * (pct - redPct), bandW, BasicStroke.CAP_BUTT, new Color(239, 68, 68, 72));
            }

            drawArcStroke(g, cx, cy, R - 2, startDeg + sweepDeg * redPct, sweepDeg * (1 - redPct), 8f, BasicStroke.CAP_BUTT, new Color(239,68,68,165));
            drawBlueWithRedlineArc(g, cx, cy, R - 2, startDeg, sweepDeg, pct, redPct, 6.5f, BasicStroke.CAP_BUTT);

            double smRad = Math.toRadians(startDeg + sweepDeg * shiftPct);
            g.setColor(YELLOW);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(cx + (R - 11)*Math.cos(smRad), cy + (R - 11)*Math.sin(smRad), cx + (R + 8)*Math.cos(smRad), cy + (R + 8)*Math.sin(smRad)));

            for (int i = 0; i <= 40; i++) {
                double f = i / 40.0;
                double rad = Math.toRadians(startDeg + sweepDeg * f);
                boolean major = i % 5 == 0;
                boolean isRed = f * MAX_RPM >= REDLINE_RPM;
                g.setColor(isRed ? RED : (major ? new Color(255,255,255,225) : new Color(255,255,255,70)));
                g.setStroke(new BasicStroke(major ? 2.4f : 1.1f));
                g.draw(new Line2D.Double(cx + (major ? R - 17 : R - 11)*Math.cos(rad), cy + (major ? R - 17 : R - 11)*Math.sin(rad), cx + (R - 4)*Math.cos(rad), cy + (R - 4)*Math.sin(rad)));
                if (major) {
                    drawCenteredText(g, String.valueOf(i / 5), cx + (R - 30)*Math.cos(rad), cy + (R - 30)*Math.sin(rad),
                        new Font("SansSerif", Font.BOLD, 16), isRed ? RED : new Color(0xE2E8F0));
                }
            }

            double nRad = Math.toRadians(startDeg + sweepDeg * pct);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(cx + (innerR + 3)*Math.cos(nRad), cy + (innerR + 3)*Math.sin(nRad), cx + (R - 3)*Math.cos(nRad), cy + (R - 3)*Math.sin(nRad)));

            g.setColor(new Color(0x05080E));
            g.fill(new Ellipse2D.Double(cx - innerR, cy - innerR, innerR * 2, innerR * 2));
            g.setColor(activeBlue);
            g.setStroke(new BasicStroke(2.5f));
            g.draw(new Ellipse2D.Double(cx - innerR, cy - innerR, innerR * 2, innerR * 2));

            drawCenteredText(g, "RPM", cx, cy - 22, new Font("SansSerif", Font.BOLD, 11), new Color(0x8B96A8));
            drawCenteredText(g, String.valueOf(rpm), cx, cy + 12, new Font("SansSerif", Font.BOLD, 38),
                inRedline ? RED : new Color(0xF3F5F8));

            // Lower-center Shift Dot (no text label)
            drawShiftDot(g, cx, cy + R * 0.84, 12, stage, flash);

        } else {
            // Option D: Compact OLED Halo — Blue sweep + integrated Shift Dot beside RPM (no label)
            double startDeg = 135, sweepDeg = 270;
            drawArcStroke(g, cx, cy, R + 5, startDeg, sweepDeg, 2.2f, BasicStroke.CAP_ROUND, new Color(255,255,255,36));
            drawArcStroke(g, cx, cy, R - 10, startDeg, sweepDeg, 16f, BasicStroke.CAP_ROUND, new Color(255,255,255,24));
            drawArcStroke(g, cx, cy, R - 10, startDeg + sweepDeg * redPct, sweepDeg * (1 - redPct), 16f, BasicStroke.CAP_ROUND, new Color(239,68,68,120));
            drawBlueWithRedlineArc(g, cx, cy, R - 10, startDeg, sweepDeg, pct, redPct, 16f, BasicStroke.CAP_ROUND);

            for (int n = 0; n <= 8; n++) {
                double rad = Math.toRadians(startDeg + sweepDeg * (n / 8.0));
                drawCenteredText(g, String.valueOf(n), cx + (R - 35)*Math.cos(rad), cy + (R - 35)*Math.sin(rad),
                    new Font("SansSerif", Font.BOLD, 15), n * 1000 >= REDLINE_RPM ? RED : new Color(0x8B96A8));
            }
            double dotX = cx + 28, dotY = cy - 36;
            drawCenteredText(g, "RPM", cx - 12, dotY, new Font("SansSerif", Font.BOLD, 14), new Color(0xC5CFDF));
            drawShiftDot(g, dotX, dotY, 9, stage, flash);

            drawCenteredText(g, String.valueOf(rpm), cx, cy + 14, new Font("SansSerif", Font.BOLD, 48),
                inRedline ? RED : new Color(0xF3F5F8));
        }
    }

    private static void drawArcStroke(Graphics2D g, double cx, double cy, double r, double startDegCw, double sweepDegCw, float width, int cap, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(width, cap, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - r, cy - r, r * 2, r * 2, -startDegCw, -sweepDegCw, Arc2D.OPEN));
    }

    private static void drawCenteredText(Graphics2D g, String s, double cx, double cy, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(s);
        int th = fm.getAscent() - fm.getDescent();
        g.drawString(s, (float) (cx - tw / 2.0), (float) (cy + th / 2.0));
    }
}
