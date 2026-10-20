package org.telegram.ui.bots;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;

/**
 * Foreground service hosting the MediaPlayer bridge's notification, one instance shared by
 * whichever BotWebViewMediaSession is currently "active". Mirrors the startForeground/notify
 * split used by org.telegram.messenger.MusicPlayerService (only the very first publish starts
 * the foreground service; every later update just calls NotificationManager.notify()).
 */
public class BotWebViewMediaService extends Service {
    private static final int NOTIFICATION_ID = 51;
    private static BotWebViewMediaService instance;

    public static void publish(BotWebViewMediaSession session) {
        BotWebViewMediaService service = instance;
        if (service != null) {
            service.updateNotification(session);
        } else {
            Intent intent = new Intent(ApplicationLoader.applicationContext, BotWebViewMediaService.class)
                    .putExtra(BotWebViewMediaSession.EXTRA_KEY, session.getKey());
            ContextCompat.startForegroundService(ApplicationLoader.applicationContext, intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String key = intent == null ? null : intent.getStringExtra(BotWebViewMediaSession.EXTRA_KEY);
        BotWebViewMediaSession session = BotWebViewMediaSession.find(key);
        if (session == null || !session.hasMedia()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = session.buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    private void updateNotification(BotWebViewMediaSession session) {
        if (session == null || session != BotWebViewMediaSession.getActiveSession() || !session.hasMedia()) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, session.buildNotification());
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        stopForeground(true);
        super.onDestroy();
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
