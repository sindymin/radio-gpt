package com.vietradio.android6;
import java.util.ArrayList;
import java.util.List;
public final class StationRepository {
  private StationRepository() {}
  public static List<Station> all() {
    List<Station> s = new ArrayList<>();
    s.add(new Station("VOH FM 99.9", "https://strmx.voh.com.vn/radio/channel3/playlist.m3u8", "https://voh.com.vn/"));
    s.add(new Station("VOH FM 87.7", "https://strm.voh.com.vn/radio/channel5/playlist.m3u8", "https://voh.com.vn/"));
    s.add(new Station("VOH AM 610", "https://strmx.voh.com.vn/radio/channel2/playlist.m3u8", "https://voh.com.vn/"));
    s.add(new Station("VOH FM 95.6", "", "https://voh.com.vn/"));
    s.add(new Station("VOH-M FM 92 - 92.5", "", "https://voh.com.vn/"));
    s.add(new Station("Đài Bình Dương", "", "https://btdradio.vn/"));
    s.add(new Station("VOV1 - Thời sự", "", "https://vov1.vov.vn/"));
    s.add(new Station("VOV2 - Văn hóa", "", "https://vov2.vov.vn/"));
    s.add(new Station("VOV3 - Âm nhạc", "", "https://vov3.vov.vn/"));
    return s;
  }
}
