package com.jwoglom.pumpx2;

import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_CONNECTED_STAGE1_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_CONNECTED_STAGE2_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_CONNECTED_STAGE3_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_CONNECTED_STAGE4_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_CONNECTED_STAGE5_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.PUMP_INVALID_CHALLENGE_INTENT;
import static com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler.UPDATE_TEXT_RECEIVER;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.common.base.Strings;
import com.jwoglom.pumpx2.pump.bluetooth.BluetoothHandler;
import com.jwoglom.pumpx2.pump.PumpState;
import com.jwoglom.pumpx2.pump.bluetooth.CharacteristicUUID;
import com.jwoglom.pumpx2.pump.bluetooth.ServiceUUID;
import com.jwoglom.pumpx2.pump.bluetooth.models.Packet;
import com.jwoglom.pumpx2.pump.bluetooth.TronMessageWrapper;
import com.jwoglom.pumpx2.pump.messages.Message;
import com.jwoglom.pumpx2.pump.messages.Packetize;
import com.jwoglom.pumpx2.pump.messages.builders.CentralChallengeBuilder;
import com.jwoglom.pumpx2.pump.messages.builders.PumpChallengeBuilder;
import com.jwoglom.pumpx2.pump.messages.request.AlarmStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.AlertStatusRequest;
import com.jwoglom.pumpx2.pump.messages.request.ApiVersionRequest;
import com.jwoglom.pumpx2.pump.messages.request.CGMHardwareInfoRequest;
import com.jwoglom.pumpx2.pump.messages.request.ControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.NonControlIQIOBRequest;
import com.jwoglom.pumpx2.pump.messages.request.PumpFeaturesRequest;
import com.jwoglom.pumpx2.pump.messages.request.PumpGlobalsRequest;
import com.jwoglom.pumpx2.pump.messages.request.PumpSettingsRequest;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.WriteType;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int ACCESS_LOCATION_REQUEST = 2;

    private TextView statusText;
    private Button retryConnectButton;
    private Spinner requestMessageSpinner;
    private Button requestSendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        retryConnectButton = findViewById(R.id.retryConnect);
        retryConnectButton.setOnClickListener((view) -> BluetoothHandler.getInstance(getApplicationContext()).startScan());

        requestMessageSpinner = findViewById(R.id.request_message_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.request_message_list_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        requestMessageSpinner.setAdapter(adapter);

        requestSendButton = findViewById(R.id.request_message_send);

        registerReceiver(pumpConnectedStage1Receiver, new IntentFilter(PUMP_CONNECTED_STAGE1_INTENT));
        registerReceiver(pumpConnectedStage2Receiver, new IntentFilter(PUMP_CONNECTED_STAGE2_INTENT));
        registerReceiver(pumpConnectedStage3Receiver, new IntentFilter(PUMP_CONNECTED_STAGE3_INTENT));
        registerReceiver(pumpConnectedStage4Receiver, new IntentFilter(PUMP_CONNECTED_STAGE4_INTENT));
        registerReceiver(pumpConnectedStage5Receiver, new IntentFilter(PUMP_CONNECTED_STAGE5_INTENT));
        registerReceiver(updateTextReceiver, new IntentFilter(UPDATE_TEXT_RECEIVER));
        registerReceiver(pumpConnectedInvalidChallengeReceiver, new IntentFilter(PUMP_INVALID_CHALLENGE_INTENT));
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (getBluetoothManager().getAdapter() != null) {
            if (!isBluetoothEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                checkPermissions();
            }
        } else {
            Timber.e("This device has no Bluetooth hardware");
        }
    }

    private boolean isBluetoothEnabled() {
        BluetoothAdapter bluetoothAdapter = getBluetoothManager().getAdapter();
        if(bluetoothAdapter == null) return false;

        return bluetoothAdapter.isEnabled();
    }

    private void initBluetoothHandler()
    {
        BluetoothHandler.getInstance(getApplicationContext());
    }

    @NotNull
    private BluetoothManager getBluetoothManager() {
        return Objects.requireNonNull((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE),"cannot get BluetoothManager");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pumpConnectedStage1Receiver);
    }


    private final BroadcastReceiver pumpConnectedStage1Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            Timber.d("PUMP STAGE1: triggering pair dialog with address: %s", address);
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("got peripheral object: %s", peripheral.getName());

            String name = intent.getStringExtra("name");

            statusText.setText("Connecting to " + name);
            statusText.postInvalidate();

            triggerPairDialog(name, address);
        }
    };

    private final BroadcastReceiver pumpConnectedStage2Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            Timber.d("PUMP STAGE2: looking for pump peripheral with address: %s", address);
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("got peripheral object: %s", peripheral.getName());
            ArrayList<byte[]> authBytes = new ArrayList<>();

            statusText.setText("Stage2");
            statusText.postInvalidate();


            // Central challenge request
            {
                Message message = CentralChallengeBuilder.create(0);
                byte currentTxId = Packetize.txId.get();
                PumpState.pushRequestMessage(message, currentTxId);
                TronMessageWrapper wrapper = new TronMessageWrapper(message, currentTxId);
                Packetize.txId.increment();
                Timber.d("Central challenge packets: %s", wrapper.packets());

                for (Packet packet : wrapper.packets()) {
                    authBytes.add(packet.build());
                }
            }

            for (byte[] b : authBytes) {
                peripheral.writeCharacteristic(ServiceUUID.PUMP_SERVICE_UUID,
                        CharacteristicUUID.AUTHORIZATION_CHARACTERISTICS,
                        b,
                        WriteType.WITH_RESPONSE);
            }

            Timber.d("Waiting for central challenge response w appInstanceId");


        }
    };

    private final BroadcastReceiver pumpConnectedStage3Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            Timber.d("PUMP STAGE3: looking for pump peripheral with address: %s", address);
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("got peripheral object: %s", peripheral.getName());
            ArrayList<byte[]> authBytes = new ArrayList<>();

            statusText.setText("Stage3");
            statusText.postInvalidate();

            String pairingCode = intent.getStringExtra("pairingCode");
            Timber.i("got pairing code: %s", pairingCode);

            int appInstanceId = intent.getIntExtra("appInstanceId", -1);
            Timber.i("got appInstanceId: %s", appInstanceId);


            String hmacKeyHex = intent.getStringExtra("hmacKey");
            Timber.i("got hmacKey: %s", hmacKeyHex);

            byte[] hmacKey = null;
            try {
                hmacKey = Hex.decodeHex(hmacKeyHex);
            } catch (DecoderException e) {
                Timber.e(e);
                e.printStackTrace();
            }


            // PumpChallengeRequest (2 packets)
            {
                Message message = PumpChallengeBuilder.create(appInstanceId, pairingCode, hmacKey);
                byte currentTxId = Packetize.txId.get();
                PumpState.pushRequestMessage(message, currentTxId);
                TronMessageWrapper wrapper = new TronMessageWrapper(message, currentTxId);
                Packetize.txId.increment();
                Timber.d("Pump challenge packets: %s", wrapper.packets());

                for (Packet packet : wrapper.packets()) {
                    authBytes.add(packet.build());
                }
            }


            for (byte[] b : authBytes) {
                peripheral.writeCharacteristic(ServiceUUID.PUMP_SERVICE_UUID,
                        CharacteristicUUID.AUTHORIZATION_CHARACTERISTICS,
                        b,
                        WriteType.WITH_RESPONSE);
            }

            Timber.i("Waiting for pump challenge response");


//                // Central challenge
//                peripheral.writeCharacteristic(BluetoothHandler.PUMP_SERVICE_UUID,
//                        BluetoothHandler.PUMP_AUTHORIZATION_CHARACTERISTICS,
//                        Hex.decodeHex("000010000a00000001020304050607361a"),
//                        WriteType.WITH_RESPONSE);
//
//                // 2x auth code
//                peripheral.writeCharacteristic(BluetoothHandler.PUMP_SERVICE_UUID,
//                        BluetoothHandler.PUMP_AUTHORIZATION_CHARACTERISTICS,
//                        Hex.decodeHex("010212021600009a6cf6348337f61a47217d6d1d"),
//                        WriteType.WITH_RESPONSE);
//                peripheral.writeCharacteristic(BluetoothHandler.PUMP_SERVICE_UUID,
//                        BluetoothHandler.PUMP_AUTHORIZATION_CHARACTERISTICS,
//                        Hex.decodeHex("00023c0d74a78f44531dc9"),
//                        WriteType.WITH_RESPONSE);
//                // Api version
//                peripheral.writeCharacteristic(BluetoothHandler.PUMP_SERVICE_UUID,
//                        BluetoothHandler.PUMP_CURRENT_STATUS_CHARACTERISTICS,
//                        Hex.decodeHex("0003200300091f"),
//                        WriteType.WITH_RESPONSE);
        }
    };

    private final BroadcastReceiver pumpConnectedStage4Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            Timber.d("PUMP STAGE4: sending version with address: %s", address);
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("got peripheral object: %s", peripheral.getName());

            statusText.setText("Stage4");
            statusText.postInvalidate();

            ArrayList<byte[]> authBytes = new ArrayList<>();

            // ApiVersionRequest
            {
                Message message = new ApiVersionRequest();
                byte currentTxId = Packetize.txId.get();
                PumpState.pushRequestMessage(message, currentTxId);
                TronMessageWrapper wrapper = new TronMessageWrapper(message, currentTxId);
                Packetize.txId.increment();
                Timber.d("ApiVersion packets: %s", wrapper.packets());

                for (Packet packet : wrapper.packets()) {
                    authBytes.add(packet.build());
                }
            }

            for (byte[] b : authBytes) {
                peripheral.writeCharacteristic(ServiceUUID.PUMP_SERVICE_UUID,
                        CharacteristicUUID.CURRENT_STATUS_CHARACTERISTICS,
                        b,
                        WriteType.WITH_RESPONSE);
            }

            Timber.i("Waiting for version response");
        }
    };

    private final BroadcastReceiver pumpConnectedStage5Receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            Timber.d("PUMP STAGE5: done with address: %s", address);
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("got peripheral object: %s", peripheral.getName());

            statusText.setText("Connected to pump!");
            statusText.postInvalidate();

            requestMessageSpinner.setVisibility(View.VISIBLE);
            requestMessageSpinner.postInvalidate();


            requestSendButton.setVisibility(View.VISIBLE);
            requestSendButton.setOnClickListener((z) -> {
                switch (requestMessageSpinner.getSelectedItem().toString()) {
                    case "AlarmStatusRequest":
                        writePumpMessage(new AlarmStatusRequest(), peripheral);
                        break;

                    case "AlertStatusRequest":
                        writePumpMessage(new AlertStatusRequest(), peripheral);
                        break;

                    case "CGMHardwareInfoRequest":
                        writePumpMessage(new CGMHardwareInfoRequest(), peripheral);
                        break;

                    case "ControlIQIOBRequest":
                        writePumpMessage(new ControlIQIOBRequest(), peripheral);
                        break;

                    case "NonControlIQIOBRequest":
                        writePumpMessage(new NonControlIQIOBRequest(), peripheral);
                        break;

                    case "PumpFeaturesRequest":
                        writePumpMessage(new PumpFeaturesRequest(), peripheral);
                        break;

                    case "PumpGlobalsRequest":
                        writePumpMessage(new PumpGlobalsRequest(), peripheral);
                        break;

                    case "PumpSettingsRequest":
                        writePumpMessage(new PumpSettingsRequest(), peripheral);
                        break;
                }
            });
            requestSendButton.postInvalidate();
        }
    };

    private final BroadcastReceiver pumpConnectedInvalidChallengeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String address = intent.getStringExtra("address");
            BluetoothPeripheral peripheral = getPeripheral(address);
            Timber.d("Invalid challenge: %s", peripheral.getName());

            PumpState.failedPumpConnectionAttempts++;

            new AlertDialog.Builder(context)
                    .setTitle("Pump Connection")
                    .setMessage("The pump rejected the pairing code. You need to unpair and re-pair the device in Bluetooth Settings. Press OK to enter the new code.")
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(PUMP_CONNECTED_STAGE1_INTENT);
                            intent.putExtra("address", peripheral.getAddress());
                            intent.putExtra("name", peripheral.getName());
                            context.sendBroadcast(intent);
                        }
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        }
    };

    // Adds the given pump message to the BT device's characteristic write queue.
    private void writePumpMessage(Message message, BluetoothPeripheral peripheral) {
        ArrayList<byte[]> authBytes = new ArrayList<>();
        {
            byte currentTxId = Packetize.txId.get();
            PumpState.pushRequestMessage(message, currentTxId);
            TronMessageWrapper wrapper = new TronMessageWrapper(message, currentTxId);
            Packetize.txId.increment();

            for (Packet packet : wrapper.packets()) {
                authBytes.add(packet.build());
            }
        }

        for (byte[] b : authBytes) {
            peripheral.writeCharacteristic(ServiceUUID.PUMP_SERVICE_UUID,
                    CharacteristicUUID.CURRENT_STATUS_CHARACTERISTICS,
                    b,
                    WriteType.WITH_RESPONSE);
        }
    }


    private final BroadcastReceiver updateTextReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String text = intent.getStringExtra("text");
            statusText.setText(text);
            statusText.postInvalidate();

        }
    };

    private BluetoothCentralManager getCentral() {
        return BluetoothHandler.getInstance(getApplicationContext()).central;
    }

    private BluetoothPeripheral getPeripheral(String peripheralAddress) {
        return getCentral().getPeripheral(peripheralAddress);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] missingPermissions = getMissingPermissions(getRequiredPermissions());
            if (missingPermissions.length > 0) {
                requestPermissions(missingPermissions, ACCESS_LOCATION_REQUEST);
            } else {
                permissionsGranted();
            }
        }
    }

    private String[] getMissingPermissions(String[] requiredPermissions) {
        List<String> missingPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String requiredPermission : requiredPermissions) {
                if (getApplicationContext().checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(requiredPermission);
                }
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    private String[] getRequiredPermissions() {
        int targetSdkVersion = getApplicationInfo().targetSdkVersion;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetSdkVersion >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        } else return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    private void permissionsGranted() {
        // Check if Location services are on because they are required to make scanning work for SDK < 31
        int targetSdkVersion = getApplicationInfo().targetSdkVersion;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && targetSdkVersion < Build.VERSION_CODES.S) {
            if (checkLocationServices()) {
                initBluetoothHandler();
            }
        } else {
            initBluetoothHandler();
        }
    }

    private boolean areLocationServicesEnabled() {
        LocationManager locationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Timber.e("could not get location manager");
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        } else {
            boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            return isGpsEnabled || isNetworkEnabled;
        }
    }

    private boolean checkLocationServices() {
        if (!areLocationServicesEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Location services are not enabled")
                    .setMessage("Scanning for Bluetooth peripherals requires locations services to be enabled.") // Want to enable?
                    .setPositiveButton("Enable", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.cancel();
                            startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // if this button is clicked, just close
                            // the dialog box and do nothing
                            dialog.cancel();
                        }
                    })
                    .create()
                    .show();
            return false;
        } else {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Check if all permission were granted
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            permissionsGranted();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Permission is required for scanning Bluetooth peripherals")
                    .setMessage("Please grant permissions")
                    .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialogInterface, int i) {
                            dialogInterface.cancel();
                            checkPermissions();
                        }
                    })
                    .create()
                    .show();
        }
    }

    private void triggerPairDialog(String btName, String btAddress) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter pairing code (case-sensitive)");
        builder.setMessage("Enter the pairing code from Bluetooth Settings > Pair Device to connect to:\n\n" + btName + " (" + btAddress + ")");

// Set up the input
        final EditText input = new EditText(this);
// Specify the type of input expected; this, for example, sets the input as a password, and will mask the text
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);

        String savedPairingCode = PumpState.getPairingCode(getApplicationContext());
        if (!Strings.isNullOrEmpty(savedPairingCode)) {
            input.setText(savedPairingCode);

            if (PumpState.failedPumpConnectionAttempts == 0) {
                triggerImmediatePair(btAddress, savedPairingCode);
                return;
            }
        }
        builder.setView(input);

// Set up the buttons
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String pairingCode = input.getText().toString();
                Timber.i("pairing code inputted: %s", pairingCode);

                triggerImmediatePair(btAddress, pairingCode);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void triggerImmediatePair(String btAddress, String pairingCode) {
        Intent intent = new Intent(PUMP_CONNECTED_STAGE2_INTENT);
        intent.putExtra("address", btAddress);
        PumpState.setPairingCode(getApplicationContext(), pairingCode);
        PumpState.authenticationKey = pairingCode;
        intent.putExtra("pairingCode", pairingCode);
        getApplicationContext().sendBroadcast(intent);
    }
}