// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.theskyc.nreader.api.RandomLoader;
import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.components.activities.GeneralActivity;
import com.theskyc.nreader.settings.Favorites;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.utility.ImageDownloadUtility;
import com.theskyc.nreader.utility.LanguageFlag;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.Objects;

public class RandomActivity extends GeneralActivity {
    @Override
    protected int getBlackThemeStyle() {
        return R.style.AppTheme_Material3_Black;
    }

    public static Gallery loadedGallery = null;
    private ImageView language;
    private ImageButton thumbnail;
    private ImageButton favorite;
    private TextView title;
    private TextView page;
    private View censor;
    private TextView randomState;
    private FloatingActionButton shuffle;
    private ImageButton share;
    private RandomLoader loader = null;
    private boolean isFavorite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //Global.initActivity(this);
        setContentView(R.layout.activity_random);
        //init components id
        Toolbar toolbar = findViewById(R.id.toolbar);
        shuffle = findViewById(R.id.shuffle);
        share = findViewById(R.id.share);
        randomState = findViewById(R.id.random_state);
        censor = findViewById(R.id.censor);
        language = findViewById(R.id.language);
        thumbnail = findViewById(R.id.thumbnail);
        favorite = findViewById(R.id.favorite);
        title = findViewById(R.id.title);
        page = findViewById(R.id.pages);

        //init toolbar
        setSupportActionBar(toolbar);
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setTitle(R.string.random_manga);


        if (loadedGallery != null) loadGallery(loadedGallery);
        else setGalleryActionsEnabled(false);

        loader = new RandomLoader(this);
        loader.start(loadedGallery == null);

        shuffle.setOnClickListener(v -> loader.requestGallery());

        thumbnail.setOnClickListener(v -> {
            if (loadedGallery != null) {
                Intent intent = new Intent(RandomActivity.this, GalleryActivity.class);
                intent.putExtra(RandomActivity.this.getPackageName() + ".GALLERY", loadedGallery);
                RandomActivity.this.startActivity(intent);
            }
        });
        share.setOnClickListener(v -> {
            if (loadedGallery != null) Global.shareGallery(RandomActivity.this, loadedGallery);
        });
        censor.setOnClickListener(v -> censor.setVisibility(View.GONE));

        favorite.setOnClickListener(v -> {
            if (loadedGallery != null) {
                if (isFavorite) {
                    Favorites.removeFavorite(loadedGallery);
                    isFavorite = false;
                } else {
                    Favorites.addFavorite(loadedGallery);
                    isFavorite = true;
                }
            }
            favoriteUpdateButton();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                loadedGallery = null;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    public void loadGallery(Gallery gallery) {
        if (Global.isDestroyed(this)) return;
        loadedGallery = gallery;
        randomState.setVisibility(View.GONE);
        shuffle.setEnabled(true);
        setGalleryActionsEnabled(true);
        ImageDownloadUtility.loadImage(this, gallery.getCover(), thumbnail);
        LanguageFlag.bind(language, gallery.getLanguage());
        isFavorite = Favorites.isFavorite(loadedGallery);
        favoriteUpdateButton();
        title.setText(gallery.getTitle());
        page.setText(getResources().getQuantityString(R.plurals.page_count_format,
            gallery.getPageCount(), gallery.getPageCount()));
        censor.setVisibility(gallery.hasIgnoredTags() ? View.VISIBLE : View.GONE);
    }

    public void showRandomLoading() {
        shuffle.setEnabled(false);
        if (loadedGallery == null) {
            randomState.setText(R.string.random_gallery_loading);
            randomState.setVisibility(View.VISIBLE);
        }
    }

    public void showRandomLoadFailure() {
        shuffle.setEnabled(true);
        if (loadedGallery == null) {
            randomState.setText(R.string.random_gallery_load_failed);
            randomState.setVisibility(View.VISIBLE);
        } else {
            Snackbar.make(shuffle, R.string.random_gallery_retry, Snackbar.LENGTH_LONG)
                .setAction(R.string.retry, view -> loader.requestGallery()).show();
        }
    }

    private void setGalleryActionsEnabled(boolean enabled) {
        thumbnail.setEnabled(enabled);
        favorite.setEnabled(enabled);
        share.setEnabled(enabled);
        favorite.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        share.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (loader != null) loader.close();
        super.onDestroy();
    }

    private void favoriteUpdateButton() {
        runOnUiThread(() -> {
            ImageDownloadUtility.loadImage(isFavorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border, favorite);
        });
    }
}
