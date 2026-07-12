package com.example.instasaver;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The hidden "vault" that shows custom albums and their files. It is not part of
 * the main tab bar; the user reaches it only by long-pressing the InstaSaver
 * title on the home screen. Custom albums (and any files filed into them) stay
 * out of the normal Reels / Photos views.
 */
public class AlbumsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_albums);

        findViewById(R.id.back).setOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.albumContainer, MediaListFragment.album())
                    .commit();
        }
    }
}
