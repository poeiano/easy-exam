package local.offerin.practice;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

// Visible foreground status raises process priority while the companion link is in use.
public final class ConnectionService extends Service {
    public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("companion-link","电脑连接",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"companion-link").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("easy exam 电脑连接运行中").setContentText("保持连接；网络中断后自动重试。点击返回工作台。").setContentIntent(open).setOngoing(true).build();
        startForeground(31,notification);
    }
    public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
    public IBinder onBind(Intent intent){return null;}
}

