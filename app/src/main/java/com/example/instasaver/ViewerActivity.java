package com.example.instasaver;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.io.File;

/**
 * Full-screen, swipeable viewer.
 *
 * Photos: pinch / double-tap to zoom, drag to pan.
 * Reels/videos: distraction-free (no counter/controls). Single-tap toggles
 * play/pause; double-tap steps to the next frame. Only the visible page plays.
 */
public class ViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_VIDEO = "video";
    public static final String EXTRA_INDEX = "index";

    private String[] paths;
    private boolean[] video;

    private ViewPager2 pager;
    private ViewerAdapter adapter;
    private View topBar;

    private final Handler barHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBar = () -> topBar.setVisibility(View.GONE);
    private static final long BAR_AUTO_HIDE_MS = 2000;

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

        // No position counter in the viewer — the total count is shown on the grid.
        findViewById(R.id.counter).setVisibility(View.GONE);
        topBar = findViewById(R.id.topBar);
        findViewById(R.id.close).setOnClickListener(v -> finish());

        pager = findViewById(R.id.viewerPager);
        adapter = new ViewerAdapter(paths, video, this::toggleBar);
        pager.setAdapter(adapter);
        pager.setCurrentItem(index, false);
        updateTopBarForType(index);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateTopBarForType(position);
                activate(position);
            }
        });

        final int start = index;
        pager.post(() -> activate(start));
    }

    /**
     * Reels are distraction-free (top bar always hidden). For photos the bar
     * (close button) shows briefly then auto-hides after 2 seconds; a tap on the
     * photo toggles it back.
     */
    private void updateTopBarForType(int position) {
        boolean isVideo = position >= 0 && position < video.length && video[position];
        if (isVideo) {
            barHandler.removeCallbacks(hideBar);
            topBar.setVisibility(View.GONE);
        } else {
            showBarAutoHide();
        }
    }

    private void showBarAutoHide() {
        topBar.setVisibility(View.VISIBLE);
        barHandler.removeCallbacks(hideBar);
        barHandler.postDelayed(hideBar, BAR_AUTO_HIDE_MS);
    }

    private void toggleBar() {
        if (topBar.getVisibility() == View.VISIBLE) {
            barHandler.removeCallbacks(hideBar);
            topBar.setVisibility(View.GONE);
        } else {
            showBarAutoHide();
        }
    }

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
        private final Runnable onImageTap;
        private VideoHolder active;

        ViewerAdapter(String[] paths, boolean[] video, Runnable onImageTap) {
            this.paths = paths;
            this.video = video;
            this.onImageTap = onImageTap;
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
            return new ImageHolder(
                    inf.inflate(R.layout.item_viewer_image, parent, false), onImageTap);
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

            ImageHolder(@NonNull View v, Runnable onImageTap) {
                super(v);
                image = v.findViewById(R.id.image);
                if (image instanceof ZoomableImageView) {
                    ((ZoomableImageView) image).setOnSingleTap(onImageTap);
                }
            }
        }

        @SuppressWarnings("ClickableViewAccessibility")
        static class VideoHolder extends RecyclerView.ViewHolder {
            final VideoView videoView;
            private boolean prepared;
            private boolean playWhenReady;
            private final GestureDetector detector;

            VideoHolder(@NonNull View v) {
                super(v);
                videoView = v.findViewById(R.id.video);

                detector = new GestureDetector(v.getContext(),
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true; // consume so taps are tracked reliably
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                // Single tap toggles play/pause.
                                if (videoView.isPlaying()) {
                                    videoView.pause();
                                } else {
                                    videoView.start();
                                }
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e) {
                                // Double tap steps to (approximately) the next frame.
                                if (videoView.isPlaying()) videoView.pause();
                                videoView.seekTo(videoView.getCurrentPosition() + 40);
                                return true;
                            }
                        });

                // Return true so the view keeps receiving the gesture sequence;
                // ViewPager2 can still intercept horizontal swipes to change pages.
                v.setOnTouchListener((view, event) -> {
                    detector.onTouchEvent(event);
                    return true;
                });
            }

            void bind(File file) {
                prepared = false;
                playWhenReady = false;
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
