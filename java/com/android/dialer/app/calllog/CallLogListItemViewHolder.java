/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.dialer.app.calllog;

import android.Manifest.permission;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.provider.BlockedNumberContract;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.transition.TransitionManager;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.android.contacts.common.dialog.CallSubjectDialog;
import com.android.dialer.R;
import com.android.dialer.app.calllog.CallLogAdapter.OnActionModeStateChangedListener;
import com.android.dialer.app.calllog.calllogcache.CallLogCache;
import com.android.dialer.app.voicemail.VoicemailPlaybackLayout;
import com.android.dialer.app.voicemail.VoicemailPlaybackPresenter;
import com.android.dialer.calldetails.CallDetailsEntries;
import com.android.dialer.calldetails.OldCallDetailsActivity;
import com.android.dialer.calllogutils.CallbackActionHelper.CallbackAction;
import com.android.dialer.clipboard.ClipboardUtils;
import com.android.dialer.common.LogUtil;
import com.android.dialer.common.concurrent.AsyncTaskExecutors;
import com.android.dialer.contactphoto.ContactPhotoManager;
import com.android.dialer.dialercontact.DialerContact;
import com.android.dialer.dialercontact.SimDetails;
import com.android.dialer.lettertile.LetterTileDrawable;
import com.android.dialer.lettertile.LetterTileDrawable.ContactType;
import com.android.dialer.logging.ContactSource;
import com.android.dialer.logging.ContactSource.Type;
import com.android.dialer.phonenumbercache.CachedNumberLookupService;
import com.android.dialer.phonenumbercache.ContactInfo;
import com.android.dialer.phonenumbercache.PhoneNumberCache;
import com.android.dialer.phonenumberutil.PhoneNumberHelper;
import com.android.dialer.telecom.TelecomUtil;
import com.android.dialer.util.CallUtil;
import com.android.dialer.util.DialerUtils;
import com.android.dialer.util.UriUtils;

import java.lang.ref.WeakReference;

/**
 * This is an object containing references to views contained by the call log list item. This
 * improves performance by reducing the frequency with which we need to find views by IDs.
 *
 * <p>This object also contains UI logic pertaining to the view, to isolate it from the
 * CallLogAdapter.
 */
public final class CallLogListItemViewHolder extends RecyclerView.ViewHolder
    implements View.OnClickListener,
        View.OnLongClickListener,
        MenuItem.OnMenuItemClickListener,
        View.OnCreateContextMenuListener {

  private static final String TASK_DELETE = "task_delete";

  /** The root view of the call log list item */
  public final View rootView;
  /** The quick contact badge for the contact. */
  public final DialerQuickContactBadge quickContactView;
  /** The primary action view of the entry. */
  public final View primaryActionView;
  /** The details of the phone call. */
  public final PhoneCallDetailsViews phoneCallDetailsViews;
  /** The text of the header for a day grouping. */
  public final TextView dayGroupHeader;
  /** The view containing the details for the call log row, including the action buttons. */
  public final CardView callLogEntryView;
  /** The actionable view which places a call to the number corresponding to the call log row. */
  public final ImageView primaryActionButtonView;

  private final Context context;
  @Nullable
  private final PhoneAccountHandle defaultPhoneAccountHandle;
  private final CallLogCache callLogCache;
  private final CallLogListItemHelper callLogListItemHelper;
  private final CachedNumberLookupService cachedNumberLookupService;
  private final VoicemailPlaybackPresenter voicemailPlaybackPresenter;
  private final OnClickListener blockReportListener;
  /** Whether the data fields are populated by the worker thread, ready to be shown. */
  public boolean isLoaded;
  /** The view containing call log item actions. Null until the ViewStub is inflated. */
  public View actionsView;
  /** The button views below are assigned only when the action section is expanded. */
  public VoicemailPlaybackLayout voicemailPlaybackView;

  public View callButtonView;
  public View videoCallButtonView;
  public View createNewContactButtonView;
  public View addToExistingContactButtonView;
  public View sendMessageView;
  public View blockReportView;
  public View blockView;
  public View unblockView;
  public View reportNotSpamView;
  public View detailsButtonView;
  public View callWithNoteButtonView;
  public View sendVoicemailButtonView;
  public final ImageView workIconView;
  public final ImageView checkBoxView;
  /**
   * The row Id for the first call associated with the call log entry. Used as a key for the map
   * used to track which call log entries have the action button section expanded.
   */
  public long rowId;
  /**
   * The call Ids for the calls represented by the current call log entry. Used when the user
   * deletes a call log entry.
   */
  public long[] callIds;
  /**
   * The callable phone number for the current call log entry. Cached here as the call back intent
   * is set only when the actions ViewStub is inflated.
   */
  @Nullable public String number;
  /** The post-dial numbers that are dialed following the phone number. */
  public String postDialDigits;
  /** The formatted phone number to display. */
  public String displayNumber;
  /**
   * The phone number presentation for the current call log entry. Cached here as the call back
   * intent is set only when the actions ViewStub is inflated.
   */
  public int numberPresentation;
  /** The type of the phone number (e.g. main, work, etc). */
  public String numberType;
  /**
   * The country iso for the call. Cached here as the call back intent is set only when the actions
   * ViewStub is inflated.
   */
  public String countryIso;
  /**
   * The type of call for the current call log entry. Cached here as the call back intent is set
   * only when the actions ViewStub is inflated.
   */
  public int callType;
  /**
   * Set when context menu is created, if the number is blocked.
   */
  public boolean isBlocked;
  /**
   * The account for the current call log entry. Cached here as the call back intent is set only
   * when the actions ViewStub is inflated.
   */
  public PhoneAccountHandle accountHandle;
  /**
   * If the call has an associated voicemail message, the URI of the voicemail message for playback.
   * Cached here as the voicemail intent is only set when the actions ViewStub is inflated.
   */
  public String voicemailUri;
  /**
   * The name or number associated with the call. Cached here for use when setting content
   * descriptions on buttons in the actions ViewStub when it is inflated.
   */
  @Nullable public CharSequence nameOrNumber;
  /**
   * The call type or Location associated with the call. Cached here for use when setting text for a
   * voicemail log's call button
   */
  public CharSequence callTypeOrLocation;
  /** The contact info for the contact displayed in this list item. */
  public volatile ContactInfo info;
  /** Whether the current log entry is a spam number or not. */
  public boolean isSpam;

  private final View.OnClickListener expandCollapseListener;
  private final OnActionModeStateChangedListener onActionModeStateChangedListener;
  private final View.OnLongClickListener longPressListener;
  private boolean voicemailPrimaryActionButtonClicked;

  public int callbackAction;
  public int dayGroupHeaderVisibility;
  public CharSequence dayGroupHeaderText;
  public boolean isAttachedToWindow;

  public CallLogAdapter.LoadDataTaskInterface asyncTask;
  private CallDetailsEntries callDetailsEntries;

  private CallLogListItemViewHolder(
      Context context,
      OnClickListener blockReportListener,
      View.OnClickListener expandCollapseListener,
      View.OnLongClickListener longClickListener,
      CallLogAdapter.OnActionModeStateChangedListener actionModeStateChangedListener,
      CallLogCache callLogCache,
      CallLogListItemHelper callLogListItemHelper,
      VoicemailPlaybackPresenter voicemailPlaybackPresenter,
      View rootView,
      DialerQuickContactBadge dialerQuickContactView,
      View primaryActionView,
      PhoneCallDetailsViews phoneCallDetailsViews,
      CardView callLogEntryView,
      TextView dayGroupHeader,
      ImageView primaryActionButtonView) {
    super(rootView);

    this.context = context;
    this.expandCollapseListener = expandCollapseListener;
    onActionModeStateChangedListener = actionModeStateChangedListener;
    longPressListener = longClickListener;
    this.callLogCache = callLogCache;
    this.callLogListItemHelper = callLogListItemHelper;
    this.voicemailPlaybackPresenter = voicemailPlaybackPresenter;
    this.blockReportListener = blockReportListener;
    cachedNumberLookupService = PhoneNumberCache.get(this.context).getCachedNumberLookupService();

    // Cache this to avoid having to look it up each time we bind to a call log entry
    defaultPhoneAccountHandle =
        TelecomUtil.getDefaultOutgoingPhoneAccount(context, PhoneAccount.SCHEME_TEL);

    this.rootView = rootView;
    this.quickContactView = dialerQuickContactView;
    this.primaryActionView = primaryActionView;
    this.phoneCallDetailsViews = phoneCallDetailsViews;
    this.callLogEntryView = callLogEntryView;
    this.dayGroupHeader = dayGroupHeader;
    this.primaryActionButtonView = primaryActionButtonView;
    this.workIconView = rootView.findViewById(R.id.work_profile_icon);
    this.checkBoxView = rootView.findViewById(R.id.quick_contact_checkbox);

    // Set text height to false on the TextViews so they don't have extra padding.
    phoneCallDetailsViews.nameView.setElegantTextHeight(false);
    phoneCallDetailsViews.callLocationAndDate.setElegantTextHeight(false);

    quickContactView.setOverlay(null);
    quickContactView.setPrioritizedMimeType(Phone.CONTENT_ITEM_TYPE);
    primaryActionButtonView.setOnClickListener(this);
    primaryActionButtonView.setOnLongClickListener(this);
    primaryActionView.setOnClickListener(this.expandCollapseListener);
    if (this.voicemailPlaybackPresenter != null) {
      primaryActionView.setOnLongClickListener(longPressListener);
      quickContactView.setOnLongClickListener(longPressListener);
      quickContactView.setMulitSelectListeners(
          this.expandCollapseListener, onActionModeStateChangedListener);
    } else {
      primaryActionView.setOnCreateContextMenuListener(this);
    }
  }

  public static CallLogListItemViewHolder create(
      View view,
      Context context,
      OnClickListener blockReportListener,
      View.OnClickListener expandCollapseListener,
      View.OnLongClickListener longClickListener,
      CallLogAdapter.OnActionModeStateChangedListener actionModeStateChangeListener,
      CallLogCache callLogCache,
      CallLogListItemHelper callLogListItemHelper,
      VoicemailPlaybackPresenter voicemailPlaybackPresenter) {

    return new CallLogListItemViewHolder(
        context,
        blockReportListener,
        expandCollapseListener,
        longClickListener,
        actionModeStateChangeListener,
        callLogCache,
        callLogListItemHelper,
        voicemailPlaybackPresenter,
        view,
        (DialerQuickContactBadge) view.findViewById(R.id.quick_contact_photo),
        view.findViewById(R.id.primary_action_view),
        PhoneCallDetailsViews.fromView(view),
        (CardView) view.findViewById(R.id.call_log_row),
        (TextView) view.findViewById(R.id.call_log_day_group_label),
        (ImageView) view.findViewById(R.id.primary_action_button));
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    int resId = item.getItemId();
    if (resId == R.id.context_menu_copy_to_clipboard) {
      ClipboardUtils.copyText(context, null, number, true);
      return true;
    } else if (resId == R.id.context_menu_edit_before_call) {
      final Intent intent = new Intent(Intent.ACTION_DIAL, CallUtil.getCallUri(number));
      DialerUtils.startActivityWithErrorToast(context, intent);
      return true;
    } else if (resId == R.id.context_menu_block_report_spam) {
      blockReportListener.onBlockReportSpam(
              displayNumber, number, countryIso, callType, info.sourceType);
    } else if (resId == R.id.context_menu_block) {
      blockReportListener.onBlock(
              displayNumber, number, countryIso, callType, info.sourceType);
    } else if (resId == R.id.context_menu_unblock) {
      blockReportListener.onUnblock(
          displayNumber, number, countryIso, callType, info.sourceType, isSpam);
    } else if (resId == R.id.context_menu_report_not_spam) {
      blockReportListener.onReportNotSpam(
          displayNumber, number, countryIso, callType, info.sourceType);
    } else if (resId == R.id.context_menu_delete) {
      AsyncTaskExecutors.createAsyncTaskExecutor()
          .submit(TASK_DELETE, new DeleteCallTask(context, callIds));
    }
    return false;
  }

  /**
   * Configures the action buttons in the expandable actions ViewStub. The ViewStub is not inflated
   * during initial binding, so click handlers, tags and accessibility text must be set here, if
   * necessary.
   */
  public void inflateActionViewStub() {
    ViewStub stub = (ViewStub) rootView.findViewById(R.id.call_log_entry_actions_stub);
    if (stub != null) {
      actionsView = stub.inflate();

      voicemailPlaybackView =
          (VoicemailPlaybackLayout) actionsView.findViewById(R.id.voicemail_playback_layout);
      voicemailPlaybackView.setViewHolder(this);

      callButtonView = actionsView.findViewById(R.id.call_action);
      callButtonView.setOnClickListener(this);

      videoCallButtonView = actionsView.findViewById(R.id.video_call_action);
      videoCallButtonView.setOnClickListener(this);

      createNewContactButtonView = actionsView.findViewById(R.id.create_new_contact_action);
      createNewContactButtonView.setOnClickListener(this);

      addToExistingContactButtonView =
          actionsView.findViewById(R.id.add_to_existing_contact_action);
      addToExistingContactButtonView.setOnClickListener(this);

      sendMessageView = actionsView.findViewById(R.id.send_message_action);
      sendMessageView.setOnClickListener(this);

      blockReportView = actionsView.findViewById(R.id.block_report_action);
      blockReportView.setOnClickListener(this);

      blockView = actionsView.findViewById(R.id.block_action);
      blockView.setOnClickListener(this);

      unblockView = actionsView.findViewById(R.id.unblock_action);
      unblockView.setOnClickListener(this);

      reportNotSpamView = actionsView.findViewById(R.id.report_not_spam_action);
      reportNotSpamView.setOnClickListener(this);

      detailsButtonView = actionsView.findViewById(R.id.details_action);
      detailsButtonView.setOnClickListener(this);

      callWithNoteButtonView = actionsView.findViewById(R.id.call_with_note_action);
      callWithNoteButtonView.setOnClickListener(this);

      sendVoicemailButtonView = actionsView.findViewById(R.id.share_voicemail);
      sendVoicemailButtonView.setOnClickListener(this);

      actionsView.setVisibility(View.GONE);
    }
  }

  private void updatePrimaryActionButton(boolean isExpanded) {

    if (nameOrNumber == null) {
      LogUtil.e("CallLogListItemViewHolder.updatePrimaryActionButton", "name or number is null");
    }

    // Calling expandTemplate with a null parameter will cause a NullPointerException.
    CharSequence validNameOrNumber = nameOrNumber == null ? "" : nameOrNumber;

    if (!TextUtils.isEmpty(voicemailUri)) {
      // Treat as voicemail list item; show play button if not expanded.
      if (!isExpanded) {
        primaryActionButtonView.setImageResource(R.drawable.quantum_ic_play_arrow_vd_theme_24);
        primaryActionButtonView.setContentDescription(
            TextUtils.expandTemplate(
                context.getString(R.string.description_voicemail_action), validNameOrNumber));
        primaryActionButtonView.setTag(null);
        primaryActionButtonView.setVisibility(View.VISIBLE);
      } else {
        primaryActionButtonView.setVisibility(View.GONE);
      }
      return;
    }

    // Treat as normal list item; show call button, if possible.
    if (!PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation)) {
      primaryActionButtonView.setTag(null);
      primaryActionButtonView.setVisibility(View.GONE);
      return;
    }

    switch (callbackAction) {
      case CallbackAction.IMS_VIDEO:
        primaryActionButtonView.setTag(
            IntentProvider.getReturnVideoCallIntentProvider(number, accountHandle));
        primaryActionButtonView.setContentDescription(
            TextUtils.expandTemplate(
                context.getString(R.string.description_video_call_action), validNameOrNumber));
        primaryActionButtonView.setImageResource(R.drawable.quantum_ic_videocam_vd_theme_24);
        primaryActionButtonView.setVisibility(View.VISIBLE);
        break;
      case CallbackAction.VOICE:
        if (callLogCache.isVoicemailNumber(accountHandle, number)) {
          // Call to generic voicemail number, in case there are multiple accounts
          primaryActionButtonView.setTag(IntentProvider.getReturnVoicemailCallIntentProvider());
        } else if (canSupportAssistedDialing()) {
          primaryActionButtonView.setTag(
              IntentProvider.getAssistedDialIntentProvider(
                  number + postDialDigits,
                  context,
                  context.getSystemService(TelephonyManager.class)));
        } else {
          primaryActionButtonView.setTag(
              IntentProvider.getReturnCallIntentProvider(number + postDialDigits));
        }

        primaryActionButtonView.setContentDescription(
            TextUtils.expandTemplate(
                context.getString(R.string.description_call_action), validNameOrNumber));
        primaryActionButtonView.setImageResource(R.drawable.quantum_ic_call_vd_theme_24);
        primaryActionButtonView.setVisibility(View.VISIBLE);
        break;
      default:
        primaryActionButtonView.setTag(null);
        primaryActionButtonView.setVisibility(View.GONE);
    }
  }

  /**
   * Binds text titles, click handlers and intents to the voicemail, details and callback action
   * buttons.
   */
  private void bindActionButtons() {
    boolean canPlaceCallToNumber = PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation);

    // Hide the call buttons by default. We then set it to be visible when appropriate below.
    // This saves us having to remember to set it to GONE in multiple places.
    callButtonView.setVisibility(View.GONE);
    videoCallButtonView.setVisibility(View.GONE);

    // For an emergency number, show "Call details" only.
    if (PhoneNumberHelper.isEmergencyNumber(context, number)) {
      createNewContactButtonView.setVisibility(View.GONE);
      addToExistingContactButtonView.setVisibility(View.GONE);
      sendMessageView.setVisibility(View.GONE);
      callWithNoteButtonView.setVisibility(View.GONE);
      blockReportView.setVisibility(View.GONE);
      blockView.setVisibility(View.GONE);
      unblockView.setVisibility(View.GONE);
      reportNotSpamView.setVisibility(View.GONE);
      voicemailPlaybackView.setVisibility(View.GONE);

      detailsButtonView.setVisibility(View.VISIBLE);
      detailsButtonView.setTag(
          IntentProvider.getCallDetailIntentProvider(
              callDetailsEntries,
              buildContact(),
              /* canReportCallerId = */ false,
              /* canSupportAssistedDialing = */ false));
      return;
    }

    if (isFullyUndialableVoicemail()) {
      // Sometimes the voicemail server will report the message is from some non phone number
      // source. If the number does not contains any dialable digit treat it as it is from a unknown
      // number, remove all action buttons but still show the voicemail playback layout.
      detailsButtonView.setVisibility(View.GONE);
      createNewContactButtonView.setVisibility(View.GONE);
      addToExistingContactButtonView.setVisibility(View.GONE);
      sendMessageView.setVisibility(View.GONE);
      callWithNoteButtonView.setVisibility(View.GONE);
      blockReportView.setVisibility(View.GONE);
      blockView.setVisibility(View.GONE);
      unblockView.setVisibility(View.GONE);
      reportNotSpamView.setVisibility(View.GONE);

      voicemailPlaybackView.setVisibility(View.VISIBLE);
      Uri uri = Uri.parse(voicemailUri);
      voicemailPlaybackPresenter.setPlaybackView(
          voicemailPlaybackView,
          rowId,
          uri,
          voicemailPrimaryActionButtonClicked,
          sendVoicemailButtonView);
      voicemailPrimaryActionButtonClicked = false;
      CallLogAsyncTaskUtil.markVoicemailAsRead(context, uri);
      return;
    }

    TextView callTypeOrLocationView =
        ((TextView) callButtonView.findViewById(R.id.call_type_or_location_text));

    if (canPlaceCallToNumber) {
      if (canSupportAssistedDialing()) {
        callButtonView.setTag(
            IntentProvider.getAssistedDialIntentProvider(
                number, context, context.getSystemService(TelephonyManager.class)));
      } else {
        callButtonView.setTag(IntentProvider.getReturnCallIntentProvider(number));
      }
      callTypeOrLocationView.setVisibility(View.GONE);
    }

    if (!TextUtils.isEmpty(voicemailUri) && canPlaceCallToNumber) {
      ((TextView) callButtonView.findViewById(R.id.call_action_text))
          .setText(
              TextUtils.expandTemplate(
                  context.getString(R.string.call_log_action_call),
                  nameOrNumber == null ? "" : nameOrNumber));

      if (callType == Calls.VOICEMAIL_TYPE && !TextUtils.isEmpty(callTypeOrLocation)) {
        callTypeOrLocationView.setText(callTypeOrLocation);
        callTypeOrLocationView.setVisibility(View.VISIBLE);
      }
      callButtonView.setVisibility(View.VISIBLE);
    }

    boolean isVoicemailNumber = callLogCache.isVoicemailNumber(accountHandle, number);

    switch (callbackAction) {
      case CallbackAction.IMS_VIDEO:
        // For an IMS video call, the secondary action should always be a
        // voice callback.
        callButtonView.setVisibility(View.VISIBLE);
        videoCallButtonView.setVisibility(View.GONE);
        break;
      case CallbackAction.VOICE:
        // For a voice call, set the secondary callback action to be an IMS video call if it is
        // available. Otherwise try to set it as a Duo call.
        if (CallUtil.isVideoEnabled(context)
            && (hasPlacedCarrierVideoCall() || canSupportCarrierVideoCall())) {
          videoCallButtonView.setTag(IntentProvider.getReturnVideoCallIntentProvider(number));
          videoCallButtonView.setVisibility(View.VISIBLE);
          break;
        }

        if (isVoicemailNumber) {
          break;
        }
        break;
      default:
        callButtonView.setVisibility(View.GONE);
        videoCallButtonView.setVisibility(View.GONE);
    }

    // For voicemail calls, show the voicemail playback layout; hide otherwise.
    if (callType == Calls.VOICEMAIL_TYPE
        && voicemailPlaybackPresenter != null
        && !TextUtils.isEmpty(voicemailUri)) {
      voicemailPlaybackView.setVisibility(View.VISIBLE);

      Uri uri = Uri.parse(voicemailUri);
      voicemailPlaybackPresenter.setPlaybackView(
          voicemailPlaybackView,
          rowId,
          uri,
          voicemailPrimaryActionButtonClicked,
          sendVoicemailButtonView);
      voicemailPrimaryActionButtonClicked = false;
      CallLogAsyncTaskUtil.markVoicemailAsRead(context, uri);
    } else {
      voicemailPlaybackView.setVisibility(View.GONE);
      sendVoicemailButtonView.setVisibility(View.GONE);
    }

    if (callType == Calls.VOICEMAIL_TYPE) {
      detailsButtonView.setVisibility(View.GONE);
    } else {
      detailsButtonView.setVisibility(View.VISIBLE);
      boolean canReportCallerId =
          cachedNumberLookupService != null
              && cachedNumberLookupService.canReportAsInvalid(info.sourceType, info.objectId);
      detailsButtonView.setTag(
          IntentProvider.getCallDetailIntentProvider(
              callDetailsEntries, buildContact(), canReportCallerId, canSupportAssistedDialing()));
    }

    boolean isBlockedOrSpam = isBlocked || isSpam;

    if (!isBlockedOrSpam && info != null && UriUtils.isEncodedContactUri(info.lookupUri)) {
      createNewContactButtonView.setTag(
          IntentProvider.getAddContactIntentProvider(
              info.lookupUri, info.name, info.number, info.type, true /* isNewContact */));
      createNewContactButtonView.setVisibility(View.VISIBLE);

      addToExistingContactButtonView.setTag(
          IntentProvider.getAddContactIntentProvider(
              info.lookupUri, info.name, info.number, info.type, false /* isNewContact */));
      addToExistingContactButtonView.setVisibility(View.VISIBLE);
    } else {
      createNewContactButtonView.setVisibility(View.GONE);
      addToExistingContactButtonView.setVisibility(View.GONE);
    }

    if (canPlaceCallToNumber && !isBlockedOrSpam && !isVoicemailNumber) {
      sendMessageView.setTag(IntentProvider.getSendSmsIntentProvider(number));
      sendMessageView.setVisibility(View.VISIBLE);
    } else {
      sendMessageView.setVisibility(View.GONE);
    }

    callLogListItemHelper.setActionContentDescriptions(this);

    boolean supportsCallSubject = callLogCache.doesAccountSupportCallSubject(accountHandle);
    callWithNoteButtonView.setVisibility(
        supportsCallSubject && !isVoicemailNumber && info != null ? View.VISIBLE : View.GONE);

    updateBlockReportActions(canPlaceCallToNumber, isVoicemailNumber);
  }

  private boolean isFullyUndialableVoicemail() {
    if (callType == Calls.VOICEMAIL_TYPE) {
      if (!hasDialableChar(number)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDialableChar(CharSequence number) {
    if (TextUtils.isEmpty(number)) {
      return false;
    }
    for (char c : number.toString().toCharArray()) {
      if (PhoneNumberUtils.isDialable(c)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasPlacedCarrierVideoCall() {
    if (!phoneCallDetailsViews.callTypeIcons.isVideoShown()) {
      return false;
    }
    if (accountHandle == null) {
      return false;
    }
    if (defaultPhoneAccountHandle == null) {
      return false;
    }
    return accountHandle.getComponentName().equals(defaultPhoneAccountHandle.getComponentName());
  }

  private boolean canSupportAssistedDialing() {
    return info != null && info.lookupKey != null;
  }

  private boolean canSupportCarrierVideoCall() {
    return callLogCache.canRelyOnVideoPresence()
        && info != null
        && (info.carrierPresence & Phone.CARRIER_PRESENCE_VT_CAPABLE) != 0;
  }

  /**
   * Show or hide the action views, such as voicemail, details, and add contact.
   *
   * <p>If the action views have never been shown yet for this view, inflate the view stub.
   */
  public void showActions(boolean show) {
    if (show) {
      if (!isLoaded) {
        // a bug for some unidentified reason showActions() can be called before the item is
        // loaded, causing NPE on uninitialized fields. Just log and return here, showActions() will
        // be called again once the item is loaded.
        LogUtil.e(
            "CallLogListItemViewHolder.showActions",
            "called before item is loaded",
            new Exception());
        return;
      }

      TransitionManager.beginDelayedTransition((ViewGroup) rootView);
      // Inflate the view stub if necessary, and wire up the event handlers.
      inflateActionViewStub();
      bindActionButtons();
      animateActions(true);
      TransitionManager.endTransitions((ViewGroup) rootView);
    } else {
      TransitionManager.beginDelayedTransition((ViewGroup) rootView);
      // When recycling a view, it is possible the actionsView ViewStub was previously
      // inflated so we should hide it in this case.
      if (actionsView != null) {
        animateActions(false);
      }
      TransitionManager.endTransitions((ViewGroup) rootView);
    }
    updatePrimaryActionButton(show);
  }

  private void animateActions(boolean shouldExpand) {
    boolean isExpanded = actionsView.getVisibility() == View.VISIBLE;
    if (shouldExpand == isExpanded) {
      return;
    }
    Resources res = context.getResources();
    actionsView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    int currentHeight = callLogEntryView.getMeasuredHeight();
    int additionalHeight = actionsView.getMeasuredHeight();

    int targetHeight;
    int colorFrom, colorTo;
    TimeInterpolator interpolator;
    int targetVisibility;
    float targetElevation;
    if (shouldExpand) {
      targetHeight = currentHeight + additionalHeight;
      colorFrom = res.getColor(android.R.color.transparent, context.getTheme());
      colorTo  = res.getColor(R.color.cardBackgroundColor, context.getTheme());
      interpolator = new AccelerateDecelerateInterpolator();
      targetVisibility = View.VISIBLE;
      targetElevation = 4f;
    } else {
      targetHeight = currentHeight - additionalHeight;
      colorFrom  = res.getColor(R.color.cardBackgroundColor, context.getTheme());
      colorTo = res.getColor(android.R.color.transparent, context.getTheme());
      interpolator = new DecelerateInterpolator();
      targetVisibility = View.GONE;
      targetElevation = 0f;
      // need to do this before animating, otherwise the color changes are weird
      callLogEntryView.setCardElevation(targetElevation);
    }

    ValueAnimator heightAnimator = ValueAnimator.ofInt(currentHeight, targetHeight);
    heightAnimator.addUpdateListener(animation -> {
      callLogEntryView.getLayoutParams().height = (int) animation.getAnimatedValue();
      callLogEntryView.requestLayout();
    });
    heightAnimator.setInterpolator(interpolator);
    heightAnimator.setDuration(200);

    ValueAnimator colorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
    colorAnimator.setDuration(200);
    colorAnimator.addUpdateListener(animator -> callLogEntryView.setCardBackgroundColor(
            (int) animator.getAnimatedValue()));

    AnimatorSet animatorSet = new AnimatorSet();
    animatorSet.playTogether(heightAnimator, colorAnimator);
    animatorSet.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        super.onAnimationEnd(animation);
        actionsView.setVisibility(targetVisibility);
        callLogEntryView.setCardElevation(targetElevation);

        // we need to set this so we can expand again
        ViewGroup.LayoutParams params = callLogEntryView.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        callLogEntryView.setLayoutParams(params);
      }
    });
    animatorSet.start();
  }

  public void updatePhoto() {
    quickContactView.assignContactUri(info.lookupUri);

    final String displayName = TextUtils.isEmpty(info.name) ? displayNumber : info.name;
    ContactPhotoManager.getInstance(context)
        .loadDialerThumbnailOrPhoto(
            quickContactView,
            info.lookupUri,
            info.photoId,
            info.photoUri,
            displayName,
            getContactType());
  }

  private @ContactType int getContactType() {
    return LetterTileDrawable.getContactTypeFromPrimitives(
        callLogCache.isVoicemailNumber(accountHandle, number),
        isSpam,
        cachedNumberLookupService != null && cachedNumberLookupService.isBusiness(info.sourceType),
        numberPresentation,
        false);
  }

  @Override
  public void onClick(View view) {
    if (view.getId() == R.id.primary_action_button) {
      CallLogAsyncTaskUtil.markCallAsRead(context, callIds);
    }

    if (view.getId() == R.id.primary_action_button && !TextUtils.isEmpty(voicemailUri)) {
      voicemailPrimaryActionButtonClicked = true;
      expandCollapseListener.onClick(primaryActionView);
      return;
    }

    if (view.getId() == R.id.call_with_note_action) {
      CallSubjectDialog.start(
          (Activity) context,
          info.photoId,
          info.photoUri,
          info.lookupUri,
          (String) nameOrNumber /* top line of contact view in call subject dialog */,
          number,
          TextUtils.isEmpty(info.name) ? null : displayNumber, /* second line of contact
                                                                           view in dialog. */
          numberType, /* phone number type (e.g. mobile) in second line of contact view */
          getContactType(),
          accountHandle);
      return;
    }

    if (view.getId() == R.id.block_report_action) {
      blockReportListener.onBlockReportSpam(
              displayNumber, number, countryIso, callType, info.sourceType);
      return;
    }

    if (view.getId() == R.id.block_action) {
      blockReportListener.onBlock(
              displayNumber, number, countryIso, callType, info.sourceType);
      return;
    }

    if (view.getId() == R.id.unblock_action) {
      blockReportListener.onUnblock(
          displayNumber, number, countryIso, callType, info.sourceType, isSpam);
      return;
    }

    if (view.getId() == R.id.report_not_spam_action) {
      blockReportListener.onReportNotSpam(
          displayNumber, number, countryIso, callType, info.sourceType);
      return;
    }

    if (view.getId() == R.id.share_voicemail) {
      voicemailPlaybackPresenter.shareVoicemail();
      return;
    }

    final IntentProvider intentProvider = (IntentProvider) view.getTag();
    if (intentProvider == null) {
      return;
    }

    final Intent intent = intentProvider.getClickIntent(context);
    // See IntentProvider.getCallDetailIntentProvider() for why this may be null.
    if (intent == null) {
      return;
    }
    if (OldCallDetailsActivity.isLaunchIntent(intent)) {
      ((Activity) context).startActivity(intent);
    } else {
      DialerUtils.startActivityWithErrorToast(context, intent);
    }
  }

  @Override
  public boolean onLongClick(View view) {
    final IntentProvider intentProvider = (IntentProvider) view.getTag();
    final Intent intent = intentProvider != null
        ? intentProvider.getLongClickIntent(context) : null;
    if (intent != null) {
      DialerUtils.startActivityWithErrorToast(context, intent);
      return true;
    }
    return false;
  }

  private DialerContact buildContact() {
    DialerContact.Builder contact = DialerContact.newBuilder();
    contact.setPhotoId(info.photoId);
    if (info.photoUri != null) {
      contact.setPhotoUri(info.photoUri.toString());
    }
    if (info.lookupUri != null) {
      contact.setContactUri(info.lookupUri.toString());
    }
    if (nameOrNumber != null) {
      contact.setNameOrNumber((String) nameOrNumber);
    }
    contact.setContactType(getContactType());
    if (number != null) {
      contact.setNumber(number);
    }

    if (!TextUtils.isEmpty(postDialDigits)) {
      contact.setPostDialDigits(postDialDigits);
    }

    /* second line of contact view. */
    if (!TextUtils.isEmpty(info.name)) {
      contact.setDisplayNumber(displayNumber);
    }
    /* phone number type (e.g. mobile) in second line of contact view */
    contact.setNumberLabel(numberType);

    /* third line of contact view. */
    String accountLabel = callLogCache.getAccountLabel(accountHandle);
    if (!TextUtils.isEmpty(accountLabel)) {
      SimDetails.Builder simDetails = SimDetails.newBuilder().setNetwork(accountLabel);
      simDetails.setColor(callLogCache.getAccountColor(accountHandle));
      contact.setSimDetails(simDetails.build());
    }
    return contact.build();
  }

  private void updateBlockReportActions(boolean canPlaceCallToNumber, boolean isVoicemailNumber) {
    // Set block/spam actions.
    blockReportView.setVisibility(View.GONE);
    blockView.setVisibility(View.GONE);
    unblockView.setVisibility(View.GONE);
    reportNotSpamView.setVisibility(View.GONE);

    if (!canPlaceCallToNumber
        || isVoicemailNumber
        || !BlockedNumberContract.canCurrentUserBlockNumbers(context)
        || PhoneNumberHelper.isEmergencyNumber(context, number, countryIso)) {
      return;
    }

    if (isBlocked) {
      unblockView.setVisibility(View.VISIBLE);
    } else {
      blockView.setVisibility(View.VISIBLE);
    }
  }

  public void setDetailedPhoneDetails(CallDetailsEntries callDetailsEntries) {
    this.callDetailsEntries = callDetailsEntries;
  }

  @Override
  public void onCreateContextMenu(
      final ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    if (TextUtils.isEmpty(number)) {
      return;
    }

    if (callType == CallLog.Calls.VOICEMAIL_TYPE) {
      menu.setHeaderTitle(context.getResources().getText(R.string.voicemail));
    } else {
      menu.setHeaderTitle(
          PhoneNumberUtils.createTtsSpannable(
              BidiFormatter.getInstance().unicodeWrap(number, TextDirectionHeuristics.LTR)));
    }

    menu.add(
            ContextMenu.NONE,
            R.id.context_menu_copy_to_clipboard,
            ContextMenu.NONE,
            R.string.action_copy_number_text)
        .setOnMenuItemClickListener(this);

    // The edit number before call does not show up if any of the conditions apply:
    // 1) Number cannot be called
    // 2) Number is the voicemail number
    // 3) Number is a SIP address

    if (PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation)
        && !callLogCache.isVoicemailNumber(accountHandle, number)
        && !PhoneNumberHelper.isSipNumber(number)) {
      menu.add(
              ContextMenu.NONE,
              R.id.context_menu_edit_before_call,
              ContextMenu.NONE,
              R.string.action_edit_number_before_call)
          .setOnMenuItemClickListener(this);
    }

    boolean isVoicemailNumber = callLogCache.isVoicemailNumber(accountHandle, number);
    boolean canPlaceCallToNumber = PhoneNumberHelper.canPlaceCallsTo(number, numberPresentation);
    if (canPlaceCallToNumber
        && !isVoicemailNumber
        && BlockedNumberContract.canCurrentUserBlockNumbers(context)
            && !PhoneNumberHelper.isEmergencyNumber(context, number, countryIso)) {
      if (isBlocked) {
        menu.add(
                ContextMenu.NONE,
                R.id.context_menu_unblock,
                ContextMenu.NONE,
                R.string.call_log_action_unblock_number)
            .setOnMenuItemClickListener(this);
      } else {
        menu.add(
                ContextMenu.NONE,
                R.id.context_menu_block,
                ContextMenu.NONE,
                R.string.call_log_action_block_number)
            .setOnMenuItemClickListener(this);
      }
    }

    if (callType != CallLog.Calls.VOICEMAIL_TYPE) {
      menu.add(ContextMenu.NONE, R.id.context_menu_delete, ContextMenu.NONE, R.string.delete)
          .setOnMenuItemClickListener(this);
    }
  }

  public interface OnClickListener {

    void onBlockReportSpam(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        ContactSource.Type contactSourceType);

    void onBlock(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        ContactSource.Type contactSourceType);

    void onUnblock(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        ContactSource.Type contactSourceType,
        boolean isSpam);

    void onReportNotSpam(
        String displayNumber,
        String number,
        String countryIso,
        int callType,
        ContactSource.Type contactSourceType);
  }

  private static class DeleteCallTask implements Runnable {
    // Using a weak reference to hold the Context so that there is no memory leak.
    private final WeakReference<Context> contextWeakReference;

    private final String callIdsStr;

    DeleteCallTask(Context context, long[] callIdsArray) {
      this.contextWeakReference = new WeakReference<>(context);
      this.callIdsStr = concatCallIds(callIdsArray);
    }

    // Suppress the lint check here as the user will not be able to see call log entries if
    // permission.WRITE_CALL_LOG is not granted.
    @SuppressLint("MissingPermission")
    @RequiresPermission(value = permission.WRITE_CALL_LOG)
    @Override
    public void run() {
      Context context = contextWeakReference.get();
      if (context == null) {
        return;
      }

      if (callIdsStr != null) {
        context
            .getContentResolver()
            .delete(
                Calls.CONTENT_URI,
                CallLog.Calls._ID + " IN (" + callIdsStr + ")" /* where */,
                null /* selectionArgs */);
        context
            .getContentResolver()
            .notifyChange(Calls.CONTENT_URI, null);
      }
    }

    private String concatCallIds(long[] callIds) {
      if (callIds == null || callIds.length == 0) {
        return null;
      }

      StringBuilder str = new StringBuilder();
      for (long callId : callIds) {
        if (str.length() != 0) {
          str.append(",");
        }
        str.append(callId);
      }

      return str.toString();
    }
  }
}
