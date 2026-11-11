package com.motonav.companion;
/** Pure decisions; never confuse remembered intent with current OS permission. */
public final class MapStartupPolicy {
    public enum Action { WAIT, LOCATE, REQUEST, SHOW_SETTINGS }
    public static Action location(boolean foreground,boolean mapReady,boolean consent,boolean pending,
        boolean fine,boolean coarse,boolean asked,boolean retry,boolean rationale){
        if(!foreground||!mapReady||!consent||pending)return Action.WAIT;
        if(fine||coarse)return Action.LOCATE;
        if(!asked||(retry&&rationale))return Action.REQUEST;
        return Action.SHOW_SETTINGS;
    }
    public static long fixTime(long nowNanos,long wallNowMillis,long elapsedNanos,long wallFixMillis){
        if(elapsedNanos>0)return fresh(nowNanos,elapsedNanos)?elapsedNanos:0;
        // Some SDK locations omit elapsedRealtimeNanos. Do not mark cached wall-clock fixes fresh.
        if(wallFixMillis<=0||wallFixMillis>wallNowMillis)return 0;
        long age=wallNowMillis-wallFixMillis;
        if(age>15000||nowNanos<=age*1000000L)return 0;
        return nowNanos-age*1000000L;
    }
    public static boolean fresh(long now,long fix){return fix>0&&now>=fix&&now-fix<=15000000000L;}
}
