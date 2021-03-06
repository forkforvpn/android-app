package net.ivpn.client.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

import net.ivpn.client.IVPNApplication;
import net.ivpn.client.common.dagger.ApplicationScope;
import net.ivpn.client.common.prefs.Settings;
import net.ivpn.client.ui.settings.SettingsActivity;
import net.ivpn.client.vpn.controller.VpnBehaviorController;
import net.ivpn.client.vpn.local.KillSwitchService;
import net.ivpn.client.vpn.local.PermissionActivity;
import net.ivpn.client.vpn.model.KillSwitchRule;
import net.ivpn.client.vpn.model.VPNRule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import static net.ivpn.client.vpn.VPNState.BOTH;
import static net.ivpn.client.vpn.VPNState.KILL_SWITCH;
import static net.ivpn.client.vpn.VPNState.NONE;
import static net.ivpn.client.vpn.VPNState.VPN;
import static net.ivpn.client.vpn.model.KillSwitchRule.DISABLE;
import static net.ivpn.client.vpn.model.KillSwitchRule.ENABLE;
import static net.ivpn.client.vpn.model.KillSwitchRule.NOTHING;

@ApplicationScope
public class GlobalBehaviorController implements ServiceConstants {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalBehaviorController.class);

    private VPNState state = NONE;
    private boolean isVpnDisconnecting;
    private List<OnVpnStatusChangedListener> listeners = new ArrayList<>();
    private KillSwitchRule killSwitchRule = NOTHING;
    private VPNRule vpnRule = VPNRule.NOTHING;

    private BroadcastReceiver securityGuardActionsReceiver;
    private Settings settings;
    private VpnBehaviorController vpnBehaviorController;

    @Inject
    public GlobalBehaviorController(Settings settings,
                                    VpnBehaviorController vpnBehaviorController) {
        this.settings = settings;
        this.vpnBehaviorController = vpnBehaviorController;
    }

    public void init() {
        if (isKillSwitchEnabled()) {
            state = isVpnActive() ? BOTH : KILL_SWITCH;
        } else {
            state = isVpnActive() ? VPN : NONE;
        }
        registerReceiver();
    }

    public VPNState getState() {
        return state;
    }

    public boolean isKillSwitchShouldBeStarted() {
        LOGGER.info("isKillSwitchShouldBeStarted");
        switch (killSwitchRule) {
            case ENABLE: {
                return state.equals(KILL_SWITCH) || state.equals(NONE);
            }
            case DISABLE: {
                return false;
            }
            case NOTHING: {
                return state.equals(KILL_SWITCH);
            }
        }
        return state.equals(KILL_SWITCH);
    }

    public void enableKillSwitch() {
        LOGGER.info("enableKillSwitch");
        switch (state) {
            case KILL_SWITCH:
            case NONE: {
                state = KILL_SWITCH;
                if (killSwitchRule.equals(ENABLE) || killSwitchRule.equals(NOTHING)) {
                    startKillSwitch();
                }
                break;
            }
            case BOTH:
            case VPN: {
                state = BOTH;
                break;
            }
        }
    }

    public void disableKillSwitch() {
        LOGGER.info("disableKillSwitch: state BEFORE = " + state);
        switch (state) {
            case NONE:
            case KILL_SWITCH: {
                state = NONE;
                if (killSwitchRule.equals(ENABLE) || killSwitchRule.equals(NOTHING)) {
                    stopKillSwitch();
                }
                break;
            }
            case VPN:
            case BOTH: {
                state = VPN;
                break;
            }
        }
    }

    public void onConnectingToVpn() {
        LOGGER.info("onConnectingToVpn: state BEFORE = " + state);
        switch (state) {
            case BOTH:
                break;
            case KILL_SWITCH:
                stopKillSwitch();
                state = BOTH;
                break;
            case VPN:
            case NONE:
                state = VPN;
                break;
        }
    }

    public void onDisconnectingFromVpn() {
        isVpnDisconnecting = true;
    }

    public void stopVPN() {
        LOGGER.info("stopVPN");
        if (!isVpnActive()) {
            LOGGER.info("VPN is NOT active, skip stopVPN event");
            return;
        }
        vpnBehaviorController.disconnect();
    }

    public void applyNetworkRules(KillSwitchRule killSwitchRule, VPNRule vpnRule) {
        LOGGER.info("applyNetworkRules killSwitchRule = " + killSwitchRule + " vpnRule = " + vpnRule);
        applyVpnRule(vpnRule);
        applyKillSwitchRule(killSwitchRule);
    }

    public void applyVpnRule(VPNRule vpnRule) {
        LOGGER.info("applyVpnRule vpnRule = " + vpnRule);
        this.vpnRule = vpnRule;
        if (vpnRule.equals(VPNRule.CONNECT)) {
            tryToConnectVpn();
        } else if (vpnRule.equals(VPNRule.DISCONNECT)) {
            stopVPN();
        }
    }

    public void applyKillSwitchRule(KillSwitchRule killSwitchRule) {
        LOGGER.info("applyKillSwitchRule: old killSwitchRule = " + this.killSwitchRule);
        LOGGER.info("applyKillSwitchRule: new killSwitchRule = " + killSwitchRule);
        LOGGER.info("applyKillSwitchRule: state = " + state);
        //Check if VPN is running or preparing to run;
        this.killSwitchRule = killSwitchRule;
        if (isVPNRunningOrPreparingToRun()) {
            return;
        }
        switch (state) {
            case NONE: {
                if (killSwitchRule.equals(ENABLE)) {
                    startKillSwitch();
                } else if (killSwitchRule.equals(DISABLE)) {
                    stopKillSwitch();
                }
                break;
            }
            case KILL_SWITCH: {
                if (killSwitchRule.equals(DISABLE)) {
                    stopKillSwitch();
                } else {
                    startKillSwitch();
                }
                break;
            }
        }
    }

    private boolean isVPNRunningOrPreparingToRun() {
        return isVpnActive() || vpnRule.equals(VPNRule.CONNECT)
                || (state.equals(VPN) || state.equals(BOTH));
    }

    private void tryToConnectVpn() {
        LOGGER.info("tryToConnectVpn");
        if (isVpnActive()) {
            return;
        }

        if (isVPNPermissionGranted()) {
            vpnBehaviorController.connectActionByRules();
        } else {
            askPermissionAndStartVpn();
        }
    }

    private boolean isVPNPermissionGranted() {
        Context context = IVPNApplication.getApplication();

        Intent intent;
        try {
            intent = VpnService.prepare(context);
        } catch (Exception ignored) {
            return false;
        }

        return intent == null;
    }

    private void askPermissionAndStartVpn() {
        LOGGER.info("askPermissionAndStartVpn");
        Context context = IVPNApplication.getApplication();
        Intent vpnIntent = new Intent(context, PermissionActivity.class);
        vpnIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(vpnIntent);
    }

    public void startKillSwitch() {
        LOGGER.info("startKillSwitch");
        Context context = IVPNApplication.getApplication();

        Intent killSwitchIntent = new Intent(context, KillSwitchService.class);
        killSwitchIntent.setAction(START_KILL_SWITCH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(killSwitchIntent);
        } else {
            context.startService(killSwitchIntent);
        }
    }

    private void stopKillSwitch() {
        LOGGER.info("stopKillSwitch");
        if (!KillSwitchService.isRunning.get()) {
            return;
        }
        Context context = IVPNApplication.getApplication();
        Intent stopIntent = new Intent(context, KillSwitchService.class);
        stopIntent.setAction(STOP_KILL_SWITCH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(stopIntent);
        } else {
            context.startService(stopIntent);
        }
    }

    public void release() {
        LOGGER.info("release");
        finishAll();
        IVPNApplication.getApplication().unregisterReceiver(securityGuardActionsReceiver);
    }

    public void finishAll() {
        LOGGER.info("finishAll");
        stopVPN();
        stopKillSwitch();
        state = NONE;
    }

    public void addConnectionStatusListener(OnVpnStatusChangedListener listener) {
        listeners.add(listener);
    }

    public void removeConnectionStatusListener(OnVpnStatusChangedListener listener) {
        listeners.remove(listener);
    }

    private void registerReceiver() {
        securityGuardActionsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getStringExtra(KILL_SWITCH_ACTION_EXTRA);
                applyKillSwitchAction(action);
            }
        };

        IVPNApplication.getApplication().registerReceiver(securityGuardActionsReceiver,
                new IntentFilter(KILL_SWITCH_ACTION));
    }

    private void applyKillSwitchAction(String action) {
        if (action == null) return;

        switch (action) {
            case CONNECT_VPN_ACTION:
                vpnBehaviorController.connectActionByRules();
                break;
            case APP_SETTINGS_ACTION:
                openSettings();
                break;
            case STOP_KILL_SWITCH_ACTION:
                stopKillSwitch();
                break;
        }
    }

    private void openSettings() {
        Context context = IVPNApplication.getApplication();

        Intent intent = new Intent(context, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean isKillSwitchEnabled() {
        return settings.isKillSwitchEnabled();
    }

    private boolean isVpnActive() {
        return vpnBehaviorController.isVPNActive();
    }

    public void updateVpnConnectionState(VPNConnectionState state) {
        switch (state) {
            case CONNECTED:
                onVpnConnected();
                break;
            case DISCONNECTED:
                if (!isVpnDisconnecting) return;

                isVpnDisconnecting = false;
                onVpnDisconnected();
                break;
            case ERROR:
                break;
        }
    }

    private void onVpnDisconnected() {
        LOGGER.info("onVpnDisconnected: state = " + state);
        LOGGER.info("onVpnDisconnected: killSwitchRule = " + killSwitchRule);
        switch (state) {
            case KILL_SWITCH:
            case BOTH: {
                state = KILL_SWITCH;
                if (killSwitchRule.equals(ENABLE) || killSwitchRule.equals(NOTHING)) {
                    startKillSwitch();
                }
                break;
            }
            case NONE:
            case VPN: {
                state = NONE;
                if (killSwitchRule.equals(ENABLE)) {
                    startKillSwitch();
                }
                break;
            }
        }
    }

    private void onVpnConnected() {
        LOGGER.info("onVpnConnected");
        switch (state) {
            case KILL_SWITCH:
            case BOTH:
                state = BOTH;
                break;
            default:
                state = VPN;
        }
    }
}