/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Xml;

import androidx.annotation.StringRes;

import org.telegram.messenger.time.FastDateFormat;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

import com.exteragram.messenger.ExteraConfig;

public class LocaleController {

    static final int QUANTITY_OTHER = 0x0000;
    static final int QUANTITY_ZERO = 0x0001;
    static final int QUANTITY_ONE = 0x0002;
    static final int QUANTITY_TWO = 0x0004;
    static final int QUANTITY_FEW = 0x0008;
    static final int QUANTITY_MANY = 0x0010;

    public static boolean isRTL = false;
    public static int nameDisplayOrder = 1;
    public static boolean is24HourFormat = false;
    public FastDateFormat formatterDay;
    public FastDateFormat formatterWeek;
    public FastDateFormat formatterWeekLong;
    public FastDateFormat formatterDayMonth;
    public FastDateFormat formatterYear;
    public FastDateFormat formatterYearMax;
    public FastDateFormat formatterStats;
    public FastDateFormat formatterBannedUntil;
    public FastDateFormat formatterBannedUntilThisYear;
    public FastDateFormat chatDate;
    public FastDateFormat chatFullDate;
    public FastDateFormat formatterScheduleDay;
    public FastDateFormat formatterScheduleYear;
    public FastDateFormat formatterMonthYear;
    public FastDateFormat[] formatterScheduleSend = new FastDateFormat[15];

    private static HashMap<Integer, String> resourcesCacheMap = new HashMap<>();

    private HashMap<String, PluralRules> allRules = new HashMap<>();

    private Locale currentLocale;
    private Locale systemDefaultLocale;
    private PluralRules currentPluralRules;
    private LocaleInfo currentLocaleInfo;
    private HashMap<String, String> localeValues = new HashMap<>();
    private String languageOverride;
    private boolean changingConfiguration = false;
    private boolean reloadLastFile;

    private String currentSystemLocale;

    private HashMap<String, String> currencyValues;
    private HashMap<String, String> translitChars;
    private HashMap<String, String> ruTranslitChars;

    private class TimeZoneChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ApplicationLoader.applicationHandler.post(() -> {
                if (!formatterDayMonth.getTimeZone().equals(TimeZone.getDefault())) {
                    LocaleController.getInstance().recreateFormatters();
                }
            });
        }
    }

    public static class LocaleInfo {

        public String name;
        public String nameEnglish;
        public String shortName;
        public String pathToFile;
        public String baseLangCode;
        public String pluralLangCode;
        public boolean isRtl;
        public int version;
        public int baseVersion;
        public boolean builtIn;
        public int serverIndex;

        public String getSaveString() {
            String langCode = baseLangCode == null ? "" : baseLangCode;
            String pluralCode = TextUtils.isEmpty(pluralLangCode) ? shortName : pluralLangCode;
            return name + "|" + nameEnglish + "|" + shortName + "|" + pathToFile + "|" + version + "|" + langCode + "|" + pluralLangCode + "|" + (isRtl ? 1 : 0) + "|" + baseVersion + "|" + serverIndex;
        }

        public static LocaleInfo createWithString(String string) {
            if (string == null || string.length() == 0) {
                return null;
            }
            String[] args = string.split("\\|");
            LocaleInfo localeInfo = null;
            if (args.length >= 4) {
                localeInfo = new LocaleInfo();
                localeInfo.name = args[0];
                localeInfo.nameEnglish = args[1];
                localeInfo.shortName = args[2].toLowerCase();
                localeInfo.pathToFile = args[3];
                if (args.length >= 5) {
                    localeInfo.version = Utilities.parseInt(args[4]);
                }
                localeInfo.baseLangCode = args.length >= 6 ? args[5] : "";
                localeInfo.pluralLangCode = args.length >= 7 ? args[6] : localeInfo.shortName;
                if (args.length >= 8) {
                    localeInfo.isRtl = Utilities.parseInt(args[7]) == 1;
                }
                if (args.length >= 9) {
                    localeInfo.baseVersion = Utilities.parseInt(args[8]);
                }
                if (args.length >= 10) {
                    localeInfo.serverIndex = Utilities.parseInt(args[9]);
                } else {
                    localeInfo.serverIndex = Integer.MAX_VALUE;
                }
                if (!TextUtils.isEmpty(localeInfo.baseLangCode)) {
                    localeInfo.baseLangCode = localeInfo.baseLangCode.replace("-", "_");
                }
            }
            return localeInfo;
        }

        public File getPathToFile() {
            if (isRemote()) {
                return new File(ApplicationLoader.getFilesDirFixed(), "remote_" + shortName + ".xml");
            } else if (isUnofficial()) {
                return new File(ApplicationLoader.getFilesDirFixed(), "unofficial_" + shortName + ".xml");
            }
            return !TextUtils.isEmpty(pathToFile) ? new File(pathToFile) : null;
        }

        public File getPathToBaseFile() {
            if (isUnofficial()) {
                return new File(ApplicationLoader.getFilesDirFixed(), "unofficial_base_" + shortName + ".xml");
            }
            return null;
        }

        public String getKey() {
            if (pathToFile != null && !isRemote() && !isUnofficial()) {
                return "local_" + shortName;
            } else if (isUnofficial()) {
                return "unofficial_" + shortName;
            }
            return shortName;
        }

        public boolean hasBaseLang() {
            return isUnofficial() && !TextUtils.isEmpty(baseLangCode) && !baseLangCode.equals(shortName);
        }

        public boolean isRemote() {
            return "remote".equals(pathToFile);
        }

        public boolean isUnofficial() {
            return "unofficial".equals(pathToFile);
        }

        public boolean isLocal() {
            return !TextUtils.isEmpty(pathToFile) && !isRemote() && !isUnofficial();
        }

        public boolean isBuiltIn() {
            return builtIn;
        }

        public String getLangCode() {
            return shortName.replace("_", "-");
        }

        public String getBaseLangCode() {
            return baseLangCode == null ? "" : baseLangCode.replace("_", "-");
        }
    }

    private boolean loadingRemoteLanguages;

    public ArrayList<LocaleInfo> languages = new ArrayList<>();
    public ArrayList<LocaleInfo> unofficialLanguages = new ArrayList<>();
    public ArrayList<LocaleInfo> remoteLanguages = new ArrayList<>();
    public HashMap<String, LocaleInfo> remoteLanguagesDict = new HashMap<>();
    public HashMap<String, LocaleInfo> languagesDict = new HashMap<>();

    private ArrayList<LocaleInfo> otherLanguages = new ArrayList<>();

    private static volatile LocaleController Instance = null;
    public static LocaleController getInstance() {
        LocaleController localInstance = Instance;
        if (localInstance == null) {
            synchronized (LocaleController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new LocaleController();
                }
            }
        }
        return localInstance;
    }

    public LocaleController() {
        addRules(new String[]{"bem", "brx", "da", "de", "el", "en", "eo", "es", "et", "fi", "fo", "gl", "he", "iw", "it", "nb",
                "nl", "nn", "no", "sv", "af", "bg", "bn", "ca", "eu", "fur", "fy", "gu", "ha", "is", "ku",
                "lb", "ml", "mr", "nah", "ne", "om", "or", "pa", "pap", "ps", "so", "sq", "sw", "ta", "te",
                "tk", "ur", "zu", "mn", "gsw", "chr", "rm", "pt", "an", "ast"}, new PluralRules_One());
        addRules(new String[]{"cs", "sk"}, new PluralRules_Czech());
        addRules(new String[]{"ff", "fr", "kab"}, new PluralRules_French());
        addRules(new String[]{"ru", "uk", "be"}, new PluralRules_Balkan());
        addRules(new String[]{"sr", "hr", "bs", "sh"}, new PluralRules_Serbian());
        addRules(new String[]{"lv"}, new PluralRules_Latvian());
        addRules(new String[]{"lt"}, new PluralRules_Lithuanian());
        addRules(new String[]{"pl"}, new PluralRules_Polish());
        addRules(new String[]{"ro", "mo"}, new PluralRules_Romanian());
        addRules(new String[]{"sl"}, new PluralRules_Slovenian());
        addRules(new String[]{"ar"}, new PluralRules_Arabic());
        addRules(new String[]{"mk"}, new PluralRules_Macedonian());
        addRules(new String[]{"cy"}, new PluralRules_Welsh());
        addRules(new String[]{"br"}, new PluralRules_Breton());
        addRules(new String[]{"lag"}, new PluralRules_Langi());
        addRules(new String[]{"shi"}, new PluralRules_Tachelhit());
        addRules(new String[]{"mt"}, new PluralRules_Maltese());
        addRules(new String[]{"ga", "se", "sma", "smi", "smj", "smn", "sms"}, new PluralRules_Two());
        addRules(new String[]{"ak", "am", "bh", "fil", "tl", "guw", "hi", "ln", "mg", "nso", "ti", "wa"}, new PluralRules_Zero());
        addRules(new String[]{"az", "bm", "fa", "ig", "hu", "ja", "kde", "kea", "ko", "my", "ses", "sg", "to",
                "tr", "vi", "wo", "yo", "zh", "bo", "dz", "id", "jv", "jw", "ka", "km", "kn", "ms", "th", "in"}, new PluralRules_None());

        LocaleInfo localeInfo = new LocaleInfo();
        localeInfo.name = "English";
        localeInfo.nameEnglish = "English";
        localeInfo.shortName = localeInfo.pluralLangCode = "en";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "Italiano";
        localeInfo.nameEnglish = "Italian";
        localeInfo.shortName = localeInfo.pluralLangCode = "it";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "Espa??ol";
        localeInfo.nameEnglish = "Spanish";
        localeInfo.shortName = localeInfo.pluralLangCode = "es";
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "Deutsch";
        localeInfo.nameEnglish = "German";
        localeInfo.shortName = localeInfo.pluralLangCode = "de";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "Nederlands";
        localeInfo.nameEnglish = "Dutch";
        localeInfo.shortName = localeInfo.pluralLangCode = "nl";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "??????????????";
        localeInfo.nameEnglish = "Arabic";
        localeInfo.shortName = localeInfo.pluralLangCode = "ar";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        localeInfo.isRtl = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "Portugu??s (Brasil)";
        localeInfo.nameEnglish = "Portuguese (Brazil)";
        localeInfo.shortName = localeInfo.pluralLangCode = "pt_br";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        localeInfo = new LocaleInfo();
        localeInfo.name = "?????????";
        localeInfo.nameEnglish = "Korean";
        localeInfo.shortName = localeInfo.pluralLangCode = "ko";
        localeInfo.pathToFile = null;
        localeInfo.builtIn = true;
        languages.add(localeInfo);
        languagesDict.put(localeInfo.shortName, localeInfo);

        loadOtherLanguages();
        if (remoteLanguages.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> loadRemoteLanguages(UserConfig.selectedAccount));
        }

        for (int a = 0; a < otherLanguages.size(); a++) {
            LocaleInfo locale = otherLanguages.get(a);
            languages.add(locale);
            languagesDict.put(locale.getKey(), locale);
        }

        for (int a = 0; a < remoteLanguages.size(); a++) {
            LocaleInfo locale = remoteLanguages.get(a);
            LocaleInfo existingLocale = getLanguageFromDict(locale.getKey());
            if (existingLocale != null) {
                existingLocale.pathToFile = locale.pathToFile;
                existingLocale.version = locale.version;
                existingLocale.baseVersion = locale.baseVersion;
                existingLocale.serverIndex = locale.serverIndex;
                remoteLanguages.set(a, existingLocale);
            } else {
                languages.add(locale);
                languagesDict.put(locale.getKey(), locale);
            }
        }

        for (int a = 0; a < unofficialLanguages.size(); a++) {
            LocaleInfo locale = unofficialLanguages.get(a);
            LocaleInfo existingLocale = getLanguageFromDict(locale.getKey());
            if (existingLocale != null) {
                existingLocale.pathToFile = locale.pathToFile;
                existingLocale.version = locale.version;
                existingLocale.baseVersion = locale.baseVersion;
                existingLocale.serverIndex = locale.serverIndex;
                unofficialLanguages.set(a, existingLocale);
            } else {
                languagesDict.put(locale.getKey(), locale);
            }
        }

        systemDefaultLocale = Locale.getDefault();
        is24HourFormat = DateFormat.is24HourFormat(ApplicationLoader.applicationContext);
        LocaleInfo currentInfo = null;
        boolean override = false;

        try {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            String lang = preferences.getString("language", null);
            if (lang != null) {
                currentInfo = getLanguageFromDict(lang);
                if (currentInfo != null) {
                    override = true;
                }
            }

            if (currentInfo == null && systemDefaultLocale.getLanguage() != null) {
                currentInfo = getLanguageFromDict(systemDefaultLocale.getLanguage());
            }
            if (currentInfo == null) {
                currentInfo = getLanguageFromDict(getLocaleString(systemDefaultLocale));
                if (currentInfo == null) {
                    currentInfo = getLanguageFromDict("en");
                }
            }

            applyLanguage(currentInfo, override, true, UserConfig.selectedAccount);
        } catch (Exception e) {
            FileLog.e(e);
        }

        try {
            IntentFilter timezoneFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            ApplicationLoader.applicationContext.registerReceiver(new TimeZoneChangedReceiver(), timezoneFilter);
        } catch (Exception e) {
            FileLog.e(e);
        }

        AndroidUtilities.runOnUIThread(() -> currentSystemLocale = getSystemLocaleStringIso639());
    }

    public static String getLanguageFlag(String countryCode) {
        if (countryCode.length() != 2 || countryCode.equals("YL")) return null;

        if (countryCode.equals("XG")) {
            return "\uD83D\uDEF0";
        } else if (countryCode.equals("XV")){
            return "\uD83C\uDF0D";
        }

        int base = 0x1F1A5;
        char[] chars = countryCode.toCharArray();
        char[] emoji = {
                CharacterCompat.highSurrogate(base),
                CharacterCompat.lowSurrogate(base + chars[0]),
                CharacterCompat.highSurrogate(base),
                CharacterCompat.lowSurrogate(base + chars[1])
        };
        return new String(emoji);
    }

    public LocaleInfo getLanguageFromDict(String key) {
        if (key == null) {
            return null;
        }
        return languagesDict.get(key.toLowerCase().replace("-", "_"));
    }
    public LocaleInfo getBuiltinLanguageByPlural(String plural) {
        Collection<LocaleInfo> values = languagesDict.values();
        for (LocaleInfo l : values)
            if (l.pathToFile != null && l.pathToFile.equals("remote") && l.pluralLangCode != null && l.pluralLangCode.equals(plural))
                return l;
        return null;
    }

    private void addRules(String[] languages, PluralRules rules) {
        for (String language : languages) {
            allRules.put(language, rules);
        }
    }

    private String stringForQuantity(int quantity) {
        switch (quantity) {
            case QUANTITY_ZERO:
                return "zero";
            case QUANTITY_ONE:
                return "one";
            case QUANTITY_TWO:
                return "two";
            case QUANTITY_FEW:
                return "few";
            case QUANTITY_MANY:
                return "many";
            default:
                return "other";
        }
    }

    public Locale getSystemDefaultLocale() {
        return systemDefaultLocale;
    }

    public boolean isCurrentLocalLocale() {
        return currentLocaleInfo.isLocal();
    }

    public void reloadCurrentRemoteLocale(int currentAccount, String langCode, boolean force) {
        if (langCode != null) {
            langCode = langCode.replace("-", "_");
        }
        if (langCode == null || currentLocaleInfo != null && (langCode.equals(currentLocaleInfo.shortName) || langCode.equals(currentLocaleInfo.baseLangCode))) {
            applyRemoteLanguage(currentLocaleInfo, langCode, force, currentAccount);
        }
    }

    public void checkUpdateForCurrentRemoteLocale(int currentAccount, int version, int baseVersion) {
        if (currentLocaleInfo == null || !currentLocaleInfo.isRemote() && !currentLocaleInfo.isUnofficial()) {
            return;
        }
        if (currentLocaleInfo.hasBaseLang()) {
            if (currentLocaleInfo.baseVersion < baseVersion) {
                applyRemoteLanguage(currentLocaleInfo, currentLocaleInfo.baseLangCode, false, currentAccount);
            }
        }
        if (currentLocaleInfo.version < version) {
            applyRemoteLanguage(currentLocaleInfo, currentLocaleInfo.shortName, false, currentAccount);
        }
    }

    private String getLocaleString(Locale locale) {
        if (locale == null) {
            return "en";
        }
        String languageCode = locale.getLanguage();
        String countryCode = locale.getCountry();
        String variantCode = locale.getVariant();
        if (languageCode.length() == 0 && countryCode.length() == 0) {
            return "en";
        }
        StringBuilder result = new StringBuilder(11);
        result.append(languageCode);
        if (countryCode.length() > 0 || variantCode.length() > 0) {
            result.append('_');
        }
        result.append(countryCode);
        if (variantCode.length() > 0) {
            result.append('_');
        }
        result.append(variantCode);
        return result.toString();
    }

    public static String getSystemLocaleStringIso639() {
        Locale locale = getInstance().getSystemDefaultLocale();
        if (locale == null) {
            return "en";
        }
        String languageCode = locale.getLanguage();
        String countryCode = locale.getCountry();
        String variantCode = locale.getVariant();
        if (languageCode.length() == 0 && countryCode.length() == 0) {
            return "en";
        }
        StringBuilder result = new StringBuilder(11);
        result.append(languageCode);
        if (countryCode.length() > 0 || variantCode.length() > 0) {
            result.append('-');
        }
        result.append(countryCode);
        if (variantCode.length() > 0) {
            result.append('_');
        }
        result.append(variantCode);
        return result.toString();
    }

    public static String getLocaleStringIso639() {
        LocaleInfo info = getInstance().currentLocaleInfo;
        if (info != null) {
            return info.getLangCode();
        }
        Locale locale = getInstance().currentLocale;
        if (locale == null) {
            return "en";
        }
        String languageCode = locale.getLanguage();
        String countryCode = locale.getCountry();
        String variantCode = locale.getVariant();
        if (languageCode.length() == 0 && countryCode.length() == 0) {
            return "en";
        }
        StringBuilder result = new StringBuilder(11);
        result.append(languageCode);
        if (countryCode.length() > 0 || variantCode.length() > 0) {
            result.append('-');
        }
        result.append(countryCode);
        if (variantCode.length() > 0) {
            result.append('_');
        }
        result.append(variantCode);
        return result.toString();
    }

    public static String getLocaleAlias(String code) {
        if (code == null) {
            return null;
        }
        switch (code) {
            case "in":
                return "id";
            case "iw":
                return "he";
            case "jw":
                return "jv";
            case "no":
                return "nb";
            case "tl":
                return "fil";
            case "ji":
                return "yi";
            case "id":
                return "in";
            case "he":
                return "iw";
            case "jv":
                return "jw";
            case "nb":
                return "no";
            case "fil":
                return "tl";
            case "yi":
                return "ji";
        }

        return null;
    }

    public boolean applyLanguageFile(File file, int currentAccount) {
        try {
            HashMap<String, String> stringMap = getLocaleFileStrings(file);

            String languageName = stringMap.get("LanguageName");
            String languageNameInEnglish = stringMap.get("LanguageNameInEnglish");
            String languageCode = stringMap.get("LanguageCode");

            if (languageName != null && languageName.length() > 0 &&
                    languageNameInEnglish != null && languageNameInEnglish.length() > 0 &&
                    languageCode != null && languageCode.length() > 0) {

                if (languageName.contains("&") || languageName.contains("|")) {
                    return false;
                }
                if (languageNameInEnglish.contains("&") || languageNameInEnglish.contains("|")) {
                    return false;
                }
                if (languageCode.contains("&") || languageCode.contains("|") || languageCode.contains("/") || languageCode.contains("\\")) {
                    return false;
                }

                File finalFile = new File(ApplicationLoader.getFilesDirFixed(), languageCode + ".xml");
                if (!AndroidUtilities.copyFile(file, finalFile)) {
                    return false;
                }

                String key = "local_" + languageCode.toLowerCase();
                LocaleInfo localeInfo = getLanguageFromDict(key);
                if (localeInfo == null) {
                    localeInfo = new LocaleInfo();
                    localeInfo.name = languageName;
                    localeInfo.nameEnglish = languageNameInEnglish;
                    localeInfo.shortName = languageCode.toLowerCase();
                    localeInfo.pluralLangCode = localeInfo.shortName;

                    localeInfo.pathToFile = finalFile.getAbsolutePath();
                    languages.add(localeInfo);
                    languagesDict.put(localeInfo.getKey(), localeInfo);
                    otherLanguages.add(localeInfo);

                    saveOtherLanguages();
                }
                localeValues = stringMap;
                applyLanguage(localeInfo, true, false, true, false, currentAccount);
                return true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private void saveOtherLanguages() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("langconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        StringBuilder stringBuilder = new StringBuilder();
        for (int a = 0; a < otherLanguages.size(); a++) {
            LocaleInfo localeInfo = otherLanguages.get(a);
            String loc = localeInfo.getSaveString();
            if (loc != null) {
                if (stringBuilder.length() != 0) {
                    stringBuilder.append("&");
                }
                stringBuilder.append(loc);
            }
        }
        editor.putString("locales", stringBuilder.toString());
        stringBuilder.setLength(0);
        for (int a = 0; a < remoteLanguages.size(); a++) {
            LocaleInfo localeInfo = remoteLanguages.get(a);
            String loc = localeInfo.getSaveString();
            if (loc != null) {
                if (stringBuilder.length() != 0) {
                    stringBuilder.append("&");
                }
                stringBuilder.append(loc);
            }
        }
        editor.putString("remote", stringBuilder.toString());
        stringBuilder.setLength(0);
        for (int a = 0; a < unofficialLanguages.size(); a++) {
            LocaleInfo localeInfo = unofficialLanguages.get(a);
            String loc = localeInfo.getSaveString();
            if (loc != null) {
                if (stringBuilder.length() != 0) {
                    stringBuilder.append("&");
                }
                stringBuilder.append(loc);
            }
        }
        editor.putString("unofficial", stringBuilder.toString());
        editor.commit();
    }

    public boolean deleteLanguage(LocaleInfo localeInfo, int currentAccount) {
        if (localeInfo.pathToFile == null || localeInfo.isRemote() && localeInfo.serverIndex != Integer.MAX_VALUE) {
            return false;
        }
        if (currentLocaleInfo == localeInfo) {
            LocaleInfo info = null;
            if (systemDefaultLocale.getLanguage() != null) {
                info = getLanguageFromDict(systemDefaultLocale.getLanguage());
            }
            if (info == null) {
                info = getLanguageFromDict(getLocaleString(systemDefaultLocale));
            }
            if (info == null) {
                info = getLanguageFromDict("en");
            }
            applyLanguage(info, true, false, currentAccount);
        }

        unofficialLanguages.remove(localeInfo);
        remoteLanguages.remove(localeInfo);
        remoteLanguagesDict.remove(localeInfo.getKey());
        otherLanguages.remove(localeInfo);
        languages.remove(localeInfo);
        languagesDict.remove(localeInfo.getKey());
        File file = new File(localeInfo.pathToFile);
        file.delete();
        saveOtherLanguages();
        return true;
    }

    private void loadOtherLanguages() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("langconfig", Activity.MODE_PRIVATE);
        String locales = preferences.getString("locales", null);
        if (!TextUtils.isEmpty(locales)) {
            String[] localesArr = locales.split("&");
            for (String locale : localesArr) {
                LocaleInfo localeInfo = LocaleInfo.createWithString(locale);
                if (localeInfo != null) {
                    otherLanguages.add(localeInfo);
                }
            }
        }
        locales = preferences.getString("remote", null);
        if (!TextUtils.isEmpty(locales)) {
            String[] localesArr = locales.split("&");
            for (String locale : localesArr) {
                LocaleInfo localeInfo = LocaleInfo.createWithString(locale);
                localeInfo.shortName = localeInfo.shortName.replace("-", "_");
                if (remoteLanguagesDict.containsKey(localeInfo.getKey())) {
                    continue;
                }
                remoteLanguages.add(localeInfo);
                remoteLanguagesDict.put(localeInfo.getKey(), localeInfo);
            }
        }
        locales = preferences.getString("unofficial", null);
        if (!TextUtils.isEmpty(locales)) {
            String[] localesArr = locales.split("&");
            for (String locale : localesArr) {
                LocaleInfo localeInfo = LocaleInfo.createWithString(locale);
                if (localeInfo == null) {
                    continue;
                }
                localeInfo.shortName = localeInfo.shortName.replace("-", "_");
                unofficialLanguages.add(localeInfo);
            }
        }
    }

    private HashMap<String, String> getLocaleFileStrings(File file) {
        return getLocaleFileStrings(file, false);
    }

    private HashMap<String, String> getLocaleFileStrings(File file, boolean preserveEscapes) {
        FileInputStream stream = null;
        reloadLastFile = false;
        try {
            if (!file.exists()) {
                return new HashMap<>();
            }
            HashMap<String, String> stringMap = new HashMap<>();
            XmlPullParser parser = Xml.newPullParser();
            //AndroidUtilities.copyFile(file, new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "locale10.xml"));
            stream = new FileInputStream(file);
            parser.setInput(stream, "UTF-8");
            int eventType = parser.getEventType();
            String name = null;
            String value = null;
            String attrName = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    name = parser.getName();
                    int c = parser.getAttributeCount();
                    if (c > 0) {
                        attrName = parser.getAttributeValue(0);
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (attrName != null) {
                        value = parser.getText();
                        if (value != null) {
                            value = value.trim();
                            if (preserveEscapes) {
                                value = value.replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace("& ", "&amp; ");
                            } else {
                                value = value.replace("\\n", "\n");
                                value = value.replace("\\", "");
                                String old = value;
                                value = value.replace("&lt;", "<");
                                if (!reloadLastFile && !value.equals(old)) {
                                    reloadLastFile = true;
                                }
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    value = null;
                    attrName = null;
                    name = null;
                }
                if (name != null && name.equals("string") && value != null && attrName != null && value.length() != 0 && attrName.length() != 0) {
                    stringMap.put(attrName, value);
                    name = null;
                    value = null;
                    attrName = null;
                }
                eventType = parser.next();
            }
            return stringMap;
        } catch (Exception e) {
            FileLog.e(e);
            reloadLastFile = true;
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return new HashMap<>();
    }

    public void applyLanguage(LocaleInfo localeInfo, boolean override, boolean init, final int currentAccount) {
        applyLanguage(localeInfo, override, init, false, false, currentAccount);
    }

    public void applyLanguage(final LocaleInfo localeInfo, boolean override, boolean init, boolean fromFile, boolean force, final int currentAccount) {
        if (localeInfo == null) {
            return;
        }
        boolean hasBase = localeInfo.hasBaseLang();
        File pathToFile = localeInfo.getPathToFile();
        File pathToBaseFile = localeInfo.getPathToBaseFile();
        String shortName = localeInfo.shortName;
        if (!init) {
            ConnectionsManager.setLangCode(localeInfo.getLangCode());
        }
        LocaleInfo existingInfo = getLanguageFromDict(localeInfo.getKey());
        if (existingInfo == null) {
            if (localeInfo.isRemote()) {
                remoteLanguages.add(localeInfo);
                remoteLanguagesDict.put(localeInfo.getKey(), localeInfo);
                languages.add(localeInfo);
                languagesDict.put(localeInfo.getKey(), localeInfo);
                saveOtherLanguages();
            } else if (localeInfo.isUnofficial()) {
                unofficialLanguages.add(localeInfo);
                languagesDict.put(localeInfo.getKey(), localeInfo);
                saveOtherLanguages();
            }
        }
        boolean isLoadingRemote = false;
        if ((localeInfo.isRemote() || localeInfo.isUnofficial()) && (force || !pathToFile.exists() || hasBase && !pathToBaseFile.exists())) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("reload locale because one of file doesn't exist" + pathToFile + " " + pathToBaseFile);
            }
            isLoadingRemote = true;
            if (init) {
                AndroidUtilities.runOnUIThread(() -> applyRemoteLanguage(localeInfo, null, true, currentAccount));
            } else {
                applyRemoteLanguage(localeInfo, null, true, currentAccount);
            }
        }
        try {
            Locale newLocale;
            String[] args;
            if (!TextUtils.isEmpty(localeInfo.pluralLangCode)) {
                args = localeInfo.pluralLangCode.split("_");
            } else if (!TextUtils.isEmpty(localeInfo.baseLangCode)) {
                args = localeInfo.baseLangCode.split("_");
            } else {
                args = localeInfo.shortName.split("_");
            }
            if (args.length == 1) {
                newLocale = new Locale(args[0]);
            } else {
                newLocale = new Locale(args[0], args[1]);
            }
            if (override) {
                languageOverride = localeInfo.shortName;

                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("language", localeInfo.getKey());
                editor.commit();
            }
            if (pathToFile == null) {
                localeValues.clear();
            } else if (!fromFile) {
                localeValues = getLocaleFileStrings(hasBase ? localeInfo.getPathToBaseFile() : localeInfo.getPathToFile());
                if (hasBase) {
                    localeValues.putAll(getLocaleFileStrings(localeInfo.getPathToFile()));
                }
            }
            currentLocale = newLocale;
            currentLocaleInfo = localeInfo;

            if (!TextUtils.isEmpty(currentLocaleInfo.pluralLangCode)) {
                currentPluralRules = allRules.get(currentLocaleInfo.pluralLangCode);
            }
            if (currentPluralRules == null) {
                currentPluralRules = allRules.get(args[0]);
                if (currentPluralRules == null) {
                    currentPluralRules = allRules.get(currentLocale.getLanguage());
                    if (currentPluralRules == null) {
                        currentPluralRules = new PluralRules_None();
                    }
                }
            }
            changingConfiguration = true;
            Locale.setDefault(currentLocale);
            android.content.res.Configuration config = new android.content.res.Configuration();
            config.locale = currentLocale;
            ApplicationLoader.applicationContext.getResources().updateConfiguration(config, ApplicationLoader.applicationContext.getResources().getDisplayMetrics());
            changingConfiguration = false;
            if (reloadLastFile) {
                if (init) {
                    AndroidUtilities.runOnUIThread(() -> reloadCurrentRemoteLocale(currentAccount, null, force));
                } else {
                    reloadCurrentRemoteLocale(currentAccount, null, force);
                }
                reloadLastFile = false;
            }
            if (!isLoadingRemote) {
                if (init) {
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface));
                } else {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            changingConfiguration = false;
        }
        recreateFormatters();
        if (force) {
            MediaDataController.getInstance(currentAccount).loadAttachMenuBots(false, true);
        }
    }

    public LocaleInfo getCurrentLocaleInfo() {
        return currentLocaleInfo;
    }

    public Locale getCurrentLocale() {
        return currentLocale;
    }

    public static String getCurrentLanguageName() {
        LocaleInfo localeInfo = getInstance().currentLocaleInfo;
        return localeInfo == null || TextUtils.isEmpty(localeInfo.name) ? getString("LanguageName", R.string.LanguageName) : localeInfo.name;
    }

    private String getStringInternal(String key, int res) {
        return getStringInternal(key, null, res);
    }

    private String getStringInternal(String key, String fallback, int res) {
        if (key.equals("AppName")) {
            try {
                return ApplicationLoader.applicationContext.getString(R.string.exteraAppName);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (key.equals("AppNameBeta")) {
            try {
                return ApplicationLoader.applicationContext.getString(R.string.exteraAppNameBeta);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        String value = BuildVars.USE_CLOUD_STRINGS ? localeValues.get(key) : null;
        if (value == null) {
            if (BuildVars.USE_CLOUD_STRINGS && fallback != null) {
                value = localeValues.get(fallback);
            }
            if (value == null) {
                try {
                    value = ApplicationLoader.applicationContext.getString(res);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        if (value == null) {
            value = "LOC_ERR:" + key;
        }
        return value;
    }

    public static String getServerString(String key) {
        String value = getInstance().localeValues.get(key);
        if (value == null) {
            int resourceId = ApplicationLoader.applicationContext.getResources().getIdentifier(key, "string", ApplicationLoader.applicationContext.getPackageName());
            if (resourceId != 0) {
                value = ApplicationLoader.applicationContext.getString(resourceId);
            }
        }
        return value;
    }

    public static String getString(@StringRes int res) {
        String key = resourcesCacheMap.get(res);
        if (key == null) {
            resourcesCacheMap.put(res, key = ApplicationLoader.applicationContext.getResources().getResourceEntryName(res));
        }
        return getString(key, res);
    }

    public static String getString(String key, int res) {
        return getInstance().getStringInternal(key, res);
    }

    public static String getString(String key, String fallback, int res) {
        return getInstance().getStringInternal(key, fallback, res);
    }

    public static String getString(String key) {
        if (TextUtils.isEmpty(key)) {
            return "LOC_ERR:" + key;
        }
        int resourceId = ApplicationLoader.applicationContext.getResources().getIdentifier(key, "string", ApplicationLoader.applicationContext.getPackageName());
        if (resourceId != 0) {
            return getString(key, resourceId);
        }
        return getServerString(key);
    }

    public static String getPluralString(String key, int plural) {
        if (key == null || key.length() == 0 || getInstance().currentPluralRules == null) {
            return "LOC_ERR:" + key;
        }
        String param = getInstance().stringForQuantity(getInstance().currentPluralRules.quantityForNumber(plural));
        param = key + "_" + param;
        int resourceId = ApplicationLoader.applicationContext.getResources().getIdentifier(param, "string", ApplicationLoader.applicationContext.getPackageName());
        return getString(param, key + "_other", resourceId);
    }

    public static String formatPluralString(String key, int plural, Object... args) {
        if (key == null || key.length() == 0 || getInstance().currentPluralRules == null) {
            return "LOC_ERR:" + key;
        }
        String param = getInstance().stringForQuantity(getInstance().currentPluralRules.quantityForNumber(plural));
        param = key + "_" + param;
        int resourceId = ApplicationLoader.applicationContext.getResources().getIdentifier(param, "string", ApplicationLoader.applicationContext.getPackageName());
        Object[] argsWithPlural = new Object[args.length + 1];
        argsWithPlural[0] = plural;
        System.arraycopy(args, 0, argsWithPlural, 1, args.length);
        return formatString(param, key + "_other", resourceId, argsWithPlural);
    }

    public static String formatPluralStringComma(String key, int plural) {
        try {
            if (key == null || key.length() == 0 || getInstance().currentPluralRules == null) {
                return "LOC_ERR:" + key;
            }
            String param = getInstance().stringForQuantity(getInstance().currentPluralRules.quantityForNumber(plural));
            param = key + "_" + param;
            StringBuilder stringBuilder = new StringBuilder(String.format(Locale.US, "%d", plural));
            for (int a = stringBuilder.length() - 3; a > 0; a -= 3) {
                stringBuilder.insert(a, ',');
            }

            String value = BuildVars.USE_CLOUD_STRINGS ? getInstance().localeValues.get(param) : null;
            if (value == null) {
                value = BuildVars.USE_CLOUD_STRINGS ? getInstance().localeValues.get(key + "_other") : null;
            }
            if (value == null) {
                int resourceId = ApplicationLoader.applicationContext.getResources().getIdentifier(param, "string", ApplicationLoader.applicationContext.getPackageName());
                value = ApplicationLoader.applicationContext.getString(resourceId);
            }
            value = value.replace("%1$d", "%1$s");

            if (getInstance().currentLocale != null) {
                return String.format(getInstance().currentLocale, value, stringBuilder);
            } else {
                return String.format(value, stringBuilder);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return "LOC_ERR: " + key;
        }
    }

    public static String formatString(@StringRes int res, Object... args) {
        String key = resourcesCacheMap.get(res);
        if (key == null) {
            resourcesCacheMap.put(res, key = ApplicationLoader.applicationContext.getResources().getResourceEntryName(res));
        }
        return formatString(key, res, args);
    }

    public static String formatString(String key, int res, Object... args) {
        return formatString(key, null, res, args);
    }

    public static String formatString(String key, String fallback, int res, Object... args) {
        try {
            String value = BuildVars.USE_CLOUD_STRINGS ? getInstance().localeValues.get(key) : null;
            if (value == null) {
                if (BuildVars.USE_CLOUD_STRINGS && fallback != null) {
                    value = getInstance().localeValues.get(fallback);
                }
                if (value == null) {
                    value = ApplicationLoader.applicationContext.getString(res);
                }
            }

            if (getInstance().currentLocale != null) {
                return String.format(getInstance().currentLocale, value, args);
            } else {
                return String.format(value, args);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return "LOC_ERR: " + key;
        }
    }

    public static String formatTTLString(int ttl) {
        if (ttl < 60) {
            return LocaleController.formatPluralString("Seconds", ttl);
        } else if (ttl < 60 * 60) {
            return LocaleController.formatPluralString("Minutes", ttl / 60);
        } else if (ttl < 60 * 60 * 24) {
            return LocaleController.formatPluralString("Hours", ttl / 60 / 60);
        } else if (ttl < 60 * 60 * 24 * 7) {
            return LocaleController.formatPluralString("Days", ttl / 60 / 60 / 24);
        } else if (ttl < 60 * 60 * 24 * 31) {
            int days = ttl / 60 / 60 / 24;
            if (ttl % 7 == 0) {
                return LocaleController.formatPluralString("Weeks", days / 7);
            } else {
                return String.format("%s %s", LocaleController.formatPluralString("Weeks", days / 7), LocaleController.formatPluralString("Days", days % 7));
            }
        } else {
            return LocaleController.formatPluralString("Months", ttl / 60 / 60 / 24 / 30);
        }
    }

    private static char[] defaultNumbers = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
    private static char[][] otherNumbers = new char[][]{
            {'??', '??', '??', '??', '??', '??', '??', '??', '??', '??'},
            {'??', '??', '??', '??', '??', '??', '??', '??', '??', '??'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'},
            {'???', '???', '???', '???', '???', '???', '???', '???', '???', '???'}
    };

    public static String fixNumbers(CharSequence numbers) {
        StringBuilder builder = new StringBuilder(numbers);
        for (int c = 0, N = builder.length(); c < N; c++) {
            char ch = builder.charAt(c);
            if (ch >= '0' && ch <= '9' || ch == '.' || ch == ',') {
                continue;
            }
            for (int a = 0; a < otherNumbers.length; a++) {
                for (int b = 0; b < otherNumbers[a].length; b++) {
                    if (ch == otherNumbers[a][b]) {
                        builder.setCharAt(c, defaultNumbers[b]);
                        a = otherNumbers.length;
                        break;
                    }
                }
            }
        }
        return builder.toString();
    }

    public String formatCurrencyString(long amount, String type) {
        return formatCurrencyString(amount, true, true, false, type);
    }

    public String formatCurrencyString(long amount, boolean fixAnything, boolean withExp, boolean editText, String type) {
        type = type.toUpperCase();
        String customFormat;
        double doubleAmount;
        boolean discount = amount < 0;
        amount = Math.abs(amount);
        Currency currency = Currency.getInstance(type);
        switch (type) {
            case "CLF":
                customFormat = " %.4f";
                doubleAmount = amount / 10000.0;
                break;

            case "IRR":
                doubleAmount = amount / 100.0f;
                if (fixAnything && amount % 100 == 0) {
                    customFormat = " %.0f";
                } else {
                    customFormat = " %.2f";
                }
                break;

            case "BHD":
            case "IQD":
            case "JOD":
            case "KWD":
            case "LYD":
            case "OMR":
            case "TND":
                customFormat = " %.3f";
                doubleAmount = amount / 1000.0;
                break;

            case "BIF":
            case "BYR":
            case "CLP":
            case "CVE":
            case "DJF":
            case "GNF":
            case "ISK":
            case "JPY":
            case "KMF":
            case "KRW":
            case "MGA":
            case "PYG":
            case "RWF":
            case "UGX":
            case "UYI":
            case "VND":
            case "VUV":
            case "XAF":
            case "XOF":
            case "XPF":
                customFormat = " %.0f";
                doubleAmount = amount;
                break;

            case "MRO":
                customFormat = " %.1f";
                doubleAmount = amount / 10.0;
                break;

            default:
                customFormat = " %.2f";
                doubleAmount = amount / 100.0;
                break;
        }
        if (!withExp) {
            customFormat = " %.0f";
        }
        if (currency != null) {
            NumberFormat format = NumberFormat.getCurrencyInstance(currentLocale != null ? currentLocale : systemDefaultLocale);
            format.setCurrency(currency);
            if (editText) {
                format.setGroupingUsed(false);
            }
            if (!withExp || fixAnything && type.equals("IRR")) {
                format.setMaximumFractionDigits(0);
            }
            String result = (discount ? "-" : "") + format.format(doubleAmount);
            int idx = result.indexOf(type);
            if (idx >= 0) {
                idx += type.length();
                if (idx < result.length() && result.charAt(idx) != ' ') {
                    result = result.substring(0, idx) + " " + result.substring(idx);
                }
            }
            return result;
        }
        return (discount ? "-" : "") + String.format(Locale.US, type + customFormat, doubleAmount);
    }

    public static int getCurrencyExpDivider(String type) {
        switch (type) {
            case "CLF":
                return 10000;
            case "BHD":
            case "IQD":
            case "JOD":
            case "KWD":
            case "LYD":
            case "OMR":
            case "TND":
                return 1000;
            case "BIF":
            case "BYR":
            case "CLP":
            case "CVE":
            case "DJF":
            case "GNF":
            case "ISK":
            case "JPY":
            case "KMF":
            case "KRW":
            case "MGA":
            case "PYG":
            case "RWF":
            case "UGX":
            case "UYI":
            case "VND":
            case "VUV":
            case "XAF":
            case "XOF":
            case "XPF":
                return 1;
            case "MRO":
                return 10;
            default:
                return 100;
        }
    }

    public String formatCurrencyDecimalString(long amount, String type, boolean inludeType) {
        type = type.toUpperCase();
        String customFormat;
        double doubleAmount;
        amount = Math.abs(amount);
        switch (type) {
            case "CLF":
                customFormat = " %.4f";
                doubleAmount = amount / 10000.0;
                break;

            case "IRR":
                doubleAmount = amount / 100.0f;
                if (amount % 100 == 0) {
                    customFormat = " %.0f";
                } else {
                    customFormat = " %.2f";
                }
                break;

            case "BHD":
            case "IQD":
            case "JOD":
            case "KWD":
            case "LYD":
            case "OMR":
            case "TND":
                customFormat = " %.3f";
                doubleAmount = amount / 1000.0;
                break;

            case "BIF":
            case "BYR":
            case "CLP":
            case "CVE":
            case "DJF":
            case "GNF":
            case "ISK":
            case "JPY":
            case "KMF":
            case "KRW":
            case "MGA":
            case "PYG":
            case "RWF":
            case "UGX":
            case "UYI":
            case "VND":
            case "VUV":
            case "XAF":
            case "XOF":
            case "XPF":
                customFormat = " %.0f";
                doubleAmount = amount;
                break;

            case "MRO":
                customFormat = " %.1f";
                doubleAmount = amount / 10.0;
                break;

            default:
                customFormat = " %.2f";
                doubleAmount = amount / 100.0;
                break;
        }
        return String.format(Locale.US, inludeType ? type : "" + customFormat, doubleAmount).trim();
    }

    public static String formatStringSimple(String string, Object... args) {
        try {
            if (getInstance().currentLocale != null) {
                return String.format(getInstance().currentLocale, string, args);
            } else {
                return String.format(string, args);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return "LOC_ERR: " + string;
        }
    }

    public static String formatDuration(int duration) {
        if (duration <= 0) {
            return formatPluralString("Seconds", 0);
        }
        final int hours = duration / 3600;
        final int minutes = duration / 60 % 60;
        final int seconds = duration % 60;
        final StringBuilder stringBuilder = new StringBuilder();
        if (hours > 0) {
            stringBuilder.append(formatPluralString("Hours", hours));
        }
        if (minutes > 0) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(' ');
            }
            stringBuilder.append(formatPluralString("Minutes", minutes));
        }
        if (seconds > 0) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(' ');
            }
            stringBuilder.append(formatPluralString("Seconds", seconds));
        }
        return stringBuilder.toString();
    }

    public static String formatCallDuration(int duration) {
        if (duration > 3600) {
            String result = LocaleController.formatPluralString("Hours", duration / 3600);
            int minutes = duration % 3600 / 60;
            if (minutes > 0) {
                result += ", " + LocaleController.formatPluralString("Minutes", minutes);
            }
            return result;
        } else if (duration > 60) {
            return LocaleController.formatPluralString("Minutes", duration / 60);
        } else {
            return LocaleController.formatPluralString("Seconds", duration);
        }
    }

    public void onDeviceConfigurationChange(Configuration newConfig) {
        if (changingConfiguration) {
            return;
        }
        is24HourFormat = DateFormat.is24HourFormat(ApplicationLoader.applicationContext);
        systemDefaultLocale = newConfig.locale;
        if (languageOverride != null) {
            LocaleInfo toSet = currentLocaleInfo;
            currentLocaleInfo = null;
            applyLanguage(toSet, false, false, UserConfig.selectedAccount);
        } else {
            Locale newLocale = newConfig.locale;
            if (newLocale != null) {
                String d1 = newLocale.getDisplayName();
                String d2 = currentLocale.getDisplayName();
                if (d1 != null && d2 != null && !d1.equals(d2)) {
                    recreateFormatters();
                }
                currentLocale = newLocale;
                if (currentLocaleInfo != null && !TextUtils.isEmpty(currentLocaleInfo.pluralLangCode)) {
                    currentPluralRules = allRules.get(currentLocaleInfo.pluralLangCode);
                }
                if (currentPluralRules == null) {
                    currentPluralRules = allRules.get(currentLocale.getLanguage());
                    if (currentPluralRules == null) {
                        currentPluralRules = allRules.get("en");
                    }
                }
            }
        }
        String newSystemLocale = getSystemLocaleStringIso639();
        if (currentSystemLocale != null && !newSystemLocale.equals(currentSystemLocale)) {
            currentSystemLocale = newSystemLocale;
            ConnectionsManager.setSystemLangCode(currentSystemLocale);
        }
    }

    public static String formatDateChat(long date) {
        return formatDateChat(date, false);
    }

    public static String formatDateChat(long date, boolean checkYear) {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            int currentYear = calendar.get(Calendar.YEAR);
            date *= 1000;

            calendar.setTimeInMillis(date);
            if (currentYear == calendar.get(Calendar.YEAR)) {
                return getInstance().chatDate.format(date);
            }
            return getInstance().chatFullDate.format(date);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR: formatDateChat";
    }

    public static String formatDate(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                return getInstance().formatterDay.format(new Date(date));
            } else if (dateDay + 1 == day && year == dateYear) {
                return getString("Yesterday", R.string.Yesterday);
            } else if (dateYear == year) {
                return getInstance().formatterDayMonth.format(new Date(date));
            } else {
                return getInstance().formatterYear.format(new Date(date));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR: formatDate";
    }

    public static String formatFwdDate(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateYear = rightNow.get(Calendar.YEAR);
            
            if (dateYear == year) {
                return LocaleController.formatString("formatFwdDate", R.string.formatFwdDate, getInstance().chatDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            } else {
                return LocaleController.formatString("formatFwdDate", R.string.formatFwdDate, getInstance().chatFullDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR: formatDate";
    }

    public static String formatDateAudio(long date, boolean shortFormat) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                if (shortFormat) {
                    return LocaleController.formatString("TodayAtFormatted", R.string.TodayAtFormatted, getInstance().formatterDay.format(new Date(date)));
                } else {
                    return LocaleController.formatString("TodayAtFormattedWithToday", R.string.TodayAtFormattedWithToday, getInstance().formatterDay.format(new Date(date)));
                }
            } else if (dateDay + 1 == day && year == dateYear) {
                return LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date)));
            } else if (dateYear == year) {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterDayMonth.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            } else {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterYear.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatDateCallLog(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                return getInstance().formatterDay.format(new Date(date));
            } else if (dateDay + 1 == day && year == dateYear) {
                return LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date)));
            } else if (dateYear == year) {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().chatDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            } else {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().chatFullDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatStatusExpireDateTime(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                return LocaleController.formatString("TodayAtFormatted", R.string.TodayAtFormatted, getInstance().formatterDay.format(new Date(date)));
            } else if (Math.abs(System.currentTimeMillis() - date) < 31536000000L) {
                return getInstance().formatterScheduleDay.format(new Date(date));
            } else {
                return getInstance().chatFullDate.format(new Date(date));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatDateTime(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                return LocaleController.formatString("TodayAtFormattedWithToday", R.string.TodayAtFormattedWithToday, getInstance().formatterDay.format(new Date(date)));
            } else if (dateDay + 1 == day && year == dateYear) {
                return LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date)));
            } else if (dateYear == year) {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().chatDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            } else {
                return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().chatFullDate.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatLocationUpdateDate(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                int diff = (int) (ConnectionsManager.getInstance(UserConfig.selectedAccount).getCurrentTime() - date / 1000) / 60;
                if (diff < 1) {
                    return LocaleController.getString("LocationUpdatedJustNow", R.string.LocationUpdatedJustNow);
                } else if (diff < 60) {
                    return LocaleController.formatPluralString("UpdatedMinutes", diff);
                }
                return LocaleController.formatString("LocationUpdatedFormatted", R.string.LocationUpdatedFormatted, LocaleController.formatString("TodayAtFormatted", R.string.TodayAtFormatted, getInstance().formatterDay.format(new Date(date))));
            } else if (dateDay + 1 == day && year == dateYear) {
                return LocaleController.formatString("LocationUpdatedFormatted", R.string.LocationUpdatedFormatted, LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date))));
            } else if (dateYear == year) {
                String format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterDayMonth.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
                return LocaleController.formatString("LocationUpdatedFormatted", R.string.LocationUpdatedFormatted, format);
            } else {
                String format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterYear.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
                return LocaleController.formatString("LocationUpdatedFormatted", R.string.LocationUpdatedFormatted, format);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatLocationLeftTime(int time) {
        String text;
        int hours = time / 60 / 60;
        time -= hours * 60 * 60;
        int minutes = time / 60;
        time -= minutes * 60;
        if (hours != 0) {
            text = String.format("%dh", hours + (minutes > 30 ? 1 : 0));
        } else if (minutes != 0) {
            text = String.format("%d", minutes + (time > 30 ? 1 : 0));
        } else {
            text = String.format("%d", time);
        }
        return text;
    }

    public static String formatDateOnline(long date, boolean[] madeShorter) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            int hour = rightNow.get(Calendar.HOUR_OF_DAY);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);
            int dateHour = rightNow.get(Calendar.HOUR_OF_DAY);

            if (dateDay == day && year == dateYear) {
                return LocaleController.formatString("LastSeenFormatted", R.string.LastSeenFormatted, LocaleController.formatString("TodayAtFormatted", R.string.TodayAtFormatted, getInstance().formatterDay.format(new Date(date))));
                /*int diff = (int) (ConnectionsManager.getInstance().getCurrentTime() - date) / 60;
                if (diff < 1) {
                    return LocaleController.getString("LastSeenNow", R.string.LastSeenNow);
                } else if (diff < 60) {
                    return LocaleController.formatPluralString("LastSeenMinutes", diff);
                } else {
                    return LocaleController.formatPluralString("LastSeenHours", (int) Math.ceil(diff / 60.0f));
                }*/
            } else if (dateDay + 1 == day && year == dateYear) {
                if (madeShorter != null) {
                    madeShorter[0] = true;
                    if (hour <= 6 && dateHour > 18 && is24HourFormat) {
                        return LocaleController.formatString("LastSeenFormatted", R.string.LastSeenFormatted, getInstance().formatterDay.format(new Date(date)));
                    }
                    return LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date)));
                } else {
                    return LocaleController.formatString("LastSeenFormatted", R.string.LastSeenFormatted, LocaleController.formatString("YesterdayAtFormatted", R.string.YesterdayAtFormatted, getInstance().formatterDay.format(new Date(date))));
                }
            } else if (Math.abs(System.currentTimeMillis() - date) < 31536000000L) {
                String format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterDayMonth.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
                return LocaleController.formatString("LastSeenDateFormatted", R.string.LastSeenDateFormatted, format);
            } else {
                String format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterYear.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
                return LocaleController.formatString("LastSeenDateFormatted", R.string.LastSeenDateFormatted, format);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    private FastDateFormat createFormatter(Locale locale, String format, String defaultFormat) {
        if (format == null || format.length() == 0) {
            format = defaultFormat;
        }
        FastDateFormat formatter;
        try {
            formatter = FastDateFormat.getInstance(format, locale);
        } catch (Exception e) {
            format = defaultFormat;
            formatter = FastDateFormat.getInstance(format, locale);
        }
        return formatter;
    }

    public void recreateFormatters() {
        Locale locale = currentLocale;
        if (locale == null) {
            locale = Locale.getDefault();
        }
        String lang = locale.getLanguage();
        if (lang == null) {
            lang = "en";
        }
        lang = lang.toLowerCase();
        isRTL = lang.length() == 2 && (lang.equals("ar") || lang.equals("fa") || lang.equals("he") || lang.equals("iw")) ||
                lang.startsWith("ar_") || lang.startsWith("fa_") || lang.startsWith("he_") || lang.startsWith("iw_")
                || currentLocaleInfo != null && currentLocaleInfo.isRtl;
        nameDisplayOrder = lang.equals("ko") ? 2 : 1;

        formatterMonthYear = createFormatter(locale, getStringInternal("formatterMonthYear", R.string.formatterMonthYear), "MMM yyyy");
        formatterDayMonth = createFormatter(locale, getStringInternal("formatterMonth", R.string.formatterMonth), "dd MMM");
        formatterYear = createFormatter(locale, getStringInternal("formatterYear", R.string.formatterYear), "dd.MM.yy");
        formatterYearMax = createFormatter(locale, getStringInternal("formatterYearMax", R.string.formatterYearMax), "dd.MM.yyyy");
        chatDate = createFormatter(locale, getStringInternal("chatDate", R.string.chatDate), "d MMMM");
        chatFullDate = createFormatter(locale, getStringInternal("chatFullDate", R.string.chatFullDate), "d MMMM yyyy");
        formatterWeek = createFormatter(locale, getStringInternal("formatterWeek", R.string.formatterWeek), "EEE");
        formatterWeekLong = createFormatter(locale, getStringInternal("formatterWeekLong", R.string.formatterWeekLong), "EEEE");
        formatterScheduleDay = createFormatter(locale, getStringInternal("formatDateSchedule", R.string.formatDateSchedule), "MMM d");
        formatterScheduleYear = createFormatter(locale, getStringInternal("formatDateScheduleYear", R.string.formatDateScheduleYear), "MMM d yyyy");
        formatterDay = createFormatter(lang.toLowerCase().equals("ar") || lang.toLowerCase().equals("ko") ? locale : Locale.US, is24HourFormat ? (ExteraConfig.formatTimeWithSeconds ? getStringInternal("formatterDay24HSec", R.string.formatterDay24HSec) : getStringInternal("formatterDay24H", R.string.formatterDay24H)) : (ExteraConfig.formatTimeWithSeconds ? getStringInternal("formatterDay12HSec", R.string.formatterDay12HSec) : getStringInternal("formatterDay12H", R.string.formatterDay12H)), is24HourFormat ? (ExteraConfig.formatTimeWithSeconds ? "HH:mm:ss" : "HH:mm") : (ExteraConfig.formatTimeWithSeconds ? "h:mm:ss a" : "h:mm a"));
        formatterStats = createFormatter(locale, is24HourFormat ? getStringInternal("formatterStats24H", R.string.formatterStats24H) : getStringInternal("formatterStats12H", R.string.formatterStats12H), is24HourFormat ? "MMM dd yyyy, HH:mm" : "MMM dd yyyy, h:mm a");
        formatterBannedUntil = createFormatter(locale, is24HourFormat ? getStringInternal("formatterBannedUntil24H", R.string.formatterBannedUntil24H) : getStringInternal("formatterBannedUntil12H", R.string.formatterBannedUntil12H), is24HourFormat ? "MMM dd yyyy, HH:mm" : "MMM dd yyyy, h:mm a");
        formatterBannedUntilThisYear = createFormatter(locale, is24HourFormat ? getStringInternal("formatterBannedUntilThisYear24H", R.string.formatterBannedUntilThisYear24H) : getStringInternal("formatterBannedUntilThisYear12H", R.string.formatterBannedUntilThisYear12H), is24HourFormat ? "MMM dd, HH:mm" : "MMM dd, h:mm a");
        formatterScheduleSend[0] = createFormatter(locale, getStringInternal("SendTodayAt", R.string.SendTodayAt), "'Send today at' HH:mm");
        formatterScheduleSend[1] = createFormatter(locale, getStringInternal("SendDayAt", R.string.SendDayAt), "'Send on' MMM d 'at' HH:mm");
        formatterScheduleSend[2] = createFormatter(locale, getStringInternal("SendDayYearAt", R.string.SendDayYearAt), "'Send on' MMM d yyyy 'at' HH:mm");
        formatterScheduleSend[3] = createFormatter(locale, getStringInternal("RemindTodayAt", R.string.RemindTodayAt), "'Remind today at' HH:mm");
        formatterScheduleSend[4] = createFormatter(locale, getStringInternal("RemindDayAt", R.string.RemindDayAt), "'Remind on' MMM d 'at' HH:mm");
        formatterScheduleSend[5] = createFormatter(locale, getStringInternal("RemindDayYearAt", R.string.RemindDayYearAt), "'Remind on' MMM d yyyy 'at' HH:mm");
        formatterScheduleSend[6] = createFormatter(locale, getStringInternal("StartTodayAt", R.string.StartTodayAt), "'Start today at' HH:mm");
        formatterScheduleSend[7] = createFormatter(locale, getStringInternal("StartDayAt", R.string.StartDayAt), "'Start on' MMM d 'at' HH:mm");
        formatterScheduleSend[8] = createFormatter(locale, getStringInternal("StartDayYearAt", R.string.StartDayYearAt), "'Start on' MMM d yyyy 'at' HH:mm");
        formatterScheduleSend[9] = createFormatter(locale, getStringInternal("StartShortTodayAt", R.string.StartShortTodayAt), "'Today,' HH:mm");
        formatterScheduleSend[10] = createFormatter(locale, getStringInternal("StartShortDayAt", R.string.StartShortDayAt), "MMM d',' HH:mm");
        formatterScheduleSend[11] = createFormatter(locale, getStringInternal("StartShortDayYearAt", R.string.StartShortDayYearAt), "MMM d yyyy, HH:mm");
        formatterScheduleSend[12] = createFormatter(locale, getStringInternal("StartsTodayAt", R.string.StartsTodayAt), "'Starts today at' HH:mm");
        formatterScheduleSend[13] = createFormatter(locale, getStringInternal("StartsDayAt", R.string.StartsDayAt), "'Starts on' MMM d 'at' HH:mm");
        formatterScheduleSend[14] = createFormatter(locale, getStringInternal("StartsDayYearAt", R.string.StartsDayYearAt), "'Starts on' MMM d yyyy 'at' HH:mm");
    }

    public static boolean isRTLCharacter(char ch) {
        return Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT || Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC || Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING || Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE;
    }

    public static String formatStartsTime(long date, int type) {
        return formatStartsTime(date, type, true);
    }

    public static String formatStartsTime(long date, int type, boolean needToday) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        int currentYear = calendar.get(Calendar.YEAR);
        int currentDay = calendar.get(Calendar.DAY_OF_YEAR);

        calendar.setTimeInMillis(date * 1000);
        int selectedYear = calendar.get(Calendar.YEAR);
        int selectedDay = calendar.get(Calendar.DAY_OF_YEAR);

        int num;
        if (currentYear == selectedYear) {
            if (needToday && selectedDay == currentDay) {
                num = 0;
            } else {
                num = 1;
            }
        } else {
            num = 2;
        }
        if (type == 1) {
            num += 3;
        } else if (type == 2) {
            num += 6;
        } else if (type == 3) {
            num += 9;
        } else if (type == 4) {
            num += 12;
        }
        return LocaleController.getInstance().formatterScheduleSend[num].format(calendar.getTimeInMillis());
    }

    public static String formatSectionDate(long date) {
        return formatYearMont(date, false);
    }


    public static String formatYearMont(long date, boolean alwaysShowYear) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateYear = rightNow.get(Calendar.YEAR);
            int month = rightNow.get(Calendar.MONTH);

            final String[] months = new String[]{
                    LocaleController.getString("January", R.string.January),
                    LocaleController.getString("February", R.string.February),
                    LocaleController.getString("March", R.string.March),
                    LocaleController.getString("April", R.string.April),
                    LocaleController.getString("May", R.string.May),
                    LocaleController.getString("June", R.string.June),
                    LocaleController.getString("July", R.string.July),
                    LocaleController.getString("August", R.string.August),
                    LocaleController.getString("September", R.string.September),
                    LocaleController.getString("October", R.string.October),
                    LocaleController.getString("November", R.string.November),
                    LocaleController.getString("December", R.string.December)
            };
            if (year == dateYear && !alwaysShowYear) {
                return months[month];
            } else {
                return months[month] + " " + dateYear;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatDateForBan(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (year == dateYear) {
                return getInstance().formatterBannedUntilThisYear.format(new Date(date));
            } else {
                return getInstance().formatterBannedUntil.format(new Date(date));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String stringForMessageListDate(long date) {
        try {
            date *= 1000;
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateYear != year) {
                return getInstance().formatterYear.format(new Date(date));
            } else {
                int dayDiff = dateDay - day;
                if (dayDiff == 0 || dayDiff == -1 && System.currentTimeMillis() - date < 60 * 60 * 8 * 1000) {
                    return getInstance().formatterDay.format(new Date(date));
                } else if (dayDiff > -7 && dayDiff <= -1) {
                    return getInstance().formatterWeek.format(new Date(date));
                } else {
                    return getInstance().formatterDayMonth.format(new Date(date));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatShortNumber(int number, int[] rounded) {
        if (ExteraConfig.disableNumberRounding) {
            StringBuilder stringBuilder = new StringBuilder(String.format(Locale.US, "%d", number));
            for (int n = stringBuilder.length() - 3; n > 0; n -= 3) {
                stringBuilder.insert(n, ',');
            }
            return stringBuilder.toString();
        }
        StringBuilder K = new StringBuilder();
        int lastDec = 0;
        int KCount = 0;
        while (number / 1000 > 0) {
            K.append("K");
            lastDec = (number % 1000) / 100;
            number /= 1000;
        }
        if (rounded != null) {
            double value = number + lastDec / 10.0;
            for (int a = 0; a < K.length(); a++) {
                value *= 1000;
            }
            rounded[0] = (int) value;
        }
        if (lastDec != 0 && K.length() > 0) {
            if (K.length() == 2) {
                return String.format(Locale.US, "%d.%dM", number, lastDec);
            } else {
                return String.format(Locale.US, "%d.%d%s", number, lastDec, K.toString());
            }
        }
        if (K.length() == 2) {
            return String.format(Locale.US, "%dM", number);
        } else {
            return String.format(Locale.US, "%d%s", number, K.toString());
        }
    }

    public static String formatUserStatus(int currentAccount, TLRPC.User user) {
        return formatUserStatus(currentAccount, user, null);
    }

    public static String formatJoined(long date) {
        try {
            date *= 1000;
            String format;
            if (Math.abs(System.currentTimeMillis() - date) < 31536000000L) {
                format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterDayMonth.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            } else {
                format = LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, getInstance().formatterYear.format(new Date(date)), getInstance().formatterDay.format(new Date(date)));
            }
            return formatString("ChannelOtherSubscriberJoined", R.string.ChannelOtherSubscriberJoined, format);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatImportedDate(long date) {
        try {
            date *= 1000;
            Date dt = new Date(date);
            return String.format("%1$s, %2$s", getInstance().formatterYear.format(dt), getInstance().formatterDay.format(dt));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }

    public static String formatUserStatus(int currentAccount, TLRPC.User user, boolean[] isOnline) {
        return formatUserStatus(currentAccount, user, isOnline, null);
    }

    public static String formatUserStatus(int currentAccount, TLRPC.User user, boolean[] isOnline, boolean[] madeShorter) {
        if (user != null && user.status != null && user.status.expires == 0) {
            if (user.status instanceof TLRPC.TL_userStatusRecently) {
                user.status.expires = -100;
            } else if (user.status instanceof TLRPC.TL_userStatusLastWeek) {
                user.status.expires = -101;
            } else if (user.status instanceof TLRPC.TL_userStatusLastMonth) {
                user.status.expires = -102;
            }
        }
        if (user != null && user.status != null && user.status.expires <= 0) {
            if (MessagesController.getInstance(currentAccount).onlinePrivacy.containsKey(user.id)) {
                if (isOnline != null) {
                    isOnline[0] = true;
                }
                return getString("Online", R.string.Online);
            }
        }
        if (user == null || user.status == null || user.status.expires == 0 || UserObject.isDeleted(user) || user instanceof TLRPC.TL_userEmpty) {
            return getString("ALongTimeAgo", R.string.ALongTimeAgo);
        } else {
            int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            if (user.status.expires > currentTime) {
                if (isOnline != null) {
                    isOnline[0] = true;
                }
                return getString("Online", R.string.Online);
            } else {
                if (user.status.expires == -1) {
                    return getString("Invisible", R.string.Invisible);
                } else if (user.status.expires == -100) {
                    return getString("Lately", R.string.Lately);
                } else if (user.status.expires == -101) {
                    return getString("WithinAWeek", R.string.WithinAWeek);
                } else if (user.status.expires == -102) {
                    return getString("WithinAMonth", R.string.WithinAMonth);
                } else {
                    return formatDateOnline(user.status.expires, madeShorter);
                }
            }
        }
    }

    private String escapeString(String str) {
        if (str.contains("[CDATA")) {
            return str;
        }
        return str.replace("<", "&lt;").replace(">", "&gt;").replace("& ", "&amp; ");
    }

    public void saveRemoteLocaleStringsForCurrentLocale(final TLRPC.TL_langPackDifference difference, int currentAccount) {
        if (currentLocaleInfo == null) {
            return;
        }
        final String langCode = difference.lang_code.replace('-', '_').toLowerCase();
        if (!langCode.equals(currentLocaleInfo.shortName) && !langCode.equals(currentLocaleInfo.baseLangCode)) {
            return;
        }
        saveRemoteLocaleStrings(currentLocaleInfo, difference, currentAccount);
    }

    public void saveRemoteLocaleStrings(LocaleInfo localeInfo, final TLRPC.TL_langPackDifference difference, int currentAccount) {
        if (difference == null || difference.strings.isEmpty() || localeInfo == null || localeInfo.isLocal()) {
            return;
        }
        final String langCode = difference.lang_code.replace('-', '_').toLowerCase();
        int type;
        if (langCode.equals(localeInfo.shortName)) {
            type = 0;
        } else if (langCode.equals(localeInfo.baseLangCode)) {
            type = 1;
        } else {
            type = -1;
        }
        if (type == -1) {
            return;
        }
        File finalFile;
        if (type == 0) {
            finalFile = localeInfo.getPathToFile();
        } else {
            finalFile = localeInfo.getPathToBaseFile();
        }
        try {
            final HashMap<String, String> values;
            if (difference.from_version == 0) {
                values = new HashMap<>();
            } else {
                values = getLocaleFileStrings(finalFile, true);
            }
            for (int a = 0; a < difference.strings.size(); a++) {
                TLRPC.LangPackString string = difference.strings.get(a);
                if (string instanceof TLRPC.TL_langPackString) {
                    values.put(string.key, escapeString(string.value));
                } else if (string instanceof TLRPC.TL_langPackStringPluralized) {
                    values.put(string.key + "_zero", string.zero_value != null ? escapeString(string.zero_value) : "");
                    values.put(string.key + "_one", string.one_value != null ? escapeString(string.one_value) : "");
                    values.put(string.key + "_two", string.two_value != null ? escapeString(string.two_value) : "");
                    values.put(string.key + "_few", string.few_value != null ? escapeString(string.few_value) : "");
                    values.put(string.key + "_many", string.many_value != null ? escapeString(string.many_value) : "");
                    values.put(string.key + "_other", string.other_value != null ? escapeString(string.other_value) : "");
                } else if (string instanceof TLRPC.TL_langPackStringDeleted) {
                    values.remove(string.key);
                }
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("save locale file to " + finalFile);
            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(finalFile));
            writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
            writer.write("<resources>\n");
            for (HashMap.Entry<String, String> entry : values.entrySet()) {
                writer.write(String.format("<string name=\"%1$s\">%2$s</string>\n", entry.getKey(), entry.getValue()));
            }
            writer.write("</resources>");
            writer.close();
            boolean hasBase = localeInfo.hasBaseLang();
            final HashMap<String, String> valuesToSet = getLocaleFileStrings(hasBase ? localeInfo.getPathToBaseFile() : localeInfo.getPathToFile());
            if (hasBase) {
                valuesToSet.putAll(getLocaleFileStrings(localeInfo.getPathToFile()));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (type == 0) {
                    localeInfo.version = difference.version;
                } else {
                    localeInfo.baseVersion = difference.version;
                }
                saveOtherLanguages();
                try {
                    if (currentLocaleInfo == localeInfo) {
                        Locale newLocale;
                        String[] args;
                        if (!TextUtils.isEmpty(localeInfo.pluralLangCode)) {
                            args = localeInfo.pluralLangCode.split("_");
                        } else if (!TextUtils.isEmpty(localeInfo.baseLangCode)) {
                            args = localeInfo.baseLangCode.split("_");
                        } else {
                            args = localeInfo.shortName.split("_");
                        }
                        if (args.length == 1) {
                            newLocale = new Locale(args[0]);
                        } else {
                            newLocale = new Locale(args[0], args[1]);
                        }
                        languageOverride = localeInfo.shortName;

                        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString("language", localeInfo.getKey());
                        editor.commit();

                        localeValues = valuesToSet;
                        currentLocale = newLocale;
                        currentLocaleInfo = localeInfo;
                        if (!TextUtils.isEmpty(currentLocaleInfo.pluralLangCode)) {
                            currentPluralRules = allRules.get(currentLocaleInfo.pluralLangCode);
                        }
                        if (currentPluralRules == null) {
                            currentPluralRules = allRules.get(currentLocale.getLanguage());
                            if (currentPluralRules == null) {
                                currentPluralRules = allRules.get("en");
                            }
                        }
                        changingConfiguration = true;
                        Locale.setDefault(currentLocale);
                        Configuration config = new Configuration();
                        config.locale = currentLocale;
                        ApplicationLoader.applicationContext.getResources().updateConfiguration(config, ApplicationLoader.applicationContext.getResources().getDisplayMetrics());
                        changingConfiguration = false;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    changingConfiguration = false;
                }
                recreateFormatters();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            });
        } catch (Exception ignore) {

        }
    }

    public void loadRemoteLanguages(final int currentAccount) {
        loadRemoteLanguages(currentAccount, true);
    }

    public void loadRemoteLanguages(final int currentAccount, boolean applyCurrent) {
        if (loadingRemoteLanguages) {
            return;
        }
        loadingRemoteLanguages = true;
        TLRPC.TL_langpack_getLanguages req = new TLRPC.TL_langpack_getLanguages();
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    loadingRemoteLanguages = false;
                    TLRPC.Vector res = (TLRPC.Vector) response;
                    for (int a = 0, size = remoteLanguages.size(); a < size; a++) {
                        remoteLanguages.get(a).serverIndex = Integer.MAX_VALUE;
                    }
                    for (int a = 0, size = res.objects.size(); a < size; a++) {
                        TLRPC.TL_langPackLanguage language = (TLRPC.TL_langPackLanguage) res.objects.get(a);
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("loaded lang " + language.name);
                        }
                        LocaleInfo localeInfo = new LocaleInfo();
                        localeInfo.nameEnglish = language.name;
                        localeInfo.name = language.native_name;
                        localeInfo.shortName = language.lang_code.replace('-', '_').toLowerCase();
                        if (language.base_lang_code != null) {
                            localeInfo.baseLangCode = language.base_lang_code.replace('-', '_').toLowerCase();
                        } else {
                            localeInfo.baseLangCode = "";
                        }
                        localeInfo.pluralLangCode = language.plural_code.replace('-', '_').toLowerCase();
                        localeInfo.isRtl = language.rtl;
                        localeInfo.pathToFile = "remote";
                        localeInfo.serverIndex = a;

                        LocaleInfo existing = getLanguageFromDict(localeInfo.getKey());
                        if (existing == null) {
                            languages.add(localeInfo);
                            languagesDict.put(localeInfo.getKey(), localeInfo);
                        } else {
                            existing.nameEnglish = localeInfo.nameEnglish;
                            existing.name = localeInfo.name;
                            existing.baseLangCode = localeInfo.baseLangCode;
                            existing.pluralLangCode = localeInfo.pluralLangCode;
                            existing.pathToFile = localeInfo.pathToFile;
                            existing.serverIndex = localeInfo.serverIndex;
                            localeInfo = existing;
                        }
                        if (!remoteLanguagesDict.containsKey(localeInfo.getKey())) {
                            remoteLanguages.add(localeInfo);
                            remoteLanguagesDict.put(localeInfo.getKey(), localeInfo);
                        }
                    }
                    for (int a = 0; a < remoteLanguages.size(); a++) {
                        LocaleInfo info = remoteLanguages.get(a);
                        if (info.serverIndex != Integer.MAX_VALUE || info == currentLocaleInfo) {
                            continue;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("remove lang " + info.getKey());
                        }
                        remoteLanguages.remove(a);
                        remoteLanguagesDict.remove(info.getKey());
                        languages.remove(info);
                        languagesDict.remove(info.getKey());
                        a--;
                    }
                    saveOtherLanguages();
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.suggestedLangpack);
                    if (applyCurrent) {
                        applyLanguage(currentLocaleInfo, true, false, currentAccount);
                    }
                });
            }
        }, ConnectionsManager.RequestFlagWithoutLogin);
    }

    private void applyRemoteLanguage(LocaleInfo localeInfo, String langCode, boolean force, final int currentAccount) {
        if (localeInfo == null || !localeInfo.isRemote() && !localeInfo.isUnofficial()) {
            return;
        }
        if (localeInfo.hasBaseLang() && (langCode == null || langCode.equals(localeInfo.baseLangCode))) {
            if (localeInfo.baseVersion != 0 && !force) {
                if (localeInfo.hasBaseLang()) {
                    TLRPC.TL_langpack_getDifference req = new TLRPC.TL_langpack_getDifference();
                    req.from_version = localeInfo.baseVersion;
                    req.lang_code = localeInfo.getBaseLangCode();
                    req.lang_pack = "";
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                        if (response != null) {
                            AndroidUtilities.runOnUIThread(() -> saveRemoteLocaleStrings(localeInfo, (TLRPC.TL_langPackDifference) response, currentAccount));
                        }
                    }, ConnectionsManager.RequestFlagWithoutLogin);
                }
            } else {
                TLRPC.TL_langpack_getLangPack req = new TLRPC.TL_langpack_getLangPack();
                req.lang_code = localeInfo.getBaseLangCode();
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                    if (response != null) {
                        AndroidUtilities.runOnUIThread(() -> saveRemoteLocaleStrings(localeInfo, (TLRPC.TL_langPackDifference) response, currentAccount));
                    }
                }, ConnectionsManager.RequestFlagWithoutLogin);
            }
        }
        if (langCode == null || langCode.equals(localeInfo.shortName)) {
            if (localeInfo.version != 0 && !force) {
                TLRPC.TL_langpack_getDifference req = new TLRPC.TL_langpack_getDifference();
                req.from_version = localeInfo.version;
                req.lang_code = localeInfo.getLangCode();
                req.lang_pack = "";
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                    if (response != null) {
                        AndroidUtilities.runOnUIThread(() -> saveRemoteLocaleStrings(localeInfo, (TLRPC.TL_langPackDifference) response, currentAccount));
                    }
                }, ConnectionsManager.RequestFlagWithoutLogin);
            } else {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    ConnectionsManager.setLangCode(localeInfo.getLangCode());
                }
                TLRPC.TL_langpack_getLangPack req = new TLRPC.TL_langpack_getLangPack();
                req.lang_code = localeInfo.getLangCode();
                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                    if (response != null) {
                        AndroidUtilities.runOnUIThread(() -> saveRemoteLocaleStrings(localeInfo, (TLRPC.TL_langPackDifference) response, currentAccount));
                    }
                }, ConnectionsManager.RequestFlagWithoutLogin);
            }
        }
    }

    public String getTranslitString(String src) {
        return getTranslitString(src, true, false);
    }

    public String getTranslitString(String src, boolean onlyEnglish) {
        return getTranslitString(src, true, onlyEnglish);
    }

    public String getTranslitString(String src, boolean ru, boolean onlyEnglish) {
        if (src == null) {
            return null;
        }

        if (ruTranslitChars == null) {
            ruTranslitChars = new HashMap<>(33);
            ruTranslitChars.put("??", "a");
            ruTranslitChars.put("??", "b");
            ruTranslitChars.put("??", "v");
            ruTranslitChars.put("??", "g");
            ruTranslitChars.put("??", "d");
            ruTranslitChars.put("??", "e");
            ruTranslitChars.put("??", "yo");
            ruTranslitChars.put("??", "zh");
            ruTranslitChars.put("??", "z");
            ruTranslitChars.put("??", "i");
            ruTranslitChars.put("??", "i");
            ruTranslitChars.put("??", "k");
            ruTranslitChars.put("??", "l");
            ruTranslitChars.put("??", "m");
            ruTranslitChars.put("??", "n");
            ruTranslitChars.put("??", "o");
            ruTranslitChars.put("??", "p");
            ruTranslitChars.put("??", "r");
            ruTranslitChars.put("??", "s");
            ruTranslitChars.put("??", "t");
            ruTranslitChars.put("??", "u");
            ruTranslitChars.put("??", "f");
            ruTranslitChars.put("??", "h");
            ruTranslitChars.put("??", "ts");
            ruTranslitChars.put("??", "ch");
            ruTranslitChars.put("??", "sh");
            ruTranslitChars.put("??", "sch");
            ruTranslitChars.put("??", "i");
            ruTranslitChars.put("??", "");
            ruTranslitChars.put("??", "");
            ruTranslitChars.put("??", "e");
            ruTranslitChars.put("??", "yu");
            ruTranslitChars.put("??", "ya");
        }

        if (translitChars == null) {
            translitChars = new HashMap<>(487);
            translitChars.put("??", "c");
            translitChars.put("???", "n");
            translitChars.put("??", "d");
            translitChars.put("???", "y");
            translitChars.put("???", "o");
            translitChars.put("??", "o");
            translitChars.put("???", "a");
            translitChars.put("??", "h");
            translitChars.put("??", "y");
            translitChars.put("??", "k");
            translitChars.put("???", "u");
            translitChars.put("???", "aa");
            translitChars.put("??", "ij");
            translitChars.put("???", "l");
            translitChars.put("??", "i");
            translitChars.put("???", "b");
            translitChars.put("??", "r");
            translitChars.put("??", "e");
            translitChars.put("???", "ffi");
            translitChars.put("??", "o");
            translitChars.put("???", "r");
            translitChars.put("???", "o");
            translitChars.put("??", "i");
            translitChars.put("???", "p");
            translitChars.put("??", "y");
            translitChars.put("???", "e");
            translitChars.put("???", "o");
            translitChars.put("???", "a");
            translitChars.put("??", "b");
            translitChars.put("???", "e");
            translitChars.put("??", "c");
            translitChars.put("??", "h");
            translitChars.put("???", "b");
            translitChars.put("???", "s");
            translitChars.put("??", "d");
            translitChars.put("???", "o");
            translitChars.put("??", "j");
            translitChars.put("???", "a");
            translitChars.put("??", "y");
            translitChars.put("??", "v");
            translitChars.put("???", "p");
            translitChars.put("???", "fi");
            translitChars.put("???", "k");
            translitChars.put("???", "d");
            translitChars.put("???", "l");
            translitChars.put("??", "e");
            translitChars.put("???", "k");
            translitChars.put("??", "c");
            translitChars.put("??", "r");
            translitChars.put("??", "hv");
            translitChars.put("??", "b");
            translitChars.put("???", "o");
            translitChars.put("??", "ou");
            translitChars.put("??", "j");
            translitChars.put("???", "g");
            translitChars.put("???", "n");
            translitChars.put("??", "j");
            translitChars.put("??", "g");
            translitChars.put("??", "dz");
            translitChars.put("??", "z");
            translitChars.put("???", "au");
            translitChars.put("??", "u");
            translitChars.put("???", "g");
            translitChars.put("??", "o");
            translitChars.put("??", "a");
            translitChars.put("??", "a");
            translitChars.put("??", "o");
            translitChars.put("??", "r");
            translitChars.put("???", "o");
            translitChars.put("??", "a");
            translitChars.put("??", "l");
            translitChars.put("??", "s");
            translitChars.put("???", "fl");
            translitChars.put("??", "i");
            translitChars.put("???", "e");
            translitChars.put("???", "n");
            translitChars.put("??", "i");
            translitChars.put("??", "n");
            translitChars.put("???", "i");
            translitChars.put("??", "t");
            translitChars.put("???", "z");
            translitChars.put("???", "y");
            translitChars.put("??", "y");
            translitChars.put("???", "s");
            translitChars.put("??", "r");
            translitChars.put("??", "g");
            translitChars.put("???", "u");
            translitChars.put("???", "k");
            translitChars.put("???", "et");
            translitChars.put("??", "i");
            translitChars.put("??", "t");
            translitChars.put("???", "c");
            translitChars.put("??", "l");
            translitChars.put("???", "av");
            translitChars.put("??", "u");
            translitChars.put("??", "ae");
            translitChars.put("??", "a");
            translitChars.put("??", "u");
            translitChars.put("???", "s");
            translitChars.put("???", "r");
            translitChars.put("???", "a");
            translitChars.put("??", "b");
            translitChars.put("???", "h");
            translitChars.put("???", "s");
            translitChars.put("???", "e");
            translitChars.put("??", "h");
            translitChars.put("???", "x");
            translitChars.put("???", "k");
            translitChars.put("???", "d");
            translitChars.put("??", "oi");
            translitChars.put("???", "p");
            translitChars.put("??", "h");
            translitChars.put("???", "v");
            translitChars.put("???", "w");
            translitChars.put("??", "n");
            translitChars.put("??", "m");
            translitChars.put("??", "g");
            translitChars.put("??", "n");
            translitChars.put("???", "p");
            translitChars.put("???", "v");
            translitChars.put("??", "u");
            translitChars.put("???", "b");
            translitChars.put("???", "p");
            translitChars.put("??", "a");
            translitChars.put("??", "c");
            translitChars.put("???", "o");
            translitChars.put("???", "a");
            translitChars.put("??", "f");
            translitChars.put("??", "ae");
            translitChars.put("???", "vy");
            translitChars.put("???", "ff");
            translitChars.put("???", "r");
            translitChars.put("??", "o");
            translitChars.put("??", "o");
            translitChars.put("???", "u");
            translitChars.put("??", "z");
            translitChars.put("???", "f");
            translitChars.put("???", "d");
            translitChars.put("??", "e");
            translitChars.put("??", "u");
            translitChars.put("??", "n");
            translitChars.put("??", "q");
            translitChars.put("???", "a");
            translitChars.put("??", "k");
            translitChars.put("??", "i");
            translitChars.put("???", "u");
            translitChars.put("??", "t");
            translitChars.put("??", "r");
            translitChars.put("??", "k");
            translitChars.put("???", "t");
            translitChars.put("???", "q");
            translitChars.put("???", "a");
            translitChars.put("??", "j");
            translitChars.put("??", "l");
            translitChars.put("???", "f");
            translitChars.put("???", "s");
            translitChars.put("???", "r");
            translitChars.put("???", "v");
            translitChars.put("??", "o");
            translitChars.put("???", "c");
            translitChars.put("???", "u");
            translitChars.put("???", "z");
            translitChars.put("???", "u");
            translitChars.put("??", "n");
            translitChars.put("??", "w");
            translitChars.put("???", "a");
            translitChars.put("??", "lj");
            translitChars.put("??", "b");
            translitChars.put("??", "r");
            translitChars.put("??", "o");
            translitChars.put("???", "w");
            translitChars.put("??", "d");
            translitChars.put("???", "ay");
            translitChars.put("??", "u");
            translitChars.put("???", "b");
            translitChars.put("??", "u");
            translitChars.put("???", "e");
            translitChars.put("??", "a");
            translitChars.put("??", "h");
            translitChars.put("???", "o");
            translitChars.put("??", "u");
            translitChars.put("??", "y");
            translitChars.put("??", "o");
            translitChars.put("???", "e");
            translitChars.put("???", "e");
            translitChars.put("??", "i");
            translitChars.put("???", "e");
            translitChars.put("???", "t");
            translitChars.put("???", "d");
            translitChars.put("???", "h");
            translitChars.put("???", "s");
            translitChars.put("??", "e");
            translitChars.put("???", "m");
            translitChars.put("??", "o");
            translitChars.put("??", "e");
            translitChars.put("??", "i");
            translitChars.put("??", "d");
            translitChars.put("???", "m");
            translitChars.put("???", "y");
            translitChars.put("??", "w");
            translitChars.put("???", "e");
            translitChars.put("???", "u");
            translitChars.put("??", "z");
            translitChars.put("??", "j");
            translitChars.put("???", "d");
            translitChars.put("??", "u");
            translitChars.put("??", "j");
            translitChars.put("??", "e");
            translitChars.put("??", "u");
            translitChars.put("??", "g");
            translitChars.put("???", "r");
            translitChars.put("??", "n");
            translitChars.put("???", "e");
            translitChars.put("???", "s");
            translitChars.put("???", "d");
            translitChars.put("??", "k");
            translitChars.put("???", "ae");
            translitChars.put("??", "e");
            translitChars.put("???", "o");
            translitChars.put("???", "m");
            translitChars.put("???", "f");
            translitChars.put("???", "a");
            translitChars.put("???", "oo");
            translitChars.put("???", "m");
            translitChars.put("???", "p");
            translitChars.put("???", "u");
            translitChars.put("???", "k");
            translitChars.put("???", "h");
            translitChars.put("??", "t");
            translitChars.put("???", "p");
            translitChars.put("???", "m");
            translitChars.put("??", "a");
            translitChars.put("???", "n");
            translitChars.put("???", "v");
            translitChars.put("??", "e");
            translitChars.put("???", "z");
            translitChars.put("???", "d");
            translitChars.put("???", "p");
            translitChars.put("??", "l");
            translitChars.put("???", "z");
            translitChars.put("??", "m");
            translitChars.put("???", "r");
            translitChars.put("???", "v");
            translitChars.put("??", "u");
            translitChars.put("??", "ss");
            translitChars.put("??", "h");
            translitChars.put("???", "t");
            translitChars.put("??", "z");
            translitChars.put("???", "r");
            translitChars.put("??", "n");
            translitChars.put("??", "a");
            translitChars.put("???", "y");
            translitChars.put("???", "y");
            translitChars.put("???", "oe");
            translitChars.put("???", "x");
            translitChars.put("??", "u");
            translitChars.put("???", "j");
            translitChars.put("???", "a");
            translitChars.put("??", "z");
            translitChars.put("???", "s");
            translitChars.put("???", "i");
            translitChars.put("???", "ao");
            translitChars.put("??", "z");
            translitChars.put("??", "y");
            translitChars.put("??", "e");
            translitChars.put("??", "o");
            translitChars.put("???", "d");
            translitChars.put("???", "l");
            translitChars.put("??", "u");
            translitChars.put("???", "a");
            translitChars.put("???", "b");
            translitChars.put("???", "u");
            translitChars.put("???", "a");
            translitChars.put("???", "t");
            translitChars.put("??", "y");
            translitChars.put("???", "t");
            translitChars.put("???", "l");
            translitChars.put("??", "j");
            translitChars.put("???", "z");
            translitChars.put("???", "h");
            translitChars.put("???", "w");
            translitChars.put("???", "k");
            translitChars.put("???", "o");
            translitChars.put("??", "i");
            translitChars.put("??", "g");
            translitChars.put("??", "e");
            translitChars.put("??", "a");
            translitChars.put("???", "a");
            translitChars.put("??", "q");
            translitChars.put("???", "t");
            translitChars.put("???", "um");
            translitChars.put("???", "c");
            translitChars.put("???", "x");
            translitChars.put("???", "u");
            translitChars.put("???", "i");
            translitChars.put("???", "r");
            translitChars.put("??", "s");
            translitChars.put("???", "o");
            translitChars.put("???", "y");
            translitChars.put("???", "s");
            translitChars.put("??", "nj");
            translitChars.put("??", "a");
            translitChars.put("???", "t");
            translitChars.put("??", "l");
            translitChars.put("??", "z");
            translitChars.put("???", "th");
            translitChars.put("??", "d");
            translitChars.put("??", "s");
            translitChars.put("??", "s");
            translitChars.put("???", "u");
            translitChars.put("???", "e");
            translitChars.put("???", "s");
            translitChars.put("??", "e");
            translitChars.put("???", "u");
            translitChars.put("???", "o");
            translitChars.put("??", "s");
            translitChars.put("???", "v");
            translitChars.put("???", "is");
            translitChars.put("???", "o");
            translitChars.put("??", "e");
            translitChars.put("??", "a");
            translitChars.put("???", "ffl");
            translitChars.put("???", "o");
            translitChars.put("??", "i");
            translitChars.put("???", "ue");
            translitChars.put("??", "d");
            translitChars.put("???", "z");
            translitChars.put("???", "w");
            translitChars.put("???", "a");
            translitChars.put("???", "t");
            translitChars.put("??", "g");
            translitChars.put("??", "n");
            translitChars.put("??", "g");
            translitChars.put("???", "u");
            translitChars.put("???", "a");
            translitChars.put("???", "n");
            translitChars.put("??", "i");
            translitChars.put("???", "r");
            translitChars.put("??", "a");
            translitChars.put("??", "s");
            translitChars.put("??", "o");
            translitChars.put("??", "r");
            translitChars.put("??", "t");
            translitChars.put("???", "i");
            translitChars.put("??", "ae");
            translitChars.put("???", "v");
            translitChars.put("??", "oe");
            translitChars.put("???", "m");
            translitChars.put("??", "z");
            translitChars.put("??", "e");
            translitChars.put("???", "av");
            translitChars.put("???", "o");
            translitChars.put("???", "e");
            translitChars.put("??", "l");
            translitChars.put("???", "i");
            translitChars.put("???", "d");
            translitChars.put("???", "st");
            translitChars.put("???", "l");
            translitChars.put("??", "r");
            translitChars.put("???", "ou");
            translitChars.put("??", "t");
            translitChars.put("??", "a");
            translitChars.put("???", "e");
            translitChars.put("???", "o");
            translitChars.put("??", "c");
            translitChars.put("???", "s");
            translitChars.put("???", "a");
            translitChars.put("??", "u");
            translitChars.put("???", "a");
            translitChars.put("??", "g");
            translitChars.put("???", "k");
            translitChars.put("???", "z");
            translitChars.put("??", "s");
            translitChars.put("???", "e");
            translitChars.put("??", "g");
            translitChars.put("???", "l");
            translitChars.put("???", "f");
            translitChars.put("???", "x");
            translitChars.put("??", "o");
            translitChars.put("??", "e");
            translitChars.put("???", "o");
            translitChars.put("??", "t");
            translitChars.put("??", "o");
            translitChars.put("i??", "i");
            translitChars.put("???", "n");
            translitChars.put("??", "c");
            translitChars.put("???", "g");
            translitChars.put("???", "w");
            translitChars.put("???", "d");
            translitChars.put("???", "l");
            translitChars.put("??", "oe");
            translitChars.put("???", "r");
            translitChars.put("??", "l");
            translitChars.put("??", "r");
            translitChars.put("??", "o");
            translitChars.put("???", "n");
            translitChars.put("???", "ae");
            translitChars.put("??", "l");
            translitChars.put("??", "a");
            translitChars.put("??", "p");
            translitChars.put("???", "o");
            translitChars.put("??", "i");
            translitChars.put("??", "r");
            translitChars.put("??", "dz");
            translitChars.put("???", "g");
            translitChars.put("???", "u");
            translitChars.put("??", "o");
            translitChars.put("??", "l");
            translitChars.put("???", "w");
            translitChars.put("??", "t");
            translitChars.put("??", "n");
            translitChars.put("??", "r");
            translitChars.put("??", "a");
            translitChars.put("??", "u");
            translitChars.put("???", "l");
            translitChars.put("???", "o");
            translitChars.put("???", "o");
            translitChars.put("???", "b");
            translitChars.put("??", "r");
            translitChars.put("???", "r");
            translitChars.put("??", "y");
            translitChars.put("???", "f");
            translitChars.put("???", "h");
            translitChars.put("??", "o");
            translitChars.put("??", "u");
            translitChars.put("???", "r");
            translitChars.put("??", "h");
            translitChars.put("??", "o");
            translitChars.put("??", "u");
            translitChars.put("???", "o");
            translitChars.put("???", "p");
            translitChars.put("???", "i");
            translitChars.put("???", "u");
            translitChars.put("??", "a");
            translitChars.put("???", "i");
            translitChars.put("???", "t");
            translitChars.put("???", "e");
            translitChars.put("???", "u");
            translitChars.put("??", "i");
            translitChars.put("??", "o");
            translitChars.put("??", "r");
            translitChars.put("??", "g");
            translitChars.put("??", "r");
            translitChars.put("???", "h");
            translitChars.put("??", "u");
            translitChars.put("??", "o");
            translitChars.put("???", "l");
            translitChars.put("???", "h");
            translitChars.put("??", "t");
            translitChars.put("??", "n");
            translitChars.put("???", "e");
            translitChars.put("??", "i");
            translitChars.put("???", "w");
            translitChars.put("??", "e");
            translitChars.put("???", "e");
            translitChars.put("??", "l");
            translitChars.put("???", "o");
            translitChars.put("??", "l");
            translitChars.put("???", "y");
            translitChars.put("???", "j");
            translitChars.put("???", "k");
            translitChars.put("???", "v");
            translitChars.put("??", "e");
            translitChars.put("??", "a");
            translitChars.put("??", "s");
            translitChars.put("??", "r");
            translitChars.put("??", "v");
            translitChars.put("???", "a");
            translitChars.put("???", "c");
            translitChars.put("???", "e");
            translitChars.put("??", "m");
            translitChars.put("???", "w");
            translitChars.put("??", "o");
            translitChars.put("??", "c");
            translitChars.put("??", "g");
            translitChars.put("??", "c");
            translitChars.put("???", "o");
            translitChars.put("???", "k");
            translitChars.put("???", "q");
            translitChars.put("???", "o");
            translitChars.put("???", "s");
            translitChars.put("???", "o");
            translitChars.put("??", "h");
            translitChars.put("??", "o");
            translitChars.put("???", "tz");
            translitChars.put("???", "e");
        }
        StringBuilder dst = new StringBuilder(src.length());
        int len = src.length();
        boolean upperCase = false;
        for (int a = 0; a < len; a++) {
            String ch = src.substring(a, a + 1);
            if (onlyEnglish) {
                String lower = ch.toLowerCase();
                upperCase = !ch.equals(lower);
                ch = lower;
            }
            String tch = translitChars.get(ch);
            if (tch == null && ru) {
                tch = ruTranslitChars.get(ch);
            }
            if (tch != null) {
                if (onlyEnglish && upperCase) {
                    if (tch.length() > 1) {
                        tch = tch.substring(0, 1).toUpperCase() + tch.substring(1);
                    } else {
                        tch = tch.toUpperCase();
                    }
                }
                dst.append(tch);
            } else {
                if (onlyEnglish) {
                    char c = ch.charAt(0);
                    if (((c < 'a' || c > 'z') || (c < '0' || c > '9')) && c != ' ' && c != '\'' && c != ',' && c != '.' && c != '&' && c != '-' && c != '/') {
                        return null;
                    }
                    if (upperCase) {
                        ch = ch.toUpperCase();
                    }
                }
                dst.append(ch);
            }
        }
        return dst.toString();
    }

    abstract public static class PluralRules {
        abstract int quantityForNumber(int n);
    }

    public static class PluralRules_Zero extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 0 || count == 1) {
                return QUANTITY_ONE;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Welsh extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 0) {
                return QUANTITY_ZERO;
            } else if (count == 1) {
                return QUANTITY_ONE;
            } else if (count == 2) {
                return QUANTITY_TWO;
            } else if (count == 3) {
                return QUANTITY_FEW;
            } else if (count == 6) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Two extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 1) {
                return QUANTITY_ONE;
            } else if (count == 2) {
                return QUANTITY_TWO;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Tachelhit extends PluralRules {
        public int quantityForNumber(int count) {
            if (count >= 0 && count <= 1) {
                return QUANTITY_ONE;
            } else if (count >= 2 && count <= 10) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Slovenian extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            if (rem100 == 1) {
                return QUANTITY_ONE;
            } else if (rem100 == 2) {
                return QUANTITY_TWO;
            } else if (rem100 >= 3 && rem100 <= 4) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Romanian extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            if (count == 1) {
                return QUANTITY_ONE;
            } else if ((count == 0 || (rem100 >= 1 && rem100 <= 19))) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Polish extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            int rem10 = count % 10;
            if (count == 1) {
                return QUANTITY_ONE;
            } else if (rem10 >= 2 && rem10 <= 4 && !(rem100 >= 12 && rem100 <= 14)) {
                return QUANTITY_FEW;
            } else if (rem10 >= 0 && rem10 <= 1 || rem10 >= 5 && rem10 <= 9 || rem100 >= 12 && rem100 <= 14) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_One extends PluralRules {
        public int quantityForNumber(int count) {
            return count == 1 ? QUANTITY_ONE : QUANTITY_OTHER;
        }
    }

    public static class PluralRules_None extends PluralRules {
        public int quantityForNumber(int count) {
            return QUANTITY_OTHER;
        }
    }

    public static class PluralRules_Maltese extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            if (count == 1) {
                return QUANTITY_ONE;
            } else if (count == 0 || (rem100 >= 2 && rem100 <= 10)) {
                return QUANTITY_FEW;
            } else if (rem100 >= 11 && rem100 <= 19) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Macedonian extends PluralRules {
        public int quantityForNumber(int count) {
            if (count % 10 == 1 && count != 11) {
                return QUANTITY_ONE;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Lithuanian extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            int rem10 = count % 10;
            if (rem10 == 1 && !(rem100 >= 11 && rem100 <= 19)) {
                return QUANTITY_ONE;
            } else if (rem10 >= 2 && rem10 <= 9 && !(rem100 >= 11 && rem100 <= 19)) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Latvian extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 0) {
                return QUANTITY_ZERO;
            } else if (count % 10 == 1 && count % 100 != 11) {
                return QUANTITY_ONE;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Langi extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 0) {
                return QUANTITY_ZERO;
            } else if (count == 1) {
                return QUANTITY_ONE;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_French extends PluralRules {
        public int quantityForNumber(int count) {
            if (count >= 0 && count < 2) {
                return QUANTITY_ONE;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Czech extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 1) {
                return QUANTITY_ONE;
            } else if (count >= 2 && count <= 4) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Breton extends PluralRules {
        public int quantityForNumber(int count) {
            if (count == 0) {
                return QUANTITY_ZERO;
            } else if (count == 1) {
                return QUANTITY_ONE;
            } else if (count == 2) {
                return QUANTITY_TWO;
            } else if (count == 3) {
                return QUANTITY_FEW;
            } else if (count == 6) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Balkan extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            int rem10 = count % 10;
            if (rem10 == 1 && rem100 != 11) {
                return QUANTITY_ONE;
            } else if (rem10 >= 2 && rem10 <= 4 && !(rem100 >= 12 && rem100 <= 14)) {
                return QUANTITY_FEW;
            } else if ((rem10 == 0 || (rem10 >= 5 && rem10 <= 9) || (rem100 >= 11 && rem100 <= 14))) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Serbian extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            int rem10 = count % 10;
            if (rem10 == 1 && rem100 != 11) {
                return QUANTITY_ONE;
            } else if (rem10 >= 2 && rem10 <= 4 && !(rem100 >= 12 && rem100 <= 14)) {
                return QUANTITY_FEW;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static class PluralRules_Arabic extends PluralRules {
        public int quantityForNumber(int count) {
            int rem100 = count % 100;
            if (count == 0) {
                return QUANTITY_ZERO;
            } else if (count == 1) {
                return QUANTITY_ONE;
            } else if (count == 2) {
                return QUANTITY_TWO;
            } else if (rem100 >= 3 && rem100 <= 10) {
                return QUANTITY_FEW;
            } else if (rem100 >= 11 && rem100 <= 99) {
                return QUANTITY_MANY;
            } else {
                return QUANTITY_OTHER;
            }
        }
    }

    public static String addNbsp(String src) {
        return src.replace(' ', '\u00A0');
    }

    private static Boolean useImperialSystemType;

    public static void resetImperialSystemType() {
        useImperialSystemType = null;
    }

    public static boolean getUseImperialSystemType() {
        ensureImperialSystemInit();
        return useImperialSystemType;
    }

    public static void ensureImperialSystemInit() {
        if (useImperialSystemType != null) {
            return;
        }
        if (SharedConfig.distanceSystemType == 0) {
            try {
                TelephonyManager telephonyManager = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
                if (telephonyManager != null) {
                    String country = telephonyManager.getSimCountryIso().toUpperCase();
                    useImperialSystemType = "US".equals(country) || "GB".equals(country) || "MM".equals(country) || "LR".equals(country);
                }
            } catch (Exception e) {
                useImperialSystemType = false;
                FileLog.e(e);
            }
        } else {
            useImperialSystemType = SharedConfig.distanceSystemType == 2;
        }
    }

    public static String formatDistance(float distance, int type) {
        return formatDistance(distance, type, null);
    }

    public static String formatDistance(float distance, int type, Boolean useImperial) {
        ensureImperialSystemInit();
        boolean imperial = useImperial != null && useImperial || useImperial == null && useImperialSystemType;
        if (imperial) {
            distance *= 3.28084f;
            if (distance < 1000) {
                switch (type) {
                    case 0:
                        return formatString("FootsAway", R.string.FootsAway, String.format("%d", (int) Math.max(1, distance)));
                    case 1:
                        return formatString("FootsFromYou", R.string.FootsFromYou, String.format("%d", (int) Math.max(1, distance)));
                    case 2:
                    default:
                        return formatString("FootsShort", R.string.FootsShort, String.format("%d", (int) Math.max(1, distance)));
                }
            } else {
                String arg;
                if (distance % 5280 == 0) {
                    arg = String.format("%d", (int) (distance / 5280));
                } else {
                    arg = String.format("%.2f", distance / 5280.0f);
                }
                switch (type) {
                    case 0:
                        return formatString("MilesAway", R.string.MilesAway, arg);
                    case 1:
                        return formatString("MilesFromYou", R.string.MilesFromYou, arg);
                    default:
                    case 2:
                        return formatString("MilesShort", R.string.MilesShort, arg);
                }

            }
        } else {
            if (distance < 1000) {
                switch (type) {
                    case 0:
                        return formatString("MetersAway2", R.string.MetersAway2, String.format("%d", (int) Math.max(1, distance)));
                    case 1:
                        return formatString("MetersFromYou2", R.string.MetersFromYou2, String.format("%d", (int) Math.max(1, distance)));
                    case 2:
                    default:
                        return formatString("MetersShort", R.string.MetersShort, String.format("%d", (int) Math.max(1, distance)));
                }
            } else {
                String arg;
                if (distance % 1000 == 0) {
                    arg = String.format("%d", (int) (distance / 1000));
                } else {
                    arg = String.format("%.2f", distance / 1000.0f);
                }
                switch (type) {
                    case 0:
                        return formatString("KMetersAway2", R.string.KMetersAway2, arg);
                    case 1:
                        return formatString("KMetersFromYou2", R.string.KMetersFromYou2, arg);
                    case 2:
                    default:
                        return formatString("KMetersShort", R.string.KMetersShort, arg);
                }
            }
        }
    }
}
