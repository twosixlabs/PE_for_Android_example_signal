package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.privatedata.DataRequest;
import android.privatedata.PrivateDataManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import net.sqlcipher.database.SQLiteDatabase;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RecipientDatabase extends Database {

  private static final String TAG = RecipientDatabase.class.getSimpleName();

          static final String TABLE_NAME               = "recipient";
  public  static final String ID                       = "_id";
  private static final String UUID                     = "uuid";
  public  static final String PHONE                    = "phone";
  public  static final String EMAIL                    = "email";
          static final String GROUP_ID                 = "group_id";
  private static final String BLOCKED                  = "blocked";
  private static final String MESSAGE_RINGTONE         = "message_ringtone";
  private static final String MESSAGE_VIBRATE          = "message_vibrate";
  private static final String CALL_RINGTONE            = "call_ringtone";
  private static final String CALL_VIBRATE             = "call_vibrate";
  private static final String NOTIFICATION_CHANNEL     = "notification_channel";
  private static final String MUTE_UNTIL               = "mute_until";
  private static final String COLOR                    = "color";
  private static final String SEEN_INVITE_REMINDER     = "seen_invite_reminder";
  private static final String DEFAULT_SUBSCRIPTION_ID  = "default_subscription_id";
  private static final String MESSAGE_EXPIRATION_TIME  = "message_expiration_time";
  public  static final String REGISTERED               = "registered";
  public  static final String SYSTEM_DISPLAY_NAME      = "system_display_name";
  private static final String SYSTEM_PHOTO_URI         = "system_photo_uri";
  public  static final String SYSTEM_PHONE_TYPE        = "system_phone_type";
  public  static final String SYSTEM_PHONE_LABEL       = "system_phone_label";
  private static final String SYSTEM_CONTACT_URI       = "system_contact_uri";
  private static final String PROFILE_KEY              = "profile_key";
  public  static final String SIGNAL_PROFILE_NAME      = "signal_profile_name";
  private static final String SIGNAL_PROFILE_AVATAR    = "signal_profile_avatar";
  private static final String PROFILE_SHARING          = "profile_sharing";
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
  private static final String FORCE_SMS_SELECTION      = "force_sms_selection";

  private static final String SORT_NAME                = "sort_name";

  private static final String[] RECIPIENT_PROJECTION = new String[] {
      UUID, PHONE, EMAIL, GROUP_ID,
      BLOCKED, MESSAGE_RINGTONE, CALL_RINGTONE, MESSAGE_VIBRATE, CALL_VIBRATE, MUTE_UNTIL, COLOR, SEEN_INVITE_REMINDER, DEFAULT_SUBSCRIPTION_ID, MESSAGE_EXPIRATION_TIME, REGISTERED,
      PROFILE_KEY, SYSTEM_DISPLAY_NAME, SYSTEM_PHOTO_URI, SYSTEM_PHONE_LABEL, SYSTEM_PHONE_TYPE, SYSTEM_CONTACT_URI,
      SIGNAL_PROFILE_NAME, SIGNAL_PROFILE_AVATAR, PROFILE_SHARING, NOTIFICATION_CHANNEL,
      UNIDENTIFIED_ACCESS_MODE,
      FORCE_SMS_SELECTION,
  };

  private static final String[] ID_PROJECTION = new String[] { ID };

  public  static final String[] SEARCH_PROJECTION = new String[] { ID, SYSTEM_DISPLAY_NAME, SIGNAL_PROFILE_NAME, PHONE, EMAIL, SYSTEM_PHONE_LABEL, SYSTEM_PHONE_TYPE, REGISTERED, "IFNULL(" + SYSTEM_DISPLAY_NAME + ", " + SIGNAL_PROFILE_NAME + ") AS " + SORT_NAME };

  private static Address addressFromCursor(Cursor cursor) {
    String  phone   = cursor.getString(cursor.getColumnIndexOrThrow(PHONE));
    String  email   = cursor.getString(cursor.getColumnIndexOrThrow(EMAIL));
    String  groupId = cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID));
    return phone != null ? Address.fromSerialized(phone) : email != null ? Address.fromSerialized(email) : Address.fromSerialized(groupId);
  }

  static final List<String> TYPED_RECIPIENT_PROJECTION = Stream.of(RECIPIENT_PROJECTION)
                                                               .map(columnName -> TABLE_NAME + "." + columnName)
                                                               .toList();

  public enum VibrateState {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    private final int id;

    VibrateState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static VibrateState fromId(int id) {
      return values()[id];
    }
  }

  public enum RegisteredState {
    UNKNOWN(0), REGISTERED(1), NOT_REGISTERED(2);

    private final int id;

    RegisteredState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static RegisteredState fromId(int id) {
      return values()[id];
    }
  }

  public enum UnidentifiedAccessMode {
    UNKNOWN(0), DISABLED(1), ENABLED(2), UNRESTRICTED(3);

    private final int mode;

    UnidentifiedAccessMode(int mode) {
      this.mode = mode;
    }

    public int getMode() {
      return mode;
    }

    public static UnidentifiedAccessMode fromMode(int mode) {
      return values()[mode];
    }
  }

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME + " (" + ID                       + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                            UUID                     + " TEXT UNIQUE DEFAULT NULL, " +
                                            PHONE                    + " TEXT UNIQUE DEFAULT NULL, " +
                                            EMAIL                    + " TEXT UNIQUE DEFAULT NULL, " +
                                            GROUP_ID                 + " TEXT UNIQUE DEFAULT NULL, " +
                                            BLOCKED                  + " INTEGER DEFAULT 0," +
                                            MESSAGE_RINGTONE         + " TEXT DEFAULT NULL, " +
                                            MESSAGE_VIBRATE          + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                                            CALL_RINGTONE            + " TEXT DEFAULT NULL, " +
                                            CALL_VIBRATE             + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                                            NOTIFICATION_CHANNEL     + " TEXT DEFAULT NULL, " +
                                            MUTE_UNTIL               + " INTEGER DEFAULT 0, " +
                                            COLOR                    + " TEXT DEFAULT NULL, " +
                                            SEEN_INVITE_REMINDER     + " INTEGER DEFAULT 0, " +
                                            DEFAULT_SUBSCRIPTION_ID  + " INTEGER DEFAULT -1, " +
                                            MESSAGE_EXPIRATION_TIME  + " INTEGER DEFAULT 0, " +
                                            REGISTERED               + " INTEGER DEFAULT " + RegisteredState.UNKNOWN.getId() + ", " +
                                            SYSTEM_DISPLAY_NAME      + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHOTO_URI         + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHONE_LABEL       + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHONE_TYPE        + " INTEGER DEFAULT -1, " +
                                            SYSTEM_CONTACT_URI       + " TEXT DEFAULT NULL, " +
                                            PROFILE_KEY              + " TEXT DEFAULT NULL, " +
                                            SIGNAL_PROFILE_NAME      + " TEXT DEFAULT NULL, " +
                                            SIGNAL_PROFILE_AVATAR    + " TEXT DEFAULT NULL, " +
                                            PROFILE_SHARING          + " INTEGER DEFAULT 0, " +
                                            UNIDENTIFIED_ACCESS_MODE + " INTEGER DEFAULT 0, " +
                                            FORCE_SMS_SELECTION      + " INTEGER DEFAULT 0);";

  public RecipientDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public RecipientId getOrInsertFromE164(@NonNull String e164) {
    // Insert µPAL here
    // This routine looks up a phone number (ITU E.164 format) in the RecipientDatabase and
    //   either returns a Recipient record or creates a new one if empty.
    // Here we insert the phone-to-name lookup PAL to add the full name from the user's contacts
    //   when a record is being created.
    if (TextUtils.isEmpty(e164)) {
      throw new AssertionError("Phone number cannot be empty.");
    }

    PrivateDataManager pdm = PrivateDataManager.getInstance();

    Bundle recParams = new Bundle();

    PhoneNumberUtil pnu = PhoneNumberUtil.getInstance();
    String searchNumber = e164;
    try {
      Phonenumber.PhoneNumber phoneNumber = pnu.parse(e164, "");
      searchNumber = "" + phoneNumber.getNationalNumber();
    } catch (NumberParseException e) {
      e.printStackTrace();
    }

    recParams.putString("phone", searchNumber);

    final String PE_TAG = "PE_Android";

    final CountDownLatch LATCH = new CountDownLatch(1);
    final StringBuffer RESULT_BUFFER = new StringBuffer("");
    ResultReceiver receiver = new ResultReceiver(null) {
      @Override
      protected void onReceiveResult(int resultCode, Bundle resultData) {
        android.util.Log.d(PE_TAG, "Received result");

        if (resultCode == PrivateDataManager.RESULT_SUCCESS) {
          android.util.Log.d(PE_TAG, "Success!!!");

          StringBuffer outputText = new StringBuffer("Result Bundle: ");
          if (resultData != null) {
            outputText.append("\n");
            for (String key : resultData.keySet()) {
              if (key.equals("name")) {
                RESULT_BUFFER.append(resultData.get(key).toString());
              }
              outputText.append(" ");
              outputText.append(key);
              outputText.append(" = ");
              outputText.append(resultData.get(key).toString());
              outputText.append("\n");
            }
          } else {
            outputText.append("NULL");
          }

          android.util.Log.d(PE_TAG, outputText.toString());
        } else {
          android.util.Log.d(PE_TAG, "Failed!!!");
          RESULT_BUFFER.append("NULL");
        }

        LATCH.countDown();
      }
    };

    final String UPAL = "com.twosixlabs.phonenameupal.NumberToNamePAL";
    if(pdm.getInstalledPALProviders(DataRequest.DataType.CONTACTS).contains(UPAL)) {
      DataRequest recReq = new DataRequest(ApplicationContext.getAppContext(), DataRequest.DataType.CONTACTS, null,
              UPAL, recParams,
              DataRequest.Purpose.TEST("Test"), receiver);

      pdm.requestData(recReq);

      try {
        LATCH.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    } else {
      Log.w(PE_TAG, "uPAL " + UPAL + " not found");
      RESULT_BUFFER.append("NULL");
    }


    // For now print the name
    String contactName = RESULT_BUFFER.toString();
    Log.d(PE_TAG, "Got name == " + contactName);

    SQLiteDatabase db    = databaseHelper.getWritableDatabase();
    String         query = PHONE + " = ?";
    String[]       args  = new String[] { e164 };

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null)) {
      RecipientId recipientId = null;
      if (cursor != null && cursor.moveToFirst()) {
        recipientId = RecipientId.from(cursor.getLong(0));
      } else {


        ContentValues values = new ContentValues();
        values.put(PHONE, e164);

        if(!contactName.equals("NULL")) {
          values.put(SIGNAL_PROFILE_NAME, contactName);
          Log.d(PE_TAG, "Recording name from uPAL");
        }

        long id = db.insert(TABLE_NAME, null, values);
        recipientId = RecipientId.from(id);
      }

      // NOTE(id) Update the database with the name
      if(!contactName.equals("NULL")) {
        ContentValues values = new ContentValues();
        values.put(SIGNAL_PROFILE_NAME, contactName);

        String id = "" + recipientId.toString().split("::")[1];
        String[] whereArgs = new String[]{id};

        db.update(TABLE_NAME, values, ID + "=? AND " + SIGNAL_PROFILE_NAME + " IS NULL", whereArgs);
      }

      return recipientId;
    }
  }

  public RecipientId getOrInsertFromEmail(@NonNull String email) {
    if (TextUtils.isEmpty(email)) {
      throw new AssertionError("Email cannot be empty.");
    }

    SQLiteDatabase db    = databaseHelper.getWritableDatabase();
    String         query = EMAIL + " = ?";
    String[]       args  = new String[] { email };

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return RecipientId.from(cursor.getLong(0));
      } else {
        ContentValues values = new ContentValues();
        values.put(EMAIL, email);
        long id = db.insert(TABLE_NAME, null, values);
        return RecipientId.from(id);
      }
    }
  }

  public RecipientId getOrInsertFromGroupId(@NonNull String groupId) {
    if (TextUtils.isEmpty(groupId)) {
      throw new AssertionError("GroupId cannot be empty.");
    }

    SQLiteDatabase db    = databaseHelper.getWritableDatabase();
    String         query = GROUP_ID + " = ?";
    String[]       args  = new String[] { groupId };

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return RecipientId.from(cursor.getLong(0));
      } else {
        ContentValues values = new ContentValues();
        values.put(GROUP_ID, groupId);
        long id = db.insert(TABLE_NAME, null, values);
        return RecipientId.from(id);
      }
    }
  }

  public Cursor getBlocked() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, ID_PROJECTION, BLOCKED + " = 1",
                          null, null, null, null, null);
  }

  public RecipientReader readerForBlocked(Cursor cursor) {
    return new RecipientReader(context, cursor);
  }

  public RecipientReader getRecipientsWithNotificationChannels() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, ID_PROJECTION, NOTIFICATION_CHANNEL  + " NOT NULL",
                                             null, null, null, null, null);

    return new RecipientReader(context, cursor);
  }

  public @NonNull RecipientSettings getRecipientSettings(@NonNull RecipientId id) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    String         query    = ID + " = ?";
    String[]       args     = new String[] { id.serialize() };

    try (Cursor cursor = database.query(TABLE_NAME, null, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(cursor);
      } else {
        throw new AssertionError("Couldn't find recipient! id: " + id.serialize());
      }
    }
  }

  @NonNull RecipientSettings getRecipientSettings(@NonNull Cursor cursor) {
    long    id                     = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
    Address address                = addressFromCursor(cursor);
    boolean blocked                = cursor.getInt(cursor.getColumnIndexOrThrow(BLOCKED))                == 1;
    String  messageRingtone        = cursor.getString(cursor.getColumnIndexOrThrow(MESSAGE_RINGTONE));
    String  callRingtone           = cursor.getString(cursor.getColumnIndexOrThrow(CALL_RINGTONE));
    int     messageVibrateState    = cursor.getInt(cursor.getColumnIndexOrThrow(MESSAGE_VIBRATE));
    int     callVibrateState       = cursor.getInt(cursor.getColumnIndexOrThrow(CALL_VIBRATE));
    long    muteUntil              = cursor.getLong(cursor.getColumnIndexOrThrow(MUTE_UNTIL));
    String  serializedColor        = cursor.getString(cursor.getColumnIndexOrThrow(COLOR));
    boolean seenInviteReminder     = cursor.getInt(cursor.getColumnIndexOrThrow(SEEN_INVITE_REMINDER)) == 1;
    int     defaultSubscriptionId  = cursor.getInt(cursor.getColumnIndexOrThrow(DEFAULT_SUBSCRIPTION_ID));
    int     expireMessages         = cursor.getInt(cursor.getColumnIndexOrThrow(MESSAGE_EXPIRATION_TIME));
    int     registeredState        = cursor.getInt(cursor.getColumnIndexOrThrow(REGISTERED));
    String  profileKeyString       = cursor.getString(cursor.getColumnIndexOrThrow(PROFILE_KEY));
    String  systemDisplayName      = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME));
    String  systemContactPhoto     = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHOTO_URI));
    String  systemPhoneLabel       = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_PHONE_LABEL));
    String  systemContactUri       = cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_CONTACT_URI));
    String  signalProfileName      = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_NAME));
    String  signalProfileAvatar    = cursor.getString(cursor.getColumnIndexOrThrow(SIGNAL_PROFILE_AVATAR));
    boolean profileSharing         = cursor.getInt(cursor.getColumnIndexOrThrow(PROFILE_SHARING))      == 1;
    String  notificationChannel    = cursor.getString(cursor.getColumnIndexOrThrow(NOTIFICATION_CHANNEL));
    int     unidentifiedAccessMode = cursor.getInt(cursor.getColumnIndexOrThrow(UNIDENTIFIED_ACCESS_MODE));
    boolean forceSmsSelection      = cursor.getInt(cursor.getColumnIndexOrThrow(FORCE_SMS_SELECTION))  == 1;

    MaterialColor color;
    byte[] profileKey = null;

    try {
      color = serializedColor == null ? null : MaterialColor.fromSerialized(serializedColor);
    } catch (MaterialColor.UnknownColorException e) {
      Log.w(TAG, e);
      color = null;
    }

    if (profileKeyString != null) {
      try {
        profileKey = Base64.decode(profileKeyString);
      } catch (IOException e) {
        Log.w(TAG, e);
        profileKey = null;
      }
    }

    return new RecipientSettings(RecipientId.from(id), address, blocked, muteUntil,
                                 VibrateState.fromId(messageVibrateState),
                                 VibrateState.fromId(callVibrateState),
                                 Util.uri(messageRingtone), Util.uri(callRingtone),
                                 color, seenInviteReminder,
                                 defaultSubscriptionId, expireMessages,
                                 RegisteredState.fromId(registeredState),
                                 profileKey, systemDisplayName, systemContactPhoto,
                                 systemPhoneLabel, systemContactUri,
                                 signalProfileName, signalProfileAvatar, profileSharing,
                                 notificationChannel, UnidentifiedAccessMode.fromMode(unidentifiedAccessMode),
                                 forceSmsSelection);
  }

  public BulkOperationsHandle resetAllSystemContactInfo() {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SYSTEM_DISPLAY_NAME, (String)null);
    contentValues.put(SYSTEM_PHOTO_URI, (String)null);
    contentValues.put(SYSTEM_PHONE_LABEL, (String)null);
    contentValues.put(SYSTEM_CONTACT_URI, (String)null);

    database.update(TABLE_NAME, contentValues, null, null);

    return new BulkOperationsHandle(database);
  }

  public void setColor(@NonNull RecipientId id, @NonNull MaterialColor color) {
    ContentValues values = new ContentValues();
    values.put(COLOR, color.serialize());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setDefaultSubscriptionId(@NonNull RecipientId id, int defaultSubscriptionId) {
    ContentValues values = new ContentValues();
    values.put(DEFAULT_SUBSCRIPTION_ID, defaultSubscriptionId);
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setForceSmsSelection(@NonNull RecipientId id, boolean forceSmsSelection) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(FORCE_SMS_SELECTION, forceSmsSelection ? 1 : 0);
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public void setBlocked(@NonNull RecipientId id, boolean blocked) {
    ContentValues values = new ContentValues();
    values.put(BLOCKED, blocked ? 1 : 0);
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setMessageRingtone(@NonNull RecipientId id, @Nullable Uri notification) {
    ContentValues values = new ContentValues();
    values.put(MESSAGE_RINGTONE, notification == null ? null : notification.toString());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setCallRingtone(@NonNull RecipientId id, @Nullable Uri ringtone) {
    ContentValues values = new ContentValues();
    values.put(CALL_RINGTONE, ringtone == null ? null : ringtone.toString());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setMessageVibrate(@NonNull RecipientId id, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(MESSAGE_VIBRATE, enabled.getId());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setCallVibrate(@NonNull RecipientId id, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(CALL_VIBRATE, enabled.getId());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setMuted(@NonNull RecipientId id, long until) {
    ContentValues values = new ContentValues();
    values.put(MUTE_UNTIL, until);
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setSeenInviteReminder(@NonNull RecipientId id, @SuppressWarnings("SameParameterValue") boolean seen) {
    ContentValues values = new ContentValues(1);
    values.put(SEEN_INVITE_REMINDER, seen ? 1 : 0);
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setExpireMessages(@NonNull RecipientId id, int expiration) {
    ContentValues values = new ContentValues(1);
    values.put(MESSAGE_EXPIRATION_TIME, expiration);
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setUnidentifiedAccessMode(@NonNull RecipientId id, @NonNull UnidentifiedAccessMode unidentifiedAccessMode) {
    ContentValues values = new ContentValues(1);
    values.put(UNIDENTIFIED_ACCESS_MODE, unidentifiedAccessMode.getMode());
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setProfileKey(@NonNull RecipientId id, @Nullable byte[] profileKey) {
    ContentValues values = new ContentValues(1);
    values.put(PROFILE_KEY, profileKey == null ? null : Base64.encodeBytes(profileKey));
    update(id, values);
    Recipient.live(id).refresh();
  }

  public void setProfileName(@NonNull RecipientId id, @Nullable String profileName) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SIGNAL_PROFILE_NAME, profileName);
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public void setProfileAvatar(@NonNull RecipientId id, @Nullable String profileAvatar) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SIGNAL_PROFILE_AVATAR, profileAvatar);
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public void setProfileSharing(@NonNull RecipientId id, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PROFILE_SHARING, enabled ? 1 : 0);
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public void setNotificationChannel(@NonNull RecipientId id, @Nullable String notificationChannel) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(NOTIFICATION_CHANNEL, notificationChannel);
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public Set<Address> getAllAddresses() {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    Set<Address>   results = new HashSet<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { ID, UUID, PHONE, EMAIL, GROUP_ID }, null, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(addressFromCursor(cursor));
      }
    }

    return results;
  }

  public void setRegistered(@NonNull RecipientId id, RegisteredState registeredState) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());
    update(id, contentValues);
    Recipient.live(id).refresh();
  }

  public void setRegistered(@NonNull List<RecipientId> activeIds,
                            @NonNull List<RecipientId> inactiveIds)
  {
    for (RecipientId activeId : activeIds) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(REGISTERED, RegisteredState.REGISTERED.getId());

      update(activeId, contentValues);
      Recipient.live(activeId).refresh();
    }

    for (RecipientId inactiveId : inactiveIds) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());

      update(inactiveId, contentValues);
      Recipient.live(inactiveId).refresh();
    }
  }

  public List<RecipientId> getRegistered() {
    SQLiteDatabase    db      = databaseHelper.getReadableDatabase();
    List<RecipientId> results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, REGISTERED + " = ?", new String[] {"1"}, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      }
    }

    return results;
  }

  public List<RecipientId> getSystemContacts() {
    SQLiteDatabase    db      = databaseHelper.getReadableDatabase();
    List<RecipientId> results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      }
    }

    return results;
  }

  public void updateSystemContactColors(@NonNull ColorUpdater updater) {
    SQLiteDatabase                  db      = databaseHelper.getReadableDatabase();
    Map<RecipientId, MaterialColor> updates = new HashMap<>();

    db.beginTransaction();
    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ID, COLOR, SYSTEM_DISPLAY_NAME}, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        long          id       = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        MaterialColor newColor = updater.update(cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME)),
                                                cursor.getString(cursor.getColumnIndexOrThrow(COLOR)));

        ContentValues contentValues = new ContentValues(1);
        contentValues.put(COLOR, newColor.serialize());
        db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] { String.valueOf(id) });

        updates.put(RecipientId.from(id), newColor);
      }
    } finally {
      db.setTransactionSuccessful();
      db.endTransaction();

      Stream.of(updates.entrySet()).forEach(entry -> Recipient.live(entry.getKey()).refresh());
    }
  }
  public @Nullable Cursor getSignalContacts() {
    String   selection = BLOCKED         + " = ? AND " +
                         REGISTERED      + " = ? AND " +
                         GROUP_ID        + " IS NULL AND " +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + PROFILE_SHARING + " = ?) AND " +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + SIGNAL_PROFILE_NAME + " NOT NULL)";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), "1" };
    String   orderBy   = SORT_NAME + ", " + SYSTEM_DISPLAY_NAME + ", " + SIGNAL_PROFILE_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor querySignalContacts(@NonNull String query) {
    query = TextUtils.isEmpty(query) ? "*" : query;
    query = "%" + query + "%";

    String   selection = BLOCKED         + " = ? AND " +
                         REGISTERED      + " = ? AND " +
                         GROUP_ID        + " IS NULL AND " +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + PROFILE_SHARING + " = ?) AND " +
                         "(" +
                           PHONE               + " LIKE ? OR " +
                           SYSTEM_DISPLAY_NAME + " LIKE ? OR " +
                           SIGNAL_PROFILE_NAME + " LIKE ?" +
                         ")";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), "1", query, query, query };
    String   orderBy   = SORT_NAME + ", " + SYSTEM_DISPLAY_NAME + ", " + SIGNAL_PROFILE_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor getNonSignalContacts() {
    String   selection = BLOCKED    + " = ? AND " +
                         REGISTERED + " != ? AND " +
                         GROUP_ID   + " IS NULL AND " +
                         SYSTEM_DISPLAY_NAME + " NOT NULL AND " +
                         "(" + PHONE + " NOT NULL OR " + EMAIL + " NOT NULL)";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()) };
    String   orderBy   = SYSTEM_DISPLAY_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor queryNonSignalContacts(@NonNull String query) {
    query = TextUtils.isEmpty(query) ? "*" : query;
    query = "%" + query + "%";

    String   selection = BLOCKED    + " = ? AND " +
                         REGISTERED + " != ? AND " +
                         GROUP_ID   + " IS NULL AND " +
                         "(" + PHONE + " NOT NULL OR " + EMAIL + " NOT NULL) AND " +
                         "(" +
                           PHONE               + " LIKE ? OR " +
                           SYSTEM_DISPLAY_NAME + " LIKE ?" +
                         ")";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), query, query };
    String   orderBy   = SYSTEM_DISPLAY_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  private void update(@NonNull RecipientId id, ContentValues contentValues) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.update(TABLE_NAME, contentValues, ID + " = ?", new String[] { id.serialize() });
  }

  public class BulkOperationsHandle {

    private final SQLiteDatabase database;

    private final Map<RecipientId, PendingContactInfo> pendingContactInfoMap = new HashMap<>();

    BulkOperationsHandle(SQLiteDatabase database) {
      this.database = database;
    }

    public void setSystemContactInfo(@NonNull RecipientId id,
                                     @Nullable String displayName,
                                     @Nullable String photoUri,
                                     @Nullable String systemPhoneLabel,
                                     int systemPhoneType,
                                     @Nullable String systemContactUri)
    {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(SYSTEM_DISPLAY_NAME, displayName);
      contentValues.put(SYSTEM_PHOTO_URI, photoUri);
      contentValues.put(SYSTEM_PHONE_LABEL, systemPhoneLabel);
      contentValues.put(SYSTEM_PHONE_TYPE, systemPhoneType);
      contentValues.put(SYSTEM_CONTACT_URI, systemContactUri);

      update(id, contentValues);
      pendingContactInfoMap.put(id, new PendingContactInfo(displayName, photoUri, systemPhoneLabel, systemContactUri));
    }

    public void finish() {
      database.setTransactionSuccessful();
      database.endTransaction();

      Stream.of(pendingContactInfoMap.entrySet()).forEach(entry -> Recipient.live(entry.getKey()).refresh());
    }
  }

  public interface ColorUpdater {
    MaterialColor update(@NonNull String name, @Nullable String color);
  }

  public static class RecipientSettings {
    private final RecipientId            id;
    private final Address                address;
    private final boolean                blocked;
    private final long                   muteUntil;
    private final VibrateState           messageVibrateState;
    private final VibrateState           callVibrateState;
    private final Uri                    messageRingtone;
    private final Uri                    callRingtone;
    private final MaterialColor          color;
    private final boolean                seenInviteReminder;
    private final int                    defaultSubscriptionId;
    private final int                    expireMessages;
    private final RegisteredState        registered;
    private final byte[]                 profileKey;
    private final String                 systemDisplayName;
    private final String                 systemContactPhoto;
    private final String                 systemPhoneLabel;
    private final String                 systemContactUri;
    private final String                 signalProfileName;
    private final String                 signalProfileAvatar;
    private final boolean                profileSharing;
    private final String                 notificationChannel;
    private final UnidentifiedAccessMode unidentifiedAccessMode;
    private final boolean                forceSmsSelection;

    RecipientSettings(@NonNull RecipientId id,
                      @NonNull Address address, boolean blocked, long muteUntil,
                      @NonNull VibrateState messageVibrateState,
                      @NonNull VibrateState callVibrateState,
                      @Nullable Uri messageRingtone,
                      @Nullable Uri callRingtone,
                      @Nullable MaterialColor color,
                      boolean seenInviteReminder,
                      int defaultSubscriptionId,
                      int expireMessages,
                      @NonNull  RegisteredState registered,
                      @Nullable byte[] profileKey,
                      @Nullable String systemDisplayName,
                      @Nullable String systemContactPhoto,
                      @Nullable String systemPhoneLabel,
                      @Nullable String systemContactUri,
                      @Nullable String signalProfileName,
                      @Nullable String signalProfileAvatar,
                      boolean profileSharing,
                      @Nullable String notificationChannel,
                      @NonNull UnidentifiedAccessMode unidentifiedAccessMode,
                      boolean forceSmsSelection)
    {
      this.id                     = id;
      this.address                = address;
      this.blocked                = blocked;
      this.muteUntil              = muteUntil;
      this.messageVibrateState    = messageVibrateState;
      this.callVibrateState       = callVibrateState;
      this.messageRingtone        = messageRingtone;
      this.callRingtone           = callRingtone;
      this.color                  = color;
      this.seenInviteReminder     = seenInviteReminder;
      this.defaultSubscriptionId  = defaultSubscriptionId;
      this.expireMessages         = expireMessages;
      this.registered             = registered;
      this.profileKey             = profileKey;
      this.systemDisplayName      = systemDisplayName;
      this.systemContactPhoto     = systemContactPhoto;
      this.systemPhoneLabel       = systemPhoneLabel;
      this.systemContactUri       = systemContactUri;
      this.signalProfileName      = signalProfileName;
      this.signalProfileAvatar    = signalProfileAvatar;
      this.profileSharing         = profileSharing;
      this.notificationChannel    = notificationChannel;
      this.unidentifiedAccessMode = unidentifiedAccessMode;
      this.forceSmsSelection      = forceSmsSelection;
    }

    public RecipientId getId() {
      return id;
    }

    public @NonNull Address getAddress() {
      return address;
    }

    public @Nullable MaterialColor getColor() {
      return color;
    }

    public boolean isBlocked() {
      return blocked;
    }

    public long getMuteUntil() {
      return muteUntil;
    }

    public @NonNull VibrateState getMessageVibrateState() {
      return messageVibrateState;
    }

    public @NonNull VibrateState getCallVibrateState() {
      return callVibrateState;
    }

    public @Nullable Uri getMessageRingtone() {
      return messageRingtone;
    }

    public @Nullable Uri getCallRingtone() {
      return callRingtone;
    }

    public boolean hasSeenInviteReminder() {
      return seenInviteReminder;
    }

    public Optional<Integer> getDefaultSubscriptionId() {
      return defaultSubscriptionId != -1 ? Optional.of(defaultSubscriptionId) : Optional.absent();
    }

    public int getExpireMessages() {
      return expireMessages;
    }

    public RegisteredState getRegistered() {
      return registered;
    }

    public @Nullable byte[] getProfileKey() {
      return profileKey;
    }

    public @Nullable String getSystemDisplayName() {
      return systemDisplayName;
    }

    public @Nullable String getSystemContactPhotoUri() {
      return systemContactPhoto;
    }

    public @Nullable String getSystemPhoneLabel() {
      return systemPhoneLabel;
    }

    public @Nullable String getSystemContactUri() {
      return systemContactUri;
    }

    public @Nullable String getProfileName() {
      return signalProfileName;
    }

    public @Nullable String getProfileAvatar() {
      return signalProfileAvatar;
    }

    public boolean isProfileSharing() {
      return profileSharing;
    }

    public @Nullable String getNotificationChannel() {
      return notificationChannel;
    }

    public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
      return unidentifiedAccessMode;
    }

    public boolean isForceSmsSelection() {
      return forceSmsSelection;
    }
  }

  public static class RecipientReader implements Closeable {

    private final Context context;
    private final Cursor  cursor;

    RecipientReader(Context context, Cursor cursor) {
      this.context = context;
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      RecipientId id = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
      return Recipient.resolved(id);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close() {
      cursor.close();
    }
  }

  private static class PendingContactInfo {

    private final String displayName;
    private final String photoUri;
    private final String phoneLabel;
    private final String contactUri;

    private PendingContactInfo(String displayName, String photoUri, String phoneLabel, String contactUri) {
      this.displayName = displayName;
      this.photoUri    = photoUri;
      this.phoneLabel  = phoneLabel;
      this.contactUri  = contactUri;
    }
  }

}
