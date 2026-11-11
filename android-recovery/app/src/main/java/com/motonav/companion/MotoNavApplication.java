package com.motonav.companion;

public final class MotoNavApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        Diagnostics.initialize(this);
    }
}
