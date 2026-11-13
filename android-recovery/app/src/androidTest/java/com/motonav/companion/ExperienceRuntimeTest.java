package com.motonav.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native layouts and inputs on a disposable AVD; synthetic navigation never enters the service. */
@RunWith(AndroidJUnit4.class)
public class ExperienceRuntimeTest {
    @Before public void emulatorOnly() {
        assertTrue(android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));
    }
    private static void idle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static Object field(Object target,String name) {
        try {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private static void visibleAction(Activity activity) {
        View root=activity.getWindow().getDecorView(),button=root.findViewWithTag("navigation_primary");
        assertNotNull(button);Rect bounds=new Rect();assertTrue(button.getGlobalVisibleRect(bounds));
        assertEquals("Primary action must not be clipped",button.getHeight(),bounds.height());
        assertTrue("Touch target is at least 48dp",bounds.height()>=AppUi.dp(activity,48));
        assertTrue("Primary action is enabled before configuration",button.isEnabled());
    }
    private static ScrollView scroll(View view) {
        if(view instanceof ScrollView)return (ScrollView)view;
        if(view instanceof ViewGroup parent)for(int i=0;i<parent.getChildCount();i++){ScrollView child=scroll(parent.getChildAt(i));if(child!=null)return child;}
        return null;
    }
    private static String suffix(Activity activity) {
        android.content.res.Configuration c=activity.getResources().getConfiguration();return c.screenWidthDp+"x"+c.screenHeightDp+"-font"+c.fontScale;
    }
    private static void capture(View view,String name) {
        assertTrue(view.getWidth()>0&&view.getHeight()>0);
        Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));
        try(FileOutputStream output=new FileOutputStream(NavigationRuntimeTest.evidence(name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
        catch(Exception e){throw new AssertionError(e);}finally{image.recycle();}
    }
    @Test public void primaryActionSurvivesScrollSettingsReopenAndRecreation() {
        try(ActivityScenario<AmapActivity> scenario=ActivityScenario.launch(AmapActivity.class)) {
            idle();scenario.onActivity(activity->{visibleAction(activity);capture(activity.getWindow().getDecorView(),"home-"+suffix(activity)+".png");
                scroll(activity.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN);});
            idle();scenario.onActivity(activity->{visibleAction(activity);activity.getWindow().getDecorView().findViewWithTag("map_settings").performClick();});
            idle();scenario.onActivity(activity->{AlertDialog dialog=(AlertDialog)field(activity,"settingsDialog");assertTrue(dialog.isShowing());
                capture(dialog.getWindow().getDecorView(),"settings-"+suffix(activity)+".png");dialog.dismiss();});
            idle();scenario.onActivity(activity->{assertNull(field(activity,"settingsDialog"));
                activity.getWindow().getDecorView().findViewWithTag("map_settings").performClick();assertTrue(((AlertDialog)field(activity,"settingsDialog")).isShowing());
                ((AlertDialog)field(activity,"settingsDialog")).dismiss();});
            scenario.recreate();idle();scenario.onActivity(ExperienceRuntimeTest::visibleAction);
        }
    }
    @Test public void landscapeKeepsPrimaryActionAvailable() {
        try(ActivityScenario<AmapActivity> scenario=ActivityScenario.launch(AmapActivity.class)) {
            scenario.onActivity(activity->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            for(int i=0;i<40;i++) {
                idle();AtomicInteger orientation=new AtomicInteger();scenario.onActivity(a->orientation.set(a.getResources().getConfiguration().orientation));
                if(orientation.get()==android.content.res.Configuration.ORIENTATION_LANDSCAPE)break;
                android.os.SystemClock.sleep(50);
            }
            idle();scenario.onActivity(activity->{assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,activity.getResources().getConfiguration().orientation);
                visibleAction(activity);capture(activity.getWindow().getDecorView(),"home-landscape-"+suffix(activity)+".png");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        }
    }
    @Test public void searchKeepsInputAndSupportsInlineRetryWithoutNetworking() {
        AtomicReference<DestinationSearchDialog> sheet=new AtomicReference<>();AtomicInteger requests=new AtomicInteger(),choice=new AtomicInteger(-1),dismissed=new AtomicInteger();
        try(ActivityScenario<AmapActivity> scenario=ActivityScenario.launch(AmapActivity.class)) {
            scenario.onActivity(activity->{sheet.set(new DestinationSearchDialog(activity,"杭",q->{requests.incrementAndGet();sheet.get().searching();},dismissed::incrementAndGet));sheet.get().show();});
            idle();scenario.onActivity(activity->{AlertDialog dialog=(AlertDialog)field(sheet.get(),"dialog");View root=dialog.getWindow().getDecorView();
                EditText input=root.findViewWithTag("destination_query");root.findViewWithTag("destination_search").performClick();
                assertNotNull(input.getError());assertEquals(0,requests.get());assertTrue(dialog.isShowing());
                input.setText("杭州 西湖文化广场");root.findViewWithTag("destination_search").performClick();assertEquals(1,requests.get());
                assertFalse(root.findViewWithTag("destination_search").isEnabled());sheet.get().failure("网络暂时不可用，请重试。");
                assertEquals("杭州 西湖文化广场",input.getText().toString());assertTrue(root.findViewWithTag("destination_search").isEnabled());
                sheet.get().results(new String[]{"西湖文化广场 · 测试地点\n杭州市拱墅区 · 仅用于界面验证","同名地点 · 测试\n请核对城市及地址"},choice::set);
            });
            idle();scenario.onActivity(activity->{AlertDialog dialog=(AlertDialog)field(sheet.get(),"dialog");capture(dialog.getWindow().getDecorView(),"search-fixture-"+suffix(activity)+".png");
                dialog.getWindow().getDecorView().findViewWithTag("destination_result").performClick();assertEquals(0,choice.get());sheet.get().dismiss();assertFalse(NavigationService.navigating);});
            idle();assertEquals("Dismiss listener runs on the following main-loop turn",1,dismissed.get());
        }
    }
    @Test public void navigationCardHidesExpiredMetricsAndFullMapUsesAvailableWidth() {
        try(ActivityScenario<AmapActivity> scenario=ActivityScenario.launch(AmapActivity.class)) {
            scenario.onActivity(activity->{
                NavigationCard card=new NavigationCard(activity);long now=android.os.SystemClock.elapsedRealtime();
                NavigationFrame fresh=new NavigationFrame(NavigationFrame.LIVE,3,120,4800,660,36,now,now);
                card.render(NavigationUiModel.create(JourneyState.Phase.NAVIGATING,fresh,"测试目的地","测试道路 · 前方右转",""));
                int width=AppUi.dp(activity,320);card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                card.layout(0,0,card.getMeasuredWidth(),card.getMeasuredHeight());
                assertEquals("Short duration stays on one line at supported font sizes",1,((TextView)field(card,"duration")).getLayout().getLineCount());
                capture(card,"guidance-fixture-"+suffix(activity)+".png");
                assertEquals("36",((TextView)field(card,"speed")).getText().toString());
                card.render(NavigationUiModel.create(JourneyState.Phase.NAVIGATING,fresh.at(now+16000),"测试目的地","旧道路","正在重新获取定位"));
                assertEquals(View.GONE,((View)field(card,"metrics")).getVisibility());assertEquals(View.GONE,((View)field(card,"icon")).getVisibility());
                assertEquals("—",((TextView)field(card,"speed")).getText().toString());
                MapDial map=new MapDial(activity);map.setRound(false);map.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(AppUi.dp(activity,220),View.MeasureSpec.EXACTLY));
                assertTrue(map.mapFrame.getMeasuredWidth()>map.mapFrame.getMeasuredHeight());map.setRound(true);
                map.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(AppUi.dp(activity,220),View.MeasureSpec.EXACTLY));
                assertEquals(map.mapFrame.getMeasuredWidth(),map.mapFrame.getMeasuredHeight());
            });
        }
    }
    @Test public void updateAndDiagnosticsRenderWithoutImplicitNetworkActions() {
        try(ActivityScenario<UpdateActivity> scenario=ActivityScenario.launch(UpdateActivity.class)) {
            idle();scenario.onActivity(activity->capture(activity.getWindow().getDecorView(),"updates-"+suffix(activity)+".png"));
        }
        try(ActivityScenario<DiagnosticsActivity> scenario=ActivityScenario.launch(DiagnosticsActivity.class)) {
            idle();scenario.onActivity(activity->{assertFalse(Diagnostics.active());capture(activity.getWindow().getDecorView(),"diagnostics-"+suffix(activity)+".png");});
        }
    }
}
