package com.example.instasaver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
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
    private TextView handleView;
    private String currentHandle;

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
        handleView = findViewById(R.id.handle);
        handleView.setOnClickListener(v -> openProfile());
        findViewById(R.id.close).setOnClickListener(v -> finish());

        pager = findViewById(R.id.viewerPager);
        adapter = new ViewerAdapter(paths, video, this::toggleBar, this::showBarAutoHide,
                this::goPrev, this::goNext);
        pager.setAdapter(adapter);
        pager.setCurrentItem(index, false);
        updateTopBarForType(index);
        updateHandle(index);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateTopBarForType(position);
                updateHandle(position);
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

    private void goPrev() {
        int i = pager.getCurrentItem();
        if (i > 0) pager.setCurrentItem(i - 1, true);
    }

    private void goNext() {
        int i = pager.getCurrentItem();
        if (i < paths.length - 1) pager.setCurrentItem(i + 1, true);
    }

    private void updateHandle(int position) {
        currentHandle = position >= 0 && position < paths.length
                ? parseHandle(paths[position]) : null;
        if (currentHandle != null) {
            handleView.setText("@" + currentHandle);
            handleView.setVisibility(View.VISIBLE);
        } else {
            handleView.setText("");
            handleView.setVisibility(View.INVISIBLE);
        }
    }

    private void openProfile() {
        if (currentHandle == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.instagram.com/" + currentHandle + "/")));
        } catch (Exception ignored) { }
    }

    /**
     * Downloads are named "<handle>-<shortcode>[-N].ext". Extract the handle only
     * when the part after it really looks like an Instagram shortcode, so imported
     * gallery files (e.g. "image-20537.jpg") don't show a bogus handle.
     */
    static String parseHandle(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        int dash = base.indexOf('-');
        if (dash <= 0) return null;
        String h = base.substring(0, dash);
        String rest = base.substring(dash + 1);
        int nextDash = rest.indexOf('-');
        String shortcode = nextDash > 0 ? rest.substring(0, nextDash) : rest;
        if (h.matches("[A-Za-z0-9._]+")
                && shortcode.length() >= 8
                && shortcode.matches("[A-Za-z0-9_-]+")) {
            return h;
        }
        return null;
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
        private final Runnable onVideoTap;
        private final Runnable onPrev;
        private final Runnable onNext;
        private VideoHolder active;

        ViewerAdapter(String[] paths, boolean[] video, Runnable onImageTap,
                      Runnable onVideoTap, Runnable onPrev, Runnable onNext) {
            this.paths = paths;
            this.video = video;
            this.onImageTap = onImageTap;
            this.onVideoTap = onVideoTap;
            this.onPrev = onPrev;
            this.onNext = onNext;
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
                return new VideoHolder(
                        inf.inflate(R.layout.item_viewer_video, parent, false),
                        onVideoTap, onPrev, onNext);
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
            private static final long CONTROLS_HIDE_MS = 2000;
            private static final int SEEK_STEP_MS = 5000;

            final VideoView videoView;
            private final View controls;
            private final ImageButton playPause;
            private boolean prepared;
            private boolean playWhenReady;
            private final GestureDetector detector;
            private final Handler handler = new Handler(Looper.getMainLooper());
            private final Runnable hideControls;
            private final Runnable onVideoTap;

            VideoHolder(@NonNull View v, Runnable onVideoTap, Runnable onPrev, Runnable onNext) {
                super(v);
                this.onVideoTap = onVideoTap;
                videoView = v.findViewById(R.id.video);
                controls = v.findViewById(R.id.videoControls);
                playPause = v.findViewById(R.id.playPause);
                ImageButton prevReel = v.findViewById(R.id.prevReel);
                ImageButton seekBack = v.findViewById(R.id.seekBack);
                ImageButton seekForward = v.findViewById(R.id.seekForward);
                ImageButton nextReel = v.findViewById(R.id.nextReel);

                hideControls = () -> controls.setVisibility(View.GONE);

                playPause.setOnClickListener(b -> {
                    if (videoView.isPlaying()) videoView.pause(); else videoView.start();
                    updatePlayIcon();
                    showControls();
                });
                seekBack.setOnClickListener(b -> {
                    videoView.seekTo(Math.max(0, videoView.getCurrentPosition() - SEEK_STEP_MS));
                    showControls();
                });
                seekForward.setOnClickListener(b -> {
                    videoView.seekTo(videoView.getCurrentPosition() + SEEK_STEP_MS);
                    showControls();
                });
                if (onPrev != null) prevReel.setOnClickListener(b -> onPrev.run());
                if (onNext != null) nextReel.setOnClickListener(b -> onNext.run());

                detector = new GestureDetector(v.getContext(),
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e) {
                                // A tap reveals the controls + the handle bar (both auto-hide).
                                if (controls.getVisibility() == View.VISIBLE) {
                                    handler.removeCallbacks(hideControls);
                                    controls.setVisibility(View.GONE);
                                } else {
                                    showControls();
                                }
                                if (onVideoTap != null) onVideoTap.run();
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e) {
                                // Double tap steps to (approximately) the next frame.
                                if (videoView.isPlaying()) videoView.pause();
                                videoView.seekTo(videoView.getCurrentPosition() + 40);
                                updatePlayIcon();
                                return true;
                            }
                        });

                v.setOnTouchListener((view, event) -> {
                    detector.onTouchEvent(event);
                    return true;
                });
            }

            private void showControls() {
                updatePlayIcon();
                controls.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideControls);
                handler.postDelayed(hideControls, CONTROLS_HIDE_MS);
            }

            private void updatePlayIcon() {
                playPause.setImageResource(
                        videoView.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            }

            void bind(File file) {
                prepared = false;
                playWhenReady = false;
                videoView.setVideoURI(Uri.fromFile(file));
                videoView.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    prepared = true;
                    if (playWhenReady) videoView.start();
                    updatePlayIcon();
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
                handler.removeCallbacks(hideControls);
                videoView.stopPlayback();
            }
        }

        @Override
        public int getItemCount() {
            return paths.length;
        }
    }
}
