/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 K's Maze <kafkasmaze@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ksmaze.android.preference;

import android.content.Context;
import android.content.DialogInterface;
import android.util.AttributeSet;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;

/**
 * Multi-select list stored as a single string (see {@link #SEPARATOR}).
 * Uses a custom dialog because AndroidX {@link ListPreference} is single-choice only.
 */
public class ListPreferenceMultiSelect extends ListPreference {

	private static final String SEPARATOR = " , ";

	private boolean[] mClickedDialogEntryIndices;

	public ListPreferenceMultiSelect(Context context, AttributeSet attrs) {
		this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle, 0);
	}

	public ListPreferenceMultiSelect(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		mClickedDialogEntryIndices = new boolean[Math.max(0, getEntries().length)];
	}

	@Override
	public void setEntries(CharSequence[] entries) {
		super.setEntries(entries);
		mClickedDialogEntryIndices = new boolean[entries.length];
	}

	@Override
	public void onClick() {
		CharSequence[] entries = getEntries();
		CharSequence[] entryValues = getEntryValues();
		if (entries == null || entryValues == null || entries.length != entryValues.length) {
			return;
		}
		if (mClickedDialogEntryIndices.length != entries.length) {
			mClickedDialogEntryIndices = new boolean[entries.length];
		}
		restoreCheckedEntries();
		new AlertDialog.Builder(getContext())
				.setTitle(getDialogTitle())
				.setMultiChoiceItems(entries, mClickedDialogEntryIndices,
						new DialogInterface.OnMultiChoiceClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which, boolean isChecked) {
								mClickedDialogEntryIndices[which] = isChecked;
							}
						})
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						StringBuilder value = new StringBuilder();
						for (int i = 0; i < entryValues.length; i++) {
							if (mClickedDialogEntryIndices[i]) {
								value.append(entryValues[i]).append(SEPARATOR);
							}
						}
						String val = value.toString();
						if (val.length() > 0) {
							val = val.substring(0, val.length() - SEPARATOR.length());
						}
						if (callChangeListener(val)) {
							setValue(val);
						}
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	public static String[] parseStoredValue(CharSequence val) {
		if (val == null) {
			return null;
		}
		if ("".equals(val)) {
			return null;
		}
		return ((String) val).split(SEPARATOR);
	}

	private void restoreCheckedEntries() {
		CharSequence[] entryValues = getEntryValues();
		if (entryValues == null) {
			return;
		}
		java.util.Arrays.fill(mClickedDialogEntryIndices, false);
		String[] vals = parseStoredValue(getValue());
		if (vals == null) {
			return;
		}
		for (String val1 : vals) {
			String val = val1.trim();
			for (int i = 0; i < entryValues.length; i++) {
				if (entryValues[i].equals(val)) {
					mClickedDialogEntryIndices[i] = true;
					break;
				}
			}
		}
	}
}
