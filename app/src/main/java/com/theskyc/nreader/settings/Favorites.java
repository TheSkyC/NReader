package com.theskyc.nreader.settings;

import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.async.database.Queries;

public class Favorites {


    public static void addFavorite(Gallery gallery) {
        Queries.FavoriteTable.addFavorite(gallery);
    }

    public static void removeFavorite(GenericGallery gallery) {
        Queries.FavoriteTable.removeFavorite(gallery.getId());
    }

    public static boolean isFavorite(GenericGallery gallery) {
        if (gallery == null || !gallery.isValid()) return false;
        return Queries.FavoriteTable.isFavorite(gallery.getId());
    }


}
