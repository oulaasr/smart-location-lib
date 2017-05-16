package io.nlopez.smartlocation.location;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.LinkedList;
import java.util.List;

import io.nlopez.smartlocation.OnLocationUpdatedListener;
import io.nlopez.smartlocation.common.Provider;
import io.nlopez.smartlocation.location.config.LocationProviderParams;
import io.nlopez.smartlocation.utils.Logger;

public class LocationController implements Provider.StatusListener {
    public static final int NO_TIMEOUT = -1;

    @NonNull
    private final LinkedList<LocationProviderFactory> mProviderList;
    @NonNull
    private final Logger mLogger;
    @NonNull
    private final Context mContext;
    @NonNull
    private final OnLocationUpdatedListener mUpdateListener;
    @NonNull
    private final LocationProviderParams mParams;
    private final long mTimeout;
    @Nullable
    private LocationProvider mCurrentProvider;
    @Nullable
    private Listener mListener;

    public LocationController(
            @NonNull Context context,
            @NonNull OnLocationUpdatedListener updateListener,
            @NonNull LocationProviderParams params,
            long timeout,
            @NonNull List<LocationProviderFactory> providerList,
            @NonNull Logger logger) {
        mContext = context;
        mUpdateListener = updateListener;
        mParams = params;
        mTimeout = timeout;
        mLogger = logger;
        mProviderList = new LinkedList<>(providerList);
    }

    @Nullable
    public LocationProvider getCurrentProvider() {
        return mCurrentProvider;
    }

    @NonNull
    public LocationController start() {
        startNext();
        return this;
    }

    private void startNext() {
        LocationProviderFactory providerFactory = mProviderList.poll();
        if (providerFactory == null) {
            mLogger.w("All providers failed");
            if (mListener != null) {
                mListener.onAllProvidersFailed();
            }
            return;
        }
        mCurrentProvider = providerFactory.create(mContext, this);
        final TimeoutableLocationUpdateListener updateListener = new TimeoutableLocationUpdateListener(
                mCurrentProvider,
                mUpdateListener,
                new ProviderTimeoutListener() {
                    @Override
                    public void onProviderTimeout(@NonNull Provider provider) {
                        if (mCurrentProvider != provider) {
                            return;
                        }
                        mLogger.d(provider + " timed out.");
                        provider.release();
                        startNext();
                    }
                },
                mTimeout,
                new Handler(Looper.getMainLooper()));
        mCurrentProvider.start(updateListener, mParams);
        updateListener.onProviderStarted();
    }

    @Nullable
    public Location getLastLocation() {
        if (mCurrentProvider == null) {
            return null;
        }
        return mCurrentProvider.getLastLocation();
    }

    public void stop() {
        if (mCurrentProvider == null) {
            return;
        }
        mCurrentProvider.stop();
    }

    public void release() {
        if (mCurrentProvider != null) {
            mCurrentProvider.release();
        }
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @Override
    public void onProviderFailed(@NonNull Provider provider) {
        if (mCurrentProvider != provider) {
            return;
        }
        mLogger.d(provider + " failed.");
        provider.release();
        startNext();
    }

    public interface Listener {
        /**
         * All providers have failed to initialize
         */
        void onAllProvidersFailed();
    }

    public interface ProviderTimeoutListener {
        void onProviderTimeout(@NonNull Provider provider);
    }

    /**
     * Handles the dispatch of location updates, and allows extra features on top like timeouts.
     */
    private static class TimeoutableLocationUpdateListener implements OnLocationUpdatedListener, Runnable {

        private final OnLocationUpdatedListener mListener;
        private final Handler mHandler;
        private final long mTimeout;
        private final ProviderTimeoutListener mTimeoutListener;
        private final LocationProvider mProvider;
        private boolean locationReceived = false;
        private boolean cancelled = false;

        TimeoutableLocationUpdateListener(
                @NonNull LocationProvider provider,
                @NonNull OnLocationUpdatedListener listener,
                @NonNull ProviderTimeoutListener timeoutListener,
                long timeout,
                @NonNull Handler handler) {
            mProvider = provider;
            mListener = listener;
            mTimeoutListener = timeoutListener;
            mTimeout = timeout;
            mHandler = handler;
        }

        public void onProviderStarted() {
            mHandler.postDelayed(this, mTimeout);
        }

        @Override
        public void onLocationUpdated(Location location) {
            if (!cancelled) {
                locationReceived = true;
                mListener.onLocationUpdated(location);
            }
        }

        @Override
        public void run() {
            if (!locationReceived) {
                mTimeoutListener.onProviderTimeout(mProvider);
                cancelled = true;
            }
        }
    }
}
