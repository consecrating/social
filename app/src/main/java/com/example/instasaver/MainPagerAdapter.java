package com.example.instasaver;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Tabs: Download, Reels, Photos, My Reels, My Photos, Delete.
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @Override
    public int getItemCount() {
        return 6;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new DownloadFragment();
            case 1:
                return MediaListFragment.library(true);    // Reels
            case 2:
                return MediaListFragment.library(false);   // Photos
            case 3:
                return MediaListFragment.favorites(true);  // My Reels
            case 4:
                return MediaListFragment.favorites(false); // My Photos
            default:
                return MediaListFragment.trash();          // Delete bin
        }
    }
}
