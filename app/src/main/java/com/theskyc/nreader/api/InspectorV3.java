// Modified for NReader by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader.api;

import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.theskyc.nreader.api.components.Gallery;
import com.theskyc.nreader.api.components.GenericGallery;
import com.theskyc.nreader.api.components.Ranges;
import com.theskyc.nreader.api.components.Tag;
import com.theskyc.nreader.api.enums.ApiRequestType;
import com.theskyc.nreader.api.enums.Language;
import com.theskyc.nreader.api.enums.SortType;
import com.theskyc.nreader.api.enums.SpecialTagIds;
import com.theskyc.nreader.api.enums.TagStatus;
import com.theskyc.nreader.api.enums.TagType;
import com.theskyc.nreader.api.local.LocalGallery;
import com.theskyc.nreader.async.database.OnlineFavoritePageStore;
import com.theskyc.nreader.async.database.Queries;
import com.theskyc.nreader.async.database.SearchPageStore;
import com.theskyc.nreader.async.database.TagCatalog;
import com.theskyc.nreader.settings.ApiAuthInterceptor;
import com.theskyc.nreader.settings.AuthStore;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.Login;
import com.theskyc.nreader.utility.LogUtility;
import com.theskyc.nreader.utility.Utility;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

public class InspectorV3 extends Thread implements Parcelable {
    public static final Creator<InspectorV3> CREATOR = new Creator<>() {
        @Override
        public InspectorV3 createFromParcel(Parcel in) {
            return new InspectorV3(in);
        }

        @Override
        public InspectorV3[] newArray(int size) {
            return new InspectorV3[size];
        }
    };
    private SortType sortType;
    private boolean custom;
    private int page, pageCount = -1, id;
    private String query, url;
    private ApiRequestType requestType;
    private Set<Tag> tags;
    @Nullable private Login.OnlineTagScope accountTagScope;
    private boolean accountTagSelectionMissing;
    private boolean submittedSearch;
    @Nullable private Set<Tag> tagsWithoutAccount;
    private ArrayList<GenericGallery> galleries = null;
    private Ranges ranges = null;
    private InspectorResponse response;
    private InspectorResponse deliveryResponse;
    private WeakReference<Context> context;
    private String jsonResponse;
    private int responseCode;
    private String retryAfter;
    private volatile okhttp3.Call activeCall;
    private volatile boolean canceled;
    private java.util.function.BooleanSupplier resultCurrent = () -> true;
    @Nullable private java.util.function.Consumer<List<SimpleGallery>> deferredRelatedResponse;
    @Nullable private JSONArray pendingRelated;
    private OnlineFavoriteClient.FavoriteListSnapshot favoriteListSnapshot;
    private volatile long favoriteAuthGeneration = -1;
    @Nullable private OnlineFavoritePageStore favoritePageStore;
    @Nullable private OnlineFavoritePageStore.Request favoritePageRequest;
    @Nullable private SearchPageStore searchPageStore;
    @Nullable private SearchPageStore.Request searchPageRequest;
    @Nullable private String searchCacheIdentity;
    private long searchAuthGeneration = -1;
    private boolean searchAccountScopedCache;
    private boolean favoriteCacheHit;
    private boolean favoriteNetworkRequestAttempted;
    private boolean favoriteRefreshSucceeded;
    private boolean searchNetworkRequestAttempted;
    private boolean searchRefreshSucceeded;
    @Nullable private List<GenericGallery> cachedSearchGalleries;
    private long cachedSearchSyncedAt;

    public boolean isFavoriteIdentityCurrent() {
        if (requestType != ApiRequestType.FAVORITE && requestType != ApiRequestType.RANDOM_FAVORITE) return true;
        return favoriteAuthGeneration < 0
            || favoriteAuthGeneration == com.theskyc.nreader.settings.AuthStore.identityGeneration();
    }

    static boolean isCacheableListRequest(@Nullable ApiRequestType requestType) {
        return requestType == ApiRequestType.BYALL || requestType == ApiRequestType.BYTAG
            || requestType == ApiRequestType.BYSEARCH;
    }

    private boolean isSearchIdentityCurrent() {
        if (!searchAccountScopedCache) return true;
        Context requestContext = context.get();
        return searchAuthGeneration >= 0 && requestContext != null
            && searchAuthGeneration == AuthStore.identityGeneration()
            && Objects.equals(searchCacheIdentity, AuthStore.activeIdentity(requestContext));
    }

    public void cancelRequest() {
        canceled = true;
        okhttp3.Call call = activeCall;
        if (call != null) call.cancel();
    }

    public void setResultCurrent(@NonNull java.util.function.BooleanSupplier resultCurrent) {
        this.resultCurrent = resultCurrent;
    }

    public void setDeferredRelatedResponse(@NonNull java.util.function.Consumer<List<SimpleGallery>> callback) {
        deferredRelatedResponse = callback;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public String getRetryAfter() {
        return retryAfter;
    }

    protected InspectorV3(Parcel in) {
        sortType = SortType.values()[in.readByte()];
        custom = in.readByte() != 0;
        page = in.readInt();
        pageCount = in.readInt();
        id = in.readInt();
        query = in.readString();
        url = in.readString();
        requestType = ApiRequestType.values[in.readByte()];
        List<? extends GenericGallery> tmpList = null;
        switch (GenericGallery.Type.values()[in.readByte()]) {
            case LOCAL:
                tmpList = in.createTypedArrayList(LocalGallery.CREATOR);
                break;
            case SIMPLE:
                tmpList = in.createTypedArrayList(SimpleGallery.CREATOR);
                break;
            case COMPLETE:
                tmpList = in.createTypedArrayList(Gallery.CREATOR);
                break;
        }
        if (tmpList != null)
        {
            galleries = new ArrayList<>();
            galleries.addAll(tmpList);
        }
        tags = new HashSet<>(Objects.requireNonNull(in.createTypedArrayList(Tag.CREATOR)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ranges = in.readParcelable(Ranges.class.getClassLoader(), Ranges.class);
        } else {
            ranges = in.readParcelable(Ranges.class.getClassLoader());
        }
    }

    private InspectorV3(Context context, InspectorResponse response) {
        initialize(context, response);
    }

    /**
     * This method will not run, but a WebView inside MainActivity will do it in its place
     */
    public static InspectorV3 favoriteInspector(Context context, String query, int page, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.page = page;
        inspector.pageCount = 0;
        inspector.query = query == null ? "" : query;
        inspector.requestType = ApiRequestType.FAVORITE;
        inspector.tags = new HashSet<>(1);
        inspector.createUrl();
        return inspector;
    }

    /**
     * @param favorite true if random online favorite, false for general random manga
     */
    public static InspectorV3 randomInspector(Context context, InspectorResponse response, boolean favorite) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.requestType = favorite ? ApiRequestType.RANDOM_FAVORITE : ApiRequestType.RANDOM;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 galleryInspector(Context context, int id, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.id = id;
        inspector.requestType = ApiRequestType.BYSINGLE;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 basicInspector(Context context, int page, InspectorResponse response) {
        return searchInspector(context, null, null, page, Global.getSortType(), null, response);
    }

    public static InspectorV3 tagInspector(Context context, Tag tag, int page, SortType sortType, InspectorResponse response) {
        if (Global.isOnlyTag())
            return searchInspector(context, null, Collections.singleton(tag), page, sortType, null, response);
        return buildSearch(context, null, null, tag, page, sortType, null, response, false, null);
    }

    public static InspectorV3 searchInspector(Context context, String query, Collection<Tag> tags, int page, SortType sortType, @Nullable Ranges ranges, InspectorResponse response) {
        return buildSearch(context, query, tags, null, page, sortType, ranges, response, false, null);
    }

    public static InspectorV3 submittedSearchInspector(Context context, String query, @Nullable Collection<Tag> tags,
        SortType sortType, @Nullable Ranges ranges, InspectorResponse response,
        boolean expectsAccountTags, @Nullable AccountSearchTags accountTags) {
        InspectorV3 inspector = buildSearch(context, query, tags, null, 1, sortType, ranges, response, true, accountTags);
        inspector.accountTagSelectionMissing = expectsAccountTags && accountTags == null;
        return inspector;
    }

    private static InspectorV3 buildSearch(Context context, @Nullable String query,
                                           @Nullable Collection<Tag> tags, @Nullable Tag browsedTag, int page,
                                           SortType sortType, @Nullable Ranges ranges, InspectorResponse response,
                                           boolean submittedSearch, @Nullable AccountSearchTags accountSelection) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.submittedSearch = submittedSearch;
        inspector.custom = tags != null;
        inspector.tags = inspector.custom ? new HashSet<>(tags) : getDefaultTags();
        inspector.tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        if (accountSelection != null) {
            inspector.applyAccountTags(accountSelection.scope, accountSelection.tags);
        } else if (!submittedSearch && !inspector.custom) {
            Login.OnlineTagScope scope = Login.currentOnlineTagScope();
            if (scope != null) inspector.applyAccountTags(scope, Queries.TagTable.getAllOnlineBlacklisted());
        }
        if (browsedTag != null) {
            inspector.tags.add(browsedTag);
            if (inspector.tagsWithoutAccount != null) inspector.tagsWithoutAccount.add(browsedTag);
        }
        inspector.page = page;
        inspector.pageCount = 0;
        inspector.ranges = ranges;
        inspector.query = query == null ? "" : query;
        inspector.sortType = sortType;
        inspector.selectSearchRequestType();
        inspector.createUrl();
        return inspector;
    }

    private void applyAccountTags(Login.OnlineTagScope scope, Collection<Tag> onlineTags) {
        accountTagScope = scope;
        Set<Tag> combined = new HashSet<>(tags);
        if (scope.isCurrent() && combined.addAll(onlineTags)) {
            tagsWithoutAccount = tags;
            tags = combined;
        }
    }

    public boolean isAccountTagScopeCurrent() {
        return !accountTagSelectionMissing && (accountTagScope == null || accountTagScope.isCurrent());
    }

    public void requireAccountTagFallback() {
        accountTagSelectionMissing = true;
    }

    public boolean usesCurrentDefaultAccountTags() {
        return accountTagScope == null ? !Login.useAccountTag() : accountTagScope.isCurrent();
    }

    public InspectorV3 withoutAccountTags() {
        if (accountTagScope == null && !accountTagSelectionMissing) return this;
        InspectorV3 portable = cloneInspector(context.get(), response);
        if (tagsWithoutAccount != null) {
            portable.tags = tagsWithoutAccount;
            portable.selectSearchRequestType();
            portable.createUrl();
        }
        portable.tagsWithoutAccount = null;
        portable.accountTagScope = null;
        portable.accountTagSelectionMissing = false;
        portable.pageCount = 0;
        return portable;
    }

    private void selectSearchRequestType() {
        if (query.isEmpty() && (ranges == null || ranges.isDefault())) {
            switch (tags.size()) {
                case 0:
                    requestType = submittedSearch ? ApiRequestType.BYSEARCH : ApiRequestType.BYALL;
                    if (!submittedSearch) tryByAllPopular();
                    break;
                case 1:
                    Tag tag = tags.iterator().next();
                    if (tag != null && tag.getId() > 0 && tag.getStatus() != TagStatus.AVOIDED) {
                        requestType = ApiRequestType.BYTAG;
                        break;
                    }
                default:
                    requestType = ApiRequestType.BYSEARCH;
                    break;
            }
        } else requestType = ApiRequestType.BYSEARCH;
    }

    public static InspectorV3 savedSearchInspector(Context context, String query, int page, SortType sortType, InspectorResponse response) {
        return savedInspector(context, query, null, page, sortType, response);
    }

    public static InspectorV3 savedHomeInspector(Context context, int page, InspectorResponse response) {
        InspectorV3 inspector = savedSearchInspector(context, "", page, SortType.RECENT_ALL_TIME, response);
        inspector.requestType = ApiRequestType.BYALL;
        inspector.createUrl();
        return inspector;
    }

    public static InspectorV3 savedTagInspector(Context context, Tag tag, int page, SortType sortType, InspectorResponse response) {
        Tag canonical = new Tag(tag.getName(), tag.getCount(), tag.getId(), tag.getType(), TagStatus.DEFAULT);
        return savedInspector(context, "", canonical, page, sortType, response);
    }

    private static InspectorV3 savedInspector(Context context, String query, @Nullable Tag tag, int page,
                                               SortType sortType, InspectorResponse response) {
        InspectorV3 inspector = new InspectorV3(context, response);
        inspector.custom = true;
        inspector.tags = new HashSet<>();
        inspector.page = page;
        inspector.pageCount = 0;
        inspector.query = query == null ? "" : query;
        inspector.sortType = sortType;
        inspector.requestType = tag == null ? ApiRequestType.BYSEARCH : ApiRequestType.BYTAG;
        if (tag != null) inspector.tags.add(tag);
        inspector.createUrl();
        return inspector;
    }

    @NonNull
    private static HashSet<Tag> getDefaultTags() {
        HashSet<Tag> tags = new HashSet<>(Queries.TagTable.getAllStatus(TagStatus.ACCEPTED));
        tags.addAll(getLanguageTags(Global.getOnlyLanguage()));
        if (Global.removeAvoidedGalleries())
            tags.addAll(Queries.TagTable.getAllStatus(TagStatus.AVOIDED));
        return tags;
    }

    private static Set<Tag> getLanguageTags(Language onlyLanguage) {
        Set<Tag> tags = new HashSet<>();
        if (onlyLanguage == null) return tags;
        switch (onlyLanguage) {
            case ENGLISH:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_ENGLISH));
                break;
            case JAPANESE:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_JAPANESE));
                break;
            case CHINESE:
                tags.add(Queries.TagTable.getTagById(SpecialTagIds.LANGUAGE_CHINESE));
                break;
        }
        return tags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (accountTagScope != null || accountTagSelectionMissing) {
            withoutAccountTags().writeToParcel(dest, flags);
            return;
        }
        dest.writeByte((byte) (Objects.requireNonNullElse(sortType, SortType.RECENT_ALL_TIME).ordinal()));
        dest.writeByte((byte) (custom ? 1 : 0));
        dest.writeInt(page);
        dest.writeInt(pageCount);
        dest.writeInt(id);
        dest.writeString(query);
        dest.writeString(url);
        dest.writeByte(requestType.ordinal());
        if (galleries == null || galleries.isEmpty())
            dest.writeByte((byte) GenericGallery.Type.SIMPLE.ordinal());
        else dest.writeByte((byte) galleries.get(0).getType().ordinal());
        dest.writeTypedList(galleries);
        dest.writeTypedList(new ArrayList<>(tags));
        dest.writeParcelable(ranges, flags);
    }

    public String getSearchTitle() {
        //triggered only when in searchMode
        if (!query.isEmpty()) return query;
        return withoutAccountTags().url.replace(Utility.getBaseUrl() + "api/v2/search?query=", "").replace('+', ' ');
    }

    public void initialize(Context context, InspectorResponse response) {
        this.response = response;
        this.deliveryResponse = response;
        this.context = new WeakReference<>(context);
    }

    public InspectorResponse getResponse() {
        return response;
    }

    public void setDeliveryResponse(@NonNull InspectorResponse response) {
        deliveryResponse = response;
    }

    public InspectorV3 cloneInspector(Context context, InspectorResponse response) {
        InspectorV3 inspectorV3 = new InspectorV3(context, response);
        inspectorV3.query = query;
        inspectorV3.url = url;
        inspectorV3.tags = tags;
        inspectorV3.tagsWithoutAccount = tagsWithoutAccount;
        inspectorV3.accountTagScope = accountTagScope;
        inspectorV3.accountTagSelectionMissing = accountTagSelectionMissing;
        inspectorV3.submittedSearch = submittedSearch;
        inspectorV3.requestType = requestType;
        inspectorV3.sortType = sortType;
        inspectorV3.pageCount = pageCount;
        inspectorV3.page = page;
        inspectorV3.id = id;
        inspectorV3.custom = custom;
        inspectorV3.ranges = ranges;
        return inspectorV3;
    }

    private void tryByAllPopular() {
        if (sortType != SortType.RECENT_ALL_TIME) {
            requestType = ApiRequestType.BYSEARCH;
            query = "-nclientv3";
        }
    }

    private static boolean isSiteQueryTag(Tag tag) {
        return tag != null && tag.getType() != null && tag.getType().getId() > 0
            && TagCatalog.isCanonicalName(tag.getName());
    }

    private void createUrl() {
        String query;
        try {
            query = this.query == null ? null : URLEncoder.encode(this.query, Charset.defaultCharset().name());
        } catch (UnsupportedEncodingException ignore) {
            query = this.query;
        }
        StringBuilder builder = new StringBuilder(Utility.getBaseUrl()).append("api/v2/");
        if (requestType == ApiRequestType.BYALL) {
            builder.append("galleries?page=").append(page);
        } else if (requestType == ApiRequestType.RANDOM) {
            builder.append("galleries/random");
        } else if (requestType == ApiRequestType.RANDOM_FAVORITE) {
            builder.append("favorites/random");
        } else if (requestType == ApiRequestType.BYSINGLE) {
            builder.append("galleries/").append(id).append("?include=related,favorite");
        } else if (requestType == ApiRequestType.FAVORITE) {
            builder.append("favorites?page=").append(page);
            if (query != null && !query.isEmpty())
                builder.append("&q=").append(query);
        } else if (requestType == ApiRequestType.BYSEARCH || requestType == ApiRequestType.BYTAG) {
            builder.append("search?query=").append(query);
            Set<String> emittedTerms = new HashSet<>();
            for (Tag tt : tags) {
                if (!isSiteQueryTag(tt)) continue;
                String term = tt.toQueryTag();
                if (!emittedTerms.add(term)) continue;
                builder.append('+');
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.append(URLEncoder.encode(term, Charset.defaultCharset()));
                } else {
                    try {
                        //noinspection CharsetObjectCanBeUsed
                        builder.append(URLEncoder.encode(term, Charset.defaultCharset().name()));
                    } catch (UnsupportedEncodingException e) {
                        LogUtility.wtf("This should not happen since we used the default charset", e);
                        return;
                    }
                }
            }
            if (ranges != null && !ranges.isDefault())
                builder.append('+').append(ranges.toQuery());
            builder.append("&page=").append(page);
            if (sortType != null && sortType.getUrlAddition() != null) {
                builder.append("&sort=").append(sortType.getUrlAddition());
            }
        }
        url = builder.toString().replace(' ', '+');
    }

    public boolean createDocument() throws IOException {
        if (!isAccountTagScopeCurrent()) throw new IOException("Online tag selection expired");
        if (jsonResponse != null) return true;
        Context requestContext = context.get();
        if (requestContext == null) throw new IOException("Request context unavailable");
        boolean accountFavorites = requestType == ApiRequestType.FAVORITE || requestType == ApiRequestType.RANDOM_FAVORITE;
        String requestUrl = resolveRequestUrl(requestContext, accountFavorites);
        Request.Builder builder = new Request.Builder().url(requestUrl);
        if (accountFavorites) {
            prepareFavoriteList(requestContext);
            builder.tag(ApiAuthInterceptor.ExpectedIdentity.class,
                new ApiAuthInterceptor.ExpectedIdentity(favoriteListSnapshot.identity, this::isFavoriteIdentityCurrent));
        } else if (accountTagScope != null) {
            builder.tag(ApiAuthInterceptor.ExpectedIdentity.class, accountTagScope.expectedIdentity());
        }
        okhttp3.Call call = Global.getClient(requestContext).newCall(builder.build());
        activeCall = call;
        if (canceled) call.cancel();
        if (requestType == ApiRequestType.FAVORITE) favoriteNetworkRequestAttempted = true;
        else if (isCacheableListRequest(requestType)) searchNetworkRequestAttempted = true;
        try (Response response = call.execute()) {
            responseCode = response.code();
            retryAfter = response.header("Retry-After");
            jsonResponse = response.body().string();
            return response.code() == HttpURLConnection.HTTP_OK;
        } finally {
            activeCall = null;
        }
    }

    private String resolveRequestUrl(Context requestContext, boolean accountFavorites) {
        if (!accountFavorites && accountTagScope == null) return url;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(url);
        okhttp3.HttpUrl base = okhttp3.HttpUrl.get(Utility.getAuthenticatedApiBaseUrl(requestContext));
        return parsed.newBuilder().host(base.host()).port(base.port()).build().toString();
    }

    private void prepareFavoriteList(Context requestContext) throws IOException {
        if (favoriteListSnapshot == null) {
            favoriteAuthGeneration = AuthStore.identityGeneration();
            favoriteListSnapshot = OnlineFavoriteClient.beginFavoriteList(requestContext);
        }
        if (favoriteListSnapshot == null || favoriteAuthGeneration < 0 || !isFavoriteIdentityCurrent())
            throw new IOException("Online identity is unavailable");
    }

    public void parseDocument() throws IOException, InvalidResponseException {
        try {
            if (requestType.isSingle()) doSingleV2();
            else doSearchV2();
        } catch (JSONException e) {
            LogUtility.e("JSON parse error: " + e.getMessage(), e);
            throw new InvalidResponseException();
        }
        jsonResponse = null;
    }

    @Override
    public synchronized void start() {
        if (getState() != State.NEW) return;
        if (deliveryResponse.shouldStart(this))
            super.start();
    }

    @Override
    public void run() {
        LogUtility.d("Starting API request: " + requestType);
        if (deliveryResponse != null) deliveryResponse.onStart();
        try {
            if (requestType == ApiRequestType.FAVORITE) loadFavoritePageCache();
            else if (isCacheableListRequest(requestType)) loadSearchPageCache();
            deliverCachedSearchPage();
            favoriteCacheHit = false;
            if (!createDocument()) throw new IOException("API request failed: HTTP " + responseCode);
            String favoriteResponseForCache = requestType == ApiRequestType.FAVORITE ? jsonResponse : null;
            String searchResponseForCache = isCacheableListRequest(requestType) ? jsonResponse : null;
            parseDocument();
            if (favoritePageStore != null && favoritePageRequest != null && favoriteResponseForCache != null
                && responseCode == HttpURLConnection.HTTP_OK && !canceled && resultCurrent.getAsBoolean()
                && isFavoriteIdentityCurrent() && favoritePageStore.recordRefreshSuccess(favoritePageRequest)) {
                favoriteRefreshSucceeded = true;
                try {
                    favoritePageStore.save(favoritePageRequest, favoriteResponseForCache, pageCount,
                        System.currentTimeMillis());
                } catch (RuntimeException error) {
                    LogUtility.w("Unable to update online favorite page cache", error);
                }
            }
            if (searchPageStore != null && searchPageRequest != null && searchResponseForCache != null
                && responseCode == HttpURLConnection.HTTP_OK && !canceled && resultCurrent.getAsBoolean()
                && isSearchIdentityCurrent() && isAccountTagScopeCurrent()
                && searchPageStore.recordRefreshSuccess(searchPageRequest)) {
                searchRefreshSucceeded = true;
                try {
                    searchPageStore.save(searchPageRequest, searchResponseForCache, pageCount,
                        System.currentTimeMillis());
                } catch (RuntimeException error) {
                    LogUtility.w("Unable to update search page cache", error);
                }
            }
            if (deliveryResponse != null && resultCurrent.getAsBoolean() && isFavoriteIdentityCurrent()
                && isSearchIdentityCurrent() && isAccountTagScopeCurrent()) {
                deliveryResponse.onSuccess(galleries);
            }
            if (pendingRelated != null && deferredRelatedResponse != null && !canceled) {
                try {
                    List<SimpleGallery> related = parseDeferredRelated(pendingRelated);
                    hydrateRelatedTags(related);
                    if (!canceled && resultCurrent.getAsBoolean() && isFavoriteIdentityCurrent())
                        deferredRelatedResponse.accept(related);
                } catch (RuntimeException error) {
                    if (!canceled) LogUtility.w("Unable to prepare related galleries", error);
                }
            }
        } catch (Exception e) {
            recordFavoriteRefreshFailure();
            recordSearchRefreshFailure();
            if (deliveryResponse != null && resultCurrent.getAsBoolean() && isAccountTagScopeCurrent()
                && (favoriteAuthGeneration < 0 || isFavoriteIdentityCurrent()) && isSearchIdentityCurrent())
                deliveryResponse.onFailure(e);
        } finally {
            if (favoritePageStore != null) {
                if (favoritePageRequest != null) favoritePageStore.finishRequest(favoritePageRequest);
                favoritePageStore.close();
                favoritePageStore = null;
                favoritePageRequest = null;
            }
            if (searchPageStore != null) {
                if (searchPageRequest != null) searchPageStore.finishRequest(searchPageRequest);
                searchPageStore.close();
                searchPageStore = null;
                searchPageRequest = null;
            }
        }
        if (deliveryResponse != null) deliveryResponse.onEnd();
        LogUtility.d("Finished API request: " + requestType);
    }

    private void loadFavoritePageCache() throws IOException {
        Context requestContext = context.get();
        if (requestContext == null) return;
        String requestUrl = resolveRequestUrl(requestContext, true);
        prepareFavoriteList(requestContext);
        try {
            favoritePageStore = OnlineFavoritePageStore.open(requestContext);
            favoritePageRequest = favoritePageStore.beginRequest(favoriteListSnapshot.identity, requestUrl);
        } catch (RuntimeException error) {
            if (favoritePageStore != null) favoritePageStore.close();
            favoritePageStore = null;
            LogUtility.w("Online favorite page cache is unavailable", error);
            return;
        }
        OnlineFavoritePageStore.CachedPage cached = favoritePageStore.load(favoritePageRequest,
            System.currentTimeMillis());
        if (cached == null || canceled || !resultCurrent.getAsBoolean() || !isFavoriteIdentityCurrent()
            || !favoritePageStore.isCurrent(favoritePageRequest)) return;

        favoriteCacheHit = true;
        responseCode = HttpURLConnection.HTTP_OK;
        jsonResponse = cached.payload;
        try {
            parseDocument();
        } catch (Exception error) {
            favoritePageStore.recordCacheMiss(favoritePageRequest);
            favoritePageStore.delete(favoritePageRequest);
            favoriteCacheHit = false;
            galleries = null;
            pageCount = -1;
            jsonResponse = null;
            LogUtility.w("Discarded invalid online favorite page cache", error);
            return;
        }
        if (deliveryResponse != null && resultCurrent.getAsBoolean() && !canceled
            && isFavoriteIdentityCurrent() && favoritePageStore.recordCacheHit(favoritePageRequest)) {
            deliveryResponse.onCacheHit(galleries, cached.syncedAt);
        }
    }

    private void recordFavoriteRefreshFailure() {
        if (!favoriteNetworkRequestAttempted || favoriteRefreshSucceeded || canceled
            || !resultCurrent.getAsBoolean() || !isFavoriteIdentityCurrent()
            || favoritePageStore == null || favoritePageRequest == null) return;
        favoritePageStore.recordRefreshFailure(favoritePageRequest);
    }

    private void loadSearchPageCache() {
        Context requestContext = context.get();
        if (requestContext == null || canceled || !resultCurrent.getAsBoolean()
            || !isAccountTagScopeCurrent()) return;

        searchAccountScopedCache = accountTagScope != null;
        if (searchAccountScopedCache) {
            searchAuthGeneration = AuthStore.identityGeneration();
            searchCacheIdentity = AuthStore.activeIdentity(requestContext);
            if (searchAuthGeneration < 0 || searchCacheIdentity == null) return;
        } else {
            searchAuthGeneration = -1;
            searchCacheIdentity = null;
        }

        String requestUrl = resolveRequestUrl(requestContext, false);
        try {
            searchPageStore = SearchPageStore.open(requestContext);
            searchPageRequest = searchPageStore.beginRequest(searchCacheIdentity, requestUrl);
        } catch (RuntimeException error) {
            if (searchPageStore != null) searchPageStore.close();
            searchPageStore = null;
            LogUtility.w("Search page cache is unavailable", error);
            return;
        }

        SearchPageStore.CachedPage cached = searchPageStore.load(searchPageRequest,
            System.currentTimeMillis());
        if (cached == null || canceled || !resultCurrent.getAsBoolean() || !isSearchIdentityCurrent()
            || !isAccountTagScopeCurrent() || !searchPageStore.isCurrent(searchPageRequest)) return;

        responseCode = HttpURLConnection.HTTP_OK;
        pageCount = cached.pageCount;
        jsonResponse = cached.payload;
        try {
            parseDocument();
        } catch (Exception error) {
            searchPageStore.recordCacheMiss(searchPageRequest);
            searchPageStore.delete(searchPageRequest);
            galleries = null;
            pageCount = -1;
            jsonResponse = null;
            LogUtility.w("Discarded invalid search page cache", error);
            return;
        }
        cachedSearchGalleries = new ArrayList<>(galleries);
        cachedSearchSyncedAt = cached.syncedAt;
    }

    private void deliverCachedSearchPage() {
        List<GenericGallery> cached = cachedSearchGalleries;
        cachedSearchGalleries = null;
        if (cached == null || deliveryResponse == null || canceled || !resultCurrent.getAsBoolean()
            || !isSearchIdentityCurrent() || !isAccountTagScopeCurrent() || searchPageStore == null
            || searchPageRequest == null || !searchPageStore.isCurrent(searchPageRequest)) return;
        if (searchPageStore.recordCacheHit(searchPageRequest))
            deliveryResponse.onCacheHit(cached, cachedSearchSyncedAt);
    }

    private void recordSearchRefreshFailure() {
        if (!searchNetworkRequestAttempted || searchRefreshSucceeded || canceled
            || !resultCurrent.getAsBoolean() || !isSearchIdentityCurrent() || !isAccountTagScopeCurrent()
            || searchPageStore == null || searchPageRequest == null) return;
        searchPageStore.recordRefreshFailure(searchPageRequest);
    }

    private void filterDocumentTags() {
        if (galleries.isEmpty() || tags == null || tags.isEmpty()) return;
        List<Tag> identifiedTags = new ArrayList<>();
        for (Tag tag : tags) {
            if (isSiteQueryTag(tag) && tag.getId() > 0) identifiedTags.add(tag);
        }
        if (identifiedTags.isEmpty()) return;
        galleries.removeIf(result -> {
            SimpleGallery gallery = (SimpleGallery) result;
            for (Tag tag : identifiedTags) {
                boolean include = tag.getStatus() != TagStatus.AVOIDED;
                if (gallery.hasSiteTag(tag) != include) return true;
            }
            return false;
        });
    }

    /**
     * Parse single gallery from API v2 response.
     * For RANDOM, the response is just {"id": N}, so we fetch the full detail.
     */
    private void doSingleV2() throws IOException, JSONException {
        galleries = new ArrayList<>(1);
        JSONObject v2 = new JSONObject(jsonResponse);
        if (v2.has("error")) return;

        // Random endpoint returns only {"id": N} — fetch full gallery detail
        if (!v2.has("title") && v2.has("id")) {
            int galleryId = v2.getInt("id");
            String detailUrl = Utility.getBaseUrl() + "api/v2/galleries/" + galleryId + "?include=related,favorite";
            okhttp3.Call call = Global.getClient(context.get()).newCall(new Request.Builder().url(detailUrl).build());
            activeCall = call;
            if (canceled) call.cancel();
            try (Response resp = call.execute()) {
                String body = resp.body().string();
                if (resp.code() != HttpURLConnection.HTTP_OK) return;
                v2 = new JSONObject(body);
            } finally {
                activeCall = null;
            }
        }

        // Handle related galleries
        List<SimpleGallery> relatedList = new ArrayList<>();
        JSONArray relatedArr = v2.optJSONArray("related");
        if (deferredRelatedResponse != null) {
            pendingRelated = relatedArr == null ? new JSONArray() : relatedArr;
        } else if (relatedArr != null) {
            for (int i = 0; i < relatedArr.length(); i++) {
                relatedList.add(SimpleGallery.fromV2ListItem(context.get(), relatedArr.getJSONObject(i)));
            }
            hydrateRelatedTags(relatedList);
        }

        Object favoriteValue = v2.opt("is_favorited");
        Boolean isFavorite = favoriteValue instanceof Boolean ? (Boolean) favoriteValue : null;

        Gallery gallery = new Gallery(context.get(), v2.toString(), relatedList, isFavorite);
        galleries.add(gallery);
    }

    private List<SimpleGallery> parseDeferredRelated(JSONArray related) {
        List<SimpleGallery> result = new ArrayList<>();
        for (int index = 0; index < related.length() && !canceled; index++) {
            try {
                result.add(SimpleGallery.fromV2ListItem(context.get(), related.getJSONObject(index)));
            } catch (JSONException | RuntimeException error) {
                LogUtility.w("Unable to parse related gallery at index " + index);
            }
        }
        return result;
    }

    private void hydrateRelatedTags(List<SimpleGallery> related) {
        Set<Integer> missingIds = new LinkedHashSet<>();
        for (SimpleGallery gallery : related) missingIds.addAll(gallery.getTagIds());
        if (missingIds.isEmpty() || context.get() == null) return;

        ArrayList<Integer> batch = new ArrayList<>(100);
        ArrayList<Tag> fetched = new ArrayList<>();
        for (int id : missingIds) {
            if (canceled) return;
            batch.add(id);
            if (batch.size() == 100) {
                fetchTagBatch(batch, fetched);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) fetchTagBatch(batch, fetched);
        for (SimpleGallery gallery : related) gallery.replaceTagsFromLookup(fetched);
    }

    private void fetchTagBatch(List<Integer> ids, List<Tag> destination) {
        if (canceled) return;
        StringBuilder query = new StringBuilder();
        for (int id : ids) {
            if (query.length() > 0) query.append(',');
            query.append(id);
        }
        String url = Utility.getBaseUrl() + "api/v2/tags/ids?ids=" + query;
        okhttp3.Call call = Global.getClient(context.get()).newCall(new Request.Builder().url(url).build());
        activeCall = call;
        if (canceled) call.cancel();
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                LogUtility.w("Unable to load related gallery tags: HTTP " + response.code());
                return;
            }
            JSONArray tags = new JSONArray(response.body().string());
            for (int i = 0; i < tags.length(); i++) {
                JSONObject json = tags.getJSONObject(i);
                Tag tag = new Tag(json.getString("name"), json.optInt("count"), json.getInt("id"),
                    TagType.typeByName(json.getString("type")), TagStatus.DEFAULT);
                Queries.TagTable.insert(tag);
                destination.add(tag);
            }
        } catch (IOException | JSONException e) {
            if (!canceled) LogUtility.w("Unable to load missing related gallery tags", e);
        } finally {
            activeCall = null;
        }
    }

    /**
     * Parse search/list results from API v2 response.
     * v2 list items have: id, media_id, thumbnail, english_title, japanese_title, tag_ids, num_pages
     */
    private void doSearchV2() throws InvalidResponseException, JSONException {
        JSONObject json = new JSONObject(jsonResponse);
        if (!json.has("result"))
            throw new InvalidResponseException();
        JSONArray results = json.getJSONArray("result");
        galleries = new ArrayList<>(results.length());
        for (int i = 0; i < results.length(); i++) {
            galleries.add(SimpleGallery.fromV2ListItem(context.get(), results.getJSONObject(i)));
        }
        Context resultContext = context.get();
        if (requestType == ApiRequestType.FAVORITE && responseCode == HttpURLConnection.HTTP_OK
            && !favoriteCacheHit && !canceled && resultContext != null) {
            List<Integer> ids = new ArrayList<>(galleries.size());
            for (GenericGallery gallery : galleries) ids.add(gallery.getId());
            OnlineFavoriteClient.acceptFavoriteList(resultContext, favoriteListSnapshot, ids);
        }
        pageCount = json.optInt("num_pages", Math.max(1, page));
        if (Global.isExactTagMatch())
            filterDocumentTags();
    }

    public void setSortType(SortType sortType) {
        this.sortType = sortType;
        if (this.requestType == ApiRequestType.BYALL)
            tryByAllPopular();
        createUrl();
    }

    @Nullable
    public SortType getSortType() {
        return sortType;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
        createUrl();
    }

    public List<GenericGallery> getGalleries() {
        return galleries;
    }

    public String getUrl() {
        return url;
    }

    public ApiRequestType getRequestType() {
        return requestType;
    }

    public int getPageCount() {
        return pageCount;
    }

    public Tag getTag() {
        Tag t = null;
        if (tags == null) return null;
        for (Tag tt : tags) {
            if (tt.getType() != TagType.LANGUAGE)
                return tt;
            t = tt;
        }
        return t;
    }

    public static class InvalidResponseException extends Exception {
        public InvalidResponseException() {
            super();
        }
    }

    public interface InspectorResponse {
        boolean shouldStart(InspectorV3 inspector);

        void onSuccess(List<GenericGallery> galleries);

        default void onCacheHit(List<GenericGallery> galleries, long syncedAt) {
        }

        void onFailure(Exception e);

        void onStart();

        void onEnd();
    }

    public static abstract class DefaultInspectorResponse implements InspectorResponse {
        @Override
        public boolean shouldStart(InspectorV3 inspector) {
            return true;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onEnd() {
        }

        @Override
        public void onSuccess(List<GenericGallery> galleries) {
        }

        @Override
        public void onFailure(Exception e) {
            LogUtility.e(e.getLocalizedMessage(), e);
        }
    }
}
