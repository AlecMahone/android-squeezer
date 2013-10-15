/*
 * Copyright (c) 2012 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer;

import java.lang.ref.WeakReference;

import uk.org.ngo.squeezer.dialogs.AboutDialog;
import uk.org.ngo.squeezer.dialogs.EnableWifiDialog;
import uk.org.ngo.squeezer.dialogs.SqueezerAuthenticationDialog;
import uk.org.ngo.squeezer.framework.HasUiThread;
import uk.org.ngo.squeezer.framework.SqueezerBaseActivity;
import uk.org.ngo.squeezer.itemlists.SqueezerAlbumListActivity;
import uk.org.ngo.squeezer.itemlists.SqueezerCurrentPlaylistActivity;
import uk.org.ngo.squeezer.itemlists.SqueezerPlayerListActivity;
import uk.org.ngo.squeezer.itemlists.SqueezerSongListActivity;
import uk.org.ngo.squeezer.model.SqueezerArtist;
import uk.org.ngo.squeezer.model.SqueezerPlayer;
import uk.org.ngo.squeezer.model.SqueezerPlayerState;
import uk.org.ngo.squeezer.model.SqueezerPlayerState.PlayStatus;
import uk.org.ngo.squeezer.model.SqueezerPlayerState.RepeatStatus;
import uk.org.ngo.squeezer.model.SqueezerPlayerState.ShuffleStatus;
import uk.org.ngo.squeezer.model.SqueezerSong;
import uk.org.ngo.squeezer.service.ISqueezeService;
import uk.org.ngo.squeezer.service.SqueezeService;
import uk.org.ngo.squeezer.util.ImageCache.ImageCacheParams;
import uk.org.ngo.squeezer.util.ImageFetcher;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

public class NowPlayingFragment extends Fragment implements
        HasUiThread, View.OnCreateContextMenuListener {
    private final String TAG = "NowPlayingFragment";

    private SqueezerBaseActivity mActivity;
    private ISqueezeService mService = null;

    private TextView albumText;
    private TextView artistText;
    private TextView trackText;
    ImageView btnContextMenu;
    private TextView currentTime;
    private TextView totalTime;
    private MenuItem menu_item_connect;
    private MenuItem menu_item_disconnect;
    private MenuItem menu_item_poweron;
    private MenuItem menu_item_poweroff;
    private MenuItem menu_item_players;
    private MenuItem menu_item_playlists;
    private MenuItem menu_item_search;
    private MenuItem menu_item_volume;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton prevButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;
    private ImageView albumArt;
    private SeekBar seekBar;

    /** Volume control panel. */
    private VolumePanel mVolumePanel;

    // Updating the seekbar
    private boolean updateSeekBar = true;
    private int secondsIn;
    private int secondsTotal;
    private final static int UPDATE_TIME = 1;

    /** ImageFetcher for album cover art */
    private ImageFetcher mImageFetcher;

    /** ImageCache parameters for the album art. */
    private ImageCacheParams mImageCacheParams;

    private final Handler uiThreadHandler = new UiThreadHandler(this);

    private final static class UiThreadHandler extends Handler {
        WeakReference<NowPlayingFragment> mFragment;

        public UiThreadHandler(NowPlayingFragment fragment) {
            mFragment = new WeakReference<NowPlayingFragment>(fragment);
        }

        // Normally I'm lazy and just post Runnables to the uiThreadHandler
        // but time updating is special enough (it happens every second) to
        // take care not to allocate so much memory which forces Dalvik to GC
        // all the time.
        @Override
        public void handleMessage(Message message) {
            if (message.what == UPDATE_TIME) {
                mFragment.get().updateTimeDisplayTo(mFragment.get().secondsIn,
                        mFragment.get().secondsTotal);
            }
        }
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (networkInfo.isConnected()) {
                Log.v(TAG, "Received WIFI connected broadcast");
                if (!isConnected()) {
                    // Requires a serviceStub. Else we'll do this on the service
                    // connection callback.
                    if (mService != null) {
                        Log.v(TAG, "Initiated connect on WIFI connected");
                        startVisibleConnection();
                    }
                }
            }
        }
    };

    private ProgressDialog connectingDialog = null;
    private void clearConnectingDialog() {
        if (connectingDialog != null && connectingDialog.isShowing())
            connectingDialog.dismiss();
        connectingDialog = null;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.v(TAG, "ServiceConnection.onServiceConnected()");
            mService = ISqueezeService.Stub.asInterface(binder);
            NowPlayingFragment.this.onServiceConnected();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private boolean mFullHeightLayout;

    /**
     * Called before onAttach. Pull out the layout spec to figure out which
     * layout to use later.
     */
    @Override
    public void onInflate(Activity activity, AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(activity, attrs, savedInstanceState);

        int layout_height = attrs.getAttributeUnsignedIntValue(
                "http://schemas.android.com/apk/res/android",
                "layout_height", 0);

        mFullHeightLayout = (layout_height == ViewGroup.LayoutParams.FILL_PARENT);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = (SqueezerBaseActivity) activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        // Set up a server connection, if it is not present
        if (getConfiguredCliIpPort(getSharedPreferences()) == null)
            SettingsActivity.show(mActivity);

        mActivity.bindService(new Intent(mActivity, SqueezeService.class), serviceConnection,
                Context.BIND_AUTO_CREATE);
        Log.d(TAG, "did bindService; serviceStub = " + mService);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v;

        if (mFullHeightLayout) {
            v = inflater.inflate(R.layout.now_playing_fragment_full, container, false);

            artistText = (TextView) v.findViewById(R.id.artistname);
            nextButton = (ImageButton) v.findViewById(R.id.next);
            prevButton = (ImageButton) v.findViewById(R.id.prev);
            shuffleButton = (ImageButton) v.findViewById(R.id.shuffle);
            repeatButton = (ImageButton) v.findViewById(R.id.repeat);
            currentTime = (TextView) v.findViewById(R.id.currenttime);
            totalTime = (TextView) v.findViewById(R.id.totaltime);
            seekBar = (SeekBar) v.findViewById(R.id.seekbar);

            btnContextMenu = (ImageView) v.findViewById(R.id.context_menu);
            btnContextMenu.setOnCreateContextMenuListener(this);
            btnContextMenu.setOnClickListener(new OnClickListener(){
                @Override
                public void onClick(View v) {
                    v.showContextMenu();
                }
            });

            // Calculate the size of the album art to display, which will be the shorter
            // of the device's two dimensions.
            Display display = mActivity.getWindowManager().getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);
            mImageFetcher = new ImageFetcher(mActivity,
                    Math.min(displayMetrics.heightPixels, displayMetrics.widthPixels));
        } else {
            v = inflater.inflate(R.layout.now_playing_fragment_mini, container, false);

            // Get an ImageFetcher to scale artwork to the size of the icon view.
            Resources resources = getResources();
            int iconSize = (Math.max(
                    resources.getDimensionPixelSize(R.dimen.album_art_icon_height),
                    resources.getDimensionPixelSize(R.dimen.album_art_icon_width)));
            mImageFetcher = new ImageFetcher(mActivity, iconSize);
        }

        // TODO: Clean this up.  I think a better approach is to create the cache
        // in the activity that hosts the fragment, and make the cache available to
        // the fragment (or, make the cache a singleton across the whole app).
        mImageFetcher.setLoadingImage(R.drawable.icon_pending_artwork);
        mImageCacheParams = new ImageCacheParams(mActivity, "artwork");
        mImageCacheParams.setMemCacheSizePercent(mActivity, 0.12f);

        albumArt = (ImageView) v.findViewById(R.id.album);
        trackText = (TextView) v.findViewById(R.id.trackname);
        albumText = (TextView) v.findViewById(R.id.albumname);
        playPauseButton = (ImageButton) v.findViewById(R.id.pause);

        // Marquee effect on TextViews only works if they're selected.
        trackText.setSelected(true);
        albumText.setSelected(true);
        if(artistText != null) artistText.setSelected(true);

        playPauseButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mService == null)
                    return;
                try {
                    if (isConnected()) {
                        Log.v(TAG, "Pause...");
                        mService.togglePausePlay();
                    } else {
                        // When we're not connected, the play/pause
                        // button turns into a green connect button.
                        onUserInitiatesConnect();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Service exception from togglePausePlay(): " + e);
                }
            }
        });

        if (mFullHeightLayout) {
            /*
             * TODO: Simplify these following the notes at
             * http://developer.android.com/resources/articles/ui-1.6.html.
             * Maybe. because the TextView resources don't support the
             * android:onClick attribute.
             */
            nextButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (mService == null)
                        return;
                    try {
                        mService.nextTrack();
                    } catch (RemoteException e) {
                    }
                }
            });

            prevButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (mService == null)
                        return;
                    try {
                        mService.previousTrack();
                    } catch (RemoteException e) {
                    }
                }
            });

            shuffleButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (mService == null)
                        return;
                    try {
                        mService.toggleShuffle();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Service exception from toggleShuffle(): " + e);
                    }
                }
            });

            repeatButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (mService == null)
                        return;
                    try {
                        mService.toggleRepeat();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Service exception from toggleRepeat(): " + e);
                    }
                }
            });

            seekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                SqueezerSong seekingSong;

                // Update the time indicator to reflect the dragged thumb
                // position.
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (fromUser) {
                        currentTime.setText(Util.makeTimeString(progress));
                    }
                }

                // Disable updates when user drags the thumb.
                public void onStartTrackingTouch(SeekBar s) {
                    seekingSong = getCurrentSong();
                    updateSeekBar = false;
                }

                // Re-enable updates. If the current song is the same as when
                // we started seeking then jump to the new point in the track,
                // otherwise ignore the seek.
                public void onStopTrackingTouch(SeekBar s) {
                    SqueezerSong thisSong = getCurrentSong();

                    updateSeekBar = true;

                    if (seekingSong == thisSong) {
                        setSecondsElapsed(s.getProgress());
                    }
                }
            });
        } else {
            // Clicking on the layout goes to NowPlayingActivity.
            v.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    NowPlayingActivity.show(mActivity);
                }
            });
        }

        return v;
    }

    /**
     * Use this to post Runnables to work off thread
     */
    public Handler getUIThreadHandler() {
        return uiThreadHandler;
    }

    // Should only be called the UI thread.
    private void setConnected(boolean connected, boolean postConnect, boolean loginFailure) {
        Log.v(TAG, "setConnected(" + connected + ", " + postConnect + ", " + loginFailure + ")");
        if (postConnect) {
            clearConnectingDialog();
            if (!connected) {
                // TODO: Make this a dialog? Allow the user to correct the
                // server settings here?
                try {
                Toast.makeText(mActivity, getText(R.string.connection_failed_text),
                        Toast.LENGTH_LONG)
                        .show();
                } catch (IllegalStateException e) {
                    // We are not allowed to show a toast at this point, but
                    // the Toast is not important so we ignore it.
                    Log.i(TAG, "Toast was not allowed: " + e);
                }
            }
        }
        if (loginFailure) {
            Toast.makeText(mActivity, getText(R.string.login_failed_text), Toast.LENGTH_LONG).show();
            new SqueezerAuthenticationDialog().show(mActivity.getSupportFragmentManager(), "AuthenticationDialog");
        }

        setMenuItemStateFromConnection();

        if (mFullHeightLayout) {
            nextButton.setEnabled(connected);
            prevButton.setEnabled(connected);
            shuffleButton.setEnabled(connected);
            repeatButton.setEnabled(connected);
        }

        if (!connected) {
            updateSongInfo(null);

            playPauseButton.setImageResource(R.drawable.presence_online); // green circle

            if (mFullHeightLayout) {
                albumArt.setImageResource(R.drawable.icon_album_noart_fullscreen);
                nextButton.setImageResource(0);
                prevButton.setImageResource(0);
                shuffleButton.setImageResource(0);
                repeatButton.setImageResource(0);
                updateUIForPlayer(null);
                artistText.setText(getText(R.string.disconnected_text));
                currentTime.setText("--:--");
                totalTime.setText("--:--");
                seekBar.setEnabled(false);
                seekBar.setProgress(0);
            } else
                albumArt.setImageResource(R.drawable.icon_album_noart);
        } else {
            if (mFullHeightLayout) {
                nextButton.setImageResource(android.R.drawable.ic_media_next);
                prevButton.setImageResource(android.R.drawable.ic_media_previous);
                seekBar.setEnabled(true);
            }
        }
    }

    private void updatePlayPauseIcon(PlayStatus playStatus) {
        playPauseButton
                .setImageResource((playStatus == PlayStatus.play) ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
    }

    private void updateShuffleStatus(ShuffleStatus shuffleStatus) {
        if (mFullHeightLayout && shuffleStatus != null) {
            shuffleButton.setImageResource(shuffleStatus.getIcon());
        }
    }

    private void updateRepeatStatus(RepeatStatus repeatStatus) {
        if (mFullHeightLayout && repeatStatus != null) {
            repeatButton.setImageResource(repeatStatus.getIcon());
        }
    }

    private void updateUIForPlayer(SqueezerPlayer player) {
        if (mFullHeightLayout) {
            mActivity.setTitle(player != null ? player.getName() : getText(R.string.app_name));
        }
    }

    private void updatePowerMenuItems(boolean canPowerOn, boolean canPowerOff) {
        boolean connected = isConnected();

        if (menu_item_poweron != null) {
            if (canPowerOn && connected) {
                SqueezerPlayer player = getActivePlayer();
                String playerName = player != null ? player.getName() : "";
                menu_item_poweron.setTitle(getString(R.string.menu_item_poweron, playerName));
                menu_item_poweron.setVisible(true);
            } else {
                menu_item_poweron.setVisible(false);
            }
        }

        if (menu_item_poweroff != null) {
            if (canPowerOff && connected) {
                SqueezerPlayer player = getActivePlayer();
                String playerName = player != null ? player.getName() : "";
                menu_item_poweroff.setTitle(getString(R.string.menu_item_poweroff, playerName));
                menu_item_poweroff.setVisible(true);
            } else {
                menu_item_poweroff.setVisible(false);
            }
        }
    }

    protected void onServiceConnected() {
        Log.v(TAG, "Service bound");
        maybeRegisterCallbacks();
        uiThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                updateUIFromServiceState();
            }
        });

        // Assume they want to connect...
        if (!isConnected()) {
            startVisibleConnection();
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume...");

        mVolumePanel = new VolumePanel(mActivity);

        mImageFetcher.addImageCache(mActivity.getSupportFragmentManager(), mImageCacheParams);

        // Start it and have it run forever (until it shuts itself down).
        // This is required so swapping out the activity (and unbinding the
        // service connection in onDestroy) doesn't cause the service to be
        // killed due to zero refcount.  This is our signal that we want
        // it running in the background.
        mActivity.startService(new Intent(mActivity, SqueezeService.class));

        if (mService != null) {
            maybeRegisterCallbacks();
            updateUIFromServiceState();
        }

        if (isAutoConnect(getSharedPreferences()))
            mActivity.registerReceiver(broadcastReceiver, new IntentFilter(
                    ConnectivityManager.CONNECTIVITY_ACTION));
    }

    /** Keep track of whether callbacks have been registered */
    private boolean mRegisteredCallbacks;

    /**
     * This is called when the service is first connected, and whenever the
     * activity is resumed.
     */
    private void maybeRegisterCallbacks() {
        if (!mRegisteredCallbacks) {
            try {
                mService.registerCallback(serviceCallback);
                mService.registerHandshakeCallback(handshakeCallback);
                mService.registerMusicChangedCallback(musicChangedCallback);
                mService.registerVolumeCallback(volumeCallback);
            } catch (RemoteException e) {
                Log.e(getTag(), "Error registering callback: " + e);
            }
            mRegisteredCallbacks = true;
        }
    }

    // Should only be called from the UI thread.
    private void updateUIFromServiceState() {
        // Update the UI to reflect connection state. Basically just for
        // the initial display, as changing the prev/next buttons to empty
        // doesn't seem to work in onCreate. (LayoutInflator still running?)
        Log.d(TAG, "updateUIFromServiceState");
        boolean connected = isConnected();
        setConnected(connected, false, false);
        if (connected) {
            SqueezerPlayerState playerState = getPlayerState();
            updateSongInfo(playerState.getCurrentSong());
            updatePlayPauseIcon(playerState.getPlayStatus());
            updateTimeDisplayTo(playerState.getCurrentTimeSecond(), playerState.getCurrentSongDuration());
            updateUIForPlayer(getActivePlayer());
            updateShuffleStatus(playerState.getShuffleStatus());
            updateRepeatStatus(playerState.getRepeatStatus());
        }
    }

    private void updateTimeDisplayTo(int secondsIn, int secondsTotal) {
        if (mFullHeightLayout) {
            if (updateSeekBar) {
                if (seekBar.getMax() != secondsTotal) {
                    seekBar.setMax(secondsTotal);
                    totalTime.setText(Util.makeTimeString(secondsTotal));
                }
                seekBar.setProgress(secondsIn);
                currentTime.setText(Util.makeTimeString(secondsIn));
            }
        }
    }

    // Should only be called from the UI thread.
    private void updateSongInfo(SqueezerSong song) {
        Log.v(TAG, "updateSongInfo " + song);
        if (song != null) {
            albumText.setText(song.getAlbumName());
            trackText.setText(song.getName());
            if (mFullHeightLayout) {
                artistText.setText(song.getArtist());
                if (song.isRemote()) {
                    btnContextMenu.setVisibility(View.GONE);
                } else {
                    btnContextMenu.setVisibility(View.VISIBLE);
                }
            }
        } else {
            albumText.setText("");
            trackText.setText("");
            if (mFullHeightLayout) {
                artistText.setText("");
                btnContextMenu.setVisibility(View.GONE);
            }
        }
        updateAlbumArt(song);
    }

    // Should only be called from the UI thread.
    private void updateAlbumArt(SqueezerSong song) {
        if (song == null || song.getArtworkUrl(mService) == null) {
            if (mFullHeightLayout)
                albumArt.setImageResource(song != null && song.isRemote()
                        ? R.drawable.icon_iradio_noart_fullscreen
                        : R.drawable.icon_album_noart_fullscreen);
            else
                albumArt.setImageResource(song != null && song.isRemote()
                        ? R.drawable.icon_iradio_noart
                        : R.drawable.icon_album_noart);
            return;
        }

        // The image fetcher might not be ready yet.
        if (mImageFetcher == null)
            return;

        mImageFetcher.loadImage(song.getArtworkUrl(mService), albumArt);
    }

    private boolean setSecondsElapsed(int seconds) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.setSecondsElapsed(seconds);
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in setSecondsElapsed(" + seconds + "): " + e);
        }
        return true;
    }

    private SqueezerPlayerState getPlayerState() {
        if (mService == null) {
            return null;
        }
        try {
            return mService.getPlayerState();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in getPlayerState(): " + e);
        }
        return null;
    }
    
    private SqueezerPlayer getActivePlayer() {
        if (mService == null) {
            return null;
        }
        try {
            return mService.getActivePlayer();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in getActivePlayer(): " + e);
        }
        return null;
    }

    private SqueezerSong getCurrentSong() {
        SqueezerPlayerState playerState = getPlayerState();
        return playerState != null ? playerState.getCurrentSong() : null;
    }

    private boolean isConnected() {
        if (mService == null) {
            return false;
        }
        try {
            return mService.isConnected();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in isConnected(): " + e);
        }
        return false;
    }

    private boolean isConnectInProgress() {
        if (mService == null) {
            return false;
        }
        try {
            return mService.isConnectInProgress();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in isConnectInProgress(): " + e);
        }
        return false;
    }

    private boolean canPowerOn() {
        if (mService == null) {
            return false;
        }
        try {
            return mService.canPowerOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in canPowerOn(): " + e);
        }
        return false;
    }

    private boolean canPowerOff() {
        if (mService == null) {
            return false;
        }
        try {
            return mService.canPowerOff();
        } catch (RemoteException e) {
            Log.e(TAG, "Service exception in canPowerOff(): " + e);
        }
        return false;
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause...");

        mVolumePanel.dismiss();
        clearConnectingDialog();
        mImageFetcher.closeCache();

        if (isAutoConnect(getSharedPreferences()))
            mActivity.unregisterReceiver(broadcastReceiver);
        
        if (mRegisteredCallbacks) {
            try {
                mService.unregisterCallback(serviceCallback);
                mService.unregisterMusicChangedCallback(musicChangedCallback);
                mService.unregisterHandshakeCallback(handshakeCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Service exception in onPause(): " + e);
            }
            mRegisteredCallbacks = false;
        }
        
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mService != null) {
            if (serviceConnection != null) {
                mActivity.unbindService(serviceConnection);
            }
        }
    }

    /**
     * Builds a context menu suitable for the currently playing song.
     * <p>
     * Takes the general song context menu, and disables items that make no sense for the song
     * that is currently playing.
     * <p>
     * {@inheritDoc}
     * @param menu
     * @param v
     * @param menuInfo
     */
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.songcontextmenu, menu);

        menu.findItem(R.id.play_now).setVisible(false);
        menu.findItem(R.id.play_next).setVisible(false);
        menu.findItem(R.id.add_to_playlist).setVisible(false);

        menu.findItem(R.id.view_this_album).setVisible(true);
        menu.findItem(R.id.view_albums_by_song).setVisible(true);
        menu.findItem(R.id.view_songs_by_artist).setVisible(true);
    }

    /**
     * Handles clicks on the context menu.
     * <p>
     * {@inheritDoc}
     * @param item
     * @return
     */
    public boolean onContextItemSelected(MenuItem item) {
        SqueezerSong song = getCurrentSong();
        if (song == null || song.isRemote())
            return false;

        // Note: Very similar to code in SqueezerSongView:doItemContext().  Refactor?
        switch (item.getItemId()) {
            case R.id.download:
                mActivity.downloadSong(song);
                return true;

            case R.id.view_this_album:
                SqueezerSongListActivity.show(getActivity(), song.getAlbum());
                return true;

            case R.id.view_albums_by_song:
                SqueezerAlbumListActivity.show(getActivity(),
                        new SqueezerArtist(song.getArtist_id(), song.getArtist()));
                return true;

            case R.id.view_songs_by_artist:
                SqueezerSongListActivity.show(getActivity(),
                        new SqueezerArtist(song.getArtist_id(), song.getArtist()));
                return true;

            default:
                throw new IllegalStateException("Unknown menu ID.");
        }
    }

    /**
     * @see android.support.v4.app.Fragment#onCreateOptionsMenu(android.view.Menu,
     *      android.view.MenuInflater)
     */
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        // I confess that I don't understand why using the inflater passed as
        // an argument here doesn't work -- but if you do it crashes without
        // a stracktrace on API 7.
        MenuInflater i = mActivity.getMenuInflater();
        i.inflate(R.menu.squeezer, menu);

        menu_item_connect = mActivity.getActionBarHelper().findItem(R.id.menu_item_connect);
        menu_item_disconnect = mActivity.getActionBarHelper().findItem(R.id.menu_item_disconnect);
        menu_item_poweron = mActivity.getActionBarHelper().findItem(R.id.menu_item_poweron);
        menu_item_poweroff = mActivity.getActionBarHelper().findItem(R.id.menu_item_poweroff);
        menu_item_players = mActivity.getActionBarHelper().findItem(R.id.menu_item_players);
        menu_item_playlists = mActivity.getActionBarHelper().findItem(R.id.menu_item_playlist);
        menu_item_search = mActivity.getActionBarHelper().findItem(R.id.menu_item_search);
        menu_item_volume = mActivity.getActionBarHelper().findItem(R.id.menu_item_volume);

        // On Android 2.3.x and lower onCreateOptionsMenu() is called when the menu is opened,
        // almost certainly post-connection to the service.  On 3.0 and higher it's called when
        // the activity is created, before the service connection is made.  Set the visibility
        // of the menu items accordingly.
        // XXX: onPrepareOptionsMenu() instead?
        setMenuItemStateFromConnection();
    }

    /**
     * Sets the state of assorted option menu items based on whether or not there is a
     * connection to the server.
     */
    private void setMenuItemStateFromConnection() {
        boolean connected = isConnected();

        // These are all set at the same time, so one check is sufficient
        if (menu_item_connect != null) {
            menu_item_connect.setVisible(!connected);
            menu_item_disconnect.setVisible(connected);
            menu_item_players.setEnabled(connected);
            menu_item_playlists.setEnabled(connected);
            menu_item_search.setEnabled(connected);
            menu_item_volume.setEnabled(connected);
        }

        updatePowerMenuItems(canPowerOn(), canPowerOff());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_item_settings:
                SettingsActivity.show(mActivity);
                return true;
            case R.id.menu_item_search:
                mActivity.onSearchRequested();
                return true;
            case R.id.menu_item_connect:
                onUserInitiatesConnect();
                return true;
            case R.id.menu_item_disconnect:
                try {
                    mService.disconnect();
                } catch (RemoteException e) {
                    Toast.makeText(mActivity, e.toString(),
                            Toast.LENGTH_LONG).show();
                }
                return true;
            case R.id.menu_item_poweron:
                try {
                    mService.powerOn();
                } catch (RemoteException e) {
                    Toast.makeText(mActivity, e.toString(), Toast.LENGTH_LONG).show();
                }
                return true;
            case R.id.menu_item_poweroff:
                try {
                    mService.powerOff();
                } catch (RemoteException e) {
                    Toast.makeText(mActivity, e.toString(),
                            Toast.LENGTH_LONG).show();
                }
                return true;
            case R.id.menu_item_playlist:
                SqueezerCurrentPlaylistActivity.show(mActivity);
                break;
            case R.id.menu_item_players:
                SqueezerPlayerListActivity.show(mActivity);
                return true;
            case R.id.menu_item_about:
                new AboutDialog().show(getFragmentManager(), "AboutDialog");
                return true;
            case R.id.menu_item_volume:
                // Show the volume dialog
                SqueezerPlayerState playerState = getPlayerState();
                SqueezerPlayer player = getActivePlayer();

                if (playerState != null) {
                    mVolumePanel.postVolumeChanged(playerState.getCurrentVolume(),
                            player == null ? "" : player.getName());
                }
                return true;
        }
        return false;
    }

    private SharedPreferences getSharedPreferences() {
        return mActivity.getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE);
    }

    private String getConfiguredCliIpPort(final SharedPreferences preferences) {
        return getStringPreference(preferences, Preferences.KEY_SERVERADDR, null);
    }

    private String getConfiguredUserName(final SharedPreferences preferences) {
        return getStringPreference(preferences, Preferences.KEY_USERNAME, "test");
    }

    private String getConfiguredPassword(final SharedPreferences preferences) {
        return getStringPreference(preferences, Preferences.KEY_PASSWORD, "test1");
    }

    private String getStringPreference(final SharedPreferences preferences, String preference, String defaultValue) {
        final String pref = preferences.getString(preference, null);
        if (pref == null || pref.length() == 0) {
            return defaultValue;
        }
        return pref;
    }

    private boolean isAutoConnect(final SharedPreferences preferences) {
        return preferences.getBoolean(Preferences.KEY_AUTO_CONNECT, true);
    }

    private void onUserInitiatesConnect() {
        // Set up a server connection, if it is not present
        if (getConfiguredCliIpPort(getSharedPreferences()) == null) {
            SettingsActivity.show(mActivity);
            return;
        }

        if (mService == null) {
            Log.e(TAG, "serviceStub is null.");
            return;
        }
        startVisibleConnection();
    }

    public void startVisibleConnection() {
        Log.v(TAG, "startVisibleConnection");
        uiThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                SharedPreferences preferences = getSharedPreferences();
                String ipPort = getConfiguredCliIpPort(preferences);
                if (ipPort == null)
                    return;

                // If we are configured to automatically connect on Wi-Fi availability
                // we will also give the user the opportunity to enable Wi-Fi
                if (isAutoConnect(preferences)) {
                    WifiManager wifiManager = (WifiManager) mActivity
                            .getSystemService(Context.WIFI_SERVICE);
                    if (!wifiManager.isWifiEnabled()) {
                        FragmentManager fragmentManager = getFragmentManager();
                        if (fragmentManager != null) {
                            EnableWifiDialog.show(getFragmentManager());
                        } else {
                            Log.i(getTag(), "fragment manager is null so we can't show EnableWifiDialog");
                        }
                        return;
                        // When a Wi-Fi connection is made this method will be called again by the
                        // broadcastReceiver
                    }
                }

                if (isConnectInProgress()) {
                    Log.v(TAG, "Connection is allready in progress, connecting aborted");
                    return;
                }
                try {
                    connectingDialog = ProgressDialog.show(mActivity,
                            getText(R.string.connecting_text),
                            getString(R.string.connecting_to_text, ipPort), true, false);
                    Log.v(TAG, "startConnect, ipPort: " + ipPort);
                    try {
                        getConfiguredCliIpPort(preferences);
                        mService.startConnect(ipPort, getConfiguredUserName(preferences), getConfiguredPassword(preferences));
                    } catch (RemoteException e) {
                        Toast.makeText(mActivity, "startConnection error: " + e,
                                Toast.LENGTH_LONG).show();
                    }
                } catch (IllegalStateException e) {
                    Log.i(TAG, "ProgressDialog.show() was not allowed, connecting aborted: " + e);
                    connectingDialog = null;
                }
            }
        });
    }

    private final IServiceCallback serviceCallback = new IServiceCallback.Stub() {
        @Override
        public void onConnectionChanged(final boolean isConnected,
                                        final boolean postConnect,
                                        final boolean loginFailed)
                       throws RemoteException {
            Log.v(TAG, "Connected == " + isConnected + " (postConnect==" + postConnect + ")");
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    setConnected(isConnected, postConnect, loginFailed);
                }
            });
        }

        @Override
        public void onPlayerChanged(final SqueezerPlayer player) throws RemoteException {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateUIForPlayer(player);
                }
            });
        }

        @Override
        public void onPlayStatusChanged(final String playStatusName)
        {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    updatePlayPauseIcon(PlayStatus.valueOf(playStatusName));
                }
            });
        }

        @Override
        public void onShuffleStatusChanged(final boolean initial, final int shuffleStatusId)
        {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    ShuffleStatus shuffleStatus = ShuffleStatus.valueOf(shuffleStatusId);
                    updateShuffleStatus(shuffleStatus);
                    if (!initial)
                        Toast.makeText(mActivity, mActivity.getServerString(shuffleStatus.getText()), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRepeatStatusChanged(final boolean initial, final int repeatStatusId)
        {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    RepeatStatus repeatStatus = RepeatStatus.valueOf(repeatStatusId);
                    updateRepeatStatus(repeatStatus);
                    if (!initial)
                        Toast.makeText(mActivity, mActivity.getServerString(repeatStatus.getText()), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onTimeInSongChange(final int secondsIn, final int secondsTotal)
                throws RemoteException {
            NowPlayingFragment.this.secondsIn = secondsIn;
            NowPlayingFragment.this.secondsTotal = secondsTotal;
            uiThreadHandler.sendEmptyMessage(UPDATE_TIME);
        }

        @Override
        public void onPowerStatusChanged(final boolean canPowerOn, final boolean canPowerOff) throws RemoteException {
            uiThreadHandler.post(new Runnable() {
                public void run() {
                    updatePowerMenuItems(canPowerOn, canPowerOff);
                }
            });
        }
    };

    private final IServiceMusicChangedCallback musicChangedCallback = new IServiceMusicChangedCallback.Stub() {
        @Override
        public void onMusicChanged(final SqueezerPlayerState playerState) throws RemoteException {
            uiThreadHandler.post(new Runnable() {
                public void run() {
                    updateSongInfo(playerState.getCurrentSong());
                }
            });
        }
    };
    
    private final IServiceHandshakeCallback handshakeCallback = new IServiceHandshakeCallback.Stub() {
        @Override
        public void onHandshakeCompleted() throws RemoteException {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    updatePowerMenuItems(canPowerOn(), canPowerOff());
                }
            });
        }
    };

    private final IServiceVolumeCallback volumeCallback = new IServiceVolumeCallback.Stub() {
        @Override
        public void onVolumeChanged(final int newVolume, final SqueezerPlayer player) throws RemoteException {
            uiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    mVolumePanel.postVolumeChanged(newVolume, player == null ? "" : player.getName());
                }
            });
        }
    };
}
