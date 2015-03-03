package com.webnation.util.keyboard;

import android.content.Context;

/**
 * Extends WordDictionary to provide zhuyin word-suggestions.
 */
public class ZhuyinDictionary extends WordDictionary {

	private static final int APPROX_DICTIONARY_SIZE = 65536;
	private static final int TONES_COUNT = ZhuyinTable.getTonesCount();

	public ZhuyinDictionary(Context context) {
		super(context, R.raw.dict_zhuyin, APPROX_DICTIONARY_SIZE);
	}

	@Override
	public String getWords(CharSequence input) {
		// Look up the syllables index; return empty string for invalid syllables.
		String[] pair = ZhuyinTable.stripTones(input.toString());
		int syllablesIndex = (pair != null) ? ZhuyinTable.getSyllablesIndex(pair[0]) : -1;
		if (syllablesIndex < 0) {
			return input.toString();
		}

		// [22-initials * 39-finals] syllables array; each syllables entry points to
		// a char[] containing words for that syllables.
		char[][] dictionary = dictionary();
		char[] data = (dictionary != null) ? dictionary[syllablesIndex] : null;
		if (data == null) {
			return input.toString();
		}

		// Counts of words for each tone are stored in the array beginning.
		int tone = ZhuyinTable.getTones(pair[1].charAt(0));
		int length = (int)data[tone];
		if (length == 0) {
			return input.toString();
		}

		int start = TONES_COUNT;
		for (int i = 0; i < tone; i++) {
			start += (int)data[i];
		}

		String returnableTemp = String.copyValueOf(data, start, length);
		String returnable = "";

		for (int i = 0; i < returnableTemp.length(); i++) {
			returnable += returnableTemp.charAt(i) + "|";
		}

		if (returnable.length() > 1) {
			returnable = returnable.substring(0, returnable.length() - 1);
		}

		if (returnable.equalsIgnoreCase("")) {
			returnable = input.toString();
		}

		return returnable;
	}
}
