/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.permissioncontroller.permission.ui.handheld;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.permissioncontroller.R;
import com.android.permissioncontroller.permission.utils.Utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Adds Settings policy editors to the existing permission categories. */
final class UwuAppPolicyPreferences implements DefaultLifecycleObserver {
    private static final String PACKAGE = "com.android.settings";
    private static final Uri URI = Uri.parse("content://com.android.settings.uwu.apppolicies");
    private static final String[] FEATURES = {"clipboard", "jump", "sensor"};
    private final Fragment mFragment;
    private final PreferenceScreen mScreen;
    private final Runnable mSortRows;
    private Bundle mSummary;
    private final Context mUserContext;
    private final String mPackageName;
    private final UserHandle mUser;
    private final PermissionPreference[] mRows = new PermissionPreference[3];
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Future<?> mPending;
    private int mGeneration;
    private boolean mActive;
    private final ContentObserver mObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    refresh();
                }
            };

    static boolean available(Context context) {
        ProviderInfo info =
                context.getPackageManager()
                        .resolveContentProvider(
                                URI.getAuthority(), PackageManager.MATCH_SYSTEM_ONLY);
        return info != null
                && PACKAGE.equals(info.packageName)
                && "android.permission.MANAGE_APP_OPS_MODES".equals(info.readPermission)
                && context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
                        == PackageManager.SIGNATURE_MATCH;
    }

    UwuAppPolicyPreferences(
            Fragment fragment,
            PreferenceScreen screen,
            String packageName,
            UserHandle user,
            Runnable sortRows) {
        mFragment = fragment;
        mScreen = screen;
        mSortRows = sortRows;
        mUserContext = Utils.getUserContext(fragment.requireContext(), user);
        mPackageName = packageName;
        mUser = user;
        final int[] titles = {
            R.string.uwu_control_clipboard, R.string.uwu_control_jump, R.string.uwu_control_sensor
        };
        final int[] icons = {
            R.drawable.ic_uwu_clipboard, R.drawable.ic_uwu_jump, R.drawable.ic_uwu_sensor
        };
        for (int i = 0; i < mRows.length; i++) {
            PermissionPreference row = new PermissionPreference(fragment.requireContext());
            row.setKey("uwu_control_" + FEATURES[i]);
            row.setTitle(titles[i]);
            row.setIcon(icons[i]);
            row.setPersistent(false);
            row.setEnabled(false);
            final String feature = FEATURES[i];
            row.setOnPreferenceClickListener(
                    preference -> {
                        Intent intent =
                                new Intent("com.android.settings.uwu.action.MANAGE_APP_POLICY")
                                        .setComponent(
                                                new ComponentName(
                                                        PACKAGE,
                                                        PACKAGE
                                                                + ".uwu.apppolicies.AppPolicyActivity"))
                                        .putExtra(Intent.EXTRA_PACKAGE_NAME, mPackageName)
                                        .putExtra("feature", feature);
                        try {
                            mFragment.requireActivity().startActivityAsUser(intent, mUser);
                        } catch (android.content.ActivityNotFoundException | SecurityException e) {
                            row.setSummary(R.string.uwu_control_unavailable);
                            row.setEnabled(false);
                        }
                        return true;
                    });
            mRows[i] = row;
        }
        attachRows();
        fragment.getLifecycle().addObserver(this);
    }

    void attachRows() {
        PreferenceCategory allowed = mScreen.findPreference("allowed");
        PreferenceCategory denied = mScreen.findPreference("denied");
        PreferenceCategory ask = mScreen.findPreference("ask");
        if (allowed == null || denied == null || ask == null) return;
        for (int i = 0; i < mRows.length; i++) {
            String feature = FEATURES[i];
            String value = mSummary == null ? null : mSummary.getString(feature);
            String category = mSummary == null ? null : mSummary.getString(feature + "_category");
            boolean loaded =
                    value != null
                            && ("allowed".equals(category)
                                    || "ask".equals(category)
                                    || "denied".equals(category));
            PreferenceCategory target =
                    loaded && "allowed".equals(category)
                            ? allowed
                            : loaded && "ask".equals(category) ? ask : denied;
            PermissionPreference row = mRows[i];
            if (row.getParent() != target) {
                if (row.getParent() != null) row.getParent().removePreference(row);
                target.addPreference(row);
            }
            row.setSummary(
                    !loaded
                            ? mFragment.getString(R.string.uwu_control_unavailable)
                            : "sensor".equals(feature) ? value : null);
            row.setEnabled(loaded && mSummary.getBoolean(feature + "_editable", true));
        }
        updateEmptyPlaceholder(allowed, R.string.no_permissions_allowed);
        updateEmptyPlaceholder(denied, R.string.no_permissions_denied);
        ask.setVisible(ask.getPreferenceCount() > 0);
        mSortRows.run();
    }

    private void updateEmptyPlaceholder(PreferenceCategory category, int titleRes) {
        String title = mFragment.getString(titleRes);
        Preference placeholder = category.findPreference(title);
        if (placeholder != null && category.getPreferenceCount() > 1) {
            category.removePreference(placeholder);
        } else if (category.getPreferenceCount() == 0) {
            PermissionPreference empty = new PermissionPreference(mFragment.requireContext());
            empty.setKey(title);
            empty.setTitle(title);
            empty.setSelectable(false);
            category.addPreference(empty);
        }
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        mActive = true;
        mUserContext.getContentResolver().registerContentObserver(URI, true, mObserver);
        refresh();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        mActive = false;
        mGeneration++;
        if (mPending != null) mPending.cancel(true);
        mUserContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        mExecutor.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        if (!mActive) return;
        int generation = ++mGeneration;
        if (mPending != null) mPending.cancel(true);
        mPending =
                mExecutor.submit(
                        () -> {
                            Bundle result = null;
                            try {
                                result =
                                        mUserContext
                                                .getContentResolver()
                                                .call(
                                                        URI,
                                                        "get_app_policy_summary",
                                                        mPackageName,
                                                        null);
                            } catch (RuntimeException ignored) {
                                // Keep failed reads distinct from an allowed policy.
                            }
                            final Bundle summary = result;
                            mHandler.post(
                                    () -> {
                                        if (!mActive
                                                || mGeneration != generation
                                                || !mFragment.isAdded()) return;
                                        mSummary = summary;
                                        attachRows();
                                    });
                        });
    }
}
