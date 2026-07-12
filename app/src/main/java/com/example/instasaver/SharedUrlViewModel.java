package com.example.instasaver;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Activity-scoped bus used to hand a shared/incoming Instagram URL from
 * MainActivity to the DownloadFragment, regardless of fragment recreation.
 */
public class SharedUrlViewModel extends ViewModel {

    private final MutableLiveData<String> pendingUrl = new MutableLiveData<>();

    public LiveData<String> getPendingUrl() {
        return pendingUrl;
    }

    public void setPendingUrl(String url) {
        pendingUrl.setValue(url);
    }

    public void clear() {
        pendingUrl.setValue(null);
    }
}
