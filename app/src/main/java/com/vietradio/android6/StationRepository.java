package com.vietradio.android6;

import java.util.ArrayList;
import java.util.List;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Station links transcribed from the Radio Viet Nam Mobi AI web bundle supplied by the user.
 * A URL appearing in that app's source does not guarantee that the stream is still online.
 */
public final class StationRepository {
  private StationRepository() {}

  /** Merge live API data into the bundled fallback list. Existing station names and
   * websites are preserved; a non-empty fresh stream URL replaces the old URL. */
  public static List<Station> merge(List<Station> bundled, List<Station> remote) {
    List<Station> result = new ArrayList<>();
    if (bundled != null) result.addAll(bundled);
    if (remote == null) return result;
    for (Station live : remote) {
      if (live == null || live.name == null || live.name.trim().isEmpty()) continue;
      int found = findByName(result, live.name);
      if (found < 0) found = findVohAlias(result, live.name);
      if (found >= 0) {
        Station old = result.get(found);
        // Keep the known direct VOH stream URLs in the bundled list. The third-party
        // API can return stale VOH URLs or URLs served by a TLS host Android 6 rejects.
        boolean isVoh = normalizeName(old.name).contains("voh");
        String url = isVoh ? old.url : (live.url != null && !live.url.trim().isEmpty() ? live.url : old.url);
        String website = old.website != null && !old.website.trim().isEmpty() ? old.website : live.website;
        result.set(found, new Station(old.name, url, website));
      } else {
        result.add(live);
      }
    }
    return result;
  }

  private static int findByName(List<Station> stations, String name) {
    String key = normalizeName(name);
    for (int i = 0; i < stations.size(); i++) {
      if (normalizeName(stations.get(i).name).equals(key)) return i;
    }
    return -1;
  }

  private static int findVohAlias(List<Station> stations, String name) {
    String key = vohFrequencyKey(name);
    if (key.isEmpty()) return -1;
    for (int i = 0; i < stations.size(); i++) {
      if (vohFrequencyKey(stations.get(i).name).equals(key)) return i;
    }
    return -1;
  }

  private static String vohFrequencyKey(String value) {
    String n = normalizeName(value);
    if (!n.contains("voh")) return "";
    if (n.contains("999")) return "voh999";
    if (n.contains("956")) return "voh956";
    if (n.contains("877")) return "voh877";
    if (n.contains("610")) return "voh610";
    if (n.contains("925") || n.contains("92")) return "voh925";
    return "";
  }

  private static String normalizeName(String value) {
    if (value == null) return "";
    String noMarks = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    return noMarks.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  public static List<Station> all() {
    List<Station> s = new ArrayList<>();

    // VOH official stream host URLs. Avoid the third-party liveapi.giavangtygia.com host
    // for playback because its TLS certificate chain is not trusted on some Android 6 devices.
    s.add(new Station("VOH FM 99.9 MHz", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-voh99.m3u8", "https://voh.com.vn/radio-kenh-fm-999-fm999mhz.html"));
    s.add(new Station("VOH FM 95.6 MHz", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-voh956.m3u8", "https://voh.com.vn/radio-kenh-fm-956-fm956mhz.html"));
    s.add(new Station("VOH FM 87.7 MHz", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-voh877.m3u8", "https://voh.com.vn/radio-kenh-fm-877-fm877mhz.html"));
    s.add(new Station("VOH AM 610 kHz", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-voh610.m3u8", "https://voh.com.vn/radio-kenh-am-610-am610khz.html"));
    s.add(new Station("VOH M FM 92–92.5 MHz", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-voh925.m3u8", "https://htv.vn/radio.htm"));

    // VOV national channels
    s.add(new Station("VOV1 – Thời sự", "https://audio-lss.vov.vn/live/vov1.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV2 – Văn hóa và đời sống", "https://audio-lss.vov.vn/live/vov2.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV3 – Âm nhạc", "https://audio-lss.vov.vn/live/vov3.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV4 – Phát thanh dân tộc", "https://audio-lss.vov.vn/live/vov4.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV5 – Phát thanh đối ngoại", "https://audio-lss.vov.vn/live/vov5.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV24/7 – Tiếng Anh", "https://audio-lss.vov.vn/live/vov24_7.m3u8", "https://vov.vn/"));
    s.add(new Station("VOV Sức khỏe – FM 89", "https://stream.vovmedia.vn/vov89", "https://vov.vn/"));

    // VOV traffic channels
    s.add(new Station("VOV Giao thông Hà Nội", "https://play.vovgiaothong.vn/live/gthn/playlist.m3u8", "https://vovgiaothong.vn/"));
    s.add(new Station("VOV Giao thông TP.HCM", "https://play.vovgiaothong.vn/live/gthcm/playlist.m3u8", "https://vovgiaothong.vn/"));
    s.add(new Station("VOV Giao thông Duyên Hải", "https://play.vovgiaothong.vn/live/duyenhai/playlist.m3u8", "https://vovgiaothong.vn/"));
    s.add(new Station("VOV Mekong", "https://play.vovgiaothong.vn/live/mekong/playlist.m3u8", "https://vovgiaothong.vn/"));

    // Music / specialty streams
    s.add(new Station("M Radio Giải Trí", "http://stream.zeno.fm/n6pvyrmoaemtv", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Radio Nhạc Vàng Xưa", "http://stream.zeno.fm/riluleoa8vdtv", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Radio Nhạc Cải Lương", "http://stream.zeno.fm/3jfzgwv7j2utv", "https://aimobi.pages.dev/radiovn/index.html"));

    // Northern and regional stations
    s.add(new Station("Hà Nội FM 90 MHz", "http://14.162.146.90:8000/HANOI90", "https://hanoitv.vn/"));
    s.add(new Station("Hà Nội FM 96 MHz", "https://cloudcdnfm90.tek4tv.vn/HANOI96/stream.m3u8", "https://hanoitv.vn/"));
    s.add(new Station("Bắc Ninh", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-bacninh.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Hải Phòng 1", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-haiphong1.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Hải Phòng 2", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-haiphong2.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Hưng Yên", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-hungyen.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Thanh Hóa", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-thanhhoa.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Sơn La FM 96 MHz", "https://cdn.sonlatv.vn/live/285bfb76d0d7a3c4615b853fe7a698e150c/playlist.m3u8", "https://sonlatv.vn/"));
    s.add(new Station("Thái Nguyên", "https://radio.thainguyentv.vn/hls/livestream.m3u8", "https://thainguyentv.vn/"));
    s.add(new Station("Phú Thọ", "https://cdn.3ssoft.vn/audio/phuthotv/phuthotv/index.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));

    // Central and southern provincial stations
    s.add(new Station("An Giang", "https://tv.angiangtv.vn/live/kgtv3/kgtv3.m3u8", "https://angiangtv.vn/"));
    s.add(new Station("Bình Dương FM 92.5 MHz", "http://sohoa.truyenthanhthongminh.vn/sohoabinhduong", "https://btdradio.vn/"));
    s.add(new Station("Bình Định", "http://14.224.130.186:8000/binhdinh", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Bình Phước", "http://sohoa.truyenthanhthongminh.vn/sohoabinhphuoc", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Cần Thơ", "http://113.161.205.237:1935/live/radio/playlist.m3u8", "https://canthotv.vn/"));
    s.add(new Station("Đắk Lắk", "http://sh.truyenthanhthongminh.vn/daklak_sohoa1", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Đắk Nông (nguồn đang được ứng dụng Mobi AI gán chung)", "http://sohoa.truyenthanhthongminh.vn/lamdong", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Đà Nẵng", "https://live.mediatech.vn/live/2858a998b1c7fbf4522accd5554588ceae3/playlist.m3u8", "https://danangtv.vn/"));
    s.add(new Station("Đồng Nai", "http://118.107.85.5:1935/radio/dongnai_aac/playlist.m3u8", "https://dnrt.vn/"));
    s.add(new Station("Đồng Tháp", "https://618b88f69e53b.streamlock.net/THDTR/thdtradio/playlist.m3u8", "https://thdt.vn/"));
    s.add(new Station("Gia Lai", "http://sh.truyenthanhthongminh.vn/gialaitv", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Hà Giang", "https://luuchieu.truyenhinhso.vn/media/live/EDBFCBB8687FE7589B3CDFEB/index.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Hà Nam", "http://sohoa.truyenthanhthongminh.vn/sohoatinhhanam", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Khánh Hòa – kênh 1", "https://cdnbaokhanhhoa.com/live/fdb4128cecf1.m3u8?vhost=audio", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Khánh Hòa – kênh 2", "https://cdnbaokhanhhoa.com/live/feb79f5c374d.m3u8?vhost=audio", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Lâm Đồng", "http://sohoa.truyenthanhthongminh.vn/lamdong", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Lào Cai", "http://sohoa.truyenthanhthongminh.vn/sohoatinhlaocai", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nam Định", "http://sohoa.truyenthanhthongminh.vn/sohoanamdinh", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Quảng Ninh – kênh 1", "https://live.baoquangninh.vn/qtvlive/qnr1.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Quảng Ninh – kênh 2", "https://live.baoquangninh.vn/qtvlive/qnr2.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Quảng Trị", "https://tructuyen.baoquangtri.vn:5443/LiveApp/streams/20VcR6dL2B9Krwob13486956694488871.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Thừa Thiên Huế", "https://live.huetv.com.vn/Radio-online/playlist.m3u8", "https://huetv.vn/"));
    s.add(new Station("Trà Vinh", "http://sohoa.truyenthanhthongminh.vn/tinhtravinh", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Vĩnh Long", "https://liveapi.giavangtygia.com/api/v3/radios/phatthanh/radio-vinhlong.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));

    // More regional URLs found in the supplied bundle. Some source mappings reuse URLs;
    // they are included as found and should be verified on-device before relying on them.
    s.add(new Station("Nguồn radio Mediatech 1", "https://live.mediatech.vn/live/2851dd3d9016ac74d84b0c4a7a659f76891/playlist.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nguồn radio Mediatech 2", "https://live.mediatech.vn/live/2853bb18b89d074498c81d7a0deb32a3755/playlist.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nguồn radio Mediatech 3", "https://live.mediatech.vn/live/28597f8fd7ea5064d0f84ab00b3699dfd86/chunklist.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nguồn radio Mediatech 4", "https://live.mediatech.vn/live/285bfb777e354ec43038aa68950adbac3a4/chunklist.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nguồn radio HTTV", "https://cohauw9bgpvod.vcdn.cloud/httv1/index.m3u8", "https://aimobi.pages.dev/radiovn/index.html"));
    s.add(new Station("Nguồn radio Cà Mau / Bạc Liêu", "https://tv.ctvcamau.vn/live/radio/radio.m3u8", "https://ctvcamau.vn/"));
    s.add(new Station("Nguồn radio Huế / khu vực", "https://live.huetv.com.vn/Radio-online/playlist.m3u8", "https://huetv.vn/"));

    return s;
  }
}
