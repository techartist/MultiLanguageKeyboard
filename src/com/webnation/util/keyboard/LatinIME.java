package com.webnation.util.keyboard;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParserException;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.text.ClipboardManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.assistek.util.keyboard.LatinIMEUtil.RingCharBuffer;
import com.assistek.util.keyboard.KanjiLists.KANJIML;
import com.assistek.util.keyboard.PinyinLists.PINYIN_ENML;
import com.assistek.util.keyboard.WubiLists.WUBI_ENML;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements ComposeSequencing, LatinKeyboardBaseView.OnKeyboardActionListener,
		CandidateView.CandidateViewListener, SharedPreferences.OnSharedPreferenceChangeListener {

	private static final String TAG = "PCKeyboardIME";
	private static final boolean PERF_DEBUG = false;
	static final boolean DEBUG = false;
	static final boolean TRACE = false;
	static Map<Integer, String> ESC_SEQUENCES;
	static Map<Integer, Integer> CTRL_SEQUENCES;

	private static final String PREF_VIBRATE_ON = "vibrate_on";
	static final String PREF_VIBRATE_LEN = "vibrate_len";
	private static final String PREF_SOUND_ON = "sound_on";
	private static final String PREF_POPUP_ON = "popup_on";
	private static final String PREF_AUTO_CAP = "auto_cap";
	private static final String PREF_SHOW_SUGGESTIONS = "show_suggestions";
	public static final String PREF_SELECTED_LANGUAGES = "selected_languages";
	public static final String PREF_INPUT_LANGUAGE = "input_language";
	static final String PREF_FULLSCREEN_OVERRIDE = "fullscreen_override";
	static final String PREF_FORCE_KEYBOARD_ON = "force_keyboard_on";
	static final String PREF_KEYBOARD_NOTIFICATION = "keyboard_notification";
	static final String PREF_CONNECTBOT_TAB_HACK = "connectbot_tab_hack";
	static final String PREF_FULL_KEYBOARD_IN_PORTRAIT = "full_keyboard_in_portrait";
	static final String PREF_SUGGESTIONS_IN_LANDSCAPE = "suggestions_in_landscape";
	static final String PREF_HEIGHT_PORTRAIT = "settings_height_portrait";
	static final String PREF_HEIGHT_LANDSCAPE = "settings_height_landscape";
	static final String PREF_HINT_MODE = "pref_hint_mode";
	static final String PREF_LONGPRESS_TIMEOUT = "pref_long_press_duration";
	static final String PREF_RENDER_MODE = "pref_render_mode";
	static final String PREF_SWIPE_UP = "pref_swipe_up";
	static final String PREF_SWIPE_DOWN = "pref_swipe_down";
	static final String PREF_SWIPE_LEFT = "pref_swipe_left";
	static final String PREF_SWIPE_RIGHT = "pref_swipe_right";
	static final String PREF_VOL_UP = "pref_vol_up";
	static final String PREF_VOL_DOWN = "pref_vol_down";

	private static final int MSG_UPDATE_SUGGESTIONS = 0;
	private static final int MSG_UPDATE_SHIFT_STATE = 2;
	private static final int MSG_UPDATE_OLD_SUGGESTIONS = 4;

	// How many continuous deletes at which to start deleting at a higher speed.
	private static final int DELETE_ACCELERATE_AT = 20;
	// Key events coming any faster than this are long-presses.
	private static final int QUICK_PRESS = 200;

	static final int ASCII_ENTER = '\n';
	static final int ASCII_SPACE = ' ';
	static final int ASCII_PERIOD = '.';

	private AlertDialog mOptionsDialog;

	/* package */KeyboardSwitcher mKeyboardSwitcher;

	private Resources mResources;

	private String mSystemLocale;
	private LanguageSwitcher mLanguageSwitcher;

	// private boolean doComposing = false;
	public static boolean composingSpaceAutoCommit = false;
	public static ArrayList<Integer> listComposingKeyboards = new ArrayList<Integer>();
	private StringBuilder mComposingOriginal = new StringBuilder();
	private StringBuilder mComposing = new StringBuilder();
	private WordComposer mWord = new WordComposer();
	private boolean mAutoSpace;
	private boolean mJustAddedAutoSpace;
	// move this state variable outside LatinIME
	private boolean mModCtrl;
	private boolean mModAlt;
	private boolean mModFn;
	// Saved shift state when leaving alphabet mode, or when applying multitouch shift
	private int mSavedShiftState;
	private boolean mVibrateOn;
	private int mVibrateLen;
	private boolean mSoundOn;
	private boolean mPopupOn;
	private boolean mAutoCapPref;
	private boolean mAutoCapActive;
	private boolean mDeadKeysActive;
	private boolean mShowSuggestions;
	private boolean mConnectbotTabHack;
	private boolean mFullscreenOverride;
	private boolean mForceKeyboardOn;
	private boolean mKeyboardNotification;
	private boolean mSuggestionsInLandscape;
	private boolean mSuggestionForceOn;
	private boolean mSuggestionForceOff;
	private String mSwipeUpAction;
	private String mSwipeDownAction;
	private String mSwipeLeftAction;
	private String mSwipeRightAction;
	private String mVolUpAction;
	private String mVolDownAction;

	public static final GlobalKeyboardSettings sKeyboardSettings = new GlobalKeyboardSettings();

	private int mHeightPortrait;
	private int mHeightLandscape;
	private int mNumKeyboardModes = 3;
	private int mKeyboardModeOverridePortrait;
	private int mKeyboardModeOverrideLandscape;
	private int mOrientation;
	private List<CharSequence> mSuggestPuncList;
	// Keep track of the last selection range to decide if we need to show word
	// alternatives
	private int mLastSelectionStart;
	private int mLastSelectionEnd;

	// Indicates whether the suggestion strip is to be on in landscape
	private boolean mJustAccepted;
	private int mDeleteCount;
	private long mLastKeyTime;

	// Modifier keys state
	private ModifierKeyState mShiftKeyState = new ModifierKeyState();
	private ModifierKeyState mSymbolKeyState = new ModifierKeyState();
	private ModifierKeyState mCtrlKeyState = new ModifierKeyState();
	private ModifierKeyState mAltKeyState = new ModifierKeyState();
	private ModifierKeyState mFnKeyState = new ModifierKeyState();

	// Compose sequence handling
	private boolean mComposeMode = false;
	private ComposeBase mComposeBuffer = new ComposeSequence(this);
	private ComposeBase mDeadAccentBuffer = new DeadAccentSequence(this);

	// private Tutorial mTutorial;

	private AudioManager mAudioManager;
	// Align sound effect volume on music volume
	private final float FX_VOLUME = -1.0f;
	private final float FX_VOLUME_RANGE_DB = 72.0f;
	private boolean mSilentMode;

	/* package */String mWordSeparators;
	private String mSentenceSeparators;
	private boolean mConfigurationChanging;

	// Keeps track of most recently inserted text (multi-character key) for
	// reverting
	private CharSequence mEnteredText;
	private boolean mRefreshKeyboardRequired;

	private NotificationReceiver mNotificationReceiver;

	/* package */Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_UPDATE_SHIFT_STATE:
					updateShiftKeyState(getCurrentInputEditorInfo());
					break;
			}
		}
	};

	// ADAM
	public static int mUseThisKeyboard;
	public static int mFunctionKeyboard;
	public static int mExtraKeyboard1;
	public static int mExtraKeyboard2;

	// Chinese
	private CandidatesContainer candidatesContainer;
	private Editor editor;
	private PhraseDictionary phraseDictionary;
	private WordDictionary wordDictionary;

	// Korean
	// private int mHangulShiftState = 0;
	private int mHangulState = 0;
	private static int mHangulKeyStack[] = { 0, 0, 0, 0, 0, 0 }; // 초,초,중,중,종,종
	private static int mHangulJamoStack[] = { 0, 0, 0 };
	final static int H_STATE_0 = 0;
	final static int H_STATE_1 = 1;
	final static int H_STATE_2 = 2;
	final static int H_STATE_3 = 3;
	final static int H_STATE_4 = 4;
	final static int H_STATE_5 = 5;
	final static int H_STATE_6 = 6;
	final static int[] e2h_map = { 16, 47, 25, 22, 6, 8, 29, 38, 32, 34, 30, 50, 48, 43, 31, 35, 17, 0, 3, 20, 36, 28, 23, 27, 42, 26, 16, 47, 25, 22, 7, 8,
			29, 38, 32, 34, 30, 50, 48, 43, 33, 37, 18, 1, 3, 21, 36, 28, 24, 27, 42, 26 };
	final static char[] h_jongsung_idx = { 0, 1, 2, 3, 4, 5, 6, 7, 0, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 0, 18, 19, 20, 21, 22, 0, 23, 24, 25, 26, 27 };
	final static char[] h_chosung_idx = { 0, 1, 9, 2, 12, 18, 3, 4, 5, 0, 6, 7, 9, 16, 17, 18, 6, 7, 8, 9, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18 };
	private static char HCURSOR_NONE = 0;
	private static char HCURSOR_NEW = 1;
	private static char HCURSOR_ADD = 2;
	private static char HCURSOR_UPDATE = 3;
	private static char HCURSOR_APPEND = 4;
	private static char HCURSOR_UPDATE_LAST = 5;
	private static char HCURSOR_DELETE_LAST = 6;
	private static char HCURSOR_DELETE = 7;
	private static int mHCursorState = HCURSOR_NONE;
	private static char h_char[] = new char[1];
	private int previousCurPos = -2;
	private int previousHangulCurPos = -1;

	private BroadcastReceiver reciever = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d("", "Recieved: " + intent.getExtras().getString("language"));
			String keyboard = intent.getExtras().getString("language");

			composingSpaceAutoCommit = false;

			candidatesContainer = null;

			if (keyboard != null) {
				if (keyboard.equalsIgnoreCase("Arabic")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_arabic;

				} else if (keyboard.equalsIgnoreCase("Bahasa Melayu")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_english;

				} else if (keyboard.equalsIgnoreCase("Bengali")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_bengali;
					mFunctionKeyboard = R.xml.kbd_qwerty_bengali2;

				} else if (keyboard.equalsIgnoreCase("Bulgarian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_bulgarian;

				} else if (keyboard.equalsIgnoreCase("Chinese - Simplified") || keyboard.equalsIgnoreCase("Chinese - Traditional")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_chinese_en;

					mExtraKeyboard1 = R.xml.kbd_qwerty_chinese_zhuyin;
					mExtraKeyboard2 = R.xml.kbd_qwerty_chinese_cangjie;

					if (!listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_en)) {
						listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_en);
					}

					if (!listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_cangjie)) {
						listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_cangjie);
					}

					if (!listComposingKeyboards.contains(R.xml.kbd_qwerty_chinese_zhuyin)) {
						listComposingKeyboards.add(R.xml.kbd_qwerty_chinese_zhuyin);
					}

				} else if (keyboard.equalsIgnoreCase("Czech")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_czech;

				} else if (keyboard.equalsIgnoreCase("Dutch")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_dutch;

				} else if (keyboard.equalsIgnoreCase("English - US")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_english;

				} else if (keyboard.equalsIgnoreCase("English - UK")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_english;

				} else if (keyboard.equalsIgnoreCase("French")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_french;

				} else if (keyboard.equalsIgnoreCase("German")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_german;

				} else if (keyboard.equalsIgnoreCase("Gujarati")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_gurjarati;
					mFunctionKeyboard = R.xml.kbd_qwerty_gurjarati2;

				} else if (keyboard.equalsIgnoreCase("Hebrew")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_hebrew;

				} else if (keyboard.equalsIgnoreCase("Hindi")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_hindi;
					mFunctionKeyboard = R.xml.kbd_qwerty_hindi2;

				} else if (keyboard.equalsIgnoreCase("Hungarian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_hungarian;

				} else if (keyboard.equalsIgnoreCase("Japanese")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_japanese_en;

					if (!listComposingKeyboards.contains(R.xml.kbd_qwerty_japanese_en)) {
						listComposingKeyboards.add(R.xml.kbd_qwerty_japanese_en);
					}

				} else if (keyboard.equalsIgnoreCase("Kannada")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_kannada;
					mFunctionKeyboard = R.xml.kbd_qwerty_kannada2;

				} else if (keyboard.equalsIgnoreCase("Korean")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_korean;

					if (!listComposingKeyboards.contains(R.xml.kbd_qwerty_korean)) {
						listComposingKeyboards.add(R.xml.kbd_qwerty_korean);
					}

					composingSpaceAutoCommit = true;

				} else if (keyboard.equalsIgnoreCase("Lithuanian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_lithuanian;

				} else if (keyboard.equalsIgnoreCase("Malayalam")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_malayalam;
					mFunctionKeyboard = R.xml.kbd_qwerty_malayalam2;

				} else if (keyboard.equalsIgnoreCase("Marathi")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_martahi;
					mFunctionKeyboard = R.xml.kbd_qwerty_marathi2;

				} else if (keyboard.equalsIgnoreCase("Polish")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_polish;

				} else if (keyboard.equalsIgnoreCase("Punjabi")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_punjabi;
					mFunctionKeyboard = R.xml.kbd_qwerty_punjabi2;

				} else if (keyboard.equalsIgnoreCase("Romanian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_romanian;

				} else if (keyboard.equalsIgnoreCase("Russian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_russian;

				} else if (keyboard.equalsIgnoreCase("Spanish - Traditional")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_spanish_traditional;

				} else if (keyboard.equalsIgnoreCase("Spanish - US")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_spanish_traditional;

				} else if (keyboard.equalsIgnoreCase("Tamil")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_tamil;
					mFunctionKeyboard = R.xml.kbd_qwerty_tamil2;

				} else if (keyboard.equalsIgnoreCase("Telugu")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_telugu;
					mFunctionKeyboard = R.xml.kbd_qwerty_telugu2;

				} else if (keyboard.equalsIgnoreCase("Thai")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_thai;
					mFunctionKeyboard = R.xml.kbd_qwerty_thai2;

				} else if (keyboard.equalsIgnoreCase("Turkish")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_turkish;

				} else if (keyboard.equalsIgnoreCase("Ukranian")) {
					mUseThisKeyboard = R.xml.kbd_qwerty_ukranian;

				} else {
					// For testing, should default to english
					mUseThisKeyboard = R.xml.kbd_qwerty_english;
					mFunctionKeyboard = -1;
				}
			}
		}
	};

	@Override
	public void onCreate() {
		Log.i("PCKeyboard", "onCreate(), os.version=" + System.getProperty("os.version"));
		editor = createEditor();
		wordDictionary = createWordDictionary(this);
		phraseDictionary = new PhraseDictionary(this);

		LatinImeLogger.init(this);
		KeyboardSwitcher.init(this);
		super.onCreate();

		onCreateCandidatesView();
		setCandidatesViewShown(false);

		mResources = getResources();
		final Configuration conf = mResources.getConfiguration();
		mOrientation = conf.orientation;
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		mLanguageSwitcher = new LanguageSwitcher(this);
		mLanguageSwitcher.loadLocales(prefs);
		mKeyboardSwitcher = KeyboardSwitcher.getInstance();
		mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
		mSystemLocale = conf.locale.toString();
		mLanguageSwitcher.setSystemLocale(conf.locale);
		String inputLanguage = mLanguageSwitcher.getInputLanguage();
		if (inputLanguage == null) {
			inputLanguage = conf.locale.toString();
		}
		Resources res = getResources();
		mConnectbotTabHack = prefs.getBoolean(PREF_CONNECTBOT_TAB_HACK, res.getBoolean(R.bool.default_connectbot_tab_hack));
		mFullscreenOverride = prefs.getBoolean(PREF_FULLSCREEN_OVERRIDE, res.getBoolean(R.bool.default_fullscreen_override));
		mForceKeyboardOn = prefs.getBoolean(PREF_FORCE_KEYBOARD_ON, res.getBoolean(R.bool.default_force_keyboard_on));
		mKeyboardNotification = prefs.getBoolean(PREF_KEYBOARD_NOTIFICATION, res.getBoolean(R.bool.default_keyboard_notification));
		mSuggestionsInLandscape = prefs.getBoolean(PREF_SUGGESTIONS_IN_LANDSCAPE, res.getBoolean(R.bool.default_suggestions_in_landscape));
		mHeightPortrait = getHeight(prefs, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
		mHeightLandscape = getHeight(prefs, PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
		LatinIME.sKeyboardSettings.hintMode = Integer.parseInt(prefs.getString(PREF_HINT_MODE, "0"));
		LatinIME.sKeyboardSettings.longpressTimeout = getPrefInt(prefs, PREF_LONGPRESS_TIMEOUT, res.getString(R.string.default_long_press_duration));
		LatinIME.sKeyboardSettings.renderMode = getPrefInt(prefs, PREF_RENDER_MODE, res.getString(R.string.default_render_mode));
		mSwipeUpAction = prefs.getString(PREF_SWIPE_UP, "0");
		mSwipeDownAction = prefs.getString(PREF_SWIPE_DOWN, "0");
		mSwipeLeftAction = prefs.getString(PREF_SWIPE_LEFT, "0");
		mSwipeRightAction = prefs.getString(PREF_SWIPE_RIGHT, "0");
		mVolUpAction = prefs.getString(PREF_VOL_UP, "0");
		mVolDownAction = prefs.getString(PREF_VOL_DOWN, "0");
		sKeyboardSettings.initPrefs(prefs, res);

		updateKeyboardOptions();

		LatinIMEUtil.GCUtils.getInstance().reset();
		boolean tryGC = true;
		for (int i = 0; i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC; ++i) {
			try {
				initSuggest(inputLanguage);
				tryGC = false;
			} catch (OutOfMemoryError e) {
				tryGC = LatinIMEUtil.GCUtils.getInstance().tryGCOrWait(inputLanguage, e);
			}
		}

		mOrientation = conf.orientation;

		// register to receive ringer mode changes for silent mode
		IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
		registerReceiver(mReceiver, filter);

		prefs.registerOnSharedPreferenceChangeListener(this);
		setNotification(mKeyboardNotification);

		registerReceiver(reciever, new IntentFilter("assistekAction"));
	}

	@Override
	public View onCreateCandidatesView() {
		if (mKeyboardSwitcher != null) {
			if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_zhuyin
					|| mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_cangjie //
					|| mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_en //
					|| mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_japanese_en) {
				candidatesContainer = (CandidatesContainer)getLayoutInflater().inflate(R.layout.candidates, null);
				candidatesContainer.setCandidateViewListener(this);
			}
		}

		return candidatesContainer;
	}

	private void setCandidates(String words, boolean highlightDefault) {
		if (candidatesContainer == null) {
			if (onCreateCandidatesView() != null) { 
				setCandidatesView(onCreateCandidatesView());
			}
		}

		if (candidatesContainer != null) {
			candidatesContainer.setCandidates(words, highlightDefault);
			setCandidatesViewShown((words.length() > 0) || (editor != null && editor.hasComposingText()));
		}
	}

	public void onPickCandidate(String candidate) {
		// Commit the picked candidate and suggest its following words.
		commitText(candidate);

		if (mKeyboardSwitcher != null && //
				(mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_zhuyin //
				|| mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_cangjie)) {

			if (candidate.length() > 0) {
				setCandidates(phraseDictionary.getFollowingWords(candidate.charAt(0)), false);
				mComposing.setLength(0);
				mComposing.append(candidate.charAt(0));

				mComposingOriginal.setLength(0);
				mComposingOriginal.append(candidate.charAt(0));

			} else {
				clearCandidates();
				setCandidatesViewShown(false);
				mComposing.setLength(0);
				mComposingOriginal.setLength(0);
			}
		} else {
			clearCandidates();
			setCandidatesViewShown(false);
		}
	}

	private void clearCandidates() {
		setCandidates("", false);
	}

	private boolean handleSpace(int keyCode) {
		if (keyCode == ' ') {
			if ((candidatesContainer != null) && candidatesContainer.isShown()) {
				// The space key could either pick the highlighted candidate or escape
				// if there's no highlighted candidate and no composing-text.
				if (!candidatesContainer.pickHighlighted() && !editor.hasComposingText()) {
					escape();
				}
			} else {
				commitText(" ");
			}
			return true;
		}
		return false;
	}

	private void escape() {
		editor.clearComposingText(getCurrentInputConnection());
		clearCandidates();
	}

	private void commitText(CharSequence text) {
		if (editor == null) {
			clearCandidates();
			setCandidatesViewShown(false);

			getCurrentInputConnection().commitText(text, 1);
			mComposing.setLength(0);
			mComposingOriginal.setLength(0);

		} else if (editor.commitText(getCurrentInputConnection(), text)) {
			// Clear candidates after committing any text.
			clearCandidates();
		}
	}

	private Editor createEditor() {
		if (mKeyboardSwitcher != null) {
			if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_zhuyin) {
				return new ZhuyinEditor();
			} else if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_cangjie) {
				return new CangjieEditor();
			} /*else if (mUseThisKeyboard == R.xml.kbd_qwerty_japanese) {
				return new JapaneseEditor();
				} */else {
				return null;
			}
		} else {
			return null;
		}
	}

	private WordDictionary createWordDictionary(Context con) {
		if (mKeyboardSwitcher != null) {
			if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_zhuyin) {
				return new ZhuyinDictionary(con);
			} else if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_chinese_cangjie) {
				return new CangjieDictionary(con);
			}/* else if (mUseThisKeyboard == R.xml.kbd_qwerty_japanese) {
				return new JapaneseDictionary(con);
				}*/else {
				return null;
			}
		} else {
			return null;
		}
	}

	// End Chinese

	// Start Korean
	private void handleHangul(int primaryCode, int[] keyCodes) {
		int hangulKeyIdx = -1;
		int newHangulChar;
		int cho_idx, jung_idx, jong_idx;
		int hangulChar = 0;
		/*        
		        if (mHangulCursorMoved == 1) {
		        	clearHangul();
		        	Log.i("Hangul", "clear Hangul at handleHangul by mHangulCursorMoved");
		        	mHangulCursorMoved = 0;
		        }
		*/
		// Log.i("Hangul", "PrimaryCode[" + Integer.toString(primaryCode)+"]");

		if (primaryCode >= 0x61 && primaryCode <= 0x7A) {
			// Log.i("SoftKey", "handleHangul - hancode");

			if (mKeyboardSwitcher.getInputView().getShiftState() == 0) {
				hangulKeyIdx = e2h_map[primaryCode - 0x61];
			} else {
				hangulKeyIdx = e2h_map[primaryCode - 0x61 + 26];
				// Keyboard currentKeyboard = mInputView.getKeyboard();
				// mHangulShiftedKeyboard.setShifted(false);
				// mInputView.setKeyboard(mHangulKeyboard);
				// mHangulKeyboard.setShifted(false);
				mKeyboardSwitcher.setShiftState(0);
				if (!mKeyboardSwitcher.hasDistinctMultitouch()) {
					handleShift();
				}
				// mHangulShiftState = 0;
			}
			hangulChar = 1;

		} else if (primaryCode >= 0x41 && primaryCode <= 0x5A) {
			hangulKeyIdx = e2h_map[primaryCode - 0x41 + 26];
			hangulChar = 1;

		} else if (primaryCode == 139 || primaryCode == 145 || primaryCode == 127 || primaryCode == 140 || primaryCode == 142 || primaryCode == 137
				|| primaryCode == 138) {
			hangulKeyIdx = e2h_map[primaryCode - 0x61];
			hangulChar = 1;
		}
		/*
		else  if (primaryCode >= 0x3131 && primaryCode <= 0x3163) {
			hangulKeyIdx = primaryCode - 0x3131;
			hangulChar = 1;
		}
		*/
		else {
			hangulChar = 0;
		}

		if (hangulChar == 1) {

			switch (mHangulState) {

				case H_STATE_0: // Hangul Clear State
					// Log.i("SoftKey", "HAN_STATE 0");
					if (hangulKeyIdx < 30) { // if 자음
						newHangulChar = 0x3131 + hangulKeyIdx;
						hangulSendKey(newHangulChar, HCURSOR_NEW);
						mHangulKeyStack[0] = hangulKeyIdx;
						mHangulJamoStack[0] = hangulKeyIdx;
						mHangulState = H_STATE_1; // goto 초성
					} else { // if 모음
						newHangulChar = 0x314F + (hangulKeyIdx - 30);
						hangulSendKey(newHangulChar, HCURSOR_NEW);
						mHangulKeyStack[2] = hangulKeyIdx;
						mHangulJamoStack[1] = hangulKeyIdx;
						mHangulState = H_STATE_3; // goto 중성
					}
					break;

				case H_STATE_1: // 초성
					// Log.i("SoftKey", "HAN_STATE 1");
					if (hangulKeyIdx < 30) { // if 자음
						int newHangulKeyIdx = isHangulKey(0, hangulKeyIdx);
						if (newHangulKeyIdx > 0) { // if 복자음
							newHangulChar = 0x3131 + newHangulKeyIdx;
							mHangulKeyStack[1] = hangulKeyIdx;
							mHangulJamoStack[0] = newHangulKeyIdx;
							// hangulSendKey(-1);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							mHangulState = H_STATE_2; // goto 초성(복자음)
						} else { // if 자음

							// cursor error trick start
							newHangulChar = 0x3131 + mHangulJamoStack[0];
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							// trick end

							newHangulChar = 0x3131 + hangulKeyIdx;
							hangulSendKey(newHangulChar, HCURSOR_ADD);
							mHangulKeyStack[0] = hangulKeyIdx;
							mHangulJamoStack[0] = hangulKeyIdx;
							mHangulState = H_STATE_1; // goto 초성
						}
					} else { // if 모음
						mHangulKeyStack[2] = hangulKeyIdx;
						mHangulJamoStack[1] = hangulKeyIdx;
						// hangulSendKey(-1);
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2]];
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						mHangulState = H_STATE_4; // goto 초성,중성
					}
					break;

				case H_STATE_2: // 초성(복자음)
					// Log.i("SoftKey", "HAN_STATE 2");
					if (hangulKeyIdx < 30) { // if 자음

						// cursor error trick start
						newHangulChar = 0x3131 + mHangulJamoStack[0];
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						// trick end

						mHangulKeyStack[0] = hangulKeyIdx;
						mHangulJamoStack[0] = hangulKeyIdx;
						mHangulJamoStack[1] = 0;
						newHangulChar = 0x3131 + hangulKeyIdx;
						hangulSendKey(newHangulChar, HCURSOR_ADD);
						mHangulState = H_STATE_1; // goto 초성
					} else { // if 모음
						newHangulChar = 0x3131 + mHangulKeyStack[0];
						mHangulKeyStack[0] = mHangulKeyStack[1];
						mHangulJamoStack[0] = mHangulKeyStack[0];
						mHangulKeyStack[1] = 0;
						// hangulSendKey(-1);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);

						mHangulKeyStack[2] = hangulKeyIdx;
						mHangulJamoStack[1] = mHangulKeyStack[2];
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = 0;

						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_ADD);
						mHangulState = H_STATE_4; // goto 초성,중성
					}
					break;

				case H_STATE_3: // 중성(단모음,복모음)
					// Log.i("SoftKey", "HAN_STATE 3");
					if (hangulKeyIdx < 30) { // 자음

						// cursor error trick start
						newHangulChar = 0x314F + (mHangulJamoStack[1] - 30);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						// trick end

						newHangulChar = 0x3131 + hangulKeyIdx;
						hangulSendKey(newHangulChar, HCURSOR_ADD);
						mHangulKeyStack[0] = hangulKeyIdx;
						mHangulJamoStack[0] = hangulKeyIdx;
						mHangulJamoStack[1] = 0;
						mHangulState = H_STATE_1; // goto 초성
					} else { // 모음
						if (mHangulKeyStack[3] == 0) {
							int newHangulKeyIdx = isHangulKey(2, hangulKeyIdx);
							if (newHangulKeyIdx > 0) { // 복모음
								// hangulSendKey(-1);
								newHangulChar = 0x314F + (newHangulKeyIdx - 30);
								hangulSendKey(newHangulChar, HCURSOR_UPDATE);
								mHangulKeyStack[3] = hangulKeyIdx;
								mHangulJamoStack[1] = newHangulKeyIdx;
							} else { // 모음

								// cursor error trick start
								newHangulChar = 0x314F + (mHangulJamoStack[1] - 30);
								hangulSendKey(newHangulChar, HCURSOR_UPDATE);
								// trick end

								newHangulChar = 0x314F + (hangulKeyIdx - 30);
								hangulSendKey(newHangulChar, HCURSOR_ADD);
								mHangulKeyStack[2] = hangulKeyIdx;
								mHangulJamoStack[1] = hangulKeyIdx;
							}
						} else {

							// cursor error trick start
							newHangulChar = 0x314F + (mHangulJamoStack[1] - 30);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							// trick end

							newHangulChar = 0x314F + (hangulKeyIdx - 30);
							hangulSendKey(newHangulChar, HCURSOR_ADD);
							mHangulKeyStack[2] = hangulKeyIdx;
							mHangulJamoStack[1] = hangulKeyIdx;
							mHangulKeyStack[3] = 0;
						}
						mHangulState = H_STATE_3;
					}
					break;
				case H_STATE_4: // 초성,중성(단모음,복모음)
					// Log.i("SoftKey", "HAN_STATE 4");
					if (hangulKeyIdx < 30) { // if 자음
						mHangulKeyStack[4] = hangulKeyIdx;
						mHangulJamoStack[2] = hangulKeyIdx;
						// hangulSendKey(-1);
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						if (jong_idx == 0) { // if 종성 is not valid ex, 라 + ㅉ
							mHangulKeyStack[0] = hangulKeyIdx;
							mHangulKeyStack[1] = 0;
							mHangulKeyStack[2] = 0;
							mHangulKeyStack[3] = 0;
							mHangulKeyStack[4] = 0;
							mHangulJamoStack[0] = hangulKeyIdx;
							mHangulJamoStack[1] = 0;
							mHangulJamoStack[2] = 0;
							newHangulChar = 0x3131 + hangulKeyIdx;
							hangulSendKey(newHangulChar, HCURSOR_ADD);
							mHangulState = H_STATE_1; // goto 초성
						} else {
							mHangulState = H_STATE_5; // goto 초성,중성,종성
						}
					} else { // if 모음
						if (mHangulKeyStack[3] == 0) {
							int newHangulKeyIdx = isHangulKey(2, hangulKeyIdx);
							if (newHangulKeyIdx > 0) { // if 복모음
								// hangulSendKey(-1);
								// mHangulKeyStack[2] = newHangulKeyIdx;
								mHangulKeyStack[3] = hangulKeyIdx;
								mHangulJamoStack[1] = newHangulKeyIdx;
								cho_idx = h_chosung_idx[mHangulJamoStack[0]];
								jung_idx = mHangulJamoStack[1] - 30;
								jong_idx = 0;
								newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
								hangulSendKey(newHangulChar, HCURSOR_UPDATE);
								mHangulState = H_STATE_4; // goto 초성,중성
							} else { // if invalid 복모음

								// cursor error trick start
								cho_idx = h_chosung_idx[mHangulJamoStack[0]];
								jung_idx = mHangulJamoStack[1] - 30;
								jong_idx = 0;
								newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
								hangulSendKey(newHangulChar, HCURSOR_UPDATE);
								// trick end

								newHangulChar = 0x314F + (hangulKeyIdx - 30);
								hangulSendKey(newHangulChar, HCURSOR_ADD);
								mHangulKeyStack[0] = 0;
								mHangulKeyStack[1] = 0;
								mHangulJamoStack[0] = 0;
								mHangulKeyStack[2] = hangulKeyIdx;
								mHangulJamoStack[1] = hangulKeyIdx;
								mHangulState = H_STATE_3; // goto 중성
							}
						} else {

							// cursor error trick start
							cho_idx = h_chosung_idx[mHangulJamoStack[0]];
							jung_idx = mHangulJamoStack[1] - 30;
							jong_idx = 0;
							newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							// trick end

							newHangulChar = 0x314F + (hangulKeyIdx - 30);
							hangulSendKey(newHangulChar, HCURSOR_ADD);
							mHangulKeyStack[0] = 0;
							mHangulKeyStack[1] = 0;
							mHangulJamoStack[0] = 0;
							mHangulKeyStack[2] = hangulKeyIdx;
							mHangulJamoStack[1] = hangulKeyIdx;
							mHangulKeyStack[3] = 0;
							mHangulState = H_STATE_3; // goto 중성

						}
					}
					break;
				case H_STATE_5: // 초성,중성,종성
					// Log.i("SoftKey", "HAN_STATE 5");
					if (hangulKeyIdx < 30) { // if 자음
						int newHangulKeyIdx = isHangulKey(4, hangulKeyIdx);
						if (newHangulKeyIdx > 0) { // if 종성 == 복자음
							// hangulSendKey(-1);
							mHangulKeyStack[5] = hangulKeyIdx;
							mHangulJamoStack[2] = newHangulKeyIdx;

							cho_idx = h_chosung_idx[mHangulJamoStack[0]];
							jung_idx = mHangulJamoStack[1] - 30;
							jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
							;
							newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							mHangulState = H_STATE_6; // goto 초성,중성,종성(복자음)
						} else { // if 종성 != 복자음

							// cursor error trick start
							cho_idx = h_chosung_idx[mHangulJamoStack[0]];
							jung_idx = mHangulJamoStack[1] - 30;
							jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
							;
							newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							// trick end

							mHangulKeyStack[0] = hangulKeyIdx;
							mHangulKeyStack[1] = 0;
							mHangulKeyStack[2] = 0;
							mHangulKeyStack[3] = 0;
							mHangulKeyStack[4] = 0;
							mHangulJamoStack[0] = hangulKeyIdx;
							mHangulJamoStack[1] = 0;
							mHangulJamoStack[2] = 0;
							newHangulChar = 0x3131 + hangulKeyIdx;
							hangulSendKey(newHangulChar, HCURSOR_ADD);
							mHangulState = H_STATE_1; // goto 초성
						}
					} else { // if 모음
						// hangulSendKey(-1);

						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = 0;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);

						mHangulKeyStack[0] = mHangulKeyStack[4];
						mHangulKeyStack[1] = 0;
						mHangulKeyStack[2] = hangulKeyIdx;
						mHangulKeyStack[3] = 0;
						mHangulKeyStack[4] = 0;
						mHangulJamoStack[0] = mHangulKeyStack[0];
						mHangulJamoStack[1] = mHangulKeyStack[2];
						mHangulJamoStack[2] = 0;

						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = 0;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_ADD);

						// Log.i("SoftKey", "--- Goto HAN_STATE 4");
						mHangulState = H_STATE_4; // goto 초성,중성
					}
					break;
				case H_STATE_6: // 초성,중성,종성(복자음)
					// Log.i("SoftKey", "HAN_STATE 6");
					if (hangulKeyIdx < 30) { // if 자음

						// cursor error trick start
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
						;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						// trick end

						mHangulKeyStack[0] = hangulKeyIdx;
						mHangulKeyStack[1] = 0;
						mHangulKeyStack[2] = 0;
						mHangulKeyStack[3] = 0;
						mHangulKeyStack[4] = 0;
						mHangulJamoStack[0] = hangulKeyIdx;
						mHangulJamoStack[1] = 0;
						mHangulJamoStack[2] = 0;

						newHangulChar = 0x3131 + hangulKeyIdx;
						hangulSendKey(newHangulChar, HCURSOR_ADD);

						mHangulState = H_STATE_1; // goto 초성
					} else { // if 모음
						// hangulSendKey(-1);
						mHangulJamoStack[2] = mHangulKeyStack[4];

						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
						;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);

						mHangulKeyStack[0] = mHangulKeyStack[5];
						mHangulKeyStack[1] = 0;
						mHangulKeyStack[2] = hangulKeyIdx;
						mHangulKeyStack[3] = 0;
						mHangulKeyStack[4] = 0;
						mHangulKeyStack[5] = 0;
						mHangulJamoStack[0] = mHangulKeyStack[0];
						mHangulJamoStack[1] = mHangulKeyStack[2];
						mHangulJamoStack[2] = 0;

						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = 0;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_ADD);

						mHangulState = H_STATE_4; // goto 초성,중성
					}
					break;
			}
		} else {
			// Log.i("Hangul", "handleHangul - No hancode");
			clearHangul();
			sendKey(primaryCode);
		}
	}

	private void hangulSendKey(int newHangulChar, int hCursor) {
		if (hCursor == HCURSOR_NEW) {
			Log.i("Hangul", "HCURSOR_NEW");

			mComposing.append((char)newHangulChar);
			getCurrentInputConnection().setComposingText(mComposing, 1);
			mHCursorState = HCURSOR_NEW;
		} else if (hCursor == HCURSOR_ADD) {
			mHCursorState = HCURSOR_ADD;
			Log.i("Hangul", "HCURSOR_ADD");
			if (mComposing.length() > 0) {
				mComposing.setLength(0);
				getCurrentInputConnection().finishComposingText();
			}

			mComposing.append((char)newHangulChar);
			getCurrentInputConnection().setComposingText(mComposing, 1);
		} else if (hCursor == HCURSOR_UPDATE) {
			Log.i("Hangul", "HCURSOR_UPDATE");
			if (mComposing.length() > 0) {
				mComposing.setCharAt(0, (char)newHangulChar);
			}

			getCurrentInputConnection().setComposingText(mComposing, 1);
			mHCursorState = HCURSOR_UPDATE;
		} else if (hCursor == HCURSOR_APPEND) {
			Log.i("Hangul", "HCURSOR_APPEND");
			mComposing.append((char)newHangulChar);
			getCurrentInputConnection().setComposingText(mComposing, 1);
			mHCursorState = HCURSOR_APPEND;
		} else if (hCursor == HCURSOR_NONE) {
			if (newHangulChar == -1) {
				Log.i("Hangul", "HCURSOR_NONE [DEL -1]");
				keyDownUp(KeyEvent.KEYCODE_DEL);
				clearHangul();
			} else if (newHangulChar == -2) {
				int hangulKeyIdx;
				int cho_idx, jung_idx, jong_idx;

				Log.i("Hangul", "HCURSOR_NONE [DEL -2]");

				switch (mHangulState) {
					case H_STATE_0:
						keyDownUp(KeyEvent.KEYCODE_DEL);
						break;
					case H_STATE_1: // 초성
						// keyDownUp(KeyEvent.KEYCODE_DEL);
						mComposing.setLength(0);
						getCurrentInputConnection().commitText("", 0);
						clearHangul();
						mHangulState = H_STATE_0;
						break;
					case H_STATE_2: // 초성(복자음)
						newHangulChar = 0x3131 + mHangulKeyStack[0];
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						mHangulKeyStack[1] = 0;
						mHangulJamoStack[0] = mHangulKeyStack[0];
						mHangulState = H_STATE_1; // goto 초성
						break;
					case H_STATE_3: // 중성(단모음,복모음)
						if (mHangulKeyStack[3] == 0) {
							// keyDownUp(KeyEvent.KEYCODE_DEL);
							mComposing.setLength(0);
							getCurrentInputConnection().commitText("", 0);
							clearHangul();
							mHangulState = H_STATE_0;
						} else {
							mHangulKeyStack[3] = 0;
							newHangulChar = 0x314F + (mHangulKeyStack[2] - 30);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							mHangulJamoStack[1] = mHangulKeyStack[2];
							mHangulState = H_STATE_3; // goto 중성
						}
						break;
					case H_STATE_4: // 초성,중성(단모음,복모음)
						if (mHangulKeyStack[3] == 0) {
							mHangulKeyStack[2] = 0;
							mHangulJamoStack[1] = 0;
							newHangulChar = 0x3131 + mHangulJamoStack[0];
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
							mHangulState = H_STATE_1; // goto 초성
						} else {
							mHangulJamoStack[1] = mHangulKeyStack[2];
							mHangulKeyStack[3] = 0;
							cho_idx = h_chosung_idx[mHangulJamoStack[0]];
							jung_idx = mHangulJamoStack[1] - 30;
							jong_idx = h_jongsung_idx[mHangulJamoStack[2]];
							newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
							hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						}
						break;
					case H_STATE_5: // 초성,중성,종성
						mHangulJamoStack[2] = 0;
						mHangulKeyStack[4] = 0;
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2]];
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						mHangulState = H_STATE_4;
						break;
					case H_STATE_6:
						mHangulKeyStack[5] = 0;
						mHangulJamoStack[2] = mHangulKeyStack[4];
						cho_idx = h_chosung_idx[mHangulJamoStack[0]];
						jung_idx = mHangulJamoStack[1] - 30;
						jong_idx = h_jongsung_idx[mHangulJamoStack[2] + 1];
						;
						newHangulChar = 0xAC00 + ((cho_idx * 21 * 28) + (jung_idx * 28) + jong_idx);
						hangulSendKey(newHangulChar, HCURSOR_UPDATE);
						mHangulState = H_STATE_5;
						break;
				}
			} else if (newHangulChar == -3) {
				Log.i("Hangul", "HCURSOR_NONE [DEL -3]");
				final int length = mComposing.length();
				if (length > 1) {
					mComposing.delete(length - 1, length);
				}
			}

		}
	}

	private void clearHangul() {
		mHCursorState = HCURSOR_NONE;
		mHangulState = 0;
		previousHangulCurPos = -1;
		mHangulKeyStack[0] = 0;
		mHangulKeyStack[1] = 0;
		mHangulKeyStack[2] = 0;
		mHangulKeyStack[3] = 0;
		mHangulKeyStack[4] = 0;
		mHangulKeyStack[5] = 0;
		mHangulJamoStack[0] = 0;
		mHangulJamoStack[1] = 0;
		mHangulJamoStack[2] = 0;
		return;
	}

	private void keyDownUp(int keyEventCode) {
		getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
		getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
	}

	private void sendKey(int keyCode) {
		switch (keyCode) {
			case '\n':
				keyDownUp(KeyEvent.KEYCODE_ENTER);
				break;
			default:
				if (keyCode >= '0' && keyCode <= '9') {
					keyDownUp(keyCode - '0' + KeyEvent.KEYCODE_0);
				} else {
					getCurrentInputConnection().commitText(String.valueOf((char)keyCode), 1);
				}
				break;
		}
	}

	private int isHangulKey(int stack_pos, int new_key) {
		/*    
		MAP(0,20,1); // ㄱ,ㅅ 
		MAP(3,23,4); // ㄴ,ㅈ
		MAP(3,29,5); // ㄴ,ㅎ
		MAP(8,0,9); // ㄹ,ㄱ
		MAP(8,16,10); // ㄹ,ㅁ
		MAP(8,17,11); // ㄹ,ㅂ
		MAP(8,20,12); // ㄹ,ㅅ
		MAP(8,27,13); // ㄹ,ㅌ
		MAP(8,28,14); // ㄹ,ㅍ
		MAP(8,29,15); // ㄹ,ㅎ
		MAP(17,20,19); // ㅂ,ㅅ			
		*/
		if (stack_pos != 2) {
			switch (mHangulKeyStack[stack_pos]) {
				case 0:
					if (new_key == 20)
						return 2;
					break;
				case 3:
					if (new_key == 23)
						return 4;
					else if (new_key == 29)
						return 5;
					break;
				case 8:
					if (new_key == 0)
						return 9;
					else if (new_key == 16)
						return 10;
					else if (new_key == 17)
						return 11;
					else if (new_key == 20)
						return 12;
					else if (new_key == 27)
						return 13;
					else if (new_key == 28)
						return 14;
					else if (new_key == 29)
						return 15;
					break;
				case 17:
					if (new_key == 20)
						return 19;
					break;
			}
		} else {
			/*        	 
			38, 30, 39 // ㅗ ㅏ ㅘ
			38, 31, 40 // ㅗ ㅐ ㅙ
			38, 50, 41 //ㅗ ㅣ ㅚ
			43, 34, 44 // ㅜ ㅓ ㅝ
			43, 35, 45 // ㅜ ㅔ ㅞ
			43, 50, 46 // ㅜ ㅣ ㅟ
			48, 50, 49 // ㅡ ㅣ ㅢ
			*/
			switch (mHangulKeyStack[stack_pos]) {
				case 38:
					if (new_key == 30)
						return 39;
					else if (new_key == 31)
						return 40;
					else if (new_key == 50)
						return 41;
					break;
				case 43:
					if (new_key == 34)
						return 44;
					else if (new_key == 35)
						return 45;
					else if (new_key == 50)
						return 46;
					break;
				case 48:
					if (new_key == 50)
						return 49;
					break;
			}
		}
		return 0;
	}

	// End Korean

	private int getKeyboardModeNum(int origMode, int override) {
		if (mNumKeyboardModes == 2 && origMode == 2)
			origMode = 1; // skip "compact". !
		int num = (origMode + override) % mNumKeyboardModes;
		if (mNumKeyboardModes == 2 && num == 1)
			num = 2; // skip "compact". !
		return num;
	}

	private void updateKeyboardOptions() {
		// Log.i(TAG, "setFullKeyboardOptions " + fullInPortrait + " " + heightPercentPortrait + " " + heightPercentLandscape);
		boolean isPortrait = isPortrait();
		int kbMode;
		mNumKeyboardModes = sKeyboardSettings.compactModeEnabled ? 3 : 2; // !
		if (isPortrait) {
			kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModePortrait, mKeyboardModeOverridePortrait);
		} else {
			kbMode = getKeyboardModeNum(sKeyboardSettings.keyboardModeLandscape, mKeyboardModeOverrideLandscape);
		}
		// Convert overall keyboard height to per-row percentage
		int screenHeightPercent = isPortrait ? mHeightPortrait : mHeightLandscape;
		LatinIME.sKeyboardSettings.keyboardMode = kbMode;
		LatinIME.sKeyboardSettings.keyboardHeightPercent = (float)screenHeightPercent;
	}

	private void setNotification(boolean visible) {
		final String ACTION = "com.assistek.util.keyboard.SHOW";
		final int ID = 1;
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager mNotificationManager = (NotificationManager)getSystemService(ns);

		if (visible && mNotificationReceiver == null) {
			int icon = R.drawable.atlogo;
			CharSequence text = "Keyboard notification enabled.";
			long when = System.currentTimeMillis();
			Notification notification = new Notification(icon, text, when);

			// : clean this up?
			mNotificationReceiver = new NotificationReceiver(this);
			final IntentFilter pFilter = new IntentFilter(ACTION);
			registerReceiver(mNotificationReceiver, pFilter);

			Intent notificationIntent = new Intent(ACTION);

			PendingIntent contentIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, notificationIntent, 0);
			// PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

			String title = "Show Hacker's Keyboard";
			String body = "Select this to open the keyboard. Disable in settings.";

			notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
			notification.setLatestEventInfo(getApplicationContext(), title, body, contentIntent);
			mNotificationManager.notify(ID, notification);
		} else if (mNotificationReceiver != null) {
			mNotificationManager.cancel(ID);
			unregisterReceiver(mNotificationReceiver);
			mNotificationReceiver = null;
		}
	}

	private boolean isPortrait() {
		return (mOrientation == Configuration.ORIENTATION_PORTRAIT);
	}

	private boolean suggestionsDisabled() {
		if (mSuggestionForceOff)
			return true;
		if (mSuggestionForceOn)
			return false;
		return !(mSuggestionsInLandscape || isPortrait());
	}

	/**
	 * Loads a dictionary or multiple separated dictionary
	 * 
	 * @return returns array of dictionary resource ids
	 */
	/* package */static int[] getDictionary(Resources res) {
		String packageName = LatinIME.class.getPackage().getName();
		XmlResourceParser xrp = res.getXml(R.xml.dictionary);
		ArrayList<Integer> dictionaries = new ArrayList<Integer>();

		try {
			int current = xrp.getEventType();
			while (current != XmlResourceParser.END_DOCUMENT) {
				if (current == XmlResourceParser.START_TAG) {
					String tag = xrp.getName();
					if (tag != null) {
						if (tag.equals("part")) {
							String dictFileName = xrp.getAttributeValue(null, "name");
							dictionaries.add(res.getIdentifier(dictFileName, "raw", packageName));
						}
					}
				}
				xrp.next();
				current = xrp.getEventType();
			}
		} catch (XmlPullParserException e) {
			Log.e(TAG, "Dictionary XML parsing failure");
		} catch (IOException e) {
			Log.e(TAG, "Dictionary XML IOException");
		}

		int count = dictionaries.size();
		int[] dict = new int[count];
		for (int i = 0; i < count; i++) {
			dict[i] = dictionaries.get(i);
		}

		return dict;
	}

	private void initSuggest(String locale) {
		Resources orig = getResources();
		Configuration conf = orig.getConfiguration();
		Locale saveLocale = conf.locale;
		conf.locale = new Locale(locale);
		orig.updateConfiguration(conf, orig.getDisplayMetrics());

		mWordSeparators = mResources.getString(R.string.word_separators);
		mSentenceSeparators = mResources.getString(R.string.sentence_separators);
		initSuggestPuncList();

		conf.locale = saveLocale;
		orig.updateConfiguration(conf, orig.getDisplayMetrics());
	}

	@Override
	public void onDestroy() {
		unregisterReceiver(mReceiver);

		if (mNotificationReceiver != null) {
			unregisterReceiver(mNotificationReceiver);
			mNotificationReceiver = null;
		}

		LatinImeLogger.commit();
		LatinImeLogger.onDestroy();
		super.onDestroy();
	}

	@Override
	public void onConfigurationChanged(Configuration conf) {
		Log.i("PCKeyboard", "onConfigurationChanged()");
		// If the system locale changes and is different from the saved
		// locale (mSystemLocale), then reload the input locale list from the
		// latin ime settings (shared prefs) and reset the input locale
		// to the first one.
		final String systemLocale = conf.locale.toString();
		if (!TextUtils.equals(systemLocale, mSystemLocale)) {
			mSystemLocale = systemLocale;
			if (mLanguageSwitcher != null) {
				mLanguageSwitcher.loadLocales(PreferenceManager.getDefaultSharedPreferences(this));
				mLanguageSwitcher.setSystemLocale(conf.locale);
				toggleLanguage(true, true);
			} else {
				reloadKeyboards();
			}
		}
		// If orientation changed while predicting, commit the change
		if (conf.orientation != mOrientation) {
			InputConnection ic = getCurrentInputConnection();
			if (ic != null)
				ic.finishComposingText(); // For voice input
			mOrientation = conf.orientation;
			reloadKeyboards();
		}
		mConfigurationChanging = true;
		super.onConfigurationChanged(conf);
		mConfigurationChanging = false;
	}

	@Override
	public View onCreateInputView() {
		setCandidatesViewShown(false); // Workaround for "already has a parent" when reconfiguring
		mKeyboardSwitcher.recreateInputView();
		mKeyboardSwitcher.makeKeyboards(true);
		mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, 0);
		return mKeyboardSwitcher.getInputView();
	}

	@Override
	public AbstractInputMethodImpl onCreateInputMethodInterface() {
		return new MyInputMethodImpl();
	}

	IBinder mToken;

	public class MyInputMethodImpl extends InputMethodImpl {
		@Override
		public void attachToken(IBinder token) {
			super.attachToken(token);
			Log.i(TAG, "attachToken " + token);
			if (mToken == null) {
				mToken = token;
			}
		}
	}

	private void resetPrediction() {
		mComposing.setLength(0);
		mComposingOriginal.setLength(0);
		mDeleteCount = 0;
		mJustAddedAutoSpace = false;
		setCandidatesViewShown(false);
	}

	@Override
	public void onStartInputView(EditorInfo attribute, boolean restarting) {
		Log.d("", "onStartInputView attribute imeOption: " + attribute.imeOptions);
		sKeyboardSettings.editorPackageName = attribute.packageName;
		sKeyboardSettings.editorFieldName = attribute.fieldName;
		sKeyboardSettings.editorFieldId = attribute.fieldId;
		sKeyboardSettings.editorInputType = attribute.inputType;

		// Log.i("PCKeyboard", "onStartInputView " + attribute + ", inputType= " + Integer.toHexString(attribute.inputType) + ", restarting=" + restarting);
		LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
		// In landscape mode, this method gets called without the input view
		// being created.
		if (inputView == null) {
			return;
		}

		if (mRefreshKeyboardRequired) {
			mRefreshKeyboardRequired = false;
			toggleLanguage(true, true);
		}

		mKeyboardSwitcher.makeKeyboards(false);

		TextEntryState.newSession(this);

		// Most such things we decide below in the switch statement, but we need to know
		// now whether this is a password text field, because we need to know now (before
		// the switch statement) whether we want to enable the voice button.

		int variation = attribute.inputType & EditorInfo.TYPE_MASK_VARIATION;

		mModCtrl = false;
		mModAlt = false;
		mModFn = false;
		mEnteredText = null;
		mSuggestionForceOn = false;
		mSuggestionForceOff = false;
		mKeyboardModeOverridePortrait = 0;
		mKeyboardModeOverrideLandscape = 0;
		sKeyboardSettings.useExtension = false;

		switch (attribute.inputType & EditorInfo.TYPE_MASK_CLASS) {
			case EditorInfo.TYPE_CLASS_NUMBER:
			case EditorInfo.TYPE_CLASS_DATETIME:
				// fall through
				// NOTE: For now, we use the phone keyboard for NUMBER and DATETIME
				// until we get
				// a dedicated number entry keypad.
				// : Use a dedicated number entry keypad here when we get one.
			case EditorInfo.TYPE_CLASS_PHONE:
				mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_PHONE, attribute.imeOptions);
				break;

			case EditorInfo.TYPE_CLASS_TEXT:
				mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, attribute.imeOptions);
				// Make sure that passwords are not displayed in candidate view

				if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME
						|| !mLanguageSwitcher.allowAutoSpace()) {
					mAutoSpace = false;
				} else {
					mAutoSpace = true;
				}
				if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
					mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_EMAIL, attribute.imeOptions);

				} else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
					mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_URL, attribute.imeOptions);

				} else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
					mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_IM, attribute.imeOptions);

				} else if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
					mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_WEB, attribute.imeOptions);
				}
				break;
			default:
				mKeyboardSwitcher.setKeyboardMode(KeyboardSwitcher.MODE_TEXT, attribute.imeOptions/*, enableVoiceButton*/);
		}
		inputView.closing();
		resetPrediction();
		loadSettings();
		updateShiftKeyState(attribute);

		inputView.setPreviewEnabled(mPopupOn);
		inputView.setProximityCorrectionEnabled(true);

		if (TRACE)
			Debug.startMethodTracing("/data/trace/latinime");

		if (editor == null) {
			editor = createEditor();
		}

		if (wordDictionary == null) {
			wordDictionary = createWordDictionary(this);
		}

		if (phraseDictionary == null) {
			phraseDictionary = new PhraseDictionary(this);
		}

		if (editor != null) {
			editor.start(attribute.inputType);
			clearCandidates();
		}
	}

	@Override
	public void onFinishInput() {
		if (editor != null) {
			editor.clearComposingText(getCurrentInputConnection());
		}
		super.onFinishInput();

		LatinImeLogger.commit();
		onAutoCompletionStateChanged(false);

		if (mKeyboardSwitcher.getInputView() != null) {
			mKeyboardSwitcher.getInputView().closing();
		}
	}

	@Override
	public void onFinishInputView(boolean finishingInput) {
		if (editor != null) {
			editor.clearComposingText(getCurrentInputConnection());
		}
		super.onFinishInputView(finishingInput);

		// Remove penging messages related to update suggestions
		mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
		mHandler.removeMessages(MSG_UPDATE_OLD_SUGGESTIONS);
	}

	@Override
	public void onFinishCandidatesView(boolean finishingInput) {
		if (editor != null) {
			editor.clearComposingText(getCurrentInputConnection());
		}
		super.onFinishCandidatesView(finishingInput);
	}

	@Override
	public void onUnbindInput() {
		if (editor != null) {
			editor.clearComposingText(getCurrentInputConnection());
		}
		super.onUnbindInput();
	}

	@Override
	public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
		super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

		if (DEBUG) {
			Log.i(TAG, "onUpdateSelection: oss=" + oldSelStart + ", ose=" + oldSelEnd + ", nss=" + newSelStart + ", nse=" + newSelEnd + ", cs="
					+ candidatesStart + ", ce=" + candidatesEnd);
		}

		// If the current selection in the text view changes, we should
		// clear whatever candidate text we have.
		if ((/*((mComposing.length() > 0 && mPredicting) || mVoiceInputHighlighted)&&*/(newSelStart != candidatesEnd || newSelEnd != candidatesEnd) && mLastSelectionStart != newSelStart)) {
			mComposing.setLength(0);
			mComposingOriginal.setLength(0);
			// mPredicting = false;
			postUpdateSuggestions();
			TextEntryState.reset();
			InputConnection ic = getCurrentInputConnection();
			if (ic != null) {
				ic.finishComposingText();
			}
			// mVoiceInputHighlighted = false;
		} else if (/*!mPredicting && */!mJustAccepted) {
			switch (TextEntryState.getState()) {
				case ACCEPTED_DEFAULT:
					TextEntryState.reset();
					// fall through
				case SPACE_AFTER_PICKED:
					mJustAddedAutoSpace = false; // The user moved the cursor.
					break;
			}
		}
		mJustAccepted = false;

		// Make a note of the cursor position
		mLastSelectionStart = newSelStart;
		mLastSelectionEnd = newSelEnd;
	}

	@Override
	public void hideWindow() {
		LatinImeLogger.commit();
		onAutoCompletionStateChanged(false);

		if (TRACE)
			Debug.stopMethodTracing();
		if (mOptionsDialog != null && mOptionsDialog.isShowing()) {
			mOptionsDialog.dismiss();
			mOptionsDialog = null;
		}

		super.hideWindow();
		TextEntryState.endSession();
	}

	@Override
	public boolean onEvaluateInputViewShown() {
		boolean parent = super.onEvaluateInputViewShown();
		boolean wanted = mForceKeyboardOn || parent;
		// Log.i(TAG, "OnEvaluateInputViewShown, parent=" + parent + " + " wanted=" + wanted);
		return wanted;
	}

	@Override
	public void onComputeInsets(InputMethodService.Insets outInsets) {
		super.onComputeInsets(outInsets);
		if (!isFullscreenMode()) {
			outInsets.contentTopInsets = outInsets.visibleTopInsets;
		}
	}

	@Override
	public boolean onEvaluateFullscreenMode() {
		DisplayMetrics dm = getResources().getDisplayMetrics();
		float displayHeight = dm.heightPixels;
		// If the display is more than X inches high, don't go to fullscreen
		// mode
		float dimen = getResources().getDimension(R.dimen.max_height_for_fullscreen);
		if (displayHeight > dimen || mFullscreenOverride || isConnectbot()) {
			return false;
		} else {
			return super.onEvaluateFullscreenMode();
		}
	}

	public boolean isKeyboardVisible() {
		return (mKeyboardSwitcher != null && mKeyboardSwitcher.getInputView() != null && mKeyboardSwitcher.getInputView().isShown());
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_BACK:
				if (event.getRepeatCount() == 0 && mKeyboardSwitcher.getInputView() != null) {
					if (mKeyboardSwitcher.getInputView().handleBack()) {
						return true;
					}
				}
				break;
			case KeyEvent.KEYCODE_DPAD_DOWN:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_LEFT:
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				break;
			case KeyEvent.KEYCODE_VOLUME_UP:
				if (!mVolUpAction.equals("none") && isKeyboardVisible()) {
					return true;
				}
				break;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				if (!mVolDownAction.equals("none") && isKeyboardVisible()) {
					return true;
				}
				break;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_DPAD_DOWN:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_LEFT:
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
				// Enable shift key and DPAD to do selections
				if (inputView != null && inputView.isShown() && inputView.getShiftState() == Keyboard.SHIFT_ON) {
					event = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(), event.getKeyCode(), event.getRepeatCount(),
							event.getDeviceId(), event.getScanCode(), KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_ON);
					InputConnection ic = getCurrentInputConnection();
					if (ic != null)
						ic.sendKeyEvent(event);
					return true;
				}
				break;
			case KeyEvent.KEYCODE_VOLUME_UP:
				if (!mVolUpAction.equals("none") && isKeyboardVisible()) {
					return doSwipeAction(mVolUpAction);
				}
				break;
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				if (!mVolDownAction.equals("none") && isKeyboardVisible()) {
					return doSwipeAction(mVolDownAction);
				}
				break;
		}
		return super.onKeyUp(keyCode, event);
	}

	private void reloadKeyboards() {
		mKeyboardSwitcher.setLanguageSwitcher(mLanguageSwitcher);
		if (mKeyboardSwitcher.getInputView() != null && mKeyboardSwitcher.getKeyboardMode() != KeyboardSwitcher.MODE_NONE) {
			// mKeyboardSwitcher.setVoiceMode(mEnableVoice && mEnableVoiceButton, mVoiceOnPrimary);
		}
		updateKeyboardOptions();
		mKeyboardSwitcher.makeKeyboards(true);
	}

	public void updateShiftKeyState(EditorInfo attr) {
		InputConnection ic = getCurrentInputConnection();
		if (ic != null && attr != null && mKeyboardSwitcher.isAlphabetMode()) {
			int oldState = getShiftState();
			boolean isShifted = mShiftKeyState.isMomentary();
			boolean isCapsLock = (oldState == Keyboard.SHIFT_CAPS_LOCKED || oldState == Keyboard.SHIFT_LOCKED);
			boolean isCaps = isCapsLock || getCursorCapsMode(ic, attr) != 0;
			// Log.i(TAG, "updateShiftKeyState isShifted=" + isShifted + " isCaps=" + isCaps + " isMomentary=" + mShiftKeyState.isMomentary() + " cursorCaps=" +
			// getCursorCapsMode(ic, attr));
			int newState = Keyboard.SHIFT_OFF;
			if (isShifted) {
				newState = (mSavedShiftState == Keyboard.SHIFT_LOCKED) ? Keyboard.SHIFT_CAPS : Keyboard.SHIFT_ON;
			} else if (isCaps) {
				newState = isCapsLock ? getCapsOrShiftLockState() : Keyboard.SHIFT_CAPS;
			}
			// Log.i(TAG, "updateShiftKeyState " + oldState + " -> " + newState);
			mKeyboardSwitcher.setShiftState(newState);
		}
		if (ic != null) {
			// Clear modifiers other than shift, to avoid them getting stuck
			int states = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON | 0x8 // KeyEvent.META_FUNCTION_ON;
					| 0x7000 // KeyEvent.META_CTRL_MASK
					| 0x70000 // KeyEvent.META_META_MASK
					| KeyEvent.META_SYM_ON;
			ic.clearMetaKeyStates(states);
		}
	}

	private int getShiftState() {
		if (mKeyboardSwitcher != null) {
			LatinKeyboardView view = mKeyboardSwitcher.getInputView();
			if (view != null) {
				return view.getShiftState();
			}
		}
		return Keyboard.SHIFT_OFF;
	}

	private int getCursorCapsMode(InputConnection ic, EditorInfo attr) {
		int caps = 0;
		EditorInfo ei = getCurrentInputEditorInfo();
		if (mAutoCapActive && ei != null && ei.inputType != EditorInfo.TYPE_NULL) {
			caps = ic.getCursorCapsMode(attr.inputType);
		}
		return caps;
	}

	private void swapPunctuationAndSpace() {
		final InputConnection ic = getCurrentInputConnection();
		if (ic == null)
			return;
		CharSequence lastTwo = ic.getTextBeforeCursor(2, 0);
		if (lastTwo != null && lastTwo.length() == 2 && lastTwo.charAt(0) == ASCII_SPACE && isSentenceSeparator(lastTwo.charAt(1))) {
			ic.beginBatchEdit();
			ic.deleteSurroundingText(2, 0);
			ic.commitText(lastTwo.charAt(1) + " ", 1);
			ic.endBatchEdit();
			updateShiftKeyState(getCurrentInputEditorInfo());
			mJustAddedAutoSpace = true;
		}
	}

	private void reswapPeriodAndSpace() {
		final InputConnection ic = getCurrentInputConnection();
		if (ic == null)
			return;
		CharSequence lastThree = ic.getTextBeforeCursor(3, 0);
		if (lastThree != null && lastThree.length() == 3 && lastThree.charAt(0) == ASCII_PERIOD && lastThree.charAt(1) == ASCII_SPACE
				&& lastThree.charAt(2) == ASCII_PERIOD) {
			ic.beginBatchEdit();
			ic.deleteSurroundingText(3, 0);
			ic.commitText(" ..", 1);
			ic.endBatchEdit();
			updateShiftKeyState(getCurrentInputEditorInfo());
		}
	}

	private void maybeRemovePreviousPeriod(CharSequence text) {
		final InputConnection ic = getCurrentInputConnection();
		if (ic == null || text.length() == 0)
			return;

		// When the text's first character is '.', remove the previous period
		// if there is one.
		CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
		if (lastOne != null && lastOne.length() == 1 && lastOne.charAt(0) == ASCII_PERIOD && text.charAt(0) == ASCII_PERIOD) {
			ic.deleteSurroundingText(1, 0);
		}
	}

	private void removeTrailingSpace() {
		final InputConnection ic = getCurrentInputConnection();
		if (ic == null)
			return;

		CharSequence lastOne = ic.getTextBeforeCursor(1, 0);
		if (lastOne != null && lastOne.length() == 1 && lastOne.charAt(0) == ASCII_SPACE) {
			ic.deleteSurroundingText(1, 0);
		}
	}

	private void showInputMethodPicker() {
		((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
	}

	private void onOptionKeyLongPressed() {
		if (!isShowingOptionDialog()) {
			showInputMethodPicker();
		}
	}

	private boolean isShowingOptionDialog() {
		return mOptionsDialog != null && mOptionsDialog.isShowing();
	}

	private boolean isConnectbot() {
		EditorInfo ei = getCurrentInputEditorInfo();
		String pkg = ei.packageName;
		if (ei == null || pkg == null)
			return false;
		return ((pkg.equalsIgnoreCase("org.connectbot") || pkg.equalsIgnoreCase("org.woltage.irssiconnectbot") || pkg.equalsIgnoreCase("com.pslib.connectbot") || pkg
				.equalsIgnoreCase("sk.vx.connectbot")) && ei.inputType == 0); //
	}

	private int getMetaState(boolean shifted) {
		int meta = 0;
		if (shifted)
			meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
		if (mModCtrl)
			meta |= 0x3000; // KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
		if (mModAlt)
			meta |= 0x30000; // KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
		return meta;
	}

	private void sendKeyDown(InputConnection ic, int key, int meta) {
		long now = System.currentTimeMillis();
		if (ic != null)
			ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta));
	}

	private void sendKeyUp(InputConnection ic, int key, int meta) {
		long now = System.currentTimeMillis();
		if (ic != null)
			ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta));
	}

	private void sendModifiedKeyDownUp(int key, boolean shifted) {
		InputConnection ic = getCurrentInputConnection();
		int meta = getMetaState(shifted);
		sendModifierKeysDown(shifted);
		sendKeyDown(ic, key, meta);
		sendKeyUp(ic, key, meta);
		sendModifierKeysUp(shifted);
	}

	private boolean isShiftMod() {
		if (mShiftKeyState.isMomentary())
			return true;
		if (mKeyboardSwitcher != null) {
			LatinKeyboardView kb = mKeyboardSwitcher.getInputView();
			if (kb != null)
				return kb.isShiftAll();
		}
		return false;
	}

	private boolean delayChordingCtrlModifier() {
		return sKeyboardSettings.chordingCtrlKey == 0;
	}

	private boolean delayChordingAltModifier() {
		return sKeyboardSettings.chordingAltKey == 0;
	}

	private void sendModifiedKeyDownUp(int key) {
		sendModifiedKeyDownUp(key, isShiftMod());
	}

	private void sendShiftKey(InputConnection ic, boolean isDown) {
		int key = KeyEvent.KEYCODE_SHIFT_LEFT;
		int meta = KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
		if (isDown) {
			sendKeyDown(ic, key, meta);
		} else {
			sendKeyUp(ic, key, meta);
		}
	}

	private void sendCtrlKey(InputConnection ic, boolean isDown, boolean chording) {
		if (chording && delayChordingCtrlModifier())
			return;

		int key = sKeyboardSettings.chordingCtrlKey;
		if (key == 0)
			key = 113; // KeyEvent.KEYCODE_CTRL_LEFT
		int meta = 0x3000; // KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON
		if (isDown) {
			sendKeyDown(ic, key, meta);
		} else {
			sendKeyUp(ic, key, meta);
		}
	}

	private void sendAltKey(InputConnection ic, boolean isDown, boolean chording) {
		if (chording && delayChordingAltModifier())
			return;

		int key = sKeyboardSettings.chordingAltKey;
		if (key == 0)
			key = 57; // KeyEvent.KEYCODE_ALT_LEFT
		int meta = 0x30000; // KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
		if (isDown) {
			sendKeyDown(ic, key, meta);
		} else {
			sendKeyUp(ic, key, meta);
		}
	}

	private void sendModifierKeysDown(boolean shifted) {
		InputConnection ic = getCurrentInputConnection();
		if (shifted) {
			// Log.i(TAG, "send SHIFT down");
			sendShiftKey(ic, true);
		}
		if (mModCtrl && (!mCtrlKeyState.isMomentary() || delayChordingCtrlModifier())) {
			sendCtrlKey(ic, true, false);
		}
		if (mModAlt && (!mAltKeyState.isMomentary() || delayChordingAltModifier())) {
			sendAltKey(ic, true, false);
		}
	}

	private void handleModifierKeysUp(boolean shifted, boolean sendKey) {
		InputConnection ic = getCurrentInputConnection();
		if (mModAlt && !mAltKeyState.isMomentary()) {
			if (sendKey)
				sendAltKey(ic, false, false);
			setModAlt(false);
		}
		if (mModCtrl && !mCtrlKeyState.isMomentary()) {
			if (sendKey)
				sendCtrlKey(ic, false, false);
			setModCtrl(false);
		}
		if (shifted) {
			// Log.i(TAG, "send SHIFT up");
			if (sendKey)
				sendShiftKey(ic, false);
			int shiftState = getShiftState();
			if (!(mShiftKeyState.isMomentary() || shiftState == Keyboard.SHIFT_LOCKED)) {
				resetShift();
			}
		}
	}

	private void sendModifierKeysUp(boolean shifted) {
		handleModifierKeysUp(shifted, true);
	}

	private void sendSpecialKey(int code) {
		if (!isConnectbot()) {
			sendModifiedKeyDownUp(code);
			return;
		}

		// (klausw): properly support xterm sequences for Ctrl/Alt modifiers?
		// See http://slackware.osuosl.org/slackware-12.0/source/l/ncurses/xterm.terminfo
		// and the output of "$ infocmp -1L". Support multiple sets, and optional
		// true numpad keys?
		if (ESC_SEQUENCES == null) {
			ESC_SEQUENCES = new HashMap<Integer, String>();
			CTRL_SEQUENCES = new HashMap<Integer, Integer>();

			// VT escape sequences without leading Escape
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_HOME, "[1~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_END, "[4~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_PAGE_UP, "[5~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_PAGE_DOWN, "[6~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F1, "OP");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F2, "OQ");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F3, "OR");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F4, "OS");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F5, "[15~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F6, "[17~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F7, "[18~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F8, "[19~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F9, "[20~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F10, "[21~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F11, "[23~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F12, "[24~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FORWARD_DEL, "[3~");
			ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_INSERT, "[2~");

			// Special ConnectBot hack: Ctrl-1 to Ctrl-0 for F1-F10.
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F1, KeyEvent.KEYCODE_1);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F2, KeyEvent.KEYCODE_2);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F3, KeyEvent.KEYCODE_3);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F4, KeyEvent.KEYCODE_4);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F5, KeyEvent.KEYCODE_5);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F6, KeyEvent.KEYCODE_6);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F7, KeyEvent.KEYCODE_7);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F8, KeyEvent.KEYCODE_8);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F9, KeyEvent.KEYCODE_9);
			CTRL_SEQUENCES.put(-LatinKeyboardView.KEYCODE_FKEY_F10, KeyEvent.KEYCODE_0);
		}
		InputConnection ic = getCurrentInputConnection();
		Integer ctrlseq = null;
		if (mConnectbotTabHack) {
			ctrlseq = CTRL_SEQUENCES.get(code);
		}
		String seq = ESC_SEQUENCES.get(code);

		if (ctrlseq != null) {
			if (mModAlt) {
				// send ESC prefix for "Meta"
				ic.commitText(Character.toString((char)27), 1);
			}
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, ctrlseq));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, ctrlseq));
		} else if (seq != null) {
			if (mModAlt) {
				// send ESC prefix for "Meta"
				ic.commitText(Character.toString((char)27), 1);
			}
			// send ESC prefix of escape sequence
			ic.commitText(Character.toString((char)27), 1);
			ic.commitText(seq, 1);
		} else {
			// send key code, let connectbot handle it
			sendDownUpKeyEvents(code);
		}
		handleModifierKeysUp(false, false);
	}

	private final static int asciiToKeyCode[] = new int[127];
	private final static int KF_MASK = 0xffff;
	private final static int KF_SHIFTABLE = 0x10000;
	private final static int KF_UPPER = 0x20000;
	private final static int KF_LETTER = 0x40000;

	{
		// Include RETURN in this set even though it's not printable.
		// Most other non-printable keys get handled elsewhere.
		asciiToKeyCode['\n'] = KeyEvent.KEYCODE_ENTER | KF_SHIFTABLE;

		// Non-alphanumeric ASCII codes which have their own keys
		// (on some keyboards)
		asciiToKeyCode[' '] = KeyEvent.KEYCODE_SPACE | KF_SHIFTABLE;
		// asciiToKeyCode['!'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['"'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['#'] = KeyEvent.KEYCODE_POUND;
		// asciiToKeyCode['$'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['%'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['&'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['\''] = KeyEvent.KEYCODE_APOSTROPHE;
		// asciiToKeyCode['('] = KeyEvent.KEYCODE_;
		// asciiToKeyCode[')'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['*'] = KeyEvent.KEYCODE_STAR;
		asciiToKeyCode['+'] = KeyEvent.KEYCODE_PLUS;
		asciiToKeyCode[','] = KeyEvent.KEYCODE_COMMA;
		asciiToKeyCode['-'] = KeyEvent.KEYCODE_MINUS;
		asciiToKeyCode['.'] = KeyEvent.KEYCODE_PERIOD;
		asciiToKeyCode['/'] = KeyEvent.KEYCODE_SLASH;
		// asciiToKeyCode[':'] = KeyEvent.KEYCODE_;
		asciiToKeyCode[';'] = KeyEvent.KEYCODE_SEMICOLON;
		// asciiToKeyCode['<'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['='] = KeyEvent.KEYCODE_EQUALS;
		// asciiToKeyCode['>'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['?'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['@'] = KeyEvent.KEYCODE_AT;
		asciiToKeyCode['['] = KeyEvent.KEYCODE_LEFT_BRACKET;
		asciiToKeyCode['\\'] = KeyEvent.KEYCODE_BACKSLASH;
		asciiToKeyCode[']'] = KeyEvent.KEYCODE_RIGHT_BRACKET;
		// asciiToKeyCode['^'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['_'] = KeyEvent.KEYCODE_;
		asciiToKeyCode['`'] = KeyEvent.KEYCODE_GRAVE;
		// asciiToKeyCode['{'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['|'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['}'] = KeyEvent.KEYCODE_;
		// asciiToKeyCode['~'] = KeyEvent.KEYCODE_;

		for (int i = 0; i <= 25; ++i) {
			asciiToKeyCode['a' + i] = KeyEvent.KEYCODE_A + i | KF_LETTER;
			asciiToKeyCode['A' + i] = KeyEvent.KEYCODE_A + i | KF_UPPER | KF_LETTER;
		}

		for (int i = 0; i <= 9; ++i) {
			asciiToKeyCode['0' + i] = KeyEvent.KEYCODE_0 + i;
		}
	}

	public void sendModifiableKeyChar(char ch) {
		// Support modified key events
		boolean modShift = isShiftMod();
		if ((modShift || mModCtrl || mModAlt) && ch > 0 && ch < 127) {
			InputConnection ic = getCurrentInputConnection();
			if (isConnectbot()) {
				if (mModAlt) {
					// send ESC prefix
					ic.commitText(Character.toString((char)27), 1);
				}
				if (mModCtrl) {
					int code = ch & 31;
					if (code == 9) {
						sendTab();
					} else {
						ic.commitText(Character.toString((char)code), 1);
					}
				} else {
					ic.commitText(Character.toString(ch), 1);
				}
				handleModifierKeysUp(false, false);
				return;
			}

			// Non-ConnectBot

			// Restrict Shift modifier to ENTER and SPACE, supporting Shift-Enter etc.
			// Note that most special keys such as DEL or cursor keys aren't handled
			// by this charcode-based method.

			int combinedCode = asciiToKeyCode[ch];
			if (combinedCode > 0) {
				int code = combinedCode & KF_MASK;
				boolean shiftable = (combinedCode & KF_SHIFTABLE) > 0;
				boolean upper = (combinedCode & KF_UPPER) > 0;
				boolean letter = (combinedCode & KF_LETTER) > 0;
				boolean shifted = modShift && (upper || shiftable);
				if (letter && !mModCtrl && !mModAlt) {
					// Try workaround for issue 179 where letters don't get upcased
					ic.commitText(Character.toString(ch), 1);
					handleModifierKeysUp(false, false);
				} else {
					sendModifiedKeyDownUp(code, shifted);
				}
				return;
			}
		}

		if (ch >= '0' && ch <= '9') {
			// WIP
			InputConnection ic = getCurrentInputConnection();
			ic.clearMetaKeyStates(KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_SYM_ON);
		}

		// Default handling for anything else, including unmodified ENTER and SPACE.
		boolean newSpace = ch == ' ' && mComposing.length() == 0;

		if (listComposingKeyboards.contains(mKeyboardSwitcher.getCurrentKeyboardXML()) && ch != '\n' && !newSpace) {
			if (composingSpaceAutoCommit && ch == ' ') {
				commitText(mComposing.toString());
				sendKeyChar(ch);

			} else {
				mComposing.append(ch);
				mComposingOriginal.append(ch);
				getCurrentInputConnection().setComposingText(mComposing, 1);

				if (editor != null) {
					editor.setComposing(mComposingOriginal.toString());
				}

				if (candidatesContainer != null && editor != null) {
					// Set the candidates for the updated composing-text and provide default
					// highlight for the word candidates.
					String words = "";
					String wordCandidates = wordDictionary.getWords(mComposingOriginal);
					String phraseCandidates = phraseDictionary.getFollowingWords(ch);

					if (!wordCandidates.equalsIgnoreCase("")) {
						words += wordCandidates;
					}

					if (!phraseCandidates.equalsIgnoreCase("")) {
						if (words.equalsIgnoreCase("")) {
							words += phraseCandidates;
						} else {
							words += "|" + phraseCandidates;
						}
					}

					setCandidates(words, false);
				}
			}
		} else {
			sendKeyChar(ch);
		}
	}

	private void sendTab() {
		InputConnection ic = getCurrentInputConnection();
		boolean tabHack = isConnectbot() && mConnectbotTabHack;

		// : tab and ^I don't work in connectbot, hackish workaround
		if (tabHack) {
			if (mModAlt) {
				// send ESC prefix
				ic.commitText(Character.toString((char)27), 1);
			}
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I));
			ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I));
		} else {
			sendModifiedKeyDownUp(KeyEvent.KEYCODE_TAB);
		}
	}

	private void sendEscape() {
		if (isConnectbot()) {
			sendKeyChar((char)27);
		} else {
			sendModifiedKeyDownUp(111);
		}
	}

	private boolean processMultiKey(int primaryCode) {
		if (mDeadAccentBuffer.composeBuffer.length() > 0) {
			// Log.i(TAG, "processMultiKey: pending DeadAccent, length=" + mDeadAccentBuffer.composeBuffer.length());
			mDeadAccentBuffer.execute(primaryCode);
			mDeadAccentBuffer.clear();
			return true;
		}
		if (mComposeMode) {
			mComposeMode = mComposeBuffer.execute(primaryCode);
			return true;
		}
		return false;
	}

	// Implementation of KeyboardViewListener

	public void onKey(int primaryCode, int[] keyCodes, int x, int y) {
		long when = SystemClock.uptimeMillis();
		if (primaryCode != Keyboard.KEYCODE_DELETE || when > mLastKeyTime + QUICK_PRESS) {
			mDeleteCount = 0;
		}
		mLastKeyTime = when;
		final boolean distinctMultiTouch = mKeyboardSwitcher.hasDistinctMultitouch();
		String oldComposing = mComposingOriginal.toString();
		String translatedComposing = "";
		StringBuilder candidates = new StringBuilder();

		switch (primaryCode) {
			case Keyboard.KEYCODE_JA_HIRAGANA:
				clearCandidates();
				setCandidatesViewShown(false);

				translatedComposing = RomaToHK.roma2hk(oldComposing, Keyboard.KEYCODE_JA_HIRAGANA).toString();

				if (translatedComposing.equalsIgnoreCase(mComposing.toString())) {
					mComposing.setLength(0);
					mComposing.append(oldComposing);
				} else {
					mComposing.setLength(0);
					mComposing.append(translatedComposing);
				}

				getCurrentInputConnection().setComposingText(mComposing, 1);
				break;

			case Keyboard.KEYCODE_JA_KATAKANA:
				clearCandidates();
				setCandidatesViewShown(false);

				translatedComposing = RomaToHK.roma2hk(oldComposing, Keyboard.KEYCODE_JA_KATAKANA).toString();

				if (translatedComposing.equalsIgnoreCase(mComposing.toString())) {
					mComposing.setLength(0);
					mComposing.append(oldComposing);
				} else {
					mComposing.setLength(0);
					mComposing.append(translatedComposing);
				}

				getCurrentInputConnection().setComposingText(mComposing, 1);
				break;

			case Keyboard.KEYCODE_JA_KANJI:
				KanjiTask task = new KanjiTask();

				task.execute(mComposingOriginal.toString());
				setCandidatesViewShown(true);

				break;

			case Keyboard.KEYCODE_COMPOSING_OK:
				clearCandidates();
				setCandidatesViewShown(false);

				getCurrentInputConnection().commitText(mComposing, 1);
				mComposing.setLength(0);
				mComposingOriginal.setLength(0);
				break;

			case Keyboard.KEYCODE_CH_PINYIN:
				for (int kln = 0; kln < PINYIN_ENML.pml.length; kln++) {
					if (PINYIN_ENML.pml[kln][PINYIN_ENML.pml[kln].length - 1][0].compareTo(mComposingOriginal.toString()) <= 0)
						continue;
					for (int i = 0; i < PINYIN_ENML.pml[kln].length; i++) {
						if (mComposingOriginal.toString().equalsIgnoreCase(PINYIN_ENML.pml[kln][i][0]))
							candidates.append(PINYIN_ENML.pml[kln][i][1] + "|");
					}
				}

				if (candidates.length() > 1) {
					candidates = new StringBuilder(candidates.substring(0, candidates.length() - 1));
				}

				if (candidates.length() == 0) {
					candidates = new StringBuilder();
				}

				setCandidatesViewShown(true);
				setCandidates(candidates.toString(), false);
				setCandidatesViewShown(true);
				break;

			case Keyboard.KEYCODE_CH_WUBI:
				for (int wln = 0; wln < WUBI_ENML.wml.length; wln++) {
					if (WUBI_ENML.wml[wln][WUBI_ENML.wml[wln].length - 1][0].toLowerCase().compareTo(mComposingOriginal.toString().toLowerCase()) < 0)
						continue;
					for (int i = 0; i < WUBI_ENML.wml[wln].length; i++) {
						if (mComposingOriginal.toString().equalsIgnoreCase(WUBI_ENML.wml[wln][i][0]))
							candidates.append(WUBI_ENML.wml[wln][i][1] + "|");
					}
				}

				if (candidates.length() > 1) {
					candidates = new StringBuilder(candidates.substring(0, candidates.length() - 1));
				}

				if (candidates.length() == 0) {
					candidates.append("");
				}

				setCandidatesViewShown(true);
				setCandidates(candidates.toString(), false);
				setCandidatesViewShown(true);
				break;

			case Keyboard.KEYCODE_EXTRA_KBD1:
				clearCandidates();
				setCandidatesViewShown(false);

				// getCurrentInputConnection().commitText(mComposing, 1);
				// mComposing.setLength(0);
				// mComposingOriginal.setLength(0);

				mKeyboardSwitcher.switchKeyboard(mExtraKeyboard1);
				editor = createEditor();
				wordDictionary = createWordDictionary(this);

				editor.start(InputType.TYPE_CLASS_TEXT);
				clearCandidates();

				break;

			case Keyboard.KEYCODE_EXTRA_KBD2:
				clearCandidates();
				setCandidatesViewShown(false);

				// getCurrentInputConnection().commitText(mComposing, 1);
				// mComposing.setLength(0);
				// mComposingOriginal.setLength(0);

				mKeyboardSwitcher.switchKeyboard(mExtraKeyboard2);
				editor = createEditor();
				wordDictionary = createWordDictionary(this);

				editor.start(InputType.TYPE_CLASS_TEXT);
				clearCandidates();

				break;

			case Keyboard.KEYCODE_EXTRA_KBDRETURN:
				clearCandidates();
				setCandidatesViewShown(false);

				// getCurrentInputConnection().commitText(mComposing, 1);
				// mComposing.setLength(0);
				// mComposingOriginal.setLength(0);

				mKeyboardSwitcher.switchKeyboard(mUseThisKeyboard);
				editor = createEditor();
				wordDictionary = createWordDictionary(this);

				if (editor != null) {
					editor.start(InputType.TYPE_CLASS_TEXT);
					clearCandidates();
				}
				break;

			case Keyboard.KEYCODE_DELETE:
				if (processMultiKey(primaryCode)) {
					break;
				}

				if (mComposing.length() > 0) {
					mComposing.deleteCharAt(mComposing.length() - 1);
				}

				if (mComposingOriginal.length() > 0) {
					mComposingOriginal.deleteCharAt(mComposingOriginal.length() - 1);
				}

				handleBackspace();
				mDeleteCount++;
				LatinImeLogger.logOnDelete();

				if (candidatesContainer != null && editor != null) {
					// Set the candidates for the updated composing-text and provide default
					// highlight for the word candidates.
					String words = "";
					String wordCandidates = wordDictionary.getWords(mComposingOriginal);
					String phraseCandidates = "";

					if (mComposingOriginal.length() == 1) {
						phraseCandidates = phraseDictionary.getFollowingWords(mComposingOriginal.charAt(0));
					}

					if (!wordCandidates.equalsIgnoreCase("")) {
						words += wordCandidates;
					}

					if (!phraseCandidates.equalsIgnoreCase("")) {
						if (words.equalsIgnoreCase("")) {
							words += phraseCandidates;
						} else {
							words += "|" + phraseCandidates;
						}
					}

					setCandidates(words, false);
				}

				break;

			case Keyboard.KEYCODE_SHIFT:
				// Shift key is handled in onPress() when device has distinct
				// multi-touch panel.
				if (!distinctMultiTouch) {
					handleShift();
				}
				break;

			case Keyboard.KEYCODE_MODE_CHANGE:
				// Symbol key is handled in onPress() when device has distinct
				// multi-touch panel.
				if (!distinctMultiTouch)
					changeKeyboardMode();
				break;
			case LatinKeyboardView.KEYCODE_CTRL_LEFT:
				// Ctrl key is handled in onPress() when device has distinct
				// multi-touch panel.
				if (!distinctMultiTouch)
					setModCtrl(!mModCtrl);
				break;
			case LatinKeyboardView.KEYCODE_ALT_LEFT:
				// Alt key is handled in onPress() when device has distinct
				// multi-touch panel.
				if (!distinctMultiTouch)
					setModAlt(!mModAlt);
				break;
			case LatinKeyboardView.KEYCODE_FN:
				if (!distinctMultiTouch)
					setModFn(!mModFn);
				break;
			case Keyboard.KEYCODE_CANCEL:
				if (!isShowingOptionDialog()) {
					handleClose();
				}
				break;
			case LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS:
				onOptionKeyLongPressed();
				break;
			case LatinKeyboardView.KEYCODE_COMPOSE:
				mComposeMode = !mComposeMode;
				mComposeBuffer.clear();
				break;
			case LatinKeyboardView.KEYCODE_NEXT_LANGUAGE:
				toggleLanguage(false, true);
				break;
			case LatinKeyboardView.KEYCODE_PREV_LANGUAGE:
				toggleLanguage(false, false);
				break;
			case 9 /* Tab */:
				if (processMultiKey(primaryCode)) {
					break;
				}
				sendTab();
				break;
			case LatinKeyboardView.KEYCODE_ESCAPE:
				if (processMultiKey(primaryCode)) {
					break;
				}
				sendEscape();
				break;
			case LatinKeyboardView.KEYCODE_DPAD_UP:
			case LatinKeyboardView.KEYCODE_DPAD_DOWN:
			case LatinKeyboardView.KEYCODE_DPAD_LEFT:
			case LatinKeyboardView.KEYCODE_DPAD_RIGHT:
			case LatinKeyboardView.KEYCODE_DPAD_CENTER:
			case LatinKeyboardView.KEYCODE_HOME:
			case LatinKeyboardView.KEYCODE_END:
			case LatinKeyboardView.KEYCODE_PAGE_UP:
			case LatinKeyboardView.KEYCODE_PAGE_DOWN:
			case LatinKeyboardView.KEYCODE_FKEY_F1:
			case LatinKeyboardView.KEYCODE_FKEY_F2:
			case LatinKeyboardView.KEYCODE_FKEY_F3:
			case LatinKeyboardView.KEYCODE_FKEY_F4:
			case LatinKeyboardView.KEYCODE_FKEY_F5:
			case LatinKeyboardView.KEYCODE_FKEY_F6:
			case LatinKeyboardView.KEYCODE_FKEY_F7:
			case LatinKeyboardView.KEYCODE_FKEY_F8:
			case LatinKeyboardView.KEYCODE_FKEY_F9:
			case LatinKeyboardView.KEYCODE_FKEY_F10:
			case LatinKeyboardView.KEYCODE_FKEY_F11:
			case LatinKeyboardView.KEYCODE_FKEY_F12:
			case LatinKeyboardView.KEYCODE_FORWARD_DEL:
			case LatinKeyboardView.KEYCODE_INSERT:
			case LatinKeyboardView.KEYCODE_SYSRQ:
			case LatinKeyboardView.KEYCODE_BREAK:
			case LatinKeyboardView.KEYCODE_NUM_LOCK:
			case LatinKeyboardView.KEYCODE_SCROLL_LOCK:
				if (processMultiKey(primaryCode)) {
					break;
				}
				// send as plain keys, or as escape sequence if needed
				sendSpecialKey(-primaryCode);
				break;
			default:
				if (!mComposeMode && mDeadKeysActive && Character.getType(primaryCode) == Character.NON_SPACING_MARK) {
					// Log.i(TAG, "possible dead character: " + primaryCode);
					if (!mDeadAccentBuffer.execute(primaryCode)) {
						// Log.i(TAG, "double dead key");
						break; // pressing a dead key twice produces spacing equivalent
					}
					updateShiftKeyState(getCurrentInputEditorInfo());
					break;
				}
				if (processMultiKey(primaryCode)) {
					break;
				}
				if (primaryCode != ASCII_ENTER) {
					mJustAddedAutoSpace = false;
				}
				RingCharBuffer.getInstance().push((char)primaryCode, x, y);
				LatinImeLogger.logOnInputChar();
				if (isWordSeparator(primaryCode)) {
					handleSeparator(primaryCode);
				} else {
					// Korean keyboard keys are handled here. This is how compound characters are constructed
					if (mKeyboardSwitcher.getCurrentKeyboardXML() == R.xml.kbd_qwerty_korean) {
						handleHangul(primaryCode, keyCodes);
					} else {
						handleCharacter(primaryCode, keyCodes);
					}
				}
		}
		mKeyboardSwitcher.onKey(primaryCode);
		// Reset after any single keystroke
		mEnteredText = null;
		// mDeadAccentBuffer.clear(); //
		Log.d("", "Composing Texts End: " + mComposing.toString() + "; " + mComposingOriginal.toString());
	}

	public void onText(CharSequence text) {
		// if (VOICE_INSTALLED && mVoiceInputHighlighted) {
		// commitVoiceInput();
		// }
		// mDeadAccentBuffer.clear(); //
		InputConnection ic = getCurrentInputConnection();
		if (ic == null)
			return;
		abortCorrection(false);
		ic.beginBatchEdit();
		// if (mPredicting) {
		// commitTyped(ic, true);
		// }
		maybeRemovePreviousPeriod(text);
		ic.commitText(text, 1);
		ic.endBatchEdit();
		updateShiftKeyState(getCurrentInputEditorInfo());
		mKeyboardSwitcher.onKey(0); // dummy key code.
		mJustAddedAutoSpace = false;
		mEnteredText = text;
	}

	public void onCancel() {
		// User released a finger outside any key
		mKeyboardSwitcher.onCancelInput();
	}

	private void handleBackspace() {
		boolean deleteChar = false;
		InputConnection ic = getCurrentInputConnection();
		if (ic == null) {
			return;
		}

		ic.beginBatchEdit();

		deleteChar = true;

		TextEntryState.backspace();

		if (TextEntryState.getState() == TextEntryState.State.UNDO_COMMIT) {
			revertLastWord(deleteChar);
			ic.endBatchEdit();
			return;

		} else if (mEnteredText != null && sameAsTextBeforeCursor(ic, mEnteredText)) {
			ic.deleteSurroundingText(mEnteredText.length(), 0);

		} else if (deleteChar) {
			sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
			if (mDeleteCount > DELETE_ACCELERATE_AT) {
				sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
			}
		}

		if (editor != null) {
			// editor.deleteLastComposingChar(ic, mComposingOriginal);
			editor.setComposing(mComposingOriginal.toString());
		}

		ic.endBatchEdit();
	}

	private void setModCtrl(boolean val) {
		// Log.i("LatinIME", "setModCtrl "+ mModCtrl + "->" + val + ", momentary=" + mCtrlKeyState.isMomentary());
		mKeyboardSwitcher.setCtrlIndicator(val);
		mModCtrl = val;
	}

	private void setModAlt(boolean val) {
		// Log.i("LatinIME", "setModAlt "+ mModAlt + "->" + val + ", momentary=" + mAltKeyState.isMomentary());
		mKeyboardSwitcher.setAltIndicator(val);
		mModAlt = val;
	}

	private void setModFn(boolean val) {
		// Log.i("LatinIME", "setModFn " + mModFn + "->" + val + ", momentary=" + mFnKeyState.isMomentary());
		mModFn = val;
		mKeyboardSwitcher.setFn(val);
		mKeyboardSwitcher.setCtrlIndicator(mModCtrl);
		mKeyboardSwitcher.setAltIndicator(mModAlt);
	}

	private void startMultitouchShift() {
		int newState = Keyboard.SHIFT_ON;
		if (mKeyboardSwitcher.isAlphabetMode()) {
			mSavedShiftState = getShiftState();
			if (mSavedShiftState == Keyboard.SHIFT_LOCKED)
				newState = Keyboard.SHIFT_CAPS;
		}
		handleShiftInternal(true, newState);
	}

	private void commitMultitouchShift() {
		if (mKeyboardSwitcher.isAlphabetMode()) {
			int newState = nextShiftState(mSavedShiftState, true);
			handleShiftInternal(true, newState);
		} else {
			// do nothing, keyboard is already flipped
		}
	}

	private void resetMultitouchShift() {
		int newState = Keyboard.SHIFT_OFF;
		if (mSavedShiftState == Keyboard.SHIFT_CAPS_LOCKED || mSavedShiftState == Keyboard.SHIFT_LOCKED) {
			newState = mSavedShiftState;
		}
		handleShiftInternal(true, newState);
	}

	private void resetShift() {
		handleShiftInternal(true, Keyboard.SHIFT_OFF);
	}

	private void handleShift() {
		handleShiftInternal(false, -1);
	}

	private static int getCapsOrShiftLockState() {
		return sKeyboardSettings.capsLock ? Keyboard.SHIFT_CAPS_LOCKED : Keyboard.SHIFT_LOCKED;
	}

	// Rotate through shift states by successively pressing and releasing the Shift key.
	private static int nextShiftState(int prevState, boolean allowCapsLock) {
		if (allowCapsLock) {
			if (prevState == Keyboard.SHIFT_OFF) {
				return Keyboard.SHIFT_ON;
			} else if (prevState == Keyboard.SHIFT_ON) {
				return getCapsOrShiftLockState();
			} else {
				return Keyboard.SHIFT_OFF;
			}
		} else {
			// currently unused, see toggleShift()
			if (prevState == Keyboard.SHIFT_OFF) {
				return Keyboard.SHIFT_ON;
			} else {
				return Keyboard.SHIFT_OFF;
			}
		}
	}

	private void handleShiftInternal(boolean forceState, int newState) {
		// Log.i(TAG, "handleShiftInternal forceNormal=" + forceNormal);
		mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE);
		KeyboardSwitcher switcher = mKeyboardSwitcher;
		if (switcher.isAlphabetMode()) {
			if (forceState) {
				switcher.setShiftState(newState);
			} else {
				switcher.setShiftState(nextShiftState(getShiftState(), true));
			}
		} else {
			switcher.toggleShift();
		}
	}

	private void abortCorrection(boolean force) {
		if (force || TextEntryState.isCorrecting()) {
			getCurrentInputConnection().finishComposingText();
			// clearSuggestions();
		}
	}

	private void handleCharacter(int primaryCode, int[] keyCodes) {
		if (candidatesContainer != null && editor != null) {
			// if (editor.compose(getCurrentInputConnection(), primaryCode, mComposingOriginal.toString())) {
			// Set the candidates for the updated composing-text and provide default
			// highlight for the word candidates.
			// setCandidates(wordDictionary.getWords(mComposingOriginal), false);
			// return true;
			// }
		}

		// mComposing.append((char)primaryCode);
		// getCurrentInputConnection().setComposingText(mComposing, 1);
		// getCurrentInputConnection().commitText("Hello", 1);

		if (mLastSelectionStart == mLastSelectionEnd && TextEntryState.isCorrecting()) {
			abortCorrection(false);
		}

		sendModifiableKeyChar((char)primaryCode);

		updateShiftKeyState(getCurrentInputEditorInfo());
		if (LatinIME.PERF_DEBUG)
			measureCps();
		TextEntryState.typedCharacter((char)primaryCode, isWordSeparator(primaryCode));
	}

	private void handleSeparator(int primaryCode) {
		boolean pickedDefault = false;
		if (candidatesContainer != null && editor != null) {
			if (candidatesContainer.isShown()) {
				// The space key could either pick the highlighted candidate or escape
				// if there's no highlighted candidate and no composing-text.
				if (!candidatesContainer.pickHighlighted() && !editor.hasComposingText()) {
					escape();
				}
			}
		}

		// Handle separator
		InputConnection ic = getCurrentInputConnection();
		if (ic != null) {
			ic.beginBatchEdit();
			abortCorrection(false);
		}

		if (mJustAddedAutoSpace && primaryCode == ASCII_ENTER) {
			removeTrailingSpace();
			mJustAddedAutoSpace = false;

			// handleClose();
		}

		sendModifiableKeyChar((char)primaryCode);

		// Handle the case of ". ." -> " .." with auto-space if necessary
		// before changing the TextEntryState.
		if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED && primaryCode == ASCII_PERIOD) {
			reswapPeriodAndSpace();
		}

		TextEntryState.typedCharacter((char)primaryCode, true);
		if (TextEntryState.getState() == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED && primaryCode != ASCII_ENTER) {
			swapPunctuationAndSpace();
		}

		if (pickedDefault) {
			TextEntryState.backToAcceptedDefault(mWord.getTypedWord());
		}

		updateShiftKeyState(getCurrentInputEditorInfo());
		if (ic != null) {
			ic.endBatchEdit();
		}
	}

	private void handleClose() {
		requestHideSelf(0);
		if (mKeyboardSwitcher != null) {
			LatinKeyboardView inputView = mKeyboardSwitcher.getInputView();
			if (inputView != null) {
				inputView.closing();
			}
		}
		TextEntryState.endSession();
	}

	private void postUpdateSuggestions() {
		mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS);
		mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_UPDATE_SUGGESTIONS), 100);
	}

	private boolean isPredictionWanted() {
		return (mShowSuggestions || mSuggestionForceOn) && !suggestionsDisabled();
	}

	private boolean sameAsTextBeforeCursor(InputConnection ic, CharSequence text) {
		CharSequence beforeText = ic.getTextBeforeCursor(text.length(), 0);
		return TextUtils.equals(text, beforeText);
	}

	public void revertLastWord(boolean deleteChar) {
		sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
	}

	protected String getWordSeparators() {
		return mWordSeparators;
	}

	public boolean isWordSeparator(int code) {
		String separators = getWordSeparators();
		return separators.contains(String.valueOf((char)code));
	}

	private boolean isSentenceSeparator(int code) {
		return mSentenceSeparators.contains(String.valueOf((char)code));
	}

	public boolean preferCapitalization() {
		return mWord.isFirstCharCapitalized();
	}

	void toggleLanguage(boolean reset, boolean next) {
		if (reset) {
			mLanguageSwitcher.reset();
		} else {
			if (next) {
				mLanguageSwitcher.next();
			} else {
				mLanguageSwitcher.prev();
			}
		}
		int currentKeyboardMode = mKeyboardSwitcher.getKeyboardMode();
		reloadKeyboards();
		mKeyboardSwitcher.makeKeyboards(true);
		mKeyboardSwitcher.setKeyboardMode(currentKeyboardMode, 0);
		initSuggest(mLanguageSwitcher.getInputLanguage());
		mLanguageSwitcher.persist();
		mAutoCapActive = mAutoCapPref && mLanguageSwitcher.allowAutoCap();
		mDeadKeysActive = mLanguageSwitcher.allowDeadKeys();
		updateShiftKeyState(getCurrentInputEditorInfo());
	}

	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		Log.i("PCKeyboard", "onSharedPreferenceChanged()");
		boolean needReload = false;
		Resources res = getResources();

		// Apply globally handled shared prefs
		sKeyboardSettings.sharedPreferenceChanged(sharedPreferences, key);
		if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEED_RELOAD)) {
			needReload = true;
		}
		if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEW_PUNC_LIST)) {
			initSuggestPuncList();
		}
		if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RECREATE_INPUT_VIEW)) {
			mKeyboardSwitcher.recreateInputView();
		}
		if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_MODE_OVERRIDE)) {
			mKeyboardModeOverrideLandscape = 0;
			mKeyboardModeOverridePortrait = 0;
		}
		if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_KEYBOARDS)) {
			toggleLanguage(true, true);
		}
		int unhandledFlags = sKeyboardSettings.unhandledFlags();
		if (unhandledFlags != GlobalKeyboardSettings.FLAG_PREF_NONE) {
			Log.w(TAG, "Not all flag settings handled, remaining=" + unhandledFlags);
		}

		if (PREF_SELECTED_LANGUAGES.equals(key)) {
			mLanguageSwitcher.loadLocales(sharedPreferences);
			mRefreshKeyboardRequired = true;
		} else if (PREF_CONNECTBOT_TAB_HACK.equals(key)) {
			mConnectbotTabHack = sharedPreferences.getBoolean(PREF_CONNECTBOT_TAB_HACK, res.getBoolean(R.bool.default_connectbot_tab_hack));
		} else if (PREF_FULLSCREEN_OVERRIDE.equals(key)) {
			mFullscreenOverride = sharedPreferences.getBoolean(PREF_FULLSCREEN_OVERRIDE, res.getBoolean(R.bool.default_fullscreen_override));
			needReload = true;
		} else if (PREF_FORCE_KEYBOARD_ON.equals(key)) {
			mForceKeyboardOn = sharedPreferences.getBoolean(PREF_FORCE_KEYBOARD_ON, res.getBoolean(R.bool.default_force_keyboard_on));
			needReload = true;
		} else if (PREF_KEYBOARD_NOTIFICATION.equals(key)) {
			mKeyboardNotification = sharedPreferences.getBoolean(PREF_KEYBOARD_NOTIFICATION, res.getBoolean(R.bool.default_keyboard_notification));
			setNotification(mKeyboardNotification);
		} else if (PREF_SUGGESTIONS_IN_LANDSCAPE.equals(key)) {
			mSuggestionsInLandscape = sharedPreferences.getBoolean(PREF_SUGGESTIONS_IN_LANDSCAPE, res.getBoolean(R.bool.default_suggestions_in_landscape));
			// Respect the suggestion settings in legacy Gingerbread mode,
			// in portrait mode, or if suggestions in landscape enabled.
			mSuggestionForceOff = false;
			mSuggestionForceOn = false;
		} else if (PREF_SHOW_SUGGESTIONS.equals(key)) {
			mShowSuggestions = sharedPreferences.getBoolean(PREF_SHOW_SUGGESTIONS, res.getBoolean(R.bool.default_suggestions));
			mSuggestionForceOff = false;
			mSuggestionForceOn = false;
			needReload = true;
		} else if (PREF_HEIGHT_PORTRAIT.equals(key)) {
			mHeightPortrait = getHeight(sharedPreferences, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait));
			needReload = true;
		} else if (PREF_HEIGHT_LANDSCAPE.equals(key)) {
			mHeightLandscape = getHeight(sharedPreferences, PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape));
			needReload = true;
		} else if (PREF_HINT_MODE.equals(key)) {
			LatinIME.sKeyboardSettings.hintMode = Integer.parseInt(sharedPreferences.getString(PREF_HINT_MODE, "0"));
			needReload = true;
		} else if (PREF_LONGPRESS_TIMEOUT.equals(key)) {
			LatinIME.sKeyboardSettings.longpressTimeout = getPrefInt(sharedPreferences, PREF_LONGPRESS_TIMEOUT,
					res.getString(R.string.default_long_press_duration));
		} else if (PREF_RENDER_MODE.equals(key)) {
			LatinIME.sKeyboardSettings.renderMode = getPrefInt(sharedPreferences, PREF_RENDER_MODE, res.getString(R.string.default_render_mode));
			needReload = true;
		} else if (PREF_SWIPE_UP.equals(key)) {
			mSwipeUpAction = sharedPreferences.getString(PREF_SWIPE_UP, "0");
		} else if (PREF_SWIPE_DOWN.equals(key)) {
			mSwipeDownAction = sharedPreferences.getString(PREF_SWIPE_DOWN, "0");
		} else if (PREF_SWIPE_LEFT.equals(key)) {
			mSwipeLeftAction = sharedPreferences.getString(PREF_SWIPE_LEFT, "0");
		} else if (PREF_SWIPE_RIGHT.equals(key)) {
			mSwipeRightAction = sharedPreferences.getString(PREF_SWIPE_RIGHT, "0");
		} else if (PREF_VOL_UP.equals(key)) {
			mVolUpAction = sharedPreferences.getString(PREF_VOL_UP, "0");
		} else if (PREF_VOL_DOWN.equals(key)) {
			mVolDownAction = sharedPreferences.getString(PREF_VOL_DOWN, "0");
		} else if (PREF_VIBRATE_LEN.equals(key)) {
			mVibrateLen = getPrefInt(sharedPreferences, PREF_VIBRATE_LEN, getResources().getString(R.string.vibrate_duration_ms));
			vibrate(); // test setting
		}

		updateKeyboardOptions();
		if (needReload) {
			mKeyboardSwitcher.makeKeyboards(true);
		}
	}

	private boolean doSwipeAction(String action) {
		// Log.i(TAG, "doSwipeAction + " + action);
		if (action == null || action.equals("") || action.equals("none")) {
			return false;
		} else if (action.equals("close")) {
			handleClose();
		} else if (action.equals("settings")) {
			// launchSettings();
		} else if (action.equals("suggestions")) {
			if (mSuggestionForceOn) {
				mSuggestionForceOn = false;
				mSuggestionForceOff = true;
			} else if (mSuggestionForceOff) {
				mSuggestionForceOn = true;
				mSuggestionForceOff = false;
			} else if (isPredictionWanted()) {
				mSuggestionForceOff = true;
			} else {
				mSuggestionForceOn = true;
			}
			// setCandidatesViewShown(isPredictionOn());
		} else if (action.equals("lang_prev")) {
			toggleLanguage(false, false);
		} else if (action.equals("lang_next")) {
			toggleLanguage(false, true);
		} else if (action.equals("debug_auto_play")) {
			if (LatinKeyboardView.DEBUG_AUTO_PLAY) {
				ClipboardManager cm = ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE));
				CharSequence text = cm.getText();
				if (!TextUtils.isEmpty(text)) {
					mKeyboardSwitcher.getInputView().startPlaying(text.toString());
				}
			}
		} else if (action.equals("full_mode")) {
			if (isPortrait()) {
				mKeyboardModeOverridePortrait = (mKeyboardModeOverridePortrait + 1) % mNumKeyboardModes;
			} else {
				mKeyboardModeOverrideLandscape = (mKeyboardModeOverrideLandscape + 1) % mNumKeyboardModes;
			}
			toggleLanguage(true, true);
		} else if (action.equals("extension")) {
			sKeyboardSettings.useExtension = !sKeyboardSettings.useExtension;
			reloadKeyboards();
		} else if (action.equals("height_up")) {
			if (isPortrait()) {
				mHeightPortrait += 5;
				if (mHeightPortrait > 70)
					mHeightPortrait = 70;
			} else {
				mHeightLandscape += 5;
				if (mHeightLandscape > 70)
					mHeightLandscape = 70;
			}
			toggleLanguage(true, true);
		} else if (action.equals("height_down")) {
			if (isPortrait()) {
				mHeightPortrait -= 5;
				if (mHeightPortrait < 15)
					mHeightPortrait = 15;
			} else {
				mHeightLandscape -= 5;
				if (mHeightLandscape < 15)
					mHeightLandscape = 15;
			}
			toggleLanguage(true, true);
		} else {
			Log.i(TAG, "Unsupported swipe action config: " + action);
		}
		return true;
	}

	public boolean swipeRight() {
		return doSwipeAction(mSwipeRightAction);
	}

	public boolean swipeLeft() {
		return doSwipeAction(mSwipeLeftAction);
	}

	public boolean swipeDown() {
		return doSwipeAction(mSwipeDownAction);
	}

	public boolean swipeUp() {
		return doSwipeAction(mSwipeUpAction);
	}

	// keyOnPress
	public void onPress(int primaryCode) {
		InputConnection ic = getCurrentInputConnection();
		if (mKeyboardSwitcher.isVibrateAndSoundFeedbackRequired()) {
			vibrate();
			playKeyClick(primaryCode);
		}
		final boolean distinctMultiTouch = mKeyboardSwitcher.hasDistinctMultitouch();
		if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
			mShiftKeyState.onPress();
			startMultitouchShift();
		} else if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
			// changeKeyboardMode();
			// mSymbolKeyState.onPress();
			// mKeyboardSwitcher.setAutoModeSwitchStateMomentary();
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
			setModCtrl(!mModCtrl);
			mCtrlKeyState.onPress();
			sendCtrlKey(ic, true, true);
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
			setModAlt(!mModAlt);
			mAltKeyState.onPress();
			sendAltKey(ic, true, true);
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_FN) {
			setModFn(!mModFn);
			mFnKeyState.onPress();
		} else {
			mShiftKeyState.onOtherKeyPressed();
			mSymbolKeyState.onOtherKeyPressed();
			mCtrlKeyState.onOtherKeyPressed();
			mAltKeyState.onOtherKeyPressed();
			mFnKeyState.onOtherKeyPressed();
		}
	}

	public void onRelease(int primaryCode) {
		// Reset any drag flags in the keyboard
		((LatinKeyboard)mKeyboardSwitcher.getInputView().getKeyboard()).keyReleased();
		// vibrate();
		final boolean distinctMultiTouch = mKeyboardSwitcher.hasDistinctMultitouch();
		InputConnection ic = getCurrentInputConnection();
		if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
			if (mShiftKeyState.isMomentary()) {
				resetMultitouchShift();
			} else {
				commitMultitouchShift();
			}
			mShiftKeyState.onRelease();
		} else if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_MODE_CHANGE) {
			// Snap back to the previous keyboard mode if the user chords the
			// mode change key and
			// other key, then released the mode change key.
			// if (mKeyboardSwitcher.isInChordingAutoModeSwitchState())
			changeKeyboardMode();
			mSymbolKeyState.onRelease();
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
			if (mCtrlKeyState.isMomentary()) {
				setModCtrl(false);
			}
			sendCtrlKey(ic, false, true);
			mCtrlKeyState.onRelease();
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT) {
			if (mAltKeyState.isMomentary()) {
				setModAlt(false);
			}
			sendAltKey(ic, false, true);
			mAltKeyState.onRelease();
		} else if (distinctMultiTouch && primaryCode == LatinKeyboardView.KEYCODE_FN) {
			if (mFnKeyState.isMomentary()) {
				setModFn(false);
			}
			mFnKeyState.onRelease();
		}
	}

	// receive ringer mode changes to detect silent mode
	private BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			updateRingerMode();
		}
	};

	// update flags for silent mode
	private void updateRingerMode() {
		if (mAudioManager == null) {
			mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		}
		if (mAudioManager != null) {
			mSilentMode = (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL);
		}
	}

	private float getKeyClickVolume() {
		if (mAudioManager == null)
			return 0.0f; // shouldn't happen

		// The volume calculations are poorly documented, this is the closest I could
		// find for explaining volume conversions:
		// http://developer.android.com/reference/android/media/MediaPlayer.html#setAuxEffectSendLevel(float)
		//
		// Note that the passed level value is a raw scalar. UI controls should be scaled logarithmically:
		// the gain applied by audio framework ranges from -72dB to 0dB, so an appropriate conversion
		// from linear UI input x to level is: x == 0 -> level = 0 0 < x <= R -> level = 10^(72*(x-R)/20/R)

		int method = sKeyboardSettings.keyClickMethod; // See click_method_values in strings.xml
		if (method == 0)
			return FX_VOLUME;

		float targetVol = sKeyboardSettings.keyClickVolume;

		if (method > 1) {
			// (klausw): on some devices the media volume controls the click volume?
			// If that's the case, try to set a relative target volume.
			int mediaMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
			int mediaVol = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			// Log.i(TAG, "getKeyClickVolume relative, media vol=" + mediaVol + "/" + mediaMax);
			float channelVol = (float)mediaVol / mediaMax;
			if (method == 2) {
				targetVol *= channelVol;
			} else if (method == 3) {
				if (channelVol == 0)
					return 0.0f; // Channel is silent, won't get audio
				targetVol = Math.min(targetVol / channelVol, 1.0f); // Cap at 1.0
			}
		}
		// Set absolute volume, treating the percentage as a logarithmic control
		float vol = (float)Math.pow(10.0, FX_VOLUME_RANGE_DB * (targetVol - 1) / 20);
		// Log.i(TAG, "getKeyClickVolume absolute, target=" + targetVol + " amp=" + vol);
		return vol;
	}

	private void playKeyClick(int primaryCode) {
		// if mAudioManager is null, we don't have the ringer state yet
		// mAudioManager will be set by updateRingerMode
		if (mAudioManager == null) {
			if (mKeyboardSwitcher.getInputView() != null) {
				updateRingerMode();
			}
		}
		if (mSoundOn && !mSilentMode) {
			// : Volume and enable should come from UI settings
			// : These should be triggered after auto-repeat logic
			int sound = AudioManager.FX_KEYPRESS_STANDARD;
			switch (primaryCode) {
				case Keyboard.KEYCODE_DELETE:
					sound = AudioManager.FX_KEYPRESS_DELETE;
					break;
				case ASCII_ENTER:
					sound = AudioManager.FX_KEYPRESS_RETURN;
					break;
				case ASCII_SPACE:
					sound = AudioManager.FX_KEYPRESS_SPACEBAR;
					break;
			}
			mAudioManager.playSoundEffect(sound, getKeyClickVolume());
		}
	}

	private void vibrate() {
		if (!mVibrateOn) {
			return;
		}
		Vibrator v = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
		if (v != null) {
			v.vibrate(mVibrateLen);
			return;
		}

		if (mKeyboardSwitcher.getInputView() != null) {
			mKeyboardSwitcher.getInputView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
		}
	}

	/* package */boolean getPopupOn() {
		return mPopupOn;
	}

	protected void launchSettings(Class<? extends PreferenceActivity> settingsClass) {
		handleClose();
		Intent intent = new Intent();
		intent.setClass(LatinIME.this, settingsClass);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
	}

	private void loadSettings() {
		// Get the settings preferences
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
		mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false);
		mVibrateLen = getPrefInt(sp, PREF_VIBRATE_LEN, getResources().getString(R.string.vibrate_duration_ms));
		mSoundOn = sp.getBoolean(PREF_SOUND_ON, false);
		mPopupOn = sp.getBoolean(PREF_POPUP_ON, mResources.getBoolean(R.bool.default_popup_preview));
		mAutoCapPref = sp.getBoolean(PREF_AUTO_CAP, getResources().getBoolean(R.bool.default_auto_cap));

		mShowSuggestions = sp.getBoolean(PREF_SHOW_SUGGESTIONS, mResources.getBoolean(R.bool.default_suggestions));

		mLanguageSwitcher.loadLocales(sp);
		mAutoCapActive = mAutoCapPref && mLanguageSwitcher.allowAutoCap();
		mDeadKeysActive = mLanguageSwitcher.allowDeadKeys();
	}

	private void initSuggestPuncList() {
		mSuggestPuncList = new ArrayList<CharSequence>();
		String suggestPuncs = sKeyboardSettings.suggestedPunctuation;
		String defaultPuncs = getResources().getString(R.string.suggested_punctuations_default);
		if (suggestPuncs.equals(defaultPuncs) || suggestPuncs.equals("")) {
			// Not user-configured, load the language-specific default.
			suggestPuncs = getResources().getString(R.string.suggested_punctuations);
		}
		if (suggestPuncs != null) {
			for (int i = 0; i < suggestPuncs.length(); i++) {
				mSuggestPuncList.add(suggestPuncs.subSequence(i, i + 1));
			}
		}
	}

	public void changeKeyboardMode() {
		KeyboardSwitcher switcher = mKeyboardSwitcher;
		if (switcher.isAlphabetMode()) {
			mSavedShiftState = getShiftState();
		}
		switcher.toggleSymbols();
		if (switcher.isAlphabetMode()) {
			switcher.setShiftState(mSavedShiftState);
		}

		updateShiftKeyState(getCurrentInputEditorInfo());
	}

	public static <E> ArrayList<E> newArrayList(E... elements) {
		int capacity = (elements.length * 110) / 100 + 5;
		ArrayList<E> list = new ArrayList<E>(capacity);
		Collections.addAll(list, elements);
		return list;
	}

	@Override
	protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
		super.dump(fd, fout, args);

		final Printer p = new PrintWriterPrinter(fout);
		p.println("LatinIME state :");
		p.println("  Keyboard mode = " + mKeyboardSwitcher.getKeyboardMode());
		p.println("  mComposing=" + mComposing.toString());
		p.println("  mAutoSpace=" + mAutoSpace);
		p.println("  TextEntryState.state=" + TextEntryState.getState());
		p.println("  mSoundOn=" + mSoundOn);
		p.println("  mVibrateOn=" + mVibrateOn);
		p.println("  mPopupOn=" + mPopupOn);
	}

	// Characters per second measurement

	private long mLastCpsTime;
	private static final int CPS_BUFFER_SIZE = 16;
	private long[] mCpsIntervals = new long[CPS_BUFFER_SIZE];
	private int mCpsIndex;
	private static Pattern NUMBER_RE = Pattern.compile("(\\d+).*");

	private void measureCps() {
		long now = System.currentTimeMillis();
		if (mLastCpsTime == 0)
			mLastCpsTime = now - 100; // Initial
		mCpsIntervals[mCpsIndex] = now - mLastCpsTime;
		mLastCpsTime = now;
		mCpsIndex = (mCpsIndex + 1) % CPS_BUFFER_SIZE;
		long total = 0;
		for (int i = 0; i < CPS_BUFFER_SIZE; i++)
			total += mCpsIntervals[i];
		System.out.println("CPS = " + ((CPS_BUFFER_SIZE * 1000f) / total));
	}

	public void onAutoCompletionStateChanged(boolean isAutoCompletion) {
		mKeyboardSwitcher.onAutoCompletionStateChanged(isAutoCompletion);
	}

	static int getIntFromString(String val, int defVal) {
		Matcher num = NUMBER_RE.matcher(val);
		if (!num.matches())
			return defVal;
		return Integer.parseInt(num.group(1));
	}

	static int getPrefInt(SharedPreferences prefs, String prefName, int defVal) {
		String prefVal = prefs.getString(prefName, Integer.toString(defVal));
		// Log.i("PCKeyboard", "getPrefInt " + prefName + " = " + prefVal + ", default " + defVal);
		return getIntFromString(prefVal, defVal);
	}

	static int getPrefInt(SharedPreferences prefs, String prefName, String defStr) {
		int defVal = getIntFromString(defStr, 0);
		return getPrefInt(prefs, prefName, defVal);
	}

	static int getHeight(SharedPreferences prefs, String prefName, String defVal) {
		int val = getPrefInt(prefs, prefName, defVal);
		if (val < 15)
			val = 15;
		if (val > 75)
			val = 75;
		return val;
	}

	private class KanjiTask extends AsyncTask<String, Void, String> {

		@Override
		protected String doInBackground(String... params) {
			Log.d("", "Composing: " + params[0].toLowerCase());

			String candidates = "";

			for (int kln = 0; kln < KANJIML.kmlr.length; kln++) {
				if (KANJIML.kmlr[kln][KANJIML.kmlr[kln].length - 1][0].compareTo(params[0].toLowerCase()) <= 0)
					continue;
				for (int i = 0; i < KANJIML.kmlr[kln].length; i++) {
					if (params[0].toLowerCase().equals(KANJIML.kmlr[kln][i][0]))
						candidates += (KANJIML.kmlr[kln][i][1] + "|");
				}
			}

			if (candidates.length() > 1) {
				candidates = candidates.substring(0, candidates.length() - 1);
			}

			return candidates;
		}

		@Override
		protected void onPostExecute(String result) {
			super.onPostExecute(result);
			setCandidates(result, false);
			setCandidatesViewShown(true);
		}
	}
}
