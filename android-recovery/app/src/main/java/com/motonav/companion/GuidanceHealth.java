package com.motonav.companion;

/** Diagnoses independent streams without relaxing the production freshness gate. */
public final class GuidanceHealth {
    public enum Reason { GPS_WEAK, MAP_FIX_STALE, MAP_FIX_STALE_SDK_LIVE, INSTRUCTION_MISSING, INSTRUCTION_STALE, AWAITING_NEW_POSITION }
    public static Reason reason(long nowNanos, boolean gpsWeak, long mapFixNanos, long sdkFixNanos, long instructionMs) {
        long nowMs = nowNanos/1_000_000L;
        if (gpsWeak) return Reason.GPS_WEAK;
        if (!MapStartupPolicy.fresh(nowNanos,mapFixNanos))
            return MapStartupPolicy.fresh(nowNanos,sdkFixNanos) ? Reason.MAP_FIX_STALE_SDK_LIVE : Reason.MAP_FIX_STALE;
        if (instructionMs < 0) return Reason.INSTRUCTION_MISSING;
        if (instructionMs > nowMs || nowMs-instructionMs > 15_000) return Reason.INSTRUCTION_STALE;
        return Reason.AWAITING_NEW_POSITION;
    }
    public static String message(Reason reason) {
        return switch (reason) {
            case GPS_WEAK -> "导航 GPS 信号弱，路线已保留。请在开阔处等待卫星定位恢复。";
            case MAP_FIX_STALE -> "当前位置已过期或精度不足，路线已保留，正在等待新定位。";
            case MAP_FIX_STALE_SDK_LIVE -> "地图定位已过期，但导航 SDK 仍在更新位置。正在等待地图定位恢复，请开启诊断记录此情况。";
            case INSTRUCTION_MISSING -> "当前位置可用，但导航 SDK 尚未返回引导指令。可在地图设置中运行 SDK 模拟导航测试。";
            case INSTRUCTION_STALE -> "当前位置可用，但导航指令已超过 15 秒未更新。路线已保留，正在等待 SDK 恢复。";
            case AWAITING_NEW_POSITION -> "已收到导航指令，正在等待中断后的新位置，恢复后继续引导。";
        };
    }
    private GuidanceHealth() { }
}
