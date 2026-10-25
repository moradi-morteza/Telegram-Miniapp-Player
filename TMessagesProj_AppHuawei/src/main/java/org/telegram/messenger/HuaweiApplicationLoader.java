package org.telegram.messenger;

import com.huawei.hms.push.HmsMessaging;

import org.telegram.messenger.huawei.BuildConfig;

public class HuaweiApplicationLoader extends ApplicationLoader {
    @Override
    protected boolean isHuaweiBuild() {
        return true;
    }

    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        HmsMessaging.getInstance(this).setAutoInitEnabled(true);
        return HuaweiPushListenerProvider.INSTANCE;
    }

    @Override
    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        if (PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices()) {
            return new GoogleLocationProvider();
        }
        return new HuaweiLocationProvider();
    }

    @Override
    protected IMapsProvider onCreateMapsProvider() {
        if (PushListenerController.GooglePushListenerServiceProvider.INSTANCE.hasServices()) {
            return new GoogleMapsProvider();
        }
        return new HuaweiMapsProvider();
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
