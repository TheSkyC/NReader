package com.theskyc.nreader.async.database;

import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.util.JsonWriter;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Filterable;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.chip.Chip;
import com.theskyc.nreader.R;
import com.theskyc.nreader.SearchActivity;
import com.theskyc.nreader.TagFilterActivity;
import com.theskyc.nreader.components.widgets.ChipTag;
import com.theskyc.nreader.components.widgets.TagTypePage;
import com.theskyc.nreader.settings.Global;
import com.theskyc.nreader.settings.TagLanguage;
import com.theskyc.nreader.settings.TagV2;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
public class TagInteractionDeviceBenchmarkTest {
    private static final String LOG = "TagInteractionBenchmark";
    private static final long TIMEOUT_MS = 25_000;
    private static final int SWIPE_BOUNDARY = 704;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test
    public void displaysAliasSuggestionAfterTyping() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("alias-input", (context, database, directory) -> {
            long rows = TagCatalogDeviceBenchmarkTest.importCatalog(context, database, "setup");
            Global.initFromShared(context);
            int id = TagCatalogDeviceBenchmarkTest.eligibleTagId(database, TagV2.getMinCount());
            String alias = "alias-a-" + id + "-end";
            String translatedName = "translated-" + id;
            SearchActivity activity = launch(context, SearchActivity.class, null);
            try {
                String locale = onMain(() -> activity.getResources().getConfiguration().getLocales().get(0)
                    .toLanguageTag());
                assertTrue("Unsupported benchmark locale: " + locale, TagTranslationCatalog.isSupportedLocale(locale));
                TagCatalogDeviceBenchmarkTest.importTranslation(database, directory, rows, "setup", locale);
                waitFor("search options button", () -> onMain(() -> {
                    View options = activity.findViewById(R.id.view_groups);
                    return activity.hasWindowFocus() && options != null && options.isShown() && options.performClick();
                }));
                waitFor("visible add tag chip", () -> onMain(() -> {
                    ViewGroup group = activity.findViewById(R.id.tag_group);
                    View last = group.getChildAt(group.getChildCount() - 1);
                    return activity.hasWindowFocus() && last instanceof Chip && !(last instanceof ChipTag) && last.isShown()
                        && last.performClick();
                }));
                waitFor("dialog to take window focus", () -> onMain(() -> !activity.hasWindowFocus()));
                UiAutomation automation = instrumentation.getUiAutomation();
                AccessibilityServiceInfo info = automation.getServiceInfo();
                int savedFlags = info.flags;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                automation.setServiceInfo(info);
                try {
                    long start = SystemClock.elapsedRealtimeNanos();
                    instrumentation.sendStringSync(alias);
                    long keysSent = SystemClock.elapsedRealtimeNanos();
                    waitFor("translated suggestion in the popup", () -> hasVisibleText(automation, translatedName));
                    long observed = SystemClock.elapsedRealtimeNanos();
                    Log.i(LOG, "aliasKeys=" + alias.length() + " inputToObservedMs=" + millis(start, observed)
                        + " afterKeysSentMs=" + millis(keysSent, observed));
                } finally {
                    info.flags = savedFlags;
                    automation.setServiceInfo(info);
                }
            } finally {
                close(activity);
            }
        });
    }

    @Test
    public void refreshesVisibleSuggestionsAfterRebuild() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("rebuild-suggestion", (context, database, directory) -> {
            Global.initFromShared(context);
            database.execSQL("INSERT INTO Tags (idTag, name, type, count) VALUES (700004, 'canonical fixture', 3, 200000)");
            SearchActivity activity = launch(context, SearchActivity.class, null);
            try {
                String locale = onMain(() -> activity.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
                String pack = "{\"schemaVersion\":2,\"locale\":\"" + locale + "\",\"revision\":1,"
                    + "\"entries\":[{\"id\":700004,\"name\":\"translated fixture\",\"aliases\":[\"unique alias\"]}]}";
                assertTrue(TagTranslationCatalog.apply(database, new ByteArrayInputStream(pack.getBytes(StandardCharsets.UTF_8)),
                    directory, () -> false));
                instrumentation.waitForIdleSync();
                TagTranslationCatalog.scheduleKeyRebuild(database);
                waitFor("search options button", () -> onMain(() -> {
                    View options = activity.findViewById(R.id.view_groups);
                    return activity.hasWindowFocus() && options != null && options.isShown() && options.performClick();
                }));
                waitFor("visible add tag chip", () -> onMain(() -> {
                    ViewGroup group = activity.findViewById(R.id.tag_group);
                    View last = group.getChildAt(group.getChildCount() - 1);
                    return activity.hasWindowFocus() && last instanceof Chip && !(last instanceof ChipTag)
                        && last.isShown() && last.performClick();
                }));
                waitFor("dialog to take window focus", () -> onMain(() -> !activity.hasWindowFocus()));
                UiAutomation automation = instrumentation.getUiAutomation();
                AccessibilityServiceInfo info = automation.getServiceInfo();
                int savedFlags = info.flags;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                automation.setServiceInfo(info);
                try {
                    instrumentation.sendStringSync("unique alias");
                    Field dialogField = SearchActivity.class.getDeclaredField("tagEntry");
                    dialogField.setAccessible(true);
                    Object tagEntry = dialogField.get(activity);
                    Field entryField = tagEntry.getClass().getDeclaredField("entry");
                    entryField.setAccessible(true);
                    AppCompatAutoCompleteTextView entry = (AppCompatAutoCompleteTextView) entryField.get(tagEntry);
                    CountDownLatch initialFilter = new CountDownLatch(1);
                    instrumentation.runOnMainSync(() -> {
                        assertTrue("Input must reach the tag field", "unique alias".contentEquals(entry.getText()));
                        ((Filterable) entry.getAdapter()).getFilter().filter("unique alias", count -> initialFilter.countDown());
                    });
                    assertTrue("Initial filtering did not finish", initialFilter.await(25, TimeUnit.SECONDS));
                    assertTrue("Translation must be hidden while rebuilding",
                        !hasVisibleText(automation, "translated fixture"));
                    while (!TagTranslationCatalog.rebuildUnicodeKeyBatch(database, () -> false)) { }
                    waitFor("refreshed translated suggestion", () -> hasVisibleText(automation, "translated fixture"));
                } finally {
                    info.flags = savedFlags;
                    automation.setServiceInfo(info);
                }
            } finally {
                close(activity);
            }
        });
    }

    @Test
    public void populatesTagRowsAfterJumpAndSwipe() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("scroll", (context, database, directory) -> {
            TagCatalogDeviceBenchmarkTest.importCatalog(context, database, "setup");
            Global.initFromShared(context);
            TagFilterActivity activity = launch(context, TagFilterActivity.class,
                Uri.parse("https://nhentai.net/tags/"));
            try {
                AtomicReference<RecyclerView> list = new AtomicReference<>();
                waitFor("full tag list", () -> onMain(() -> {
                    TagTypePage page = (TagTypePage) activity.getSupportFragmentManager().findFragmentByTag("f1");
                    RecyclerView recycler = page == null ? null : page.getRecyclerView();
                    if (recycler == null || !recycler.isShown() || recycler.getAdapter() == null
                        || !hasTitleAt(recycler, 0)) return false;
                    list.set(recycler);
                    return activity.hasWindowFocus();
                }));
                RecyclerView recycler = list.get();
                int rows = onMain(() -> recycler.getAdapter().getItemCount());
                int[] jumps = {128, 384, 640};
                assertTrue("No rows past the swipe boundary: " + rows, rows > SWIPE_BOUNDARY);
                Log.i(LOG, "browseItems=" + rows + " minimumCount=" + TagV2.getMinCount());
                for (int position : jumps) {
                    measureJump(recycler, position);
                }
                measureSwipe(recycler);
            } finally {
                close(activity);
            }
        });
    }

    @Test
    public void refreshesVisibleTagAfterTranslationInstall() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("translation-ui", (context, database, directory) -> {
            TagCatalogDeviceBenchmarkTest.importCatalog(context, database, "setup");
            Global.initFromShared(context);
            String locale = context.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            assertTrue("Unsupported benchmark locale: " + locale, TagTranslationCatalog.isSupportedLocale(locale));
            int id = TagCatalogDeviceBenchmarkTest.eligibleTagId(database, TagV2.getMinCount());
            String canonicalName = Queries.TagTable.getTagById(id).getName();
            TagFilterActivity activity = launch(context, TagFilterActivity.class,
                Uri.parse("https://nhentai.net/tags/"));
            try {
                AtomicReference<RecyclerView> list = new AtomicReference<>();
                waitFor("tag list before translation", () -> onMain(() -> {
                    TagTypePage page = (TagTypePage) activity.getSupportFragmentManager().findFragmentByTag("f1");
                    RecyclerView recycler = page == null ? null : page.getRecyclerView();
                    if (recycler == null || !recycler.isShown() || !hasTitleAt(recycler, 0)) return false;
                    list.set(recycler);
                    return true;
                }));
                assertTrue("Expected the selected benchmark row to be visible",
                    onMain(() -> {
                        TextView title = titleAt(list.get(), 0);
                        return title != null && title.getText().toString().contains(canonicalName);
                    }));
                long start = SystemClock.elapsedRealtimeNanos();
                File pack = writeSingleTranslation(directory, locale, id);
                try (InputStream input = new FileInputStream(pack)) {
                    assertTrue(TagTranslationCatalog.apply(database, input, directory, () -> false));
                } finally {
                    if (pack.exists() && !pack.delete()) Log.w(LOG, "Could not remove UI translation pack");
                }
                waitFor("translated visible tag", () -> onMain(() -> {
                    TextView title = titleAt(list.get(), 0);
                    return title != null && title.getText().toString().contains("translated-" + id);
                }));
                Log.i(LOG, "translationUiToVisibleMs=" + elapsedMillis(start));
            } finally {
                close(activity);
            }
        });
    }

    @Test
    public void fallsBackToCanonicalTagAfterTranslationRemoval() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("translation-ui-removal", (context, database, directory) -> {
            TagCatalogDeviceBenchmarkTest.importCatalog(context, database, "setup");
            Global.initFromShared(context);
            String locale = context.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            assertTrue("Unsupported benchmark locale: " + locale, TagTranslationCatalog.isSupportedLocale(locale));
            int id = TagCatalogDeviceBenchmarkTest.eligibleTagId(database, TagV2.getMinCount());
            String canonicalName = Queries.TagTable.getTagById(id).getName();
            TagFilterActivity activity = launch(context, TagFilterActivity.class,
                Uri.parse("https://nhentai.net/tags/"));
            try {
                AtomicReference<RecyclerView> list = new AtomicReference<>();
                waitFor("tag list before translation removal", () -> onMain(() -> {
                    TagTypePage page = (TagTypePage) activity.getSupportFragmentManager().findFragmentByTag("f1");
                    RecyclerView recycler = page == null ? null : page.getRecyclerView();
                    if (recycler == null || !recycler.isShown() || !hasTitleAt(recycler, 0)) return false;
                    list.set(recycler);
                    return true;
                }));
                File pack = writeSingleTranslation(directory, locale, id);
                try (InputStream input = new FileInputStream(pack)) {
                    assertTrue(TagTranslationCatalog.apply(database, input, directory, () -> false));
                } finally {
                    if (pack.exists() && !pack.delete()) Log.w(LOG, "Could not remove UI translation pack");
                }
                waitFor("translated tag before removal", () -> onMain(() -> {
                    TextView title = titleAt(list.get(), 0);
                    return title != null && title.getText().toString().contains("translated-" + id);
                }));

                long start = SystemClock.elapsedRealtimeNanos();
                TagTranslationCatalog.remove(database, locale);
                waitFor("canonical tag after translation removal", () -> onMain(() -> {
                    TextView title = titleAt(list.get(), 0);
                    if (title == null) return false;
                    String value = title.getText().toString();
                    return value.contains(canonicalName) && !value.contains("translated-" + id);
                }));
                Log.i(LOG, "translationRemovalUiToFallbackMs=" + elapsedMillis(start));
            } finally {
                close(activity);
            }
        });
    }

    @Test
    public void switchesIndependentTagLanguageOnVisibleList() throws Exception {
        TagCatalogDeviceBenchmarkTest.withFixture("tag-language-selection", (context, database, directory) -> {
            TagCatalogDeviceBenchmarkTest.importCatalog(context, database, "setup");
            Global.initFromShared(context);
            String appLocale = context.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            String selectedLocale = "ja".equals(appLocale) ? "en" : "ja";
            assertTrue("Unsupported benchmark locale: " + selectedLocale,
                TagTranslationCatalog.isSupportedLocale(selectedLocale));
            int id = TagCatalogDeviceBenchmarkTest.eligibleTagId(database, TagV2.getMinCount());
            String canonicalName = Queries.TagTable.getTagById(id).getName();
            File pack = writeSingleTranslation(directory, selectedLocale, id);
            try (InputStream input = new FileInputStream(pack)) {
                assertTrue(TagTranslationCatalog.apply(database, input, directory, () -> false));
            } finally {
                if (pack.exists() && !pack.delete()) Log.w(LOG, "Could not remove tag-language pack");
            }
            TagLanguage.select(context, selectedLocale);
            TagFilterActivity activity = launch(context, TagFilterActivity.class,
                Uri.parse("https://nhentai.net/tags/"));
            try {
                AtomicReference<RecyclerView> list = new AtomicReference<>();
                waitFor("tag list with selected language", () -> onMain(() -> {
                    TagTypePage page = (TagTypePage) activity.getSupportFragmentManager().findFragmentByTag("f1");
                    RecyclerView recycler = page == null ? null : page.getRecyclerView();
                    if (recycler == null || !recycler.isShown() || !hasTitleAt(recycler, 0)) return false;
                    list.set(recycler);
                    return true;
                }));
                waitFor("translated visible tag in selected language", () -> onMain(() -> {
                    TextView title = titleAt(list.get(), 0);
                    return title != null && title.getText().toString().contains("translated-" + id);
                }));
                TagLanguage.select(context, "");
                waitFor("canonical visible tag after returning to app language", () -> onMain(() -> {
                    TextView title = titleAt(list.get(), 0);
                    if (title == null) return false;
                    String value = title.getText().toString();
                    return value.contains(canonicalName) && !value.contains("translated-" + id);
                }));
            } finally {
                TagLanguage.select(context, "");
                close(activity);
            }
        });
    }

    private void measureJump(RecyclerView list, int position) {
        LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
        AtomicLong start = new AtomicLong();
        try (TitleFrame sample = new TitleFrame(list, position)) {
            instrumentation.runOnMainSync(() -> {
                assertTrue("Jump target was already visible", layout.findLastVisibleItemPosition() < position);
                start.set(SystemClock.elapsedRealtimeNanos());
                layout.scrollToPositionWithOffset(position, 0);
            });
            waitFor("populated row after jump to " + position, () -> sample.populated.get() != 0);
            Log.i(LOG, "jumpPosition=" + position + " toPopulatedFrameMs=" + millis(start.get(), sample.populated.get())
                + " firstVisibleToPopulatedFrameMs=" + millis(sample.visible.get(), sample.populated.get()));
        }
    }

    private void measureSwipe(RecyclerView list) {
        GridLayoutManager layout = (GridLayoutManager) list.getLayoutManager();
        int anchor = onMain(() -> {
            int items = layout.findLastVisibleItemPosition() - layout.findFirstVisibleItemPosition() + 1;
            int columns = layout.getSpanCount();
            int screenRows = (items + columns - 1) / columns;
            int position = SWIPE_BOUNDARY - (screenRows + 1) * columns;
            layout.scrollToPositionWithOffset(position, 0);
            return position;
        });
        waitFor("swipe starting row", () -> onMain(() -> hasTitleAt(list, anchor)));
        assertTrue("Swipe boundary was already visible",
            onMain(() -> layout.findLastVisibleItemPosition() < SWIPE_BOUNDARY));
        Rect viewport = new Rect();
        assertTrue("Tag list is not visible", onMain(() -> list.getGlobalVisibleRect(viewport)));
        try (TitleFrame sample = new TitleFrame(list, SWIPE_BOUNDARY)) {
            long start = SystemClock.elapsedRealtimeNanos();
            swipe(viewport);
            waitFor("populated row after swipe", () -> sample.populated.get() != 0);
            assertTrue("Swipe target was already visible", sample.visible.get() >= start);
            Log.i(LOG, "swipeBoundary=" + SWIPE_BOUNDARY + " toPopulatedFrameMs=" + millis(start, sample.populated.get())
                + " firstVisibleToPopulatedFrameMs=" + millis(sample.visible.get(), sample.populated.get()));
        }
    }

    private final class TitleFrame implements AutoCloseable {
        private final RecyclerView list;
        private final ViewTreeObserver.OnPreDrawListener listener;
        private final AtomicLong visible = new AtomicLong();
        private final AtomicLong populated = new AtomicLong();

        TitleFrame(RecyclerView list, int position) {
            this.list = list;
            listener = () -> {
                if (populated.get() != 0) return true;
                LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
                int first = layout.findFirstVisibleItemPosition();
                int last = layout.findLastVisibleItemPosition();
                if (first == RecyclerView.NO_POSITION || last < position) return true;
                long now = SystemClock.elapsedRealtimeNanos();
                visible.compareAndSet(0, now);
                for (int row = Math.max(first, position); row <= last; row++) {
                    if (hasTitleAt(list, row)) {
                        populated.set(now);
                        break;
                    }
                }
                return true;
            };
            instrumentation.runOnMainSync(() -> list.getViewTreeObserver().addOnPreDrawListener(listener));
        }

        @Override
        public void close() {
            instrumentation.runOnMainSync(() -> list.getViewTreeObserver().removeOnPreDrawListener(listener));
        }
    }

    private void swipe(Rect viewport) {
        float x = viewport.exactCenterX();
        float startY = viewport.top + viewport.height() * 0.8f;
        float endY = viewport.top + viewport.height() * 0.2f;
        long downTime = SystemClock.uptimeMillis();
        sendTouch(downTime, MotionEvent.ACTION_DOWN, x, startY);
        for (int step = 1; step <= 10; step++) {
            SystemClock.sleep(20);
            sendTouch(downTime, MotionEvent.ACTION_MOVE, x, startY + (endY - startY) * step / 10);
        }
        sendTouch(downTime, MotionEvent.ACTION_UP, x, endY);
    }

    private void sendTouch(long downTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private static boolean hasTitleAt(RecyclerView list, int position) {
        TextView title = titleAt(list, position);
        return title != null && title.getText().length() > 0 && title.getGlobalVisibleRect(new Rect());
    }

    private static TextView titleAt(RecyclerView list, int position) {
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView.findViewById(R.id.title);
    }

    private static boolean hasVisibleText(UiAutomation automation, String expected) {
        boolean found = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (found) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected)) {
                        try {
                            found |= node.isVisibleToUser() && node.getText() != null
                                && node.getText().toString().contains(expected);
                        } finally {
                            node.recycle();
                        }
                    }
                } finally {
                    root.recycle();
                }
            } finally {
                window.recycle();
            }
        }
        return found;
    }

    private <T extends Activity> T launch(Context context, Class<T> type, Uri uri) {
        return type.cast(instrumentation.startActivitySync(new Intent(context, type)
            .setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private void close(Activity activity) {
        instrumentation.runOnMainSync(activity::finishAndRemoveTask);
        waitFor("activity destruction", () -> onMain(activity::isDestroyed));
    }

    private <T> T onMain(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }

    private static void waitFor(String description, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(16);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static double millis(long start, long end) {
        return (end - start) / 1_000_000.0;
    }

    private static double elapsedMillis(long start) {
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0;
    }

    private static File writeSingleTranslation(File directory, String locale, int id) throws IOException {
        File pack = new File(directory, "ui-translation.json");
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(new FileOutputStream(pack), StandardCharsets.UTF_8))) {
            writer.beginObject();
            writer.name("schemaVersion").value(2);
            writer.name("locale").value(locale);
            writer.name("revision").value(1);
            writer.name("entries").beginArray();
            writer.beginObject();
            writer.name("id").value(id);
            writer.name("name").value("translated-" + id);
            writer.endObject();
            writer.endArray();
            writer.endObject();
        }
        return pack;
    }
}
