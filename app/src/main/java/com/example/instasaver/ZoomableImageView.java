package com.example.instasaver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView that supports pinch-to-zoom, double-tap zoom, and pan. It plays
 * nicely with a horizontally-swiping parent (ViewPager2): while zoomed in it
 * asks the parent not to intercept touches so panning works; at fit scale it
 * releases that so page swipes work again.
 */
@SuppressLint("ClickableViewAccessibility")
public class ZoomableImageView extends AppCompatImageView {

    private static final int NONE = 0, DRAG = 1, ZOOM = 2;
    private int mode = NONE;

    private final Matrix matrix = new Matrix();
    private final float[] mvals = new float[9];

    private final float minScale = 1f;
    private final float maxScale = 5f;
    private float saveScale = 1f; // 1 == fit-to-screen

    private float origWidth, origHeight;
    private int viewWidth, viewHeight;

    private final PointF last = new PointF();

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private Runnable onSingleTap;

    public void setOnSingleTap(Runnable r) {
        this.onSingleTap = r;
    }

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
        setImageMatrix(matrix);

        setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            PointF curr = new PointF(event.getX(), event.getY());

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last.set(curr);
                    mode = DRAG;
                    disallowParentIntercept(saveScale > 1f);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    last.set(curr);
                    mode = ZOOM;
                    disallowParentIntercept(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && saveScale > 1f) {
                        matrix.postTranslate(curr.x - last.x, curr.y - last.y);
                        fixTranslation();
                        last.set(curr);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE;
                    if (saveScale <= 1f) disallowParentIntercept(false);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    break;
            }
            setImageMatrix(matrix);
            return true;
        });
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (viewWidth > 0) fitToScreen();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        viewWidth = MeasureSpec.getSize(widthMeasureSpec);
        viewHeight = MeasureSpec.getSize(heightMeasureSpec);
        fitToScreen();
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null || d.getIntrinsicWidth() == 0 || viewWidth == 0) return;
        int bmW = d.getIntrinsicWidth();
        int bmH = d.getIntrinsicHeight();
        float scale = Math.min((float) viewWidth / bmW, (float) viewHeight / bmH);
        matrix.reset();
        matrix.postScale(scale, scale);
        float dx = (viewWidth - bmW * scale) / 2f;
        float dy = (viewHeight - bmH * scale) / 2f;
        matrix.postTranslate(dx, dy);
        origWidth = bmW * scale;
        origHeight = bmH * scale;
        saveScale = 1f;
        setImageMatrix(matrix);
    }

    private void fixTranslation() {
        matrix.getValues(mvals);
        float transX = mvals[Matrix.MTRANS_X];
        float transY = mvals[Matrix.MTRANS_Y];
        float fixX = getFixTrans(transX, viewWidth, origWidth * saveScale);
        float fixY = getFixTrans(transY, viewHeight, origHeight * saveScale);
        if (fixX != 0 || fixY != 0) matrix.postTranslate(fixX, fixY);
    }

    private float getFixTrans(float trans, float viewSize, float contentSize) {
        float minTrans, maxTrans;
        if (contentSize <= viewSize) {
            minTrans = (viewSize - contentSize) / 2f;
            maxTrans = minTrans;
        } else {
            minTrans = viewSize - contentSize;
            maxTrans = 0;
        }
        if (trans < minTrans) return minTrans - trans;
        if (trans > maxTrans) return maxTrans - trans;
        return 0;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mode = ZOOM;
            disallowParentIntercept(true);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float prev = saveScale;
            saveScale *= factor;
            if (saveScale > maxScale) {
                saveScale = maxScale;
                factor = maxScale / prev;
            } else if (saveScale < minScale) {
                saveScale = minScale;
                factor = minScale / prev;
            }
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            fixTranslation();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (onSingleTap != null) onSingleTap.run();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (saveScale > 1f) {
                fitToScreen();
                disallowParentIntercept(false);
            } else {
                float target = 2.5f;
                float factor = target / saveScale;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                saveScale = target;
                fixTranslation();
                disallowParentIntercept(true);
            }
            setImageMatrix(matrix);
            return true;
        }
    }
}
