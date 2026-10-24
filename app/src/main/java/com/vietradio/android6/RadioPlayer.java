package com.vietradio.android6;

import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Equalizer;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import okhttp3.OkHttpClient;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * Radio playback based on Media3/ExoPlayer.
 * TEST BUILD ONLY: trusts any server certificate chain for radio streams, but keeps
 * hostname verification enabled. Do not publish this configuration as a release app.
 */
public final class RadioPlayer {
  public interface StateListener { void onPlaybackStateChanged(String stationName, boolean playing); }
  private static ExoPlayer player;
  private static LoudnessEnhancer loudnessEnhancer;
  private static Equalizer fallbackEqualizer;
  private static String currentStationName;
  private static boolean playing;
  private static StateListener stateListener;
  private static Context appContext;
  private RadioPlayer() {}

  public static void setStateListener(StateListener listener) {
    stateListener = listener;
    notifyState();
  }
  public static boolean isPlayingStation(String stationName) {
    return playing && currentStationName != null && currentStationName.equals(stationName);
  }
  public static boolean isCurrentStation(String stationName) {
    return currentStationName != null && currentStationName.equals(stationName) && player != null;
  }
  private static void notifyState() {
    StateListener listener = stateListener;
    if (listener != null) listener.onPlaybackStateChanged(currentStationName, playing);
  }

  private static OkHttpClient createRadioHttpClient() throws Exception {
    X509TrustManager trustAll = new X509TrustManager() {
      @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
      @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
    };
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, new javax.net.ssl.TrustManager[]{trustAll}, new SecureRandom());
    // Deliberately do not override hostnameVerifier: hostnames are still checked.
    return new OkHttpClient.Builder()
        .sslSocketFactory(ssl.getSocketFactory(), trustAll)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
  }

  public static void play(Context c, Station s) {
    stop();
    if (s.url == null || s.url.trim().isEmpty()) {
      Toast.makeText(c, "Kênh này chưa có URL audio trực tiếp.", Toast.LENGTH_LONG).show();
      return;
    }
    appContext = c.getApplicationContext();
    currentStationName = s.name;
    playing = false;
    notifyState();
    try {
      OkHttpClient client = createRadioHttpClient();
      OkHttpDataSource.Factory dataSourceFactory = new OkHttpDataSource.Factory(client)
          .setUserAgent("RadioVietNamAndroid6/3.14");
      player = new ExoPlayer.Builder(appContext)
          .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
          .build();
      player.addListener(new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean isPlaying) {
          playing = isPlaying;
          if (isPlaying && player != null) attachVolumeBoost(player.getAudioSessionId());
          else releaseVolumeBoost();
          notifyState();
        }
        @Override public void onPlayerError(PlaybackException error) {
          String detail = error.getErrorCodeName();
          Toast.makeText(appContext, "Không phát được stream (" + detail + "). URL có thể đã đổi hoặc máy chủ từ chối kết nối.", Toast.LENGTH_LONG).show();
          stop();
        }
      });
      player.setMediaItem(MediaItem.fromUri(s.url));
      player.prepare();
      player.play();
    } catch (Exception | LinkageError e) {
      stop();
      Toast.makeText(c, "Không mở được stream: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
    }
  }

  /** Best-effort per-stream gain boost. */
  private static void attachVolumeBoost(int sessionId) {
    releaseVolumeBoost();
    try {
      loudnessEnhancer = new LoudnessEnhancer(sessionId);
      loudnessEnhancer.setTargetGain(1200);
      loudnessEnhancer.setEnabled(true);
      return;
    } catch (Exception | LinkageError ignored) {
      if (loudnessEnhancer != null) { try { loudnessEnhancer.release(); } catch (Exception ignoredAgain) {} loudnessEnhancer = null; }
    }
    try {
      fallbackEqualizer = new Equalizer(0, sessionId);
      short[] range = fallbackEqualizer.getBandLevelRange();
      short boost = (short) Math.min(1200, range[1]);
      short bands = fallbackEqualizer.getNumberOfBands();
      for (short band = 0; band < bands; band++) fallbackEqualizer.setBandLevel(band, boost);
      fallbackEqualizer.setEnabled(true);
    } catch (Exception | LinkageError ignored) {
      if (fallbackEqualizer != null) { try { fallbackEqualizer.release(); } catch (Exception ignoredAgain) {} fallbackEqualizer = null; }
    }
  }

  private static void releaseVolumeBoost() {
    if (loudnessEnhancer != null) {
      try { loudnessEnhancer.setEnabled(false); } catch (Exception ignored) {}
      try { loudnessEnhancer.release(); } catch (Exception ignored) {}
      loudnessEnhancer = null;
    }
    if (fallbackEqualizer != null) {
      try { fallbackEqualizer.setEnabled(false); } catch (Exception ignored) {}
      try { fallbackEqualizer.release(); } catch (Exception ignored) {}
      fallbackEqualizer = null;
    }
  }

  public static void toggle(Context c, Station s) {
    if (player == null || !isCurrentStation(s.name)) { play(c, s); return; }
    try {
      if (player.isPlaying() || player.getPlayWhenReady()) player.pause();
      else player.play();
    } catch (Exception e) { play(c, s); }
  }

  public static void stop() {
    releaseVolumeBoost();
    try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
    player = null;
    currentStationName = null;
    playing = false;
    notifyState();
  }
}
