package tw.nekomimi.nekogram.pdf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import java.io.File;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * A simple in-app PDF viewer built on Android's PdfRenderer: pages in a vertical list, pinch or
 * double-tap to zoom, the current page in the title. Rendering happens off the UI thread, one page
 * at a time (PdfRenderer allows a single open page), with a small cache of rendered pages.
 */
public class PdfViewerActivity extends BaseFragment {

    private static final int MENU_OPEN_EXTERNAL = 1;

    private final File file;
    private final String title;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private int pageCount;
    private int[] pageWidths;
    private int[] pageHeights;
    private final Object rendererLock = new Object();
    private LruCache<Integer, Bitmap> cache;

    private ZoomLayout zoomLayout;
    private RecyclerListView listView;
    private TextView pageIndicator;
    private int renderWidth;

    /** Opens the file in the viewer when it is a PDF and the setting is on; false to open it as before. */
    public static boolean openIfPdf(File file, String fileName, String mimeType, Activity activity) {
        if (!NekoConfig.inAppPdfViewer || file == null || !file.isFile() || !(activity instanceof LaunchActivity)) {
            return false;
        }
        final boolean pdf = "application/pdf".equalsIgnoreCase(mimeType)
                || fileName != null && fileName.toLowerCase().endsWith(".pdf")
                || file.getName().toLowerCase().endsWith(".pdf");
        if (!pdf) {
            return false;
        }
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null) {
            return false;
        }
        // Password-protected or damaged files can't be rendered: those still open in another app.
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer probe = new PdfRenderer(fd)) {
            if (probe.getPageCount() <= 0) {
                return false;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> last.presentFragment(new PdfViewerActivity(file, fileName)));
        return true;
    }

    public PdfViewerActivity(File file, String title) {
        this.file = file;
        this.title = title != null && !title.isEmpty() ? title : file.getName();
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            pageCount = renderer.getPageCount();
            pageWidths = new int[pageCount];
            pageHeights = new int[pageCount];
            for (int i = 0; i < pageCount; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    pageWidths[i] = Math.max(1, page.getWidth());
                    pageHeights[i] = Math.max(1, page.getHeight());
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
            closeRenderer();
            return false;
        }
        final int maxKb = (int) Math.min(Runtime.getRuntime().maxMemory() / 1024 / 6, 64 * 1024);
        cache = new LruCache<Integer, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        Utilities.globalQueue.postRunnable(this::closeRenderer);
        if (cache != null) {
            cache.evictAll();
        }
    }

    private void closeRenderer() {
        synchronized (rendererLock) {
            try {
                if (renderer != null) {
                    renderer.close();
                }
            } catch (Throwable ignore) {
            }
            renderer = null;
            try {
                if (descriptor != null) {
                    descriptor.close();
                }
            } catch (Throwable ignore) {
            }
            descriptor = null;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(title);
        actionBar.setSubtitle(LocaleController.formatString(R.string.PdfViewerPage, 1, pageCount));
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_OPEN_EXTERNAL, R.drawable.msg_openin);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_OPEN_EXTERNAL) {
                    openExternally();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF525659);
        renderWidth = Math.min(AndroidUtilities.displaySize.x, 1440);

        listView = new RecyclerListView(context);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(new PagesAdapter());
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(true);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final int first = layoutManager.findFirstVisibleItemPosition();
                final int last = layoutManager.findLastVisibleItemPosition();
                if (first < 0) {
                    return;
                }
                final int current = last == pageCount - 1 && !recyclerView.canScrollVertically(1) ? last : first;
                actionBar.setSubtitle(LocaleController.formatString(R.string.PdfViewerPage, current + 1, pageCount));
                pageIndicator.setText((current + 1) + " / " + pageCount);
            }
        });

        zoomLayout = new ZoomLayout(context);
        zoomLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(zoomLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        pageIndicator = new TextView(context);
        pageIndicator.setTextColor(Color.WHITE);
        pageIndicator.setTextSize(13);
        pageIndicator.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        pageIndicator.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x99000000));
        pageIndicator.setText("1 / " + pageCount);
        root.addView(pageIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        fragmentView = root;
        return fragmentView;
    }

    private void openExternally() {
        if (getParentActivity() == null) {
            return;
        }
        final boolean was = NekoConfig.inAppPdfViewer;
        NekoConfig.inAppPdfViewer = false;
        try {
            AndroidUtilities.openForView(file, title, "application/pdf", getParentActivity(), null, false);
        } finally {
            NekoConfig.inAppPdfViewer = was;
        }
    }

    private int pageHeightFor(int index, int width) {
        return (int) ((long) pageHeights[index] * width / pageWidths[index]);
    }

    private void renderAsync(int index, ImageView target) {
        target.setTag(index);
        final Bitmap cached = cache.get(index);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        final int width = renderWidth;
        Utilities.globalQueue.postRunnable(() -> {
            if (!(target.getTag() instanceof Integer) || (Integer) target.getTag() != index) {
                return; // scrolled away before its turn
            }
            Bitmap bitmap = null;
            synchronized (rendererLock) {
                if (renderer == null) {
                    return;
                }
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    final int height = Math.max(1, pageHeightFor(index, width));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    new Canvas(bitmap).drawColor(Color.WHITE); // pages are transparent by default
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            final Bitmap result = bitmap;
            if (result != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    cache.put(index, result);
                    if (target.getTag() instanceof Integer && (Integer) target.getTag() == index) {
                        target.setImageBitmap(result);
                    }
                });
            }
        });
    }

    private class PagesAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final ImageView page = new ImageView(parent.getContext());
            page.setScaleType(ImageView.ScaleType.FIT_XY);
            page.setBackgroundColor(Color.WHITE);
            return new RecyclerListView.Holder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final ImageView page = (ImageView) holder.itemView;
            final int width = listView.getWidth() > 0 ? listView.getWidth() - AndroidUtilities.dp(16) : renderWidth;
            final RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, pageHeightFor(position, width));
            params.leftMargin = params.rightMargin = AndroidUtilities.dp(8);
            params.topMargin = AndroidUtilities.dp(position == 0 ? 8 : 4);
            params.bottomMargin = AndroidUtilities.dp(4);
            page.setLayoutParams(params);
            renderAsync(position, page);
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }
    }

    /** Pinch and double-tap zoom over the page list; panning sideways while zoomed in. */
    private static class ZoomLayout extends FrameLayout {

        private static final float MAX_SCALE = 4f;
        private float scale = 1f;
        private float translateX;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
    
        ZoomLayout(Context context) {
            super(context);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    setScale(scale * detector.getScaleFactor(), detector.getFocusX());
                    return true;
                }
            });
            gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    setScale(scale > 1.2f ? 1f : 2.5f, e.getX());
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    if (scale > 1f) {
                        translateX -= distanceX;
                        clampAndApply();
                    }
                    return false;
                }
            });
        }

        private void setScale(float newScale, float focusX) {
            newScale = Math.max(1f, Math.min(MAX_SCALE, newScale));
            final View child = getChildAt(0);
            if (child == null) {
                return;
            }
            // keep the point under the fingers in place horizontally
            final float contentX = (focusX - translateX - getWidth() / 2f) / scale;
            scale = newScale;
            translateX = focusX - getWidth() / 2f - contentX * scale;
            clampAndApply();
        }

        private void clampAndApply() {
            final View child = getChildAt(0);
            if (child == null) {
                return;
            }
            final float maxShift = (getWidth() * scale - getWidth()) / 2f;
            translateX = Math.max(-maxShift, Math.min(maxShift, translateX));
            if (scale <= 1f) {
                translateX = 0;
            }
            child.setPivotX(getWidth() / 2f);
            child.setPivotY(getHeight() / 2f);
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setTranslationX(translateX);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            if (ev.getPointerCount() > 1 || scaleDetector.isInProgress()) {
                return true; // the list doesn't scroll while pinching
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            scaleDetector.onTouchEvent(ev);
            gestureDetector.onTouchEvent(ev);
            return true;
        }
    }
}
