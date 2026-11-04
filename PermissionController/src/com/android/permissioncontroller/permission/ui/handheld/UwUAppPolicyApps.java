/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.permissioncontroller.permission.ui.handheld;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.android.permissioncontroller.R;
import com.android.permissioncontroller.permission.ui.Category;
import com.android.permissioncontroller.permission.utils.Utils;

import kotlin.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Loads Settings app-policy categories for the native permission app list. */
final class UwUAppPolicyApps implements DefaultLifecycleObserver {
    private static final Uri URI = Uri.parse("content://com.android.settings.uwu.apppolicies");
    private final PermissionAppsFragment mFragment;
    private final String mFeature;
    private final Consumer<Map<Category, List<Pair<String, UserHandle>>>> mResult;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mPending;
    private CancellationSignal mSignal;
    private int mGeneration;
    private boolean mActive;
    private final ContentObserver mObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh();
                }
            };
    private final BroadcastReceiver mPackages =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refresh();
                }
            };

    static String featureForGroup(String group) {
        if ("uwu_control_clipboard".equals(group)) return "clipboard";
        if ("uwu_control_jump".equals(group)) return "jump";
        if ("uwu_control_sensor".equals(group)) return "sensor";
        return null;
    }

    UwUAppPolicyApps(
            PermissionAppsFragment fragment,
            String feature,
            Consumer<Map<Category, List<Pair<String, UserHandle>>>> result) {
        mFragment = fragment;
        mFeature = feature;
        mResult = result;
        fragment.getLifecycle().addObserver(this);
    }

    void bindHeader() {
        int title;
        int description;
        int icon;
        switch (mFeature) {
            case "clipboard":
                title = R.string.uwu_control_clipboard;
                description = R.string.uwu_control_clipboard_description;
                icon = R.drawable.ic_uwu_clipboard;
                break;
            case "jump":
                title = R.string.uwu_control_jump;
                description = R.string.uwu_control_jump_description;
                icon = R.drawable.ic_uwu_jump;
                break;
            default:
                title = R.string.uwu_control_sensor;
                description = R.string.uwu_control_sensor_description;
                icon = R.drawable.ic_uwu_sensor;
        }
        Context context = mFragment.requireContext();
        mFragment.setHeader(
                Utils.applyTint(context, icon, android.R.attr.colorControlNormal),
                context.getText(title),
                null,
                null,
                true);
        mFragment.setSummary(context.getText(description), null);
        mFragment.requireActivity().setTitle(title);
    }

    void openApp(String packageName, UserHandle user) {
        Intent intent =
                new Intent("com.android.settings.uwu.action.MANAGE_APP_POLICY")
                        .setComponent(
                                new ComponentName(
                                        "com.android.settings",
                                        "com.android.settings.uwu.apppolicies.AppPolicyActivity"))
                        .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                        .putExtra("feature", mFeature);
        try {
            mFragment.requireActivity().startActivityAsUser(intent, user);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(
                            mFragment.requireContext(),
                            R.string.uwu_control_unavailable,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        mActive = true;
        Context context = mFragment.requireContext();
        context.getContentResolver().registerContentObserver(URI, true, mObserver);
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        context.registerReceiver(mPackages, filter, Context.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        mActive = false;
        mGeneration++;
        cancelPending();
        Context context = mFragment.requireContext();
        context.getContentResolver().unregisterContentObserver(mObserver);
        context.unregisterReceiver(mPackages);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        cancelPending();
        mExecutor.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void cancelPending() {
        if (mSignal != null) mSignal.cancel();
        if (mPending != null) mPending.cancel(true);
    }

    void refresh() {
        if (!mActive) return;
        cancelPending();
        int generation = ++mGeneration;
        CancellationSignal signal = new CancellationSignal();
        mSignal = signal;
        Context context = mFragment.requireContext();
        mPending =
                mExecutor.submit(
                        () -> {
                            Map<Category, List<Pair<String, UserHandle>>> result = null;
                            try {
                                if (!UwuAppPolicyPreferences.available(context)) {
                                    throw new IllegalStateException(
                                            "App policy provider unavailable");
                                }
                                result = load(context, signal);
                            } catch (RuntimeException e) {
                                if (!signal.isCanceled())
                                    Log.w("UwUAppPolicyApps", "Cannot load policies", e);
                            }
                            final Map<Category, List<Pair<String, UserHandle>>> apps = result;
                            mHandler.post(
                                    () -> {
                                        if (mActive
                                                && mGeneration == generation
                                                && mFragment.isAdded()) {
                                            mResult.accept(apps);
                                        }
                                    });
                        });
    }

    private Map<Category, List<Pair<String, UserHandle>>> load(
            Context context, CancellationSignal signal) {
        Map<Category, List<Pair<String, UserHandle>>> result = new LinkedHashMap<>();
        result.put(Category.ALLOWED, new ArrayList<>());
        result.put(Category.ALLOWED_FOREGROUND, new ArrayList<>());
        result.put(Category.ALLOWED_FOR_COMPATIBILITY, new ArrayList<>());
        result.put(Category.ASK, new ArrayList<>());
        result.put(Category.DENIED, new ArrayList<>());
        Uri uri = URI.buildUpon().appendPath("apps").appendPath(mFeature).build();
        try (Cursor cursor =
                context.getContentResolver().query(uri, null, null, null, null, signal)) {
            if (cursor == null) throw new IllegalStateException("Missing policy cursor");
            int nameColumn = cursor.getColumnIndexOrThrow("package_name");
            int categoryColumn = cursor.getColumnIndexOrThrow("category");
            while (cursor.moveToNext()) {
                signal.throwIfCanceled();
                String name = cursor.getString(nameColumn);
                if (name == null || name.isEmpty()) {
                    throw new IllegalStateException("Missing policy package");
                }
                Category category;
                switch (cursor.getString(categoryColumn)) {
                    case "allowed":
                        category = Category.ALLOWED;
                        break;
                    case "ask":
                        category = Category.ASK;
                        break;
                    case "denied":
                        category = Category.DENIED;
                        break;
                    default:
                        throw new IllegalStateException("Invalid policy category");
                }
                result.get(category).add(new Pair<>(name, Process.myUserHandle()));
            }
        }
        return result;
    }
}
