package com.motonav.companion;

/** Single-flight route/navigation state. Epochs invalidate asynchronous results after dismissal. */
public final class JourneyState {
    public enum Phase { IDLE, PLANNING, READY, STARTING, NAVIGATING }
    public enum Guidance { LIVE, WAITING, EXPIRED }
    private long epoch, readyAt;
    private long lastInstructionMs, waitingSinceMs = -1;
    private Phase phase=Phase.IDLE;
    public Phase phase(){return phase;}
    public long epoch(){return epoch;}
    public long cancel(){phase=Phase.IDLE;readyAt=0;waitingSinceMs=-1;return ++epoch;}
    public long begin(boolean goodFix,boolean destination){
        if(!goodFix||!destination||phase==Phase.PLANNING||phase==Phase.STARTING||phase==Phase.NAVIGATING)return -1;
        cancel();phase=Phase.PLANNING;return epoch;
    }
    public boolean planning(long token){return token==epoch&&phase==Phase.PLANNING;}
    public boolean ready(long token,long now){if(!planning(token)||now<0)return false;phase=Phase.READY;readyAt=now;return true;}
    public boolean start(boolean goodFix,long now,double movedMeters){
        if(phase!=Phase.READY)return false;
        if(!goodFix||now<readyAt||now-readyAt>60000||!Double.isFinite(movedMeters)||movedMeters<0||movedMeters>60){cancel();return false;}
        phase=Phase.STARTING;return true;
    }
    public boolean started(long token){if(token!=epoch||phase!=Phase.STARTING)return false;phase=Phase.NAVIGATING;return true;}
    public boolean active(long token){return token==epoch&&(phase==Phase.STARTING||phase==Phase.NAVIGATING);}
    public void startGuidance(long nowMs) {
        lastInstructionMs = nowMs;
        waitingSinceMs = -1;
    }

    public Guidance guidance(long nowMs, long trustedFixNanos, boolean newInstruction) {
        if (phase != Phase.NAVIGATING) return Guidance.WAITING;
        if (waitingSinceMs >= 0 && nowMs - waitingSinceMs >= 45_000) return Guidance.EXPIRED;
        if (trustedFixNanos > 0 && newInstruction
                && (waitingSinceMs < 0 || trustedFixNanos / 1_000_000L > waitingSinceMs)) {
            lastInstructionMs = nowMs;
            waitingSinceMs = -1;
            return Guidance.LIVE;
        }
        if (trustedFixNanos > 0 && waitingSinceMs < 0 && nowMs - lastInstructionMs <= 15_000) {
            return Guidance.LIVE;
        }
        if (waitingSinceMs < 0) waitingSinceMs = nowMs;
        return Guidance.WAITING;
    }

    public static boolean coordinate(double latitude,double longitude){return Double.isFinite(latitude)&&Double.isFinite(longitude)&&Math.abs(latitude)<=90&&Math.abs(longitude)<=180;}
    public static boolean usableFix(boolean fine,boolean mock,float accuracy,long now,long fix){return fine&&!mock&&MapStartupPolicy.navigationAccuracy(accuracy)&&MapStartupPolicy.fresh(now,fix);}
    public static int displacement(String text){try{int cc=Integer.parseInt(text.trim());return cc>=1&&cc<=10000?cc:0;}catch(RuntimeException e){return 0;}}
    /** Official AMap IconType values; unknown is never interpreted as straight. */
    public static String maneuver(int type){
        switch(type){
            case 2:return "左转";case 3:return "右转";case 4:return "向左前方行驶";case 5:return "向右前方行驶";
            case 6:return "向左后方转弯";case 7:return "向右后方转弯";case 8:return "左转掉头";case 9:return "直行";
            case 10:return "到达途经点";case 11:case 17:return "进入环岛";case 12:case 18:return "驶出环岛";
            case 13:return "到达服务区";case 14:return "到达收费站";case 15:return "到达目的地";case 16:return "进入隧道";
            case 19:return "右转掉头";case 20:return "沿道路继续行驶";case 21:case 25:return "绕环岛左转";
            case 22:case 26:return "绕环岛右转";case 23:case 27:return "绕环岛直行";case 24:case 28:return "绕环岛掉头";
            case 65:return "靠左行驶";case 66:return "靠右行驶";default:return "请核对地图路线";
        }
    }
    public static String routeError(int code){
        if(code==16)return "摩托算路失败（16）：高德服务访问权限不足。请核对新包名/签名绑定并申请本Key的摩托车服务权限；普通地图/搜索权限不等于摩托授权。不会改用汽车或自行车路线。";
        return "摩托算路失败（"+code+"）。请检查网络、定位和高德应用配置；本次未生成可导航路线。";
    }
}
