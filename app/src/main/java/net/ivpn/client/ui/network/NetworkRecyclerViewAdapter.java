package net.ivpn.client.ui.network;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import net.ivpn.client.IVPNApplication;
import net.ivpn.client.databinding.ViewCommonNetworkBehaviourBinding;
import net.ivpn.client.databinding.ViewNetworkMainBinding;
import net.ivpn.client.databinding.ViewWifiItemBinding;
import net.ivpn.client.vpn.model.NetworkState;
import net.ivpn.client.vpn.model.WifiItem;

import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import static net.ivpn.client.vpn.model.NetworkState.DEFAULT;
import static net.ivpn.client.vpn.model.NetworkState.NONE;

public class NetworkRecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int WIFI_ITEM = 0;
    private static final int NETWORK_FEATURE_DESCRIPTION = 1;
    private static final int COMMON_ITEM = 2;

    private boolean isNetworkRulesEnabled;
    private NetworkState defaultState = NONE;
    private List<WifiItem> wifiItemList = new LinkedList<>();
    private NetworkState mobileDataState = DEFAULT;
    private OnNetworkFeatureStateChanged onNetworkFeatureStateChanged;

    NetworkRecyclerViewAdapter() {
    }

    @Override
    public int getItemViewType(int position) {
        switch (position) {
            case 0: {
                return NETWORK_FEATURE_DESCRIPTION;
            }
            case 1: {
                return COMMON_ITEM;
            }
            default: {
                return WIFI_ITEM;
            }
        }
    }

    @Override
    public int getItemCount() {
        if (isNetworkRulesEnabled) {
            return wifiItemList.size() + 2;
        } else {
            return 1;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case WIFI_ITEM: {
                ViewWifiItemBinding binding = ViewWifiItemBinding.inflate(layoutInflater, parent, false);
                return new WifiItemViewHolder(binding);
            }
            case COMMON_ITEM: {
                ViewCommonNetworkBehaviourBinding binding = ViewCommonNetworkBehaviourBinding.inflate(layoutInflater, parent, false);
                return new CommonNetworkViewHolder(binding);
            }
            default: {
                ViewNetworkMainBinding binding = ViewNetworkMainBinding.inflate(layoutInflater, parent, false);
                return new NetworkFeatureViewHolder(binding);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof WifiItemViewHolder) {
            ((WifiItemViewHolder) holder).bind(wifiItemList.get(position - 2));
        } else if (holder instanceof CommonNetworkViewHolder) {
            ((CommonNetworkViewHolder) holder).bind();
        } else if (holder instanceof NetworkFeatureViewHolder) {
            ((NetworkFeatureViewHolder) holder).bind();
        }
    }

    public void setWifiItemList(List<WifiItem> wifiItemList) {
        this.wifiItemList = wifiItemList;
    }

    public void setNetworkRulesEnabled(boolean isNetworkWatcherFeatureEnabled) {
        if (isNetworkRulesEnabled != isNetworkWatcherFeatureEnabled) {
            this.isNetworkRulesEnabled = isNetworkWatcherFeatureEnabled;
            notifyDataSetChanged();
        }
    }

    public void setDefaultNetworkState(NetworkState defaultState) {
        this.defaultState = defaultState;
    }

    public void setMobileDataState(NetworkState mobileDataState) {
        this.mobileDataState = mobileDataState;
    }

    private void updateUIWithDefaultValue(NetworkState defaultState) {
        setDefaultNetworkState(defaultState);
        notifyDataSetChanged();
    }

    public void setOnNetworkFeatureStateChanged(OnNetworkFeatureStateChanged onNetworkFeatureStateChanged) {
        this.onNetworkFeatureStateChanged = onNetworkFeatureStateChanged;
    }

    public class WifiItemViewHolder extends RecyclerView.ViewHolder {

        private ViewWifiItemBinding binding;
        @Inject WifiItemViewModel viewModel;

        WifiItemViewHolder(ViewWifiItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            IVPNApplication.getApplication().appComponent.provideActivityComponent().create().inject(this);

            viewModel.setDefaultState(defaultState);

            binding.behaviorSpinner.setAdapter(new NetworkAdapter(binding.getRoot().getContext(),
                    NetworkState.getActiveState(), defaultState));
            binding.setViewmodel(viewModel);
        }

        public void bind(WifiItem wifiItem) {
            WifiItemViewModel viewModel = binding.getViewmodel();
            viewModel.setWifiItem(wifiItem);
            viewModel.setDefaultState(defaultState);
            binding.executePendingBindings();
        }
    }

    class NetworkFeatureViewHolder extends RecyclerView.ViewHolder
            implements CompoundButton.OnCheckedChangeListener {

        private ViewNetworkMainBinding binding;

        NetworkFeatureViewHolder(ViewNetworkMainBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            Log.d("NetworkFeature", "NetworkFeatureViewHolder: isNetworkRulesEnabled = " + isNetworkRulesEnabled);
            binding.wifiMainSwitcher.setChecked(isNetworkRulesEnabled);
            binding.setIsNetworkFilterEnabled(isNetworkRulesEnabled);
            binding.wifiMainSwitcher.setOnCheckedChangeListener(this);
        }

        private void bind() {
            binding.wifiMainSwitcher.setChecked(isNetworkRulesEnabled);
            binding.setIsNetworkFilterEnabled(isNetworkRulesEnabled);
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            Log.d("NetworkFeature", "onCheckedChanged: isChecked = " + isChecked);
            if (isChecked == isNetworkRulesEnabled) {
                return;
            }
            isNetworkRulesEnabled = isChecked;
            binding.setIsNetworkFilterEnabled(isNetworkRulesEnabled);
            if (onNetworkFeatureStateChanged != null) {
                onNetworkFeatureStateChanged.onNetworkFeatureStateChanged(isChecked);
            }
            notifyDataSetChanged();
        }
    }

    public class CommonNetworkViewHolder extends RecyclerView.ViewHolder
            implements CommonBehaviourItemViewModel.OnDefaultBehaviourChanged {

        private ViewCommonNetworkBehaviourBinding binding;
        @Inject
        public CommonBehaviourItemViewModel viewModel;

        CommonNetworkViewHolder(ViewCommonNetworkBehaviourBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            IVPNApplication.getApplication().appComponent.provideActivityComponent().create().inject(this);
            viewModel.setNavigator(this);
            binding.setViewmodel(viewModel);
            binding.defaultBehaviorSpinner.setAdapter(new NetworkAdapter(binding.getRoot().getContext(),
                    NetworkState.getDefaultStates(), defaultState));
            binding.mobileBehaviorSpinner.setAdapter(new NetworkAdapter(binding.getRoot().getContext(),
                    NetworkState.getActiveState(), defaultState));
        }

        private void bind() {
            viewModel.setDefaultState(defaultState);
            viewModel.setMobileDataState(mobileDataState);
            binding.executePendingBindings();
        }

        @Override
        public void onDefaultBehaviourChanged(NetworkState state) {
            if (defaultState == state) {
                return;
            }
            updateUIWithDefaultValue(state);
        }

        @Override
        public void onMobileDataBehaviourChanged(NetworkState mobileDataState) {
            NetworkRecyclerViewAdapter.this.mobileDataState = mobileDataState;
        }
    }
}