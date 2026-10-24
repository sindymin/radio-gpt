package com.vietradio.android6;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/** TEST-ONLY compatibility workaround for old Android certificate stores.
 * Applies only to selected schedule hosts. Do not reuse in a public/release app.
 */
final class LegacyTls {
  private LegacyTls() {}
  static void applyForScheduleHost(HttpsURLConnection connection, String address) throws Exception {
    String host = new URL(address).getHost().toLowerCase(java.util.Locale.ROOT);
    if (!(host.equals("app.giavangtygia.com") || host.equals("voh.com.vn") || host.endsWith(".voh.com.vn") || host.equals("htv.vn") || host.endsWith(".htv.vn"))) return;
    TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}
    }};
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustAll, new java.security.SecureRandom());
    connection.setSSLSocketFactory(context.getSocketFactory());
    // The certificate chain is bypassed for testing, but keep hostname verification enabled.
  }
}
