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


class HtmlPageCanvas extends Canvas implements CommandListener, HttpCallback {

    private VideoProxyBrowserMIDlet midlet;
    private SiteParser  parser;
    private FileManager fm;
    String  currentUrl;
    private boolean isLoading;
    private String  statusMessage;
    private boolean started;

    private Font fontNormal, fontSmall, fontBold;
    private int  lineHeight, smallLineHeight;

    private Vector displayItems;
    private int    selectedIndex;
    private int    scrollY;

    String sessionToken;

    private Hashtable thumbCache    = new Hashtable();
    private Hashtable thumbFetching = new Hashtable();
    private int       thumbActiveCount = 0;  // cap concurrent thumb threads
    private static final int MAX_THUMB_THREADS = 2;

    // Cached screens - allocate once to avoid heap churn on Retro2ME
    private SavedFilesScreen savedScreen;
    private LoginScreen      loginScreenCached;

    // Auto-refresh (manual toggle)
    private Timer   autoRefreshTimer;
    private boolean autoRefreshOn = false;
    private static final int AUTO_REFRESH_INTERVAL = 60000; // 60 seconds

    // Conversion poll – auto-refresh while file is still converting (no dl links yet)
    private Timer   convPollTimer;
    private static final int CONV_POLL_INTERVAL = 15000; // 15 seconds

    private Command searchCmd, exitCmd, backCmd,
                    refreshCmd, loginCmd, savedCmd, aboutCmd, autoRefreshCmd, convertCmd, musicSearchCmd, imageSearchCmd, queueCmd;
    private ConvertScreen convertScreen;
    private Vector historyUrls;

    // Dark flat palette
    private static final int C_BG      = 0x0F0F0F;
    private static final int C_CARD0   = 0x1A1A1A;
    private static final int C_CARD1   = 0x161616;
    private static final int C_TEXT    = 0xE8E8E8;
    private static final int C_SUBTEXT = 0x888888;
    private static final int C_LINK    = 0xA0C8FF;
    private static final int C_ACCENT  = 0xD42B52;
    private static final int C_SELBG   = 0xD42B52;
    private static final int C_SELTXT  = 0xFFFFFF;
    private static final int C_STBG    = 0x0A0A0A;
    private static final int C_STTXT   = 0xCCCCCC;
    private static final int C_SRCH    = 0x1C1C2E;
    private static final int C_PAGE    = 0x0F1F0F;
    private static final int C_CAP     = 0x2A0F0F;
    private static final int C_CONV    = 0x1A160A;
    private static final int C_DL      = 0x0A1A12;
    private static final int ACCENT_W  = 3;

    public HtmlPageCanvas(VideoProxyBrowserMIDlet midlet) {
        this.midlet=midlet;
        parser=new SiteParser(); fm=FileManager.getInstance();
        fontNormal=Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontSmall =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontBold  =Font.getFont(Font.FACE_PROPORTIONAL,Font.STYLE_BOLD,  Font.SIZE_SMALL);
        lineHeight     =fontNormal.getHeight()+4;
        smallLineHeight=fontSmall.getHeight() +2;
        displayItems=new Vector(); historyUrls=new Vector();

        searchCmd =new Command("Search",       Command.SCREEN,1);
        refreshCmd=new Command("Refresh",      Command.SCREEN,2);
        savedCmd  =new Command("My Videos",    Command.SCREEN,3);
        loginCmd  =new Command("Login",        Command.SCREEN,4);
        musicSearchCmd = new Command("Music Search", Command.SCREEN, 5);
        imageSearchCmd = new Command("Image Search", Command.SCREEN, 5);
        queueCmd       = new Command("Download Queue", Command.SCREEN, 6);
        aboutCmd  =new Command("About",        Command.SCREEN,6);
        autoRefreshCmd=new Command("Auto Refresh: OFF",Command.SCREEN,7);
        convertCmd=new Command("Converter",    Command.SCREEN,8);
        backCmd   =new Command("Back",         Command.BACK,  1);
        exitCmd   =new Command("Exit",         Command.EXIT,  9);
        
        addCommand(searchCmd);  
        addCommand(refreshCmd);
        addCommand(savedCmd);   
        addCommand(loginCmd);
        addCommand(musicSearchCmd);
        addCommand(imageSearchCmd);
        addCommand(queueCmd);
        addCommand(aboutCmd);   
        addCommand(autoRefreshCmd);
        addCommand(convertCmd);
        addCommand(backCmd);
        addCommand(exitCmd);
        
        setCommandListener(this);
        statusMessage="Welcome to DashTube";
    }

    public void startLoadingInitialPage() {
        if (!started) { started=true; navigateTo("http://video.2yxa.mobi/"); }
    }

    void navigateTo(String url) {
        String clean=UrlUtils.stripProxy(url);
        if (currentUrl!=null) historyUrls.addElement(currentUrl);
        currentUrl=clean; fetchUrl(clean,null,false);
    }

    private void goBack() {
        if (historyUrls.size()>0) {
            int last=historyUrls.size()-1;
            String prev=(String)historyUrls.elementAt(last);
            historyUrls.removeElementAt(last); currentUrl=prev; fetchUrl(prev,null,false);
        }
    }

    void fetchUrl(String url, String postData, boolean isPost) {
        isLoading=true; statusMessage="Loading..."; repaint();
        new Thread(new HttpRequestRunnable(url,postData,isPost,this)).start();
    }

    public void onSuccess(String content, String baseUrl) {
        currentUrl=baseUrl; parser.parse(content);
        String t=parser.getSessionToken(); if(t!=null&&t.length()>0) sessionToken=t;
        displayItems=parser.getItems(); selectedIndex=displayItems.size()>0?0:-1;
        scrollY=0; isLoading=false;
        statusMessage=displayItems.size()+" items";
        if (parser.isConversionReadyPage()) {
            if (parser.hasDownloadLinks()) {
                // Links are ready – stop any conversion poll
                stopConvPoll();
                statusMessage="Ready! Choose download below";
            } else {
                // Still converting – start auto-poll if not already running
                statusMessage="Converting... auto-refresh in 15s";
                startConvPoll();
            }
        } else {
            stopConvPoll();
        }
        repaint();
    }

    private void startConvPoll() {
        if (convPollTimer != null) return; // already running
        convPollTimer = new Timer();
        final HtmlPageCanvas self = this;
        convPollTimer.schedule(new TimerTask() {
            public void run() {
                if (currentUrl != null && !isLoading) {
                    statusMessage = "Auto-refreshing...";
                    midlet.getDisplay().callSerially(new Runnable(){public void run(){ repaint(); }});
                    fetchUrl(currentUrl, null, false);
                }
            }
        }, CONV_POLL_INTERVAL, CONV_POLL_INTERVAL);
    }

    private void stopConvPoll() {
        if (convPollTimer != null) {
            try { convPollTimer.cancel(); } catch(Exception e) {}
            convPollTimer = null;
        }
    }

    public void onError(String errorMsg) {
        isLoading=false; statusMessage="Error: "+errorMsg;
        stopConvPoll(); // stop polling on network failure
        repaint();
    }

    public void submitCaptchaCode(String code, String action, String inputName, String token) {
        if (action==null) action=currentUrl;
        action=UrlUtils.stripProxy(action);
        String post=inputName+"="+UrlEncoder.encode(code);
        if (token!=null&&token.length()>0) post+="&ILOVE2YXA="+UrlEncoder.encode(token);
        fetchUrl(action,post,true);
    }

    public void playLocalFile(String fileUrl) {
        statusMessage="Opening saved file..."; repaint();
        try {
            Player p=Manager.createPlayer(fileUrl); p.realize();
            VolumeControl vc=(VolumeControl)p.getControl("VolumeControl");
            if (vc!=null) vc.setLevel(100);
            p.start(); statusMessage="Playing local file";
        } catch(Exception e) { statusMessage="Play error: "+e.getMessage(); }
        repaint();
    }

    // ---- Media playback ----
    public void playMedia(final String url) {
        statusMessage="Starting stream..."; repaint();
        String mime=MediaUtils.guessMime(url);
        if (MediaUtils.isDirectlyPlayable(mime)) { if (tryDirectStream(url)) return; }
        statusMessage="Buffering (download-then-play)..."; repaint();
        downloadThenPlay(url);
    }

    private boolean tryDirectStream(String url) {
        try {
            String clean=UrlUtils.stripProxy(url);
            if (clean.startsWith("https://video.2yxa.mobi/"))
                clean="http://video.2yxa.mobi/"+clean.substring("https://video.2yxa.mobi/".length());
            else if (clean.startsWith("https://")) clean="http://"+clean.substring(8);
            Player p=Manager.createPlayer(clean); p.realize();
            VolumeControl vc=(VolumeControl)p.getControl("VolumeControl");
            if (vc!=null) vc.setLevel(100);
            p.start(); statusMessage="Streaming"; repaint(); return true;
        } catch(Exception e) { return false; }
    }

    private void downloadThenPlay(final String url) {
        if (!fm.isReady()) { statusMessage="Storage unavailable: "+fm.getInitError(); repaint(); return; }
        final String tempPath=fm.getTempFileUrl(url);
        final DownloadProgressScreen prog=new DownloadProgressScreen(midlet,FileManager.filenameFromUrl(url));
        midlet.getDisplay().setCurrent(prog);
        new Thread(new DownloadAndPlayRunnable(url,tempPath, new DlListener() {
            public void onProgress(long dl,long tot,int pct) { prog.updateProgress(dl,tot,pct); }
            public void onComplete(String path,long bytes) {
                prog.setDone("Buffered "+fmtSize(bytes)+". Playing...");
                midlet.getDisplay().callSerially(new Runnable(){ public void run(){
                    midlet.getDisplay().setCurrent(HtmlPageCanvas.this); playLocalFile(tempPath);
                }});
            }
            public void onError(String msg) {
                if (msg!=null&&msg.startsWith("PLAY_FAIL:")) {
                    String localPath=msg.substring(10);
                    prog.setDone("Player failed. Try system player.");
                    tryPlatformRequest(localPath);
                } else { prog.setError(msg); statusMessage="Failed: "+msg; }
            }
            private String fmtSize(long b){
                if(b<1024)return b+" B";if(b<1024*1024)return (b/1024)+" KB";return (b/(1024*1024))+" MB";
            }
        }, true)).start();
    }

    private void tryPlatformRequest(final String fileUrl) {
        midlet.getDisplay().callSerially(new Runnable(){ public void run(){
            try { midlet.platformRequest(fileUrl); statusMessage="Opened in system player"; }
            catch(Exception e) { statusMessage="platformRequest error: "+e.getMessage(); }
            repaint();
        }});
    }

    // ---- Download choice: opens DownloadChoiceCanvas ----
    private void showDownloadChoice(final PageItem item) {
        midlet.getDisplay().setCurrent(new DownloadChoiceCanvas(midlet, this, item, fm));
    }

    // Public wrapper so DownloadChoiceCanvas can call it
    public void startDownloadPublic(String url, String fn) {
        startDownload(url, fn);
    }

    private void startDownload(final String url, final String fn) {
        if (!fm.isReady()) { statusMessage="Storage: "+fm.getInitError(); repaint(); return; }
        final DownloadProgressScreen prog=new DownloadProgressScreen(midlet,fn);
        midlet.getDisplay().setCurrent(prog);
        new Thread(new Runnable(){ public void run(){
            fm.saveToDir(url, null, fn, new DlListener() {
                public void onProgress(long dl,long tot,int pct) { prog.updateProgress(dl,tot,pct); }
                public void onComplete(String path,long bytes) {
                    prog.setDone("Saved! "+(bytes/1024)+" KB  "+fm.getAvailableSpaceString()+" free");
                    statusMessage="Saved: "+fn;
                }
                public void onError(String msg) { prog.setError(msg); statusMessage="Failed: "+msg; }
            });
        }}).start();
    }

    // ---- Paint ----
    protected void paint(Graphics g) {
        int w=getWidth(), h=getHeight();
        int sH=fontSmall.getHeight()+8;
        int vH=h-sH;
        int maxW=w-8;

        g.setColor(C_BG); g.fillRect(0,0,w,h);

        int y=2-scrollY; int totalH=2;
        for (int i=0;i<displayItems.size();i++) {
            PageItem item=(PageItem)displayItems.elementAt(i);
            int ih=calcH(item,maxW);
            if (y+ih>0&&y<vH) drawItem(g,item,4,y,maxW,ih,i==selectedIndex,i);
            y+=ih+3; totalH+=ih+3;
        }

        // Scrollbar
        if (totalH>vH) {
            int sbW=3, sbX=w-sbW-1;
            g.setColor(0x222222); g.fillRect(sbX,0,sbW,vH);
            int thumbH=Math.max(12,(vH*vH)/totalH);
            int thumbY=(totalH>vH)?(scrollY*(vH-thumbH))/(totalH-vH):0;
            if (thumbY<0) thumbY=0; if (thumbY+thumbH>vH) thumbY=vH-thumbH;
            g.setColor(C_ACCENT); g.fillRect(sbX,thumbY,sbW,thumbH);
        }

        // Status bar
        g.setColor(C_STBG); g.fillRect(0,h-sH,w,sH);
        g.setColor(C_ACCENT); g.drawLine(0,h-sH,w,h-sH);
        g.setColor(C_STTXT); g.setFont(fontSmall);
        g.drawString(statusMessage,4,h-sH+4,Graphics.TOP|Graphics.LEFT);

        // Loading overlay
        if (isLoading) {
            int ow=80, oh=20, ox=(w-ow)/2, oy=(vH-oh)/2;
            g.setColor(0x1A1A1A); g.fillRect(ox-2,oy-2,ow+4,oh+4);
            g.setColor(C_ACCENT); g.drawRect(ox-2,oy-2,ow+4,oh+4);
            g.setColor(0xE8E8E8); g.setFont(fontBold);
            g.drawString("Loading...",w/2,oy+3,Graphics.TOP|Graphics.HCENTER);
        }
    }

    private int calcH(PageItem item, int maxW) {
        int bh=fontBold.getHeight()+2, nh=fontNormal.getHeight()+2, pad=8;
        switch (item.type) {
            case PageItem.TYPE_VIDEO_RESULT: {
                int thumbW=maxW/3, thumbH=thumbW*3/4; if(thumbH<32)thumbH=32;
                int textH=wrapCount(item.text,fontBold,maxW-thumbW-8)*bh+smallLineHeight+4;
                return Math.max(thumbH,textH)+pad;
            }
            case PageItem.TYPE_DOWNLOAD:    return wrapCount(item.text,fontBold,maxW-10)*bh+pad+2;
            case PageItem.TYPE_SEARCH_FORM: return fontBold.getHeight()+pad+6;
            case PageItem.TYPE_PAGINATION:
            case PageItem.TYPE_CAPTCHA:
            case PageItem.TYPE_CONVERT:     return fontNormal.getHeight()+pad+2;
            case PageItem.TYPE_TEXT:        return wrapCount(item.text,fontNormal,maxW-8)*nh+4;
            default:                        return wrapCount(item.text,fontNormal,maxW-8)*nh+4;
        }
    }

    private int wrapCount(String text, Font f, int maxW) {
        if (text==null||text.length()==0) return 1;
        int lines=1, ls=0;
        for (int i=1;i<=text.length();i++)
            if (f.substringWidth(text,ls,i-ls)>maxW) { lines++; ls=i-1; }
        return lines;
    }

    private void drawItem(Graphics g, PageItem item,
                          int x, int y, int maxW, int itemH,
                          boolean sel, int idx) {
        int bg;
        switch (item.type) {
            case PageItem.TYPE_VIDEO_RESULT: bg=(idx%2==0)?C_CARD0:C_CARD1; break;
            case PageItem.TYPE_SEARCH_FORM:  bg=C_SRCH; break;
            case PageItem.TYPE_PAGINATION:   bg=C_PAGE; break;
            case PageItem.TYPE_CAPTCHA:      bg=C_CAP;  break;
            case PageItem.TYPE_CONVERT:      bg=C_CONV; break;
            case PageItem.TYPE_DOWNLOAD:     bg=C_DL;   break;
            default:                         bg=C_BG;   break;
        }
        if (sel) { g.setColor(C_SELBG); g.fillRect(x-4,y,maxW+8,itemH); }
        else     { g.setColor(bg);      g.fillRect(x-4,y,maxW+8,itemH); }

        int barColor=0; boolean showBar=false;
        switch (item.type) {
            case PageItem.TYPE_VIDEO_RESULT: barColor=0x3A6EDB; showBar=true; break;
            case PageItem.TYPE_DOWNLOAD:     barColor=0x22BB66; showBar=true; break;
            case PageItem.TYPE_CONVERT:      barColor=0xE8A020; showBar=true; break;
            case PageItem.TYPE_SEARCH_FORM:  barColor=C_ACCENT; showBar=true; break;
            case PageItem.TYPE_CAPTCHA:      barColor=0xCC2222; showBar=true; break;
            case PageItem.TYPE_PAGINATION:   barColor=0x444466; showBar=true; break;
        }
        if (!sel&&showBar) { g.setColor(barColor); g.fillRect(x-4,y,ACCENT_W,itemH); }

        int tc=sel?C_SELTXT:C_TEXT;
        int cx=x+(showBar&&!sel?ACCENT_W+3:2);
        int cw=maxW-(showBar&&!sel?ACCENT_W+5:4);
        int ty=y+4;

        if (!sel) { g.setColor(0x222222); g.drawLine(x-4,y+itemH-1,x+maxW+4,y+itemH-1); }

        switch (item.type) {
            case PageItem.TYPE_VIDEO_RESULT: {
                int thumbW=maxW/3, thumbH=thumbW*3/4; if(thumbH<32)thumbH=32;
                int thumbX=x+(showBar&&!sel?ACCENT_W+2:0);
                int thumbY=y+(itemH-thumbH)/2;
                if (item.videoId!=null) requestThumb(item.videoId);
                Image thumb=(item.videoId!=null)?(Image)thumbCache.get(item.videoId):null;
                if (thumb!=null) {
                    g.setClip(thumbX,thumbY,thumbW,thumbH);
                    int iw=thumb.getWidth(), ih=thumb.getHeight();
                    int drawW=thumbW, drawH=(thumbW*ih)/iw;
                    if (drawH>thumbH) { drawH=thumbH; drawW=(thumbH*iw)/ih; }
                    g.drawImage(thumb,thumbX+(thumbW-drawW)/2,thumbY+(thumbH-drawH)/2,Graphics.TOP|Graphics.LEFT);
                    g.setClip(0,0,getWidth(),getHeight());
                } else {
                    g.setColor(sel?0x444444:0x222233); g.fillRect(thumbX,thumbY,thumbW,thumbH);
                    g.setColor(sel?0x888888:0x555566); g.drawRect(thumbX,thumbY,thumbW-1,thumbH-1);
                    int px=thumbX+thumbW/2, py=thumbY+thumbH/2;
                    g.setColor(sel?0xCCCCCC:0x3A6EDB);
                    g.fillTriangle(px-5,py-6,px-5,py+6,px+6,py);
                }
                int txX=thumbX+thumbW+4;
                int txW=maxW-thumbW-(showBar&&!sel?ACCENT_W+2:0)-8;
                g.setFont(fontBold); g.setColor(tc);
                int uh=drawWrap(g,item.text,txX,ty,txW,fontBold)*(fontBold.getHeight()+2);
                if (item.extra!=null&&item.extra.length()>0) {
                    g.setFont(fontSmall); g.setColor(sel?C_SELTXT:C_SUBTEXT);
                    g.drawSubstring(item.extra,0,Math.min(item.extra.length(),32),txX,ty+uh,Graphics.TOP|Graphics.LEFT);
                }
                break;
            }
            case PageItem.TYPE_DOWNLOAD: {
                g.setFont(fontBold); g.setColor(sel?C_SELTXT:0x33DD77);
                drawWrap(g,item.text,cx,ty,cw,fontBold); break;
            }
            case PageItem.TYPE_CONVERT: {
                g.setFont(fontNormal); g.setColor(sel?C_SELTXT:0xE8A020);
                g.drawString(item.text,cx,ty,Graphics.TOP|Graphics.LEFT); break;
            }
            case PageItem.TYPE_CAPTCHA: {
                g.setFont(fontBold); g.setColor(sel?C_SELTXT:0xFF5555);
                g.drawString(item.text,cx,ty,Graphics.TOP|Graphics.LEFT); break;
            }
            case PageItem.TYPE_SEARCH_FORM: {
                g.setFont(fontBold); g.setColor(sel?C_SELTXT:C_ACCENT);
                g.drawString(item.text,x+maxW/2,ty,Graphics.TOP|Graphics.HCENTER); break;
            }
            case PageItem.TYPE_PAGINATION: {
                g.setFont(fontNormal); g.setColor(sel?C_SELTXT:C_LINK);
                g.drawString(item.text,cx,ty,Graphics.TOP|Graphics.LEFT); break;
            }
            case PageItem.TYPE_TEXT: {
                g.setFont(fontSmall); g.setColor(sel?C_SELTXT:C_SUBTEXT);
                drawWrap(g,item.text,x+4,ty,maxW-8,fontSmall); break;
            }
            default: {
                g.setFont(fontNormal); g.setColor(sel?C_SELTXT:C_LINK);
                drawWrap(g,item.text,cx,ty,cw,fontNormal); break;
            }
        }
    }

    private int drawWrap(Graphics g, String text, int x, int y, int maxW, Font f) {
        if (text==null) return 0;
        g.setFont(f);
        int fh=f.getHeight()+2, ls=0, cy=y, lines=0;
        for (int i=1;i<=text.length();i++) {
            boolean last=(i==text.length());
            boolean over=!last&&f.substringWidth(text,ls,i-ls+1)>maxW;
            if (over||last) {
                int len=last&&!over?i-ls:i-1-ls;
                if (len<=0) { ls=i-1; continue; }
                g.drawSubstring(text,ls,len,x,cy,Graphics.TOP|Graphics.LEFT);
                cy+=fh; lines++;
                ls=last&&!over?i:i-1;
            }
        }
        return Math.max(lines,1);
    }

    protected void keyPressed(int keyCode) {
        if (isLoading) return;
        int action = getGameActionSafe(keyCode);
        if      (action==Canvas.UP)   { if(selectedIndex>0){selectedIndex--;ensureVisible();} repaint(); }
        else if (action==Canvas.DOWN) { if(selectedIndex<displayItems.size()-1){selectedIndex++;ensureVisible();} repaint(); }
        else if (action==Canvas.FIRE) { activateSelected(); }
        else if (keyCode==KEY_NUM2)   { scrollY=Math.max(0,scrollY-getHeight()/2); clampScroll(); repaint(); }
        else if (keyCode==KEY_NUM8)   { scrollY+=getHeight()/2; clampScroll(); repaint(); }
        else if (keyCode==KEY_NUM0)   { showSearchBox(); }
        else if (keyCode==KEY_NUM5)   { activateSelected(); }  // Nokia 5800 OK key
        else if (keyCode==KEY_NUM7)   { midlet.getDisplay().setCurrent(new SavedFilesScreen(midlet,this)); }
    }

    /** Prevents scrollY from going past the last item. */
    private void clampScroll() {
        int sH=fontSmall.getHeight()+8, vH=getHeight()-sH;
        int totalH=2;
        for (int i=0;i<displayItems.size();i++)
            totalH+=calcH((PageItem)displayItems.elementAt(i),getWidth()-8)+3;
        int maxScroll=Math.max(0,totalH-vH);
        if (scrollY>maxScroll) scrollY=maxScroll;
        if (scrollY<0)         scrollY=0;
    }

    /**
     * Nokia 5800 (S60 5th edition) quirks:
     *  - The touch "OK/Select" area fires keyCode -8 (S60 selection key)
     *  - The d-pad sends -1=UP -6=DOWN -2=LEFT -5=RIGHT on some firmware
     *  - getGameAction() returns 0 for these on S60 touch
     * We supplement getGameAction() with explicit checks.
     */
    private int getGameActionSafe(int keyCode) {
        // First try the standard mapping
        int action = 0;
        try { action = getGameAction(keyCode); } catch(Exception e) {}
        if (action != 0) return action;
        // Nokia S60 raw key codes
        switch (keyCode) {
            case -8:  return Canvas.FIRE;   // S60 Selection / centre tap
            case -1:  return Canvas.UP;
            case -6:  return Canvas.DOWN;
            case -2:  return Canvas.LEFT;
            case -5:  return Canvas.RIGHT;
            case  53: return Canvas.FIRE;   // KEY_NUM5 as fire fallback
        }
        return 0;
    }

    // Touch support for Nokia 5800 (tap to select & activate)
    protected void pointerPressed(int px, int py) {
        if (isLoading) return;
        int sH = fontSmall.getHeight()+8;
        int vH = getHeight()-sH;
        if (py >= vH) return; // tapped status bar
        // Find which item was tapped
        int y = 2 - scrollY;
        for (int i = 0; i < displayItems.size(); i++) {
            PageItem item = (PageItem)displayItems.elementAt(i);
            int ih = calcH(item, getWidth()-8);
            if (py >= y && py < y+ih) {
                selectedIndex = i;
                repaint();
                return;
            }
            y += ih+3;
        }
    }

    protected void pointerReleased(int px, int py) {
        if (isLoading) return;
        int sH = fontSmall.getHeight()+8;
        int vH = getHeight()-sH;
        if (py >= vH) return;
        int y = 2 - scrollY;
        for (int i = 0; i < displayItems.size(); i++) {
            PageItem item = (PageItem)displayItems.elementAt(i);
            int ih = calcH(item, getWidth()-8);
            if (py >= y && py < y+ih) {
                if (i == selectedIndex) activateSelected(); // tap = select + activate
                else { selectedIndex = i; repaint(); }
                return;
            }
            y += ih+3;
        }
    }

    private void ensureVisible() {
        if (selectedIndex<0||selectedIndex>=displayItems.size()) return;
        int sh=fontSmall.getHeight()+8, vh=getHeight()-sh;
        int y=2;
        for (int i=0;i<selectedIndex&&i<displayItems.size();i++)
            y+=calcH((PageItem)displayItems.elementAt(i),getWidth()-8)+3;
        int selH=calcH((PageItem)displayItems.elementAt(selectedIndex),getWidth()-8);
        if (y<scrollY) scrollY=y-2;
        else if (y+selH>scrollY+vh) scrollY=y+selH-vh;
        if (scrollY<0) scrollY=0;
    }

    private void activateSelected() {
        if (selectedIndex<0||selectedIndex>=displayItems.size()) return;
        PageItem item=(PageItem)displayItems.elementAt(selectedIndex);
        switch (item.type) {
            case PageItem.TYPE_VIDEO_RESULT:
            case PageItem.TYPE_LINK:
            case PageItem.TYPE_PAGINATION:
                if (item.url!=null) navigateTo(item.url); break;
            case PageItem.TYPE_CONVERT:
                openConverter(item.url); break;
            case PageItem.TYPE_DOWNLOAD:
                if (item.url!=null) showDownloadChoice(item); break;
            case PageItem.TYPE_SEARCH_FORM:
                showSearchBox(); break;
            case PageItem.TYPE_CAPTCHA:
                midlet.getDisplay().setCurrent(
                    new CaptchaScreen(midlet,this,item.extra2,item.url,item.extra,sessionToken)); break;
            case PageItem.TYPE_SAVED:
                if (item.url!=null) playLocalFile(item.url); break;
        }
    }

    public void openConverter(String prefilledUrl) {
        if (convertScreen == null) convertScreen = new ConvertScreen(midlet, this);
        if (prefilledUrl != null && prefilledUrl.length() > 0)
            convertScreen.setPrefilledUrl(prefilledUrl);
        midlet.getDisplay().setCurrent(convertScreen);
    }

    private void showSearchBox() {
        final TextBox tb=new TextBox("Search Videos","",128,TextField.ANY);
        // OK (priority 1) → left softkey = positive "Search" action
        // CANCEL (priority 2) → right softkey = dismiss, consistent with rest of app
        final Command ok =new Command("Search",Command.OK,    1);
        final Command cnl=new Command("Cancel",Command.CANCEL,1);
        tb.addCommand(ok); tb.addCommand(cnl);
        tb.setCommandListener(new CommandListener(){ public void commandAction(Command c,Displayable d){
            if (c==ok) {
                String q=tb.getString().trim();
                if (q.length()>0) { midlet.getDisplay().setCurrent(HtmlPageCanvas.this); performSearch(q); return; }
            }
            midlet.getDisplay().setCurrent(HtmlPageCanvas.this);
        }});
        midlet.getDisplay().setCurrent(tb);
    }

    private void performSearch(String query) {
        StringBuffer url=new StringBuffer("http://video.2yxa.mobi/");
        url.append("?query=").append(UrlEncoder.encode(query));
        url.append("&sort=relevance&server=you");
        if (sessionToken!=null&&sessionToken.length()>0)
            url.append("&ILOVE2YXA=").append(UrlEncoder.encode(sessionToken));
        navigateTo(url.toString());
    }

    public void commandAction(Command c,Displayable d) {
        if      (c==exitCmd)    midlet.notifyDestroyed();
        else if (c==searchCmd)  showSearchBox();
        else if (c==musicSearchCmd) midlet.showMusicSearch();
        else if (c==imageSearchCmd) midlet.showImageSearch();
        else if (c==queueCmd)       midlet.getDisplay().setCurrent(DownloadQueue.getInstance(midlet).getScreen());
        else if (c==savedCmd) {
            if (savedScreen==null) savedScreen=new SavedFilesScreen(midlet,this);
            else savedScreen.refresh();
            midlet.getDisplay().setCurrent(savedScreen);
        }
        else if (c==loginCmd) {
            if (loginScreenCached==null) loginScreenCached=new LoginScreen(midlet,this);
            midlet.getDisplay().setCurrent(loginScreenCached);
        }
        else if (c==aboutCmd)   midlet.getDisplay().setCurrent(new AboutCanvas(midlet,this));
        else if (c==convertCmd) openConverter(null);
        else if (c==refreshCmd&&currentUrl!=null) fetchUrl(currentUrl,null,false);
        else if (c==backCmd)    goBack();
        else if (c==autoRefreshCmd) toggleAutoRefresh();
    }

    private void toggleAutoRefresh() {
        autoRefreshOn=!autoRefreshOn;
        removeCommand(autoRefreshCmd);
        autoRefreshCmd=new Command("Auto Refresh: "+(autoRefreshOn?"ON ":"OFF"),Command.SCREEN,7);
        addCommand(autoRefreshCmd);
        if (autoRefreshOn) {
            if (autoRefreshTimer!=null) { try{autoRefreshTimer.cancel();}catch(Exception e){} }
            autoRefreshTimer=new Timer();
            autoRefreshTimer.schedule(new TimerTask(){
                public void run(){
                    if (autoRefreshOn&&currentUrl!=null&&!isLoading) {
                        midlet.getDisplay().callSerially(new Runnable(){public void run(){
                            fetchUrl(currentUrl,null,false);
                        }});
                    }
                }
            }, AUTO_REFRESH_INTERVAL, AUTO_REFRESH_INTERVAL);
            statusMessage="Auto-refresh ON (60s)"; repaint();
        } else {
            if (autoRefreshTimer!=null) { try{autoRefreshTimer.cancel();}catch(Exception e){} autoRefreshTimer=null; }
            statusMessage="Auto-refresh OFF"; repaint();
        }
    }

    // ---- Thumbnail loader ----
    private void requestThumb(final String videoId) {
        if (videoId==null||videoId.length()==0) return;
        if (thumbCache.containsKey(videoId))    return;
        if (thumbFetching.containsKey(videoId)) return;
        if (thumbActiveCount>=MAX_THUMB_THREADS) return; // cap threads
        thumbFetching.put(videoId,videoId);
        thumbActiveCount++;
        new Thread(new Runnable(){ public void run(){
            Image img=fetchThumb(videoId);
            thumbFetching.remove(videoId);
            thumbActiveCount--;
            if (thumbActiveCount<0) thumbActiveCount=0;
            if (img!=null) {
                thumbCache.put(videoId,img);
                midlet.getDisplay().callSerially(new Runnable(){public void run(){repaint();}});
            }
        }}).start();
    }

    private Image fetchThumb(String videoId) {
        // Direct fetch from YouTube - no proxy needed
        String url="http://img.youtube.com/vi/"+videoId+"/default.jpg";
        HttpConnection conn=null; InputStream is=null;
        try {
            conn=(HttpConnection)Connector.open(url);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent","Mozilla/5.0 (J2ME; MIDP-2.0; CLDC-1.1) DashTube/1.0");
            int rc=conn.getResponseCode(); if(rc!=HttpConnection.HTTP_OK)return null;
            is=conn.openInputStream();
            ByteArrayOutputStream baos=new ByteArrayOutputStream();
            byte[] buf=new byte[1024]; int n;
            while((n=is.read(buf))!=-1) baos.write(buf,0,n);
            byte[] data=baos.toByteArray();
            if (data.length<100) return null;
            return Image.createImage(data,0,data.length);
        } catch(Exception e) { return null; }
        finally {
            try{if(is!=null)is.close();}catch(Exception e){}
            try{if(conn!=null)conn.close();}catch(Exception e){}
        }
    }

    public void shutdown() {
        autoRefreshOn=false;
        if (autoRefreshTimer!=null) { try{autoRefreshTimer.cancel();}catch(Exception e){} autoRefreshTimer=null; }
        stopConvPoll();
    }
}