package com.motonav.companion;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small shared native UI vocabulary; no map, permission or network ownership. */
final class AppUi {
    static final int BG=0xff0c1116, SURFACE=0xff151e26, RAISED=0xff202d37;
    static final int TEXT=0xfff0f4f7, MUTED=0xffa9bac6, ACCENT=0xffc4f879, PURPLE=0xffba8cff;
    static final int AMBER=0xffffd08a, DANGER=0xffffb4ad, BORDER=0xff33434f;
    private AppUi() {}
    static int dp(Context context,float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    static GradientDrawable surface(Context context,int color,int radius) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context,radius)); return shape;
    }
    static TextView text(Context context,String value,int size,int color) {
        TextView view=new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("tnum"); view.setLineSpacing(dp(context,2),1f); return view;
    }
    static void setText(TextView view,CharSequence value) {
        if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);
    }
    static Button button(Context context,String label,boolean primary,Runnable action) {
        Button button=new Button(context); button.setText(label); button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(null,Typeface.BOLD); button.setMinHeight(dp(context,52)); button.setMinimumHeight(dp(context,52));
        button.setPadding(dp(context,16),dp(context,10),dp(context,16),dp(context,10));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{MUTED,primary?BG:TEXT}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x337f94a5),
                surface(context,primary?ACCENT:RAISED,16),null));
        button.setOnClickListener(view->action.run()); return button;
    }
    static LinearLayout column(Context context) {
        LinearLayout layout=new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    static LinearLayout card(Context context) {
        LinearLayout card=column(context); int p=dp(context,16);card.setPadding(p,p,p,p);card.setBackground(surface(context,SURFACE,20));return card;
    }
    static void add(LinearLayout parent,View child,int top) {
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(parent.getContext(),top);parent.addView(child,params);
    }
    static LinearLayout page(Activity activity,String title,String description) {
        LinearLayout page=column(activity);int p=dp(activity,20);page.setPadding(p,p,p,p);
        Button back=button(activity,"‹  返回",false,activity::finish);
        LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(-2,-2);page.addView(back,backParams);
        TextView heading=text(activity,title,28,TEXT);heading.setTypeface(null,Typeface.BOLD);if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);add(page,heading,24);
        if(!description.isEmpty())add(page,text(activity,description,14,MUTED),8);
        return page;
    }
    static ScrollView scroll(Activity activity,LinearLayout page) {
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        scroll.addView(page);insets(activity,scroll);return scroll;
    }
    static void insets(Activity activity,View root) {
        root.setBackgroundColor(BG);activity.getWindow().setStatusBarColor(BG);activity.getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;
            });
            root.requestApplyInsets();
        } else root.setFitsSystemWindows(true);
    }
    static void field(EditText field,String hint) {
        Context context=field.getContext();field.setHint(hint);field.setTextSize(16);field.setTextColor(TEXT);field.setHintTextColor(MUTED);
        field.setPadding(dp(context,14),dp(context,14),dp(context,14),dp(context,14));field.setMinHeight(dp(context,54));
        GradientDrawable shape=surface(context,BG,14);shape.setStroke(dp(context,1),BORDER);field.setBackground(shape);
    }
}
