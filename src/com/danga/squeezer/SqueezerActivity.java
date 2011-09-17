package com.danga.squeezer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.text.Html;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.danga.squeezer.framework.SqueezerBaseActivity;
import com.danga.squeezer.framework.SqueezerIconUpdater;
import com.danga.squeezer.itemlists.SqueezerAlbumListActivity;
import com.danga.squeezer.itemlists.SqueezerCurrentPlaylistActivity;
import com.danga.squeezer.itemlists.SqueezerPlayerListActivity;
import com.danga.squeezer.itemlists.SqueezerSongListActivity;
import com.danga.squeezer.model.SqueezerAlbum;
import com.danga.squeezer.model.SqueezerArtist;
import com.danga.squeezer.model.SqueezerSong;
import com.danga.squeezer.service.SqueezeService;

public class SqueezerActivity extends SqueezerBaseActivity {
    private static final int DIALOG_ABOUT = 0;
    private static final int DIALOG_CONNECTING = 1;

    protected static final int HOME_REQUESTCODE = 0;
    
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private AtomicBoolean isPlaying = new AtomicBoolean(false);
    private AtomicReference<SqueezerSong> currentSong = new AtomicReference<SqueezerSong>();
    
    private TextView albumText;
    private TextView artistText;
    private TextView trackText;   
    private TextView currentTime;
    private TextView totalTime;   
    private ImageButton homeButton;
    private ImageButton curPlayListButton;
    private ImageButton playPauseButton;
    private ImageButton nextButton;
    private ImageButton prevButton;
    private ImageView albumArt;
    private SeekBar seekBar;

    private final SqueezerIconUpdater<SqueezerSong> iconUpdater = new SqueezerIconUpdater<SqueezerSong>(this);
    
    // Where we're connecting to.
    private boolean connectInProgress = false;
    private String connectingTo = null;
    private ProgressDialog connectingDialog = null;

    // Updating the seekbar
    private boolean updateSeekBar = true;
    private volatile int secondsIn;
    private volatile int secondsTotal;
    private final static int UPDATE_TIME = 1;
    
    private Handler uiThreadHandler = new Handler() {
        // Normally I'm lazy and just post Runnables to the uiThreadHandler
        // but time updating is special enough (it happens every second) to
        // take care not to allocate so much memory which forces Dalvik to GC
        // all the time.
        @Override
        public void handleMessage (Message msg) {
            if (msg.what == UPDATE_TIME) {
                updateTimeDisplayTo(secondsIn, secondsTotal);
            }
        }
    };
    
    @Override
	public Handler getUIThreadHandler() {
    	return uiThreadHandler;
    }

    /** Called when the activity is first created. */
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
              
        albumText = (TextView) findViewById(R.id.albumname);
        artistText = (TextView) findViewById(R.id.artistname);
        trackText = (TextView) findViewById(R.id.trackname);
        homeButton = (ImageButton) findViewById(R.id.ic_mp_Home_btn);
        curPlayListButton = (ImageButton) findViewById(R.id.curplaylist);
        playPauseButton = (ImageButton) findViewById(R.id.pause);
        nextButton = (ImageButton) findViewById(R.id.next);
        prevButton = (ImageButton) findViewById(R.id.prev);
        albumArt = (ImageView) findViewById(R.id.album);
        currentTime = (TextView) findViewById(R.id.currenttime);
        totalTime = (TextView) findViewById(R.id.totaltime);
        seekBar = (SeekBar) findViewById(R.id.seekbar);

        // TODO: Simplify these following the notes at
        // http://developer.android.com/resources/articles/ui-1.6.html
		homeButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
                if (!isConnected()) return;
				SqueezerHomeActivity.show(SqueezerActivity.this);
			}
		});
		
		curPlayListButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
                if (!isConnected()) return;
				SqueezerCurrentPlaylistActivity.show(SqueezerActivity.this);
			}
		});
		
        playPauseButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (getService() == null) return;
                    try {
                        if (isConnected.get()) {
                            Log.v(getTag(), "Pause...");
                            getService().togglePausePlay();
                        } else {
                            // When we're not connected, the play/pause
                            // button turns into a green connect button.
                            onUserInitiatesConnect();
                        }
                    } catch (RemoteException e) {
                        Log.e(getTag(), "Service exception from togglePausePlay(): " + e);
                    }
                }
	    });
        
        nextButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (getService() == null) return;
                try {
                    getService().nextTrack();
                } catch (RemoteException e) { }
            }
        });

        prevButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (getService() == null) return;
                try {
                    getService().previousTrack();
                } catch (RemoteException e) { }
            }
        });

        artistText.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				SqueezerSong song = getCurrentSong();
				if (song != null) {
					if (!song.isRemote())
						SqueezerAlbumListActivity.show(SqueezerActivity.this, new SqueezerArtist(song.getArtist_id(), song.getArtist()));
				}
			}
		});
        
        albumText.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				SqueezerSong song = getCurrentSong();
				if (song != null) {
					if (!song.isRemote())
						SqueezerSongListActivity.show(SqueezerActivity.this, new SqueezerAlbum(song.getAlbum_id(), song.getAlbum()));
				}
			}
		});
        
        trackText.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				SqueezerSong song = getCurrentSong();
				if (song != null) {
					if (!song.isRemote())
						SqueezerSongListActivity.show(SqueezerActivity.this, new SqueezerArtist(song.getArtist_id(), song.getArtist()));
				}
			}
		});
        
        seekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
        	SqueezerSong seekingSong;
        	
        	// Update the time indicator to reflect the dragged thumb position.
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

			// Re-enable updates.  If the current song is the same as when
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
    }
    
    /*
     * Intercept hardware volume control keys to control Squeezeserver
     * volume.
     * 
     * Change the volume when the key is depressed.  Suppress the keyUp
     * event, otherwise you get a notification beep as well as the volume
     * changing.
     */
    @Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_UP:
			changeVolumeBy(+5);
			return true;
		case KeyEvent.KEYCODE_VOLUME_DOWN:
			changeVolumeBy(-5);
			return true;
		}
		
		return super.onKeyDown(keyCode, event);
	}

    @Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_VOLUME_UP:
		case KeyEvent.KEYCODE_VOLUME_DOWN:
			return true;
		}
		
		return super.onKeyUp(keyCode, event);
	}    
    
    private boolean changeVolumeBy(int delta) {
        Log.v(getTag(), "Adjust volume by: " + delta);
        if (getService() == null) {
            return false;
        }
        try {
            getService().adjustVolumeBy(delta);
            return true;
        } catch (RemoteException e) {
        }
        return false;
    }

    // Should only be called the UI thread.
    private void setConnected(boolean connected, boolean postConnect) {
        Log.v(getTag(), "setConnected(" + connected + ", " + postConnect + ")");
        if (postConnect) {
            connectInProgress = false;
            Log.v(getTag(), "Post-connect; connectingDialog == " + connectingDialog);
            if (connectingDialog != null) {
                Log.v(getTag(), "Dismissing...");
                connectingDialog.dismiss();
                connectInProgress = false;
                if (!connected) {
                  Toast.makeText(this, getText(R.string.connection_failed_text), Toast.LENGTH_LONG).show();
                  return;
                }
            }
        }

    	isConnected.set(connected);
    	nextButton.setEnabled(connected);
    	prevButton.setEnabled(connected);
    	if (!connected) {
            nextButton.setImageResource(0);
            prevButton.setImageResource(0);
            albumArt.setImageDrawable(null);
            updateSongInfo(null);
            artistText.setText(getText(R.string.disconnected_text));
            setTitleForPlayer(null);
            currentTime.setText("--:--");
            totalTime.setText("--:--");
            seekBar.setEnabled(false);
            seekBar.setProgress(0);
    	} else {
            nextButton.setImageResource(android.R.drawable.ic_media_next);
            prevButton.setImageResource(android.R.drawable.ic_media_previous);
            updateSongInfoFromService();
            seekBar.setEnabled(true);
    	}
    	updatePlayPauseIcon();
    }

    private void updatePlayPauseIcon() {
        uiThreadHandler.post(new Runnable() {
            public void run() {
                if (!isConnected.get()) {
                    playPauseButton.setImageResource(android.R.drawable.presence_online);  // green circle
                } else if (isPlaying.get()) {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                } else {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play);
                }
            }
        });
    }

    // May be called from any thread.
    private void setTitleForPlayer(final String playerName) {
        uiThreadHandler.post(new Runnable() {
            public void run() {
                if (playerName != null && !"".equals(playerName)) {
                    setTitle(getText(R.string.app_name) + ": " + playerName);
                } else {
                    setTitle(getText(R.string.app_name));
                }
            }
        });
    }

	@Override
	protected void onServiceConnected() throws RemoteException {
    	Log.v(getTag(), "Service bound");
    	uiThreadHandler.post(new Runnable() {
    	    public void run() {
    	        updateUIFromServiceState();

    	        // Assume they want to connect...
    	        if (!isConnected()) {
    	            String ipPort = getConfiguredCliIpPort();
    	            if (ipPort != null) {
    	                startVisibleConnectionTo(ipPort);
    	            }
    	        }
    	    }
    	});
   	    getService().registerCallback(serviceCallback);
	}

    @Override
    public void onResume() {
        super.onResume();
        Log.d(getTag(), "onResume...");

        // Start it and have it run forever (until it shuts itself down).
        // This is required so swapping out the activity (and unbinding the
        // service connection in onPause) doesn't cause the service to be
        // killed due to zero refcount.  This is our signal that we want
        // it running in the background.
        startService(new Intent(this, SqueezeService.class));
        
        if (getService() != null) {
            updateUIFromServiceState();
            
            // If they've already set this up in the past, what they probably
            // want to do at this point is connect to the server, so do it
            // automatically.  (Requires a serviceStub.  Else we'll do this
            // on the service connection callback.)
            if (!isConnected()) {
                String ipPort = getConfiguredCliIpPort();
                if (ipPort != null) {
                    startVisibleConnectionTo(ipPort);
                }
            }
        }
    }

    // Should only be called from the UI thread.
    private void updateUIFromServiceState() {
        // Update the UI to reflect connection state.  Basically just for
        // the initial display, as changing the prev/next buttons to empty
        // doesn't seem to work in onCreate.  (LayoutInflator still running?)
        setConnected(isConnected(), false);

        // TODO(bradfitz): remove this check once everything is converted into
        // safe accessors like isConnected() already is.
        if (getService() == null) {
            Log.e(getTag(), "Can't update UI with null serviceStub");
            return;
        }

        try {
            setTitleForPlayer(getService().getActivePlayerName());
            isPlaying.set(getService().isPlaying());
            updatePlayPauseIcon();
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception: " + e);
        }
       
    }
    
    private void updateTimeDisplayTo(int secondsIn, int secondsTotal) {
    	if (updateSeekBar) {
	        if (seekBar.getMax() != secondsTotal) {
	            seekBar.setMax(secondsTotal);
	            totalTime.setText(Util.makeTimeString(secondsTotal));
	        }
	        seekBar.setProgress(secondsIn);
	        currentTime.setText(Util.makeTimeString(secondsIn));
	    }
    }
    
    // Should only be called from the UI thread.
    private void updateSongInfoFromService() {
        SqueezerSong song = getCurrentSong();
    	updateSongInfo(song);
        updateTimeDisplayTo(getSecondsElapsed(), getSecondsTotal());
        updateAlbumArtIfNeeded(song);
    }
    
    private void updateSongInfo(SqueezerSong song) {
    	if (song != null) {
	        artistText.setText(song.getArtist());
	        albumText.setText(song.getAlbum());
	        trackText.setText(song.getName());
    	} else {
            artistText.setText("");
            albumText.setText("");
            trackText.setText("");
    	}
    }

    // Should only be called from the UI thread.
    private void updateAlbumArtIfNeeded(SqueezerSong song) {
        if (Util.atomicSongUpdated(currentSong, song))
        	iconUpdater.updateIcon(albumArt, song, song != null ? song.getArtworkUrl(getService()) : null);
    }
    
    private int getSecondsElapsed() {
        if (getService() == null) {
            return 0;
        }
        try {
            return getService().getSecondsElapsed();
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in getSecondsElapsed(): " + e);
        }
        return 0;
    }
    
    private int getSecondsTotal() {
        if (getService() == null) {
            return 0;
        }
        try {
            return getService().getSecondsTotal();
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in getSecondsTotal(): " + e);
        }
        return 0;
    }
    
    private boolean setSecondsElapsed(int seconds) {
    	if (getService() == null) {
    		return false;
    	}
    	try {
    		return getService().setSecondsElapsed(seconds);
    	} catch (RemoteException e) {
    		Log.e(getTag(), "Service exception in setSecondsElapsed(" + seconds + "): " + e);
    	}
    	return true;
    }
    
    private SqueezerSong getCurrentSong() {
        if (getService() == null) {
            return null;
        }
        try {
            return getService().currentSong();
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in getServiceCurrentSong(): " + e);
        }
        return null;
    }


    private boolean isConnected() {
        if (getService() == null) {
            return false;
        }
        try {
            return getService().isConnected(); 
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in isConnected(): " + e);
        }
        return false;
    }

    private boolean canPowerOn() {
        if (getService() == null) {
            return false;
        }
        try {
            return getService().canPowerOn(); 
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in canPowerOn(): " + e);
        }
        return false;
    }

    private boolean canPowerOff() {
        if (getService() == null) {
            return false;
        }
        try {
            return getService().canPowerOff(); 
        } catch (RemoteException e) {
            Log.e(getTag(), "Service exception in canPowerOff(): " + e);
        }
        return false;
    }

    @Override
    public void onPause() {
        if (getService() != null) {
            try {
                getService().unregisterCallback(serviceCallback);
            } catch (RemoteException e) {
                Log.e(getTag(), "Service exception in onPause(): " + e);
            }
        }
        super.onPause();
    }
	
	@Override
	public boolean onSearchRequested() {
  		SqueezerSearchActivity.show(this);
		return false;
	}
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.squeezer, menu);
        return super.onCreateOptionsMenu(menu);
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
    	super.onPrepareOptionsMenu(menu);
    	boolean connected = isConnected.get();

    	// Only show one of connect and disconnect:
    	MenuItem connect = menu.findItem(R.id.menu_item_connect);
    	connect.setVisible(!connected);
    	MenuItem disconnect = menu.findItem(R.id.menu_item_disconnect);
    	disconnect.setVisible(connected);

    	// Only show power on/off, according to playerstate
    	MenuItem powerOn = menu.findItem(R.id.menu_item_poweron);
    	powerOn.setVisible(canPowerOn());
    	MenuItem powerOff = menu.findItem(R.id.menu_item_poweroff);
    	powerOff.setVisible(canPowerOff());

    	// Disable things that don't work when not connected.
        MenuItem players = menu.findItem(R.id.menu_item_players);
        players.setEnabled(connected);
        MenuItem search = menu.findItem(R.id.menu_item_search);
        search.setEnabled(connected);

    	return true;	
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
        case DIALOG_ABOUT:
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getText(R.string.about_title));
            PackageManager pm = getPackageManager();
            PackageInfo info;
            String aboutText;
            try {
                info = pm.getPackageInfo("com.danga.squeezer", 0);
                aboutText = getString(R.string.about_text, info.versionName);
            } catch (NameNotFoundException e) {
                aboutText = "Package not found.";
            }
            builder.setMessage(Html.fromHtml(aboutText));
            return builder.create();
        case DIALOG_CONNECTING:
            // Note: this only happens the first time.  onPrepareDialog is called on each connect.
            connectingDialog = new ProgressDialog(this);
            connectingDialog.setTitle(getText(R.string.connecting_text));
            connectingDialog.setIndeterminate(true);
            return connectingDialog;
        }
        return null;
    }
    
    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
        case DIALOG_CONNECTING:
            ProgressDialog connectingDialog = (ProgressDialog) dialog;
            connectingDialog.setMessage(getString(R.string.connecting_to_text, connectingTo));
            if (!connectInProgress) {
                // Lose the race?  If Service/network is very fast.
                connectingDialog.dismiss();
            }
            return;
        }
    }
    
    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        switch (item.getItemId()) {
      	case R.id.menu_item_settings:
            SettingsActivity.show(this);
            return true;
      	case R.id.menu_item_search:
      		SqueezerSearchActivity.show(this);
      		return true;
      	case R.id.menu_item_connect:
      	    onUserInitiatesConnect();
            return true;
      	case R.id.menu_item_disconnect:
            try {
                getService().disconnect();
            } catch (RemoteException e) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
            }
            return true;
      	case R.id.menu_item_poweron:
            try {
                getService().powerOn();
            } catch (RemoteException e) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
            }
            return true;
      	case R.id.menu_item_poweroff:
            try {
                getService().powerOff();
            } catch (RemoteException e) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
            }
            return true;
      	case R.id.menu_item_players:
      		SqueezerPlayerListActivity.show(this);
      	    return true;
        case R.id.menu_item_about:
            showDialog(DIALOG_ABOUT);
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }

    // Returns null if not configured.
    private String getConfiguredCliIpPort() {
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, 0);
        final String ipPort = preferences.getString(Preferences.KEY_SERVERADDR, null);
        if (ipPort == null || ipPort.length() == 0) {
            return null;
        }
        return ipPort;
    }
    
    private void onUserInitiatesConnect() {
        if (getService() == null) {
            Log.e(getTag(), "serviceStub is null.");
            return;
        }
        String ipPort = getConfiguredCliIpPort();
        if (ipPort == null) {
            SettingsActivity.show(this);
            return;
        }
        Log.v(getTag(), "User-initiated connect to: " + ipPort);
        startVisibleConnectionTo(ipPort);
    }
    
    private void startVisibleConnectionTo(String ipPort) {
        connectInProgress = true;
        connectingTo = ipPort;
        showDialog(DIALOG_CONNECTING);
        try {
            getService().startConnect(ipPort);
        } catch (RemoteException e) {
            Toast.makeText(this, "startConnection error: " + e,
                    Toast.LENGTH_LONG).show();
        }
    }

	public static void show(Context context) {
		final Intent intent = new Intent(context, SqueezerActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
				.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }
	
    private IServiceCallback serviceCallback = new IServiceCallback.Stub() {
        public void onConnectionChanged(final boolean isConnected,
                                        final boolean postConnect)
                       throws RemoteException {
                Log.v(getTag(), "Connected == " + isConnected + " (postConnect==" + postConnect + ")");
                uiThreadHandler.post(new Runnable() {
                        public void run() {
                            setConnected(isConnected, postConnect);
                        }
                    });
            }

            public void onPlayerChanged(final String playerId,
                                        final String playerName) throws RemoteException {
                Log.v(getTag(), "player now " + playerId + ": " + playerName);
                setTitleForPlayer(playerName);
            }

            public void onMusicChanged() throws RemoteException {
                uiThreadHandler.post(new Runnable() {
                        public void run() {
                            updateSongInfoFromService();
                        }
                    });
            }

            public void onVolumeChange(final int newVolume) throws RemoteException {
                Log.v(getTag(), "Volume = " + newVolume);
//                uiThreadHandler.post(new Runnable() {              	
//                    public void run() {
//						  Do something here if necessary               
//                    }
//                });
            }

            public void onPlayStatusChanged(boolean newStatus)
                throws RemoteException {
                isPlaying.set(newStatus);
                updatePlayPauseIcon();
            }

            public void onTimeInSongChange(final int secondsIn, final int secondsTotal)
                    throws RemoteException {
                SqueezerActivity.this.secondsIn = secondsIn;
                SqueezerActivity.this.secondsTotal = secondsTotal;
                uiThreadHandler.sendEmptyMessage(UPDATE_TIME);
            }
        };

}
