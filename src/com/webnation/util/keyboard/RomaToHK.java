package com.webnation.util.keyboard;

import com.assistek.util.keyboard.KanjiLists.HIRAGANA_R;
import com.assistek.util.keyboard.KanjiLists.KATAKANA_R;

//import com.assistek.util.keyboard.LlianeGlobal;

public class RomaToHK {
	public static int font_size;

	public static CharSequence roma2hk(CharSequence input, int keycodeLanguage) {
		CharSequence output = "";
		font_size = 20;
		String romajis = input.toString().toLowerCase();
		String romaji;
		int found;

		for (int i = romajis.length(); romajis.length() != 0; i--) {
			found = 0;
			romaji = romajis.substring(0, i);
			if (romaji.equals(" ")) {
				output = output + " ";
				romajis = romajis.substring(1);
				i = romajis.length() + 1;
				continue;
			}
			if (keycodeLanguage == Keyboard.KEYCODE_JA_HIRAGANA) {
				for (int j = 0; j < HIRAGANA_R.kl.length; j++)
					if (romaji.equals(HIRAGANA_R.kl[j].romaji_.toLowerCase())) {
						output = output + HIRAGANA_R.kl[j].kanji_;
						romajis = romajis.substring(i);
						i = romajis.length() + 1;
						found = 1;
						continue;
					}
			} else {
				for (int j = 0; j < KATAKANA_R.kl.length; j++)
					if (romaji.equals(KATAKANA_R.kl[j].romaji_.toLowerCase())) {
						output = output + KATAKANA_R.kl[j].kanji_;
						romajis = romajis.substring(i);
						i = romajis.length() + 1;
						found = 1;
						continue;
					}
			}
			if ((i == 2)
					&& (found == 0)
					&& (romajis.toCharArray()[0] == romajis.toCharArray()[1])
					&& ((romajis.toCharArray()[0] == 'k') || (romajis.toCharArray()[0] == 's') || (romajis.toCharArray()[0] == 't')
							|| (romajis.toCharArray()[0] == 'y') || (romajis.toCharArray()[0] == 'p') || (romajis.toCharArray()[0] == 'h') || (romajis
							.toCharArray()[0] == 'j'))) {
				if (keycodeLanguage == Keyboard.KEYCODE_JA_HIRAGANA)
					output = output + "っ";
				else
					output = output + "ッ";
				romajis = romajis.substring(1);
				i = romajis.length() + 1;
				continue;
			} else if ((i == 1) && (found == 0)) {
				output = output + romaji;
				romajis = romajis.substring(1);
				i = romajis.length() + 1;
				continue;
			}
		}
		return output;
	}
}
