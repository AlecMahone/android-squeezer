/*******************************************************************************
 * Copyright 2011, 2012 Chris Banes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package uk.org.ngo.squeezer.graphics.drawable;

import uk.org.ngo.squeezer.util.WeakReferenceRunnable;
import uk.org.ngo.squeezer.widget.CacheableImageView;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class CacheableBitmapDrawable extends BitmapDrawable {

    static final String LOG_TAG = "CacheableBitmapDrawable";
    private static final int UNUSED_DRAWABLE_RECYCLE_DELAY_MS = 2000;

    private final String mUrl;

    // Number of Views currently displaying bitmap
    private int mDisplayingCount;

    // Has it been displayed yet
    private boolean mHasBeenDisplayed;

    // Number of caches currently referencing the wrapper
    private int mCacheCount;

    // The CheckStateRunnable currently being delayed
    private Runnable mCheckStateRunnable;

    // Handler which may be used later
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    public CacheableBitmapDrawable(Bitmap bitmap) {
        this(null, bitmap);
    }

    @SuppressWarnings("deprecation")
    public CacheableBitmapDrawable(String url, Bitmap bitmap) {
        super(bitmap);

        mUrl = url;
        mDisplayingCount = 0;
        mCacheCount = 0;
    }

    /**
     * @return Amount of heap size currently being used by {@code Bitmap}
     */
    public int getMemorySize() {
        int size = 0;

        final Bitmap bitmap = getBitmap();
        if (null != bitmap && !bitmap.isRecycled()) {
            size = bitmap.getRowBytes() * bitmap.getHeight();
        }

        return size;
    }

    /**
     * @return the URL associated with the BitmapDrawable
     */
    public String getUrl() {
        return mUrl;
    }

    /**
     * Returns true when this wrapper has a bitmap and the bitmap has not been recycled.
     *
     * @return true - if the bitmap has not been recycled.
     */
    public synchronized boolean hasValidBitmap() {
        Bitmap bitmap = getBitmap();
        if (null != bitmap) {
            return !bitmap.isRecycled();
        }
        return false;
    }

    /**
     * @return true - if the bitmap is currently being displayed by a {@link CacheableImageView}.
     */
    public synchronized boolean isBeingDisplayed() {
        return mDisplayingCount > 0;
    }

    /**
     * @return true - if the wrapper is currently referenced by a cache.
     */
    public synchronized boolean isReferencedByCache() {
        return mCacheCount > 0;
    }

    /**
     * Used to signal to the Drawable whether it is being used or not.
     *
     * @param beingUsed - true if being used, false if not.
     */
    public synchronized void setBeingUsed(boolean beingUsed) {
        if (beingUsed) {
            mDisplayingCount++;
            mHasBeenDisplayed = true;
        } else {
            mDisplayingCount--;
        }
        checkState();
    }

    /**
     * Used to signal to the wrapper whether it is being referenced by a cache or not.
     *
     * @param added - true if the wrapper has been added to a cache, false if removed.
     */
    public synchronized void setCached(boolean added) {
        if (added) {
            mCacheCount++;
        } else {
            mCacheCount--;
        }
        checkState();
    }

    private void cancelCheckStateCallback() {
        if (null != mCheckStateRunnable) {
            Log.d(LOG_TAG, "Cancelling checkState() callback for: " + mUrl);
            sHandler.removeCallbacks(mCheckStateRunnable);
            mCheckStateRunnable = null;
        }
    }

    /**
     * Calls {@link #checkState(boolean)} with default parameter of <code>false</code>.
     */
    private void checkState() {
        checkState(false);
    }

    /**
     * Checks whether the wrapper is currently referenced by a cache, and is being displayed. If
     * neither of those conditions are met then the bitmap is ready to be recycled. Whether this
     * happens now, or is delayed depends on whether the Drawable has been displayed or not.
     * <ul>
     * <li>If it has been displayed, it is recycled straight away.</li>
     * <li>If it has not been displayed, and <code>ignoreBeenDisplayed</code> is <code>false</code>,
     * a call to <code>checkState(true)</code> is queued to be called after a delay.</li>
     * <li>If it has not been displayed, and <code>ignoreBeenDisplayed</code> is <code>true</code>,
     * it is recycled straight away.</li>
     * </ul>
     *
     * @see Constants#UNUSED_DRAWABLE_RECYCLE_DELAY_MS
     *
     * @param ignoreBeenDisplayed - Whether to ignore the 'has been displayed' flag when deciding
     *            whether to recycle() now.
     */
    private synchronized void checkState(final boolean ignoreBeenDisplayed) {
        Log.d(LOG_TAG, String.format(
                "checkState(). Been Displayed: %b, Displaying: %d, Caching: %d, URL: %s",
                mHasBeenDisplayed, mDisplayingCount, mCacheCount, mUrl));

        if (mCacheCount <= 0 && mDisplayingCount <= 0) {
            // We're not being referenced or used anywhere

            // Cancel the callback, if one is queued.
            cancelCheckStateCallback();

            if (hasValidBitmap()) {
                /**
                 * If we have been displayed or we don't care whether we have been or not, then
                 * recycle() now. Otherwise, we retry in UNUSED_DRAWABLE_RECYCLE_DELAY_MS.
                 */
                if (mHasBeenDisplayed || ignoreBeenDisplayed) {
                    Log.d(LOG_TAG, "Recycling bitmap with url: " + mUrl);
                    getBitmap().recycle();
                } else {
                    Log.d(LOG_TAG,
                            "Unused Bitmap which hasn't been displayed, delaying recycle(): "
                                    + mUrl);
                    mCheckStateRunnable = new CheckStateRunnable(this);
                    sHandler.postDelayed(mCheckStateRunnable, UNUSED_DRAWABLE_RECYCLE_DELAY_MS);
                }
            }
        } else {
            // We're being referenced (by either a cache or used somewhere)

            /**
             * If mCheckStateRunnable isn't null, then a checkState() call has been queued
             * previously. As we're being used now, cancel the callback.
             */
            cancelCheckStateCallback();
        }
    }

    /**
     * Runnable which run a {@link CacheableBitmapDrawable#checkState(boolean) checkState(false)}
     * call.
     */
    private static final class CheckStateRunnable extends
            WeakReferenceRunnable<CacheableBitmapDrawable> {

        public CheckStateRunnable(CacheableBitmapDrawable object) {
            super(object);
        }

        @Override
        public void run(CacheableBitmapDrawable object) {
            object.checkState(true);
        }
    }

}