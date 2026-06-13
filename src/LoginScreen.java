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


class LoginScreen extends Canvas implements CommandListener {
    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;

    private String  usernameVal = "";
    private String  passwordVal = "";
    private String  statusMsg   = "Login to avoid captcha";
    private int     focusField  = 0;   // 0=username 1=password
    private boolean loggingIn   = false;
    private boolean autoLoginAttempted = false;
    private String  loginAction = "http://video.2yxa.mobi/in.php?y=351231";
    private String  sessionToken;

    private Command loginCmd, skipCmd, clearCmd, exitCmd, editCmd;
    private Font fontBold, fontNormal, fontSmall;

    // Dark palette
    private static final int C_BG     = 0x0F0F0F;
    private static final int C_CARD   = 0x1A1A1A;
    private static final int C_ACCENT = 0xD42B52;
    private static final int C_TEXT   = 0xE8E8E8;
    private static final int C_SUB    = 0x888888;
    private static final int C_SELTXT = 0xFFFFFF;
    private static final int C_FIELD  = 0x222222;
    private static final int C_FIELDF = 0x2A2A3E;  // focused field bg

    public LoginScreen(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser) {
        this.midlet=midlet; this.browser=browser;
        fontBold  =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        fontNormal=Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        fontSmall =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);

        loginCmd=new Command("Login",      Command.OK,    1);
        editCmd =new Command("Edit Field", Command.SCREEN,2);
        skipCmd =new Command("Skip",       Command.SCREEN,3);
        clearCmd=new Command("Clear Saved",Command.SCREEN,4);
        exitCmd =new Command("Exit",       Command.EXIT,  5);
        addCommand(loginCmd); addCommand(editCmd);
        addCommand(skipCmd);  addCommand(clearCmd); addCommand(exitCmd);
        setCommandListener(this);

        // Load saved credentials
        String[] saved=LoginStore.loadCredentials();
        if (saved[0]!=null&&saved[0].length()>0) usernameVal=saved[0];
        if (saved[1]!=null&&saved[1].length()>0) passwordVal=saved[1];
        if (saved[2]!=null&&saved[2].length()>0) { sessionToken=saved[2]; browser.sessionToken=saved[2]; }

        if (LoginStore.hasSavedCredentials())
            statusMsg="Saved: "+usernameVal+" - Connecting...";

        fetchAuthPage();
    }

    private void fetchAuthPage() {
        new Thread(new HttpRequestRunnable("http://video.2yxa.mobi/auth.php",null,false,
            new HttpCallback() {
                public void onSuccess(String content,String base) {
                    String lo=content.toLowerCase();
                    int ti=lo.indexOf("ilove2yxa=");
                    if (ti>=0) {
                        int ts=ti+10, te=ts;
                        while(te<content.length()&&content.charAt(te)!='"'&&content.charAt(te)!=(char)39&&content.charAt(te)!='&'&&content.charAt(te)!=' '&&content.charAt(te)!='>')te++;
                        if (te>ts) { sessionToken=content.substring(ts,te); browser.sessionToken=sessionToken; }
                    }
                    int fi=lo.indexOf("<form");
                    while(fi>=0) {
                        int fe=lo.indexOf(">",fi); if(fe<0)break;
                        int fc=lo.indexOf("</form>",fe); if(fc<0)fc=lo.length();
                        String body=lo.substring(fe+1,fc);
                        if(body.indexOf("name=\"us\"")>=0||body.indexOf("name='us'")>=0) {
                            String act=SiteParser.extractAttr(content.substring(fi,fe+1),"action");
                            if(act!=null)loginAction=SiteParser.makeAbsolute(HtmlDecoder.decode(act));
                            break;
                        }
                        fi=lo.indexOf("<form",fe+1);
                    }
                    if (!autoLoginAttempted&&LoginStore.hasSavedCredentials()) {
                        autoLoginAttempted=true;
                        String[] c=LoginStore.loadCredentials();
                        setStatus("Auto-login as "+c[0]+"...");
                        doLogin(c[0],c[1]);
                    } else {
                        setStatus(sessionToken!=null?"Ready. Session active.":"Ready. Enter credentials.");
                    }
                }
                public void onError(String msg) {
                    setStatus("Warning: "+msg+(LoginStore.hasSavedCredentials()?" - Saved login, press Skip":""));
                }
            }
        )).start();
    }

    private void setStatus(final String msg) {
        midlet.getDisplay().callSerially(new Runnable(){public void run(){statusMsg=msg;repaint();}});
    }

    protected void paint(Graphics g) {
        int w=getWidth(), h=getHeight();
        g.setColor(C_BG); g.fillRect(0,0,w,h);

        // ── Title bar ──
        g.setColor(C_ACCENT); g.fillRect(0,0,w,fontBold.getHeight()+10);
        g.setColor(C_SELTXT); g.setFont(fontBold);
        g.drawString("DashTube Login",w/2,5,Graphics.TOP|Graphics.HCENTER);
        int y=fontBold.getHeight()+18;

        int fieldW=w-24, fieldX=12, labelH=fontSmall.getHeight(), inputH=fontNormal.getHeight()+10;

        // ── Username ──
        g.setFont(fontSmall); g.setColor(C_SUB);
        g.drawString("USERNAME",fieldX,y,Graphics.TOP|Graphics.LEFT);
        y+=labelH+2;
        boolean uFoc=(focusField==0);
        g.setColor(uFoc?C_FIELDF:C_FIELD); g.fillRoundRect(fieldX,y,fieldW,inputH,6,6);
        if (uFoc) { g.setColor(C_ACCENT); g.drawRoundRect(fieldX,y,fieldW,inputH,6,6); }
        g.setFont(fontNormal); g.setColor(C_TEXT);
        g.drawString(usernameVal.length()>0?usernameVal:" ",fieldX+8,y+5,Graphics.TOP|Graphics.LEFT);
        y+=inputH+10;

        // ── Password ──
        g.setFont(fontSmall); g.setColor(C_SUB);
        g.drawString("PASSWORD",fieldX,y,Graphics.TOP|Graphics.LEFT);
        y+=labelH+2;
        boolean pFoc=(focusField==1);
        g.setColor(pFoc?C_FIELDF:C_FIELD); g.fillRoundRect(fieldX,y,fieldW,inputH,6,6);
        if (pFoc) { g.setColor(C_ACCENT); g.drawRoundRect(fieldX,y,fieldW,inputH,6,6); }
        g.setFont(fontNormal); g.setColor(C_TEXT);
        StringBuffer stars=new StringBuffer();
        for(int i=0;i<passwordVal.length();i++) stars.append('*');
        g.drawString(stars.length()>0?stars.toString():" ",fieldX+8,y+5,Graphics.TOP|Graphics.LEFT);
        y+=inputH+14;

        // ── Divider ──
        g.setColor(0x222222); g.drawLine(fieldX,y,fieldX+fieldW,y); y+=10;

        // ── Status (wrapped) ──
        g.setFont(fontSmall); g.setColor(C_SUB);
        String sm=statusMsg!=null?statusMsg:""; int ls=0;
        for(int i=1;i<=sm.length();i++){
            boolean last=(i==sm.length());
            if(fontSmall.substringWidth(sm,ls,i-ls)>fieldW||last){
                int len=last?i-ls:i-1-ls;
                if(len>0){g.drawSubstring(sm,ls,len,fieldX,y,Graphics.TOP|Graphics.LEFT);y+=fontSmall.getHeight()+2;}
                ls=last?i:i-1;
            }
        }

        // ── Bottom hint ──
        g.setFont(fontSmall); g.setColor(0x444444);
        g.drawString("FIRE/Menu: Edit | Login | Skip",w/2,h-fontSmall.getHeight()-5,Graphics.TOP|Graphics.HCENTER);
    }

    protected void keyPressed(int keyCode) {
        int action = getGameActionSafe(keyCode);
        if (action==Canvas.UP||action==Canvas.LEFT||action==Canvas.DOWN||action==Canvas.RIGHT) {
            focusField=(focusField+1)%2; repaint();
        } else if (action==Canvas.FIRE) { openEditor(); }
        else if (keyCode==KEY_NUM5) { openEditor(); }
    }

    private int getGameActionSafe(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch(Exception e) {}
        if (action != 0) return action;
        switch (keyCode) {
            case -8: return Canvas.FIRE;
            case -1: return Canvas.UP;
            case -6: return Canvas.DOWN;
            case -2: return Canvas.LEFT;
            case -5: return Canvas.RIGHT;
        }
        return 0;
    }

    // Touch support: tap username/password field to open editor (Nokia 5800)
    protected void pointerReleased(int px, int py) {
        int headerH = fontBold.getHeight()+10;
        int labelH = fontSmall.getHeight();
        int inputH = fontNormal.getHeight()+10;
        int uTop = headerH+18+labelH+2;
        int uBot = uTop+inputH;
        int pTop = uBot+10+labelH+2;
        int pBot = pTop+inputH;
        if      (py >= uTop && py <= uBot) { focusField=0; repaint(); openEditor(); }
        else if (py >= pTop && py <= pBot) { focusField=1; repaint(); openEditor(); }
    }

    private void openEditor() {
        boolean isPass=(focusField==1);
        final TextBox tb=new TextBox(isPass?"Password":"Username",isPass?passwordVal:usernameVal,
            80, isPass?TextField.PASSWORD:TextField.ANY);
        // OK → left (confirm), Cancel → right (dismiss)
        final Command ok=new Command("OK",    Command.OK,    1);
        final Command bk=new Command("Cancel",Command.CANCEL,1);
        tb.addCommand(ok); tb.addCommand(bk);
        tb.setCommandListener(new CommandListener(){ public void commandAction(Command c,Displayable d){
            if (c==ok) { String val=tb.getString(); if(focusField==0)usernameVal=val; else passwordVal=val; }
            midlet.getDisplay().setCurrent(LoginScreen.this); repaint();
        }});
        midlet.getDisplay().setCurrent(tb);
    }

    public void commandAction(Command c,Displayable d) {
        if (c==exitCmd) { midlet.notifyDestroyed(); }
        else if (c==skipCmd) { midlet.getDisplay().setCurrent(browser); browser.startLoadingInitialPage(); }
        else if (c==clearCmd) { LoginStore.clearCredentials(); usernameVal=""; passwordVal=""; setStatus("Credentials cleared."); }
        else if (c==editCmd)  { openEditor(); }
        else if (c==loginCmd) {
            String user=usernameVal.trim(), pass=passwordVal.trim();
            if (user.length()==0) { setStatus("Enter username"); return; }
            if (pass.length()==0) { setStatus("Enter password"); return; }
            if (loggingIn) return;
            loggingIn=true; doLogin(user,pass);
        }
    }

    private void doLogin(final String user, final String pass) {
        setStatus("Logging in as "+user+"...");
        // Save credentials immediately - they persist even on timeout/error
        LoginStore.saveCredentials(user, pass, sessionToken);

        String post="us="+UrlEncoder.encode(user)+"&ps="+UrlEncoder.encode(pass);
        String action=UrlUtils.stripProxy(loginAction);
        if (sessionToken!=null&&sessionToken.length()>0&&action.indexOf("ILOVE2YXA")<0)
            action+=(action.indexOf('?')>=0?"&":"?")+"ILOVE2YXA="+UrlEncoder.encode(sessionToken);

        new Thread(new HttpRequestRunnable(action,post,true, new HttpCallback() {
            public void onSuccess(String content,String base) {
                loggingIn=false;
                String lo=content.toLowerCase();
                // Extract updated session token
                int ti=lo.indexOf("ilove2yxa=");
                if(ti>=0){int ts=ti+10,te=ts;
                    while(te<content.length()&&content.charAt(te)!='"'&&content.charAt(te)!=(char)39&&content.charAt(te)!='&'&&content.charAt(te)!=' '&&content.charAt(te)!='>')te++;
                    if(te>ts){sessionToken=content.substring(ts,te);browser.sessionToken=sessionToken;}}
                // Re-save with updated token
                LoginStore.saveCredentials(user,pass,sessionToken);
                boolean failed=lo.indexOf("wrong")>=0||lo.indexOf("invalid")>=0||lo.indexOf("incorrect")>=0;
                boolean ok=!failed&&(lo.indexOf("authorization successful")>=0
                    ||lo.indexOf("successful")>=0||lo.indexOf("make.php")>=0
                    ||lo.indexOf("my files")>=0||lo.indexOf("logout")>=0
                    ||lo.indexOf("welcome")>=0||lo.indexOf("video.2yxa.mobi")>=0);
                if (failed) {
                    setStatus("Wrong credentials - saved anyway.");
                } else if (ok) {
                    setStatus("Login OK! Saved.");
                    midlet.getDisplay().callSerially(new Runnable(){public void run(){
                        midlet.getDisplay().setCurrent(browser); browser.startLoadingInitialPage();
                    }});
                } else {
                    // Ambiguous - credentials saved, proceed to browser
                    setStatus("Saved. Continuing...");
                    midlet.getDisplay().callSerially(new Runnable(){public void run(){
                        midlet.getDisplay().setCurrent(browser); browser.startLoadingInitialPage();
                    }});
                }
            }
            public void onError(String msg) {
                loggingIn=false;
                setStatus("Error: "+msg+" (credentials saved)");
            }
        })).start();
    }
}

// ======================== Download Choice Canvas ========================