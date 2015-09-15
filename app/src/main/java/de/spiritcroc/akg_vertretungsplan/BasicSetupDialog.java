/*
 * Copyright (C) 2015 SpiritCroc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.spiritcroc.akg_vertretungsplan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;

import java.util.Arrays;

public class BasicSetupDialog extends DialogFragment {
    private SharedPreferences sharedPreferences;
    private int selection;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        final String[] planValues = getResources().getStringArray(R.array.pref_plan_value_array);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        selection = Arrays.asList(planValues).indexOf(sharedPreferences.getString("pref_plan", "1"));
        if (selection < 0) {
            selection = 0;
        }
        return builder.setTitle(R.string.dialog_select_plan)
                .setPositiveButton(R.string.dialog_agree, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        sharedPreferences.edit().putString("pref_plan", planValues[selection]).apply();
                    }
                })
                .setSingleChoiceItems(R.array.pref_plan_array, selection, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selection = which;
                    }
                }).create();
    }
    @Override
    public void onDismiss (DialogInterface dialog){
        super.onDismiss(dialog);
        Activity currentActivity = getActivity();
        if (currentActivity instanceof FormattedActivity)
            ((FormattedActivity) currentActivity).onCreateAfterDisclaimer();
    }
}