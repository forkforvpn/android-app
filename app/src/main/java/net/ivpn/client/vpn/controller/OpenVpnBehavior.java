package net.ivpn.client.vpn.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.ivpn.client.IVPNApplication;
import net.ivpn.client.R;
import net.ivpn.client.common.pinger.OnFastestServerDetectorListener;
import net.ivpn.client.common.pinger.PingProvider;
import net.ivpn.client.common.prefs.ServerType;
import net.ivpn.client.common.prefs.ServersRepository;
import net.ivpn.client.common.prefs.Settings;
import net.ivpn.client.common.utils.DomainResolver;
import net.ivpn.client.common.utils.ToastUtil;
import net.ivpn.client.rest.data.model.Server;
import net.ivpn.client.ui.connect.ConnectionState;
import net.ivpn.client.vpn.GlobalBehaviorController;
import net.ivpn.client.vpn.OnVpnStatusChangedListener;
import net.ivpn.client.vpn.ServiceConstants;
import net.ivpn.client.vpn.VPNConnectionState;
import net.ivpn.client.vpn.openvpn.IVPNService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import de.blinkt.openvpn.core.ConnectionStatus;

import static net.ivpn.client.ui.connect.ConnectionState.CONNECTED;
import static net.ivpn.client.ui.connect.ConnectionState.CONNECTING;
import static net.ivpn.client.ui.connect.ConnectionState.DISCONNECTING;
import static net.ivpn.client.ui.connect.ConnectionState.NOT_CONNECTED;
import static net.ivpn.client.ui.connect.ConnectionState.PAUSED;
import static net.ivpn.client.ui.connect.ConnectionState.PAUSING;

public class OpenVpnBehavior implements VpnBehavior, OnVpnStatusChangedListener, ServiceConstants {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenVpnBehavior.class);
    private static final String TAG = OpenVpnBehavior.class.getSimpleName();
    private static final long COMMON_TIME_OUT = 15000L;
    private static final long PORT_CHECK_TIME_OUT = 11000L;
    private static final long NO_NETWORK_TIME_OUT = 2000L;
    private static final long TICK = 1000L;

    private ConnectionStatus status;
    private ConnectionState state;
    private VpnStateListener stateListener;
    private PauseTimer timer;
    private GlobalBehaviorController globalBehaviorController;
    private ServersRepository serversRepository;
    private Settings settings;
    private VpnBehaviorController vpnBehaviorController;
    private PingProvider pingProvider;
    private DomainResolver domainResolver;
    private BroadcastReceiver connectionStatusReceiver;
    private long connectionTime;

    private Handler handler;
    private Runnable commonRunnable = () -> {
        onTimeOut();
        reset();
    };
    private Runnable portCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (status != null && status == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET) {
                LOGGER.info("notifyAnotherPortUsedToConnect state = " + state);
                reset();
                tryAnotherPort();
            }
        }
    };
    private Runnable noNetworkRunnable = new Runnable() {
        @Override
        public void run() {
            LOGGER.info("no network runnable");
            if (stateListener != null) {
                stateListener.notifyNoNetworkConnection();
            }
            stopVpn();
            reset();
        }
    };

    @Inject
    OpenVpnBehavior(GlobalBehaviorController globalBehaviorController, ServersRepository serversRepository,
                    Settings settings, VpnBehaviorController vpnBehaviorController, PingProvider pingProvider,
                    DomainResolver domainResolver) {
        LOGGER.info("OpenVpn behaviour");
        this.globalBehaviorController = globalBehaviorController;
        this.serversRepository = serversRepository;
        this.settings = settings;
        this.vpnBehaviorController = vpnBehaviorController;
        this.pingProvider = pingProvider;
        this.domainResolver = domainResolver;
        handler = new Handler(Looper.myLooper());

        init();
    }

    private void init() {
        if (isVpnActive()) {
            state = CONNECTED;
            connectionTime = System.currentTimeMillis();
        } else {
            state = NOT_CONNECTED;
            connectionTime = 0;
        }
        timer = new PauseTimer(new PauseTimer.PauseTimerListener() {
            @Override
            public void onTick(long millisUntilFinished) {
                if (stateListener != null) {
                    stateListener.onTimeTick(millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                resume();
            }
        });
        registerReceivers();
    }

    @Override
    public void disconnect() {
        LOGGER.info("disconnect state = " + state);
        //ToDo should we use NOT_CONNECTED state too?
        if (state == CONNECTING || state == CONNECTED) {
            state = DISCONNECTING;
            sendConnectionState();
        }
        stopVpn();
    }

    @Override
    public void pause(long pauseDuration) {
        LOGGER.info("Pause, state = " + state);
        timer.startTimer(pauseDuration);
        state = PAUSING;
        sendConnectionState();
        pauseVpn(pauseDuration);
    }

    @Override
    public void resume() {
        LOGGER.info("Resume, state = " + state);
        timer.stopTimer();
        state = CONNECTING;
        sendConnectionState();
        handler.postDelayed(commonRunnable, COMMON_TIME_OUT);
        handler.postDelayed(portCheckRunnable, PORT_CHECK_TIME_OUT);
        domainResolver.tryResolveCurrentServerDomain(null);
        resumeVpn();
    }

    @Override
    public void stop() {
        LOGGER.info("Stop, state = " + state);
        timer.stopTimer();
        state = NOT_CONNECTED;
        sendConnectionState();
        forceStopVpn();
    }

    @Override
    public void startConnecting() {
        LOGGER.info("startConnecting, state = " + state);
        if (state == NOT_CONNECTED || state == PAUSED) {
            if (isFastestServerEnabled()) {
                startConnectWithFastestServer();
            } else {
                startConnectProcess();
            }
        }
    }

    @Override
    public void startConnecting(boolean force) {
        startConnecting();
    }

    @Override
    public void setStateListener(VpnStateListener stateListener) {
        Log.d(TAG, "setStateListener: ");
        this.stateListener = stateListener;
        if (stateListener != null) {
            stateListener.onConnectionStateChanged(state);
        }
    }

    @Override
    public void removeStateListener(VpnStateListener stateListener) {
        Log.d(TAG, "removeStateListener: ");
        this.stateListener = null;
    }

    @Override
    public void destroy() {
        LOGGER.info("destroy");
        stop();
        unregisterReceivers();
    }

    @Override
    public void notifyVpnState() {
        sendConnectionState();
    }

    @Override
    public long getConnectionTime() {
        if (state == null || !state.equals(CONNECTED)) {
            return -1;
        } else {
            return System.currentTimeMillis()  - connectionTime;
        }
    }

    @Override
    public void actionByUser() {
        LOGGER.info("Connection init by user");
        if (state.equals(DISCONNECTING)) {
            return;
        }

        performConnectionAction();
    }

    @Override
    public void reconnect() {
        LOGGER.info("Reconnect, state = " + state);
        if (isFastestServerEnabled()) {
            startReconnectWithFastestServer();
        } else {
            startReconnectProcess();
        }
    }

    @Override
    public void regenerateKeys() {

    }

    private void registerReceivers() {
        globalBehaviorController.addConnectionStatusListener(this);

        connectionStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                if (action.equals(VPN_STATUS)) {
                    String name = intent.getStringExtra(VPN_EXTRA_STATUS);
                    if (name == null) {
                        return;
                    }
                    ConnectionStatus status = ConnectionStatus.valueOf(name);
                    onReceiveConnectionStatus(status);
                } else if (action.equals(NOTIFICATION_ACTION)) {
                    onNotificationAction(intent);
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(VPN_STATUS);
        intentFilter.addAction(NOTIFICATION_ACTION);

        IVPNApplication.getApplication().registerReceiver(connectionStatusReceiver, intentFilter);
    }

    private void unregisterReceivers() {
        IVPNApplication.getApplication().unregisterReceiver(connectionStatusReceiver);
        globalBehaviorController.removeConnectionStatusListener(this);
    }

    private void onNotificationAction(Intent intent) {
        String actionExtra = intent.getStringExtra(NOTIFICATION_ACTION_EXTRA);
        if (actionExtra == null) {
            return;
        }
        switch (actionExtra) {
            case DISCONNECT_ACTION: {
                vpnBehaviorController.disconnect();
                break;
            }
            case PAUSE_ACTION: {
                vpnBehaviorController.pauseActionByUser();
                break;
            }
            case RESUME_ACTION: {
                vpnBehaviorController.resumeActionByUser();
                break;
            }
            case STOP_ACTION: {
                vpnBehaviorController.stopActionByUser();
                break;
            }
        }
    }

    private void performConnectionAction() {
        LOGGER.info("performConnectionAction: isVpnActive() = " + isVpnActive());
        LOGGER.info("performConnectionAction: state = " + state);
        if (state.equals(CONNECTING) || state.equals(CONNECTED)) {
            startDisconnectProcess();
        } else {
            if (isFastestServerEnabled()) {
                startConnectWithFastestServer();
            } else {
                startConnectProcess();
            }
        }
    }

    private void startConnectWithFastestServer() {
        LOGGER.info("startConnectWithFastestServer: state = " + state);
        if (stateListener != null) {
            stateListener.onFindingFastestServer();
        }
        pingProvider.findFastestServer(getFastestServerDetectorListener(false));
        //nothing to do, we will get fastest server through listener
    }

    private void startReconnectWithFastestServer() {
        LOGGER.info("startReconnectWithFastestServer: state = " + state);
        if (stateListener != null) {
            stateListener.onFindingFastestServer();
        }
        pingProvider.findFastestServer(getFastestServerDetectorListener(true));
    }

    private void startConnectProcess() {
        LOGGER.info("startConnectProcess: state = " + state);
        state = CONNECTING;
        sendConnectionState();
        handler.postDelayed(commonRunnable, COMMON_TIME_OUT);
        handler.postDelayed(portCheckRunnable, PORT_CHECK_TIME_OUT);
        domainResolver.tryResolveCurrentServerDomain(null);
        startVpn();
    }

    private void startReconnectProcess() {
        state = CONNECTING;
        sendConnectionState();
        handler.postDelayed(commonRunnable, COMMON_TIME_OUT);
        handler.postDelayed(portCheckRunnable, PORT_CHECK_TIME_OUT);
        domainResolver.tryResolveCurrentServerDomain(null);
        reconnectVpn();
    }

    private void startDisconnectProcess() {
        LOGGER.info("startDisconnectProcess: state = " + state);
        state = DISCONNECTING;
        sendConnectionState();
        stopVpn();
    }

    private void onAuthFailed() {
        LOGGER.info("onAuthFailed: state = " + state);
        handler.removeCallbacksAndMessages(null);
        stopVpn();
        if (stateListener != null) {
            stateListener.onAuthFailed();
        }
        state = NOT_CONNECTED;
        connectionTime = 0;
        sendConnectionState();
    }

    private void reset() {
        LOGGER.info("Reset");
        handler.removeCallbacksAndMessages(null);
        state = NOT_CONNECTED;
        if (stateListener != null) {
            stateListener.onCheckSessionState();
        }
        sendConnectionState();
        connectionTime = 0;
    }

    private void tryAnotherPort() {
        LOGGER.info("Try another port");
        stopVpn();
        if (stateListener != null) {
            stateListener.notifyAnotherPortUsedToConnect();
        }

        new Handler().postDelayed(() -> {
            selectNextPort();
            performConnectionAction();
        }, 500);
    }

    private void onTimeOut() {
        LOGGER.info("onTimeOut");
        stopVpn();
        if (stateListener != null) {
            stateListener.onTimeOut();
        }
    }

    private void selectNextPort() {
        LOGGER.info("selectNextPort");
        settings.nextPort();
    }

    private void onReceiveConnectionStatus(ConnectionStatus status) {
        if (status == null) {
            return;
        }
        this.status = status;
        LOGGER.info("onReceiveConnectionStatus: status = " + status);
        LOGGER.info("onReceiveConnectionStatus: state = " + state);
        switch (status) {
            case LEVEL_CONNECTED:
                globalBehaviorController.updateVpnConnectionState(VPNConnectionState.CONNECTED);
                state = CONNECTED;
                connectionTime = System.currentTimeMillis();
                sendConnectionState();
                handler.removeCallbacksAndMessages(null);
                break;
            case UNKNOWN_LEVEL:
            case LEVEL_AUTH_FAILED:
                globalBehaviorController.updateVpnConnectionState(VPNConnectionState.ERROR);
                onAuthFailed();
                break;
            case LEVEL_NOTCONNECTED:
                globalBehaviorController.updateVpnConnectionState(VPNConnectionState.DISCONNECTED);
                if (state.equals(NOT_CONNECTED) || state.equals(CONNECTING) || state.equals(PAUSED)) {
                    return;
                }
                if (state.equals(PAUSING)) {
                    state = PAUSED;
                } else {
                    connectionTime = 0;
                    state = NOT_CONNECTED;
                    if (stateListener != null) {
                        stateListener.onCheckSessionState();
                    }
                }
                sendConnectionState();
                handler.removeCallbacksAndMessages(null);
                break;
            case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
                handler.removeCallbacks(noNetworkRunnable);
                //ToDo should we change state to Connecting?
                break;
            case LEVEL_NONETWORK:
                if (state.equals(CONNECTED)) {
                    return;
                }
                handler.removeCallbacks(noNetworkRunnable);
                handler.postDelayed(noNetworkRunnable, NO_NETWORK_TIME_OUT);
                break;
            case LEVEL_START:
                break;
            case LEVEL_CONNECTING_SERVER_REPLIED:
                handler.removeCallbacks(noNetworkRunnable);
                break;
        }
    }

    private boolean isVpnActive() {
        return vpnBehaviorController.isVPNActive();
    }

    private boolean isFastestServerEnabled() {
        return settings.isFastestServerEnabled();
    }

    private void sendConnectionState() {
        LOGGER.info("sendConnectionState: state = " + state);
        if (stateListener != null) {
            stateListener.onConnectionStateChanged(state);
        }
    }

    private void stopVpn() {
        LOGGER.info("stopVpn: state = " + state);
        LOGGER.info("IVPNService.isRunning.get() = " + IVPNService.isRunning.get());
        if (!IVPNService.isRunning.get()) {
            return;
        }

        globalBehaviorController.onDisconnectingFromVpn();
        Context context = IVPNApplication.getApplication();
        Intent disconnectIntent = new Intent(context, IVPNService.class);
        disconnectIntent.setAction(DISCONNECT_VPN);
        startService(context, disconnectIntent);
    }

    private void forceStopVpn() {
        if (!IVPNService.isRunning.get()) {
            return;
        }
        globalBehaviorController.onDisconnectingFromVpn();
        Context context = IVPNApplication.getApplication();
        Intent forceStopIntent = new Intent(context, IVPNService.class);
        forceStopIntent.setAction(STOP_VPN);
        startService(context, forceStopIntent);
    }

    private void pauseVpn(long pauseDuration) {
        globalBehaviorController.onDisconnectingFromVpn();
        Context context = IVPNApplication.getApplication();
        Intent pauseIntent = new Intent(context, IVPNService.class);
        pauseIntent.setAction(PAUSE_VPN);
        pauseIntent.putExtra(VPN_PAUSE_DURATION_EXTRA, pauseDuration);
        startService(context, pauseIntent);
    }

    private void resumeVpn() {
        globalBehaviorController.onConnectingToVpn();
        Context context = IVPNApplication.getApplication();
        Intent resumeIntent = new Intent(context, IVPNService.class);
        resumeIntent.setAction(RESUME_VPN);
        startService(context, resumeIntent);
    }

    private void reconnectVpn() {
        globalBehaviorController.onConnectingToVpn();
        Context context = IVPNApplication.getApplication();
        Intent reconnectIntent = new Intent(context, IVPNService.class);
        reconnectIntent.setAction(RECONNECTING_VPN);
        startService(context, reconnectIntent);
    }

    private void startVpn() {
        Log.d(TAG, "startVpn: ");
        globalBehaviorController.onConnectingToVpn();
        Context context = IVPNApplication.getApplication();
        Intent startServiceIntent = new Intent(context, IVPNService.class);
        startService(context, startServiceIntent);
    }

    private void startService(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onStatusChanged(ConnectionStatus status) {
        onReceiveConnectionStatus(status);
    }

    private OnFastestServerDetectorListener getFastestServerDetectorListener(boolean isReconnecting) {
        return new OnFastestServerDetectorListener() {
            @Override
            public void onFastestServerDetected(Server server) {
                LOGGER.info("OpenVPN onFastestServerDetected: server = " + server);
                if (stateListener != null) {
                    stateListener.notifyServerAsFastest(server);
                }
                serversRepository.setCurrentServer(ServerType.ENTRY, server);
                if (isReconnecting) {
                    startReconnectProcess();
                } else {
                    startConnectProcess();
                }
            }

            @Override
            public void onDefaultServerApplied(Server server) {
                LOGGER.info("OpenVPN onDefaultServerApplied: server = " + server);
                ToastUtil.toast(R.string.connect_unable_test_fastest_server);
                if (stateListener != null) {
                    stateListener.notifyServerAsFastest(server);
                }
                serversRepository.setCurrentServer(ServerType.ENTRY, server);
                if (isReconnecting) {
                    startReconnectProcess();
                } else {
                    startConnectProcess();
                }
            }
        };
    }
}