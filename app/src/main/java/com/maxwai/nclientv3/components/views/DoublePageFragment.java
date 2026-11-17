// NReader modifications by TheSkyC (2026); see NOTICE.
package com.maxwai.nclientv3.components.views;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.maxwai.nclientv3.R;
import com.maxwai.nclientv3.api.components.GenericGallery;
import com.maxwai.nclientv3.components.reader.ReaderScaleMode;
import com.maxwai.nclientv3.components.reader.ReaderImageFilter;
import com.maxwai.nclientv3.files.GalleryFolder;
import com.maxwai.nclientv3.settings.Global;

public final class DoublePageFragment extends Fragment {
    private static final String GALLERY_KEY = "GALLERY";
    private static final String DIRECTORY_KEY = "DIRECTORY";
    private static final String FIRST_PAGE_KEY = "FIRST_PAGE";
    private static final String SECOND_PAGE_KEY = "SECOND_PAGE";
    private static final String SCALE_MODE_KEY = "SCALE_MODE";
    private static final String FILTER_MODE_KEY = "FILTER_MODE";
    private static final String AUTO_CROP_KEY = "AUTO_CROP";

    private int firstPage;
    private int secondPage;

    public static DoublePageFragment newInstance(@NonNull GenericGallery gallery, int firstPage,
                                                 int secondPage, @Nullable GalleryFolder directory,
                                                 int scaleMode) {
        return newInstance(gallery, firstPage, secondPage, directory, scaleMode, ReaderImageFilter.NONE);
    }

    public static DoublePageFragment newInstance(@NonNull GenericGallery gallery, int firstPage,
                                                 int secondPage, @Nullable GalleryFolder directory,
                                                 int scaleMode, int filterMode) {
        return newInstance(gallery, firstPage, secondPage, directory, scaleMode, filterMode, false);
    }

    public static DoublePageFragment newInstance(@NonNull GenericGallery gallery, int firstPage,
                                                 int secondPage, @Nullable GalleryFolder directory,
                                                 int scaleMode, int filterMode, boolean autoCrop) {
        return newInstance(gallery, firstPage, secondPage, directory, scaleMode, filterMode,
            autoCrop, 0, 0, 0);
    }

    public static DoublePageFragment newInstance(@NonNull GenericGallery gallery, int firstPage,
                                                 int secondPage, @Nullable GalleryFolder directory,
                                                 int scaleMode, int filterMode, boolean autoCrop,
                                                 int brightness, int contrast, int saturation) {
        Bundle args = new Bundle();
        args.putParcelable(GALLERY_KEY, gallery);
        args.putParcelable(DIRECTORY_KEY, directory);
        args.putInt(FIRST_PAGE_KEY, firstPage);
        args.putInt(SECOND_PAGE_KEY, secondPage);
        args.putInt(SCALE_MODE_KEY, ReaderScaleMode.sanitize(scaleMode));
        args.putInt(FILTER_MODE_KEY, ReaderImageFilter.sanitize(filterMode));
        args.putBoolean(AUTO_CROP_KEY, autoCrop);
        args.putInt("BRIGHTNESS", ReaderImageFilter.sanitizeAdjustment(brightness));
        args.putInt("CONTRAST", ReaderImageFilter.sanitizeAdjustment(contrast));
        args.putInt("SATURATION", ReaderImageFilter.sanitizeAdjustment(saturation));
        DoublePageFragment fragment = new DoublePageFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_double_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.double_page_root).setLayoutDirection(
            Global.useRtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        Bundle args = requireArguments();
        GenericGallery gallery;
        GalleryFolder directory;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gallery = args.getParcelable(GALLERY_KEY, GenericGallery.class);
            directory = args.getParcelable(DIRECTORY_KEY, GalleryFolder.class);
        } else {
            gallery = args.getParcelable(GALLERY_KEY);
            directory = args.getParcelable(DIRECTORY_KEY);
        }
        if (gallery == null) return;
        firstPage = args.getInt(FIRST_PAGE_KEY, 0);
        secondPage = args.getInt(SECOND_PAGE_KEY, -1);
        int scaleMode = ReaderScaleMode.sanitize(args.getInt(SCALE_MODE_KEY, ReaderScaleMode.SMART));
        int filterMode = ReaderImageFilter.sanitize(args.getInt(FILTER_MODE_KEY, ReaderImageFilter.NONE));
        boolean autoCrop = args.getBoolean(AUTO_CROP_KEY, false);
        int brightness = ReaderImageFilter.sanitizeAdjustment(args.getInt("BRIGHTNESS", 0));
        int contrast = ReaderImageFilter.sanitizeAdjustment(args.getInt("CONTRAST", 0));
        int saturation = ReaderImageFilter.sanitizeAdjustment(args.getInt("SATURATION", 0));
        LinearLayout root = view.findViewById(R.id.double_page_root);
        FrameLayout secondContainer = view.findViewById(R.id.double_page_second);
        if (secondPage < 0) {
            root.setWeightSum(1f);
            secondContainer.setVisibility(View.GONE);
        }
        if (getChildFragmentManager().findFragmentByTag(pageTag(firstPage)) == null) {
            getChildFragmentManager().beginTransaction()
                .add(R.id.double_page_first, ZoomFragment.newInstance(gallery, firstPage, directory,
                    scaleMode, filterMode, autoCrop, brightness, contrast, saturation), pageTag(firstPage))
                .commitNow();
        }
        if (secondPage >= 0 && getChildFragmentManager().findFragmentByTag(pageTag(secondPage)) == null) {
            getChildFragmentManager().beginTransaction()
                .add(R.id.double_page_second, ZoomFragment.newInstance(gallery, secondPage, directory,
                    scaleMode, filterMode, autoCrop, brightness, contrast, saturation), pageTag(secondPage))
                .commitNow();
        }
    }

    @NonNull
    private String pageTag(int page) {
        return "page_" + page;
    }

    @Nullable
    public ZoomFragment pageFragment(int page) {
        Fragment fragment = getChildFragmentManager().findFragmentByTag(pageTag(page));
        return fragment instanceof ZoomFragment ? (ZoomFragment) fragment : null;
    }

    public int firstPage() {
        return firstPage;
    }

    public int secondPage() {
        return secondPage;
    }

    public void setAutoCrop(boolean enabled) {
        ZoomFragment first = pageFragment(firstPage);
        if (first != null) first.setAutoCrop(enabled);
        if (secondPage >= 0) {
            ZoomFragment second = pageFragment(secondPage);
            if (second != null) second.setAutoCrop(enabled);
        }
    }

    public void setAdjustments(int brightness, int contrast, int saturation) {
        ZoomFragment first = pageFragment(firstPage);
        if (first != null) first.setAdjustments(brightness, contrast, saturation);
        if (secondPage >= 0) {
            ZoomFragment second = pageFragment(secondPage);
            if (second != null) second.setAdjustments(brightness, contrast, saturation);
        }
    }
}
