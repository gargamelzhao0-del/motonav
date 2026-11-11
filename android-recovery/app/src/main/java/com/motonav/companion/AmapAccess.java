package com.motonav.companion;
/** Pure pre-initialization gate. A configured key is not proof of authorization. */
public final class AmapAccess {
    private AmapAccess() {}
    public static boolean configured(String key) { return key != null && key.matches("[a-fA-F0-9]{32}"); }
    public static boolean canInitialize(String key, boolean consent, boolean foreground) { return configured(key) && consent && foreground; }
    public static String navigationStatus() { return "导航未启用：需另行实现和验证导航流程、服务权限与摩托车许可。不得将汽车/自行车路线当作摩托车路线；高德数据不会进入 BLE。"; }
}
