package com.vietradio.android6;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

/** Background stream recorder. Silent mode never creates a MediaPlayer, so it produces no speaker audio. */
public class RadioRecordService extends Service {
  private volatile boolean running;
  private PowerManager.WakeLock wakeLock;
  @Override public int onStartCommand(Intent i,int flags,int id) {
    if(i==null){stopSelf();return START_NOT_STICKY;}
    String url=i.getStringExtra("url"), name=i.getStringExtra("name");
    boolean silent=i.getBooleanExtra("silent",true);
    int duration=i.getIntExtra("durationMinutes",0);
    if(url==null||url.trim().isEmpty()){stopSelf();return START_NOT_STICKY;}
    createNotification(silent); running=true;
    try { PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE); wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VietRadio:Recorder"); wakeLock.acquire((duration>0?duration*60L*1000L:6L*60L*60L*1000L)); } catch(Exception ignored){}
    final String stream=url, station=name; final int minutes=duration;
    new Thread(()->record(stream,station,minutes),"RadioRecorder").start();
    return START_NOT_STICKY;
  }
  private void record(String stream,String name,int minutes) {
    File dir=new File(Environment.getExternalStorageDirectory(),"RadioRecordings"); if(!dir.exists())dir.mkdirs();
    String safe=(name==null?"Radio":name).replaceAll("[^a-zA-Z0-9À-ỹ _-]","_");
    String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
    File out=new File(dir,safe+"_"+stamp+(stream.toLowerCase(Locale.US).contains(".m3u8")?".ts":".radio"));
    long end=minutes>0?System.currentTimeMillis()+minutes*60L*1000L:Long.MAX_VALUE;
    try { URL u=new URL(stream); if(stream.toLowerCase(Locale.US).contains(".m3u8")) recordHls(u,out,end); else recordContinuous(u,out,end); }
    catch(Exception e){android.util.Log.e("VietRadio","Ghi âm thất bại",e);}
    finally { running=false; try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){} stopForeground(true); stopSelf(); }
  }
  private String getText(URL u) throws Exception {
    HttpURLConnection c=(HttpURLConnection)u.openConnection(); c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","VietRadioAndroid/1.0");
    try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return new String(b.toByteArray(),"UTF-8");} finally{c.disconnect();}
  }
  private void recordHls(URL playlist,File out,long end) throws Exception {
    Set<String> seen=new HashSet<>();
    try(FileOutputStream dest=new FileOutputStream(out,true)) {
      while(running&&System.currentTimeMillis()<end) {
        String body=getText(playlist); String[] lines=body.split("\\r?\\n");
        boolean master=false; for(String l:lines)if(l.trim().startsWith("#EXT-X-STREAM-INF"))master=true;
        if(master){URL variant=null;for(int i=0;i<lines.length;i++)if(lines[i].trim().startsWith("#EXT-X-STREAM-INF")){for(int j=i+1;j<lines.length;j++)if(!lines[j].trim().isEmpty()&&!lines[j].trim().startsWith("#")){variant=new URL(playlist,lines[j].trim());break;}break;}if(variant==null)throw new IOException("HLS playlist không có stream con");playlist=variant;continue;}
        boolean got=false;
        for(String l:lines){String item=l.trim();if(item.isEmpty()||item.startsWith("#"))continue;URL seg=new URL(playlist,item);String key=seg.toString();if(!seen.add(key))continue;
          HttpURLConnection c=(HttpURLConnection)seg.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestProperty("User-Agent","VietRadioAndroid/1.0");
          try(InputStream in=new BufferedInputStream(c.getInputStream())){byte[] buf=new byte[8192];int n;while(running&&(n=in.read(buf))!=-1)dest.write(buf,0,n);dest.flush();}finally{c.disconnect();}got=true;if(!running||System.currentTimeMillis()>=end)break;
        }
        if(!got)Thread.sleep(1200);
      }
    }
  }
  private void recordContinuous(URL url,File out,long end) throws Exception {
    HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Icy-MetaData","0");c.connect();
    try(InputStream in=new BufferedInputStream(c.getInputStream());FileOutputStream f=new FileOutputStream(out)){byte[] buf=new byte[8192];int n;while(running&&System.currentTimeMillis()<end&&(n=in.read(buf))!=-1)f.write(buf,0,n);}finally{c.disconnect();}
  }
  private void createNotification(boolean silent){
    if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel("radio_record","Ghi âm radio",NotificationManager.IMPORTANCE_LOW);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);}
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"radio_record"):new Notification.Builder(this);
    b.setContentTitle("Radio Việt Nam").setContentText(silent?"Đang ghi âm nền — không phát ra loa":"Đang ghi âm radio").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true);startForeground(41,b.build());
  }
  @Override public void onDestroy(){running=false;try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}super.onDestroy();}
  @Override public IBinder onBind(Intent i){return null;}
}
