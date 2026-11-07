package cn.qingri.app;

import android.content.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.util.*;

public class MonthGrid extends LinearLayout {
    public interface OnDate { void select(LocalDate day); }
    public MonthGrid(Context c,YearMonth month,LocalDate selected,Set<LocalDate> occupied,OnDate listener) {
        super(c);setOrientation(VERTICAL);setBackground(Ui.shape(Ui.WHITE,20,c));Ui.pad(this,10);
        LinearLayout weekdays=Ui.row(c);
        for(String label:new String[]{"一","二","三","四","五","六","日"}) {
            TextView t=Ui.text(c,label,12,Ui.MUTED);t.setGravity(Gravity.CENTER);weekdays.addView(t,new LinearLayout.LayoutParams(0,Ui.dp(c,32),1));
        }
        addView(weekdays);
        int offset=month.atDay(1).getDayOfWeek().getValue()-1;
        int rows=(offset+month.lengthOfMonth()+6)/7;
        for(int row=0;row<rows;row++) {
            LinearLayout line=Ui.row(c);
            for(int col=0;col<7;col++) {
                int number=row*7+col-offset+1;
                LinearLayout cell=Ui.column(c);cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(c,50),1);cp.setMargins(Ui.dp(c,2),Ui.dp(c,2),Ui.dp(c,2),Ui.dp(c,2));line.addView(cell,cp);
                if(number<1||number>month.lengthOfMonth()) continue;
                LocalDate date=month.atDay(number);boolean active=date.equals(selected),today=date.equals(LocalDate.now());
                if(active)cell.setBackground(Ui.shape(Ui.GREEN,13,c));else if(today)cell.setBackground(Ui.outline(Ui.WHITE,Ui.GREEN,13,c));
                TextView t=Ui.centered(Ui.bold(c,String.valueOf(number),16,active?Ui.WHITE:today?Ui.GREEN:Ui.INK));cell.addView(t);
                View dot=new View(c);dot.setBackground(Ui.shape(active?0xFFD1E6AC:Ui.GREEN,3,c));dot.setVisibility(occupied.contains(date)?View.VISIBLE:View.INVISIBLE);
                LinearLayout.LayoutParams dp=Ui.lp(c,4,4);dp.topMargin=Ui.dp(c,5);cell.addView(dot,dp);
                cell.setContentDescription(date.toString()+(occupied.contains(date)?"，有日程":"，无日程"));cell.setOnClickListener(v->listener.select(date));
            }
            addView(line);
        }
    }
}
