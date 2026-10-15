package com.vietradio.android6;

import android.content.Context;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public final class RadioPlayer {
    private static ExoPlayer player;
    private static String currentUrl;
    private static boolean preparing;

    private RadioPlayer() {}

    public static void play(Context context, Station station) {
        if (station == null || station.url == null || station.url.trim().isEmpty()) {
            stop();
            Toast.makeText(context, "Kênh này chưa có URL audio trực tiếp.", Toast.LENGTH_LONG).show();
            return;
        }

        if (player != null && station.url.equals(currentUrl)) {
            if (preparing) return;
            try {
                if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                player.play();
            } catch (Exception e) {
                stop();
                play(context, station);
            }
            return;
        }

        stop();
        Context appContext = context.getApplicationContext();

        try {
            ExoPlayer newPlayer = new ExoPlayer.Builder(appContext).build();
            player = newPlayer;
            currentUrl = station.url;
            preparing = true;

            newPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (player != newPlayer) return;
                    preparing = state == Player.STATE_BUFFERING;
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (player != newPlayer) return;
                    Toast.makeText(appContext,
                            "Không phát được stream: " + error.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                    stop();
                }
            });

            newPlayer.setMediaItem(MediaItem.fromUri(station.url));
            newPlayer.prepare();
            newPlayer.play();
        } catch (Exception e) {
            stop();
            Toast.makeText(appContext, "Lỗi mở stream: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void toggle(Context context, Station station) {
        if (station == null) return;
        if (player == null || station.url == null || !station.url.equals(currentUrl)) {
            play(context, station);
            return;
        }
        if (preparing) return;
        try {
            if (player.isPlaying()) player.pause();
            else player.play();
        } catch (Exception e) {
            play(context, station);
        }
    }

    public static void stop() {
        ExoPlayer oldPlayer = player;
        player = null;
        currentUrl = null;
        preparing = false;
        if (oldPlayer != null) {
            try { oldPlayer.stop(); } catch (Exception ignored) {}
            try { oldPlayer.release(); } catch (Exception ignored) {}
        }
    }
}
