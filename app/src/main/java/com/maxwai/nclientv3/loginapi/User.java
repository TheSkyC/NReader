package com.maxwai.nclientv3.loginapi;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.utility.LogUtility;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class User {
    private final String username;
    private final int id;
    @androidx.annotation.Nullable private final String avatarUrl;

    User(JsonReader jr) throws IOException {
        int id = -1;
        String username = null;
        String avatarUrl = null;
        jr.beginObject();
        while (jr.peek() != JsonToken.END_OBJECT) {
            switch (jr.nextName()) {
                case "id":
                    id = jr.nextInt();
                    break;
                case "username":
                    username = jr.nextString();
                    break;
                case "avatar_url":
                    if (jr.peek() == JsonToken.STRING) avatarUrl = NhentaiLoginApi.trustedAvatarUrl(jr.nextString());
                    else jr.skipValue();
                    break;
                default:
                    jr.skipValue();
                    break;
            }
        }
        jr.endObject();
        if (id <= 0 || username == null || username.trim().isEmpty() || username.length() > 256) {
            throw new IOException("Invalid user information");
        }
        this.username = username;
        this.id = id;
        this.avatarUrl = avatarUrl;
    }

    public static void createUser(@NonNull Context context, final CreateUser createUser) {
        Context appContext = context.getApplicationContext();
        long generation = AuthStore.identityGeneration();
        if (generation < 0) return;
        String identity = AuthStore.activeIdentity(appContext);
        if (identity == null || generation != AuthStore.identityGeneration()) return;
        Global.getClient(appContext)
            .newCall(new Request.Builder().url(Utility.getAuthenticatedApiBaseUrl(appContext) + "user")
                .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity,
                    () -> generation == AuthStore.identityGeneration())).build())
            .enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    LogUtility.w("Unable to load account profile: " + e.getClass().getSimpleName());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response received = response) {
                        if (!received.isSuccessful() || received.body() == null) return;
                        User user = readResponse(received.body());
                        com.maxwai.nclientv3.settings.AccountRepository repository =
                            com.maxwai.nclientv3.settings.AccountRuntime.repository();
                        if (repository != null) {
                            synchronized (repository) {
                                if (generation != AuthStore.identityGeneration()
                                    || !repository.updateProfile(identity, repository.identityGeneration(),
                                        user.id, user.username, user.avatarUrl)) return;
                            }
                            AuthStore.notifyAccountProfileChanged();
                        }
                        if (Login.updateUserIfCurrent(appContext, identity, generation, user)) {
                            if (createUser != null) createUser.onCreateUser(user);
                        }
                    } catch (IOException | RuntimeException exception) {
                        LogUtility.w("Unable to read account profile: " + exception.getClass().getSimpleName());
                    }
                }
            });
    }

    public static User readResponse(@NonNull ResponseBody body) throws IOException {
        if (body.contentLength() > 65536 || body.source().request(65537))
            throw new IOException("Account profile response too large");
        return new User(new JsonReader(body.charStream()));
    }

    @NonNull
    @Override
    public String toString() {
        return username + '(' + id + ')';
    }

    public String getUsername() {
        return username;
    }

    public int getId() {
        return id;
    }

    @androidx.annotation.Nullable
    public String getAvatarUrl() {
        return avatarUrl;
    }

    public interface CreateUser {
        void onCreateUser(User user);
    }


}
