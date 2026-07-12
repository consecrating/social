package com.example.instasaver;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.io.File;

/**
 * Full-screen, swipeable viewer for the downloaded library.
 *
 * The caller passes the exact list currently on screen (paths + a video flag per
 * item) and the index that was tapped, so swiping left/right moves through the
 * same, already-filtered/sorted set. Photos display fit-to-screen; reels/videos
 * play with a standard media controller and only the visible page plays.
 */
public class ViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_INDEX = "index";

    private String[] paths;
    private boolean[] video;

    private ViewPager2 pager;
    private ViewerAdapter adapter;
    private TextView counter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        paths = getIntent().getStringArrayExtra(EXTRA_PATHS);
        video = getIntent().getBooleanArrayExtra(EXTRA_VIDEO);
        int index = getIntent().getIntExtra(EXTRA_INDEX, 0);

        if (paths == null || paths.length == 0) {
            finish();
            return;
        }
        if (video == null || video.length != paths.length) {
            video = new boolean[paths.length];
        }
        if (index < 0 || index >= paths.length) index = 0;

        counter = findViewById(R.id.counter);
        findViewById(R.id.close).setOnClickListener(v -> finish());

        pager = findViewById(R.id.viewerPager);
        adapter = new ViewerAdapter(paths, video);
        pager.setAdapter(adapter);
        pager.setCurrentItem(index, false);
        updateCounter(index);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position);
                activate(position);
            }
        });

        final int start = index;
        pager.post(() -> activate(start));
    }

    private void updateCounter(int position) {
        counter.setText((position + 1) + " / " + paths.length);
    }

    /** Play the video on the given page and pause any previously playing one. */
    private void activate(int position) {
        View child = pager.getChildAt(0);
        if (child instanceof RecyclerView) {
            adapter.setActive((RecyclerView) child, position);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (adapter != null) adapter.pauseActive();
    }

    // ------------------------------------------------------------------

    static class ViewerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int T_IMAGE = 0;
        private static final int T_VIDEO = 1;

        private final String[] paths;
        private final boolean[] video;
        private VideoHolder active;

        ViewerAdapter(String[] paths, boolean[] video) {
            this.paths = paths;
            this.video = video;
        }

        @Override
        public int getItemViewType(int position) {
            return video[position] ? T_VIDEO : T_IMAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == T_VIDEO) {
                return new VideoHolder(inf.inflate(R.layout.item_viewer_video, parent, false));
            }
            return new ImageHolder(inf.inflate(R.layout.item_viewer_image, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            File file = new File(paths[position]);
            if (holder instanceof ImageHolder) {
                Glide.with(holder.itemView.getContext())
                        .load(file)
                        .fitCenter()
                        .placeholder(R.drawable.ic_placeholder)
                        .into(((ImageHolder) holder).image);
            } else if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).bind(file);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof VideoHolder) {
                ((VideoHolder) holder).release();
                if (active == holder) active = null;
            }
        }

        void setActive(RecyclerView rv, int position) {
            pauseActive();
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (vh instanceof VideoHolder) {
                active = (VideoHolder) vh;
                active.play();
            }
        }

        void pauseActive() {
            if (active != null) active.pause();
        }

        static class ImageHolder extends RecyclerView.ViewHolder {
            final ImageView image;

            ImageHolder(@NonNull View v) {
                super(v);
                image = v.findViewById(R.id.image);
            }
        }

        static class VideoHolder extends RecyclerView.ViewHolder {
            final VideoView videoView;
            private boolean prepared;
            private boolean playWhenReady;

            VideoHolder(@NonNull View v) {
                super(v);
                videoView = v.findViewById(R.id.video);
            }

            void bind(File file) {
                prepared = false;
                playWhenReady = false;
                MediaController controller = new MediaController(itemView.getContext());
                controller.setAnchorView(videoView);
                videoView.setMediaController(controller);
                videoView.setVideoURI(Uri.fromFile(file));
                videoView.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    prepared = true;
                    if (playWhenReady) videoView.start();
                });
            }

            void play() {
                playWhenReady = true;
                if (prepared) videoView.start();
            }

            void pause() {
                playWhenReady = false;
                if (videoView.isPlaying()) videoView.pause();
            }

            void release() {
                playWhenReady = false;
                videoView.stopPlayback();
            }
        }

        @Override
        public int getItemCount() {
            return paths.length;
        }
    }
}
