package com.motonav.companion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** The same native component is exercised by Android screenshot/layout tests. */
final class NavigationCard extends LinearLayout {
    private final TextView title,detail,distance,speed,remaining,duration,destination;
    private final LinearLayout metrics;
    private final ImageView icon;
    private final ProgressBar progress;
    NavigationCard(Context context) {
        super(context);setOrientation(VERTICAL);int p=AppUi.dp(context,18);setPadding(p,p,p,p);
        setBackground(AppUi.surface(context,AppUi.SURFACE,22));
        destination=AppUi.text(context,"",12,AppUi.MUTED);destination.setPadding(0,0,0,AppUi.dp(context,10));addView(destination);
        LinearLayout top=new LinearLayout(context);top.setGravity(Gravity.CENTER_VERTICAL);
        icon=new ImageView(context);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(icon,new LinearLayout.LayoutParams(AppUi.dp(context,56),AppUi.dp(context,56)));
        LinearLayout labels=AppUi.column(context);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=AppUi.dp(context,10);top.addView(labels,lp);
        title=AppUi.text(context,"下一站，去哪里？",20,AppUi.TEXT);title.setTypeface(null,Typeface.BOLD);labels.addView(title);
        distance=AppUi.text(context,"",38,AppUi.ACCENT);distance.setTypeface(null,Typeface.BOLD);labels.addView(distance);
        progress=new ProgressBar(context);top.addView(progress,new LinearLayout.LayoutParams(AppUi.dp(context,28),AppUi.dp(context,28)));addView(top);
        detail=AppUi.text(context,"自动定位当前位置，选择目的地后开始导航。",14,AppUi.MUTED);AppUi.add(this,detail,10);
        metrics=new LinearLayout(context);metrics.setBaselineAligned(false);
        speed=metric(metrics,"车速 km/h");remaining=metric(metrics,"剩余距离");duration=metric(metrics,"预计用时");AppUi.add(this,metrics,18);
        render(NavigationUiModel.create(JourneyState.Phase.IDLE,NavigationFrame.status(NavigationFrame.STOPPED),"","",""));
    }
    private TextView metric(LinearLayout parent,String label) {
        LinearLayout group=AppUi.column(getContext());group.setPadding(AppUi.dp(getContext(),4),0,AppUi.dp(getContext(),4),0);
        TextView value=AppUi.text(getContext(),"—",20,AppUi.TEXT);value.setTypeface(null,Typeface.BOLD);group.addView(value);
        group.addView(AppUi.text(getContext(),label,12,AppUi.MUTED));parent.addView(group,new LinearLayout.LayoutParams(0,-2,1));return value;
    }
    void icon(Bitmap bitmap) { icon.setImageBitmap(bitmap); }
    void render(NavigationUiModel state) {
        AppUi.setText(destination,"前往 "+state.destination());destination.setVisibility((state.navigating()||state.busy())&&!state.destination().isEmpty()?VISIBLE:GONE);
        AppUi.setText(title,state.title());AppUi.setText(detail,state.detail());AppUi.setText(distance,state.turnDistance());
        distance.setVisibility(state.live()?VISIBLE:GONE);icon.setVisibility(state.live()&&icon.getDrawable()!=null?VISIBLE:GONE);
        title.setTextColor(state.navigating()&&!state.live()?AppUi.AMBER:AppUi.TEXT);
        progress.setVisibility(state.busy()?VISIBLE:GONE);
        AppUi.setText(speed,state.speed());AppUi.setText(remaining,state.remaining());AppUi.setText(duration,state.duration());
        metrics.setVisibility(state.live()?VISIBLE:GONE);
    }
}
