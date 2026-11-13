package com.motonav.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** One search surface keeps input, progress, errors and results together. Search is explicit. */
final class DestinationSearchDialog {
    private final AlertDialog dialog;
    private final EditText input;
    private final Button search;
    private final TextView status;
    private final ProgressBar progress;
    private final LinearLayout results;
    private final Consumer<String> submit;
    private boolean busy;
    DestinationSearchDialog(Activity activity,String initial,Consumer<String> submit,Runnable dismissed) {
        this.submit=submit;
        LinearLayout page=AppUi.column(activity);int p=AppUi.dp(activity,20);page.setPadding(p,p,p,p);
        TextView title=AppUi.text(activity,"想去哪里？",26,AppUi.TEXT);title.setTypeface(null,android.graphics.Typeface.BOLD);page.addView(title);
        AppUi.add(page,AppUi.text(activity,"输入城市和地点，核对地址后选择目的地。",14,AppUi.MUTED),8);
        input=new EditText(activity);AppUi.field(input,"例如：杭州 西湖文化广场");input.setSingleLine(true);input.setSaveEnabled(false);input.setSaveFromParentEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);input.setText(initial);input.setSelection(input.length());input.setTag("destination_query");AppUi.add(page,input,18);
        search=AppUi.button(activity,"搜索地点",true,this::search);search.setTag("destination_search");AppUi.add(page,search,10);
        input.setOnEditorActionListener((view,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){search();return true;}return false;});
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(View.GONE);AppUi.add(page,progress,10);
        status=AppUi.text(activity,"仅点击搜索时向高德提交搜索词。",13,AppUi.MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);AppUi.add(page,status,12);
        results=AppUi.column(activity);AppUi.add(page,results,4);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(page);scroll.setBackgroundColor(AppUi.BG);
        dialog=new AlertDialog.Builder(activity).setView(scroll).setNegativeButton("返回地图",(d,w)->{}).create();
        dialog.setOnDismissListener(d->dismissed.run());
    }
    void show() {
        dialog.show();
        if(dialog.getWindow()!=null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
    }
    private void search() {
        if(busy)return;
        String query=input.getText().toString().trim();
        if(query.length()<2||query.length()>80){input.setError("请输入 2–80 字的城市或地点名称");return;}
        input.setError(null);
        InputMethodManager keyboard=(InputMethodManager)input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);
        submit.accept(query);
    }
    void searching() {
        busy=true;results.removeAllViews();input.setEnabled(false);search.setEnabled(false);search.setAlpha(.6f);
        progress.setVisibility(View.VISIBLE);status.setTextColor(AppUi.MUTED);status.setText("正在查找地点…");
    }
    private void finished() {
        busy=false;input.setEnabled(true);search.setEnabled(true);search.setAlpha(1);progress.setVisibility(View.GONE);
    }
    void failure(String message) { finished();results.removeAllViews();status.setTextColor(AppUi.AMBER);status.setText(message);search.setText("重新搜索"); }
    void results(String[] labels,IntConsumer selected) {
        finished();results.removeAllViews();status.setTextColor(AppUi.MUTED);status.setText("找到 "+labels.length+" 个地点 · 请核对地址");search.setText("搜索地点");
        for(int i=0;i<labels.length;i++) {
            final int choice=i;Context context=input.getContext();LinearLayout row=AppUi.card(context);
            row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x337f94a5),AppUi.surface(context,AppUi.RAISED,16),null));
            row.setMinimumHeight(AppUi.dp(context,56));row.setFocusable(true);row.setContentDescription(labels[i]);
            String[] parts=labels[i].split("\n",2);TextView title=AppUi.text(context,parts[0],16,AppUi.TEXT);title.setTypeface(null,android.graphics.Typeface.BOLD);row.addView(title);
            if(parts.length>1)AppUi.add(row,AppUi.text(context,parts[1],13,AppUi.MUTED),4);
            row.setOnClickListener(view->selected.accept(choice));row.setTag("destination_result");AppUi.add(results,row,8);
        }
    }
    void dismiss() { dialog.dismiss(); }
}
