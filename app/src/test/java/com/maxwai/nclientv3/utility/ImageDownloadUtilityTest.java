// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.utility;

import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.LayoutInflater;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.bumptech.glide.request.Request;
import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.Gallery;
import com.maxwai.nclientv3.settings.Global;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ImageDownloadUtilityTest {
    @Test
    public void loadingLogoHasClearanceAroundItsArtworkInBothThemes() {
        Application application = RuntimeEnvironment.getApplication();
        for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Configuration configuration = new Configuration(application.getResources().getConfiguration());
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
            Drawable logo = Global.getLogo(application.createConfigurationContext(configuration).getResources());
            assertNotNull(logo);
            int width = 515;
            int height = Math.round(width * (float) logo.getIntrinsicHeight() / logo.getIntrinsicWidth());
            Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            logo.setBounds(0, 0, width, height);
            logo.draw(new Canvas(rendered));
            Rect artwork = new Rect(width, height, 0, 0);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (Color.alpha(rendered.getPixel(x, y)) > 0) {
                        artwork.left = Math.min(artwork.left, x);
                        artwork.top = Math.min(artwork.top, y);
                        artwork.right = Math.max(artwork.right, x + 1);
                        artwork.bottom = Math.max(artwork.bottom, y + 1);
                    }
                }
            }
            assertFalse(artwork.isEmpty());
            assertTrue(artwork.left >= 12);
            assertTrue(artwork.top >= 12);
            assertTrue(width - artwork.right >= 12);
            assertTrue(height - artwork.bottom >= 12);
        }
    }

    @Test
    public void coverPlaceholderStaysCenteredAndBoundedDuringLoadingAndFailure() {
        Application application = RuntimeEnvironment.getApplication();
        float density = application.getResources().getDisplayMetrics().density;
        for (int widthDp : new int[]{104, 200}) {
            ImageView image = new ImageView(application);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int width = Math.round(widthDp * density);
            int height = width * 3 / 2;
            image.layout(0, 0, width, height);
            Drawable placeholder = ImageDownloadUtility.placeholder(image);
            DrawableImageViewTarget target = DebugImageMask.target(image);
            target.onLoadStarted(placeholder);
            assertCenteredLogo(image, width, height, density);
            target.onLoadFailed(placeholder);
            assertCenteredLogo(image, width, height, density);
            assertEquals(ImageView.ScaleType.CENTER_CROP, image.getScaleType());
        }
    }

    private void assertCenteredLogo(ImageView image, int width, int height, float density) {
        Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        image.draw(new Canvas(rendered));
        LayerDrawable placeholder = (LayerDrawable) image.getDrawable();
        Rect logo = placeholder.getDrawable(0).getBounds();
        assertEquals(72 * density, logo.width(), 1);
        assertEquals(width / 2f, logo.exactCenterX(), 1);
        assertEquals(height / 2f, logo.exactCenterY(), 1);
        assertTrue(logo.width() > logo.height() * 2);
        assertTrue(logo.left > 0 && logo.right < width);
        assertEquals(Color.TRANSPARENT, rendered.getPixel(width / 2, height / 4));
        assertEquals(Color.TRANSPARENT, rendered.getPixel(width / 2, height * 3 / 4));
        boolean visible = false;
        for (int y = logo.top; y < logo.bottom && !visible; y++) {
            for (int x = logo.left; x < logo.right; x++) {
                if (Color.alpha(rendered.getPixel(x, y)) > 0) {
                    visible = true;
                    break;
                }
            }
        }
        assertTrue(visible);
    }

    @Test
    public void loadedCoverStillFillsItsViewAfterPlaceholder() {
        Application application = RuntimeEnvironment.getApplication();
        DebugImageMask.setEnabled(application, false);
        try {
            ImageView image = new ImageView(application);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.layout(0, 0, 120, 180);
            DrawableImageViewTarget target = DebugImageMask.target(image);
            target.onLoadStarted(ImageDownloadUtility.placeholder(image));
            Bitmap cover = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
            cover.eraseColor(Color.RED);
            target.onResourceReady(new BitmapDrawable(application.getResources(), cover), null);
            Bitmap rendered = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888);
            image.draw(new Canvas(rendered));
            assertEquals(Color.RED, rendered.getPixel(0, 0));
            assertEquals(Color.RED, rendered.getPixel(119, 179));
            assertEquals(ImageView.ScaleType.CENTER_CROP, image.getScaleType());
        } finally {
            DebugImageMask.setEnabled(application, true);
        }
    }

    @Test
    public void readerPlaceholderKeepsItsOriginalAspectRatio() {
        ImageView image = new ImageView(RuntimeEnvironment.getApplication());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable placeholder = ImageDownloadUtility.placeholder(image);
        assertTrue(placeholder.getIntrinsicWidth() > placeholder.getIntrinsicHeight() * 2);
        assertFalse(placeholder instanceof LayerDrawable);
    }

    @Test
    public void pageLayoutsUseTheLoadingLogoBeforeRequestsStart() {
        try (ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.AppTheme);
            controller.setup();
            for (int layout : new int[]{R.layout.image_void_full, R.layout.image_void_static}) {
                ImageView image = LayoutInflater.from(activity).inflate(layout, null).findViewById(R.id.image);
                Drawable placeholder = ImageDownloadUtility.placeholder(image);
                assertNotNull(image.getDrawable());
                assertEquals(placeholder.getIntrinsicWidth(), image.getDrawable().getIntrinsicWidth());
                assertEquals(placeholder.getIntrinsicHeight(), image.getDrawable().getIntrinsicHeight());
                assertTrue(image.getDrawable().getIntrinsicWidth() > image.getDrawable().getIntrinsicHeight() * 2);
            }
        }
    }

    @Test
    public void failedFirstPageDoesNotBlockLaterPages() throws Exception {
        try (ActivityController<AppCompatActivity> controller = imageActivity()) {
            AppCompatActivity activity = controller.get();
            Gallery gallery = imageGallery(activity);
            ImageView first = imageView(activity);
            ImageDownloadUtility.loadImageOp(activity, first, gallery, 0, 0, true);
            awaitTerminal(first);
            assertFalse(DebugImageMask.target(first).getRequest().isComplete());
            ImageView second = imageView(activity);
            ImageDownloadUtility.loadImageOp(activity, second, gallery, 1, 0, true);
            awaitTerminal(second);
            assertTrue(DebugImageMask.target(second).getRequest().isComplete());
            assertTrue(gallery.getGalleryData().getCheckedExt());
        }
    }

    @Test
    public void replacingFirstPageAndDestroyingActivityClearRequests() throws Exception {
        ActivityController<AppCompatActivity> controller = imageActivity();
        AppCompatActivity activity = controller.get();
        Gallery gallery = imageGallery(activity);
        ImageView image = new ImageView(activity);
        // No measured size: the first request waits for layout until it is replaced.
        ImageDownloadUtility.loadImageOp(activity, image, gallery, 0, 0, true);
        Request previous = DebugImageMask.target(image).getRequest();
        image.layout(0, 0, 100, 100);
        ImageDownloadUtility.loadImageOp(activity, image, gallery, 1, 0, true);
        assertTrue(previous.isCleared());
        awaitTerminal(image);
        Request current = DebugImageMask.target(image).getRequest();
        assertTrue(current.isComplete());
        controller.pause().stop().destroy();
        assertTrue(current.isCleared());
        assertNull(DebugImageMask.target(image).getRequest());
    }

    private ActivityController<AppCompatActivity> imageActivity() {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.AppTheme);
        Global.initFromShared(controller.get());
        return controller.setup();
    }

    @Test
    public void disablingImageLoadingClearsThePreviousBinding() throws Exception {
        try (ActivityController<AppCompatActivity> controller = imageActivity()) {
            AppCompatActivity activity = controller.get();
            ImageView image = imageView(activity);
            Gallery gallery = imageGallery(activity);
            ImageDownloadUtility.loadImageOp(activity, image, gallery, 1, 0, true);
            awaitTerminal(image);
            Request previous = DebugImageMask.target(image).getRequest();
            ReflectionHelpers.setStaticField(Global.class, "usageMobile", Global.DataUsageType.NONE);
            ReflectionHelpers.setStaticField(Global.class, "usageWifi", Global.DataUsageType.NONE);
            try {
                ImageDownloadUtility.loadImageOp(activity, image, gallery, 0, 0, true);
                assertTrue(previous.isCleared());
                assertNull(DebugImageMask.target(image).getRequest());
                assertTrue(image.getDrawable().getIntrinsicWidth() > image.getDrawable().getIntrinsicHeight() * 2);
            } finally {
                Global.initFromShared(activity);
            }
        }
    }

    private ImageView imageView(AppCompatActivity activity) {
        ImageView image = new ImageView(activity);
        image.layout(0, 0, 100, 100);
        return image;
    }

    private Gallery imageGallery(AppCompatActivity activity) throws Exception {
        File valid = File.createTempFile("reader-image", ".png", activity.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(valid)) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        Gallery gallery = new Gallery(null, "{\"id\":123,\"num_pages\":2,\"pages\":[{\"path\":\"missing.png\"},{\"path\":\"valid.png\"}]}", null, false);
        gallery.getGalleryData().getPage(0).setImagePath(Uri.fromFile(new File(activity.getCacheDir(), "missing-reader-page.png")));
        gallery.getGalleryData().getPage(1).setImagePath(Uri.fromFile(valid));
        return gallery;
    }

    private void awaitTerminal(ImageView image) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Request request = DebugImageMask.target(image).getRequest();
        assertNotNull(request);
        while (request.isRunning() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("Image request did not finish", request.isRunning());
    }
}
