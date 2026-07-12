package com.example.instasaver;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** Three tabs: Download, Reels (videos), Photos (images). */
public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @Override
    public int getItemCount() {
        return 3;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new DownloadFragment();
            case 1:
                return MediaListFragment.newInstance(true);   // reels / videos
            default:
                return MediaListFragment.newInstance(false);  // photos
        }
    }
}
