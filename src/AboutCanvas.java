/*
 * DashTube v1.0 – DASH ANIMATION V2
 * MIDP 2.0 / CLDC 1.1 + JSR-75
 */


import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.io.file.*;
import javax.microedition.media.*;
import javax.microedition.media.control.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.*;


class AboutCanvas extends Canvas implements CommandListener {
    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          parent;
    private Command backCmd;
    private Font fontBold, fontNormal, fontSmall;
    private Image icon;

    private static final int C_BG     = 0x0F0F0F;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;
    private static final int C_CARD   = 0x1A1A1A;

    public AboutCanvas(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas parent) {
        this.midlet=midlet; this.parent=parent;
        fontBold  =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        fontNormal=Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        fontSmall =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        backCmd=new Command("Back",Command.BACK,1);
        addCommand(backCmd); setCommandListener(this);
        try { icon=Image.createImage("/Dashtube.png"); } catch(Exception e) { icon=null; }
    }

    protected void paint(Graphics g) {
        int w=getWidth(), h=getHeight();
        g.setColor(C_BG); g.fillRect(0,0,w,h);

        int y=14;

        // ── Icon: Centered properly using HCENTER anchor ──
        if (icon!=null) {
            // Using Graphics.HCENTER automatically centers the image horizontally around w/2
            g.drawImage(icon, w / 2, y, Graphics.TOP | Graphics.HCENTER);
            y += icon.getHeight() + 10;
        } else {
            y += 10; // padding if image failed to load
        }

        // App name
        g.setFont(fontBold); g.setColor(C_TEXT);
        g.drawString("DashTube",w/2,y,Graphics.TOP|Graphics.HCENTER);
        y+=fontBold.getHeight()+4;

        // Crimson divider
        g.setColor(C_ACCENT); g.fillRect(w/4,y,w/2,2); y+=10;

        // Info rows
        String[][] rows={
            {"Version",  "1.2.1"},
            {"By",       "DASH ANIMATION V2"},
            {"API",      "video.2yxa.mobi"},
            {"Runtime",  "MIDP 2.0 / CLDC 1.1"},
        };
        int cPad=8, cH=fontNormal.getHeight()+cPad*2, cW=w-24, cX=12;
        for (int i=0;i<rows.length;i++) {
            g.setColor(C_CARD); g.fillRoundRect(cX,y,cW,cH,6,6);
            g.setFont(fontSmall); g.setColor(C_SUB);
            g.drawString(rows[i][0],cX+8,y+cPad,Graphics.TOP|Graphics.LEFT);
            g.setFont(fontNormal); g.setColor(C_TEXT);
            g.drawString(rows[i][1],cX+cW-8,y+cPad,Graphics.TOP|Graphics.RIGHT);
            y+=cH+4;
        }

        y+=8;
        g.setFont(fontSmall); g.setColor(C_SUB);
        g.drawString("Press Back to return",w/2,y,Graphics.TOP|Graphics.HCENTER);
    }

    protected void keyPressed(int k) { midlet.getDisplay().setCurrent(parent); }
    public void commandAction(Command c,Displayable d) { midlet.getDisplay().setCurrent(parent); }
}

// ======================== Main Browser Canvas ========================