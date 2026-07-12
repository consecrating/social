package com.example.instasaver;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/** Shows one album's contents with separate Reels and Photos tabs. */
public class AlbumDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ALBUM = "album";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);

        final String album = getIntent().getStringExtra(EXTRA_ALBUM);
        if (TextUtils.isEmpty(album)) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.albumTitle)).setText(album);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        ViewPager2 pager = findViewById(R.id.albumPager);
        TabLayout tabs = findViewById(R.id.albumTabs);
        pager.setAdapter(new AlbumPagerAdapter(this, album));

        new TabLayoutMediator(tabs, pager, (tab, position) ->
                tab.setText(position == 0 ? R.string.tab_reels : R.string.tab_photos)
        ).attach();
    }

    static class AlbumPagerAdapter extends FragmentStateAdapter {
        private final String album;

        AlbumPagerAdapter(@NonNull FragmentActivity activity, String album) {
            super(activity);
            this.album = album;
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return MediaListFragment.albumTyped(album, position == 0);
        }
    }
}
