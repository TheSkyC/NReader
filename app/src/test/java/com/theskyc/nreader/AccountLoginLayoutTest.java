// NReader modifications by TheSkyC (2026); see NOTICE.
package com.theskyc.nreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w280dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AccountLoginLayoutTest {
    @Test
    public void formControlsRemainReadableInCompactAndLargeTextConfigurations() {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).get();
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("zh", "CN"), new Locale("ar", "SA")}) {
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(locale);
            configuration.setLayoutDirection(locale);
            configuration.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                R.style.AppTheme_Material3);
            float density = context.getResources().getDisplayMetrics().density;
            View root = LayoutInflater.from(context).inflate(R.layout.activity_account_login, null);
            root.setLayoutDirection(configuration.getLayoutDirection());
            root.findViewById(R.id.account_progress).setVisibility(View.VISIBLE);
            ((TextView) root.findViewById(R.id.account_status)).setText(R.string.account_verification_error);
            int width = Math.round(280 * density);
            int height = Math.round(360 * density);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);

            for (int id : new int[]{R.id.account_username, R.id.account_password,
                R.id.account_remember_password, R.id.account_sign_in, R.id.account_sign_out}) {
                TextView control = root.findViewById(id);
                assertTrue(locale + ": touch target " + id, control.getHeight() >= Math.round(48 * density));
                assertTrue(locale + ": text height " + id, control.getLayout().getHeight() <= control.getHeight());
                for (int line = 0; line < control.getLineCount(); line++) {
                    assertEquals(locale + ": truncated text " + id, 0,
                        control.getLayout().getEllipsisCount(line));
                }
            }
            TextView password = root.findViewById(R.id.account_password);
            TextInputLayout passwordContainer = (TextInputLayout) password.getParent().getParent();
            View toggle = passwordContainer.findViewById(com.google.android.material.R.id.text_input_end_icon);
            assertEquals(View.VISIBLE, toggle.getVisibility());
            assertTrue(password.getTransformationMethod() instanceof PasswordTransformationMethod);
            toggle.performClick();
            assertTrue(!(password.getTransformationMethod() instanceof PasswordTransformationMethod));
            toggle.performClick();
            assertTrue(password.getTransformationMethod() instanceof PasswordTransformationMethod);
            assertTrue(password.getWidth() > Math.round(48 * density));
            ScrollView scroll = (ScrollView) root.findViewById(R.id.account_sign_in).getParent().getParent();
            View signIn = root.findViewById(R.id.account_sign_in);
            Rect buttonBounds = new Rect();
            signIn.getDrawingRect(buttonBounds);
            scroll.offsetDescendantRectToMyCoords(signIn, buttonBounds);
            scroll.scrollTo(0, buttonBounds.bottom - scroll.getHeight());
            assertTrue(locale + ": sign in must be reachable", buttonBounds.top >= scroll.getScrollY()
                && buttonBounds.bottom <= scroll.getScrollY() + scroll.getHeight());
        }
    }
}
