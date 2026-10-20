package org.telegram.ui.bots;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the play/pause action button tapped on the MediaPlayer bridge's notification. */
public class BotWebViewMediaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        BotWebViewMediaSession session = BotWebViewMediaSession.find(intent.getStringExtra(BotWebViewMediaSession.EXTRA_KEY));
        if (session == null) return;
        if (BotWebViewMediaSession.ACTION_PLAY.equals(intent.getAction())) session.requestPlayPause(true);
        else if (BotWebViewMediaSession.ACTION_PAUSE.equals(intent.getAction())) session.requestPlayPause(false);
    }
}
