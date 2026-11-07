package cn.qingri.app;

import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;

public class NatureView extends View {
    final Paint paint=new Paint(3);
    public NatureView(Context c){super(c);setContentDescription("柔和的山林、阳光和树木");}
    void fill(Canvas c,int color,Path path){paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawPath(path,paint);}
    void tree(Canvas c,float x,float y,float height,int color,float sway){
        c.save();c.rotate(sway,x,y);paint.setShader(null);paint.setColor(0xFF6A7D5B);c.drawRoundRect(x-2,y-height*.15f,x+2,y+4,2,2,paint);
        Path tree=new Path();tree.moveTo(x,y-height);tree.lineTo(x-height*.25f,y-height*.43f);tree.lineTo(x-height*.14f,y-height*.43f);tree.lineTo(x-height*.34f,y-height*.13f);tree.quadTo(x,y+height*.02f,x+height*.34f,y-height*.13f);tree.lineTo(x+height*.14f,y-height*.43f);tree.lineTo(x+height*.25f,y-height*.43f);tree.close();fill(c,color,tree);c.restore();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/360f,getHeight()/205f);Path clip=new Path();clip.addRoundRect(new RectF(0,0,360,205),24,24,Path.Direction.CW);canvas.clipPath(clip);
        paint.setShader(new LinearGradient(0,0,0,205,0xFFEBF3E5,0xFFDCE9D4,Shader.TileMode.CLAMP));canvas.drawRect(0,0,360,205,paint);paint.setShader(null);
        paint.setColor(0x55FFF9D6);canvas.drawCircle(270,53,36,paint);paint.setColor(0xFFFFF6D4);canvas.drawCircle(270,53,23,paint);
        Path p=new Path();p.moveTo(0,132);p.cubicTo(65,62,105,75,170,115);p.cubicTo(229,65,297,78,360,122);p.lineTo(360,205);p.lineTo(0,205);p.close();fill(canvas,0xFFBBCFB6,p);
        p=new Path();p.moveTo(0,152);p.cubicTo(70,100,145,140,191,148);p.cubicTo(262,101,311,112,360,151);p.lineTo(360,205);p.lineTo(0,205);p.close();fill(canvas,0xFF92B397,p);
        boolean motion=ValueAnimator.areAnimatorsEnabled()&&!((PowerManager)getContext().getSystemService(Context.POWER_SERVICE)).isPowerSaveMode();float wave=motion?(float)Math.sin(SystemClock.uptimeMillis()/2900.0):0;
        tree(canvas,37,163,73,0xFF5E8E73,wave*.5f);tree(canvas,79,163,53,0xFF54876E,-wave*.6f);tree(canvas,315,165,67,0xFF5F8F72,wave*.5f);
        p=new Path();p.moveTo(0,169);p.cubicTo(95,142,154,182,231,164);p.quadTo(299,144,360,176);p.lineTo(360,205);p.lineTo(0,205);p.close();fill(canvas,0xFF719C7B,p);
        p=new Path();p.moveTo(217,155);p.cubicTo(182,174,247,183,178,205);p.lineTo(254,205);p.cubicTo(293,180,207,170,236,155);p.close();fill(canvas,0xFFCCDDC3,p);
        tree(canvas,25,210,87,0xFF356D59,-wave*.5f);tree(canvas,333,212,87,0xFF39715A,wave*.5f);tree(canvas,287,205,54,0xFF42775B,-wave*.4f);
        paint.setColor(0xFFC7DDAB);canvas.drawOval(103,172+wave,110,175+wave,paint);canvas.drawOval(128,190-wave,136,193-wave,paint);canvas.drawOval(260,182+wave,267,185+wave,paint);
        canvas.restore();if(motion&&isShown())postInvalidateDelayed(90);
    }
}
