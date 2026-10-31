package com.vietradio.android6;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Equalizer;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import okhttp3.OkHttpClient;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
  /** Extra loudness in millibels (100 mB = 1 dB); user-adjustable with the slider, saved in SharedPreferences. */
  private static final int DEFAULT_BOOST_MB = 1800;
  private static final int MAX_BOOST_MB = 4000;
  private static int boostMb = -1;
  private static int boost() { return boostMb < 0 ? DEFAULT_BOOST_MB : boostMb; }
  public static int defaultBoostMb() { return DEFAULT_BOOST_MB; }
  public static int getBoostMb(Context c) { return boost(); }
  /** Sets (and applies live, if audio is playing) the loudness boost. Remembering it per station/file is done by the UI. */
  public static void setBoostMb(Context c, int mb) {
    boostMb = Math.max(0, Math.min(MAX_BOOST_MB, mb));
    applyBoostLive();
  }
  private static void applyBoostLive() {
    try {
      if (loudnessEnhancer != null) loudnessEnhancer.setTargetGain(boost());
      else if (fallbackEqualizer != null) {
        short[] range = fallbackEqualizer.getBandLevelRange();
        short lvl = (short) Math.min(boost(), range[1]);
        short bands = fallbackEqualizer.getNumberOfBands();
        for (short band = 0; band < bands; band++) fallbackEqualizer.setBandLevel(band, lvl);
      }
    } catch (Exception | LinkageError ignored) {}
  }
  private static String currentStationName;
  private static boolean playing;
  private static boolean buffering;
  private static StateListener stateListener;
  private static Context appContext;
  // ---- TS recording seek support (restart-from-byte-offset) ----
  private static final AtomicLong tsOffset = new AtomicLong(0);
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());
  private static File tsFile;
  private static String tsUrl;
  private static long tsLength;
  private static int tsOrigin;
  private static long seekBaseMs;
  private static long totalDurationMs;
  private static boolean scanTried;
  private static long pendingTargetMs = -1;
  private static int refineTries;
  private static boolean cbrMode;
  private static boolean localPlay;
  private static boolean genericRestart;
  private static int pktAlign = 188;
  private static String diagInfo = "";
  // PCR/PAT index of the TS file (built in background): idxByte[i] = absolute offset of a PAT packet, idxMs[i] = time there.
  private static volatile long[] idxByte, idxMs;
  private static volatile long idxTotalMs;
  private static volatile boolean idxReady;
  private static volatile int playGen;
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
  /** True from the moment playback of this station is requested until audio actually starts. */
  public static boolean isLoadingStation(String stationName) {
    if (!isCurrentStation(stationName) || playing) return false;
    try {
      int st = player.getPlaybackState();
      return player.getPlayWhenReady() && (st == Player.STATE_BUFFERING || st == Player.STATE_IDLE);
    } catch (Exception e) { return false; }
  }
  /** True only while the selected station is buffering and playback is still requested. */
  public static boolean isBufferingStation(String stationName) {
    return isCurrentStation(stationName) && player.getPlaybackState() == Player.STATE_BUFFERING
        && player.getPlayWhenReady();
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
    localPlay = s.url != null && s.url.startsWith("file:");
    tsOffset.set(0); seekBaseMs = 0; totalDurationMs = 0; scanTried = false; pendingTargetMs = -1; refineTries = 0;
    tsFile = null; tsUrl = null; tsLength = 0; tsOrigin = 0; cbrMode = false; genericRestart = false; pktAlign = 188; diagInfo = "";
    idxReady = false; idxTotalMs = 0; idxByte = null; idxMs = null; playGen++;
    try {
      if (s.url.startsWith("file:")) {
        // Detect MPEG-TS by content (188-byte sync grid), not by file extension.
        final File f = new File(Uri.parse(s.url).getPath());
        int o = findGrid(f);
        if (o >= 0) {
          tsFile = f; tsUrl = s.url; tsLength = f.length(); tsOrigin = o;
          final int gen = playGen; final int origin = o;
          new Thread(new Runnable() { @Override public void run() { buildTsIndex(f, origin, gen); } }, "TsIndex").start();
        }
        if (o < 0) {
          // Not MPEG-TS: restart-from-offset at an arbitrary byte (used only if ExoPlayer cannot seek it itself).
          tsFile = f; tsUrl = s.url; tsLength = f.length(); tsOrigin = 0; genericRestart = true; pktAlign = 1;
        }
        String nm = f.getName(); int dot = nm.lastIndexOf('.');
        diagInfo = (o >= 0 ? "TS" : "KHONG-TS") + " ext=" + (dot >= 0 ? nm.substring(dot) : "?") + " " + (f.length() / 1024) + "KB dau=" + headHex(f);
        debugToast(diagInfo, true);
      }
      // Local file that is not a 188-byte-packet MPEG-TS (e.g. HLS packed-audio AAC/ADTS saved as .ts,
      // .aac, .mp3, .audio): let ExoPlayer seek by constant bitrate, otherwise it has no seek map.
      if (tsFile == null || genericRestart) { if (s.url.startsWith("file:")) cbrMode = true; }
    } catch (Exception ignored) {}
    currentStationName = s.name;
    playing = false;
    buffering = true;
    notifyState();
    try {
      OkHttpClient client = createRadioHttpClient();
      OkHttpDataSource.Factory httpDataSourceFactory = new OkHttpDataSource.Factory(client)
          .setUserAgent("RadioVietNamAndroid6/3.14");
      // Use Android's default data source wrapper: it routes file:// URIs to FileDataSource
      // and HTTP(S) radio URLs to OkHttp. OkHttpDataSource alone cannot read local files.
      DefaultDataSource.Factory dataSourceFactory =
          new DefaultDataSource.Factory(appContext, httpDataSourceFactory);
      // Use Media3's default TS extractor settings. The constant-bitrate seeking
      // flag is not available in the Media3 version used by this project.
      DataSource.Factory finalFactory = dataSourceFactory;
      if (tsFile != null) {
        // Local TS recordings: ExoPlayer's PCR binary search often jumps back to 0, so
        // seeking is done by re-opening the file at a byte offset (see seekToPosition).
        final AtomicLong off = tsOffset;
        finalFactory = new ResolvingDataSource.Factory(dataSourceFactory, new ResolvingDataSource.Resolver() {
          @Override public DataSpec resolveDataSpec(DataSpec dataSpec) throws IOException {
            long o = off.get();
            if (o > 0 && "file".equals(dataSpec.uri.getScheme())) return dataSpec.subrange(o);
            return dataSpec;
          }
        });
      }
      DefaultMediaSourceFactory msf = cbrMode
          ? new DefaultMediaSourceFactory(finalFactory, new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
          : new DefaultMediaSourceFactory(finalFactory);
      player = new ExoPlayer.Builder(appContext)
          .setMediaSourceFactory(msf)
          .build();
      // Keep the CPU/network awake while playing so audio continues with the screen off or the app in the background.
      try { player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK); } catch (Throwable ignored) {}
      player.addListener(new Player.Listener() {
        @Override public void onPlaybackStateChanged(int playbackState) {
          buffering = playbackState == Player.STATE_BUFFERING;
          if (playbackState == Player.STATE_READY) onTsReady();
          if (playbackState == Player.STATE_ENDED) stopKeepAlive();
          notifyState();
        }
        @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
          notifyState();
        }
        @Override public void onIsPlayingChanged(boolean isPlaying) {
          playing = isPlaying;
          if (isPlaying && player != null) attachVolumeBoost(player.getAudioSessionId());
          else releaseVolumeBoost();
          notifyState();
        }
        @Override public void onPlayerError(PlaybackException error) {
          if (tsFile != null && tsOffset.get() > 0 && player != null) {
            // Could not parse from the chosen offset: fall back to playing from the start.
            tsOffset.set(0); seekBaseMs = 0; pendingTargetMs = -1;
            try { player.setMediaItem(MediaItem.fromUri(tsUrl)); player.prepare(); player.play(); return; } catch (Exception ignored) {}
          }
          String detail = error.getErrorCodeName();
          Toast.makeText(appContext, "Không phát được stream (" + detail + "). URL có thể đã đổi hoặc máy chủ từ chối kết nối.", Toast.LENGTH_LONG).show();
          stop();
        }
      });
      player.setMediaItem(MediaItem.fromUri(s.url));
      player.prepare();
      player.play();
      startKeepAlive(s.name);
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
      loudnessEnhancer.setTargetGain(boost());
      loudnessEnhancer.setEnabled(true);
      return;
    } catch (Exception | LinkageError ignored) {
      if (loudnessEnhancer != null) { try { loudnessEnhancer.release(); } catch (Exception ignoredAgain) {} loudnessEnhancer = null; }
    }
    try {
      fallbackEqualizer = new Equalizer(0, sessionId);
      short[] range = fallbackEqualizer.getBandLevelRange();
      short boost = (short) Math.min(boost(), range[1]);
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

  /** Playback controls for a local recording or radio stream. */
  public static boolean hasPlayer() { return player != null; }
  public static long getCurrentPositionMs() {
    try {
      if (player == null) return 0L;
      long p = Math.max(0L, player.getCurrentPosition());
      if (tsFile != null) {
        p += seekBaseMs;
        if (totalDurationMs > 0) p = Math.min(p, totalDurationMs);
      }
      return p;
    } catch (Exception e) { return 0L; }
  }
  public static long getDurationMs() {
    try {
      if (player == null) return 0L;
      long d = player.getDuration();
      boolean valid = !(d == androidx.media3.common.C.TIME_UNSET || d <= 0);
      if (tsFile != null) {
        if (idxReady && idxTotalMs > 0) return idxTotalMs;
        if (totalDurationMs <= 0) {
          if (valid && tsOffset.get() == 0 && pendingTargetMs < 0) totalDurationMs = d;
          else if (!scanTried) { scanTried = true; totalDurationMs = scanDurationMs(tsFile, tsOrigin); }
        }
        if (totalDurationMs > 0) return totalDurationMs;
        return valid ? d + seekBaseMs : 0L;
      }
      return valid ? d : 0L;
    } catch (Exception e) { return 0L; }
  }
  public static boolean isReady() {
    try { return player != null && player.getPlaybackState() == Player.STATE_READY; }
    catch (Exception e) { return false; }
  }
  /** True once the current file/stream has played to its end. */
  public static boolean isEnded() {
    try { return player != null && player.getPlaybackState() == Player.STATE_ENDED; } catch (Exception e) { return false; }
  }
  public static void resume() {
    try {
      if (player == null) return;
      if (player.getPlaybackState() == Player.STATE_ENDED) {
        // Finished: pressing play replays the file from the very beginning.
        startKeepAlive(currentStationName);
        if (tsFile != null && tsOffset.get() > 0) { refineTries = 0; restartTsAt(0L, 0L, true); return; }
        seekBaseMs = 0; pendingTargetMs = -1;
        player.seekTo(0);
      }
      player.play();
    } catch (Exception ignored) {}
  }
  public static void pause() { try { if (player != null) player.pause(); } catch (Exception ignored) {} }
  public static boolean isCurrentMediaSeekable() {
    try { return player != null && player.isCurrentMediaItemSeekable(); }
    catch (Exception e) { return false; }
  }
  public static void seekToPosition(long positionMs) {
    try {
      if (player == null) return;
      long target = Math.max(0L, positionMs);
      if (tsFile != null && !(genericRestart && player.isCurrentMediaItemSeekable() && tsOffset.get() == 0)) {
        long total = getDurationMs();
        if (total > 0L && genericRestart) {
          target = Math.min(target, Math.max(0L, total - 1500L));
          refineTries = 0;
          long off = (long) ((double) target / (double) total * (double) tsLength);
          debugToast("Tua mo-lai " + (target / 1000L) + "s / " + (total / 1000L) + "s | " + diagInfo, true);
          restartTsAt(target, off, false);
          return;
        }
        if (total > 0L) {
          target = Math.min(target, Math.max(0L, total - 1500L));
          refineTries = 0;
          long[] ib = idxByte, im = idxMs;
          if (idxReady && ib != null && im != null && ib.length > 0) {
            int lo = 0, hi = im.length - 1;
            while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (im[mid] <= target) lo = mid; else hi = mid - 1; }
            debugToast("Tua TS (index) " + (target / 1000L) + "s -> " + (im[lo] / 1000L) + "s / " + (total / 1000L) + "s");
            restartTsAt(im[lo], ib[lo], true);
          } else {
            debugToast("Tua TS (uoc luong) " + (target / 1000L) + "s / " + (total / 1000L) + "s");
            long off = tsOrigin + (long) ((double) target / (double) total * (double) (tsLength - tsOrigin));
            restartTsAt(target, off, false);
          }
          return;
        }
      }
      long duration = getDurationMs();
      if (duration > 0L) target = Math.min(target, duration);
      debugToast((cbrMode ? "Tua CBR " : "Tua thuong ") + (target / 1000L) + "s / " + (duration / 1000L) + "s, seekable=" + player.isCurrentMediaItemSeekable() + " | " + diagInfo, true);
      player.seekTo(target);
    } catch (Exception ignored) {}
  }
  private static void debugToast(String m) { debugToast(m, false); }
  private static final boolean DEBUG_TOASTS = false;
  private static void debugToast(String m, boolean longToast) {
    if (!DEBUG_TOASTS) return;
    try { if (appContext != null) Toast.makeText(appContext, m, longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
  }
  private static String headHex(File f) {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      byte[] b = new byte[8]; int n = r.read(b); StringBuilder sb = new StringBuilder();
      for (int i = 0; i < n; i++) sb.append(String.format("%02X", b[i] & 0xFF));
      return sb.toString();
    } catch (Exception e) { return "?"; }
  }
  /** True if a duration is believable for this file size (radio audio is roughly 16..512 kbit/s). */
  private static boolean plausibleDuration(long ms, long size) {
    if (ms <= 0 || size <= 0) return false;
    double kbps = size * 8.0 / ms; // bits per ms == kbit/s
    return kbps >= 16.0 && kbps <= 512.0;
  }

  /**
   * Duration of a recording file in ms (0 if unknown). Blocking: call from a background thread.
   * Tries the PCR clock (TS), a full ADTS-AAC frame count, then Android's media reader, and only accepts a
   * result that matches the file size at a sensible bitrate.
   */
  static long fileDurationMs(File f) {
    long size = f.length();
    int origin = -1;
    try { origin = findGrid(f); } catch (Throwable ignored) {}
    if (origin >= 0) {
      try { long d = scanTsTotalMs(f, origin); if (plausibleDuration(d, size)) return d; } catch (Throwable ignored) {}
    } else {
      try { long d = scanAdtsMs(f); if (plausibleDuration(d, size)) return d; } catch (Throwable ignored) {}
    }
    android.media.MediaMetadataRetriever r = null;
    try {
      r = new android.media.MediaMetadataRetriever();
      r.setDataSource(f.getAbsolutePath());
      String v = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
      if (v != null) { long d = Long.parseLong(v); if (plausibleDuration(d, size)) return d; }
    } catch (Throwable ignored) {
    } finally {
      try { if (r != null) r.release(); } catch (Throwable ignored) {}
    }
    return 0L;
  }

  /** Counts ADTS AAC frames through the whole file (skipping ID3 tags in between): 1024 samples per frame. */
  private static long scanAdtsMs(File f) {
    final int[] SR = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      long len = r.length();
      byte[] win = new byte[4 << 20];
      long winStart = -1; int winLen = 0;
      long pos = 0; long frames = 0; double ms = 0;
      while (pos + 7 <= len) {
        int need = (int) Math.min(10L, len - pos);
        if (winStart < 0 || pos < winStart || pos + need > winStart + winLen) {
          r.seek(pos);
          int got = 0;
          while (got < win.length) { int k = r.read(win, got, win.length - got); if (k < 0) break; got += k; }
          winStart = pos; winLen = got;
          if (got < need) break;
        }
        int o = (int) (pos - winStart);
        int b0 = win[o] & 0xFF, b1 = win[o + 1] & 0xFF;
        if (b0 == 0xFF && (b1 & 0xF6) == 0xF0 && need >= 7) {
          int sri = (win[o + 2] >> 2) & 0xF;
          int flen = ((win[o + 3] & 3) << 11) | ((win[o + 4] & 0xFF) << 3) | ((win[o + 5] & 0xE0) >> 5);
          if (sri < 13 && flen >= 7) { ms += 1024000.0 / SR[sri]; frames++; pos += flen; continue; }
        } else if (b0 == 'I' && b1 == 'D' && need >= 10 && win[o + 2] == '3') {
          long size = ((win[o + 6] & 0x7FL) << 21) | ((win[o + 7] & 0x7FL) << 14) | ((win[o + 8] & 0x7FL) << 7) | (win[o + 9] & 0x7FL);
          pos += 10 + size; continue;
        }
        pos++;
      }
      return frames >= 20 ? (long) ms : 0L;
    } catch (Exception e) { return 0L; }
  }
  /** Total playing time of a TS file from its PCR clock (jumps/resets count as 0). */
  private static long scanTsTotalMs(File f, int origin) {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      final long MASK = (1L << 33) - 1;
      byte[] buf = new byte[188 * 4000];
      r.seek(origin);
      int pid = -1; long lastPcr = -1, ticks = 0;
      while (true) {
        int got = 0;
        while (got < buf.length) { int k = r.read(buf, got, buf.length - got); if (k < 0) break; got += k; }
        if (got < 188) break;
        for (int p = 0; p + 188 <= got; p += 188) {
          if (buf[p] != 0x47) continue;
          int afc = (buf[p + 3] >> 4) & 3;
          if ((afc & 2) == 0 || (buf[p + 4] & 0xFF) < 7 || (buf[p + 5] & 0x10) == 0) continue;
          int ppid = ((buf[p + 1] & 0x1F) << 8) | (buf[p + 2] & 0xFF);
          if (pid < 0) pid = ppid;
          if (ppid != pid) continue;
          long pcr = pcr33(buf, p + 6);
          if (lastPcr >= 0) { long d = (pcr - lastPcr) & MASK; if (d <= 2700000L) ticks += d; }
          lastPcr = pcr;
        }
        if (got < buf.length) break;
      }
      return ticks / 90L;
    } catch (Exception e) { return 0L; }
  }

  /** Scan the whole TS once: cumulative PCR time at every PAT packet (jumps/resets in PCR count as 0). */
  private static void buildTsIndex(File f, int origin, int gen) {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      final long MASK = (1L << 33) - 1;
      byte[] buf = new byte[188 * 4000];
      long[] bytes = new long[1024], ms = new long[1024]; int cnt = 0;
      long fileOff = origin; r.seek(origin);
      int pid = -1; long lastPcr = -1, ticks = 0, lastMarkMs = -100000;
      while (true) {
        int got = 0;
        while (got < buf.length) { int k = r.read(buf, got, buf.length - got); if (k < 0) break; got += k; }
        if (got < 188) break;
        if (gen != playGen) return;
        for (int p = 0; p + 188 <= got; p += 188) {
          if (buf[p] != 0x47) continue;
          int ppid = ((buf[p + 1] & 0x1F) << 8) | (buf[p + 2] & 0xFF);
          int afc = (buf[p + 3] >> 4) & 3;
          if ((afc & 2) != 0 && (buf[p + 4] & 0xFF) >= 7 && (buf[p + 5] & 0x10) != 0) {
            if (pid < 0) pid = ppid;
            if (ppid == pid) {
              long pcr = pcr33(buf, p + 6);
              if (lastPcr >= 0) { long d = (pcr - lastPcr) & MASK; if (d <= 2700000L) ticks += d; }
              lastPcr = pcr;
            }
          }
          if (ppid == 0 && (buf[p + 1] & 0x40) != 0) {
            long curMs = ticks / 90L;
            if (cnt == 0 || curMs >= lastMarkMs + 1000L) {
              if (cnt == bytes.length) { bytes = java.util.Arrays.copyOf(bytes, cnt * 2); ms = java.util.Arrays.copyOf(ms, cnt * 2); }
              bytes[cnt] = fileOff + p; ms[cnt] = curMs; cnt++; lastMarkMs = curMs;
            }
          }
        }
        fileOff += got;
        if (got < buf.length) break;
      }
      if (gen != playGen || cnt == 0 || pid < 0) return;
      idxByte = java.util.Arrays.copyOf(bytes, cnt);
      idxMs = java.util.Arrays.copyOf(ms, cnt);
      idxTotalMs = ticks / 90L;
      idxReady = true;
    } catch (Exception ignored) {}
  }
  public static void seekToStart() {
    try {
      if (player == null) return;
      if (tsFile != null && tsOffset.get() > 0) { refineTries = 0; restartTsAt(0L, 0L, true); return; }
      player.seekTo(0); player.play();
    } catch (Exception ignored) {}
  }

  /** Re-open the TS file from a byte offset aligned to a 188-byte packet. */
  private static void restartTsAt(long targetMs, long offset, boolean exact) {
    if (player == null || tsFile == null) return;
    long maxOff = tsOrigin + Math.max(0L, ((tsLength - tsOrigin) / pktAlign) - (pktAlign == 1 ? 4096L : 200L)) * pktAlign;
    long o = Math.max(tsOrigin, Math.min(offset, maxOff));
    o = tsOrigin + ((o - tsOrigin) / pktAlign) * pktAlign;
    if (o <= tsOrigin) { tsOffset.set(0); seekBaseMs = 0; pendingTargetMs = -1; }
    else { tsOffset.set(o); seekBaseMs = targetMs; pendingTargetMs = exact ? -1 : targetMs; }
    player.setMediaItem(MediaItem.fromUri(tsUrl));
    player.prepare();
    player.play();
  }

  /** After a restart, measure where we really landed and correct the clock (or retry once or twice). */
  private static void onTsReady() {
    if (tsFile == null || player == null) return;
    long d = player.getDuration();
    boolean valid = !(d == androidx.media3.common.C.TIME_UNSET || d <= 0);
    if (pendingTargetMs < 0) {
      if (totalDurationMs <= 0 && valid && tsOffset.get() == 0) totalDurationMs = d;
      return;
    }
    if (totalDurationMs <= 0 || !valid) { seekBaseMs = pendingTargetMs; pendingTargetMs = -1; return; }
    final long target = pendingTargetMs;
    long actualBase = Math.max(0L, totalDurationMs - d);
    long err = target - actualBase;
    if (Math.abs(err) > 2500L && refineTries < 2) {
      refineTries++;
      long off = Math.max(tsOffset.get(), (long) tsOrigin);
      double bytesPerMs = (double) (tsLength - off) / (double) d;
      final long newOff = off + (long) (err * bytesPerMs);
      mainHandler.post(new Runnable() { @Override public void run() { try { restartTsAt(target, newOff, false); } catch (Exception ignored) {} } });
    } else {
      seekBaseMs = actualBase;
      pendingTargetMs = -1;
    }
  }

  // ---- MPEG-TS helpers ----
  private static int findGrid(File f) {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      byte[] b = new byte[4096];
      int n = r.read(b);
      for (int i = 0; i < 188 && i + 376 < n; i++) {
        if (b[i] == 0x47 && b[i + 188] == 0x47 && b[i + 376] == 0x47) return i;
      }
    } catch (Exception ignored) {}
    return -1;
  }
  private static long pcr33(byte[] b, int o) {
    return ((b[o] & 0xFFL) << 25) | ((b[o + 1] & 0xFFL) << 17) | ((b[o + 2] & 0xFFL) << 9)
        | ((b[o + 3] & 0xFFL) << 1) | ((b[o + 4] & 0x80L) >> 7);
  }
  /** Duration from first/last PCR of the file (same method ExoPlayer uses), 0 if unknown. */
  private static long scanDurationMs(File f, int origin) {
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      byte[] buf = new byte[188 * 2000];
      r.seek(origin);
      int n = r.read(buf);
      int pid = -1; long first = 0;
      for (int p = 0; p + 188 <= n; p += 188) {
        if (buf[p] != 0x47 || ((buf[p + 3] >> 4) & 2) == 0 || (buf[p + 4] & 0xFF) < 7 || (buf[p + 5] & 0x10) == 0) continue;
        pid = ((buf[p + 1] & 0x1F) << 8) | (buf[p + 2] & 0xFF);
        first = pcr33(buf, p + 6);
        break;
      }
      if (pid < 0) return 0L;
      long packets = (f.length() - origin) / 188L;
      long startPkt = Math.max(0L, packets - 2000L);
      r.seek(origin + startPkt * 188L);
      n = r.read(buf);
      for (int p = (n / 188 - 1) * 188; p >= 0; p -= 188) {
        if (buf[p] != 0x47 || ((buf[p + 3] >> 4) & 2) == 0 || (buf[p + 4] & 0xFF) < 7 || (buf[p + 5] & 0x10) == 0) continue;
        if ((((buf[p + 1] & 0x1F) << 8) | (buf[p + 2] & 0xFF)) != pid) continue;
        long diff = (pcr33(buf, p + 6) - first) & ((1L << 33) - 1);
        return diff * 1000L / 90000L;
      }
    } catch (Exception ignored) {}
    return 0L;
  }

  public static void toggle(Context c, Station s) {
    if (player == null || !isCurrentStation(s.name)) { play(c, s); return; }
    try {
      if (player.isPlaying() || player.getPlayWhenReady()) player.pause();
      else player.play();
    } catch (Exception e) { play(c, s); }
  }

  /** Used when playback is stopped from outside the UI (notification Stop / app swiped away): keep the "nghe dở" position of a recording. */
  static void saveLocalResumePosition(Context c) {
    try {
      if (player == null || !localPlay || currentStationName == null || player.getPlaybackState() == Player.STATE_ENDED) return;
      long pos = getCurrentPositionMs();
      android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences("radio", Context.MODE_PRIVATE);
      if (pos < 1000L) sp.edit().remove("resume_" + currentStationName).apply();
      else sp.edit().putLong("resume_" + currentStationName, pos).apply();
    } catch (Exception ignored) {}
  }

  private static void startKeepAlive(String name) {
    try {
      if (appContext == null) return;
      Intent i = new Intent(appContext, PlaybackKeepAliveService.class);
      i.putExtra("name", name);
      if (android.os.Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(i); else appContext.startService(i);
    } catch (Exception ignored) {}
  }
  private static void stopKeepAlive() {
    try { if (appContext != null) appContext.stopService(new Intent(appContext, PlaybackKeepAliveService.class)); } catch (Exception ignored) {}
  }

  public static void stop() {
    stopKeepAlive();
    releaseVolumeBoost();
    try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
    player = null;
    tsFile = null; tsUrl = null; tsOffset.set(0); seekBaseMs = 0; pendingTargetMs = -1;
    currentStationName = null;
    playing = false;
    buffering = false;
    notifyState();
  }
}
