package com.motonav.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Matte circular map viewport. No fabricated telemetry, heading, or SDK logo replacement. */
final class MapDial extends FrameLayout {
    final FrameLayout mapFrame;
    boolean round=true;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    MapDial(Context context){
        super(context);density=getResources().getDisplayMetrics().density;setWillNotDraw(false);
        mapFrame=new FrameLayout(context);mapFrame.setBackgroundColor(0xff1a211c);
        mapFrame.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){
            if(round)outline.setOval(0,0,v.getWidth(),v.getHeight());else outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),12*density);
        }});mapFrame.setClipToOutline(true);addView(mapFrame);
    }
    void toggleShape(){setRound(!round);}
    void setRound(boolean value){round=value;mapFrame.invalidateOutline();invalidate();}
    @Override protected void onMeasure(int width,int height){
        int w=MeasureSpec.getSize(width),h=MeasureSpec.getSize(height);
        int size=Math.max(0,Math.min(w,h)-(int)(24*density));
        mapFrame.measure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY));setMeasuredDimension(w,h);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int size=mapFrame.getMeasuredWidth(),x=(getWidth()-size)/2,y=(getHeight()-size)/2;mapFrame.layout(x,y,x+size,y+size);mapFrame.invalidateOutline();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,radius=mapFrame.getMeasuredWidth()/2f+5*density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(8*density);paint.setColor(0xff282d28);
        if(round)canvas.drawCircle(cx,cy,radius,paint);else canvas.drawRoundRect(cx-radius,cy-radius,cx+radius,cy+radius,16*density,16*density,paint);
        paint.setStrokeWidth(density);paint.setColor(0xff65715b);
        if(round)canvas.drawCircle(cx,cy,radius+4*density,paint);else canvas.drawRoundRect(cx-radius-4*density,cy-radius-4*density,cx+radius+4*density,cy+radius+4*density,20*density,20*density,paint);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        // Let the SDK handle pan/zoom rather than letting the surrounding page steal the gesture.
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            float x=e.getX()-getWidth()/2f,y=e.getY()-getHeight()/2f,r=mapFrame.getMeasuredWidth()/2f;
            if(round&&x*x+y*y>r*r)return false;
            if(!round&&(Math.abs(x)>r||Math.abs(y)>r))return false;
        }
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_CANCEL);
        return super.dispatchTouchEvent(e);
    }
}
