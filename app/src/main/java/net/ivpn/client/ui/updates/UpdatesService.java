package net.ivpn.client.ui.updates;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import net.ivpn.client.R;
import net.ivpn.client.vpn.ServiceConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class UpdatesService extends Service implements ServiceConstants {

    public static AtomicBoolean isRunning = new AtomicBoolean(false);

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatesService.class);
    private static final String TAG = UpdatesService.class.getSimpleName();

    private NotificationManager notificationManager;

    private int notificationId;

    @Override
    public void onCreate() {
        LOGGER.info("onCreate");
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationId = ServiceConstants.UPDATE_CHANNEL.hashCode();
    }

    @Override
    public void onDestroy() {
        LOGGER.info("onDestroy");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        LOGGER.info("onStartCommand: action = " + action);
        if (action == null) {
            isRunning.set(false);
            showNotification(System.currentTimeMillis());
            return stop();
        }

        switch (action) {
            case UPDATE_PROCEED: {
                closeSystemDialogs();
                return doSendActionBroadcast(UPDATE_PROCEED);
            }
            case UPDATE_SKIP: {
                return doSendActionBroadcast(UPDATE_SKIP);
            }
            case UPDATE_SETTINGS: {
                closeSystemDialogs();
                return doSendActionBroadcast(UPDATE_SETTINGS);
            }
            case SHOW_UPDATE_NOTIFICATION: {
                isRunning.set(true);
                return show();
            }
            case CANCEL_UPDATE_NOTIFICATION: {
                isRunning.set(false);
                showNotification(System.currentTimeMillis());
                return stop();
            }
        }

        return START_STICKY;
    }

    private void closeSystemDialogs() {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        sendBroadcast(intent);
    }

    private int doSendActionBroadcast(String action) {
        Intent vpnAction = new Intent();
        vpnAction.setAction(UPDATE_NOTIFICATION_ACTION);
        vpnAction.putExtra(UPDATE_NOTIFICATION_ACTION_EXTRA, action);
        sendBroadcast(vpnAction, Manifest.permission.ACCESS_NETWORK_STATE);
        return START_NOT_STICKY;
    }

    private int show() {
        showNotification(System.currentTimeMillis());
        return START_STICKY;
    }

    private int stop() {
        stopForeground(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopSelf(STOP_FOREGROUND_REMOVE);
        } else {
            notificationManager.cancel(notificationId);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showNotification(long when) {
        Log.d(TAG, "showNotification: ");

        int iconId = R.drawable.ic_stat_name;
        String title = getTitle();
        String msg = getMessage();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ServiceConstants.UPDATE_CHANNEL);

        builder.setContentTitle(title);
        builder.setContentText(msg);
        builder.setOnlyAlertOnce(true);
        builder.setOngoing(true);

        builder.setSmallIcon(iconId);
        builder.setContentIntent(getContentIntent());
        builder.setColor(getResources().getColor(R.color.colorAccent));
        builder.setStyle(new NotificationCompat.BigTextStyle()
                .bigText(msg));

        if (when != 0) {
            builder.setWhen(when);
            builder.setShowWhen(true);
        }

        addNotificationActions(builder);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(ServiceConstants.UPDATE_CHANNEL);
        }

        builder.setTicker(msg);

        @SuppressWarnings("deprecation")
        Notification notification = builder.getNotification();

        notificationManager.notify(notificationId, notification);
        startForeground(notificationId, notification);
    }

    private String getTitle() {
        return getString(R.string.notification_update_title);
    }

    private String getMessage() {
        return getString(R.string.notification_update_message);
    }

    private PendingIntent getContentIntent() {
        Intent intent = new Intent(this, UpdatesActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return pendingIntent;
    }

    private void addNotificationActions(NotificationCompat.Builder builder) {
        addProceedAction(builder);
        addSkipAction(builder);
        addSettingsAction(builder);
    }

    private void addProceedAction(NotificationCompat.Builder builder) {
        Intent intent = new Intent(this, UpdatesService.class);
        intent.setAction(UPDATE_PROCEED);
        PendingIntent pendingIntent = PendingIntent.getService(this, IVPN_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.ic_file_download,
                getString(R.string.notification_update_proceed), pendingIntent);
    }

    private void addSkipAction(NotificationCompat.Builder builder) {
        Intent intent = new Intent(this, UpdatesService.class);
        intent.setAction(UPDATE_SKIP);
        PendingIntent pendingIntent = PendingIntent.getService(this, IVPN_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.ic_cancel,
                getString(R.string.notification_update_skip), pendingIntent);
    }

    private void addSettingsAction(NotificationCompat.Builder builder) {
        Intent intent = new Intent(this, UpdatesService.class);
        intent.setAction(UPDATE_SETTINGS);
        PendingIntent pendingIntent = PendingIntent.getService(this, IVPN_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.ic_settings,
                getString(R.string.notification_update_settings), pendingIntent);
    }
}