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


class CaptchaScreen extends Canvas implements CommandListener {
    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          parent;
    private Image  captchaImage;
    private String statusMsg;
    private String inputName, formAction, sessionToken, imageUrl;
    private Command enterCmd, cancelCmd, retryCmd;
    private Font    fontNormal, fontBold;

    public CaptchaScreen(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas parent,
                         String imageUrl, String formAction,
                         String inputName, String sessionToken) {
        this.midlet=midlet; this.parent=parent;
        this.imageUrl=UrlUtils.stripProxy(imageUrl);
        this.formAction=UrlUtils.stripProxy(formAction);
        this.inputName=inputName!=null?inputName:"secretKey";
        this.sessionToken=sessionToken;
        this.statusMsg="Loading captcha image...";
        fontNormal=Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        fontBold  =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        enterCmd =new Command("Enter Code",Command.OK,    1);
        cancelCmd=new Command("Cancel",    Command.CANCEL,2);
        retryCmd =new Command("Retry Image",Command.SCREEN,3);
        addCommand(enterCmd); addCommand(cancelCmd); addCommand(retryCmd);
        setCommandListener(this);
        if (this.imageUrl!=null) loadCaptchaImage(this.imageUrl);
        else { statusMsg="No captcha image URL"; repaint(); }
    }

    private void loadCaptchaImage(final String url) {
        statusMsg="Loading captcha..."; repaint();
        new Thread(new Runnable() { public void run() {
            HttpConnection conn=null; InputStream is=null;
            try {
                String pu="http://"+HttpRequestRunnable.PROXY_HOST
                    +"/?url="+UrlEncoder.encode(UrlUtils.stripProxy(url));
                conn=(HttpConnection)Connector.open(pu);
                conn.setRequestMethod(HttpConnection.GET);
                conn.setRequestProperty("User-Agent","Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.0");
                conn.setRequestProperty("Accept","image/jpeg,image/*,*/*");
                CookieManager.injectCookies(conn,HttpRequestRunnable.PROXY_HOST);
                int rc=conn.getResponseCode();
                if (rc==HttpConnection.HTTP_OK) {
                    CookieManager.storeCookies(conn);
                    is=conn.openInputStream();
                    ByteArrayOutputStream b=new ByteArrayOutputStream();
                    byte[] buf=new byte[512]; int n;
                    while((n=is.read(buf))!=-1) b.write(buf,0,n);
                    final Image img=Image.createImage(b.toByteArray(),0,b.size());
                    midlet.getDisplay().callSerially(new Runnable(){ public void run(){
                        captchaImage=img; statusMsg="Press 'Enter Code' to answer"; repaint();
                    }});
                } else { showMsg("HTTP "+rc); }
            } catch(Exception e) { showMsg("Error: "+e.getMessage()); }
            finally {
                try{if(is!=null)is.close();}catch(Exception e){}
                try{if(conn!=null)conn.close();}catch(Exception e){}
            }
        }}).start();
    }

    private void showMsg(final String m) {
        midlet.getDisplay().callSerially(new Runnable(){public void run(){statusMsg=m;repaint();}});
    }

    protected void paint(Graphics g) {
        int w=getWidth(), h=getHeight();
        g.setColor(0xFFFFFF); g.fillRect(0,0,w,h);
        g.setColor(0xCC0000); g.setFont(fontBold);
        g.drawString("CAPTCHA",w/2,4,Graphics.TOP|Graphics.HCENTER);
        int y=fontBold.getHeight()+12;
        if (captchaImage!=null) {
            g.drawImage(captchaImage,(w-captchaImage.getWidth())/2,y,Graphics.TOP|Graphics.LEFT);
            y+=captchaImage.getHeight()+10;
        } else {
            g.setColor(0xDDDDDD); g.fillRect(w/2-75,y,150,50);
            g.setColor(0x888888); g.setFont(fontNormal);
            g.drawString("[ loading... ]",w/2,y+17,Graphics.TOP|Graphics.HCENTER); y+=62;
        }
        g.setColor(0); g.setFont(fontNormal);
        g.drawString(statusMsg!=null?statusMsg:"",w/2,y,Graphics.TOP|Graphics.HCENTER);
        g.setColor(0x555555);
        g.drawString("Menu: Enter Code / Cancel",w/2,h-fontNormal.getHeight()-4,Graphics.TOP|Graphics.HCENTER);
    }

    public void commandAction(Command c,Displayable d) {
        if      (c==cancelCmd) midlet.getDisplay().setCurrent(parent);
        else if (c==retryCmd)  { captchaImage=null; repaint(); if(imageUrl!=null)loadCaptchaImage(imageUrl); }
        else if (c==enterCmd)  showCodeEntry();
    }

    private void showCodeEntry() {
        final TextBox tb=new TextBox("Type code in image","",20,TextField.ANY);
        // Submit → left (positive), Back → right (dismiss)
        final Command ok=new Command("Submit",Command.OK,    1);
        final Command bk=new Command("Back",  Command.CANCEL,1);
        tb.addCommand(ok); tb.addCommand(bk);
        tb.setCommandListener(new CommandListener(){ public void commandAction(Command c,Displayable d){
            if (c==ok) {
                String code=tb.getString().trim();
                if (code.length()>0) {
                    midlet.getDisplay().setCurrent(parent);
                    parent.submitCaptchaCode(code,formAction,inputName,sessionToken); return;
                }
            }
            midlet.getDisplay().setCurrent(CaptchaScreen.this);
        }});
        midlet.getDisplay().setCurrent(tb);
    }
}

// ======================== Download Progress Screen ========================

class DownloadProgressScreen extends Canvas {
    private VideoProxyBrowserMIDlet midlet;
    private String  title, statusMsg;
    private int     percent;
    private boolean done;
    private Font    fontNormal, fontBold, fontSmall;

    private static final int C_BG     = 0x0F0F0F;
    private static final int C_ACCENT = 0xD42B52;

    public DownloadProgressScreen(VideoProxyBrowserMIDlet midlet, String title) {
        this.midlet=midlet; this.title=title;
        this.statusMsg="Starting..."; this.percent=0;
        fontNormal=Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
        fontBold  =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_BOLD, Font.SIZE_SMALL);
        fontSmall =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN,Font.SIZE_SMALL);
    }

    public void updateProgress(final long dl,final long tot,final int pct) {
        midlet.getDisplay().callSerially(new Runnable(){ public void run(){
            percent=pct<0?0:pct; statusMsg=fmtSize(dl)+(tot>0?" / "+fmtSize(tot):""); repaint();
        }});
    }

    public void setDone(final String msg) {
        midlet.getDisplay().callSerially(new Runnable(){ public void run(){
            done=true; percent=100; statusMsg=msg; repaint();
        }});
    }

    public void setError(final String msg) {
        midlet.getDisplay().callSerially(new Runnable(){ public void run(){
            done=true; statusMsg="Error: "+msg; repaint();
        }});
    }

    private String fmtSize(long b) {
        if(b<1024)return b+" B"; if(b<1024*1024)return (b/1024)+" KB"; return (b/(1024*1024))+" MB";
    }

    protected void paint(Graphics g) {
        int w=getWidth(), h=getHeight();
        g.setColor(C_BG); g.fillRect(0,0,w,h);
        int fy=h/4;
        g.setFont(fontBold); g.setColor(C_ACCENT);
        g.drawString(done?"Complete":"Downloading",w/2,fy,Graphics.TOP|Graphics.HCENTER);
        fy+=fontBold.getHeight()+8;
        g.setFont(fontNormal); g.setColor(0x888888);
        if (title!=null) {
            int mw=w-16, ls=0;
            for (int i=1;i<=title.length();i++) {
                boolean last=(i==title.length());
                if(fontNormal.substringWidth(title,ls,i-ls)>mw||last){
                    int len=last?i-ls:i-1-ls;
                    if(len>0){g.drawSubstring(title,ls,len,w/2,fy,Graphics.TOP|Graphics.HCENTER);fy+=fontNormal.getHeight()+2;}
                    ls=last?i:i-1;
                }
            }
        }
        fy+=6;
        int bw=w-24, bh=10, bx=12;
        g.setColor(0x222222); g.fillRoundRect(bx,fy,bw,bh,bh,bh);
        int fill=(bw*percent)/100;
        if (fill>0) { g.setColor(done?0x22BB66:C_ACCENT); g.fillRoundRect(bx,fy,fill,bh,bh,bh); }
        fy+=bh+6;
        g.setFont(fontBold); g.setColor(0xE8E8E8);
        g.drawString(percent+"%",w/2,fy,Graphics.TOP|Graphics.HCENTER); fy+=fontBold.getHeight()+4;
        g.setFont(fontNormal); g.setColor(0x888888);
        if (statusMsg!=null) g.drawString(statusMsg,w/2,fy,Graphics.TOP|Graphics.HCENTER);
        if (done) {
            fy+=fontNormal.getHeight()+12;
            g.setColor(0x444444); g.drawLine(w/4,fy,3*w/4,fy); fy+=6;
            g.setFont(fontSmall); g.setColor(0x888888);
            g.drawString("Press any key",w/2,fy,Graphics.TOP|Graphics.HCENTER);
        }
    }

    protected void keyPressed(int keyCode) {
        if (done) {
            HtmlPageCanvas b=VideoProxyBrowserMIDlet.getBrowser();
            if (b!=null) midlet.getDisplay().setCurrent(b);
        }
    }

    protected void pointerReleased(int px, int py) {
        keyPressed(Canvas.FIRE); // treat any tap as key press
    }
}

// ======================== Saved Files Screen ========================

class SavedFilesScreen extends List implements CommandListener {
    private VideoProxyBrowserMIDlet midlet;
    private HtmlPageCanvas          browser;
    private FileManager             fm;
    private String[]                filenames;
    private Command playCmd, deleteCmd, backCmd;

    public SavedFilesScreen(VideoProxyBrowserMIDlet midlet, HtmlPageCanvas browser) {
        super("Saved Videos",List.IMPLICIT);
        this.midlet=midlet; this.browser=browser; this.fm=FileManager.getInstance();
        playCmd  =new Command("Play",  Command.OK,    1);
        deleteCmd=new Command("Delete",Command.SCREEN,2);
        backCmd  =new Command("Back",  Command.BACK,  1);
        addCommand(playCmd); addCommand(deleteCmd); addCommand(backCmd);
        setCommandListener(this); refresh();
    }

    public void refresh() {
        deleteAll();
        if (!fm.isReady()) { append("Storage not available: "+fm.getInitError(),null); filenames=new String[0]; return; }
        filenames=fm.listVideos();
        if (filenames.length==0) append("No saved videos",null);
        else for (int i=0;i<filenames.length;i++) append(filenames[i],null);
        setTitle("Saved - "+fm.getAvailableSpaceString()+" free");
    }

    public void commandAction(Command c,Displayable d) {
        if (c==backCmd) { midlet.getDisplay().setCurrent(browser); return; }
        int idx=getSelectedIndex();
        if (filenames==null||filenames.length==0||idx<0||idx>=filenames.length) return;
        String fn=filenames[idx];
        if (c==playCmd||c==SELECT_COMMAND) {
            browser.playLocalFile(fm.getVideoDirUrl()+fn);
            midlet.getDisplay().setCurrent(browser);
        } else if (c==deleteCmd) { fm.deleteVideo(fn); refresh(); }
    }
}

// ======================== Login Screen (Canvas-based dark UI) ========================