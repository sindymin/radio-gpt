package com.vietradio.android6;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.Gravity;

/** Displays a schedule source website inside the app. */
public class ScheduleSourceActivity extends Activity {
    private WebView webView;
    private ProgressBar progress;
    private TextView message;
    private String sourceUrl;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = "https://app.giavangtygia.com/api/schedule";
        sourceUrl = url;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(4), dp(8), dp(8));
        root.setBackgroundColor(0xFFFFFFFF);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("← Trở về");
        back.setAllCaps(false);
        bar.addView(back, new LinearLayout.LayoutParams(dp(112), dp(48)));
        TextView title = new TextView(this);
        title.setText("Nguồn lịch phát sóng");
        title.setTextSize(17);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        back.setOnClickListener(v -> finish());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        message = new TextView(this);
        message.setTextColor(0xFF222222);
        message.setTextSize(14);
        message.setPadding(dp(10), dp(8), dp(10), dp(8));
        message.setBackgroundColor(0xFFFFF3CD);
        message.setVisibility(View.GONE);
        root.addView(message, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFFFFFFF);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadsImagesAutomatically(true);
        webView.getSettings().setSupportZoom(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                message.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
            @SuppressWarnings("deprecation")
            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                progress.setVisibility(View.GONE);
                showPageError(description, failingUrl);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    showPageError(error == null ? "Lỗi tải trang" : String.valueOf(error.getDescription()), request.getUrl().toString());
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // TEST ONLY: permit an untrusted certificate chain for the exact schedule API host.
                // Still reject hostname mismatch and certificate validity-period errors.
                String failedHost = null;
                try { failedHost = Uri.parse(error.getUrl()).getHost(); } catch (Exception ignored) {}
                boolean targetHost = "app.giavangtygia.com".equalsIgnoreCase(failedHost);
                boolean identityOrDateError = error.hasError(SslError.SSL_IDMISMATCH)
                        || error.hasError(SslError.SSL_EXPIRED)
                        || error.hasError(SslError.SSL_NOTYETVALID)
                        || error.hasError(SslError.SSL_DATE_INVALID);
                if (targetHost && !identityOrDateError) {
                    handler.proceed();
                } else {
                    handler.cancel();
                    progress.setVisibility(View.GONE);
                    showPageError("Lỗi chứng chỉ SSL của nguồn lịch; kết nối đã bị chặn.", sourceUrl);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        webView.loadUrl(url);
    }

    private void showPageError(String description, String failingUrl) {
        if (message == null) return;
        message.setVisibility(View.VISIBLE);
        message.setText("Không tải được trang lịch trong WebView của điện thoại.\n" +
                "Chi tiết: " + (description == null ? "không rõ lỗi" : description) +
                "\nBạn có thể mở nguồn bằng trình duyệt ngoài hoặc cập nhật Android System WebView/Chrome.");
        // Keep the WebView visible; a real error message is preferable to an unexplained white screen.
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}
