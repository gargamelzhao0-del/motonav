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
        mapFrame=new FrameLayout(context);mapFrame.setBackgroundColor(AppUi.SURFACE);
        mapFrame.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){
            if(round)outline.setOval(0,0,v.getWidth(),v.getHeight());else outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),12*density);
        }});mapFrame.setClipToOutline(true);addView(mapFrame);
    }
    void toggleShape(){setRound(!round);}
    void setRound(boolean value){round=value;mapFrame.invalidateOutline();requestLayout();invalidate();}
    @Override protected void onMeasure(int width,int height){
        int w=MeasureSpec.getSize(width),h=MeasureSpec.getSize(height);
        int size=Math.max(0,Math.min(w,h)-(int)(24*density));
        int fw=round?size:Math.max(0,w-(int)(16*density)),fh=round?size:Math.max(0,h-(int)(16*density));
        mapFrame.measure(MeasureSpec.makeMeasureSpec(fw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fh,MeasureSpec.EXACTLY));setMeasuredDimension(w,h);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int w=mapFrame.getMeasuredWidth(),h=mapFrame.getMeasuredHeight(),x=(getWidth()-w)/2,y=(getHeight()-h)/2;mapFrame.layout(x,y,x+w,y+h);mapFrame.invalidateOutline();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,radius=mapFrame.getMeasuredWidth()/2f+5*density;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(8*density);paint.setColor(AppUi.RAISED);
        if(round)canvas.drawCircle(cx,cy,radius,paint);else {paint.setStrokeWidth(density);paint.setColor(AppUi.BORDER);canvas.drawRoundRect(mapFrame.getLeft()-density,mapFrame.getTop()-density,mapFrame.getRight()+density,mapFrame.getBottom()+density,13*density,13*density,paint);return;}
        paint.setStrokeWidth(density);paint.setColor(AppUi.BORDER);
        if(round)canvas.drawCircle(cx,cy,radius+4*density,paint);else canvas.drawRoundRect(cx-radius-4*density,cy-radius-4*density,cx+radius+4*density,cy+radius+4*density,20*density,20*density,paint);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        // Let the SDK handle pan/zoom rather than letting the surrounding page steal the gesture.
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            float x=e.getX()-getWidth()/2f,y=e.getY()-getHeight()/2f,r=mapFrame.getMeasuredWidth()/2f;
            if(round&&x*x+y*y>r*r)return false;
            if(!round&&(e.getX()<mapFrame.getLeft()||e.getX()>mapFrame.getRight()||e.getY()<mapFrame.getTop()||e.getY()>mapFrame.getBottom()))return false;
        }
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_CANCEL);
        return super.dispatchTouchEvent(e);
    }
}
