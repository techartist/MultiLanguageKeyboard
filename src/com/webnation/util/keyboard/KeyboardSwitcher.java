package com.webnation.util.keyboard;

import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.view.InflateException;

public class KeyboardSwitcher implements SharedPreferences.OnSharedPreferenceChangeListener {

	public static final int MODE_NONE = 0;
	public static final int MODE_TEXT = 1;
	public static final int MODE_SYMBOLS = 2;
	public static final int MODE_PHONE = 3;
	public static final int MODE_URL = 4;
	public static final int MODE_EMAIL = 5;
	public static final int MODE_IM = 6;
	public static final int MODE_WEB = 7;

	// Main keyboard layouts without the settings key
	public static final int KEYBOARDMODE_NORMAL = R.id.mode_normal;

	// Symbols keyboard layout without the settings key
	public static final int KEYBOARDMODE_SYMBOLS = R.id.mode_symbols;

	public static final String DEFAULT_LAYOUT_ID = "0";
	public static final String PREF_KEYBOARD_LAYOUT = "pref_keyboard_layout";
	private static final int[] THEMES = new int[] { R.layout.input_ics, R.layout.input_gingerbread, R.layout.input_stone_bold, R.layout.input_trans_neon, };

	// Tables which contains resource ids for each character theme color
	private static final int KBD_NUMPAD = R.xml.kbd_numpad;
	private static final int KBD_SYMBOLS = R.xml.kbd_symbols;
	private static final int KBD_QWERTY = R.xml.kbd_qwerty_english;

	private LatinKeyboardView mInputView;
	private static final int[] ALPHABET_MODES = { KEYBOARDMODE_NORMAL };

	private LatinIME mInputMethodService;

	private KeyboardId mSymbolsId;
	private KeyboardId mSymbolsShiftedId;

	private KeyboardId mCurrentId;
	private final HashMap<KeyboardId, SoftReference<LatinKeyboard>> mKeyboards = new HashMap<KeyboardId, SoftReference<LatinKeyboard>>();

	private int mMode = MODE_NONE;
	/** One of the MODE_XXX values */
	private int mImeOptions;
	private boolean mIsSymbols;
	private int mFullMode;
	/**
	 * mIsAutoCompletionActive indicates that auto completed word will be input instead of what user actually typed.
	 */
	private boolean mIsAutoCompletionActive;
	private boolean mPreferSymbols;

	private static final int AUTO_MODE_SWITCH_STATE_ALPHA = 0;
	private static final int AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN = 1;
	private static final int AUTO_MODE_SWITCH_STATE_SYMBOL = 2;
	// The following states are used only on the distinct multi-touch panel
	// devices.
	private static final int AUTO_MODE_SWITCH_STATE_MOMENTARY = 3;
	private static final int AUTO_MODE_SWITCH_STATE_CHORDING = 4;
	private int mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA;

	private int mLastDisplayWidth;
	private LanguageSwitcher mLanguageSwitcher;

	private int mLayoutId;

	private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

	public static KeyboardSwitcher getInstance() {
		return sInstance;
	}

	private KeyboardSwitcher() {
		// Intentional empty constructor for singleton.
	}

	public static void init(LatinIME ims) {
		sInstance.mInputMethodService = ims;

		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ims);
		sInstance.mLayoutId = Integer.valueOf(prefs.getString(PREF_KEYBOARD_LAYOUT, DEFAULT_LAYOUT_ID));

		prefs.registerOnSharedPreferenceChangeListener(sInstance);

		sInstance.mSymbolsId = sInstance.makeSymbolsId();
		sInstance.mSymbolsShiftedId = sInstance.makeSymbolsShiftedId();
	}

	/**
	 * Sets the input locale, when there are multiple locales for input. If no locale switching is required, then the locale should be set to null.
	 * 
	 * @param locale the current input locale, or null for default locale with no locale button.
	 */
	public void setLanguageSwitcher(LanguageSwitcher languageSwitcher) {
		mLanguageSwitcher = languageSwitcher;
		languageSwitcher.getInputLocale(); // for side effect
	}

	private KeyboardId makeSymbolsId() {
		return new KeyboardId(LatinIME.mFunctionKeyboard, KEYBOARDMODE_SYMBOLS, true);
	}

	private KeyboardId makeSymbolsShiftedId() {
		if (mFullMode > 0)
			return null;
		return new KeyboardId(KBD_SYMBOLS, KEYBOARDMODE_SYMBOLS, false);
	}

	public void makeKeyboards(boolean forceCreate) {
		mFullMode = LatinIME.sKeyboardSettings.keyboardMode;
		mSymbolsId = makeSymbolsId();
		mSymbolsShiftedId = makeSymbolsShiftedId();

		if (forceCreate)
			mKeyboards.clear();
		// Configuration change is coming after the keyboard gets recreated. So
		// don't rely on that.
		// If keyboards have already been made, check if we have a screen width
		// change and
		// create the keyboard layouts again at the correct orientation
		int displayWidth = mInputMethodService.getMaxWidth();
		if (displayWidth == mLastDisplayWidth)
			return;
		mLastDisplayWidth = displayWidth;
		if (!forceCreate)
			mKeyboards.clear();
	}

	/**
	 * Represents the parameters necessary to construct a new LatinKeyboard, which also serve as a unique identifier for each keyboard type.
	 */
	private static class KeyboardId {
		public final int mXml;
		public final int mKeyboardMode;
		/** A KEYBOARDMODE_XXX value */
		public final boolean mEnableShiftLock;
		public final float mKeyboardHeightPercent;
		public final boolean mUsingExtension;

		private final int mHashCode;

		public KeyboardId(int xml, int mode, boolean enableShiftLock) {
			this.mXml = xml;
			this.mKeyboardMode = mode;
			this.mEnableShiftLock = enableShiftLock;
			this.mKeyboardHeightPercent = LatinIME.sKeyboardSettings.keyboardHeightPercent;
			this.mUsingExtension = LatinIME.sKeyboardSettings.useExtension;
			this.mHashCode = Arrays.hashCode(new Object[] { xml, mode, enableShiftLock });
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof KeyboardId && equals((KeyboardId)other);
		}

		private boolean equals(KeyboardId other) {
			return other != null && other.mXml == this.mXml && other.mKeyboardMode == this.mKeyboardMode && other.mUsingExtension == this.mUsingExtension
					&& other.mEnableShiftLock == this.mEnableShiftLock;
		}

		@Override
		public int hashCode() {
			return mHashCode;
		}
	}

	public void setKeyboardMode(int mode, int imeOptions) {
		mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA;
		mPreferSymbols = mode == MODE_SYMBOLS;
		if (mode == MODE_SYMBOLS) {
			mode = MODE_TEXT;
		}
		try {
			setKeyboardMode(mode, imeOptions, mPreferSymbols);
		} catch (RuntimeException e) {
			LatinImeLogger.logOnException(mode + "," + imeOptions + "," + mPreferSymbols, e);
		}
	}

	private void setKeyboardMode(int mode, int imeOptions, boolean isSymbols) {
		if (mInputView == null)
			return;
		mMode = mode;
		mImeOptions = imeOptions;
		mIsSymbols = isSymbols;

		mInputView.setPreviewEnabled(mInputMethodService.getPopupOn());

		KeyboardId id = getKeyboardId(mode, imeOptions, isSymbols);
		LatinKeyboard keyboard = null;
		keyboard = getKeyboard(id);

		if (mode == MODE_PHONE) {
			mInputView.setPhoneKeyboard(keyboard);
		}

		mCurrentId = id;
		mInputView.setKeyboard(keyboard);
		keyboard.setShiftState(Keyboard.SHIFT_OFF);
		keyboard.setImeOptions(mInputMethodService.getResources(), mMode, imeOptions);
		keyboard.updateSymbolIcons(mIsAutoCompletionActive);
	}

	private LatinKeyboard getKeyboard(KeyboardId id) {
		SoftReference<LatinKeyboard> ref = mKeyboards.get(id);
		LatinKeyboard keyboard = (ref == null) ? null : ref.get();
		if (keyboard == null) {
			Resources orig = mInputMethodService.getResources();
			Configuration conf = orig.getConfiguration();
			Locale saveLocale = conf.locale;
			conf.locale = LatinIME.sKeyboardSettings.inputLocale;
			orig.updateConfiguration(conf, null);
			keyboard = new LatinKeyboard(mInputMethodService, id.mXml, id.mKeyboardMode, id.mKeyboardHeightPercent);
			keyboard.setLanguageSwitcher(mLanguageSwitcher, mIsAutoCompletionActive);

			if (id.mEnableShiftLock) {
				keyboard.enableShiftLock();
			}
			mKeyboards.put(id, new SoftReference<LatinKeyboard>(keyboard));

			conf.locale = saveLocale;
			orig.updateConfiguration(conf, null);
		}
		return keyboard;
	}

	public boolean isFullMode() {
		return mFullMode > 0;
	}

	private KeyboardId getKeyboardId(int mode, int imeOptions, boolean isSymbols) {
		LatinIME.composingSpaceAutoCommit = false;

		if (LatinIME.mUseThisKeyboard == 0) {
			LatinIME.mUseThisKeyboard = R.xml.kbd_qwerty_english;// ADAM default
		}

		if (LatinIME.mFunctionKeyboard == 0) {
			LatinIME.mFunctionKeyboard = -1;
		}

		if (LatinIME.mExtraKeyboard1 == 0) {
			LatinIME.mExtraKeyboard1 = -1;
		}

		if (LatinIME.mExtraKeyboard2 == 0) {
			LatinIME.mExtraKeyboard2 = -1;
		}

		// Identify the keyboards which use Composing
		if (!LatinIME.listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_en)) {
			LatinIME.listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_en);
		}

		if (!LatinIME.listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_cangjie)) {
			LatinIME.listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_cangjie);
		}

		if (!LatinIME.listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_zhuyin)) {
			LatinIME.listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_zhuyin);
		}

		if (!LatinIME.listComposingKeyboards.contains(R.xml.kbd_qwerty_japanese_en)) {
			LatinIME.listComposingKeyboards.add(R.xml.kbd_qwerty_japanese_en);
		}

		if (!LatinIME.listComposingKeyboards.contains(R.xml.kbd_qwerty_korean)) {
			LatinIME.listComposingKeyboards.add(R.xml.kbd_qwerty_korean);
		}

		switch (mode) {
			case MODE_TEXT:
			case MODE_URL:
			case MODE_EMAIL:
			case MODE_IM:
			case MODE_WEB:
				return new KeyboardId(LatinIME.mUseThisKeyboard, KEYBOARDMODE_NORMAL, true);
		}

		int keyboardRowsResId = KBD_QWERTY;
		if (isSymbols) {
			return new KeyboardId(KBD_SYMBOLS, KEYBOARDMODE_SYMBOLS, false);
		}
		switch (mode) {
			case MODE_NONE:
				LatinImeLogger.logOnWarning("getKeyboardId:" + mode + "," + imeOptions + "," + isSymbols);
				/* fall through */
			case MODE_URL:
			case MODE_EMAIL:
			case MODE_IM:
			case MODE_WEB:
			case MODE_TEXT:
				return new KeyboardId(keyboardRowsResId, KEYBOARDMODE_NORMAL, true);
			case MODE_SYMBOLS:
				return new KeyboardId(KBD_SYMBOLS, KEYBOARDMODE_SYMBOLS, false);
			case MODE_PHONE:
				return new KeyboardId(KBD_NUMPAD, 0, false);
		}
		return null;
	}

	public int getKeyboardMode() {
		return mMode;
	}

	public boolean isAlphabetMode() {
		if (mCurrentId == null) {
			return false;
		}
		int currentMode = mCurrentId.mKeyboardMode;
		if (mFullMode > 0 && currentMode == KEYBOARDMODE_NORMAL)
			return true;
		for (Integer mode : ALPHABET_MODES) {
			if (currentMode == mode) {
				return true;
			}
		}
		return false;
	}

	public void setShiftState(int shiftState) {
		if (mInputView != null) {
			mInputView.setShiftState(shiftState);
		}
	}

	public void setFn(boolean useFn) {
		if (mInputView == null)
			return;
		int oldShiftState = mInputView.getShiftState();
		if (useFn) {
			LatinKeyboard kbd = getKeyboard(mSymbolsId);
			kbd.enableShiftLock();
			mCurrentId = mSymbolsId;
			mInputView.setKeyboard(kbd);
			mInputView.setShiftState(oldShiftState);
		} else {
			// Return to default keyboard state
			setKeyboardMode(mMode, mImeOptions, false);
			mInputView.setShiftState(oldShiftState);
		}
	}

	public void switchKeyboard(int kbdXML) {
		if (mCurrentId.mXml != kbdXML) {
			KeyboardId newID = new KeyboardId(kbdXML, KEYBOARDMODE_NORMAL, true);

			LatinKeyboard kbd = getKeyboard(newID);
			kbd.enableShiftLock();
			mCurrentId = newID;
			mInputView.setKeyboard(kbd);
			mInputView.setShiftState(mInputView.getShiftState());
		}
	}

	public void setCtrlIndicator(boolean active) {
		if (mInputView == null)
			return;
		mInputView.setCtrlIndicator(active);
	}

	public void setAltIndicator(boolean active) {
		if (mInputView == null)
			return;
		mInputView.setAltIndicator(active);
	}

	public void toggleShift() {
		if (isAlphabetMode())
			return;
		if (mFullMode > 0) {
			boolean shifted = mInputView.isShiftAll();
			mInputView.setShiftState(shifted ? Keyboard.SHIFT_OFF : Keyboard.SHIFT_ON);
			return;
		}
		if (mCurrentId.equals(mSymbolsId) || !mCurrentId.equals(mSymbolsShiftedId)) {
			LatinKeyboard symbolsShiftedKeyboard = getKeyboard(mSymbolsShiftedId);
			mCurrentId = mSymbolsShiftedId;
			mInputView.setKeyboard(symbolsShiftedKeyboard);
			// Symbol shifted keyboard has a ALT_SYM key that has a caps lock style indicator.
			// To enable the indicator, we need to set the shift state appropriately.
			symbolsShiftedKeyboard.enableShiftLock();
			symbolsShiftedKeyboard.setShiftState(Keyboard.SHIFT_LOCKED);
			symbolsShiftedKeyboard.setImeOptions(mInputMethodService.getResources(), mMode, mImeOptions);
		} else {
			LatinKeyboard symbolsKeyboard = getKeyboard(mSymbolsId);
			mCurrentId = mSymbolsId;
			mInputView.setKeyboard(symbolsKeyboard);
			symbolsKeyboard.enableShiftLock();
			symbolsKeyboard.setShiftState(Keyboard.SHIFT_OFF);
			symbolsKeyboard.setImeOptions(mInputMethodService.getResources(), mMode, mImeOptions);
		}
	}

	public void onCancelInput() {
		// Snap back to the previous keyboard mode if the user cancels sliding
		// input.
		if (mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_MOMENTARY && getPointerCount() == 1)
			mInputMethodService.changeKeyboardMode();
	}

	public void toggleSymbols() {
		if (mMode != MODE_TEXT) {
			mMode = MODE_TEXT;
		} else if (mMode != MODE_SYMBOLS) {
			mMode = MODE_SYMBOLS;
		}

		setKeyboardMode(mMode, mImeOptions, !mIsSymbols);
		if (mIsSymbols && !mPreferSymbols) {
			mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN;
		} else {
			mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA;
		}
	}

	public boolean hasDistinctMultitouch() {
		return mInputView != null && mInputView.hasDistinctMultitouch();
	}

	public void setAutoModeSwitchStateMomentary() {
		mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_MOMENTARY;
	}

	public boolean isInMomentaryAutoModeSwitchState() {
		return mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_MOMENTARY;
	}

	public boolean isInChordingAutoModeSwitchState() {
		return mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_CHORDING;
	}

	public boolean isVibrateAndSoundFeedbackRequired() {
		return mInputView != null && !mInputView.isInSlidingKeyInput();
	}

	private int getPointerCount() {
		return mInputView == null ? 0 : mInputView.getPointerCount();
	}

	/**
	 * Updates state machine to figure out when to automatically snap back to the previous mode.
	 */
	public void onKey(int key) {
		// Switch back to alpha mode if user types one or more non-space/enter
		// characters
		// followed by a space/enter
		switch (mAutoModeSwitchState) {
			case AUTO_MODE_SWITCH_STATE_MOMENTARY:
				// Only distinct multi touch devices can be in this state.
				// On non-distinct multi touch devices, mode change key is handled
				// by {@link onKey},
				// not by {@link onPress} and {@link onRelease}. So, on such
				// devices,
				// {@link mAutoModeSwitchState} starts from {@link
				// AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN},
				// or {@link AUTO_MODE_SWITCH_STATE_ALPHA}, not from
				// {@link AUTO_MODE_SWITCH_STATE_MOMENTARY}.
				if (key == LatinKeyboard.KEYCODE_MODE_CHANGE) {
					// Detected only the mode change key has been pressed, and then
					// released.
					if (mIsSymbols) {
						mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN;
					} else {
						mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA;
					}
				} else if (getPointerCount() == 1) {
					// Snap back to the previous keyboard mode if the user pressed
					// the mode change key
					// and slid to other key, then released the finger.
					// If the user cancels the sliding input, snapping back to the
					// previous keyboard
					// mode is handled by {@link #onCancelInput}.
					mInputMethodService.changeKeyboardMode();
				} else {
					// Chording input is being started. The keyboard mode will be
					// snapped back to the
					// previous mode in {@link onReleaseSymbol} when the mode change
					// key is released.
					mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_CHORDING;
				}
				break;
			case AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN:
				if (key != LatinIME.ASCII_SPACE && key != LatinIME.ASCII_ENTER && key >= 0) {
					mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_SYMBOL;
				}
				break;
			case AUTO_MODE_SWITCH_STATE_SYMBOL:
				// Snap back to alpha keyboard mode if user types one or more
				// non-space/enter
				// characters followed by a space/enter.
				if (key == LatinIME.ASCII_ENTER || key == LatinIME.ASCII_SPACE) {
					mInputMethodService.changeKeyboardMode();
				}
				break;
		}
	}

	public LatinKeyboardView getInputView() {
		return mInputView;
	}

	public void recreateInputView() {
		changeLatinKeyboardView(mLayoutId, true);
	}

	private void changeLatinKeyboardView(int newLayout, boolean forceReset) {
		if (mLayoutId != newLayout || mInputView == null || forceReset) {
			if (mInputView != null) {
				mInputView.closing();
			}
			if (THEMES.length <= newLayout) {
				newLayout = Integer.valueOf(DEFAULT_LAYOUT_ID);
			}

			LatinIMEUtil.GCUtils.getInstance().reset();
			boolean tryGC = true;
			for (int i = 0; i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
				try {
					mInputView = (LatinKeyboardView)mInputMethodService.getLayoutInflater().inflate(THEMES[newLayout], null);
					tryGC = false;
				} catch (OutOfMemoryError e) {
					tryGC = LatinIMEUtil.GCUtils.getInstance().tryGCOrWait(mLayoutId + "," + newLayout, e);
				} catch (InflateException e) {
					tryGC = LatinIMEUtil.GCUtils.getInstance().tryGCOrWait(mLayoutId + "," + newLayout, e);
				}
			}
			mInputView.setExtensionLayoutResId(THEMES[newLayout]);
			mInputView.setOnKeyboardActionListener(mInputMethodService);
			mInputView.setPadding(0, 0, 0, 0);
			mLayoutId = newLayout;
		}
		mInputMethodService.mHandler.post(new Runnable() {
			public void run() {
				if (mInputView != null) {
					mInputMethodService.setInputView(mInputView);
				}
				mInputMethodService.updateInputViewShown();
			}
		});
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if (PREF_KEYBOARD_LAYOUT.equals(key)) {
			changeLatinKeyboardView(Integer.valueOf(sharedPreferences.getString(key, DEFAULT_LAYOUT_ID)), true);
		}
	}

	public void onAutoCompletionStateChanged(boolean isAutoCompletion) {
		if (isAutoCompletion != mIsAutoCompletionActive) {
			LatinKeyboardView keyboardView = getInputView();
			mIsAutoCompletionActive = isAutoCompletion;
			keyboardView.invalidateKey(((LatinKeyboard)keyboardView.getKeyboard()).onAutoCompletionStateChanged(isAutoCompletion));
		}
	}

	public int getCurrentKeyboardXML() {
		if (mCurrentId == null) {
			return -1;
		}

		return mCurrentId.mXml;
	}
}
