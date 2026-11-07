package cn.qingri.app;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;

/** Cached, perspective-projected sectors with real thickness and matching hit regions. */
public final class PieView extends View {
    public interface OnSliceSelectedListener {void onSliceSelected(int index);}
    private static final float TILT=.64f;
    private static final ExecutorService DRAWER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"qingri-pie");t.setDaemon(true);return t;});
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds=new RectF();
    private List<TimeStats.Slice> slices=Collections.emptyList();
    private double[] ends=new double[0];
    private int[] colors=new int[0];
    private long total;
    private int selected=-1,generation;
    private Bitmap bitmap;
    private Future<?> pending;
    private OnSliceSelectedListener listener;
    private float downX,downY,depth;
    private int downHit=-1;

    public PieView(Context c){super(c);setFocusable(true);setClickable(true);setContentDescription("学习时长扇形图，立体扇区，可点选单次学习");}
    public static int colorFor(TimeRecord record){int h=(record.token==null?0:record.token.hashCode())&0x7fffffff;return Color.HSVToColor(new float[]{30+(h%142),.36f+((h>>>8)%22)/100f,.62f+((h>>>16)%23)/100f});}
    public void setOnSliceSelectedListener(OnSliceSelectedListener value){listener=value;}
    public void setData(TimeStats.Summary data){
        slices=new ArrayList<>(data.slices);total=data.total;ends=new double[slices.size()];colors=new int[slices.size()];
        double sum=0;for(int i=0;i<slices.size();i++){sum+=(double)slices.get(i).duration/Math.max(1,total)*360;ends[i]=sum;colors[i]=colorFor(slices.get(i).record);}if(ends.length>0)ends[ends.length-1]=360;
        selected=-1;setContentDescription("学习时长扇形图，共"+slices.size()+"次学习，总有效时长"+TimeStats.duration(total)+"；立体扇区，点选扇区或下方记录可查看占比");rebuild(true);
    }
    public void setSelectedIndex(int index){int value=index>=0&&index<slices.size()?index:-1;if(value!=selected){selected=value;rebuild(false);}}
    private float dp(float value){return getResources().getDisplayMetrics().density*value;}
    private void geometry(){
        depth=dp(24);float padding=dp(20),shadow=dp(12);
        float radius=Math.max(0,Math.min((getWidth()-2*padding)/2,(getHeight()-2*padding-depth-shadow)/(2*TILT)));
        float centerY=(getHeight()-depth-shadow)/2;bounds.set(getWidth()/2f-radius,centerY-radius*TILT,getWidth()/2f+radius,centerY+radius*TILT);
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){geometry();rebuild(true);}
    private static int shade(int color,float factor){return Color.rgb(Math.min(255,Math.round(Color.red(color)*factor)),Math.min(255,Math.round(Color.green(color)*factor)),Math.min(255,Math.round(Color.blue(color)*factor)));}
    private static int blendWhite(int color,float amount){return Color.rgb(Math.round(Color.red(color)*(1-amount)+255*amount),Math.round(Color.green(color)*(1-amount)+255*amount),Math.round(Color.blue(color)*(1-amount)+255*amount));}
    private static float x(RectF area,double angle){return area.centerX()+area.width()/2*(float)Math.cos(Math.toRadians(angle));}
    private static float y(RectF area,double angle){return area.centerY()+area.height()/2*(float)Math.sin(Math.toRadians(angle));}
    private static RectF lifted(RectF area,double start,double end,float offset,float lift){RectF moved=new RectF(area);double mid=Math.toRadians((start+end)/2-90);moved.offset((float)Math.cos(mid)*offset,(float)Math.sin(mid)*offset*TILT-lift);return moved;}
    private static void shadow(Canvas canvas,RectF area,float amount,float blur,double start,double end,boolean whole){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(0x231C3A28);p.setShadowLayer(blur,0,blur*.35f,0x4613291D);RectF ground=new RectF(area);ground.offset(0,amount);
        if(whole)canvas.drawOval(ground,p);else canvas.drawArc(ground,(float)start-90,(float)(end-start),true,p);
    }
    private static Path radialFace(RectF area,double angle,float depth){Path path=new Path();float cx=area.centerX(),cy=area.centerY(),px=x(area,angle),py=y(area,angle);path.moveTo(cx,cy);path.lineTo(px,py);path.lineTo(px,py+depth);path.lineTo(cx,cy+depth);path.close();return path;}
    private static Path topSector(RectF area,double start,double end){Path path=new Path();path.moveTo(area.centerX(),area.centerY());path.arcTo(area,(float)start-90,(float)(end-start),false);path.close();return path;}
    private static void facePaint(Paint paint,RectF area,int color,float depth,float brightness){
        paint.setStyle(Paint.Style.FILL);paint.setShader(new LinearGradient(0,area.top,0,area.bottom+depth,new int[]{shade(color,brightness),shade(color,brightness*.72f)},null,Shader.TileMode.CLAMP));
    }
    private static void sides(Canvas canvas,RectF area,double start,double end,int color,float thickness,boolean cuts){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);double a=start-90,b=end-90;
        if(cuts&&end-start<359.999){
            if(Math.cos(Math.toRadians(a))<-0.000001){facePaint(p,area,color,thickness,.69f);canvas.drawPath(radialFace(area,a,thickness),p);}
            if(Math.cos(Math.toRadians(b))>0.000001){facePaint(p,area,color,thickness,.78f);canvas.drawPath(radialFace(area,b,thickness),p);}
        }
        double frontStart=Math.max(90,start),frontEnd=Math.min(270,end);
        if(frontEnd<=frontStart)return;
        float angle=(float)frontStart-90,sweep=(float)(frontEnd-frontStart);RectF bottom=new RectF(area);bottom.offset(0,thickness);
        Path wall=new Path();wall.moveTo(x(area,angle),y(area,angle));wall.arcTo(area,angle,sweep,false);wall.lineTo(x(bottom,angle+sweep),y(bottom,angle+sweep));wall.arcTo(bottom,angle+sweep,-sweep,false);wall.close();
        facePaint(p,area,color,thickness,.83f);canvas.drawPath(wall,p);
    }
    private static void top(Canvas canvas,RectF area,double start,double end,int color,float lineWidth,boolean edges){
        float sweep=(float)(end-start);if(sweep<=0)return;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setShader(new LinearGradient(area.left,area.top,area.right,area.bottom,new int[]{blendWhite(color,.20f),color,shade(color,.93f)},new float[]{0,.56f,1},Shader.TileMode.CLAMP));canvas.drawArc(area,(float)start-90,sweep,true,p);
        p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setColor(0xCCFFFFFF);p.setStrokeWidth(lineWidth);
        if(edges&&sweep>1.4f)canvas.drawArc(area,(float)start-90,sweep,true,p);
        else if(!edges)canvas.drawOval(area,p);
    }
    private void rebuild(boolean clear){
        final int ticket=++generation;if(pending!=null)pending.cancel(true);if(clear)bitmap=null;invalidate();
        geometry();final int width=getWidth(),height=getHeight();if(width<=0||height<=0||slices.isEmpty())return;
        final double[] arcEnds=ends.clone();final int[] palette=colors.clone();final RectF area=new RectF(bounds);final int active=selected;
        final float thickness=depth,lineWidth=dp(.8f),blur=dp(9),lift=dp(9),offset=arcEnds.length>1?dp(8):0;
        pending=DRAWER.submit(()->{
            Bitmap ready;try{ready=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);}catch(OutOfMemoryError ex){return;}
            Canvas canvas=new Canvas(ready);shadow(canvas,area,thickness+dp(5),blur,0,360,true);
            if(active>=0&&arcEnds.length>1){
                double a=(active==0?0:arcEnds[active-1])-90,b=arcEnds[active]-90;Paint face=new Paint(Paint.ANTI_ALIAS_FLAG);
                if(Math.cos(Math.toRadians(a))>.000001){facePaint(face,area,blendWhite(palette[(active+arcEnds.length-1)%arcEnds.length],.32f),thickness,.78f);canvas.drawPath(radialFace(area,a,thickness),face);}
                if(Math.cos(Math.toRadians(b))<-.000001){facePaint(face,area,blendWhite(palette[(active+1)%arcEnds.length],.32f),thickness,.69f);canvas.drawPath(radialFace(area,b,thickness),face);}
            }
            double start=0;
            for(int i=0;i<arcEnds.length;i++){if((i&127)==0&&Thread.currentThread().isInterrupted()){ready.recycle();return;}if(i!=active)sides(canvas,area,start,arcEnds[i],active<0?palette[i]:blendWhite(palette[i],.32f),thickness,false);start=arcEnds[i];}
            start=0;
            for(int i=0;i<arcEnds.length;i++){if((i&127)==0&&Thread.currentThread().isInterrupted()){ready.recycle();return;}if(i!=active)top(canvas,area,start,arcEnds[i],active<0?palette[i]:blendWhite(palette[i],.32f),lineWidth,arcEnds.length>1);start=arcEnds[i];}
            if(active>=0&&active<arcEnds.length){
                double first=active==0?0:arcEnds[active-1],last=arcEnds[active];RectF raised=lifted(area,first,last,offset,lift);shadow(canvas,raised,thickness+dp(5),blur,first,last,false);
                Path blockers=new Path();blockers.addOval(area,Path.Direction.CW);blockers.op(topSector(area,first,last),Path.Op.DIFFERENCE);
                canvas.save();canvas.clipOutPath(blockers);sides(canvas,raised,first,last,palette[active],thickness,true);canvas.restore();
                sides(canvas,raised,first,last,palette[active],Math.min(lift,thickness),true);
                top(canvas,raised,first,last,palette[active],lineWidth*1.6f,arcEnds.length>1);
            }
            main.post(()->{if(ticket==generation){bitmap=ready;invalidate();}else ready.recycle();});
        });
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(bitmap!=null){canvas.drawBitmap(bitmap,0,0,paint);return;}
        if(total>0){paint.setColor(Ui.PALE);paint.setStyle(Paint.Style.FILL);canvas.drawOval(bounds,paint);paint.setColor(Ui.GREEN);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(13));canvas.drawText("正在绘制…",bounds.centerX(),bounds.centerY(),paint);}
    }
    private int angleIndex(double angle){int lo=0,hi=ends.length-1;while(lo<hi){int middle=(lo+hi)>>>1;if(angle<ends[middle])hi=middle;else lo=middle+1;}return lo;}
    private boolean sectorContains(int index,double angle){double start=index==0?0:ends[index-1];return angle>=start&&angle<ends[index];}
    private double angle(RectF area,float px,float py){return(Math.toDegrees(Math.atan2((py-area.centerY())/(area.height()/2),(px-area.centerX())/(area.width()/2)))+450)%360;}
    private boolean onTop(RectF area,float px,float py){double nx=(px-area.centerX())/(area.width()/2),ny=(py-area.centerY())/(area.height()/2);return nx*nx+ny*ny<=1;}
    private double wallAngle(RectF area,float px,float py,float wallDepth){
        double nx=(px-area.centerX())/(area.width()/2);if(Math.abs(nx)>1)return -1;
        double edge=area.centerY()+area.height()/2*Math.sqrt(Math.max(0,1-nx*nx));return py>=edge&&py<=edge+wallDepth?Math.toDegrees(Math.acos(nx))+90:-1;
    }
    private static boolean quad(float px,float py,float[] points){float minX=points[0],maxX=minX,minY=points[1],maxY=minY;for(int i=1;i<4;i++){minX=Math.min(minX,points[i*2]);maxX=Math.max(maxX,points[i*2]);minY=Math.min(minY,points[i*2+1]);maxY=Math.max(maxY,points[i*2+1]);}if(px<minX||px>maxX||py<minY||py>maxY)return false;boolean plus=false,minus=false;for(int i=0;i<4;i++){int j=(i+1)%4;float cross=(points[j*2]-points[i*2])*(py-points[i*2+1])-(points[j*2+1]-points[i*2+1])*(px-points[i*2]);plus|=cross>.01f;minus|=cross<-.01f;}return !(plus&&minus);}
    private boolean cutHit(RectF area,double angle,float px,float py,float wallDepth){float cx=area.centerX(),cy=area.centerY(),ex=x(area,angle),ey=y(area,angle);return quad(px,py,new float[]{cx,cy,ex,ey,ex,ey+wallDepth,cx,cy+wallDepth});}
    private boolean sideHit(RectF area,int index,float px,float py,float wallDepth){double first=index==0?0:ends[index-1],last=ends[index],wall=wallAngle(area,px,py,wallDepth);if(wall>=0&&sectorContains(index,wall))return true;if(last-first<359.999){double a=first-90,b=last-90;if(Math.cos(Math.toRadians(a))<-.000001&&cutHit(area,a,px,py,wallDepth))return true;if(Math.cos(Math.toRadians(b))>.000001&&cutHit(area,b,px,py,wallDepth))return true;}return false;}
    private int hit(float px,float py){
        if(ends.length==0||bounds.width()<=0||bounds.height()<=0)return -1;
        if(selected>=0){double first=selected==0?0:ends[selected-1],last=ends[selected];RectF raised=lifted(bounds,first,last,ends.length>1?dp(8):0,dp(9));
            if(onTop(raised,px,py)&&sectorContains(selected,angle(raised,px,py)))return selected;
            boolean blocked=onTop(bounds,px,py)&&angleIndex(angle(bounds,px,py))!=selected;
            if(sideHit(raised,selected,px,py,Math.min(dp(9),depth))||(!blocked&&sideHit(raised,selected,px,py,depth)))return selected;
        }
        if(onTop(bounds,px,py)){int index=angleIndex(angle(bounds,px,py));if(index!=selected)return index;}
        double side=wallAngle(bounds,px,py,depth);if(side>=0){int index=angleIndex(side);if(index!=selected)return index;}
        if(selected>=0&&ends.length>1){double a=(selected==0?0:ends[selected-1])-90,b=ends[selected]-90;if(Math.cos(Math.toRadians(b))<-.000001&&cutHit(bounds,b,px,py,depth))return(selected+1)%ends.length;if(Math.cos(Math.toRadians(a))>.000001&&cutHit(bounds,a,px,py,depth))return(selected+ends.length-1)%ends.length;}
        return -1;
    }
    private void choose(int index){setSelectedIndex(index);performClick();if(listener!=null)listener.onSliceSelected(index);}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();downHit=hit(downX,downY);return downHit>=0;}
        if(event.getAction()==MotionEvent.ACTION_UP){float slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();if(downHit>=0&&Math.hypot(event.getX()-downX,event.getY()-downY)<=slop){int index=hit(event.getX(),event.getY());if(index>=0)choose(index);}downHit=-1;return true;}
        if(event.getAction()==MotionEvent.ACTION_CANCEL){downHit=-1;return false;}return downHit>=0;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int code,KeyEvent event){
        if(!slices.isEmpty()&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)){int index=selected<0?(code==KeyEvent.KEYCODE_DPAD_LEFT?slices.size()-1:0):(selected+(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1)+slices.size())%slices.size();choose(index);return true;}return super.onKeyDown(code,event);
    }
    @Override protected void onDetachedFromWindow(){generation++;if(pending!=null)pending.cancel(true);bitmap=null;downHit=-1;super.onDetachedFromWindow();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(bitmap==null)rebuild(true);}
}
