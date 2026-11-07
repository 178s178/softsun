package cn.qingri.app;

public final class StudyTimer {
    private long accumulated;
    private long anchor;
    private boolean running;
    public StudyTimer(long accumulated,boolean running,long anchor) {this.accumulated=Math.max(0,accumulated);this.running=running;this.anchor=anchor;}
    public long elapsed(long now) {return accumulated+(running?Math.max(0,now-anchor):0);}
    public void start(long now) {if(!running){anchor=now;running=true;}}
    public void pause(long now) {if(running){accumulated=elapsed(now);running=false;anchor=now;}}
    public void checkpoint(long now) {if(running){accumulated=elapsed(now);anchor=now;}}
    public boolean isRunning(){return running;}
    public long accumulated(){return accumulated;}
    public long anchor(){return anchor;}
}
