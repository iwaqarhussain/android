/*
 *  This file is part of eduVPN.
 *
 *     eduVPN is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     eduVPN is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with eduVPN.  If not, see <http://www.gnu.org/licenses/>.
 */

package nl.eduvpn.app.fragment;

import android.animation.ValueAnimator;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import de.blinkt.openvpn.VpnProfile;
import nl.eduvpn.app.Constants;
import nl.eduvpn.app.EduVPNApplication;
import nl.eduvpn.app.MainActivity;
import nl.eduvpn.app.R;
import nl.eduvpn.app.adapter.ProfileAdapter;
import nl.eduvpn.app.entity.AuthorizationType;
import nl.eduvpn.app.entity.DiscoveredAPI;
import nl.eduvpn.app.entity.Instance;
import nl.eduvpn.app.entity.KeyPair;
import nl.eduvpn.app.entity.Profile;
import nl.eduvpn.app.entity.SavedKeyPair;
import nl.eduvpn.app.entity.SavedProfile;
import nl.eduvpn.app.entity.SavedToken;
import nl.eduvpn.app.service.APIService;
import nl.eduvpn.app.service.ConfigurationService;
import nl.eduvpn.app.service.ConnectionService;
import nl.eduvpn.app.service.HistoryService;
import nl.eduvpn.app.service.PreferencesService;
import nl.eduvpn.app.service.SerializerService;
import nl.eduvpn.app.service.VPNService;
import nl.eduvpn.app.utils.ErrorDialog;
import nl.eduvpn.app.utils.FormattingUtils;
import nl.eduvpn.app.utils.ItemClickSupport;
import nl.eduvpn.app.utils.Log;
import nl.eduvpn.app.utils.SwipeToDeleteAnimator;
import nl.eduvpn.app.utils.SwipeToDeleteHelper;

/**
 * Fragment which is displayed when the app start.
 * Created by Daniel Zolnai on 2016-10-20.
 */
public class HomeFragment extends Fragment {

    private static final String TAG = HomeFragment.class.getName();

    @Inject
    protected HistoryService _historyService;

    @Inject
    protected VPNService _vpnService;

    @Inject
    protected PreferencesService _preferencesService;

    @Inject
    protected APIService _apiService;

    @Inject
    protected SerializerService _serializerService;

    @Inject
    protected ConnectionService _connectionService;

    @Inject
    protected ConfigurationService _configurationService;

    @BindView(R.id.secureInternetList)
    protected RecyclerView _secureInternetList;

    @BindView(R.id.instituteAccessList)
    protected RecyclerView _instituteAccessList;

    @BindView(R.id.secureInternetContainer)
    protected View _secureInternetContainer;

    @BindView(R.id.instituteAccessContainer)
    protected View _instituteAccessContainer;

    @BindView(R.id.noProvidersYet)
    protected TextView _noProvidersYet;

    @BindView(R.id.loadingBar)
    protected ViewGroup _loadingBar;

    @BindView(R.id.displayText)
    protected TextView _displayText;

    @BindView(R.id.warningIcon)
    protected ImageView _warningIcon;

    @BindView(R.id.progressBar)
    protected View _progressBar;

    private Unbinder _unbinder;

    private int _pendingInstanceCount;
    private List<Instance> _problematicInstances;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable final Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        _unbinder = ButterKnife.bind(this, view);
        EduVPNApplication.get(view.getContext()).component().inject(this);

        // Basic setup of the lists
        _secureInternetList.setHasFixedSize(true);
        _instituteAccessList.setHasFixedSize(true);
        _secureInternetList.setLayoutManager(new LinearLayoutManager(view.getContext(), LinearLayoutManager.VERTICAL, false));
        _instituteAccessList.setLayoutManager(new LinearLayoutManager(view.getContext(), LinearLayoutManager.VERTICAL, false));

        // Swipe to delete
        ItemTouchHelper instituteSwipeHelper = new ItemTouchHelper(new SwipeToDeleteHelper(getContext()));
        instituteSwipeHelper.attachToRecyclerView(_instituteAccessList);
        _instituteAccessList.addItemDecoration(new SwipeToDeleteAnimator(getContext()));
        ItemTouchHelper secureInternetSwipeHelper = new ItemTouchHelper(new SwipeToDeleteHelper(getContext()));
        secureInternetSwipeHelper.attachToRecyclerView(_secureInternetList);
        _secureInternetList.addItemDecoration(new SwipeToDeleteAnimator(getContext()));

        // Fill with data
        List<SavedToken> savedInstituteAccessTokens = _historyService.getSavedTokensForAuthorizationType(AuthorizationType.LOCAL);
        List<SavedToken> savedSecureInternetTokens = _historyService.getSavedTokensForAuthorizationType(AuthorizationType.DISTRIBUTED);
        // Secure internet tokens are valid for all other instances.
        savedSecureInternetTokens = enhanceSecureInternetTokensList(savedSecureInternetTokens);
        if (savedInstituteAccessTokens.isEmpty() && savedSecureInternetTokens.isEmpty()) {
            // No saved tokens
            _loadingBar.setVisibility(View.GONE);
            _noProvidersYet.setVisibility(View.VISIBLE);
            _instituteAccessContainer.setVisibility(View.GONE);
            _secureInternetContainer.setVisibility(View.GONE);
        } else {

            _loadingBar.setVisibility(View.VISIBLE);
            _noProvidersYet.setVisibility(View.GONE);
            // There are some saved institute access tokens
            if (!savedInstituteAccessTokens.isEmpty()) {
                ProfileAdapter adapter = new ProfileAdapter(_historyService, null);
                _instituteAccessList.setAdapter(adapter);
                _fillList(adapter, savedInstituteAccessTokens);
                _instituteAccessContainer.setVisibility(View.VISIBLE);
            } else {
                _instituteAccessContainer.setVisibility(View.GONE);
            }

            // There are some saved secure internet tokens
            if (!savedSecureInternetTokens.isEmpty()) {
                ProfileAdapter adapter = new ProfileAdapter(_historyService, null);
                _fillList(adapter, savedSecureInternetTokens);
                _secureInternetList.setAdapter(adapter);
                _secureInternetContainer.setVisibility(View.VISIBLE);
            } else {
                _secureInternetContainer.setVisibility(View.GONE);
            }
        }
        ItemClickSupport.OnItemClickListener clickListener = new ItemClickSupport.OnItemClickListener() {
            @Override
            public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                ProfileAdapter adapter = (ProfileAdapter)recyclerView.getAdapter();
                if (adapter.isPendingRemoval(position)) {
                    return;
                }
                Pair<Instance, Profile> instanceProfilePair = adapter.getItem(position);
                // We surely have a discovered API and access token, since we just loaded the list with them
                Instance instance = instanceProfilePair.first;
                Profile profile = instanceProfilePair.second;
                DiscoveredAPI discoveredAPI = _historyService.getCachedDiscoveredAPI(instance.getSanitizedBaseURI());
                String accessToken = _historyService.getCachedAccessToken(instance);
                if (discoveredAPI == null || accessToken == null) {
                    ErrorDialog.show(getContext(), R.string.error_dialog_title, R.string.cant_connect_application_state_missing);
                    return;
                }
                _preferencesService.currentInstance(instance);
                _preferencesService.storeCurrentDiscoveredAPI(discoveredAPI);
                _preferencesService.storeCurrentProfile(profile);
                _connectionService.setAccessToken(accessToken);
                // In case we already have a downloaded profile, connect to it right away.
                SavedProfile savedProfile = _historyService.getCachedSavedProfile(instance.getSanitizedBaseURI(), profile.getProfileId());
                if (savedProfile != null) {
                    String profileUUID = savedProfile.getProfileUUID();
                    VpnProfile vpnProfile = _vpnService.getProfileWithUUID(profileUUID);
                    if (vpnProfile != null) {
                        // Profile found, connecting
                        _vpnService.connect(getActivity(), vpnProfile);
                        ((MainActivity)getActivity()).openFragment(new ConnectionStatusFragment(), false);
                        return;
                    } else {
                        Log.e(TAG, "Profile is not saved even it was marked as one!");
                        _historyService.removeSavedProfile(savedProfile);
                        // Continue with downloading the profile
                    }
                }
                // Ok so we don't have a downloaded profile, we need to download one
                _downloadKeyPairIfNeeded(instance, discoveredAPI, profile);
            }
        };
        ItemClickSupport.addTo(_instituteAccessList).setOnItemClickListener(clickListener);
        ItemClickSupport.addTo(_secureInternetList).setOnItemClickListener(clickListener);
        ItemClickSupport.OnItemLongClickListener longClickListener = new ItemClickSupport.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClicked(RecyclerView recyclerView, int position, View v) {
                // On long click we show the full name in a toast
                // Is useful when the names don't fit too well.
                ProfileAdapter adapter = (ProfileAdapter)recyclerView.getAdapter();
                if (adapter.isPendingRemoval(position)) {
                    return true;
                }
                Pair<Instance, Profile> instanceProfilePair = adapter.getItem(position);
                Toast.makeText(
                        getContext(),
                        FormattingUtils.formatProfileName(getContext(), instanceProfilePair.first, instanceProfilePair.second),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        };
        ItemClickSupport.addTo(_instituteAccessList).setOnItemLongClickListener(longClickListener);
        ItemClickSupport.addTo(_secureInternetList).setOnItemLongClickListener(longClickListener);

        return view;
    }

    /**
     * Creates a list with all distributed auth instances, using the same token.
     *
     * @return The list of all distributed auth instances.
     */
    private List<SavedToken> enhanceSecureInternetTokensList(List<SavedToken> savedSecureInternetTokens) {
        if (savedSecureInternetTokens == null) {
            return null;
        }
        if (savedSecureInternetTokens.size() == 0) {
            return savedSecureInternetTokens;
        }
        String savedAccessToken = savedSecureInternetTokens.get(0).getAccessToken();
        List<Instance> instanceList = _configurationService.getSecureInternetList();
        List<SavedToken> result = new ArrayList<>();
        for (Instance instance : instanceList) {
            result.add(new SavedToken(instance, savedAccessToken));
        }
        return result;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _unbinder.unbind();
    }


    /**
     * Starts fetching the list of profiles to be displayed.
     * This will be done from multiple APIs and loaded asynchronously.
     * While the list is still filling, a loading indicator is shown. When all resources were downloaded,
     * indicator will be hidden.
     *
     * @param adapter                  The adapter to show the profiles in.
     * @param instanceAccessTokenPairs Each instance & access token pair.
     */
    private void _fillList(final ProfileAdapter adapter, List<SavedToken> instanceAccessTokenPairs) {
        _pendingInstanceCount = instanceAccessTokenPairs.size();
        _problematicInstances = new ArrayList<>();
        for (SavedToken savedToken : instanceAccessTokenPairs) {
            final Instance instance = savedToken.getInstance();
            final String accessToken = savedToken.getAccessToken();
            DiscoveredAPI discoveredAPI = _historyService.getCachedDiscoveredAPI(instance.getSanitizedBaseURI());
            if (discoveredAPI != null) {
                // We got everything, fetch the available profiles.
                _fetchProfileList(adapter, instance, discoveredAPI, accessToken);
            } else {
                _apiService.getJSON(instance.getSanitizedBaseURI() + Constants.API_DISCOVERY_POSTFIX,
                        false,
                        new APIService.Callback<JSONObject>() {
                            @Override
                            public void onSuccess(JSONObject result) {
                                try {
                                    DiscoveredAPI discoveredAPI = _serializerService.deserializeDiscoveredAPI(result);
                                    // Cache the result
                                    _historyService.cacheDiscoveredAPI(instance.getSanitizedBaseURI(), discoveredAPI);
                                    _fetchProfileList(adapter, instance, discoveredAPI, accessToken);
                                } catch (SerializerService.UnknownFormatException ex) {
                                    Log.e(TAG, "Error parsing discovered API!", ex);
                                    _problematicInstances.add(instance);
                                    _checkLoadingFinished(adapter);
                                }
                            }

                            @Override
                            public void onError(String errorMessage) {
                                Log.e(TAG, "Error while fetching discovered API: " + errorMessage);
                                _problematicInstances.add(instance);
                                _checkLoadingFinished(adapter);
                            }
                        });
            }
        }
    }

    /**
     * Starts downloading the list of profiles for a single VPN provider.
     *
     * @param adapter       The adapter to download the data into.
     * @param instance      The VPN provider instance.
     * @param discoveredAPI The discovered API containing the URLs.
     * @param accessToken   The access token for the API.
     */
    private void _fetchProfileList(@NonNull final ProfileAdapter adapter, @NonNull final Instance instance,
                                   @NonNull DiscoveredAPI discoveredAPI, @NonNull String accessToken) {
        _connectionService.setAccessToken(accessToken);
        _apiService.getJSON(discoveredAPI.getProfileListEndpoint(), true, new APIService.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    List<Profile> profiles = _serializerService.deserializeProfileList(result);
                    List<Pair<Instance, Profile>> newItems = new ArrayList<>();
                    for (Profile profile : profiles) {
                        newItems.add(new Pair<>(instance, profile));
                    }
                    adapter.addItems(newItems);
                } catch (SerializerService.UnknownFormatException ex) {
                    _problematicInstances.add(instance);
                    Log.e(TAG, "Error parsing profile list.", ex);
                }
                _checkLoadingFinished(adapter);
            }

            @Override
            public void onError(String errorMessage) {
                _problematicInstances.add(instance);
                Log.e(TAG, "Error fetching profile list: " + errorMessage);
                _checkLoadingFinished(adapter);
            }
        });
    }

    /**
     * Checks if the loading has finished.
     * If yes, it hides the loading animation.
     * If there were any errors, it will display a warning bar as well.
     *
     * @param adapter The adapter which the items are being loaded into.
     */
    private synchronized void _checkLoadingFinished(final ProfileAdapter adapter) {
        _pendingInstanceCount--;
        if (_pendingInstanceCount <= 0 && _problematicInstances.size() == 0) {
            if (_loadingBar == null) {
                Log.d(TAG, "Layout has been destroyed already.");
                return;
            }
            float startHeight = _loadingBar.getHeight();
            ValueAnimator animator = ValueAnimator.ofFloat(startHeight, 0);
            animator.setDuration(600);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float fraction = animation.getAnimatedFraction();
                    float alpha = 1f - fraction;
                    float height = (Float)animation.getAnimatedValue();
                    if (_loadingBar != null) {
                        _loadingBar.setAlpha(alpha);
                        _loadingBar.getLayoutParams().height = (int)height;
                        _loadingBar.requestLayout();
                    }
                }
            });
            animator.start();
        } else if (_pendingInstanceCount <= 0) {
            if (_displayText == null) {
                Log.d(TAG, "Layout has been destroyed already.");
                return;
            }
            // There are some warnings
            _displayText.setText(R.string.could_not_fetch_all_profiles);
            _warningIcon.setVisibility(View.VISIBLE);
            _progressBar.setVisibility(View.GONE);
            _loadingBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Display a dialog with all the warnings
                    ErrorDialog.show(getContext(),
                            getString(R.string.warnings_list),
                            getString(R.string.instance_access_warning_message),
                            new ErrorDialog.InstanceWarningHandler() {
                                @Override
                                public List<Instance> getInstances() {
                                    return _problematicInstances;
                                }

                                @Override
                                public void retryInstance(Instance instance) {
                                    _warningIcon.setVisibility(View.GONE);
                                    _progressBar.setVisibility(View.VISIBLE);
                                    _displayText.setText(R.string.loading_available_profiles);
                                    SavedToken savedToken = _historyService.getSavedToken(instance.getSanitizedBaseURI());
                                    if (savedToken == null) {
                                        // Should never happen
                                        ErrorDialog.show(getContext(), R.string.error_dialog_title, R.string.data_removed);
                                    } else {
                                        // Retry
                                        _problematicInstances.remove(instance);
                                        _fillList(adapter, Collections.singletonList(savedToken));
                                    }
                                }

                                @Override
                                public void loginInstance(final Instance instance) {
                                    _apiService.getJSON(instance.getSanitizedBaseURI() + Constants.API_DISCOVERY_POSTFIX,
                                            false,
                                            new APIService.Callback<JSONObject>() {
                                                @Override
                                                public void onSuccess(JSONObject result) {
                                                    try {
                                                        DiscoveredAPI discoveredAPI = _serializerService.deserializeDiscoveredAPI(result);
                                                        // Cache the result
                                                        _historyService.cacheDiscoveredAPI(instance.getSanitizedBaseURI(), discoveredAPI);
                                                        _problematicInstances.remove(instance);
                                                        _connectionService.initiateConnection(getActivity(), instance, discoveredAPI);
                                                    } catch (SerializerService.UnknownFormatException ex) {
                                                        Log.e(TAG, "Error parsing discovered API!", ex);
                                                        ErrorDialog.show(getContext(), R.string.error_dialog_title, R.string.provider_incorrect_format);
                                                    }
                                                }

                                                @Override
                                                public void onError(String errorMessage) {
                                                    Log.e(TAG, "Error while fetching discovered API: " + errorMessage);
                                                    ErrorDialog.show(getContext(), R.string.error_dialog_title, R.string.provider_not_found_retry);
                                                }
                                            });
                                }

                                @Override
                                public void removeInstance(Instance instance) {
                                    _historyService.removeAccessTokens(instance);
                                    _historyService.removeDiscoveredAPI(instance.getSanitizedBaseURI());
                                    _historyService.removeSavedProfilesForInstance(instance.getSanitizedBaseURI());
                                    _problematicInstances.remove(instance);
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            _checkLoadingFinished(adapter);
                                        }
                                    });
                                }
                            });
                }
            });
        }
    }

    /**
     * Downloads the key pair if no cached one found. After that it downloads the profile and connects to it.
     *
     * @param instance      The VPN provider.
     * @param discoveredAPI The discovered API.
     * @param profile       The profile to download.
     */
    private void _downloadKeyPairIfNeeded(@NonNull final Instance instance, @NonNull final DiscoveredAPI discoveredAPI, @NonNull final Profile profile) {
        // First we create a keypair, if there is no saved one yet.
        SavedKeyPair savedKeyPair = _historyService.getSavedKeyPairForAPI(discoveredAPI);
        int dialogMessageRes = savedKeyPair != null ? R.string.vpn_profile_download_message : R.string.vpn_profile_creating_keypair;
        final ProgressDialog dialog = ProgressDialog.show(getContext(),
                getString(R.string.progress_dialog_title),
                getString(dialogMessageRes),
                true,
                false);
        if (savedKeyPair != null) {
            _downloadProfileWithKeyPair(instance, discoveredAPI, savedKeyPair, profile, dialog);
        }
        String requestData = "display_name=" + Constants.PROFILE_DISPLAY_NAME;
        String createKeyPairEndpoint = discoveredAPI.getCreateKeyPairEndpoint();
        _apiService.postResource(createKeyPairEndpoint, requestData, true, new APIService.Callback<String>() {

            @Override
            public void onSuccess(String keyPairJson) {
                try {
                    KeyPair keyPair = _serializerService.deserializeKeyPair(new JSONObject(keyPairJson));
                    Log.i(TAG, "Created key pair, is it successful: " + keyPair.isOK());
                    // Save it for later
                    SavedKeyPair savedKeyPair = new SavedKeyPair(discoveredAPI.getApiBaseUri(), keyPair);
                    _historyService.storeSavedKeyPair(savedKeyPair);
                    _downloadProfileWithKeyPair(instance, discoveredAPI, savedKeyPair, profile, dialog);
                } catch (Exception ex) {
                    dialog.dismiss();
                    ErrorDialog.show(getContext(), R.string.error_dialog_title, getString(R.string.error_parsing_keypair, ex.getMessage()));
                    Log.e(TAG, "Unable to parse keypair data", ex);
                }
            }

            @Override
            public void onError(String errorMessage) {
                dialog.dismiss();
                ErrorDialog.show(getContext(), R.string.error_dialog_title, getString(R.string.error_creating_keypair, errorMessage));
                Log.e(TAG, "Error creating keypair: " + errorMessage);
            }
        });
    }

    /**
     * Now that we have the key pair, we can download the profile.
     *
     * @param instance      The API instance definition.
     * @param discoveredAPI The discovered API URLs.
     * @param savedKeyPair  The private key and certificate used to generate the profile.
     * @param profile       The profile to create.
     * @param dialog        Loading dialog which should be dismissed when finished.
     */
    private void _downloadProfileWithKeyPair(final Instance instance, DiscoveredAPI discoveredAPI,
                                             final SavedKeyPair savedKeyPair, final Profile profile,
                                             final ProgressDialog dialog) {
        dialog.setMessage(getString(R.string.vpn_profile_download_message));
        String requestData = "?display_name=" + Constants.PROFILE_DISPLAY_NAME + "&profile_id=" + profile.getProfileId();
        _apiService.getString(discoveredAPI.getProfileConfigEndpoint() + requestData, true, new APIService.Callback<String>() {
            @Override
            public void onSuccess(String vpnConfig) {
                // The downloaded profile misses the <cert> and <key> fields. We will insert that via the saved key pair.
                String configName = FormattingUtils.formatProfileName(getContext(), instance, profile);
                VpnProfile vpnProfile = _vpnService.importConfig(vpnConfig, configName, savedKeyPair);
                if (vpnProfile != null && getActivity() != null) {
                    // Cache the profile
                    SavedProfile savedProfile = new SavedProfile(instance, profile, vpnProfile.getUUIDString());
                    _historyService.cacheSavedProfile(savedProfile);
                    // Connect with the profile
                    dialog.dismiss();
                    _vpnService.connect(getActivity(), vpnProfile);
                    ((MainActivity)getActivity()).openFragment(new ConnectionStatusFragment(), false);
                } else {
                    dialog.dismiss();
                    ErrorDialog.show(getContext(), R.string.error_dialog_title, R.string.error_importing_profile);
                }
            }

            @Override
            public void onError(String errorMessage) {
                dialog.dismiss();
                ErrorDialog.show(getContext(), R.string.error_dialog_title, getString(R.string.error_fetching_profile, errorMessage));
                Log.e(TAG, "Error fetching profile: " + errorMessage);
            }
        });
    }

    /**
     * Called when the user clicks on the 'Add Provider' button.
     */
    @OnClick(R.id.addProvider)
    protected void onAddProviderClicked() {
        ((MainActivity)getActivity()).openFragment(new TypeSelectorFragment(), true);
    }
}
