package com.motonav.companion;

import java.util.Locale;

/** Presentation only. A waiting/expired frame never exposes old navigation metrics. */
record NavigationUiModel(String destination,String title,String detail,String turnDistance,
        String speed,String remaining,String duration,boolean live,boolean busy,boolean navigating) {
    static NavigationUiModel create(JourneyState.Phase phase,NavigationFrame frame,String destination,String road,String message) {
        String target=clean(destination);
        boolean navigating=phase==JourneyState.Phase.NAVIGATING||phase==JourneyState.Phase.STARTING;
        boolean busy=phase!=JourneyState.Phase.IDLE&&phase!=JourneyState.Phase.NAVIGATING;
        boolean live=phase==JourneyState.Phase.NAVIGATING&&frame.mode()==NavigationFrame.LIVE;
        String title=switch(phase) {
            case IDLE -> target.isEmpty()?"下一站，去哪里？":"准备出发";
            case LOCATING -> "正在获取起点";
            case PLANNING, READY -> "正在准备路线";
            case STARTING -> "正在启动导航";
            case NAVIGATING -> live?JourneyState.maneuver(frame.icon()):"等待导航恢复";
        };
        if(frame.mode()==NavigationFrame.ARRIVED)title="已到达目的地";
        String detail=live?(clean(road).isEmpty()?"沿当前路线继续行驶":clean(road)):clean(message);
        if(detail.isEmpty())detail="自动定位当前位置，选择目的地后开始导航。";
        return new NavigationUiModel(target,title,detail,live?distance(frame.turnMeters()):"",
                live&&frame.speedKmh()>=0?Integer.toString(frame.speedKmh()):"—",
                live?distance(frame.remainingMeters()):"—",live?duration(frame.remainingSeconds()):"—",live,busy,navigating);
    }
    static String distance(int metres) {
        if(metres<0)return "—";
        if(metres<1000)return metres+" m";
        return metres<100000?String.format(Locale.ROOT,"%.1f km",metres/1000.0):String.format(Locale.ROOT,"%.0f km",metres/1000.0);
    }
    static String duration(int seconds) {
        if(seconds<0)return "—";
        long minutes=((long)seconds+59)/60;
        if(minutes<60)return minutes+" 分钟";
        return minutes/60+" 小时"+(minutes%60==0?"":" "+minutes%60+" 分钟");
    }
    private static String clean(String value) { return value==null?"":value.trim(); }
}
