package com.example.instasaver;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Shows one album's contents for a single media type (Reels or Photos). */
public class AlbumDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ALBUM = "album";
    public static final String EXTRA_IS_VIDEO = "is_video";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);

        final String album = getIntent().getStringExtra(EXTRA_ALBUM);
        final boolean isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, true);
        if (TextUtils.isEmpty(album)) {
            finish();
            return;
        }

        ((TextView) findViewById(R.id.albumTitle))
                .setText(album + (isVideo ? "  ·  Reels" : "  ·  Photos"));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.albumContainer, MediaListFragment.albumTyped(album, isVideo))
                    .commit();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        VaultLock.onVaultScreenStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        VaultLock.onVaultScreenStop();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        VaultLock.touch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!VaultLock.isUnlocked()) { // backgrounded or timed out → re-lock
            finish();
        }
    }
}
