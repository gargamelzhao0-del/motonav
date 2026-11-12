package com.motonav.companion;
/** Pure decisions; never confuse remembered intent with current OS permission. */
public final class MapStartupPolicy {
    public enum Action { WAIT, LOCATE, REQUEST, SHOW_SETTINGS }
    public static Action location(boolean foreground,boolean mapReady,boolean consent,boolean pending,
        boolean fine,boolean coarse,boolean asked,boolean retry,boolean rationale){
        if(!foreground||!mapReady||!consent||pending)return Action.WAIT;
        if(fine)return Action.LOCATE;
        if(coarse)return !retry?Action.LOCATE:(rationale?Action.REQUEST:Action.SHOW_SETTINGS);
        if(!asked||(retry&&rationale))return Action.REQUEST;
        return Action.SHOW_SETTINGS;
    }
    public static boolean usableSnapshot(boolean permission,boolean systemEnabled,long now,long fix){
        return permission&&systemEnabled&&fresh(now,fix);
    }
    public static boolean validFix(double latitude,double longitude,float accuracy,boolean mock){
        return !mock&&Double.isFinite(latitude)&&Double.isFinite(longitude)&&latitude>=-90&&latitude<=90&&longitude>=-180&&longitude<=180&&!(latitude==0&&longitude==0)&&Float.isFinite(accuracy)&&accuracy>0;
    }
    public static boolean navigationAccuracy(float meters) {
        return Float.isFinite(meters) && meters > 0 && meters <= 100;
    }
    public static String locationError(int code){
        if(code==7||code==32)return "定位鉴权失败（错误"+code+"）：请核对Android Key、新包名和本安装签名SHA1；不要发送Key。";
        if(code==4)return "定位网络请求失败（错误4）。请检查网络，App 会继续获取位置。";
        return "高德定位失败（错误"+code+"），请记录错误码并检查定位权限、系统开关及网络。";
    }
    public static long fixTime(long nowNanos,long wallNowMillis,long elapsedNanos,long wallFixMillis){
        if(elapsedNanos>0 && !epochBasedElapsed(nowNanos,elapsedNanos,wallFixMillis))
            return fresh(nowNanos,elapsedNanos)?elapsedNanos:0;
        // Some SDK locations omit elapsedRealtimeNanos. Do not mark cached wall-clock fixes fresh.
        // Android Emulator's GPS HAL can instead put Unix-epoch nanoseconds in this
        // field. Only use wall time when both fields demonstrably describe the same
        // epoch measurement; never rescue arbitrary future or stale monotonic fixes.
        if(wallFixMillis<=0||wallFixMillis>wallNowMillis)return 0;
        long age=wallNowMillis-wallFixMillis;
        if(age>15000||nowNanos<=age*1000000L)return 0;
        return nowNanos-age*1000000L;
    }
    public static boolean epochBasedElapsed(long nowNanos,long elapsedNanos,long wallFixMillis) {
        return elapsedNanos>nowNanos && wallFixMillis>=946684800000L
                && Math.abs(elapsedNanos/1000000L-wallFixMillis)<=1;
    }
    public static boolean fresh(long now,long fix){return fix>0&&now>=fix&&now-fix<=15000000000L;}
}
