package com.maxwai.nclientv3.loginapi;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.settings.Global;
import com.maxwai.nclientv3.settings.Login;
import com.maxwai.nclientv3.settings.AuthStore;
import com.maxwai.nclientv3.settings.ApiAuthInterceptor;
import com.maxwai.nclientv3.utility.Utility;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class User {
    private final String username;
    private final int id;

    private User(JsonReader jr) throws IOException {
        int id = -1;
        String username = null;
        jr.beginObject();
        while (jr.peek() != JsonToken.END_OBJECT) {
            switch (jr.nextName()) {
                case "id":
                    id = jr.nextInt();
                    break;
                case "username":
                    username = jr.nextString();
                    break;
                default:
                    jr.skipValue();
                    break;
            }
        }
        jr.endObject();
        if (id == -1 || username == null) {
            throw new RuntimeException("No user information found");
        }
        this.username = username;
        this.id = id;
    }

    public static void createUser(@NonNull Context context, final CreateUser createUser) {
        String identity = AuthStore.activeIdentity(context);
        if (identity == null) return;
        Global.getClient(context)
            .newCall(new Request.Builder().url(Utility.getApiBaseUrl() + "user")
                .tag(ApiAuthInterceptor.ExpectedIdentity.class, new ApiAuthInterceptor.ExpectedIdentity(identity)).build())
            .enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response received = response) {
                        if (!received.isSuccessful() || received.body() == null) return;
                        User user = new User(new JsonReader(received.body().charStream()));
                        if (Login.updateUserIfCurrent(context, identity, user) && createUser != null)
                            createUser.onCreateUser(user);
                    } catch (RuntimeException exception) {
                        throw new IOException("Invalid user response", exception);
                    }
                }
            });
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

    public interface CreateUser {
        void onCreateUser(User user);
    }


}
