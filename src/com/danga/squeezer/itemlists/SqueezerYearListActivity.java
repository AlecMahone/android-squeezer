package com.danga.squeezer.itemlists;

import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import com.danga.squeezer.framework.SqueezerBaseListActivity;
import com.danga.squeezer.framework.SqueezerItemView;
import com.danga.squeezer.model.SqueezerYear;
import com.danga.squeezer.service.SqueezerServerState;

public class SqueezerYearListActivity extends SqueezerBaseListActivity<SqueezerYear>{

	@Override
	public SqueezerItemView<SqueezerYear> createItemView() {
		return new SqueezerYearView(this);
	}

	@Override
	protected void registerCallback() throws RemoteException {
		getService().registerYearListCallback(yearListCallback);
	}

	@Override
	protected void unregisterCallback() throws RemoteException {
		getService().unregisterYearListCallback(yearListCallback);
	}

	@Override
	protected void orderPage(int start) throws RemoteException {
		getService().years(start);
	}

	@Override
	protected void onItemSelected(int index, SqueezerYear item) throws RemoteException {
		SqueezerAlbumListActivity.show(this, item);
	}


	public static void show(Context context) {
        final Intent intent = new Intent(context, SqueezerYearListActivity.class);
        context.startActivity(intent);
    }

    private final IServiceYearListCallback yearListCallback = new IServiceYearListCallback.Stub() {
		public void onYearsReceived(int count, int start, List<SqueezerYear> items) throws RemoteException {
			onItemsReceived(count, start, items);
		}

        public void onServerStateChanged(SqueezerServerState oldState, SqueezerServerState newState)
                throws RemoteException {
            // TODO Auto-generated method stub

        }
    };

}
