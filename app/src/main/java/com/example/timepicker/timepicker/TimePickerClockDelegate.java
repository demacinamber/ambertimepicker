/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.timepicker.timepicker;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcelable;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.TtsSpan;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;


import com.example.timepicker.R;
import com.example.timepicker.timepicker.util.DateFormatFix;
import com.example.timepicker.timepicker.util.StateSet;
import com.example.timepicker.timepicker.util.Utils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Calendar;

// TODO import android.icu.text.DecimalFormatSymbols;

/**
 * A delegate implementing the radial clock-based TimePicker.
 */
class TimePickerClockDelegate extends TimePicker.AbstractTimePickerDelegate {
    /**
     * Delay in milliseconds before valid but potentially incomplete, for
     * example "1" but not "12", keyboard edits are propagated from the
     * hour / minute fields to the radial picker.
     */
    private static final long DELAY_COMMIT_MILLIS = 2000;

    @IntDef({FROM_EXTERNAL_API, FROM_RADIAL_PICKER, FROM_INPUT_PICKER})
    @Retention(RetentionPolicy.SOURCE)
    private @interface ChangeSource {
    }

    private static final int FROM_EXTERNAL_API = 0;
    private static final int FROM_RADIAL_PICKER = 1;
    private static final int FROM_INPUT_PICKER = 2;

    // Index used by RadialPickerLayout
    private static final int HOUR_INDEX = RadialTimePickerView.HOURS;
    private static final int MINUTE_INDEX = RadialTimePickerView.MINUTES;

    private static final int[] ATTRS_TEXT_COLOR = new int[]{android.R.attr.textColor};
    private static final int[] ATTRS_DISABLED_ALPHA = new int[]{android.R.attr.disabledAlpha};

    private static final int AM = 0;
    private static final int PM = 1;

    private static final int HOURS_IN_HALF_DAY = 12;

    private final NumericTextView mHourView;
    private final NumericTextView mMinuteView;
    private final View mAmPmLayout;
    private final RadioButton mAmLabel;
    private final RadioButton mPmLabel;
    private final RadialTimePickerView mRadialTimePickerView;
    private final TextView mSeparatorView;
    private final TextView mAmOrPmTextView;

    private boolean mRadialPickerModeEnabled = true;
    private final ImageButton mRadialTimePickerModeButton;
    private final String mRadialTimePickerModeEnabledDescription;
    private final String mTextInputPickerModeEnabledDescription;
    private final View mRadialTimePickerHeader;
    private final View mTextInputPickerHeader;

    private final TextInputTimePickerView mTextInputPickerView;

    private final Calendar mTempCalendar;

    // Accessibility strings.
    private final String mSelectHours;
    private final String mSelectMinutes;

    private boolean mIsEnabled = true;
    private boolean mAllowAutoAdvance;
    private int mCurrentHour;
    private int mCurrentMinute;
    private boolean mIs24Hour;
    private boolean mIsAmPmAtStart;

    // Localization data.
    private boolean mHourFormatShowLeadingZero;
    private boolean mHourFormatStartsAtZero;

    // Most recent time announcement values for accessibility.
    private CharSequence mLastAnnouncedText;
    private boolean mLastAnnouncedIsHour;

    public TimePickerClockDelegate(TimePicker delegator, Context context, AttributeSet attrs,
                                   int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        // process style attributes
        final TypedArray a = mContext.obtainStyledAttributes(attrs,
                R.styleable.TimePicker, defStyleAttr, defStyleRes);
        final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        final Resources res = mContext.getResources();

        mSelectHours = res.getString(R.string.select_hours);
        mSelectMinutes = res.getString(R.string.select_minutes);

        final int layoutResourceId = a.getResourceId(R.styleable.TimePicker_dtp_internalLayout,
                R.layout.time_picker_material);
        final View mainView = inflater.inflate(layoutResourceId, delegator);
        mainView.setSaveFromParentEnabled(false);
        mRadialTimePickerHeader = mainView.findViewById(R.id.time_header);
        mRadialTimePickerHeader.setOnTouchListener(new NearestTouchDelegate());

        // Set up hour/minute labels.
        mHourView = (NumericTextView) mainView.findViewById(R.id.hours);
        mHourView.setOnClickListener(mClickListener);
        mHourView.setOnFocusChangeListener(mFocusListener);
        mHourView.setOnDigitEnteredListener(mDigitEnteredListener);
        mHourView.setAccessibilityDelegate(
                new ClickActionDelegate(context, R.string.select_hours));
        mSeparatorView = (TextView) mainView.findViewById(R.id.separator);
        mAmOrPmTextView = (TextView) mainView.findViewById(R.id.tvAmOrPm);
        mMinuteView = (NumericTextView) mainView.findViewById(R.id.minutes);
        mMinuteView.setOnClickListener(mClickListener);
        mMinuteView.setOnFocusChangeListener(mFocusListener);
        mMinuteView.setOnDigitEnteredListener(mDigitEnteredListener);
        mMinuteView.setAccessibilityDelegate(
                new ClickActionDelegate(context, R.string.select_minutes));
        mMinuteView.setRange(0, 59);

        // Set up AM/PM labels.
        mAmPmLayout = mainView.findViewById(R.id.ampm_layout);
        mAmPmLayout.setOnTouchListener(new NearestTouchDelegate());

        final String[] amPmStrings = TimePicker.getAmPmStrings(context);
        mAmLabel = (RadioButton) mAmPmLayout.findViewById(R.id.am_label);
        mAmLabel.setText(obtainVerbatim(amPmStrings[0]));
        mAmLabel.setOnClickListener(mClickListener);
        ensureMinimumTextWidth(mAmLabel);

        mPmLabel = (RadioButton) mAmPmLayout.findViewById(R.id.pm_label);
        mPmLabel.setText(obtainVerbatim(amPmStrings[1]));
        mPmLabel.setOnClickListener(mClickListener);
        ensureMinimumTextWidth(mPmLabel);

        // For the sake of backwards compatibility, attempt to extract the text
        // color from the header time text appearance. If it's set, we'll let
        // that override the "real" header text color.
        ColorStateList headerTextColor = null;

      /*  if (headerTextColor == null) {
            headerTextColor = Utils.getColorStateList(mContext, a, R.styleable.TimePicker_dtp_headerTextColor);//a.getColorStateList(R.styleable.TimePicker_headerTextColor);
        }*/

        mTextInputPickerHeader = mainView.findViewById(R.id.input_header);

        if (headerTextColor != null) {
            mHourView.setTextColor(headerTextColor);
            mSeparatorView.setTextColor(headerTextColor);
            mMinuteView.setTextColor(headerTextColor);
            mAmLabel.setTextColor(headerTextColor);
            mPmLabel.setTextColor(headerTextColor);
        }

        // Set up header background, if available.
        // if (a.hasValueOrEmpty(R.styleable.TimePicker_headerBackground)) {
        Drawable headerBg = a.getDrawable(R.styleable.TimePicker_headerBackground);
        if (headerBg != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                //mRadialTimePickerHeader.setBackground(a.getDrawable(R.styleable.TimePicker_headerBackground));
                mTextInputPickerHeader.setBackground(a.getDrawable(R.styleable.TimePicker_headerBackground));
            } else {
                //mRadialTimePickerHeader.setBackgroundDrawable(a.getDrawable(R.styleable.TimePicker_headerBackground));
                mTextInputPickerHeader.setBackgroundDrawable(a.getDrawable(R.styleable.TimePicker_headerBackground));
            }
        }

        a.recycle();

        mRadialTimePickerView = (RadialTimePickerView) mainView.findViewById(R.id.radial_picker);
        mRadialTimePickerView.applyAttributes(attrs, defStyleAttr, defStyleRes);
        mRadialTimePickerView.setOnValueSelectedListener(mOnValueSelectedListener);

        mTextInputPickerView = (TextInputTimePickerView) mainView.findViewById(R.id.input_mode);
        mTextInputPickerView.setListener(mOnValueTypedListener);

        mRadialTimePickerModeButton =
                (ImageButton) mainView.findViewById(R.id.toggle_mode);

        // start of FIX - tinting the drawable manually because the android:tint attribute crashes the app
        Drawable drawable = mRadialTimePickerModeButton.getDrawable();
        Drawable wrapped = DrawableCompat.wrap(drawable);

        TypedArray arr = context.obtainStyledAttributes(new int[]{R.attr.colorControlNormal});
        ColorStateList tintList = Utils.getColorStateList(mContext, arr, 0);//arr.getColorStateList(0);
        arr.recycle();

        if (tintList != null) {
            DrawableCompat.setTintList(wrapped, tintList);
        }

        mRadialTimePickerModeButton.setImageDrawable(wrapped);
        // end of FIX

        mRadialTimePickerModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRadialPickerMode();
            }
        });
        mRadialTimePickerModeEnabledDescription = context.getResources().getString(
                R.string.time_picker_radial_mode_description);
        mTextInputPickerModeEnabledDescription = context.getResources().getString(
                R.string.time_picker_text_input_mode_description);

        mAllowAutoAdvance = true;

        updateHourFormat();

        // Initialize with current time.
        mTempCalendar = Calendar.getInstance(mLocale);
        final int currentHour = mTempCalendar.get(Calendar.HOUR_OF_DAY);
        final int currentMinute = mTempCalendar.get(Calendar.MINUTE);
        initialize(currentHour, currentMinute, mIs24Hour, HOUR_INDEX);
    }

    private void toggleRadialPickerMode() {
        if (mRadialPickerModeEnabled) {
            mRadialTimePickerView.setVisibility(View.GONE);
            mRadialTimePickerHeader.setVisibility(View.GONE);
            mTextInputPickerHeader.setVisibility(View.VISIBLE);
            mTextInputPickerView.setVisibility(View.VISIBLE);
            //mRadialTimePickerModeButton.setImageResource(R.drawable.btn_clock_material);
            mRadialTimePickerModeButton.setImageDrawable(Utils.tintDrawable(mContext, AppCompatResources.getDrawable(mContext, R.drawable.btn_clock_material), R.attr.colorControlNormal)); // fixing tinting
            mRadialTimePickerModeButton.setContentDescription(
                    mRadialTimePickerModeEnabledDescription);
            mRadialPickerModeEnabled = false;
        } else {
            mRadialTimePickerView.setVisibility(View.VISIBLE);
            mRadialTimePickerHeader.setVisibility(View.VISIBLE);
            mTextInputPickerHeader.setVisibility(View.GONE);
            mTextInputPickerView.changeInputMethod(false);
            mTextInputPickerView.setVisibility(View.GONE);
            //mRadialTimePickerModeButton.setImageResource(R.drawable.btn_keyboard_key_material);
            mRadialTimePickerModeButton.setImageDrawable(Utils.tintDrawable(mContext, AppCompatResources.getDrawable(mContext, R.drawable.btn_keyboard_key_material), R.attr.colorControlNormal)); // fixing tinting
            mRadialTimePickerModeButton.setContentDescription(
                    mTextInputPickerModeEnabledDescription);
            updateTextInputPicker();
            mRadialPickerModeEnabled = true;
        }
    }

    @Override
    public boolean validateInput() {
        return mTextInputPickerView.validateInput();
    }

    /**
     * Ensures that a TextView is wide enough to contain its text without
     * wrapping or clipping. Measures the specified view and sets the minimum
     * width to the view's desired width.
     *
     * @param v the text view to measure
     */
    private static void ensureMinimumTextWidth(TextView v) {
        v.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

        // Set both the TextView and the View version of minimum
        // width because they are subtly different.
        final int minWidth = v.getMeasuredWidth();
        v.setMinWidth(minWidth);
        v.setMinimumWidth(minWidth);
    }

    /**
     * Updates hour formatting based on the current locale and 24-hour mode.
     * <p>
     * Determines how the hour should be formatted, sets member variables for
     * leading zero and starting hour, and sets the hour view's presentation.
     */
    private void updateHourFormat() {
        final String bestDateTimePattern = DateFormatFix.getBestDateTimePattern(mContext, mLocale, mIs24Hour ? "Hm" : "hm");
        final int lengthPattern = bestDateTimePattern.length();
        boolean showLeadingZero = false;
        char hourFormat = '\0';

        for (int i = 0; i < lengthPattern; i++) {
            final char c = bestDateTimePattern.charAt(i);
            if (c == 'H' || c == 'h' || c == 'K' || c == 'k') {
                hourFormat = c;
                if (i + 1 < lengthPattern && c == bestDateTimePattern.charAt(i + 1)) {
                    showLeadingZero = true;
                }
                break;
            }
        }

        mHourFormatShowLeadingZero = showLeadingZero;
        mHourFormatStartsAtZero = hourFormat == 'K' || hourFormat == 'H';

        // Update hour text field.
        final int minHour = mHourFormatStartsAtZero ? 0 : 1;
        final int maxHour = (mIs24Hour ? 23 : 11) + minHour;
        mHourView.setRange(minHour, maxHour);
        mHourView.setShowLeadingZeroes(mHourFormatShowLeadingZero);

        /*final String[] digits = DecimalFormatSymbols.getInstance(mLocale).getDigitStrings();

        int maxCharLength = 0;
        for (int i = 0; i < 10; i++) {
            maxCharLength = Math.max(maxCharLength, digits[i].length());
        }*/

        NumberFormat intFormat = DecimalFormat.getIntegerInstance(mLocale);
        intFormat.setGroupingUsed(false);

        int maxCharLength = 0;

        for (int i = 0; i < 10; i++) {
            maxCharLength = Math.max(maxCharLength, intFormat.format(i).length());
        }

        mTextInputPickerView.setHourFormat(maxCharLength * 2);
    }

    static final CharSequence obtainVerbatim(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new SpannableStringBuilder().append(text,
                    new TtsSpan.VerbatimBuilder(text).build(), 0);
        } else {
            return text;
        }
    }

    /**
     * The legacy text color might have been poorly defined. Ensures that it
     * has an appropriate activated state, using the selected state if one
     * exists or modifying the default text color otherwise.
     *
     * @param color a legacy text color, or {@code null}
     * @return a color state list with an appropriate activated state, or
     * {@code null} if a valid activated state could not be generated
     */
    @Nullable
    private ColorStateList applyLegacyColorFixes(@Nullable ColorStateList color) {
        if (color == null || Utils.colorHasState(color, android.R.attr.state_activated)) {
            return color;
        }

        final int activatedColor;
        final int defaultColor;
        if (Utils.colorHasState(color, android.R.attr.state_selected)) {
            activatedColor = color.getColorForState(StateSet.get(
                    StateSet.VIEW_STATE_ENABLED | StateSet.VIEW_STATE_SELECTED), 0);
            defaultColor = color.getColorForState(StateSet.get(
                    StateSet.VIEW_STATE_ENABLED), 0);
        } else {
            activatedColor = color.getDefaultColor();

            // Generate a non-activated color using the disabled alpha.
            final TypedArray ta = mContext.obtainStyledAttributes(ATTRS_DISABLED_ALPHA);
            final float disabledAlpha = ta.getFloat(0, 0.30f);
            defaultColor = multiplyAlphaComponent(activatedColor, disabledAlpha);
            ta.recycle();
        }

        if (activatedColor == 0 || defaultColor == 0) {
            // We somehow failed to obtain the colors.
            return null;
        }

        final int[][] stateSet = new int[][]{{android.R.attr.state_activated}, {}};
        final int[] colors = new int[]{activatedColor, defaultColor};
        return new ColorStateList(stateSet, colors);
    }

    private int multiplyAlphaComponent(int color, float alphaMod) {
        final int srcRgb = color & 0xFFFFFF;
        final int srcAlpha = (color >> 24) & 0xFF;
        final int dstAlpha = (int) (srcAlpha * alphaMod + 0.5f);
        return srcRgb | (dstAlpha << 24);
    }

    private static class ClickActionDelegate extends AccessibilityDelegate {
        private final AccessibilityNodeInfoCompat.AccessibilityActionCompat mClickAction;

        public ClickActionDelegate(Context context, int resId) {
            mClickAction = new AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, context.getString(resId));
            /*mClickAction = new AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, context.getString(resId));*/
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            AccessibilityNodeInfoCompat.wrap(info).addAction(mClickAction);
            //info.addAction(mClickAction);
        }
    }

    private void initialize(int hourOfDay, int minute, boolean is24HourView, int index) {
        mCurrentHour = hourOfDay;
        mCurrentMinute = minute;
        mIs24Hour = is24HourView;
        updateUI(index);
    }

    private void updateUI(int index) {
        updateHeaderAmPm();
        updateHeaderHour(mCurrentHour, false);
        updateHeaderSeparator();
        updateHeaderMinute(mCurrentMinute, false);
        updateRadialPicker(index);
        updateTextInputPicker();

        mDelegator.invalidate();
    }

    private void updateTextInputPicker() {
        mTextInputPickerView.updateTextInputValues(getLocalizedHour(mCurrentHour), mCurrentMinute,
                mCurrentHour < 12 ? AM : PM, mIs24Hour, mHourFormatStartsAtZero);
    }

    private void updateRadialPicker(int index) {
        mRadialTimePickerView.initialize(mCurrentHour, mCurrentMinute, mIs24Hour);
        setCurrentItemShowing(index, false, true);
    }

    private void updateHeaderAmPm() {
        if (mIs24Hour) {
            mAmPmLayout.setVisibility(View.GONE);
        } else {
            // Ensure that AM/PM layout is in the correct position.
            final String dateTimePattern = DateFormatFix.getBestDateTimePattern(mContext, mLocale, "hm");
            final boolean isAmPmAtStart = dateTimePattern.startsWith("a");
            setAmPmAtStart(isAmPmAtStart);

            updateAmPmLabelStates(mCurrentHour < 12 ? AM : PM);
        }
    }

    private void setAmPmAtStart(boolean isAmPmAtStart) {
        if (mIsAmPmAtStart != isAmPmAtStart) {
            mIsAmPmAtStart = isAmPmAtStart;

            final RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) mAmPmLayout.getLayoutParams();

            /*if (params.getRule(RelativeLayout.RIGHT_OF) != 0 ||
                    params.getRule(RelativeLayout.LEFT_OF) != 0) {
                if (isAmPmAtStart) {
                    params.removeRule(RelativeLayout.RIGHT_OF);
                    params.addRule(RelativeLayout.LEFT_OF, mHourView.getId());
                } else {
                    params.removeRule(RelativeLayout.LEFT_OF);
                    params.addRule(RelativeLayout.RIGHT_OF, mMinuteView.getId());
                }
            }*/
            int[] rules = params.getRules();

            if (rules[RelativeLayout.RIGHT_OF] != 0 ||
                    rules[RelativeLayout.LEFT_OF] != 0) {
                if (isAmPmAtStart) {
                    params.addRule(RelativeLayout.RIGHT_OF, 0);
                    params.addRule(RelativeLayout.LEFT_OF, mHourView.getId());
                } else {
                    params.addRule(RelativeLayout.LEFT_OF, 0);
                    params.addRule(RelativeLayout.RIGHT_OF, mMinuteView.getId());
                }
            }

            mAmPmLayout.setLayoutParams(params);
        }
    }

    /**
     * Set the current hour.
     */
    @Override
    public void setHour(int hour) {
        setHourInternal(hour, FROM_EXTERNAL_API, true);
    }

    private void setHourInternal(int hour, @ChangeSource int source, boolean announce) {
        if (mCurrentHour == hour) {
            return;
        }

        mCurrentHour = hour;
        updateHeaderHour(hour, announce);
        updateHeaderAmPm();

        if (source != FROM_RADIAL_PICKER) {
            mRadialTimePickerView.setCurrentHour(hour);
            mRadialTimePickerView.setAmOrPm(hour < 12 ? AM : PM);
        }
        if (source != FROM_INPUT_PICKER) {
            updateTextInputPicker();
        }

        mDelegator.invalidate();
        onTimeChanged();
    }

    /**
     * @return the current hour in the range (0-23)
     */
    @Override
    public int getHour() {
        final int currentHour = mRadialTimePickerView.getCurrentHour();
        if (mIs24Hour) {
            return currentHour;
        }

        if (mRadialTimePickerView.getAmOrPm() == PM) {
            return (currentHour % HOURS_IN_HALF_DAY) + HOURS_IN_HALF_DAY;
        } else {
            return currentHour % HOURS_IN_HALF_DAY;
        }
    }

    /**
     * Set the current minute (0-59).
     */
    @Override
    public void setMinute(int minute) {
        setMinuteInternal(minute, FROM_EXTERNAL_API);
    }

    private void setMinuteInternal(int minute, @ChangeSource int source) {
        if (mCurrentMinute == minute) {
            return;
        }

        mCurrentMinute = minute;
        updateHeaderMinute(minute, true);

        if (source != FROM_RADIAL_PICKER) {
            mRadialTimePickerView.setCurrentMinute(minute);
        }
        if (source != FROM_INPUT_PICKER) {
            updateTextInputPicker();
        }

        mDelegator.invalidate();
        onTimeChanged();
    }

    /**
     * @return The current minute.
     */
    @Override
    public int getMinute() {
        return mRadialTimePickerView.getCurrentMinute();
    }

    /**
     * Sets whether time is displayed in 24-hour mode or 12-hour mode with
     * AM/PM indicators.
     *
     * @param is24Hour {@code true} to display time in 24-hour mode or
     *                 {@code false} for 12-hour mode with AM/PM
     */
    public void setIs24Hour(boolean is24Hour) {
        if (mIs24Hour != is24Hour) {
            mIs24Hour = is24Hour;
            mCurrentHour = getHour();

            updateHourFormat();
            updateUI(mRadialTimePickerView.getCurrentItemShowing());
        }
    }

    /**
     * @return {@code true} if time is displayed in 24-hour mode, or
     * {@code false} if time is displayed in 12-hour mode with AM/PM
     * indicators
     */
    @Override
    public boolean is24Hour() {
        return mIs24Hour;
    }

    @Override
    public void setEnabled(boolean enabled) {
        mHourView.setEnabled(enabled);
        mMinuteView.setEnabled(enabled);
        mAmLabel.setEnabled(enabled);
        mPmLabel.setEnabled(enabled);
        mRadialTimePickerView.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return mIsEnabled;
    }

    @Override
    public int getBaseline() {
        // does not support baseline alignment
        return -1;
    }

    @Override
    public Parcelable onSaveInstanceState(Parcelable superState) {
        return new SavedState(superState, getHour(), getMinute(),
                is24Hour(), getCurrentItemShowing());
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state instanceof SavedState) {
            final SavedState ss = (SavedState) state;
            initialize(ss.getHour(), ss.getMinute(), ss.is24HourMode(), ss.getCurrentItemShowing());
            mRadialTimePickerView.invalidate();
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return true;
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (mIs24Hour) {
            flags |= DateUtils.FORMAT_24HOUR;
        } else {
            flags |= DateUtils.FORMAT_12HOUR;
        }

        mTempCalendar.set(Calendar.HOUR_OF_DAY, getHour());
        mTempCalendar.set(Calendar.MINUTE, getMinute());

        final String selectedTime = DateUtils.formatDateTime(mContext,
                mTempCalendar.getTimeInMillis(), flags);
        final String selectionMode = mRadialTimePickerView.getCurrentItemShowing() == HOUR_INDEX ?
                mSelectHours : mSelectMinutes;
        event.getText().add(selectedTime + " " + selectionMode);
    }

    /**
     * @hide
     */
    @Override
    public View getHourView() {
        return mHourView;
    }

    /**
     * @hide
     */
    @Override
    public View getMinuteView() {
        return mMinuteView;
    }

    /**
     * @hide
     */
    @Override
    public View getAmView() {
        return mAmLabel;
    }

    /**
     * @hide
     */
    @Override
    public View getPmView() {
        return mPmLabel;
    }

    /**
     * @return the index of the current item showing
     */
    private int getCurrentItemShowing() {
        return mRadialTimePickerView.getCurrentItemShowing();
    }

    /**
     * Propagate the time change
     */
    private void onTimeChanged() {
        mDelegator.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        if (mOnTimeChangedListener != null) {
            mOnTimeChangedListener.onTimeChanged(mDelegator, getHour(), getMinute());
        }
        if (mAutoFillChangeListener != null) {
            mAutoFillChangeListener.onTimeChanged(mDelegator, getHour(), getMinute());
        }
    }

    private void tryVibrate() {
        mDelegator.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void updateAmPmLabelStates(int amOrPm) {


        if (amOrPm == AM) {
            mAmLabel.setTextColor(ContextCompat.getColor(mContext, R.color.white));
            mPmLabel.setTextColor(ContextCompat.getColor(mContext, R.color.grey_1));
        } else {
            mAmLabel.setTextColor(ContextCompat.getColor(mContext, R.color.grey_1));
            mPmLabel.setTextColor(ContextCompat.getColor(mContext, R.color.white));
        }
        setAmOrPmTextView();

        final boolean isAm = amOrPm == AM;
        mAmLabel.setActivated(isAm);
        mAmLabel.setChecked(isAm);

        final boolean isPm = amOrPm == PM;
        mPmLabel.setActivated(isPm);
        mPmLabel.setChecked(isPm);
    }

    /**
     * Converts hour-of-day (0-23) time into a localized hour number.
     * <p>
     * The localized value may be in the range (0-23), (1-24), (0-11), or
     * (1-12) depending on the locale. This method does not handle leading
     * zeroes.
     *
     * @param hourOfDay the hour-of-day (0-23)
     * @return a localized hour number
     */
    private int getLocalizedHour(int hourOfDay) {
        if (!mIs24Hour) {
            // Convert to hour-of-am-pm.
            hourOfDay %= 12;
        }

        if (!mHourFormatStartsAtZero && hourOfDay == 0) {
            // Convert to clock-hour (either of-day or of-am-pm).
            hourOfDay = mIs24Hour ? 24 : 12;
        }

        return hourOfDay;
    }

    private void updateHeaderHour(int hourOfDay, boolean announce) {
        final int localizedHour = getLocalizedHour(hourOfDay);
        mHourView.setValue(localizedHour);

        if (announce) {
            tryAnnounceForAccessibility(mHourView.getText(), true);
        }

        setAmOrPmTextView();
    }

    private void updateHeaderMinute(int minuteOfHour, boolean announce) {
        mMinuteView.setValue(minuteOfHour);

        if (announce) {
            tryAnnounceForAccessibility(mMinuteView.getText(), false);
        }

        setAmOrPmTextView();
    }

    private void setAmOrPmTextView() {
        if (mAmLabel.isChecked()) {
            mAmOrPmTextView.setText("AM");
        } else {
            mAmOrPmTextView.setText("PM");
        }
    }

    /**
     * The time separator is defined in the Unicode CLDR and cannot be supposed to be ":".
     * <p>
     * See http://unicode.org/cldr/trac/browser/trunk/common/main
     * <p>
     * We pass the correct "skeleton" depending on 12 or 24 hours view and then extract the
     * separator as the character which is just after the hour marker in the returned pattern.
     */
    private void updateHeaderSeparator() {
        final String bestDateTimePattern = DateFormatFix.getBestDateTimePattern(mContext, mLocale,
                (mIs24Hour) ? "Hm" : "hm");
        final String separatorText;
        // See http://www.unicode.org/reports/tr35/tr35-dates.html for hour formats
        final char[] hourFormats = {'H', 'h', 'K', 'k'};
        int hIndex = lastIndexOfAny(bestDateTimePattern, hourFormats);
        if (hIndex == -1) {
            // Default case
            separatorText = ":";
        } else {
            separatorText = Character.toString(bestDateTimePattern.charAt(hIndex + 1));
        }
        mSeparatorView.setText(separatorText);
        mTextInputPickerView.updateSeparator(separatorText);
    }

    static private int lastIndexOfAny(String str, char[] any) {
        final int lengthAny = any.length;
        if (lengthAny > 0) {
            for (int i = str.length() - 1; i >= 0; i--) {
                char c = str.charAt(i);
                for (int j = 0; j < lengthAny; j++) {
                    if (c == any[j]) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private void tryAnnounceForAccessibility(CharSequence text, boolean isHour) {
        if (mLastAnnouncedIsHour != isHour || !text.equals(mLastAnnouncedText)) {
            // TODO: Find a better solution, potentially live regions?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                mDelegator.announceForAccessibility(text);
            }
            mLastAnnouncedText = text;
            mLastAnnouncedIsHour = isHour;
        }
    }

    /**
     * Show either Hours or Minutes.
     */
    private void setCurrentItemShowing(int index, boolean animateCircle, boolean announce) {
        mRadialTimePickerView.setCurrentItemShowing(index, animateCircle);

        if (index == HOUR_INDEX) {
            if (announce) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    mDelegator.announceForAccessibility(mSelectHours);
                }
            }
        } else {
            if (announce) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    mDelegator.announceForAccessibility(mSelectMinutes);
                }
            }
        }

        mHourView.setActivated(index == HOUR_INDEX);
        mMinuteView.setActivated(index == MINUTE_INDEX);
    }

    private void setAmOrPm(int amOrPm) {


        updateAmPmLabelStates(amOrPm);

        if (mRadialTimePickerView.setAmOrPm(amOrPm)) {
            mCurrentHour = getHour();
            updateTextInputPicker();
            if (mOnTimeChangedListener != null) {
                mOnTimeChangedListener.onTimeChanged(mDelegator, getHour(), getMinute());
            }
        }
    }

    /**
     * Listener for RadialTimePickerView interaction.
     */
    private final RadialTimePickerView.OnValueSelectedListener mOnValueSelectedListener = new RadialTimePickerView.OnValueSelectedListener() {
        @Override
        public void onValueSelected(int pickerType, int newValue, boolean autoAdvance) {
            boolean valueChanged = false;
            switch (pickerType) {
                case RadialTimePickerView.HOURS:
                    if (getHour() != newValue) {
                        valueChanged = true;
                    }
                    final boolean isTransition = mAllowAutoAdvance && autoAdvance;
                    setHourInternal(newValue, FROM_RADIAL_PICKER, !isTransition);
                    if (isTransition) {
                        setCurrentItemShowing(MINUTE_INDEX, true, false);

                        final int localizedHour = getLocalizedHour(newValue);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                            mDelegator.announceForAccessibility(localizedHour + ". " + mSelectMinutes);
                        }
                    }
                    break;
                case RadialTimePickerView.MINUTES:
                    if (getMinute() != newValue) {
                        valueChanged = true;
                    }
                    setMinuteInternal(newValue, FROM_RADIAL_PICKER);
                    break;
            }

            if (mOnTimeChangedListener != null && valueChanged) {
                mOnTimeChangedListener.onTimeChanged(mDelegator, getHour(), getMinute());
            }
        }
    };

    private final TextInputTimePickerView.OnValueTypedListener mOnValueTypedListener = new TextInputTimePickerView.OnValueTypedListener() {
        @Override
        public void onValueChanged(int pickerType, int newValue) {
            switch (pickerType) {
                case TextInputTimePickerView.HOURS:
                    setHourInternal(newValue, FROM_INPUT_PICKER, false);
                    break;
                case TextInputTimePickerView.MINUTES:
                    setMinuteInternal(newValue, FROM_INPUT_PICKER);
                    break;
                case TextInputTimePickerView.AMPM:
                    setAmOrPm(newValue);
                    break;
            }
        }
    };

    /**
     * Listener for keyboard interaction.
     */
    private final NumericTextView.OnValueChangedListener mDigitEnteredListener = new NumericTextView.OnValueChangedListener() {
        @Override
        public void onValueChanged(NumericTextView view, int value,
                                   boolean isValid, boolean isFinished) {
            final Runnable commitCallback;
            final View nextFocusTarget;
            if (view == mHourView) {
                commitCallback = mCommitHour;
                nextFocusTarget = view.isFocused() ? mMinuteView : null;
            } else if (view == mMinuteView) {
                commitCallback = mCommitMinute;
                nextFocusTarget = null;
            } else {
                return;
            }

            view.removeCallbacks(commitCallback);

            if (isValid) {
                if (isFinished) {
                    // Done with hours entry, make visual updates
                    // immediately and move to next focus if needed.
                    commitCallback.run();

                    if (nextFocusTarget != null) {
                        nextFocusTarget.requestFocus();
                    }
                } else {
                    // May still be making changes. Postpone visual
                    // updates to prevent distracting the user.
                    view.postDelayed(commitCallback, DELAY_COMMIT_MILLIS);
                }
            }
        }
    };

    private final Runnable mCommitHour = new Runnable() {
        @Override
        public void run() {
            setHour(mHourView.getValue());
        }
    };

    private final Runnable mCommitMinute = new Runnable() {
        @Override
        public void run() {
            setMinute(mMinuteView.getValue());
        }
    };

    private final View.OnFocusChangeListener mFocusListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean focused) {
            if (focused) {
                int i = v.getId();
                if (i == R.id.am_label) {
                    setAmOrPm(AM);
                } else if (i == R.id.pm_label) {
                    setAmOrPm(PM);
                } else if (i == R.id.hours) {
                    setCurrentItemShowing(HOUR_INDEX, true, true);
                } else if (i == R.id.minutes) {
                    setCurrentItemShowing(MINUTE_INDEX, true, true);
                } else {
                    // Failed to handle this click, don't vibrate.
                    return;
                }

                tryVibrate();
            }
        }
    };

    private final View.OnClickListener mClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            final int amOrPm;
            int i = v.getId();
            if (i == R.id.am_label) {
                setAmOrPm(AM);
            } else if (i == R.id.pm_label) {
                setAmOrPm(PM);
            } else if (i == R.id.hours) {
                setCurrentItemShowing(HOUR_INDEX, true, true);
            } else if (i == R.id.minutes) {
                setCurrentItemShowing(MINUTE_INDEX, true, true);
            } else {
                // Failed to handle this click, don't vibrate.
                return;
            }

            tryVibrate();
        }
    };

    /**
     * Delegates unhandled touches in a view group to the nearest child view.
     */
    private static class NearestTouchDelegate implements View.OnTouchListener {
        private View mInitialTouchTarget;

        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            final int actionMasked = motionEvent.getActionMasked();
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                if (view instanceof ViewGroup) {
                    mInitialTouchTarget = findNearestChild((ViewGroup) view,
                            (int) motionEvent.getX(), (int) motionEvent.getY());
                } else {
                    mInitialTouchTarget = null;
                }
            }

            final View child = mInitialTouchTarget;
            if (child == null) {
                return false;
            }

            final float offsetX = view.getScrollX() - child.getLeft();
            final float offsetY = view.getScrollY() - child.getTop();
            motionEvent.offsetLocation(offsetX, offsetY);
            final boolean handled = child.dispatchTouchEvent(motionEvent);
            motionEvent.offsetLocation(-offsetX, -offsetY);

            if (actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL) {
                mInitialTouchTarget = null;
            }

            return handled;
        }

        private View findNearestChild(ViewGroup v, int x, int y) {
            View bestChild = null;
            int bestDist = Integer.MAX_VALUE;

            for (int i = 0, count = v.getChildCount(); i < count; i++) {
                final View child = v.getChildAt(i);
                final int dX = x - (child.getLeft() + child.getWidth() / 2);
                final int dY = y - (child.getTop() + child.getHeight() / 2);
                final int dist = dX * dX + dY * dY;
                if (bestDist > dist) {
                    bestChild = child;
                    bestDist = dist;
                }
            }

            return bestChild;
        }
    }
}
