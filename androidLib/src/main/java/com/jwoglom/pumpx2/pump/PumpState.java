package com.jwoglom.pumpx2.pump;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;

import com.google.common.base.Preconditions;
import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.PacketArrayList;
import com.jwoglom.pumpx2.pump.messages.bluetooth.Characteristic;
import com.jwoglom.pumpx2.pump.messages.bluetooth.CharacteristicUUID;
import com.jwoglom.pumpx2.pump.messages.bluetooth.PumpStateSupplier;
import com.jwoglom.pumpx2.pump.messages.models.ApiVersion;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class PumpState {
    // TODO: Refactor this class to be less hacky.

    static {
        PumpStateSupplier.authenticationKey = PumpState::getAuthenticationKey;
        PumpStateSupplier.pumpTimeSinceReset = PumpState::getPumpTimeSinceReset;
        PumpStateSupplier.pumpApiVersion = PumpState::getPumpAPIVersion;
        PumpStateSupplier.actionsAffectingInsulinDeliveryEnabled = PumpState::actionsAffectingInsulinDeliveryEnabled;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("PumpState", Context.MODE_PRIVATE);
    }

    // The pairing code is also called the authentication key
    private static final String PAIRING_CODE_PREF = "pairingCode";
    public static String savedAuthenticationKey = null;
    public static void setPairingCode(Context context, String pairingCode) {
        prefs(context).edit().putString(PAIRING_CODE_PREF, pairingCode).apply();
        savedAuthenticationKey = pairingCode;
    }

    public static String getPairingCode(Context context) {
        return prefs(context).getString(PAIRING_CODE_PREF, null);
    }

    public static String getAuthenticationKey() {
        return savedAuthenticationKey;
    }

    // This is used during packet generation for signed messages,
    // and is filled by calling TimeSinceResetRequest
    public static Long pumpTimeSinceReset = null;
    // This is the time at which pumpTimeSinceReset was fetched
    public static Long selfTimeSinceReset = null;

    public static Long getPumpTimeSinceReset() {
        return pumpTimeSinceReset;
    }

    public static void setPumpTimeSinceReset(long time) {
        pumpTimeSinceReset = time;
        selfTimeSinceReset = System.currentTimeMillis();
    }

    public static int failedPumpConnectionAttempts = 0;


    // The most recent Bluetooth MAC of the connected pump
    private static final String SAVED_BLUETOOTH_MAC_PREF = "savedBluetoothMAC";
    public static void setSavedBluetoothMAC(Context context, String bluetoothMAC) {
        prefs(context).edit().putString(SAVED_BLUETOOTH_MAC_PREF, bluetoothMAC).apply();
    }

    public static String getSavedBluetoothMAC(Context context) {
        return prefs(context).getString(SAVED_BLUETOOTH_MAC_PREF, null);
    }


    // The (major, minor) pump version returned from ApiVersionResponse
    private static ApiVersion pumpApiVersion;
    public static void setPumpAPIVersion(ApiVersion apiVersion) {
        pumpApiVersion = apiVersion;
    }

    public static ApiVersion getPumpAPIVersion() {
        return pumpApiVersion;
    }

    // The state of recent messages sent to the pump paired with the transaction id.
    private static final Map<Pair<Characteristic, Byte>, Pair<Boolean, Message>> requestMessages = new HashMap<>();
    public static synchronized void pushRequestMessage(Message m, byte txId) {
        Characteristic c = Characteristic.of(CharacteristicUUID.determine(m));
        Pair<Characteristic, Byte> key = Pair.create(c, txId);
        Preconditions.checkState(!requestMessages.containsKey(key));
        requestMessages.put(key, Pair.create(false, m));
    }

    public static synchronized Optional<Message> readRequestMessage(Characteristic c, byte txId) {
        Pair<Boolean, Message> pair = requestMessages.get(Pair.create(c, txId));
        if (pair == null) {
            return Optional.empty();
        }
        return Optional.of(pair.second);
    }

    public static synchronized void finishRequestMessage(Characteristic c, byte txId) {
        Pair<Characteristic, Byte> key = Pair.create(c, txId);
        Pair<Boolean, Message> pair = requestMessages.get(key);
        Preconditions.checkState(pair != null, "could not find requestMessage for txId "+txId+" and char "+c);
        Preconditions.checkState(!pair.first, "txId "+txId+" was already processed for char "+c);
        requestMessages.put(key, Pair.create(true, pair.second));
    }

    private static final Map<Pair<Characteristic, Byte>, PacketArrayList> savedPacketArrayList = new HashMap<>();
    public static synchronized void savePacketArrayList(Characteristic c, byte txId, PacketArrayList l) {
        Pair<Characteristic, Byte> key = Pair.create(c, txId);
        Preconditions.checkState(!savedPacketArrayList.containsKey(key));
        savedPacketArrayList.put(key, l);
    }

    public static synchronized Optional<PacketArrayList> checkForSavedPacketArrayList(Characteristic c, byte txId) {
        return Optional.ofNullable(savedPacketArrayList.get(Pair.create(c, txId)));
    }

    private static boolean actionsAffectingInsulinDeliveryEnabled = false;
    public static boolean actionsAffectingInsulinDeliveryEnabled() {
        return actionsAffectingInsulinDeliveryEnabled;
    }

    public static void enableActionsAffectingInsulinDelivery() {
        actionsAffectingInsulinDeliveryEnabled = true;
    }

    /**
     * When set, TandemPump will not send authentication messages on start.
     */
    public static boolean tconnectAppAlreadyAuthenticated = false;
}

