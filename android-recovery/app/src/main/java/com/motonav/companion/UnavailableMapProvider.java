package com.motonav.companion;

import java.util.List;

/**
 * Honest placeholder for a future official Amap integration. It does not read the
 * installed Amap app, scrape its data, make network requests, or fall back to fiction.
 */
public final class UnavailableMapProvider implements MapProvider {
    private static final String REQUIREMENTS =
            "高德官方地图尚未接入：需完成开发者资质与 Android Key / 包名 / 签名配置、"
            + "接入官方地图及搜索/导航 SDK、核对服务许可、按要求展示隐私政策并取得用户同意、"
            + "申请定位权限并取得有效位置后才能启用真实地图与导航。摩托车导航需单独开通；车载或外部屏幕投射需明确书面许可。官方 Link SDK 还需审核硬件 Key、授权流程、SDK 与数据结构。"
            + " 当前不能读取高德 App 私有数据，也不会返回模拟结果冒充真实路线。";

    @Override public String id() { return "amap"; }
    @Override public String name() { return "高德官方 / Amap"; }
    @Override public boolean isAvailable() { return false; }
    @Override public boolean isSimulation() { return false; }
    @Override public String status() { return REQUIREMENTS; }

    @Override public List<Place> search(String query) {
        throw new IllegalStateException("无法搜索地点。" + REQUIREMENTS);
    }

    @Override public Route routeTo(String placeId) {
        throw new IllegalStateException("无法规划路线。" + REQUIREMENTS);
    }
}
