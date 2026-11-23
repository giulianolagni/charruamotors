import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Ícone do Charrua OBD Pro 1.4, desenhado em Java2D (unidades de 108 = canvas do adaptive icon):
 * fundo de fibra de carbono com silhueta off-road e brilho azul no horizonte; à frente, a espora
 * gaúcha cromada (banda em U, pescoço = plug OBD2 com os 16 pinos e a roseta estrelada) sob um
 * arco de LEDs azuis, como um conta-giros.
 *
 * Uso: java IconeCharrua.java <pasta res> <pasta de prévias>
 */
public class IconeCharrua {

    static final Color AZUL = new Color(0x29, 0x79, 0xFF);
    static final Color CIANO = new Color(0x00, 0xE5, 0xFF);

    public static void main(String[] a) throws Exception {
        File res = new File(a[0]);
        File previas = new File(a[1]);
        previas.mkdirs();
        String[] dpis = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        double[] escalas = {1, 1.5, 2, 3, 4};
        for (int i = 0; i < dpis.length; i++) {
            File pasta = new File(res, "mipmap-" + dpis[i]);
            pasta.mkdirs();
            int camada = (int) Math.round(108 * escalas[i]);
            int legado = (int) Math.round(48 * escalas[i]);
            ImageIO.write(fundo(camada), "png", new File(pasta, "ic_launcher_background.png"));
            ImageIO.write(frente(camada, false), "png", new File(pasta, "ic_launcher_foreground.png"));
            ImageIO.write(frente(camada, true), "png", new File(pasta, "ic_launcher_monochrome.png"));
            ImageIO.write(legado(legado, false), "png", new File(pasta, "ic_launcher.png"));
            ImageIO.write(legado(legado, true), "png", new File(pasta, "ic_launcher_round.png"));
        }
        ImageIO.write(legado(512, false), "png", new File(previas, "previa_quadrado.png"));
        ImageIO.write(legado(512, true), "png", new File(previas, "previa_redondo.png"));
        ImageIO.write(composto(432), "png", new File(previas, "previa_camadas.png"));
    }

    static Graphics2D preparar(BufferedImage img, double escala) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.scale(escala, escala);
        return g;
    }

    /** Ícone legado (48 dp): as duas camadas, recorte central de 72 unidades, em quadrado arredondado ou círculo. */
    static BufferedImage legado(int lado, boolean redondo) {
        BufferedImage camadas = composto(lado * 108 / 72);
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double m = lado * 0.02;
        Shape forma = redondo
            ? new Ellipse2D.Double(m, m, lado - 2 * m, lado - 2 * m)
            : new RoundRectangle2D.Double(m, m, lado - 2 * m, lado - 2 * m, lado * 0.36, lado * 0.36);
        g.setClip(forma);
        int deslocamento = (camadas.getWidth() - lado) / 2;
        g.drawImage(camadas, -deslocamento, -deslocamento, null);
        g.setClip(null);
        g.setStroke(new BasicStroke((float) (lado * 0.012)));
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(forma);
        g.dispose();
        return img;
    }

    static BufferedImage composto(int lado) {
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.drawImage(fundo(lado), 0, 0, null);
        g.drawImage(frente(lado, false), 0, 0, null);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ fundo: carbono + off-road

    static BufferedImage fundo(int lado) {
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preparar(img, lado / 108.0);
        g.setColor(new Color(0x0B, 0x0E, 0x13));
        g.fill(new Rectangle2D.Double(0, 0, 108, 108));

        // Trama de fibra de carbono (sarja 2x2) girada 45°.
        AffineTransform original = g.getTransform();
        g.rotate(Math.toRadians(45), 54, 54);
        double c = 3.0;
        for (int i = -30; i < 60; i++) {
            for (int j = -30; j < 60; j++) {
                double x = i * c, y = j * c;
                boolean horizontal = ((i + j) / 2 + 100) % 2 == 0;
                Paint p = horizontal
                    ? new GradientPaint((float) x, (float) y, new Color(0x2A, 0x30, 0x38), (float) x, (float) (y + c), new Color(0x12, 0x15, 0x1A))
                    : new GradientPaint((float) x, (float) y, new Color(0x1E, 0x23, 0x2A), (float) (x + c), (float) y, new Color(0x0D, 0x10, 0x14));
                g.setPaint(p);
                g.fill(new Rectangle2D.Double(x + 0.15, y + 0.15, c - 0.3, c - 0.3));
            }
        }
        g.setTransform(original);

        // Vinheta e brilho azul do horizonte.
        g.setPaint(new RadialGradientPaint(new Point2D.Double(54, 46), 70f, new float[]{0f, 0.6f, 1f},
            new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 70), new Color(0, 0, 0, 200)}));
        g.fill(new Rectangle2D.Double(0, 0, 108, 108));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(54, 84), 44f, new float[]{0f, 1f},
            new Color[]{new Color(0x29, 0x79, 0xFF, 120), new Color(0x29, 0x79, 0xFF, 0)}));
        g.fill(new Rectangle2D.Double(0, 40, 108, 68));

        // Silhueta off-road: serras, trilha e uma caminhonete 4x4 subindo o morro.
        Path2D serra = new Path2D.Double();
        serra.moveTo(0, 80);
        serra.curveTo(10, 74, 16, 70, 24, 73);
        serra.curveTo(30, 75, 34, 66, 42, 68);
        serra.curveTo(48, 70, 52, 76, 60, 75);
        serra.curveTo(68, 74, 72, 64, 82, 66);
        serra.curveTo(92, 68, 98, 74, 108, 72);
        serra.lineTo(108, 108);
        serra.lineTo(0, 108);
        serra.closePath();
        g.setColor(new Color(0x0A, 0x14, 0x24));
        g.fill(serra);
        g.setStroke(new BasicStroke(0.7f));
        g.setColor(new Color(0x29, 0x79, 0xFF, 170));
        Path2D cume = new Path2D.Double();
        cume.moveTo(0, 80);
        cume.curveTo(10, 74, 16, 70, 24, 73);
        cume.curveTo(30, 75, 34, 66, 42, 68);
        cume.curveTo(48, 70, 52, 76, 60, 75);
        cume.curveTo(68, 74, 72, 64, 82, 66);
        cume.curveTo(92, 68, 98, 74, 108, 72);
        g.draw(cume);

        Path2D morro = new Path2D.Double();
        morro.moveTo(0, 92);
        morro.curveTo(20, 84, 40, 82, 58, 86);
        morro.curveTo(76, 90, 92, 84, 108, 82);
        morro.lineTo(108, 108);
        morro.lineTo(0, 108);
        morro.closePath();
        g.setColor(new Color(0x05, 0x09, 0x10));
        g.fill(morro);
        g.setColor(new Color(0x00, 0xE5, 0xFF, 120));
        g.setStroke(new BasicStroke(0.5f));
        Path2D crista = new Path2D.Double();
        crista.moveTo(0, 92);
        crista.curveTo(20, 84, 40, 82, 58, 86);
        crista.curveTo(76, 90, 92, 84, 108, 82);
        g.draw(crista);
        caminhonete(g, 54, 85.9, 3);
        g.dispose();
        return img;
    }

    /** Caminhonete 4x4 em silhueta (cabine dupla, pneus grandes), apoiada em (x, y) e inclinada. */
    static void caminhonete(Graphics2D g, double x, double y, double graus) {
        AffineTransform t = g.getTransform();
        g.translate(x, y);
        g.rotate(Math.toRadians(graus));
        g.scale(0.82, 0.82);
        Path2D corpo = new Path2D.Double();
        corpo.moveTo(-10, -3.6);
        corpo.lineTo(-10, -6.6);
        corpo.lineTo(-1.5, -6.6);
        corpo.lineTo(0, -10);
        corpo.lineTo(6, -10);
        corpo.lineTo(8, -6.6);
        corpo.lineTo(10.6, -6.2);
        corpo.lineTo(10.6, -3.6);
        corpo.closePath();
        g.setColor(new Color(0x03, 0x06, 0x0B));
        g.fill(corpo);
        g.setColor(new Color(0x00, 0xE5, 0xFF, 150));
        g.setStroke(new BasicStroke(0.35f));
        g.draw(corpo);
        // Rack de teto e snorkel.
        g.draw(new Line2D.Double(-0.4, -11, 6.4, -11));
        g.draw(new Line2D.Double(7.4, -6.8, 7.4, -10.6));
        for (double rx : new double[]{-6, 6.6}) {
            Ellipse2D roda = new Ellipse2D.Double(rx - 2.9, -5.6, 5.8, 5.8);
            g.setColor(new Color(0x03, 0x06, 0x0B));
            g.fill(roda);
            g.setColor(new Color(0x29, 0x79, 0xFF, 160));
            g.draw(roda);
        }
        // Farol aceso.
        g.setColor(new Color(0xB3, 0xF5, 0xFF));
        g.fill(new Ellipse2D.Double(9.7, -6.1, 1.2, 0.9));
        g.setTransform(t);
    }

    // ------------------------------------------------------------------ frente: LEDs + espora + plug

    static BufferedImage frente(int lado, boolean mono) {
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preparar(img, lado / 108.0);
        // Tudo dentro da zona segura do adaptive icon (círculo de 66 dp no centro).
        g.translate(54, 54);
        g.scale(0.84, 0.84);
        g.translate(-54, -54);
        leds(g, mono);
        espora(g, mono);
        g.dispose();
        return img;
    }

    /** Arco de 11 LEDs azuis (painel de LEDs / conta-giros), com brilho. */
    static void leds(Graphics2D g, boolean mono) {
        double cx = 54, cy = 60, r = 30;
        int n = 11;
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(214 + (326 - 214) * i / (double) (n - 1));
            double x = cx + Math.cos(ang) * r, y = cy + Math.sin(ang) * r;
            boolean aceso = i < 8;
            float f = i / (float) (n - 1);
            Color cor = misturar(AZUL, CIANO, f);
            if (i >= 9) cor = new Color(0xFF, 0x52, 0x52); // zona vermelha
            if (mono) {
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(x - 1.6, y - 1.6, 3.2, 3.2));
                continue;
            }
            if (aceso) {
                g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), 4.2f, new float[]{0f, 1f},
                    new Color[]{new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), 150), new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), 0)}));
                g.fill(new Ellipse2D.Double(x - 4.2, y - 4.2, 8.4, 8.4));
            }
            g.setColor(aceso ? cor : new Color(cor.getRed() / 4, cor.getGreen() / 4, cor.getBlue() / 4));
            g.fill(new Ellipse2D.Double(x - 1.6, y - 1.6, 3.2, 3.2));
            if (aceso) {
                g.setColor(new Color(255, 255, 255, 200));
                g.fill(new Ellipse2D.Double(x - 0.7, y - 0.9, 1.0, 0.8));
            }
        }
    }

    static Paint cromo(double y0, double y1) {
        return new LinearGradientPaint(new Point2D.Double(0, y0), new Point2D.Double(0, y1),
            new float[]{0f, 0.3f, 0.48f, 0.55f, 0.75f, 1f},
            new Color[]{new Color(0xF4, 0xF7, 0xFA), new Color(0x9C, 0xA6, 0xB1), new Color(0xFF, 0xFF, 0xFF),
                new Color(0x4E, 0x58, 0x63), new Color(0x8E, 0x99, 0xA4), new Color(0xE6, 0xEB, 0xF0)});
    }

    static void espora(Graphics2D g, boolean mono) {
        Color contorno = new Color(0x10, 0x14, 0x1A);
        double cy = 63;

        // Banda em U (abraça o calcanhar), aberta para a esquerda, com as pontas de fixação.
        Path2D banda = new Path2D.Double();
        banda.moveTo(23, cy - 15);
        banda.lineTo(33, cy - 15);
        banda.curveTo(44, cy - 15, 48, cy - 8, 48, cy);
        banda.curveTo(48, cy + 8, 44, cy + 15, 33, cy + 15);
        banda.lineTo(23, cy + 15);
        Stroke grosso = new BasicStroke(6.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Shape bandaCheia = new Area(grosso.createStrokedShape(banda));
        preencher(g, bandaCheia, mono, cy - 18, cy + 18, contorno);
        // Botões de fixação (onde prende a correia).
        for (double by : new double[]{cy - 15, cy + 15}) {
            Ellipse2D botao = new Ellipse2D.Double(21.2, by - 4, 8, 8);
            preencher(g, botao, mono, by - 4, by + 4, contorno);
            if (!mono) {
                g.setColor(new Color(0x29, 0x79, 0xFF));
                g.fill(new Ellipse2D.Double(23.6, by - 1.6, 3.2, 3.2));
            }
        }

        // Pescoço = carcaça do plug OBD2 (trapézio com os 16 pinos em 2 fileiras).
        Path2D plug = new Path2D.Double();
        plug.moveTo(45, cy - 8.5);
        plug.lineTo(66, cy - 8.5);
        plug.lineTo(64, cy + 8.5);
        plug.lineTo(47, cy + 8.5);
        plug.closePath();
        Shape plugArredondado = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(plug);
        Area carcaca = new Area(plug);
        carcaca.add(new Area(plugArredondado));
        preencher(g, carcaca, mono, cy - 10, cy + 10, contorno);
        // Face interna escura com os pinos.
        Path2D face = new Path2D.Double();
        face.moveTo(47.2, cy - 6.4);
        face.lineTo(63.8, cy - 6.4);
        face.lineTo(62.2, cy + 6.4);
        face.lineTo(48.8, cy + 6.4);
        face.closePath();
        if (mono) {
            g.setComposite(AlphaComposite.Clear);
            g.fill(face);
            g.setComposite(AlphaComposite.SrcOver);
        } else {
            g.setPaint(new LinearGradientPaint(new Point2D.Double(0, cy - 6.4), new Point2D.Double(0, cy + 6.4),
                new float[]{0f, 1f}, new Color[]{new Color(0x05, 0x08, 0x0D), new Color(0x1A, 0x22, 0x2E)}));
            g.fill(face);
        }
        for (int fila = 0; fila < 2; fila++) {
            double py = cy - 2.6 + fila * 5.2;
            double x0 = 49.3 + fila * 0.6, x1 = 61.7 - fila * 0.6;
            for (int k = 0; k < 8; k++) {
                double px = x0 + (x1 - x0) * k / 7.0;
                if (mono) {
                    g.setColor(Color.WHITE);
                } else {
                    g.setPaint(new RadialGradientPaint(new Point2D.Double(px, py), 1.5f, new float[]{0f, 1f},
                        new Color[]{new Color(0xB3, 0xF5, 0xFF), new Color(0x29, 0x79, 0xFF)}));
                }
                g.fill(new Ellipse2D.Double(px - 0.75, py - 0.75, 1.5, 1.5));
            }
        }

        // Haste da roseta.
        Shape haste = new RoundRectangle2D.Double(64.5, cy - 2.2, 9, 4.4, 2, 2);
        preencher(g, haste, mono, cy - 2.2, cy + 2.2, contorno);

        // Roseta estrelada (12 pontas) girando na ponta.
        double rx = 76.5, ry = cy, externo = 12, interno = 6;
        Path2D estrela = new Path2D.Double();
        int pontas = 14;
        for (int i = 0; i < pontas * 2; i++) {
            double ang = Math.PI * i / pontas - Math.PI / 2;
            double r = i % 2 == 0 ? externo : interno;
            double x = rx + Math.cos(ang) * r, y = ry + Math.sin(ang) * r;
            if (i == 0) estrela.moveTo(x, y); else estrela.lineTo(x, y);
        }
        estrela.closePath();
        preencher(g, estrela, mono, ry - externo, ry + externo, contorno);
        Ellipse2D cubo = new Ellipse2D.Double(rx - 4, ry - 4, 8, 8);
        preencher(g, cubo, mono, ry - 4, ry + 4, contorno);
        if (!mono) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(rx, ry), 2.2f, new float[]{0f, 1f},
                new Color[]{new Color(0xB3, 0xF5, 0xFF), new Color(0x29, 0x79, 0xFF)}));
            g.fill(new Ellipse2D.Double(rx - 1.8, ry - 1.8, 3.6, 3.6));
        } else {
            g.setComposite(AlphaComposite.Clear);
            g.fill(new Ellipse2D.Double(rx - 1.8, ry - 1.8, 3.6, 3.6));
            g.setComposite(AlphaComposite.SrcOver);
        }
        if (!mono) {
            // Reflexo especular no alto da banda.
            g.setColor(new Color(255, 255, 255, 170));
            g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D reflexo = new Path2D.Double();
            reflexo.moveTo(30, cy - 16.6);
            reflexo.curveTo(40, cy - 16.6, 45, cy - 12, 46.4, cy - 5);
            g.draw(reflexo);
        }
    }

    /** Cromo com sombra e contorno (ou branco liso no ícone monocromático). */
    static void preencher(Graphics2D g, Shape forma, boolean mono, double y0, double y1, Color contorno) {
        if (mono) {
            g.setColor(Color.WHITE);
            g.fill(forma);
            return;
        }
        AffineTransform t = g.getTransform();
        g.translate(0.9, 1.4);
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(forma);
        g.setTransform(t);
        g.setPaint(cromo(y0, y1));
        g.fill(forma);
        g.setColor(contorno);
        g.setStroke(new BasicStroke(0.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(forma);
    }

    static Color misturar(Color a, Color b, float f) {
        return new Color(
            Math.round(a.getRed() + (b.getRed() - a.getRed()) * f),
            Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
            Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f));
    }
}
