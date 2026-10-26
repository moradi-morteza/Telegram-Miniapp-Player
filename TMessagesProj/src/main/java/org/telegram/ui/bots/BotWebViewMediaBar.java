package org.telegram.ui.bots;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RadialProgressView;

/**
 * Minimal transport bar for a Mini App's MediaPlayer bridge (web_app_set_media_metadata /
 * web_app_set_media_playback_state / web_app_clear_media_metadata / web_app_set_media_bar_enabled):
 * [cover | title / artist / seekbar | play-pause]. Reports play/pause/seek back to the page via
 * {@link Callback}. Step 1 only -- no OS notification, no bottom-sheet-tabs integration; only
 * live while the Mini App's own page/sheet is open.
 */
public class BotWebViewMediaBar extends FrameLayout {

    public interface Callback {
        void onPlayPauseRequested(boolean play);
        void onSeekRequested(long positionMs);
        default void onStateChanged() {}
    }

    private static final int PADDING_H = 12;
    private static final int PADDING_V = 7;
    private static final int ARTWORK_SIZE = 40;
    private static final int BUTTON_SIZE = 38;
    private static final int TITLE_HEIGHT = 20;
    private static final int ARTIST_HEIGHT = 18;
    private static final int SEEKBAR_HEIGHT = 20;
    private static final float SEEKBAR_LINE_HEIGHT = 3;
    private static final float SEEKBAR_THUMB_SIZE = 10;
    private static final int CONTENT_HEIGHT = Math.max(ARTWORK_SIZE, TITLE_HEIGHT + ARTIST_HEIGHT + 3 + SEEKBAR_HEIGHT);
    public static final int HEIGHT_DP = CONTENT_HEIGHT + PADDING_V * 2;

    private Callback callback;

    private String title = "";
    private String artist = "";
    private String coverUrl;
    private long positionMs;
    private long durationMs = -1;
    private boolean playing;
    private boolean loading;
    private boolean hasMedia;
    private boolean barEnabled = true;
    private boolean controllable = true;
    private float playbackRate = 1f;
    private boolean tracking;
    private long pendingSeekMs = -1;
    private long pendingSeekStartedAt;
    private int loadingRequestId;

    private int backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite);
    private int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    private int secondaryTextColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
    private int accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon);

    private final GradientDrawable backgroundDrawable;
    private final BackupImageView artwork;
    private final TextView titleView;
    private final TextView artistView;
    private final ImageView playPause;
    private final PlayPauseDrawable playPauseDrawable;
    private final RadialProgressView loadingSpinner;
    private final SeekBar seekBar;
    private final TextView elapsedTime;
    private final TextView remainingTime;

    public BotWebViewMediaBar(Context context) {
        super(context);

        backgroundDrawable = new GradientDrawable();
        backgroundDrawable.setColor(backgroundColor);
        // The bar is edge-to-edge. Rounded top corners expose the sheet's default background
        // through the transparent cut-outs (often white, especially with a dark Mini App).
        // Keep the full-width surface square so the requested background color covers every pixel.
        backgroundDrawable.setCornerRadius(0);
        setBackground(backgroundDrawable);
        setPadding(dp(PADDING_H), dp(PADDING_V), dp(PADDING_H), dp(PADDING_V));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, CONTENT_HEIGHT, Gravity.CENTER_VERTICAL | Gravity.LEFT));

        artwork = new BackupImageView(context);
        artwork.setRoundRadius(dp(8));
        row.addView(artwork, LayoutHelper.createLinear(ARTWORK_SIZE, ARTWORK_SIZE, Gravity.CENTER_VERTICAL));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, LayoutHelper.createLinear(0, CONTENT_HEIGHT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        titleView = textView(context, 14, textColor, true);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, TITLE_HEIGHT));
        artistView = textView(context, 12, secondaryTextColor, false);
        column.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, ARTIST_HEIGHT));

        LinearLayout transport = new LinearLayout(context);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(transport, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, SEEKBAR_HEIGHT, 0, 3, 0, 0));

        elapsedTime = textView(context, 10, secondaryTextColor, false);
        transport.addView(elapsedTime, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        seekBar = buildSeekBar(context);
        transport.addView(seekBar, LayoutHelper.createLinear(0, SEEKBAR_HEIGHT, 1f));

        remainingTime = textView(context, 10, secondaryTextColor, false);
        transport.addView(remainingTime, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));

        FrameLayout playPauseSlot = new FrameLayout(context);
        row.addView(playPauseSlot, LayoutHelper.createLinear(BUTTON_SIZE, BUTTON_SIZE, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        playPause = new ImageView(context);
        playPauseDrawable = new PlayPauseDrawable(18);
        playPauseDrawable.setColor(accentColor);
        playPauseDrawable.setParent(playPause);
        playPause.setImageDrawable(playPauseDrawable);
        playPause.setScaleType(ImageView.ScaleType.CENTER);
        playPause.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(accentColor, 0.18f), 1));
        playPause.setOnClickListener(v -> {
            if (!loading && controllable && callback != null) {
                applyPlayPauseRequest(!playing);
                callback.onPlayPauseRequested(playing);
            }
        });
        playPauseSlot.addView(playPause, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadingSpinner = new RadialProgressView(context);
        loadingSpinner.setSize(dp(18));
        loadingSpinner.setStrokeWidth(2f);
        loadingSpinner.setProgressColor(accentColor);
        playPauseSlot.addView(loadingSpinner, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        setVisibility(GONE);
    }

    private SeekBar buildSeekBar(Context context) {
        SeekBar bar = new SeekBar(context);
        bar.setMax(1000);
        int thumbInset = dp(SEEKBAR_THUMB_SIZE / 2f + 1);
        bar.setPadding(thumbInset, 0, thumbInset, 0);
        bar.setSplitTrack(false);

        int inset = (int) ((dp(SEEKBAR_HEIGHT) - dp(SEEKBAR_LINE_HEIGHT)) / 2f);
        Drawable track = new InsetDrawable(pill(Theme.multAlpha(secondaryTextColor, 0.3f)), 0, inset, 0, inset);
        Drawable progress = new ClipDrawable(new InsetDrawable(pill(accentColor), 0, inset, 0, inset), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable progressDrawable = new LayerDrawable(new Drawable[]{track, progress});
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        bar.setProgressDrawable(progressDrawable);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(accentColor);
        thumb.setSize(dp(SEEKBAR_THUMB_SIZE), dp(SEEKBAR_THUMB_SIZE));
        bar.setThumb(thumb);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) updateTimeLabels(durationMs * progress / 1000L);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) {
                tracking = false;
                if (durationMs > 0) {
                    pendingSeekMs = durationMs * b.getProgress() / 1000L;
                    pendingSeekStartedAt = SystemClock.elapsedRealtime();
                    positionMs = pendingSeekMs;
                    beginLoading();
                    updateTimeLabels(positionMs);
                    refresh();
                    if (callback != null) callback.onSeekRequested(positionMs);
                }
            }
        });
        return bar;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(SEEKBAR_LINE_HEIGHT) / 2f);
        drawable.setColor(color);
        return drawable;
    }

    private TextView textView(Context context, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setMetadata(String title, String artist, String coverUrl, long durationMs) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        if (durationMs >= 0) this.durationMs = durationMs;
        this.hasMedia = true;
        if (!TextUtils.equals(coverUrl, this.coverUrl)) {
            this.coverUrl = coverUrl;
            if (TextUtils.isEmpty(coverUrl)) {
                artwork.setImageDrawable(null);
            } else {
                artwork.getImageReceiver().setImage(coverUrl, "200_200", null, "jpg", 0);
            }
        }
        refresh();
    }

    public void setPlaybackState(long positionMs, long durationMs, int isPlaying, int isLoading, float playbackRate) {
        boolean nextLoading = isLoading >= 0 ? isLoading == 1 : loading;
        boolean seekConfirmed = false;
        if (positionMs >= 0) {
            if (pendingSeekMs < 0
                    || Math.abs(positionMs - pendingSeekMs) <= 1500
                    || (!nextLoading && SystemClock.elapsedRealtime() - pendingSeekStartedAt >= 3000)) {
                seekConfirmed = pendingSeekMs >= 0;
                pendingSeekMs = -1;
                this.positionMs = positionMs;
            }
        }
        if (durationMs >= 0) this.durationMs = durationMs;
        if (isPlaying >= 0) this.playing = isPlaying == 1;
        if (!Float.isNaN(playbackRate) && playbackRate > 0) this.playbackRate = playbackRate;
        this.loading = isLoading >= 0 ? nextLoading : (seekConfirmed || isPlaying >= 0 ? false : loading);
        if (!this.loading) loadingRequestId++;
        if (hasMedia) refresh();
    }

    public void clearMetadata() {
        hasMedia = false;
        playing = false;
        loading = false;
        loadingRequestId++;
        positionMs = 0;
        durationMs = -1;
        playbackRate = 1f;
        pendingSeekMs = -1;
        refresh();
    }

    public void setMediaBarEnabled(boolean enabled) {
        barEnabled = enabled;
        refresh();
    }

    /** Applies only the supplied colors; null fields keep their current value. */
    public void setMediaBarStyle(String backgroundColor, String textColor, String secondaryTextColor, String accentColor) {
        if (backgroundColor != null) this.backgroundColor = Color.parseColor(backgroundColor);
        if (textColor != null) this.textColor = Color.parseColor(textColor);
        if (secondaryTextColor != null) this.secondaryTextColor = Color.parseColor(secondaryTextColor);
        if (accentColor != null) this.accentColor = Color.parseColor(accentColor);
        applyStyle();
    }

    /** Restores a complete style snapshot when a minimized Mini App is reopened. */
    public void setMediaBarStyle(int backgroundColor, int textColor, int secondaryTextColor, int accentColor) {
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.secondaryTextColor = secondaryTextColor;
        this.accentColor = accentColor;
        applyStyle();
    }

    private void applyStyle() {
        backgroundDrawable.setColor(backgroundColor);
        titleView.setTextColor(textColor);
        artistView.setTextColor(secondaryTextColor);
        elapsedTime.setTextColor(secondaryTextColor);
        remainingTime.setTextColor(secondaryTextColor);
        playPauseDrawable.setColor(accentColor);
        playPause.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(accentColor, 0.18f), 1));
        loadingSpinner.setProgressColor(accentColor);

        int inset = (int) ((dp(SEEKBAR_HEIGHT) - dp(SEEKBAR_LINE_HEIGHT)) / 2f);
        Drawable track = new InsetDrawable(pill(Theme.multAlpha(secondaryTextColor, 0.3f)), 0, inset, 0, inset);
        Drawable progress = new ClipDrawable(new InsetDrawable(pill(accentColor), 0, inset, 0, inset), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable progressDrawable = new LayerDrawable(new Drawable[]{track, progress});
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        seekBar.setProgressDrawable(progressDrawable);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(accentColor);
        thumb.setSize(dp(SEEKBAR_THUMB_SIZE), dp(SEEKBAR_THUMB_SIZE));
        seekBar.setThumb(thumb);
        invalidate();
    }

    public void setControllable(boolean controllable) {
        this.controllable = controllable;
        refresh();
    }

    /** Optimistic feedback for controls initiated outside this bar (notification/tab/system UI). */
    public void applyPlayPauseRequest(boolean play) {
        playing = play;
        if (play) {
            beginLoading();
        } else {
            loading = false;
            loadingRequestId++;
        }
        refresh();
    }

    public void applySeekRequest(long positionMs) {
        this.positionMs = Math.max(0, durationMs > 0 ? Math.min(positionMs, durationMs) : positionMs);
        pendingSeekMs = this.positionMs;
        pendingSeekStartedAt = SystemClock.elapsedRealtime();
        beginLoading();
        refresh();
    }

    private void beginLoading() {
        loading = true;
        final int requestId = ++loadingRequestId;
        AndroidUtilities.runOnUIThread(() -> {
            if (loading && requestId == loadingRequestId) {
                loading = false;
                refresh();
                if (callback != null) callback.onStateChanged();
            }
        }, 8000);
    }

    public boolean hasMedia() { return hasMedia; }
    public boolean isMediaBarEnabled() { return barEnabled; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getCoverUrl() { return coverUrl; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public boolean isPlaying() { return playing; }
    public boolean isLoading() { return loading; }
    public float getPlaybackRate() { return playbackRate; }
    public int getBackgroundColorValue() { return backgroundColor; }
    public int getTextColorValue() { return textColor; }
    public int getSecondaryTextColorValue() { return secondaryTextColor; }
    public int getAccentColorValue() { return accentColor; }

    private void refresh() {
        boolean visible = hasMedia && barEnabled;
        setVisibility(visible ? VISIBLE : GONE);
        if (!visible) return;
        titleView.setText(title);
        artistView.setText(artist);
        playPauseDrawable.setPause(playing, true);
        final boolean controlsLoading = loading || !controllable;
        loadingSpinner.setVisibility(controlsLoading ? VISIBLE : GONE);
        playPause.setVisibility(controlsLoading ? INVISIBLE : VISIBLE);
        boolean hasDuration = durationMs > 0;
        seekBar.setEnabled(hasDuration && controllable && !loading);
        if (!tracking) {
            if (hasDuration) {
                seekBar.setProgress((int) Math.min(1000, positionMs * 1000L / durationMs));
                updateTimeLabels(positionMs);
            } else {
                seekBar.setProgress(0);
                elapsedTime.setText("");
                remainingTime.setText("");
            }
        }
    }

    private void updateTimeLabels(long shownPositionMs) {
        long position = Math.max(0, shownPositionMs);
        long remaining = Math.max(0, durationMs - position);
        elapsedTime.setText(AndroidUtilities.formatShortDuration((int) (position / 1000)));
        remainingTime.setText("-" + AndroidUtilities.formatShortDuration((int) (remaining / 1000)));
    }
}
