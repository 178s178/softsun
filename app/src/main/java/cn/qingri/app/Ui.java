package cn.qingri.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

public final class Ui {
    public static final int BG=Color.rgb(246,247,242), WHITE=Color.WHITE, INK=Color.rgb(34,48,42),
        MUTED=Color.rgb(117,129,121), GREEN=Color.rgb(40,113,91), PALE=Color.rgb(228,239,224), LINE=Color.rgb(231,235,228);
    public static int dp(Context c,float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    public static GradientDrawable shape(int color,float radius,Context c) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d; }
    public static GradientDrawable outline(int color,int border,float radius,Context c) { GradientDrawable d=shape(color,radius,c);d.setStroke(dp(c,1),border);return d; }
    public static TextView text(Context c,String value,float size,int color) { TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setIncludeFontPadding(false);return t; }
    public static TextView bold(Context c,String value,float size,int color) { TextView t=text(c,value,size,color);t.setTypeface(null,Typeface.BOLD);return t; }
    public static TextView centered(TextView view) {view.setGravity(Gravity.CENTER);return view;}
    public static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l; }
    public static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    public static void pad(View v,int a) { v.setPadding(dp(v.getContext(),a),dp(v.getContext(),a),dp(v.getContext(),a),dp(v.getContext(),a)); }
    public static LinearLayout.LayoutParams lp(Context c,int w,int h) { return new LinearLayout.LayoutParams(w<0?w:dp(c,w),h<0?h:dp(c,h)); }
    public static void gap(LinearLayout parent,int n) { View v=new View(parent.getContext());parent.addView(v,lp(parent.getContext(),1,n)); }
    public static void weight(LinearLayout parent,View view) { parent.addView(view,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1)); }
    public static TextView button(Context c,String label,boolean primary,Runnable action) {
        TextView v=bold(c,label,15,primary?WHITE:GREEN);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(c,48));
        v.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));v.setBackground(shape(primary?GREEN:PALE,14,c));v.setOnClickListener(w->action.run());return v;
    }
    public static View iconButton(Context c,String type,String description,Runnable action) {
        Icon v=new Icon(c,type,GREEN);v.setContentDescription(description);v.setBackground(shape(WHITE,15,c));v.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));v.setOnClickListener(w->action.run());return v;
    }
    public static int category(String name) {
        if ("学习".equals(name)) return Color.rgb(72,115,166);
        if ("工作".equals(name)) return Color.rgb(171,123,53);
        if ("其他".equals(name)) return Color.rgb(140,105,161);
        return GREEN;
    }
    public static void window(Activity activity,View root) {
        activity.getWindow().setStatusBarColor(BG);activity.getWindow().setNavigationBarColor(WHITE);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT>=35) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,i.bottom);return insets;
            });
        }
    }
    public static void toast(Context c,String msg) { Toast.makeText(c,msg,Toast.LENGTH_SHORT).show(); }
    public static class Icon extends View {
        String kind; int color;
        public Icon(Context c,String kind,int color) { super(c);this.kind=kind;this.color=color;setMinimumWidth(dp(c,24));setMinimumHeight(dp(c,24)); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w=getWidth()-getPaddingLeft()-getPaddingRight(),h=getHeight()-getPaddingTop()-getPaddingBottom();
            canvas.save();canvas.translate(getPaddingLeft(),getPaddingTop());canvas.scale(w/24,h/24);
            Paint p=new Paint(3);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();
            switch(kind) {
                case "plus": canvas.drawLine(12,5,12,19,p);canvas.drawLine(5,12,19,12,p);break;
                case "search":canvas.drawCircle(10.3f,10.3f,6.2f,p);canvas.drawLine(15,15,20,20,p);break;
                case "back":case "prev":path.moveTo(15,5);path.lineTo(8,12);path.lineTo(15,19);canvas.drawPath(path,p);break;
                case "next":path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);canvas.drawPath(path,p);break;
                case "check":canvas.drawCircle(12,12,9,p);path.moveTo(7,12);path.lineTo(11,16);path.lineTo(17,9);canvas.drawPath(path,p);break;
                case "empty":canvas.drawRoundRect(5,5,19,21,3,3,p);canvas.drawLine(9,3,9,7,p);canvas.drawLine(15,3,15,7,p);path.moveTo(8,14);path.lineTo(11,17);path.lineTo(17,11);canvas.drawPath(path,p);break;
                case "calendar":canvas.drawRoundRect(3,5,21,21,3,3,p);canvas.drawLine(3,10,21,10,p);canvas.drawLine(8,3,8,7,p);canvas.drawLine(16,3,16,7,p);canvas.drawPoint(8,15,p);canvas.drawPoint(13,15,p);canvas.drawPoint(17,15,p);break;
                case "settings":canvas.drawCircle(12,12,4,p);canvas.drawCircle(12,12,9,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*11,12+(float)Math.sin(a)*11,p);}break;
                case "bell":path.moveTo(5,17);path.lineTo(7,14);path.lineTo(7,9);path.cubicTo(7,2,17,2,17,9);path.lineTo(17,14);path.lineTo(19,17);path.close();canvas.drawPath(path,p);canvas.drawArc(10,18,14,22,0,180,false,p);break;
                case "circle":canvas.drawCircle(12,12,9,p);break;
                case "pie":canvas.drawCircle(12,12,9,p);canvas.drawLine(12,3,12,12,p);canvas.drawLine(12,12,19,18,p);break;
                case "leaf":path.moveTo(4,20);path.cubicTo(1,9,9,3,21,3);path.cubicTo(22,16,13,22,4,20);canvas.drawPath(path,p);canvas.drawLine(4,20,16,8,p);canvas.drawLine(10,14,9,9,p);break;
                default:canvas.drawRoundRect(4,4,20,20,4,4,p);canvas.drawLine(8,9,16,9,p);canvas.drawLine(8,15,14,15,p);
            }
            canvas.restore();
        }
    }
    public static class Ring extends View {
        int done,total;
        public Ring(Context c,int done,int total) { super(c);this.done=done;this.total=total;setContentDescription("已完成 "+done+"，共 "+total+" 项"); }
        @Override protected void onDraw(Canvas canvas) {
            float w=getWidth(),h=getHeight(),r=Math.min(w,h)/2-dp(getContext(),6); Paint p=new Paint(3);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),5));p.setColor(0x446EA28E);canvas.drawCircle(w/2,h/2,r,p);
            p.setColor(0xFFD1E6AC);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(w/2-r,h/2-r,w/2+r,h/2+r,-90,total==0?0:360f*done/total,false,p);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(dp(getContext(),23));canvas.drawText((total==0?0:100*done/total)+"%",w/2,h/2+dp(getContext(),3),p);p.setTextSize(dp(getContext(),10));p.setColor(0xFFD5E5DC);canvas.drawText("完成进度",w/2,h/2+dp(getContext(),21),p);
        }
    }
}
