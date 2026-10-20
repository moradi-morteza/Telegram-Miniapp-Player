package org.telegram.ui.bots;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.util.HashMap;
import java.util.Map;

/**
 * Native Android media session + notification for a Mini App's MediaPlayer bridge. Modeled on
 * Telegram's own {@link org.telegram.messenger.MusicPlayerService} (same MediaStyle notification
 * pattern, same NotificationsController channel, same .setOngoing()-while-playing convention that
 * keeps the notification from being swiped away during playback).
 *
 * One instance per account:botId, kept alive across a sheet minimizing into a tab -- only a full
 * close (or the Mini App itself clearing its media) releases it. This is the single source of
 * truth for the OS notification; BotWebViewMediaBar and the collapsed tab pill remain their own,
 * separate, already-working representations of the same underlying events.
 */
public class BotWebViewMediaSession {
    public static final String ACTION_PLAY = "org.telegram.webapp.media.PLAY";
    public static final String ACTION_PAUSE = "org.telegram.webapp.media.PAUSE";
    public static final String ACTION_OPEN = "org.telegram.webapp.media.OPEN";
    public static final String ACTION_LOADING_NOOP = "org.telegram.webapp.media.LOADING_NOOP";
    public static final String EXTRA_KEY = "webapp_media_key";

    public interface Callback {
        void onPlayPauseRequested(boolean play);
        void onSeekRequested(long positionMs);
    }

    private static final Map<String, BotWebViewMediaSession> sessions = new HashMap<>();
    private static BotWebViewMediaSession activeSession;

    public static BotWebViewMediaSession get(int account, long botId) {
        String key = account + ":" + botId;
        BotWebViewMediaSession session = sessions.get(key);
        if (session == null) {
            session = new BotWebViewMediaSession(account, botId, key);
            sessions.put(key, session);
        }
        return session;
    }

    @Nullable
    public static BotWebViewMediaSession find(String key) {
        return key == null ? null : sessions.get(key);
    }

    @Nullable
    public static BotWebViewMediaSession find(int account, long botId) {
        return sessions.get(account + ":" + botId);
    }

    @Nullable
    public static BotWebViewMediaSession getActiveSession() {
        return activeSession;
    }

    private final Context context = ApplicationLoader.applicationContext;
    private final int account;
    private final long botId;
    private final String key;
    private final MediaSessionCompat mediaSession;
    private final ImageReceiver imageReceiver = new ImageReceiver(null);

    private Callback callback;
    private String title = "";
    private String artist = "";
    private String coverUrl;
    private Bitmap coverBitmap;
    private long positionMs;
    private long durationMs;
    private boolean playing;
    private boolean loading;
    private boolean hasMedia;
    private boolean mediaBarEnabled = true;
    private boolean controllable = true;
    private float playbackRate = 1f;
    private boolean seekPending;
    private long pendingSeekMs;
    private long pendingSeekStartedAt;

    private BotWebViewMediaSession(int account, long botId, String key) {
        this.account = account;
        this.botId = botId;
        this.key = key;
        mediaSession = new MediaSessionCompat(context, "BotWebViewMedia:" + key);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { requestPlayPause(true); }
            @Override public void onPause() { requestPlayPause(false); }
            @Override public void onSeekTo(long pos) { requestSeek(pos); }
        });
        mediaSession.setActive(true);
        imageReceiver.setDelegate((receiver, set, thumb, memCache) -> {
            if (set && hasMedia) {
                coverBitmap = receiver.getBitmap();
                publish();
            }
        });
    }

    public String getKey() { return key; }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void rebindCallback(Callback callback, boolean webViewPreserved) {
        this.callback = callback;
        controllable = webViewPreserved;
        publish();
    }

    /** A bridge call proves that the page and its MediaPlayer listener are alive again. */
    public void onBridgeActivity() {
        if (!controllable) {
            controllable = true;
            publish();
        }
    }

    public void setMetadata(String title, String artist, String coverUrl, long durationMs) {
        onBridgeActivity();
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        if (durationMs >= 0) this.durationMs = durationMs;
        hasMedia = true;
        final boolean becameActive = activeSession != this;
        if (activeSession != null && activeSession != this) {
            activeSession.mediaSession.setActive(false);
        }
        activeSession = this;
        mediaSession.setActive(true);
        if (becameActive) notifiedTitle = null;
        if (!TextUtils.equals(this.coverUrl, coverUrl)) {
            this.coverUrl = coverUrl;
            coverBitmap = null;
            imageReceiver.setImageBitmap((Bitmap) null);
            if (!TextUtils.isEmpty(coverUrl)) {
                imageReceiver.setImage(coverUrl, "200_200", null, "jpg", 0);
            }
        }
        publish();
    }

    public void setPlaybackState(long positionMs, long durationMs, int isPlaying, int isLoading, float playbackRate) {
        onBridgeActivity();
        final boolean nextLoading = isLoading >= 0 ? isLoading == 1 : loading;
        if (positionMs >= 0) {
            if (!seekPending
                    || Math.abs(positionMs - pendingSeekMs) <= 1500
                    || (!nextLoading && SystemClock.elapsedRealtime() - pendingSeekStartedAt >= 3000)) {
                seekPending = false;
                this.positionMs = positionMs;
            }
        }
        if (durationMs >= 0) this.durationMs = durationMs;
        if (isPlaying >= 0) this.playing = isPlaying == 1;
        if (isLoading >= 0) {
            this.loading = isLoading == 1;
            if (!this.loading) loadingRequestId++;
        } else if (isPlaying >= 0 || positionMs >= 0 && !seekPending) {
            // A matching bridge response also completes a fast, already-buffered request.
            this.loading = false;
            loadingRequestId++;
        }
        if (!Float.isNaN(playbackRate) && playbackRate > 0) this.playbackRate = playbackRate;
        if (hasMedia) publish();
    }

    public void setMediaBarEnabled(boolean enabled) {
        onBridgeActivity();
        mediaBarEnabled = enabled;
    }

    // Called only when the Mini App itself ends playback with nothing queued next -- NOT when a
    // host sheet merely minimizes (see BotWebViewSheet/BotWebViewAttachedSheet: they deliberately
    // don't call this on minimize so the notification survives).
    public void clear() {
        hasMedia = false;
        playing = false;
        loading = false;
        positionMs = 0;
        durationMs = 0;
        playbackRate = 1f;
        seekPending = false;
        loadingRequestId++;
        imageReceiver.cancelLoadImage();
        if (activeSession == this) {
            activeSession = null;
            context.stopService(new Intent(context, BotWebViewMediaService.class));
        }
        mediaSession.setActive(false);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0).build());
        mediaSession.setMetadata(null);
        notifiedTitle = null;
        notifiedArtist = null;
        notifiedCover = null;
    }

    // Full teardown -- only on a full (non-minimize) close of every host showing this bot.
    public void release() {
        clear();
        if (sessions.get(key) == this) sessions.remove(key);
        callback = null;
        mediaSession.release();
    }

    private static final long LOADING_TIMEOUT_MS = 8000;
    private int loadingRequestId;

    // Pausing is instant and never needs buffering, so it must never enter the loading state --
    // a Mini App's 'pause' handler only reports is_playing back, never is_loading, so a
    // loading=true set here would never get cleared and would strand the transport controls
    // behind a permanent spinner.
    public void requestPlayPause(boolean play) {
        if (!controllable) return;
        playing = play;
        if (play) {
            beginLoading();
        } else {
            loading = false;
            loadingRequestId++;
        }
        publish();
        if (callback != null) callback.onPlayPauseRequested(play);
    }

    public void requestSeek(long positionMs) {
        requestSeekAbsolute(positionMs);
    }

    public void requestSeekAbsolute(long positionMs) {
        if (!controllable) return;
        this.positionMs = Math.max(0, durationMs > 0 ? Math.min(positionMs, durationMs) : positionMs);
        pendingSeekMs = this.positionMs;
        pendingSeekStartedAt = SystemClock.elapsedRealtime();
        seekPending = true;
        beginLoading();
        publish();
        if (callback != null) callback.onSeekRequested(this.positionMs);
    }

    // Safety net for a Mini App that never reports is_loading back: auto-clears loading after a
    // timeout instead of leaving the transport controls stuck behind a spinner forever. Guarded
    // by an id so a stale timeout can't clobber a newer, still-in-flight loading request.
    private void beginLoading() {
        loading = true;
        final int requestId = ++loadingRequestId;
        AndroidUtilities.runOnUIThread(() -> {
            if (loading && requestId == loadingRequestId) {
                loading = false;
                publish();
            }
        }, LOADING_TIMEOUT_MS);
    }

    // Last notification content actually posted, so publish() (called roughly once a second
    // while media plays, per the SDK's throttled position reporting) doesn't repost the
    // Notification -- a RemoteViews rebuild plus a binder IPC to system_server -- on every tick.
    private String notifiedTitle;
    private String notifiedArtist;
    private boolean notifiedPlaying;
    private boolean notifiedLoading;
    private Bitmap notifiedCover;

    private void publish() {
        if (!hasMedia) return;
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (coverBitmap != null && !coverBitmap.isRecycled()) metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap);
        mediaSession.setMetadata(metadata.build());
        final boolean controlsLoading = loading || !controllable;
        long actions = PlaybackStateCompat.ACTION_SEEK_TO;
        if (!controlsLoading) {
            actions |= PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE;
        }
        int state = controlsLoading ? PlaybackStateCompat.STATE_BUFFERING : playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        // The lock-screen/notification scrubber is driven by this PlaybackState (position +
        // speed), which the system animates on its own -- setting it every tick is cheap and is
        // the only thing that needs to happen that often.
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, playing && !controlsLoading ? playbackRate : 0f)
                .build());
        if (activeSession == this) {
            // loading matters here too: the notification's action shows a spinner while
            // loading and the play/pause icon otherwise, so a loading<->not-loading flip
            // with everything else unchanged (e.g. resuming from the same paused position)
            // must still repost, or the action is left stuck on a stale icon.
            final boolean contentChanged = !TextUtils.equals(title, notifiedTitle) || !TextUtils.equals(artist, notifiedArtist)
                    || playing != notifiedPlaying || controlsLoading != notifiedLoading || coverBitmap != notifiedCover;
            if (contentChanged) {
                notifiedTitle = title;
                notifiedArtist = artist;
                notifiedPlaying = playing;
                notifiedLoading = controlsLoading;
                notifiedCover = coverBitmap;
                BotWebViewMediaService.publish(this);
            }
        }
    }

    // Same MediaStyle notification pattern as MusicPlayerService.updateMediaPlayerNotification():
    // Notification.Builder + Notification.MediaStyle, NotificationsController's shared channel,
    // .setOngoing(playing) so it can't be swiped away while actually playing (only while paused).
    public Notification buildNotification() {
        NotificationsController.checkOtherNotificationsChannel();
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN)
                .putExtra("currentAccount", account).putExtra("bot_id", botId);
        PendingIntent content = PendingIntent.getActivity(context, key.hashCode(), open, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        final boolean controlsLoading = loading || !controllable;
        String action = playing ? ACTION_PAUSE : ACTION_PLAY;
        Intent control = new Intent(action).setComponent(new ComponentName(context, BotWebViewMediaReceiver.class)).putExtra(EXTRA_KEY, key);
        PendingIntent controlIntent = PendingIntent.getBroadcast(context, key.hashCode() + 1, control, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        Intent loadingNoop = new Intent(ACTION_LOADING_NOOP).setComponent(new ComponentName(context, BotWebViewMediaReceiver.class)).putExtra(EXTRA_KEY, key);
        PendingIntent loadingIntent = PendingIntent.getBroadcast(context, key.hashCode() + 2, loadingNoop, pendingFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(
                (android.media.session.MediaSession.Token) mediaSession.getSessionToken().getToken()).setShowActionsInCompactView(0);
        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.player).setContentTitle(title).setContentText(artist)
                .setContentIntent(content).setShowWhen(false).setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(style);
        // Current / total, e.g. "1:23 / 4:56" -- a snapshot of whatever position was current
        // the last time the notification actually got reposted (see publish()'s
        // contentChanged check), not a live-ticking counter, so this alone never forces an
        // extra repost.
        if (durationMs > 0) {
            builder.setSubText(AndroidUtilities.formatShortDuration((int) (positionMs / 1000)) + " / " + AndroidUtilities.formatShortDuration((int) (durationMs / 1000)));
        }
        if (Build.VERSION.SDK_INT >= 26) builder.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
        if (coverBitmap != null && !coverBitmap.isRecycled()) builder.setLargeIcon(coverBitmap);
        builder.addAction(new Notification.Action.Builder(
                controlsLoading ? R.drawable.loading_animation2 : playing ? R.drawable.ic_action_pause : R.drawable.ic_action_play,
                controlsLoading ? LocaleController.getString(R.string.Loading) : LocaleController.getString(playing ? R.string.AccActionPause : R.string.AccActionPlay),
                controlsLoading ? loadingIntent : controlIntent).build());
        return builder.build();
    }

    private int pendingFlags(int flags) {
        return flags | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public Bitmap getCoverBitmap() { return coverBitmap; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public boolean isPlaying() { return playing; }
    public boolean isLoading() { return loading; }
    public boolean hasMedia() { return hasMedia; }
    public boolean isMediaBarEnabled() { return mediaBarEnabled; }
    public boolean isControllable() { return controllable; }
    public int getAccount() { return account; }
    public long getBotId() { return botId; }
}
