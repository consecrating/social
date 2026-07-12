package com.example.instasaver;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MainActivity extends AppCompatActivity {

    private SharedUrlViewModel sharedUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedUrl = new ViewModelProvider(this).get(SharedUrlViewModel.class);

        ViewPager2 pager = findViewById(R.id.pager);
        TabLayout tabs = findViewById(R.id.tabs);
        pager.setAdapter(new MainPagerAdapter(this));
        pager.setOffscreenPageLimit(1);

        new TabLayoutMediator(tabs, pager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText(R.string.tab_download); break;
                case 1: tab.setText(R.string.tab_reels); break;
                case 2: tab.setText(R.string.tab_photos); break;
                case 3: tab.setText(R.string.tab_my_reels); break;
                case 4: tab.setText(R.string.tab_my_photos); break;
                default: tab.setText(R.string.tab_delete); break;
            }
        }).attach();

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** A link shared into the app lands on the Download tab and auto-fills. */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(shared)) {
                sharedUrl.setPendingUrl(shared.trim());
                ViewPager2 pager = findViewById(R.id.pager);
                if (pager != null) pager.setCurrentItem(0, false);
            }
        }
    }
}
