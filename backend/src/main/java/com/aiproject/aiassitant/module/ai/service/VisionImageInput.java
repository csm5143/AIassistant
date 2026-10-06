package com.aiproject.aiassitant.module.ai.service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;

/** Keep text legible and label the encoded bytes, rather than the original extension. */
public final class VisionImageInput {
    private VisionImageInput() {}
    public static String dataUrl(byte[] bytes, String ext) throws IOException {
        String mime = switch(ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
        if (!ext.equalsIgnoreCase("gif")) {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src != null && (Math.max(src.getWidth(), src.getHeight()) > 2048 || mime.equals("image/bmp"))) {
                double scale = Math.min(1, 2048d / Math.max(src.getWidth(), src.getHeight()));
                BufferedImage out = new BufferedImage(Math.max(1,(int)(src.getWidth()*scale)),
                    Math.max(1,(int)(src.getHeight()*scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D g=out.createGraphics();
                g.setColor(Color.WHITE); g.fillRect(0,0,out.getWidth(),out.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(src,0,0,out.getWidth(),out.getHeight(),null); g.dispose();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean png=mime.equals("image/png");
                ImageIO.write(out,png?"png":"jpeg",buffer);
                bytes=buffer.toByteArray(); mime=png?"image/png":"image/jpeg";
            }
        }
        return "data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes);
    }
}
